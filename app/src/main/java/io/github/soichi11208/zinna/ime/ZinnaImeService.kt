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
    /** 趣味ボードの間にコピーしたもの。表のボードでは出さない。 */
    private val hobbyClipboard = ClipboardHistory()

    /** コピーされた文字を、今のボード（表／趣味）の履歴に入れる。 */
    private fun addClip(text: String): Boolean =
        if (settings.hobbyMode) hobbyClipboard.add(text)
        // 趣味ボードでコピーしたものが表に流れてこないように
        else if (text in hobbyClipboard.items()) false
        else clipboard.add(text)

    private fun recordClip(): Boolean = ClipboardHistory.readText(clipboardManager)?.let(::addClip) ?: false

    /** 今のボードで見せるクリップボード。趣味ボードは表のものも見られる（一方通行）。 */
    private fun visibleClips(): List<String> =
        if (settings.hobbyMode) (hobbyClipboard.items() + clipboard.items()).distinct() else clipboard.items()
    private var clipboardManager: ClipboardManager? = null
    private enum class StripContent { IDLE_ACTIONS, CANDIDATES, CLIPBOARD }
    private var stripContent = StripContent.IDLE_ACTIONS
    private val clipboardUiHandler = Handler(Looper.getMainLooper())
    private val clipboardPoller = Executors.newSingleThreadScheduledExecutor()
    private var clipboardPollingActive = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (recordClip()) refreshClipboardDisplay()
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

    /** 入力欄のカーソル位置（onUpdateSelection で受け取った最新のもの。わからないときは -1）。 */
    private var selStart = -1
    private var selEnd = -1
    /** 入力中の文字（下線）の始まりの位置。アプリが教えてくれたもの。-1 ＝ わからない。 */
    private var composeStart = -1

    /** What the strip is worth when it is not covering the keyboard. */
    private var collapsedStripHeight = 0

    /** キーボード部分の高さ（4段ぶん）。記号・メニュー・候補一覧などどの画面でもこの高さに固定する。 */
    private var keysHeightPx = 0

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
        EnglishCandidates.preload(this)
        SymbolReadings.preload(this)
        ZipCodes.preload(this)
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
                if (clipboardPollingActive && addClip(text)) refreshClipboardDisplay()
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
            // 趣味ボードは趣味ボード用の背景画像（なければ画像なし）
            if (settings.hobbyMode) setBackgroundImage(settings.hobbyBackgroundImage, settings.hobbyBackgroundOpacity)
            else setBackgroundImage(settings.backgroundImage, settings.backgroundOpacity)
            glass = settings.liquidGlass
            memento = settings.mementoKeys
            mementoStyle = settings.mementoStyle
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
            longPressListener = ::openCandidateMenu
            twoLines = settings.candidateTwoLines
            // Expanding covers the keyboard rather than squeezing it: a two-row keyboard is not
            // usable, and there is nothing to type while reading the list anyway.
            expandedListener = CandidateStripView.OnExpandedChangeListener { open ->
                // 広げても全体の高さは変えない：一覧は「候補バー＋キーボード」と同じ高さにする
                val keysHeight = keysHeightPx
                keyboardView?.visibility = if (open) View.GONE else View.VISIBLE
                (layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.height =
                        if (open && keysHeight > 0) collapsedStripHeight + keysHeight
                        else if (open) LinearLayout.LayoutParams.MATCH_PARENT
                        else collapsedStripHeight
                    layoutParams = it
                }
            }
        }
        // 2行モードは候補バーを2倍の高さにする（キーボードの高さはそのまま）
        val stripHeight = (theme.keyHeightDp * 0.8f * resources.displayMetrics.density *
            (if (settings.candidateTwoLines) 1.7f else 1f)).toInt()
        collapsedStripHeight = stripHeight
        keysHeightPx = (theme.keyHeightDp * resources.displayMetrics.density * KEY_ROWS).toInt()
        // 全体の高さ＝候補バー＋キーボード。中身が何に切り替わってもこの高さのまま。
        root.contentHeight = stripHeight + keysHeightPx

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
            minimal = settings.minimalKeys
            pressShape = settings.pressShape
            haptics.mode = settings.vibrationMode
            haptics.strength = settings.vibrationStrength
            pressColor = settings.pressColor
            memento = settings.mementoKeys
            mementoStyle = settings.mementoStyle
            lensParams = io.github.soichi11208.zinna.keyboard.Lens.Params(
                refract = settings.glassRefract, frost = settings.glassFrost,
                light = settings.glassLight, shadow = settings.glassShadow,
            )
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
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keysHeightPx),
        )

        panelRoot = root
        symbolPanel = null
        keyboardMenu = null
        candidateView = candidates
        stripContent = StripContent.IDLE_ACTIONS
        keyboard.backdropProvider = { root.snapshotBehind(keyboard) }
        keyboard.backdropRevision = { root.backdropRevision }
        keyboardView = keyboard
        keyboard.enterIcon = enterIconFor(currentInputEditorInfo)
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
        val themed = if (settings.pureBlack) sized.asPureBlack() else sized
        // MEMENTO MORI：色は固定（深い青に白い文字。押した時は水色）
        val moon = settings.mementoStyle == 1
        val looked = if (settings.mementoKeys) themed.copy(
            backgroundColor = when (settings.mementoStyle) { 1 -> 0xFF050C1C.toInt(); 3 -> 0xFF041030.toInt(); else -> 0xFF062258.toInt() },
            labelColor = android.graphics.Color.WHITE,
            keyPressedColor = if (moon) 0xFFFFE9A8.toInt() else 0xFF16CFFB.toInt(),
            flickGuideColor = android.graphics.Color.WHITE,
            flickGuideLabelColor = android.graphics.Color.BLACK,
            flickGuideSelectedLabelColor = android.graphics.Color.BLACK,
            candidateBackgroundColor = 0x59020A1F,
            candidateTextColor = android.graphics.Color.WHITE,
        ) else themed
        val custom = applyCustom(looked)
        if (!settings.hobbyMode) return custom
        // 趣味ボード：背景の色（おまかせは深い紫）と文字の色を変える
        return custom.copy(
            backgroundColor = settings.hobbyBg.takeIf { it != 0 } ?: HOBBY_DEFAULT_BG,
            labelColor = settings.hobbyText.takeIf { it != 0 } ?: custom.labelColor,
        )
    }

    /** 設定の「カスタマイズ」タブで変えたもの（文字色・フォント・太さ・大きさ・背景色）を上から重ねる。 */
    private fun applyCustom(t: KeyboardTheme): KeyboardTheme = t.copy(
        labelColor = settings.customKeyText.takeIf { it != 0 } ?: t.labelColor,
        backgroundColor = settings.customKeyboardBg.takeIf { it != 0 } ?: t.backgroundColor,
        labelSizeSp = t.labelSizeSp * settings.customKeyScale,
        keyFont = settings.customKeyFont,
        keyWeight = settings.customKeyWeight,
        candidateTextColor = settings.customCandText.takeIf { it != 0 } ?: t.candidateTextColor,
        candidateFont = settings.customCandFont,
        candidateWeight = settings.customCandWeight,
        candidateLabelSizeSp = t.labelSizeSp * settings.customCandScale,
        candidateFill = settings.customCandBg,
        hapticFeedback = t.hapticFeedback && settings.vibrationMode != 0,
    )

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        learnedMain.reload()
        learnedHobby.reload()
        nextMain.reload()
        nextHobby.reload()
        lastCommitted = null
        forgetDeleted()
        selStart = info?.initialSelStart ?: -1
        selEnd = info?.initialSelEnd ?: -1
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
        keyboardView?.enterIcon = enterIconFor(info)
        // 送信ボタンを押すと、アプリは入力欄を空にして入力をやり直させる（restarting = true）。
        // そのとき mozc の中に打ちかけの文字が残っていると、次の1文字を打った瞬間に
        // 送ったはずのメッセージがそのまま入力欄に戻ってきてしまう。
        //   「こんにちは」入力中 → 送信 → 欄が空に → 「あ」を打つ → 「こんにちはあ」が出る
        // なので、やり直しのときも打ちかけの文字は必ず捨てる（前の文脈だけは残す）。
        toggleTimeout.cancel()
        if (restarting) {
            session.revert()
            // 欄に下線つきの文字が残っていたら、ふつうの文字にしておく（次の入力で上書きされないように）。
            if (isComposing) currentInputConnection?.finishComposingText()
        } else {
            session.resetContext()
        }
        lastTableKey = null
        clientContextValid = false
        isComposing = false
        // Also capture the current clip in case it changed while this service was not running.
        recordClip()
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
        selStart = newSelStart
        selEnd = newSelEnd
        composeStart = candidatesStart
        // 入力中（下線つき）なのに、カーソルがその外へ動かされた（画面を直接タップした等）。
        // そのままだと次の文字が元の場所に入り、カーソルもそこへ飛んでしまうので、ここで確定する。
        //   今日は|晴れ  ← 入力中「はれ」の外をタップ → 「はれ」を確定してから、新しい場所で入力
        if (isComposing && candidatesStart >= 0 &&
            (newSelStart < candidatesStart || newSelEnd > candidatesEnd)
        ) {
            toggleTimeout.cancel()
            cancelZenzai()
            currentInputConnection?.finishComposingText()
            session.resetContext()
            isComposing = false
            lastTableKey = null
            lastState = null
            showIdleActions()
            return
        }
        // candidatesStart < 0 means the editor is no longer showing a composing region.
        // （送信ボタンでアプリが欄を空にしたときもここに来る）
        if (candidatesStart < 0 && isComposing) {
            toggleTimeout.cancel()
            cancelZenzai()
            session.resetContext()
            isComposing = false
            lastTableKey = null
            lastState = null
            showIdleActions()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        toggleTimeout.cancel()
        // mozc が覚えたことを、すぐファイルに書く（アプリが止められても消えないように）
        session.syncLearning()
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
        // ⌫ 以外を押したら、「消した文字を戻す」は忘れる（カーソル移動や画面切り替えは除く）
        when (output.action) {
            is KeyAction.Backspace, is KeyAction.MoveCursor, is KeyAction.SwitchLayout, is KeyAction.Shift,
            is KeyAction.OpenPanel, is KeyAction.OpenKaomoji, is KeyAction.OpenEmoji, is KeyAction.OpenMenu,
            is KeyAction.OpenEdit -> Unit
            else -> forgetDeleted()
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

            is KeyAction.HalfWidth -> convertToHalfWidth()

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

            is KeyAction.OpenKaomoji -> openSymbolPanel(kaomoji = true)

            is KeyAction.OpenEmoji -> openSymbolPanel(emoji = true)

            is KeyAction.OpenMenu -> openKeyboardMenu()

            is KeyAction.OpenEdit -> openEditMenu()

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

    /**
     * 空白キーの上フリック：入力中の文字のうち、全角の記号・英数字だけを半角にして確定する。
     *   （１２：３０）！ → (12:30)!     ひらがな・カタカナはそのまま
     */
    private fun convertToHalfWidth() {
        if (!isComposing) return
        val state = lastState ?: return
        val half = toHalfWidth(state.preedit)
        render(session.revert())
        currentInputConnection?.commitText(half, 1)
        clientContextValid = false
    }

    private fun toHalfWidth(text: String): String = buildString {
        for (c in text) append(
            when (c) {
                in '！'..'～' -> c - 0xFEE0
                '　' -> ' '
                '「' -> '｢'; '」' -> '｣'; '、' -> '､'; '。' -> '｡'; '・' -> '･'
                '〜' -> '~'; '￥' -> '\\'; '”', '“' -> '"'; '’', '‘' -> '\''
                else -> c
            }
        )
    }

    private fun toKatakana(text: String): String = buildString {
        for (c in text) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }

    /** ⌫ 長押しのゲージが満タンになったとき：カーソルより左の文字を全部消す（右は残す）。 */
    private fun clearAll() {
        toggleTimeout.cancel()
        if (isComposing) {
            render(session.revert())
        }
        lastTableKey = null
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()
        val beforeText = ic.getTextBeforeCursor(CLEAR_ALL_MAX_CHARS, 0)?.toString().orEmpty()
        val selectedText = ic.getSelectedText(0)?.toString().orEmpty()
        deletedBuffer.setLength(0)
        rememberDeleted(
            beforeText + selectedText,
            if (selStart >= 0) (minOf(selStart, selEnd) - beforeText.length).coerceAtLeast(0) else -1,
        )
        if (selectedText.isNotEmpty()) ic.commitText("", 1)
        ic.deleteSurroundingText(beforeText.length, 0)
        ic.endBatchEdit()
        clientContextValid = false
        showIdleActions()
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
        lastCommitted = null
        showEmailDomains()
    }

    /** 「名前@」まで打ってあれば、続きのドメインを候補に出す（入力中でないとき）。 */
    private fun showEmailDomains(): Boolean {
        if (isComposing) return false
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return false
        val (partial, domains) = EmailDomains.match(before) ?: return false
        stripContent = StripContent.CANDIDATES
        candidateView?.setCandidates(
            domains.mapIndexed { i, d -> MozcSession.Candidate(EMAIL_ID_BASE - i, d.removePrefix(partial)) }, -1,
        )
        return true
    }

    /** 入力中の文字が「名前@途中」になっていたら、ドメインを足した候補（入力中の文字ごと置きかえる）。 */
    private fun emailCandidates(preedit: String): List<MozcSession.Candidate> {
        if (preedit.isEmpty() || preedit.length > 64 || preedit.any { it.code > 0x7E }) return emptyList()
        val before = if ('@' in preedit) "" else
            currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty().let {
                // 入力中の文字そのものは、まだ確定していないので含まれていないことがある
                if (it.endsWith(preedit)) it.dropLast(preedit.length) else it
            }
        val (partial, domains) = EmailDomains.match(before + preedit) ?: return emptyList()
        return domains.mapIndexed { i, d ->
            MozcSession.Candidate(EMAIL_ID_BASE - 50 - i, preedit + d.removePrefix(partial))
        }
    }

    /**
     * Lets Mozc handle conversion or space insertion, with a fallback for unconsumed keys.
     *
     * With an empty composition, a half-width space is returned unconsumed for the client to
     * insert. Rendering that response alone would silently swallow the keystroke.
     */
    /** 入力欄が求めるエンターの動きから、⏎キーのアイコンを決める（動きと同じ決まりで）。 */
    private fun enterIconFor(info: EditorInfo?): Int {
        val imeOptions = info?.imeOptions ?: 0
        if (EnterRouting.targetFor(imeOptions, info?.inputType ?: 0) != EnterRouting.Target.ACTION) {
            return FlickKeyboardView.ENTER_NEWLINE
        }
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> FlickKeyboardView.ENTER_SEND
            EditorInfo.IME_ACTION_SEARCH -> FlickKeyboardView.ENTER_SEARCH
            EditorInfo.IME_ACTION_NEXT -> FlickKeyboardView.ENTER_NEXT
            EditorInfo.IME_ACTION_DONE -> FlickKeyboardView.ENTER_DONE
            EditorInfo.IME_ACTION_GO -> FlickKeyboardView.ENTER_GO
            EditorInfo.IME_ACTION_PREVIOUS -> FlickKeyboardView.ENTER_PREVIOUS
            else -> FlickKeyboardView.ENTER_NEWLINE
        }
    }

    private fun handleSpace() {
        if (session.zenzaiEnabled && isComposing) {
            zenzaiCandidate?.let { onCandidateSelected(it); return }
        }
        // 「空白は常に半角」がONなら、変換中でないときはどの画面でも半角空白を入れる
        if (settings.alwaysHalfSpace && !isComposing) {
            currentInputConnection?.commitText(" ", 1)
            return
        }
        val state = session.sendSpecialKey(KeyEvent.SpecialKey.SPACE)
        if (state == null || !state.consumed) {
            val fullWidth = layout?.inputStyle?.fullWidthSpace ?: false
            currentInputConnection?.commitText(if (fullWidth) FULL_WIDTH_SPACE else " ", 1)
        }
        render(state)
    }

    // ---------------------------------------------------------------- 消した文字を戻す
    //
    //  ⌫ で消した文字（全消しも）を覚えておき、候補バーに「↶ ○○ を戻す」を出す。
    //  ほかのキーを押したら忘れる。
    //    今日は晴れ|  →  ⌫⌫  →  今日は|   [↶「晴れ」を戻す]  →  今日は晴れ|

    private val deletedBuffer = StringBuilder()

    /** 消した場所（戻すときはここに入れる）。-1 ＝ わからない。 */
    private var deletedAt = -1

    private fun rememberDeleted(text: String, at: Int = -1) {
        if (text.isEmpty()) return
        deletedAt = at
        deletedBuffer.insert(0, text) // 続けて消した文字は前につなげる
        if (deletedBuffer.length > CLEAR_ALL_MAX_CHARS) deletedBuffer.setLength(CLEAR_ALL_MAX_CHARS)
    }

    private fun forgetDeleted() { deletedBuffer.setLength(0); deletedAt = -1 }

    private fun restoreDeleted() {
        val text = deletedBuffer.toString()
        if (text.isEmpty()) return
        if (isComposing) render(session.submit())
        val ic = currentInputConnection ?: return
        // 消したあとにカーソルを動かしていても、消した場所に戻す
        if (deletedAt >= 0 && selStart >= 0 && (selStart != deletedAt || selEnd != deletedAt)) {
            ic.setSelection(deletedAt, deletedAt)
        }
        ic.commitText(text, 1)
        forgetDeleted()
        showIdleActions()
    }

    private fun handleBackspace() {
        if (!isComposing) lastCommitted = null
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
            val selected = ic.getSelectedText(0)
            val hasSelection = !selected.isNullOrEmpty()
            when (BackspaceRouting.editorDeletion(hadComposition, hasSelection)) {
                EditorDeletion.NONE -> Unit
                // Committing empty text is what replaces a selection; deleting around it would not.
                EditorDeletion.SELECTION -> {
                    rememberDeleted(selected.toString(), if (selStart >= 0) minOf(selStart, selEnd) else -1)
                    ic.commitText("", 1)
                    if (selStart >= 0) { selStart = minOf(selStart, selEnd); selEnd = selStart }
                }
                EditorDeletion.PRECEDING_CHARACTER -> {
                    // 見た目の1文字をまるごと消す（絵文字は部品が何個もあるので、半分だけ消すと「�」になる）。
                    // 消した文字は覚えておく（すぐ「戻す」で戻せるように）。
                    val before = ic.getTextBeforeCursor(GRAPHEME_LOOK_CHARS, 0)?.toString().orEmpty()
                    val ch = before.takeLast(Graphemes.lastLength(before))
                    rememberDeleted(ch, if (selStart >= 0) (selStart - ch.length).coerceAtLeast(0) else -1)
                    ic.deleteSurroundingText(ch.length.coerceAtLeast(1), 0)
                    // アプリからの知らせを待たずに、今の位置を先に合わせておく（続けて消したとき用）
                    if (selStart >= 0) { selStart = (selStart - ch.length).coerceAtLeast(0); selEnd = selStart }
                }
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
        lastCommitted = null
        session.stopKeyToggling()
        val special = if (delta < 0) KeyEvent.SpecialKey.LEFT else KeyEvent.SpecialKey.RIGHT
        val state = session.sendSpecialKey(special)
        if (state != null && state.hasComposition) {
            render(state)
            return
        }
        // Outside a composition, move the editor caret instead.
        // 前は「アプリが返した一部の文字列」の中での位置を、全体の位置として使っていたので、
        // 長い文章だとカーソルがとんでもない所（右端など）へ飛ぶことがあった。
        val ic = currentInputConnection ?: return
        if (selStart < 0) {
            // 位置がわからないときは、矢印キーを送ってアプリに動かしてもらう
            sendDownUpKeyEvents(
                if (delta < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            )
            return
        }
        val from = if (delta < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
        // 見た目の1文字ぶん動かす（絵文字の途中で止まると、カーソルが動かないように見える）
        val look = GRAPHEME_LOOK_CHARS * kotlin.math.abs(delta)
        val target = if (delta < 0) {
            val before = ic.getTextBeforeCursor(look, 0) ?: ""
            from - Graphemes.lastLength(before, -delta)
        } else {
            val after = ic.getTextAfterCursor(look, 0) ?: ""
            from + Graphemes.firstLength(after, delta)
        }
        if (target < 0) return
        selStart = target; selEnd = target
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
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        // setComposingText left the caret just past the composition, which is the only fixed point
        // we can measure its start from — the editor never tells us where it put the span.
        // 中身を教えてくれないアプリでは、アプリが知らせてくれた「下線の始まり」を使う。
        // （前はここであきらめていたので、◀▶で途中に入れても、カーソルが右端に飛んでいた）
        val start = if (extracted != null) {
            extracted.startOffset + extracted.selectionStart - state.preedit.length
        } else {
            composeStart
        }
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
            learnWord(lastState?.preeditReading.orEmpty(), state.committedText)
        } else if (state.committedText.isNotEmpty() && !hadComposition) {
            learnNext(state.committedText)
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

        // 確定した直後：前に続けて打った言葉（続きの予測）＋ mozc の予測
        val justCommitted = !state.hasComposition && state.committedText.isNotEmpty()
        val next = if (justCommitted) nextCandidates() else emptyList()
        if (state.candidates.isEmpty() && next.isEmpty()) {
            candidateView?.expanded = false
            showIdleActions()
        } else if (!state.hasComposition) {
            stripContent = StripContent.CANDIDATES
            val hidden = settings.hiddenCandidates
            val shown = (next + state.candidates).distinctBy { it.text }.filter { it.text !in hidden }
            if (shown.isEmpty()) showIdleActions() else candidateView?.setCandidates(shown, -1)
        } else {
            stripContent = StripContent.CANDIDATES
            val shown = decorate(zenzaiBaseCandidates(state), state)
            val focusedText = state.candidates.getOrNull(state.focusedCandidateIndex)?.text
            candidateView?.setCandidates(shown, if (focusedText == null) -1 else shown.indexOfFirst { it.text == focusedText })
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
            val candidates = decorate(ZenzaiConversion.candidates(base, candidate), state)
            stripContent = StripContent.CANDIDATES
            // AIの候補には色を付けない（後から出てくるので目立ちすぎる）。
            // 変換キーで候補を選んでいる最中なら、その候補の色だけそのまま残す。
            val focusedText = base.getOrNull(state.focusedCandidateIndex)?.text
            candidateView?.setCandidates(candidates, candidates.indexOfFirst { it.text == focusedText && focusedText != null })
        }
    }

    /**
     * 候補の並びに手を加える：
     *  ・日付・時刻（「きょう」→ 2026/11/17 など）と計算（「12*3=」→ 36）を足す
     *  ・長押しで「出さない」にした候補を取り除く
     */
    private fun decorate(list: List<MozcSession.Candidate>, state: MozcSession.State): List<MozcSession.Candidate> {
        val hidden = settings.hiddenCandidates
        // 覚えた言葉を先頭に（趣味ボードで覚えたものは趣味ボードのときだけ）
        var out = (learnedCandidates(state.preeditReading) + list).distinctBy { it.text }
        // メールアドレスを打っているなら、@のあとのドメインを先頭に
        val email = emailCandidates(state.preedit)
        if (email.isNotEmpty()) out = (email + out).distinctBy { it.text }
        // カタカナ語なら英語の綴りを、いちばん目の候補のすぐ後ろに
        if (settings.englishCandidates) {
            val english = EnglishCandidates.lookup(state.preeditReading)
                .mapIndexed { i, t -> MozcSession.Candidate(-5000 - i, t) }
            if (english.isNotEmpty()) out = (out.take(1) + english + out.drop(1)).distinctBy { it.text }
        }
        if (settings.smartCandidates) {
            val calc = SmartCandidates.calculate(state.preedit).mapIndexed { i, t -> MozcSession.Candidate(-2000 - i, t) }
            val dates = SmartCandidates.dateTime(state.preeditReading).mapIndexed { i, t -> MozcSession.Candidate(-3000 - i, t) }
            // 郵便番号→住所、数字→漢数字・金額、日付→曜日つき、5きろ→5km
            val extra = (ZipCodes.lookup(state.preedit) +
                SmartCandidates.dateWithWeekday(state.preedit) +
                SmartCandidates.units(state.preedit) +
                SmartCandidates.numbers(state.preedit))
                .mapIndexed { i, t -> MozcSession.Candidate(-8000 - i, t) }
            // 記号は2つめの候補のあと（「ほし」なら 星・☆ … の順）
            val symbols = SymbolReadings.lookup(state.preeditReading)
                .mapIndexed { i, t -> MozcSession.Candidate(-9000 - i, t) }
            // 計算はいちばん前、日付などは1つめの候補（今日 など）のすぐ後ろ
            out = calc + out.take(1) + dates + extra + out.drop(1)
            if (symbols.isNotEmpty()) out = out.take(2) + symbols + out.drop(2)
            out = out.distinctBy { it.text }
        }
        if (hidden.isNotEmpty()) out = out.filter { it.text !in hidden }
        val rules = settings.hiddenRules.mapNotNull(HiddenRules::decode)
        if (rules.isEmpty()) return out
        val reading = state.preeditReading
        val kept = out.filter { c -> rules.none { it.matches(reading, c.text) } }
        // 全部消えてしまうと打った文字が選べなくなるので、そのときは消さない
        return kept.ifEmpty { out }
    }

    /**
     * 候補を長押し：「辞書登録」か「候補から削除」かを選ぶメニューを出す。
     * 入力中の文字は確定せずにそのまま残す（戻るを押せば続きを打てる）。
     */
    private fun openCandidateMenu(candidate: MozcSession.Candidate) {
        val reading = lastState?.preeditReading.orEmpty()
        val word = candidate.text.take(100)
        showMenu(listOf(
            listOf(KeyboardMenuView.Item({ "「$word」" }) { }),
            listOf(
                KeyboardMenuView.Item({ "📖 辞書登録" }) {
                    closeKeyboardMenu()
                    startActivity(
                        Intent(this, UserDictionaryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(UserDictionaryActivity.EXTRA_WORD, word)
                            .putExtra(UserDictionaryActivity.EXTRA_READING, reading.take(100))
                    )
                },
                KeyboardMenuView.Item({ "🗑 候補から削除" }) {
                    closeKeyboardMenu()
                    hideCandidate(candidate)
                },
            ),
            listOf(KeyboardMenuView.Item({ "⌨ 戻る" }) { closeKeyboardMenu() }),
        ), submitComposition = false)
    }

    /** 候補から削除：その候補を今後出さない（設定の「カスタマイズ」で戻せる）。 */
    private fun hideCandidate(candidate: MozcSession.Candidate) {
        settings.hiddenCandidates = settings.hiddenCandidates + candidate.text
        // その文だけでなく「変換のしかた」も覚えて、ほかの文でも出さない
        HiddenRules.derive(lastState?.preeditReading.orEmpty(), candidate.text)?.let {
            settings.hiddenRules = settings.hiddenRules + it.encode()
        }
        Toast.makeText(this, "「${candidate.text}」を候補に出さないようにしました（設定で戻せます）", Toast.LENGTH_SHORT).show()
        lastState?.let { state ->
            if (state.candidates.isNotEmpty()) {
                val shown = decorate(zenzaiBaseCandidates(state), state)
                candidateView?.setCandidates(shown, -1)
            }
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
        forgetDeleted()
        if (candidate.id < 0 && isComposing) {
            learnConfirmed(candidate.text)
            learnWord(lastState?.preeditReading.orEmpty(), candidate.text)
        }
        cancelZenzai()
        candidateView?.expanded = false
        if (candidate.id >= 0) {
            render(session.selectCandidate(candidate.id))
            return
        }
        val wasComposing = isComposing
        session.revert()
        isComposing = false
        currentInputConnection?.commitText(candidate.text, 1)
        lastTableKey = null
        if (!wasComposing && candidate.id <= EMAIL_ID_BASE && candidate.id > EMAIL_ID_BASE - 100) {
            // メールのドメインを入れたあとは、続きの予測は出さない
            lastCommitted = null
            showIdleActions()
            return
        }
        if (!wasComposing) learnNext(candidate.text)
        // 続けて、その言葉のあとの予測を出す
        val next = nextCandidates()
        if (next.isEmpty()) showIdleActions()
        else { stripContent = StripContent.CANDIDATES; candidateView?.setCandidates(next, -1) }
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
        if (visibleClips().isNotEmpty()) {
            actions += CandidateStripView.Action("", R.drawable.ic_clipboard, CLIPBOARD_LABEL) { showClipboard() }
        }
        // ↶ ＝ ⌫ で消した文字（全消しも）を戻す。変換をひらがなに戻す機能はやめた
        actions += CandidateStripView.Action("", R.drawable.ic_undo, "消した文字を戻す") { restoreDeleted() }
        // 片手モードのボタンは置かない（あA1長押しのメニューから切り替えられる）
        // 鍵のアイコンだけ：閉じている＝趣味ボード、開いている＝表のボード
        actions += if (settings.hobbyMode) {
            CandidateStripView.Action("", R.drawable.ic_lock, HOBBY_ON_LABEL) { toggleHobby() }
        } else {
            CandidateStripView.Action("", R.drawable.ic_lock_open, HOBBY_OFF_LABEL) { toggleHobby() }
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
        recordClip()
        stripContent = StripContent.CLIPBOARD
        val actions = mutableListOf<CandidateStripView.Action>()
        // キーボードに戻る（アイコンで分かるように）
        actions += CandidateStripView.Action("", R.drawable.ic_keyboard, "キーボードに戻る") { showIdleActions() }
        for (text in visibleClips()) {
            // 全文を枠で囲んで表示（新しい順。一覧は上下にスクロールできる）
            actions += CandidateStripView.Action(text, block = true) {
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
    private fun openSymbolPanel(kaomoji: Boolean = false, emoji: Boolean = false) {
        val root = panelRoot ?: return
        val keyboard = keyboardView ?: return
        if (isComposing) render(session.submit())
        val panel = symbolPanel ?: SymbolPanelView(this, theme, object : SymbolPanelView.Listener {
            // 趣味ボードは学習しないが、趣味ボード用の履歴は残す
            override fun isPrivate(): Boolean =
                settings.privateMode || (session.incognito && !settings.hobbyMode)
            override fun historySuffix(): String = if (settings.hobbyMode) ImeSettings.HOBBY_SUFFIX else ""
            override fun phrases(): List<String> = settings.phrases
            override fun onPanelText(text: String) {
                forgetDeleted()
                if (isComposing) render(session.submit())
                currentInputConnection?.commitText(text, 1)
            }
            override fun onPanelBackspace() = handleBackspace()
            override fun onPanelClose() = closeSymbolPanel()
        }).also {
            root.addView(
                it,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keysHeightPx),
            )
            symbolPanel = it
        }
        (panel.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = keysHeightPx
            panel.layoutParams = it
        }
        panel.reset()
        if (kaomoji) panel.showKaomoji()
        if (emoji) panel.showEmoji()
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
        // 趣味ボードの間も、ふつうの学習（表のボード）には残さない
        session.setIncognito(settings.privateMode || settings.hobbyMode || appAsksNoLearning)
    }

    /**
     * 表のボード ⇄ 趣味ボード。キーボードを作り直して色・背景を変え、
     * 全消しと同じ光の波を流して「切り替わった」と分かるようにする。
     */
    private fun toggleHobby() {
        if (isComposing) render(session.submit())
        settings.hobbyMode = !settings.hobbyMode
        applyPrivacy(currentInputEditorInfo)
        setInputView(onCreateInputView())
        keyboardView?.let { kb -> kb.post { kb.playClearWave() } }
        showIdleActions()
    }

    private val learnedMain by lazy { LearnedWords(this, LearnedWords.MAIN_FILE) }
    private val nextMain by lazy { LearnedWords(this, LearnedWords.NEXT_MAIN_FILE) }
    private val nextHobby by lazy { LearnedWords(this, LearnedWords.NEXT_HOBBY_FILE) }

    /** 直前に確定した言葉（続きの予測に使う）。カーソルを動かしたり消したりしたら忘れる。 */
    private var lastCommitted: String? = null

    /** 「前の言葉 → 今の言葉」を覚える（続きの予測）。 */
    private fun learnNext(word: String) {
        val prev = lastCommitted
        lastCommitted = word
        if (prev == null || !canLearn()) return
        (if (settings.hobbyMode) nextHobby else nextMain).remember(prev, word)
    }

    /** 直前の言葉のあとに、前に打った言葉（続きの予測）。趣味ボードは趣味 → 表 の順。 */
    private fun nextCandidates(): List<MozcSession.Candidate> {
        val prev = lastCommitted ?: return emptyList()
        val words = if (settings.hobbyMode) nextHobby.lookupExact(prev) + nextMain.lookupExact(prev)
        else nextMain.lookupExact(prev)
        val hidden = settings.hiddenCandidates
        return words.distinct().filter { it !in hidden }.take(8)
            .mapIndexed { i, w -> MozcSession.Candidate(NEXT_ID_BASE - i, w) }
    }
    private val learnedHobby by lazy {
        LearnedWords(this, LearnedWords.HOBBY_FILE).also { hobby ->
            // 前の版は趣味ボードの言葉を設定に平文で置いていたので、暗号化した方へ移す
            val old = settings.hobbyWords
            if (old.isNotEmpty()) {
                for ((r, w) in old.asReversed()) hobby.remember(r, w)
                settings.hobbyWords = emptyList()
            }
        }
    }

    /** 覚えてよいか（プライベートモード・パスワード欄・学習しないでと頼む欄では覚えない）。 */
    private fun canLearn(): Boolean {
        val info = currentInputEditorInfo
        return !settings.privateMode &&
            !isPasswordField(info?.inputType ?: 0) &&
            ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
    }

    /**
     * 確定した言葉を覚える。表のボードなら表の一覧、趣味ボードなら趣味の一覧へ。
     * AI変換の候補を選んだときも、ここで覚える（mozc は覚えられないので）。
     */
    private fun learnWord(reading: String, word: String) {
        learnNext(word)
        if (!canLearn()) return
        (if (settings.hobbyMode) learnedHobby else learnedMain).remember(reading, word)
    }

    /** 覚えた言葉のうち、今の読みに合うもの。趣味ボードは趣味 → 表 の順（一方通行）。 */
    private fun learnedCandidates(reading: String): List<MozcSession.Candidate> {
        if (reading.isEmpty()) return emptyList()
        val words = if (settings.hobbyMode) learnedHobby.lookup(reading) + learnedMain.lookup(reading)
        else learnedMain.lookup(reading)
        return words.distinct().mapIndexed { i, w -> MozcSession.Candidate(-4000 - i, w) }
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
    /**
     * 「編集」キー：キーボードの場所に編集ボタンを出す。押しても閉じないので、
     * 全選択 → コピー のように続けて押せる。⌨ 戻る で閉じる。
     *   ┌────────┬────────┐
     *   │ 全選択  │ 切り取り │
     *   ├────────┼────────┤
     *   │ コピー  │ 貼り付け │
     *   ├────┬────┬───────┤
     *   │ ◀  │ ▶  │ ⌨ 戻る │
     */
    private fun openEditMenu() {
        fun edit(id: Int) {
            if (isComposing) render(session.submit())
            currentInputConnection?.performContextMenuAction(id)
        }
        showMenu(listOf(
            listOf(
                KeyboardMenuView.Item({ "全選択" }) { edit(android.R.id.selectAll) },
                KeyboardMenuView.Item({ "切り取り" }) { edit(android.R.id.cut) },
            ),
            listOf(
                KeyboardMenuView.Item({ "コピー" }) { edit(android.R.id.copy) },
                KeyboardMenuView.Item({ "貼り付け" }) { edit(android.R.id.paste) },
            ),
            listOf(
                KeyboardMenuView.Item({ "◀" }) { handleCursorMove(-1) },
                KeyboardMenuView.Item({ "▶" }) { handleCursorMove(1) },
                KeyboardMenuView.Item({ "単語登録" }) {
                    // 選んでいる文字を「単語」に入れた状態で、単語登録の画面を開く
                    if (isComposing) render(session.submit())
                    val selected = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
                    closeKeyboardMenu()
                    startActivity(
                        Intent(this, UserDictionaryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(UserDictionaryActivity.EXTRA_WORD, selected.take(100))
                    )
                },
                KeyboardMenuView.Item({ "⌨ 戻る" }) { closeKeyboardMenu() },
            ),
        ), submitComposition = false)
    }

    private fun openKeyboardMenu() {
        showMenu(settingsMenuItems())
    }

    /** キーボードの場所にボタンの並び（メニュー）を出す。 */
    private fun showMenu(items: List<List<KeyboardMenuView.Item>>, submitComposition: Boolean = true) {
        val root = panelRoot ?: return
        val keyboard = keyboardView ?: return
        if (submitComposition && isComposing) render(session.submit())
        val menu = keyboardMenu ?: KeyboardMenuView(this, theme).also {
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keysHeightPx))
            keyboardMenu = it
        }
        (menu.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = keysHeightPx
            menu.layoutParams = it
        }
        menu.setItems(items)
        symbolPanel?.visibility = View.GONE
        keyboard.visibility = View.GONE
        menu.visibility = View.VISIBLE
        Anim.fadeIn(menu)
    }

    private fun settingsMenuItems(): List<List<KeyboardMenuView.Item>> = listOf(
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
            listOf(
                KeyboardMenuView.Item({
                    if (settings.flickGuideStyle == FlickGuideStyle.NONE) "ポップアップ：オフ" else "ポップアップ：オン"
                }) {
                    settings.flickGuideStyle =
                        if (settings.flickGuideStyle == FlickGuideStyle.NONE) FlickGuideStyle.PREVIEW else FlickGuideStyle.NONE
                    keyboardView?.guideStyle = guideStyleFor(keyboardView?.layout)
                },
                KeyboardMenuView.Item({ if (settings.privateMode) "プライベート：オン" else "プライベート：オフ" }) {
                    togglePrivateMode()
                },
                KeyboardMenuView.Item({ "閉じる" }) { closeKeyboardMenu() },
            ),
        )

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

        /** キーボードの段数（高さを決める基準）。 */
        private const val KEY_ROWS = 4

        /** 全削除で一度に見る文字数の上限。 */
        private const val CLEAR_ALL_MAX_CHARS = 100_000
        /** 見た目の1文字を探すために読む長さ（家族の絵文字など、長い絵文字でも入る長さ） */
        private const val GRAPHEME_LOOK_CHARS = 32


        /** '*' in mozc's flick/12-key tables. See data/preedit/flick-hiragana.tsv upstream. */
        private const val CYCLE_MODIFIER_KEY = "*"

        /** U+3000 IDEOGRAPHIC SPACE, what a Japanese input mode types for the space key. */
        private const val FULL_WIDTH_SPACE = "　"

        private const val CLIPBOARD_LABEL = "クリップボード"
        private const val ONE_HAND_LABEL = "片手モード"
        private const val UNDO_LABEL = "元に戻す"
        private const val PRIVATE_OFF_LABEL = "プライベートモード：オフ"
        private const val NEXT_ID_BASE = -7000
        private const val EMAIL_ID_BASE = -6000
        private const val HOBBY_DEFAULT_BG = 0xFF2A1640.toInt()
        private const val HOBBY_OFF_LABEL = "趣味ボードに切り替える"
        private const val HOBBY_ON_LABEL = "表のボードに戻す"
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
