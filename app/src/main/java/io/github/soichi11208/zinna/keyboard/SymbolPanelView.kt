package io.github.soichi11208.zinna.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.soichi11208.zinna.theme.KeyboardTheme
import org.json.JSONArray

/**
 * 記号・絵文字・顔文字パネル。
 *
 * ┌───────────────────────────┐
 * │  グリッド（タップで入力）          │
 * ├───────────────────────────┤
 * │ 履歴│カテゴリ1│カテゴリ2│…  (横スクロール) │
 * ├───────────────────────────┤
 * │ ⌨ │ 記号 │ 絵文字 │ 顔文字 │ ⌫ │
 * └───────────────────────────┘
 *
 * 背景は透明なので、キーボードの背景画像がそのまま透けて見える。
 * 列数は画面幅に合わせて自動で増えるので、Fold を開いた横長画面でも詰まらない。
 */
@SuppressLint("ViewConstructor")
class SymbolPanelView(
    context: Context,
    private val theme: KeyboardTheme,
    private val listener: Listener,
) : LinearLayout(context) {

    interface Listener {
        fun onPanelText(text: String)
        fun onPanelBackspace()
        fun onPanelClose()
        /** プライベートモード中は履歴を残さない。 */
        fun isPrivate(): Boolean
        /** 設定画面で登録した定型文。 */
        fun phrases(): List<String>
    }

    private enum class Kind(val label: String, val asset: String, val historyKey: String) {
        SYMBOL("記号", "panels/symbols.json", "history_symbol"),
        EMOJI("絵文字", "panels/emoji.json", "history_emoji"),
        KAOMOJI("顔文字", "panels/kaomoji.json", "history_kaomoji"),
        PHRASE("定型文", "", "history_phrase"),
    }

    private data class Category(val name: String, val items: List<String>)

    private val prefs = context.getSharedPreferences("symbol_panel", Context.MODE_PRIVATE)
    private val cache = HashMap<Kind, List<Category>>()
    private var kind = Kind.SYMBOL
    private var categoryIndex = 1 // 0 = 履歴

    private val grid = GridView(context)
    private val tabRow = LinearLayout(context)
    private val tabScroll = HorizontalScrollView(context)
    private val kindButtons = HashMap<Kind, TextView>()
    private var items: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private val accent = Color.parseColor("#2FA8FF")

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT)

        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(KIND_COLUMN_DP[kind] ?: 56f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 0
            horizontalSpacing = 0
            selector = GradientDrawable().apply { setColor(theme.keyPressedColor) }
            adapter = GridAdapter()
            setOnItemClickListener { _, _, position, _ ->
                val text = items.getOrNull(position) ?: return@setOnItemClickListener
                remember(text)
                listener.onPanelText(text)
            }
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        tabRow.orientation = HORIZONTAL
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabRow)
        tabScroll.setBackgroundColor(withAlpha(theme.backgroundColor, 0xCC))
        addView(tabScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)))

        addView(buildBottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(52f)))

        showKind(Kind.SYMBOL)
    }

    /** パネルを開くたびに呼ぶ。履歴があれば履歴タブから開く。 */
    fun reset() {
        showKind(kind)
    }

    /** 顔文字のページを開く。 */
    fun showKaomoji() = showKind(Kind.KAOMOJI)

    /** 絵文字のページを開く。 */
    fun showEmoji() = showKind(Kind.EMOJI)

    private fun buildBottomBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(withAlpha(theme.modifierKeyColor, 0xE6))
        }
        fun button(label: String, weight: Float, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(theme.labelColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setOnClickListener { onClick() }
                bar.addView(this, LayoutParams(0, LayoutParams.MATCH_PARENT, weight))
            }
        button("⌨", 1f) { listener.onPanelClose() }
        for (k in Kind.entries) {
            kindButtons[k] = button(k.label, 1.2f) { showKind(k) }
        }
        button("⌫", 1f) { listener.onPanelBackspace() }.apply {
            // 長押しで連続削除
            setOnLongClickListener {
                val repeater = object : Runnable {
                    override fun run() {
                        if (!isPressed) return
                        listener.onPanelBackspace()
                        postDelayed(this, 60)
                    }
                }
                post(repeater)
                true
            }
        }
        return bar
    }

    private fun showKind(next: Kind) {
        kind = next
        val categories = categoriesOf(next)
        categoryIndex = if (next != Kind.PHRASE && history(next).isNotEmpty()) 0 else 1
        grid.columnWidth = dp(KIND_COLUMN_DP[next] ?: 56f)
        kindButtons.forEach { (k, view) ->
            view.setTextColor(if (k == next) accent else theme.labelColor)
            view.typeface = if (k == next) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        tabRow.removeAllViews()
        val names = listOf("履歴") + categories.map { it.name }
        names.forEachIndexed { index, name ->
            val tab = TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (name.contains('\n')) 12f else 15f)
                setLineSpacing(0f, 0.9f)
                setPadding(dp(14f), 0, dp(14f), 0)
                setOnClickListener { selectCategory(index) }
            }
            tabRow.addView(tab, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        selectCategory(categoryIndex)
    }

    private fun selectCategory(index: Int) {
        categoryIndex = index
        items = if (index == 0) history(kind) else categoriesOf(kind)[index - 1].items
        for (i in 0 until tabRow.childCount) {
            val tab = tabRow.getChildAt(i) as TextView
            val selected = i == index
            tab.setTextColor(if (selected) accent else theme.labelColor)
            tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun categoriesOf(k: Kind): List<Category> {
        // 定型文は設定画面でいつでも変わるので、毎回読み直す
        if (k == Kind.PHRASE) return listOf(Category("定型文", listener.phrases()))
        return loadAsset(k)
    }

    private fun loadAsset(k: Kind): List<Category> = cache.getOrPut(k) {
        try {
            val json = context.assets.open(k.asset).bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val list = obj.getJSONArray("items")
                Category(obj.getString("name"), (0 until list.length()).map { list.getString(it) })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- 履歴

    private fun history(k: Kind): List<String> =
        prefs.getString(k.historyKey, null)
            ?.split(HISTORY_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun remember(text: String) {
        if (listener.isPrivate()) return
        val updated = (listOf(text) + history(kind).filter { it != text }).take(HISTORY_LIMIT)
        prefs.edit().putString(kind.historyKey, updated.joinToString(HISTORY_SEPARATOR)).apply()
    }

    // ---------------------------------------------------------------- グリッド

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(theme.labelColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(6f), 0, dp(6f), 0)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f))
            }
            view.text = items[position]
            view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                when {
                    kind == Kind.KAOMOJI || kind == Kind.PHRASE -> 15f
                    kind == Kind.EMOJI -> 26f
                    else -> 22f
                },
            )
            return view
        }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        private const val HISTORY_LIMIT = 40

        /** 改行やスペースを含む顔文字もあるので、まず使われない文字で区切る。 */
        private const val HISTORY_SEPARATOR = "\u001F"

        private val KIND_COLUMN_DP = mapOf(
            Kind.SYMBOL to 56f,
            Kind.EMOJI to 56f,
            Kind.KAOMOJI to 130f,
            Kind.PHRASE to 260f,
        )
    }
}
