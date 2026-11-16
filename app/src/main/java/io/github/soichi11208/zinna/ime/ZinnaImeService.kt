package io.github.soichi11208.zinna.ime

import android.content.Context
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import io.github.soichi11208.zinna.R
import io.github.soichi11208.zinna.keyboard.CandidateStripView
import io.github.soichi11208.zinna.keyboard.FlickDirection
import io.github.soichi11208.zinna.keyboard.FlickGuideStyle
import io.github.soichi11208.zinna.keyboard.FlickKeyboardView
import io.github.soichi11208.zinna.keyboard.InputStyle
import io.github.soichi11208.zinna.keyboard.KeyAction
import io.github.soichi11208.zinna.keyboard.KeyOutput
import io.github.soichi11208.zinna.keyboard.KeySpec
import io.github.soichi11208.zinna.keyboard.KeyboardLayout
import io.github.soichi11208.zinna.keyboard.SplitLayout
import io.github.soichi11208.zinna.keyboard.KeyboardPanelView
import io.github.soichi11208.zinna.keyboard.SymbolPanelView
import io.github.soichi11208.zinna.keyboard.Anim
import io.github.soichi11208.zinna.keyboard.LayoutRepository
import io.github.soichi11208.zinna.settings.ImeSettings
import io.github.soichi11208.zinna.settings.SettingsActivity
import io.github.soichi11208.zinna.settings.UserDictionaryActivity
import io.github.soichi11208.zinna.keyboard.KeyboardMenuView
import android.content.Intent
import io.github.soichi11208.zinna.theme.KeyboardTheme
import io.github.soichi11208.zinna.theme.MaterialYouTheme
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as MozcContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The input method itself.
 *
 * Responsibilities are kept narrow on purpose: translate [KeyOutput]s into mozc calls, and push
 * mozc's resulting [MozcSession.State] into the editor and the candidate strip. Flick geometry
 * lives in [FlickKeyboardView], conversion lives in mozc, and layout/theme data lives in
 * [LayoutRepository].
 */
class ZinnaImeService : InputMethodService() {

    private lateinit var repository: LayoutRepository
    private lateinit var settings: ImeSettings
    private lateinit var session: ConversionSession
    private lateinit var zenzai: ZenzaiEngine
    private var zenzaiCandidate: MozcSession.Candidate? = null
    private var zenzaiDictionary = emptyList<io.github.soichi11208.zinna.mozc.UserDictionary.Entry>()
    private val conversionSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "zenzai_plus_enabled" || key == "zenzai_mozc_enabled") {
            if (isComposing) render(session.submit())
            cancelZenzai()
            session.setZenzaiEnabled(settings.zenzaiPlusEnabled, settings.zenzaiMozcEnabled)
            if (settings.zenzaiPlusEnabled && settings.zenzaiMozcEnabled) {
                io.github.soichi11208.zinna.mozc.UserDictionary(this).sync()
            }
            zenzai.setEnabled(settings.zenzaiPlusEnabled)
            lastState = null
            isComposing = false
            if (settings.zenzaiPlusEnabled) zenzai.syncDictionary { zenzaiDictionary = it }
            else zenzaiDictionary = emptyList()
            showIdleActions()
        } else if (key == io.github.soichi11208.zinna.mozc.UserDictionary.REVISION_KEY && settings.zenzaiPlusEnabled) {
            zenzai.syncDictionary { zenzaiDictionary = it }
        }
    }

    private var keyboardView: FlickKeyboardView? = null
    private var candidateView: CandidateStripView? = null
    private var layout: KeyboardLayout? = null
    private var theme: KeyboardTheme = KeyboardTheme.Default

    /**
     * Whether mozc currently holds a composition. Mirrors the last rendered state so Enter can
     * decide between 確定 and the editor's action *before* it submits and destroys the evidence.
     */
    private var isComposing = false

    /** [ImeSettings.revision] the current input view was built from. */
    private var builtFromRevision = Int.MIN_VALUE

    private val clipboard = ClipboardHistory()
    private var clipboardManager: ClipboardManager? = null
    private enum class StripContent { IDLE_ACTIONS, CANDIDATES, CLIPBOARD }
    private var stripContent = StripContent.IDLE_ACTIONS
    private val clipboardUiHandler = Handler(Looper.getMainLooper())
    private val clipboardPoller = Executors.newSingleThreadScheduledExecutor()
    private var clipboardPollingActive = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (clipboard.record(clipboardManager)) refreshClipboardDisplay()
    }

    private fun refreshClipboardDisplay() {
        // Composition can still exist while the strip shows actions or clipboard history.
        // Refresh what is actually displayed, even if the input window is temporarily hidden.
        when (stripContent) {
            StripContent.CLIPBOARD -> showClipboard()
            StripContent.IDLE_ACTIONS -> showIdleActions()
            StripContent.CANDIDATES -> Unit
        }
    }

    /** The table key the previous keystroke sent, for [endTogglingIfRepeat]. */
    private var lastTableKey: String? = null

    /**
     * Whether [MozcSession.clientContext] still describes where the cursor actually is.
     *
     * Reading the text around the cursor is a round trip to the edited app, so it is not something
     * to do per keystroke. It only has to be right when a composition *starts* — that is the moment
     * mozc rebuilds its history from it — and the cursor cannot move under us while one is in
     * progress without [onUpdateSelection] saying so.
     */
    private var clientContextValid = false

    /** What the strip is worth when it is not covering the keyboard. */
    private var collapsedStripHeight = 0

    /** The last state mozc rendered, so the expand chip can redraw from it. */
    private var lastState: MozcSession.State? = null

    /**
     * The system-bar insets last dispatched to us.
     *
     * Kept because a view built mid-session may never be handed them again: the window's insets
     * have not changed, so nothing re-dispatches, and a fresh panel would sit at zero padding with
     * its bottom row under the navigation bar. Seeding from these makes the rebuilt view correct on
     * its first frame instead of waiting for a dispatch that may not come.
     */
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0

    override fun onCreate() {
        super.onCreate()
        repository = LayoutRepository(this)
        settings = ImeSettings(this)
        session = ConversionSession(this)
        zenzai = ZenzaiEngine(this) {
            Toast.makeText(this, "AI変換でエラーが起きました。ふつうの変換はそのまま使えます", Toast.LENGTH_LONG).show()
        }
        getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(conversionSettingsListener)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        clipboardPollingActive = true
        val manager = clipboardManager
        // Capture while the keyboard is hidden too. Clipboard IPC runs off the UI thread so
        // missing notifications or a slow clipboard service cannot delay keystrokes.
        clipboardPoller.scheduleWithFixedDelay({
            val text = ClipboardHistory.readText(manager)
            if (text != null) clipboardUiHandler.post {
                if (clipboardPollingActive && clipboard.add(text)) refreshClipboardDisplay()
            }
        }, 0, 250, TimeUnit.MILLISECONDS)
        if (!session.isAvailable) {
            // Without the native engine there is nothing useful to do; the keyboard still renders
            // so the user can switch away rather than being stuck with a dead input field.
            Log.e(TAG, "mozc engine unavailable — conversion disabled")
        }
    }

    override fun onCreateInputView(): View {
        val loaded = repository.loadLayout(settings.keyboardStyle.defaultLayoutId)
            ?: repository.loadLayout(LayoutRepository.DEFAULT_LAYOUT_ID)
        if (loaded == null) Log.e(TAG, "default layout missing from assets")
        layout = loaded
        theme = resolveTheme()

        val root = KeyboardPanelView(this).apply {
            // The IME window runs edge-to-edge from targetSdk 35, so it extends underneath the
            // navigation bar and the bottom key row ends up beneath the gesture pill. Padding the
            // panel lifts the keys clear while its own background keeps covering the strip behind
            // the bar, so the keyboard still reaches the bottom of the screen.
            setBackgroundColor(this@ZinnaImeService.theme.backgroundColor)
            setBackgroundImage(settings.backgroundImage, settings.backgroundOpacity)
            glass = settings.liquidGlass
            // Start from what we already know, so a rebuild triggered by a settings change is
            // padded before it draws rather than after the next dispatch — which, since the
            // window's insets did not change, may never arrive.
            updatePadding(
                left = systemInsetLeft,
                right = systemInsetRight,
                bottom = systemInsetBottom,
            )
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                systemInsetLeft = bars.left
                systemInsetRight = bars.right
                systemInsetBottom = bars.bottom
                view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
            // And ask for a fresh dispatch once attached, so a stale seed (after a rotation, say)
            // is corrected rather than persisting for the rest of the session.
            doOnAttach { ViewCompat.requestApplyInsets(it) }
        }

        val candidates = CandidateStripView(this).apply {
            theme = this@ZinnaImeService.theme
            listener = CandidateStripView.OnCandidateSelectedListener(::onCandidateSelected)
            // Expanding covers the keyboard rather than squeezing it: a two-row keyboard is not
            // usable, and there is nothing to type while reading the list anyway.
            expandedListener = CandidateStripView.OnExpandedChangeListener { open ->
                keyboardView?.visibility = if (open) View.GONE else View.VISIBLE
                (layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.height =
                        if (open) LinearLayout.LayoutParams.MATCH_PARENT else collapsedStripHeight
                    layoutParams = it
                }
            }
        }
        val stripHeight = (theme.keyHeightDp * 0.8f * resources.displayMetrics.density).toInt()
        collapsedStripHeight = stripHeight

        val keyboard = FlickKeyboardView(this).apply {
            theme = this@ZinnaImeService.theme
            layout = loaded?.let { adaptForWidth(it) }
            guideOverflowTop = stripHeight.toFloat()
            guideStyle = guideStyleFor(loaded)
            listener = FlickKeyboardView.OnKeyOutputListener(::onKeyOutput)
            flickThresholdDp = settings.flickThresholdDp
            holdToClear = settings.holdToClear
            gaugeFillMs = settings.clearChargeMs
            oneHand = effectiveOneHand()
            glass = settings.liquidGlass
            oneHandListener = { action ->
                setOneHand(if (action == 0) 0 else -settings.oneHandMode)
            }
            panelLongPressListener = ::openKeyboardMenu
            clearAllListener = ::clearAll
        }

        root.addView(
            candidates,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight),
        )
        root.addView(
            keyboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        panelRoot = root
        symbolPanel = null
        keyboardMenu = null
        candidateView = candidates
        stripContent = StripContent.IDLE_ACTIONS
        keyboardView = keyboard
        builtFromRevision = settings.revision
        loaded?.let { session.applyInputStyle(effectiveStyle(it.inputStyle)) }
        return root
    }

    /**
     * Material You unless the user has dropped a theme file in.
     *
     * A user override wins because they asked for it explicitly; otherwise we follow the system
     * palette, which also means the keyboard tracks light/dark without a setting.
     */
    private fun resolveTheme(): KeyboardTheme {
        val base = repository.loadTheme(MaterialYouTheme.ID)
            ?: MaterialYouTheme.create(this, forceDark = settings.pureBlack)
        val sized = base.copy(keyHeightDp = base.keyHeightDp * settings.keyHeightScale)
        return if (settings.pureBlack) sized.asPureBlack() else sized
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        zenzai.setEnabled(settings.zenzaiPlusEnabled)
        session.setZenzaiEnabled(settings.zenzaiPlusEnabled, settings.zenzaiMozcEnabled)
        if (settings.zenzaiPlusEnabled) zenzai.syncDictionary { zenzaiDictionary = it }
        else zenzaiDictionary = emptyList()
        cancelZenzai()
        lastState = null
        // Settings live in another process's Activity, so this is the first moment we can notice
        // they changed. Rebuilding only on a revision bump keeps the common case free.
        if (settings.revision != builtFromRevision) {
            setInputView(onCreateInputView())
        }
        applyPrivacy(info)
        if (!restarting) session.resetContext()
        lastTableKey = null
        clientContextValid = false
        isComposing = false
        // Also capture the current clip in case it changed while this service was not running.
        clipboard.record(clipboardManager)
        showIdleActions()
        closeSymbolPanel()
        closeKeyboardMenu()
    }

    /**
     * Rebuilds the keyboard so a light/dark switch or a wallpaper recolour is picked up. The
     * dynamic palette is read at view-creation time, so without this the keyboard keeps the colours
     * it was born with until the IME process restarts.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(onCreateInputView())
    }

    /**
     * The cursor moved, or the app changed the text under us.
     *
     * Two things go stale at once: what mozc was told is to the left of the cursor, and our own
     * belief that a composition is in progress. Neither survives the editor being edited by anyone
     * but us, and a composition that the app has already dropped must not keep Backspace routed
     * into mozc.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        clientContextValid = false
        // candidatesStart < 0 means the editor is no longer showing a composing region.
        if (candidatesStart < 0 && isComposing) {
            cancelZenzai()
            session.resetContext()
            isComposing = false
            lastTableKey = null
            showIdleActions()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        cancelZenzai()
        lastState = null
        // Anything half-composed at this point can no longer be committed anywhere sensible.
        session.resetContext()
        currentInputConnection?.finishComposingText()
        lastTableKey = null
        candidateView?.clear()
        stripContent = StripContent.IDLE_ACTIONS
    }

    override fun onDestroy() {
        getSharedPreferences(ImeSettings.NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(conversionSettingsListener)
        zenzai.close()
        clipboardPollingActive = false
        clipboardPoller.shutdownNow()
        clipboardUiHandler.removeCallbacksAndMessages(null)
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        clipboardManager = null
        session.close()
        super.onDestroy()
    }

    private fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection) {
        toggleTimeout.cancel()
        if (settings.autoBracket && insertBracketPair(output)) {
            lastTableKey = null
            return
        }
        when (val action = output.action) {
            is KeyAction.Input -> {
                refreshClientContext()
                val toggling = isToggleTap(direction)
                if (!toggling) endTogglingIfRepeat(action.text)
                render(session.sendText(action.text, output.label))
                lastTableKey = action.text
                if (toggling) toggleTimeout.schedule()
            }

            // The dakuten/small-kana cycle is a table entry, not a session command: mozc's flick
            // table maps '*' onto "advance the preceding kana one step" (あ→ぁ→あ, は→ば→ぱ→は).
            // Deliberately outside endTogglingIfRepeat: the cycle *is* mozc's toggling, so ending
            // it between two presses of this key resets は→ば→ぱ back to は.
            is KeyAction.ModifyChar -> render(session.sendText(CYCLE_MODIFIER_KEY))

            is KeyAction.InsertSymbol -> handleSymbol(action.text)

            is KeyAction.Undo -> render(session.undo())

            is KeyAction.Backspace -> handleBackspace()

            is KeyAction.Space -> handleSpace()

            is KeyAction.Katakana -> convertToKatakana()

            // With something composing, this converts; with nothing to convert it behaves as
            // Enter. Inserting a space there would be the desktop behaviour and is not what a
            // 確定 key on a phone should do.
            is KeyAction.Convert -> {
                val neural = zenzaiCandidate
                if (isComposing && neural != null) onCandidateSelected(neural)
                else if (isComposing) render(session.sendSpecialKey(KeyEvent.SpecialKey.SPACE))
                else handleEnter()
            }

            is KeyAction.Enter -> handleEnter()

            is KeyAction.MoveCursor -> handleCursorMove(action.delta)

            is KeyAction.SwitchLayout -> switchLayout(action.layoutId)

            // Consumed by the keyboard view, which owns the shift state because it has to draw it.
            // Reaching here would mean the view stopped intercepting it.
            is KeyAction.Shift -> Log.w(TAG, "shift reached the service; view did not consume it")

            is KeyAction.OpenPanel -> openSymbolPanel()

            is KeyAction.ShowImePicker -> {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
        // Anything that is not a character ends the run, so the key after it starts a new one.
        if (output.action !is KeyAction.Input) lastTableKey = null
    }

    /**
     * 連打入力（ケータイ入力）がONで、かなの画面を真ん中タップしたとき true。
     * このときは同じキーを続けて押すと あ→い→う… と変わってほしいので、mozc の連打を止めない。
     */
    private fun isToggleTap(direction: FlickDirection): Boolean =
        settings.toggleInput && direction == FlickDirection.CENTER &&
            layout?.inputStyle == InputStyle.FLICK_HIRAGANA

    /** 連打入力がONなら、かな画面だけ mozc の連打用の表に切り替える。 */
    private fun effectiveStyle(style: InputStyle): InputStyle =
        if (settings.toggleInput && style == InputStyle.FLICK_HIRAGANA) InputStyle.TOGGLE_FLICK_HIRAGANA
        else style

    /**
     * 連打入力で、しばらく手を止めたら文字を確定させるタイマー。
     * これがあるので「ああ」は、あ→少し待つ→あ で打てる（ガラケーと同じ）。
     */
    private val toggleTimeout = object {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val task = Runnable {
            if (isComposing) render(session.stopKeyToggling())
            lastTableKey = null
        }
        fun schedule() { handler.removeCallbacks(task); handler.postDelayed(task, TOGGLE_TIMEOUT_MS) }
        fun cancel() = handler.removeCallbacks(task)
    }

    /**
     * 開き括弧なら閉じ括弧もいっしょに入れて、カーソルを間に置く。入れたら true。
     * かな画面の「(」は全角の「（）」にする（mozc に渡すと全角になるのと同じ）。
     */
    private fun insertBracketPair(output: KeyOutput): Boolean {
        val action = output.action
        if (action !is KeyAction.Input && action !is KeyAction.InsertSymbol) return false
        var open = output.label
        val japanese = layout?.inputStyle == InputStyle.FLICK_HIRAGANA
        if (japanese) open = FULL_WIDTH_OF[open] ?: open
        val close = BRACKET_PAIRS[open] ?: return false
        val ic = currentInputConnection ?: return false
        if (isComposing) render(session.submit())
        ic.beginBatchEdit()
        ic.commitText(open + close, 1)
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (extracted != null) {
            val pos = extracted.startOffset + extracted.selectionStart - close.length
            if (pos >= 0) ic.setSelection(pos, pos)
        }
        ic.endBatchEdit()
        return true
    }

    /**
     * 入力中のひらがなをカタカナにして確定する。
     * mozc の候補に同じカタカナがあればそれを選ぶ（学習も効く）。なければ自分で変換して入れる。
     */
    private fun convertToKatakana() {
        if (!isComposing) return
        val state = lastState ?: return
        val reading = state.preeditReading.ifEmpty { state.preedit }
        val katakana = toKatakana(reading)
        val match = state.candidates.firstOrNull { it.text == katakana }
        if (match != null) {
            onCandidateSelected(match)
            return
        }
        render(session.revert())
        currentInputConnection?.commitText(katakana, 1)
        clientContextValid = false
    }

    private fun toKatakana(text: String): String = buildString {
        for (c in text) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }

    /** ⌫ 長押しのゲージが満タンになったとき：入力欄の文字を全部消す。 */
    private fun clearAll() {
        toggleTimeout.cancel()
        if (isComposing) {
            render(session.revert())
        }
        lastTableKey = null
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(CLEAR_ALL_MAX_CHARS, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(CLEAR_ALL_MAX_CHARS, 0)?.length ?: 0
        val selected = ic.getSelectedText(0)?.length ?: 0
        if (selected > 0) ic.commitText("", 1)
        ic.deleteSurroundingText(before, after)
        ic.endBatchEdit()
        clientContextValid = false
    }

    /**
     * Tells mozc what is to the left of the cursor, so it can convert in context.
     *
     * mozc turns `preceding_text` into history segments the moment a composition begins, and ranks
     * against them. That is the difference between "main" + にまーじしました coming out as
     * mainにマージしました and as main二マージしました — without it every phrase is converted from a
     * standing start, and a short particle loses to whatever homophone has the better unigram cost.
     *
     * Only refreshed when no composition is in progress: that is the only moment mozc reads it, and
     * `getTextBeforeCursor` is an IPC round trip to the app being typed into.
     *
     * Password fields get the type but no text. mozc adjusts its own behaviour for them, and the
     * contents of a password box are not something to feed a converter that learns.
     */
    private fun refreshClientContext() {
        if (clientContextValid && isComposing) return

        val info = currentInputEditorInfo
        val password = isPasswordField(info?.inputType ?: 0)
        val builder = MozcContext.newBuilder().setInputFieldType(
            if (password) MozcContext.InputFieldType.PASSWORD else MozcContext.InputFieldType.NORMAL
        )
        if (!password) {
            val before = currentInputConnection?.getTextBeforeCursor(PRECEDING_TEXT_CHARS, 0)
            if (!before.isNullOrEmpty()) builder.precedingText = before.toString()
        }
        session.clientContext = builder.build()
        clientContextValid = true
    }

    /**
     * mozc must not see, learn from, or convert with what is typed into a password box. The four
     * variations below are every way Android says "this is a password".
     */
    private fun isPasswordField(inputType: Int): Boolean {
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            EditorInfo.TYPE_CLASS_NUMBER -> variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Stops mozc's toggling when the same table key arrives twice in a row.
     *
     * A 12-key plane sends mozc a table key, and the same one twice advances that key's cycle
     * instead of repeating it: "22" gives b rather than aa, and "11" on the symbol plane gives ☆
     * rather than 11. That is how a phone without flick input reaches the other characters — but
     * every one of them is on a flick direction here, so the cycle only ever gets in the way.
     *
     * STOP_KEY_TOGGLING is mozc's own command for it: it settles the pending character so the next
     * press begins a new one. Sent only on a repeat, so an ordinary keystroke still costs one round
     * trip.
     *
     * The dakuten key is deliberately not routed through here. Its cycle は→ば→ぱ *is* mozc's
     * toggling, and ending it between presses drops the character back to は.
     */
    private fun endTogglingIfRepeat(tableKey: String) {
        if (lastTableKey == tableKey) session.stopKeyToggling()
    }

    /**
     * Symbols finalise whatever is composing and then go straight into the editor.
     *
     * They are not conversion input — nobody wants "あ(" offered as a candidate — and committing
     * first means the symbol lands after the kana rather than in the middle of it.
     */
    private fun handleSymbol(text: String) {
        if (isComposing) render(session.submit())
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * Lets Mozc handle conversion or space insertion, with a fallback for unconsumed keys.
     *
     * With an empty composition, a half-width space is returned unconsumed for the client to
     * insert. Rendering that response alone would silently swallow the keystroke.
     */
    private fun handleSpace() {
        if (session.zenzaiEnabled && isComposing) {
            zenzaiCandidate?.let { onCandidateSelected(it); return }
        }
        val state = session.sendSpecialKey(KeyEvent.SpecialKey.SPACE)
        if (state == null || !state.consumed) {
            val fullWidth = layout?.inputStyle?.fullWidthSpace ?: false
            currentInputConnection?.commitText(if (fullWidth) FULL_WIDTH_SPACE else " ", 1)
        }
        render(state)
    }

    private fun handleBackspace() {
        // Route from the state before sending Backspace. Deleting the final composing character
        // returns an empty Mozc state, but that must not turn the same key into an editor delete.
        val hadComposition = isComposing
        val state = session.sendSpecialKey(KeyEvent.SpecialKey.BACKSPACE)
        val ic = currentInputConnection
        if (ic != null) {
            // Asked rather than remembered: the editor moves its own selection — a long-press, a
            // drag of the handles, Select All from its own menu — and only some of that reaches
            // onUpdateSelection. A null answer means the editor will not say, so the ordinary
            // single-character delete stands.
            val hasSelection = !ic.getSelectedText(0).isNullOrEmpty()
            when (BackspaceRouting.editorDeletion(hadComposition, hasSelection)) {
                EditorDeletion.NONE -> Unit
                // Committing empty text is what replaces a selection; deleting around it would not.
                EditorDeletion.SELECTION -> ic.commitText("", 1)
                EditorDeletion.PRECEDING_CHARACTER -> ic.deleteSurroundingText(1, 0)
            }
        }
        render(state)
    }

    /**
     * 確定 when something is composing, otherwise the editor's own Enter.
     *
     * The composing check has to happen *before* submitting — after a successful SUBMIT the
     * composition is by definition gone, so inspecting the resulting state would make every Enter
     * also fire the editor action and send the message you were only trying to finalise.
     */
    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        if (isComposing) {
            zenzaiCandidate?.let {
                onCandidateSelected(it)
                return
            }
            render(session.submit())
            return
        }
        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val target = EnterRouting.targetFor(imeOptions, inputType)

        // Logged because which branch a field lands in is not knowable from here — the descriptor
        // is whatever the app chose to declare. When Enter misbehaves somewhere, this says why.
        Log.d(TAG, "enter: imeOptions=0x${imeOptions.toString(16)} " +
            "inputType=0x${inputType.toString(16)} -> $target")

        when (target) {
            EnterRouting.Target.ACTION ->
                ic.performEditorAction(imeOptions and EditorInfo.IME_MASK_ACTION)

            EnterRouting.Target.NEWLINE -> ic.commitText("\n", 1)

            EnterRouting.Target.KEY_EVENT -> {
                val now = android.os.SystemClock.uptimeMillis()
                val enter = android.view.KeyEvent.KEYCODE_ENTER
                ic.sendKeyEvent(
                    android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, enter, 0)
                )
                ic.sendKeyEvent(
                    android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, enter, 0)
                )
            }
        }
    }

    private fun handleCursorMove(delta: Int) {
        session.stopKeyToggling()
        val special = if (delta < 0) KeyEvent.SpecialKey.LEFT else KeyEvent.SpecialKey.RIGHT
        val state = session.sendSpecialKey(special)
        if (state != null && state.hasComposition) {
            render(state)
            return
        }
        // Outside a composition, move the editor caret instead.
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val target = (extracted.selectionStart + delta).coerceIn(0, extracted.text?.length ?: 0)
        ic.setSelection(target, target)
    }

    private fun switchLayout(layoutId: String) {
        // The layouts point inside their own family; the user's style decides which family the
        // kana and alphabet planes actually come from, so every switch goes through it.
        val resolved = settings.keyboardStyle.resolve(layoutId)
        val next = repository.loadLayout(resolved)
        if (next == null) {
            Log.w(TAG, "layout $resolved not found; staying on ${layout?.id}")
            return
        }
        // Finalise before swapping planes so a half-typed kana is not silently discarded.
        render(session.submit())
        lastTableKey = null
        layout = next
        keyboardView?.layout = adaptForWidth(next)
        keyboardView?.let { Anim.fadeIn(it, from = 0.85f, durationMs = 160L) }
        // Set alongside the layout, not once at view creation: the guide depends on which plane is
        // showing, and switching planes never rebuilds the view.
        keyboardView?.guideStyle = guideStyleFor(next)
        session.applyInputStyle(effectiveStyle(next.inputStyle))
    }

    /**
     * The guide to draw on [layout], which is the user's choice everywhere except the symbol plane.
     *
     * Keyed on the input style rather than the layout id so a hand-written layout gets the same
     * treatment, and so the plain number pad — where no key offers a flick worth previewing — is
     * left out.
     */
    private fun guideStyleFor(layout: KeyboardLayout?): FlickGuideStyle =
        if (settings.showAllDirectionsOnSymbolPlane &&
            layout?.inputStyle == InputStyle.TOGGLE_FLICK_NUMBER
        ) {
            FlickGuideStyle.DIRECTIONS
        } else {
            settings.flickGuideStyle
        }

    /**
     * Moves the caret to where mozc put it inside the composition.
     *
     * `setComposingText` cannot do this itself: its `newCursorPosition` is measured from outside
     * the span — past its end for a positive value, before its start for anything else — so the
     * one place it cannot leave the caret is in the middle. Pressing ◀ over a half-typed word
     * therefore moved mozc's cursor while the editor kept drawing the caret at the end, and the
     * next keystroke appeared somewhere the user was not looking.
     *
     * Only runs when the caret is not already at the end, which is every ordinary keystroke, so
     * typing pays nothing for it. Must follow `endBatchEdit`, or the position read back is the one
     * from before this render.
     */
    private fun placeComposingCaret(ic: InputConnection, state: MozcSession.State) {
        if (state.preedit.isEmpty() || state.preeditCursor >= state.preedit.length) return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        // setComposingText left the caret just past the composition, which is the only fixed point
        // we can measure its start from — the editor never tells us where it put the span.
        val start = extracted.startOffset + extracted.selectionStart - state.preedit.length
        if (start < 0) return
        val target = start + state.preeditCursor
        ic.setSelection(target, target)
    }

    private fun render(state: MozcSession.State?) {
        val ic = currentInputConnection ?: return
        if (state == null) {
            recoverFromLostSession(ic)
            return
        }

        cancelZenzai()

        val hadComposition = isComposing
        if (state.committedText.isNotEmpty() && !state.hasComposition && hadComposition) {
            learnConfirmed(state.committedText)
        }
        val removeOldEditorComposition =
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = hadComposition,
                nextHasComposition = state.hasComposition,
                committedText = state.committedText,
            )
        ic.beginBatchEdit()
        if (state.committedText.isNotEmpty()) {
            ic.commitText(state.committedText, 1)
        }
        if (state.preedit.isEmpty()) {
            if (removeOldEditorComposition) {
                // finishComposingText would confirm the stale one-character span instead.
                ic.commitText("", 1)
            } else {
                ic.finishComposingText()
            }
        } else {
            val styled = SpannableString(state.preedit).apply {
                setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ic.setComposingText(styled, 1)
        }
        ic.endBatchEdit()

        placeComposingCaret(ic, state)

        isComposing = state.hasComposition
        lastState = state

        if (state.candidates.isEmpty()) {
            candidateView?.expanded = false
            showIdleActions()
        } else {
            stripContent = StripContent.CANDIDATES
            candidateView?.setCandidates(zenzaiBaseCandidates(state), state.focusedCandidateIndex)
        }
        requestZenzai(state)
    }

    private fun cancelZenzai() {
        zenzai.cancel()
        zenzaiCandidate = null
    }

    private fun requestZenzai(state: MozcSession.State) {
        if (!ZenzaiConversion.canConvert(
                enabled = settings.zenzaiPlusEnabled,
                composing = state.hasComposition,
                japanese = layout?.inputStyle?.fullWidthSpace == true,
                password = isPasswordField(currentInputEditorInfo?.inputType ?: 0),
            )) return
        val prompt = ZenzaiConversion.prompt(
            state.preeditReading,
            session.clientContext?.precedingText.orEmpty(),
        ) ?: return
        val base = zenzaiBaseCandidates(state)
        zenzai.request(prompt) { candidate ->
            // Candidate taps and editor changes may finish composition while inference runs.
            if (lastState !== state || !isComposing) return@request
            zenzaiCandidate = candidate
            if (stripContent == StripContent.CLIPBOARD) return@request
            val candidates = ZenzaiConversion.candidates(base, candidate)
            stripContent = StripContent.CANDIDATES
            candidateView?.setCandidates(candidates, 0)
        }
    }

    private fun zenzaiBaseCandidates(state: MozcSession.State): List<MozcSession.Candidate> {
        if (!session.zenzaiEnabled) return state.candidates
        val key = ZenzaiConversion.prompt(state.preeditReading, "") ?: return state.candidates
        val registered = zenzaiDictionary.filter { ZenzaiConversion.prompt(it.reading, "") == key }
            .mapIndexed { index, entry -> MozcSession.Candidate(-100 - index, entry.word) }
        return (registered + state.candidates).distinctBy { it.text }
    }

    private fun learnConfirmed(output: String) {
        if (session.incognito) return
        if (!session.zenzaiEnabled || !settings.zenzaiPlusEnabled || isPasswordField(currentInputEditorInfo?.inputType ?: 0) ||
            (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return
        val reading = lastState?.preeditReading.orEmpty()
        zenzai.confirm(reading, session.clientContext?.precedingText.orEmpty(), output)
    }


    /**
     * Puts the keyboard back into a usable state after mozc failed to answer.
     *
     * A null response means the call across JNI did not complete, so what mozc believes is now
     * unknown — but the editor is still showing the composing span from the last successful render,
     * and [isComposing] still says a composition exists. Leaving both is what wedges the keyboard:
     * Backspace routes to mozc because a composition is believed to exist, mozc has nothing to
     * delete, and the key stops doing anything at all.
     *
     * The composition is finished rather than dropped. The characters were typed on purpose, so
     * they stay in the editor as ordinary text; only the ability to keep converting them is lost.
     */
    private fun recoverFromLostSession(ic: android.view.inputmethod.InputConnection) {
        cancelZenzai()
        Log.w(TAG, "no response from mozc; finishing the composition and resetting state")
        if (isComposing) ic.finishComposingText()
        isComposing = false
        lastTableKey = null
        showIdleActions()
    }

    /**
     * Commits a candidate the user tapped.
     *
     * mozc's candidates are selected by id so it can learn from the choice. Anything with an id we
     * made up is not mozc's to select, so it is committed as plain text after telling mozc to
     * finish what it was composing.
     */
    private fun onCandidateSelected(candidate: MozcSession.Candidate) {
        if (candidate.id < 0 && isComposing) learnConfirmed(candidate.text)
        cancelZenzai()
        candidateView?.expanded = false
        if (candidate.id >= 0) {
            render(session.selectCandidate(candidate.id))
            return
        }
        session.revert()
        isComposing = false
        currentInputConnection?.commitText(candidate.text, 1)
        lastTableKey = null
        showIdleActions()
    }

    /**
     * What the strip shows when there is nothing to convert.
     *
     * Undo, and a way into the clipboard — the two things Gboard puts there, and the two that are
     * otherwise unreachable without leaving the keyboard.
     */
    private fun showIdleActions() {
        stripContent = StripContent.IDLE_ACTIONS
        val view = candidateView ?: return
        view.expanded = false
        val actions = mutableListOf<CandidateStripView.Action>()
        if (clipboard.items().isNotEmpty()) {
            actions += CandidateStripView.Action("", R.drawable.ic_clipboard, CLIPBOARD_LABEL) { showClipboard() }
        }
        actions += CandidateStripView.Action("", R.drawable.ic_undo, UNDO_LABEL) { render(session.undo()) }
        actions += CandidateStripView.Action("", R.drawable.ic_one_hand, ONE_HAND_LABEL) {
            setOneHand(if (settings.oneHandMode == 0) settings.oneHandLastSide else 0)
        }
        // 鍵が閉じている＝プライベート中、開いている＝ふつう。
        actions += if (settings.privateMode) {
            CandidateStripView.Action("", R.drawable.ic_lock, PRIVATE_ON_LABEL) { togglePrivateMode() }
        } else {
            CandidateStripView.Action("", R.drawable.ic_lock_open, PRIVATE_OFF_LABEL) { togglePrivateMode() }
        }
        view.setActions(actions)
    }

    /**
     * Swaps the strip over to the stored clippings, each one pasting itself.
     *
     * Labels are cut down to a chip's worth of text; the whole clipping is still what gets pasted.
     */
    private fun showClipboard() {
        val view = candidateView ?: return
        // Opening history must show the current clip even if its change callback is still queued.
        clipboard.record(clipboardManager)
        stripContent = StripContent.CLIPBOARD
        val actions = mutableListOf<CandidateStripView.Action>()
        actions += CandidateStripView.Action(BACK_LABEL) { showIdleActions() }
        for (text in clipboard.items()) {
            actions += CandidateStripView.Action(chipLabel(text)) {
                if (isComposing) render(session.submit())
                currentInputConnection?.commitText(text, 1)
                showIdleActions()
            }
        }
        view.setActions(actions)
        // 押したらすぐ一覧で開く（下に引っぱらなくても選べるように）。
        view.expanded = true
    }

    // ---------------------------------------------------------------- 記号・絵文字パネル

    private var panelRoot: KeyboardPanelView? = null
    private var symbolPanel: SymbolPanelView? = null

    /**
     * キーボードを隠して、同じ高さの記号・絵文字パネルを出す。
     *
     *  [候補バー]          [候補バー]
     *  [キーボード]  →    [記号パネル]
     */
    private fun openSymbolPanel() {
        val root = panelRoot ?: return
        val keyboard = keyboardView ?: return
        if (isComposing) render(session.submit())
        val panel = symbolPanel ?: SymbolPanelView(this, theme, object : SymbolPanelView.Listener {
            override fun isPrivate(): Boolean = session.incognito
            override fun phrases(): List<String> = settings.phrases
            override fun onPanelText(text: String) {
                if (isComposing) render(session.submit())
                currentInputConnection?.commitText(text, 1)
            }
            override fun onPanelBackspace() = handleBackspace()
            override fun onPanelClose() = closeSymbolPanel()
        }).also {
            root.addView(
                it,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyboard.height),
            )
            symbolPanel = it
        }
        (panel.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = keyboard.height
            panel.layoutParams = it
        }
        panel.reset()
        keyboard.visibility = View.GONE
        Anim.fadeIn(panel)
        panel.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- プライベートモード

    /**
     * プライベートモードを mozc に反映する。
     * 自分でONにしたときに加えて、アプリが「学習しないで」と頼んできた入力欄でも自動でONにする。
     */
    private fun applyPrivacy(info: EditorInfo?) {
        val appAsksNoLearning =
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        session.setIncognito(settings.privateMode || appAsksNoLearning)
    }

    private fun togglePrivateMode() {
        settings.privateMode = !settings.privateMode
        if (isComposing) render(session.submit())
        applyPrivacy(currentInputEditorInfo)
        showIdleActions()
    }

    // ---------------------------------------------------------------- 片手モード

    /** 左右分割になっている（Fold を開いている）ときは、片手モードは使わない。 */
    private fun isSplitActive(): Boolean =
        settings.splitWhenWide && resources.configuration.screenWidthDp >= SPLIT_MIN_WIDTH_DP

    private fun effectiveOneHand(): Int = if (isSplitActive()) 0 else settings.oneHandMode

    /** 0 = ふつう、-1 = 左寄せ、1 = 右寄せ。 */
    private fun setOneHand(mode: Int) {
        settings.oneHandMode = mode
        keyboardView?.let {
            it.oneHand = effectiveOneHand()
            Anim.fadeIn(it, from = 0.85f, durationMs = 160L)
        }
    }

    // ---------------------------------------------------------------- 長押しメニュー

    private var keyboardMenu: KeyboardMenuView? = null

    /** 「☺記」長押しで、キーボードの場所にメニューを出す。 */
    private fun openKeyboardMenu() {
        val root = panelRoot ?: return
        val keyboard = keyboardView ?: return
        if (isComposing) render(session.submit())
        val menu = keyboardMenu ?: KeyboardMenuView(this, theme).also {
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyboard.height))
            keyboardMenu = it
        }
        (menu.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = keyboard.height
            menu.layoutParams = it
        }
        menu.setItems(listOf(
            listOf(
                KeyboardMenuView.Item({ "アプリ設定" }) {
                    closeKeyboardMenu()
                    startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                KeyboardMenuView.Item({ "単語登録" }) {
                    closeKeyboardMenu()
                    startActivity(Intent(this, UserDictionaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                KeyboardMenuView.Item({
                    when (settings.oneHandMode) { -1 -> "片手：左"; 1 -> "片手：右"; else -> "片手：オフ" }
                }) {
                    // オフ → 右 → 左 → オフ … と切り替える
                    setOneHand(when (settings.oneHandMode) { 0 -> 1; 1 -> -1; else -> 0 })
                },
            ),
            listOf(
                KeyboardMenuView.Item({ "高さ −" }) { changeKeyHeight(-0.05f) },
                KeyboardMenuView.Item({ "高さ ＋" }) { changeKeyHeight(+0.05f) },
                KeyboardMenuView.Item({ if (settings.splitWhenWide) "左右分割：オン" else "左右分割：オフ" }) {
                    settings.splitWhenWide = !settings.splitWhenWide
                    rebuildKeepingMenu()
                },
            ),
            listOf(KeyboardMenuView.Item({ "閉じる" }) { closeKeyboardMenu() }),
        ))
        symbolPanel?.visibility = View.GONE
        keyboard.visibility = View.GONE
        menu.visibility = View.VISIBLE
        Anim.fadeIn(menu)
    }

    private fun closeKeyboardMenu() {
        keyboardMenu?.visibility = View.GONE
        keyboardView?.let { if (it.visibility != View.VISIBLE) { it.visibility = View.VISIBLE; Anim.fadeIn(it) } }
    }

    private fun changeKeyHeight(delta: Float) {
        val next = (settings.keyHeightScale + delta)
            .coerceIn(ImeSettings.MIN_KEY_HEIGHT_SCALE, ImeSettings.MAX_KEY_HEIGHT_SCALE)
        settings.keyHeightScale = next
        rebuildKeepingMenu()
    }

    /** 設定を変えたあと、キーボードを作り直してメニューを開いたままにする。 */
    private fun rebuildKeepingMenu() {
        keyboardMenu = null
        setInputView(onCreateInputView())
        panelRoot?.post { openKeyboardMenu() }
    }

    // ---------------------------------------------------------------- 左右分割（Fold 展開時）

    /** 横幅が広いとき（Fold を開いたとき）だけ、左右分割の配置に変える。 */
    private fun adaptForWidth(layout: KeyboardLayout): KeyboardLayout =
        if (settings.splitWhenWide && resources.configuration.screenWidthDp >= SPLIT_MIN_WIDTH_DP) {
            SplitLayout.split(layout)
        } else {
            layout
        }

    private fun closeSymbolPanel() {
        val panel = symbolPanel
        if (panel?.visibility == View.VISIBLE) panel.visibility = View.GONE
        keyboardView?.let { if (it.visibility != View.VISIBLE) { it.visibility = View.VISIBLE; Anim.fadeIn(it) } }
    }

    private fun chipLabel(text: String): String {
        val flattened = text.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= CLIP_LABEL_LIMIT) flattened
        else flattened.take(CLIP_LABEL_LIMIT) + "…"
    }

    companion object {
        private const val TAG = "ZinnaImeService"

        /** 連打入力で、この時間（ミリ秒）手を止めたら文字を確定する。 */
        private const val TOGGLE_TIMEOUT_MS = 900L

        /** 全削除で一度に見る文字数の上限。 */
        private const val CLEAR_ALL_MAX_CHARS = 100_000

        private val BRACKET_PAIRS = mapOf(
            "(" to ")", "（" to "）", "「" to "」", "『" to "』", "【" to "】",
            "〈" to "〉", "《" to "》", "〔" to "〕", "［" to "］", "[" to "]",
            "{" to "}", "｛" to "｝", "“" to "”", "‘" to "’",
        )

        private val FULL_WIDTH_OF = mapOf("(" to "（", "[" to "［", "{" to "｛")

        /** '*' in mozc's flick/12-key tables. See data/preedit/flick-hiragana.tsv upstream. */
        private const val CYCLE_MODIFIER_KEY = "*"

        /** U+3000 IDEOGRAPHIC SPACE, what a Japanese input mode types for the space key. */
        private const val FULL_WIDTH_SPACE = "　"

        private const val CLIPBOARD_LABEL = "クリップボード"
        private const val ONE_HAND_LABEL = "片手モード"
        private const val UNDO_LABEL = "元に戻す"
        private const val BACK_LABEL = "✕"
        private const val PRIVATE_OFF_LABEL = "プライベートモード：オフ"
        private const val PRIVATE_ON_LABEL = "プライベートモード：オン"

        /** これ以上の横幅（dp）なら左右分割にする。Fold7 を開くと約 840dp、閉じると約 400dp。 */
        private const val SPLIT_MIN_WIDTH_DP = 600

        /** How much of a clipping fits on a chip before it stops being readable. */
        private const val CLIP_LABEL_LIMIT = 24

        /**
         * How much text to the left mozc is given. It only takes the last connective token out of
         * this, so a short window is enough and keeps the round trip small.
         */
        private const val PRECEDING_TEXT_CHARS = 64
    }
}
