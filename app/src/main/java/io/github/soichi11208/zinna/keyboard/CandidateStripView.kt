package io.github.soichi11208.zinna.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.soichi11208.zinna.R
import io.github.soichi11208.zinna.ime.MozcSession
import io.github.soichi11208.zinna.theme.KeyboardTheme

/**
 * Horizontal strip of conversion candidates.
 *
 * Built out of plain TextViews rather than a RecyclerView: a candidate list is short and fully
 * replaced on every keystroke, so view recycling buys nothing and costs an adapter's worth of
 * latency on the hottest path in the app.
 */
@SuppressLint("ViewConstructor")
class CandidateStripView(context: Context) : FrameLayout(context) {

    fun interface OnCandidateSelectedListener {
        fun onCandidateSelected(candidate: MozcSession.Candidate)
    }

    /**
     * Something to offer when there is nothing to convert — the clipboard, undo, a clipping to
     * paste. The strip only draws these; what they mean is the input method's business.
     */
    class Action(
        val label: String,
        /** Drawable to sit before the label, or 0 for none. */
        val iconRes: Int = 0,
        /** アイコンだけのボタンの説明（読み上げ用）。 */
        val description: String? = null,
        val onSelected: () -> Unit,
    )

    var listener: OnCandidateSelectedListener? = null

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            for (i in 0 until container.childCount) {
                applyTheme(container.getChildAt(i) as TextView)
            }
            invalidate()
        }

    /** Which chip currently carries the focus highlight, so only the two that change are touched. */
    private var focusedChild = -1

    fun interface OnExpandedChangeListener {
        fun onExpandedChanged(expanded: Boolean)
    }

    /** Told when the user opens or closes the full list, so the keyboard can make room. */
    var expandedListener: OnExpandedChangeListener? = null

    /**
     * Whether the whole candidate list is on show.
     *
     * Collapsed, the chips sit on one line that scrolls sideways — which is the right shape while
     * typing, and the wrong one for reading twenty candidates. Expanded, they wrap into rows over
     * the keyboard, the way every other input method does it.
     */
    var expanded: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            oneLine.visibility = if (value) GONE else VISIBLE
            wrapped.visibility = if (value) VISIBLE else GONE
            // Chips live in exactly one of the two containers, so they move across on every toggle.
            val from = if (value) row else grid
            val to = if (value) grid else row
            while (from.childCount > 0) {
                val chip = from.getChildAt(0)
                from.removeViewAt(0)
                to.addView(chip)
            }
            invalidate()
            Anim.fadeIn(if (value) wrapped else oneLine)
            expandedListener?.onExpandedChanged(value)
        }

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val grid = FlowLayout(context)

    private val oneLine = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private val wrapped = ScrollView(context).apply {
        visibility = GONE
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Whichever container is on screen; everything else here works through this. */
    private val container: ViewGroup get() = if (expanded) grid else row

    // ------------------------------------------------------------ 下に引っぱって一覧を開く
    //
    //  候補の列を指で下へ引っぱる → 候補の一覧（全部）が開く
    //  一覧のいちばん上で上へはらう → 元の1列に戻る

    private var pullStartX = 0f
    private var pullStartY = 0f
    private var pulling = false
    private val pullSlop = 24f * resources.displayMetrics.density

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pullStartX = ev.x
                pullStartY = ev.y
                pulling = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - pullStartX
                val dy = ev.y - pullStartY
                val vertical = kotlin.math.abs(dy) > pullSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f
                if (vertical && !expanded && dy > 0 && hasCandidates()) {
                    pulling = true
                    return true
                }
                if (vertical && expanded && dy < 0 && !wrapped.canScrollVertically(-1)) {
                    pulling = true
                    return true
                }
            }
        }
        return false
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
            event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
        ) {
            pulling = false
        } else if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            // 引っぱった瞬間に切り替える（指を離すまで待たない）。
            if (!expanded && event.y - pullStartY > pullSlop) expanded = true
            else if (expanded && pullStartY - event.y > pullSlop) expanded = false
        }
        return true
    }

    /** 候補（選べるもの）が出ているか。アクションボタンだけのときは引っぱっても開かない。 */
    private fun hasCandidates(): Boolean = showingCandidates

    private var showingCandidates = false

    init {
        // Transparent so the panel behind — colour or the user's background image — shows through.
        addView(oneLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wrapped, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Replaces the visible candidates.
     *
     * Reuses the chips already on screen instead of rebuilding them. This runs on every keystroke,
     * and constructing a TextView is not cheap — it resolves attributes, allocates layout state and
     * forces a full measure pass. Tearing down twenty of them and building twenty more per key was
     * enough to make fast typing feel like the keyboard was falling behind.
     *
     * Each property is written only when it actually differs: TextView.setText and setBackground
     * invalidate unconditionally, so assigning the same value still costs a layout pass.
     */
    fun setCandidates(candidates: List<MozcSession.Candidate>, focusedIndex: Int) {
        showingCandidates = candidates.isNotEmpty()
        detachTrailing()
        while (container.childCount > candidates.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < candidates.size) {
            container.addView(
                newChip(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        candidates.forEachIndexed { index, candidate ->
            val view = container.getChildAt(index) as TextView
            view.tag = candidate
            if (view.text != candidate.text) view.text = candidate.text
            // A chip reused from the idle actions still has their icon attached.
            if (view.getTag(R.id.chip_icon) != 0) {
                view.setTag(R.id.chip_icon, 0)
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                // アイコンボタン用に広げた左右の余白を元に戻す。
                val pad = (12 * resources.displayMetrics.density).toInt()
                view.setPadding(pad, view.paddingTop, pad, view.paddingBottom)
                view.contentDescription = null
            }
        }

        if (focusedChild != focusedIndex) {
            highlight(focusedChild, false)
            highlight(focusedIndex, true)
            focusedChild = focusedIndex
        }
        oneLine.scrollTo(0, 0)
    }

    /**
     * Fills the strip with [actions] instead of candidates.
     *
     * The strip is the widest piece of idle real estate on the keyboard, and while nothing is being
     * converted it is showing nothing at all. Same chips, same recycling — only what the tag holds
     * differs, so the click path can tell an action from a candidate.
     */
    fun setActions(actions: List<Action>) {
        showingCandidates = false
        detachTrailing()
        while (container.childCount > actions.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < actions.size) {
            container.addView(
                newChip(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        actions.forEachIndexed { index, action ->
            val view = container.getChildAt(index) as TextView
            view.tag = action
            if (view.text != action.label) view.text = action.label
            if (view.getTag(R.id.chip_icon) != action.iconRes) {
                view.setTag(R.id.chip_icon, action.iconRes)
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(action.iconRes, 0, 0, 0)
                if (view.isLaidOut) Anim.swap(view)
            }
            view.contentDescription = action.description ?: action.label
            // アイコンだけのボタンは、文字との隙間をなくして左右を広めにとる（押しやすく）。
            val iconOnly = action.label.isEmpty() && action.iconRes != 0
            val pad = ((if (iconOnly) 18 else 12) * resources.displayMetrics.density).toInt()
            view.compoundDrawablePadding = if (iconOnly) 0 else pad / 2
            view.setPadding(pad, view.paddingTop, pad, view.paddingBottom)
        }

        if (focusedChild != -1) {
            highlight(focusedChild, false)
            focusedChild = -1
        }
        oneLine.scrollTo(0, 0)
    }

    /**
     * Puts a single action at the end of the candidates, replacing any previous one.
     *
     * Kept apart from [setCandidates] so the candidate path stays a straight recycle: this is one
     * chip that changes rarely, and rebuilding it per keystroke alongside them would put a layout
     * pass back on the hot path that was taken off it.
     */
    fun setTrailingAction(action: Action?) {
        val existing = trailing
        if (action == null) {
            if (existing != null) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                trailing = null
            }
            return
        }
        val chip = existing ?: newChip().also { trailing = it }
        chip.tag = action
        if (chip.text != action.label) chip.text = action.label
        val parent = container
        if (chip.parent !== parent) {
            (chip.parent as? ViewGroup)?.removeView(chip)
            parent.addView(chip)
        } else if (parent.indexOfChild(chip) != parent.childCount - 1) {
            parent.removeView(chip)
            parent.addView(chip)
        }
    }

    /** The expand chip, kept out of the recycled pool so the counting stays simple. */
    private var trailing: TextView? = null

    private fun detachTrailing() {
        trailing?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun highlight(index: Int, on: Boolean) {
        val view = container.getChildAt(index) ?: return
        if (on) view.setBackgroundColor(theme.keyPressedColor) else view.background = null
    }

    private fun newChip(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        applyTheme(this)
        setOnClickListener { clicked ->
            // Picking a candidate commits text just as a keypress does, so it gets the same
            // confirmation. Without it the strip is the one part of the keyboard that feels
            // dead under the thumb.
            if (theme.hapticFeedback) {
                clicked.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            Anim.tap(clicked)
            // Read through the tag rather than capturing: the chip outlives the candidate it was
            // first built for, so a captured one would commit whatever was showing keystrokes ago.
            when (val tag = clicked.tag) {
                is MozcSession.Candidate -> listener?.onCandidateSelected(tag)
                is Action -> tag.onSelected()
            }
        }
    }

    private fun applyTheme(view: TextView) {
        val padding = (12 * resources.displayMetrics.density).toInt()
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.labelSizeSp)
        view.setTextColor(theme.candidateTextColor)
        view.setPadding(padding, padding / 2, padding, padding / 2)
        view.compoundDrawablePadding = padding / 2
        // The icons ship white so they can take the text colour, whatever the theme sets it to.
        view.compoundDrawableTintList = ColorStateList.valueOf(theme.candidateTextColor)
    }

    fun clear() = setCandidates(emptyList(), -1)
}
