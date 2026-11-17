package io.github.soichi11208.zinna.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.soichi11208.zinna.keyboard.FlickGuideStyle
import io.github.soichi11208.zinna.keyboard.KeyboardStyle
import java.io.File

/**
 * User-tunable appearance settings.
 *
 * Separate from [io.github.soichi11208.zinna.theme.KeyboardTheme]: a theme is a shareable file describing a look,
 * while these are per-device choices layered on top of whichever theme is active. Pure black is
 * about this screen's panel, and a background photo belongs to this user — neither travels with a
 * theme someone hands you.
 *
 * Backed by SharedPreferences rather than DataStore because the IME reads them synchronously while
 * building its input view, on a path where suspending would mean rendering the keyboard twice.
 */
class ImeSettings(private val context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val backgroundDir = File(context.applicationContext.filesDir, BACKGROUND_DIR)

    /** AIモデルがAPKに入っているか。入っていれば、AI変換を最初からONにする。 */
    private val zenzaiBundled: Boolean =
        runCatching { context.applicationContext.assets.open("zenzai/model.json").use { } }.isSuccess

    /** Forces a true-black panel. On OLED those pixels are switched off entirely. */
    var pureBlack: Boolean
        get() = prefs.getBoolean(KEY_PURE_BLACK, false)
        set(value) = prefs.edit().putBoolean(KEY_PURE_BLACK, value).apply()

    var zenzaiPlusEnabled: Boolean
        get() = prefs.getBoolean(KEY_ZENZAI_PLUS, zenzaiBundled)
        set(value) {
            prefs.edit().putBoolean(KEY_ZENZAI_PLUS, value).apply()
            bumpRevision()
        }

    var zenzaiMozcEnabled: Boolean
        // AIの候補に加えて、ふつうの変換（Mozc）の候補や予測も出す。
        get() = prefs.getBoolean(KEY_ZENZAI_MOZC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ZENZAI_MOZC, value).apply()
            bumpRevision()
        }

    /** プライベートモード（キーボード上のボタンで切り替え）。学習・履歴を残さない。 */
    var privateMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVATE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVATE_MODE, value).apply()

    /** 定型文。1行に1つ。 */
    var phrases: List<String>
        get() = (prefs.getString(KEY_PHRASES, null) ?: DEFAULT_PHRASES)
            .split("\n").map { it.trimEnd() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_PHRASES, value.joinToString("\n")).apply()

    /** 定型文を設定画面で編集するときの生テキスト。 */
    var phrasesText: String
        get() = prefs.getString(KEY_PHRASES, null) ?: DEFAULT_PHRASES
        set(value) = prefs.edit().putString(KEY_PHRASES, value).apply()

    /** Fold を開いた（横幅が広い）ときに、左右分割の両手レイアウトにする。 */
    var splitWhenWide: Boolean
        get() = prefs.getBoolean(KEY_SPLIT_WIDE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SPLIT_WIDE, value).apply()
            bumpRevision()
        }

    /** フリックと判定するまでに指を動かす距離（dp）。小さいほど敏感。 */
    var flickThresholdDp: Float
        get() = prefs.getFloat(KEY_FLICK_THRESHOLD, DEFAULT_FLICK_THRESHOLD_DP)
        set(value) {
            prefs.edit().putFloat(KEY_FLICK_THRESHOLD, value).apply()
            bumpRevision()
        }

    /** 連打入力（ケータイ入力）。ONだと「あ」を続けて押すと あ→い→う… と変わる。 */
    var toggleInput: Boolean
        get() = prefs.getBoolean(KEY_TOGGLE_INPUT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TOGGLE_INPUT, value).apply()
            bumpRevision()
        }

    /** ⌫ 長押しでゲージがたまり、満タンで全削除。OFFなら長押しで連続削除。 */
    var holdToClear: Boolean
        get() = prefs.getBoolean(KEY_HOLD_TO_CLEAR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HOLD_TO_CLEAR, value).apply()
            bumpRevision()
        }

    /**
     * キーの見た目。"glass"＝リキッドグラス、"minimal"＝ミニマリスト（罫線だけ）、"flat"＝平ら。
     * 前の版の「リキッドグラス ON/OFF」を引き継ぐ（OFF だった人は平ら）。
     */
    var keyLook: String
        get() = prefs.getString(KEY_KEY_LOOK, null)
            // 一時期あった「フローズン」を選んでいた人は、リキッドグラスに戻す
            ?.let { if (it == "frozen") LOOK_GLASS else it }
            ?: if (prefs.getBoolean(KEY_LIQUID_GLASS, true)) LOOK_GLASS else LOOK_FLAT
        set(value) {
            prefs.edit().putString(KEY_KEY_LOOK, value).apply()
            bumpRevision()
        }

    /** リキッドグラス風の見た目（半透明のキー）。 */
    var liquidGlass: Boolean
        get() = keyLook == LOOK_GLASS
        set(value) { keyLook = if (value) LOOK_GLASS else LOOK_FLAT }

    /** MEMENTO MORI（深い海の青と、斜めに傾いた札のキー）。 */
    val mementoKeys: Boolean get() = keyLook.startsWith(LOOK_MEMENTO)

    /** MEMENTO MORI の種類（0=A 海の青, 1=B 満月, 2=C 大胆, 3=D 線だけ）。 */
    val mementoStyle: Int get() = when (keyLook) {
        LOOK_MEMENTO_MOON -> 1
        LOOK_MEMENTO_BOLD -> 2
        LOOK_MEMENTO_LINE -> 3
        else -> 0
    }

    /** ミニマリスト（キーの間に細い線だけ）。 */
    val minimalKeys: Boolean get() = keyLook == LOOK_MINIMAL

    /** リキッドグラスのゆがみ具合（0＝ゆがまない、1＝ふつう、2＝強い）。 */
    var glassRefract: Float
        get() = prefs.getFloat(KEY_GLASS_REFRACT, 1f)
        set(value) { prefs.edit().putFloat(KEY_GLASS_REFRACT, value).apply(); bumpRevision() }

    /** リキッドグラスのくもり具合（0＝透明、1＝すりガラス）。 */
    var glassFrost: Float
        get() = prefs.getFloat(KEY_GLASS_FROST, 0.2f)
        set(value) { prefs.edit().putFloat(KEY_GLASS_FROST, value).apply(); bumpRevision() }

    /** リキッドグラスの縁の光の強さ（0〜2、1＝ふつう）。 */
    var glassLight: Float
        get() = prefs.getFloat(KEY_GLASS_LIGHT, 1f)
        set(value) { prefs.edit().putFloat(KEY_GLASS_LIGHT, value).apply(); bumpRevision() }

    /** リキッドグラスの影の濃さ（0〜2、1＝ふつう）。 */
    var glassShadow: Float
        get() = prefs.getFloat(KEY_GLASS_SHADOW, 1f)
        set(value) { prefs.edit().putFloat(KEY_GLASS_SHADOW, value).apply(); bumpRevision() }

    /** 片手モード。0 = ふつう、-1 = 左に寄せる、1 = 右に寄せる。 */
    var oneHandMode: Int
        get() = prefs.getInt(KEY_ONE_HAND, 0)
        set(value) {
            prefs.edit().putInt(KEY_ONE_HAND, value).apply()
            if (value != 0) prefs.edit().putInt(KEY_ONE_HAND_SIDE, value).apply()
        }

    /** 最後に使った片手モードの向き（次にONにしたときにこちらへ寄せる）。 */
    val oneHandLastSide: Int
        get() = prefs.getInt(KEY_ONE_HAND_SIDE, 1)

    /** 全削除ゲージが満タンになるまでの時間（ミリ秒）。 */
    var clearChargeMs: Long
        get() = prefs.getLong(KEY_CLEAR_CHARGE, 1000L)
        set(value) {
            prefs.edit().putLong(KEY_CLEAR_CHARGE, value).apply()
            bumpRevision()
        }

    /** 押したときの形（0＝角丸、1＝四角、2＝丸）。 */
    var pressShape: Int
        get() = prefs.getInt(KEY_PRESS_SHAPE, 0)
        set(value) { prefs.edit().putInt(KEY_PRESS_SHAPE, value).apply(); bumpRevision() }

    /** 押したときの色（ARGB。0＝テーマの色）。 */
    var pressColor: Int
        get() = prefs.getInt(KEY_PRESS_COLOR, 0)
        set(value) { prefs.edit().putInt(KEY_PRESS_COLOR, value).apply(); bumpRevision() }

    // ---------------------------------------------------------------- カスタマイズ
    // 色は ARGB（0 ＝テーマのまま）、フォントは Android のフォント名（"" ＝標準）、
    // 太さは 100〜900（0 ＝標準）、大きさは倍率（1 ＝そのまま）。

    private fun int(key: String) = prefs.getInt(key, 0)
    private fun setInt(key: String, v: Int) { prefs.edit().putInt(key, v).apply(); bumpRevision() }
    private fun str(key: String) = prefs.getString(key, "") ?: ""
    private fun setStr(key: String, v: String) { prefs.edit().putString(key, v).apply(); bumpRevision() }
    private fun scale(key: String) = prefs.getFloat(key, 1f)
    private fun setScale(key: String, v: Float) { prefs.edit().putFloat(key, v).apply(); bumpRevision() }

    var customKeyText: Int get() = int("c_key_text"); set(v) = setInt("c_key_text", v)
    var customKeyFont: String get() = str("c_key_font"); set(v) = setStr("c_key_font", v)
    var customKeyWeight: Int get() = int("c_key_weight"); set(v) = setInt("c_key_weight", v)
    var customKeyScale: Float get() = scale("c_key_scale"); set(v) = setScale("c_key_scale", v)
    var customKeyboardBg: Int get() = int("c_kb_bg"); set(v) = setInt("c_kb_bg", v)
    var customCandText: Int get() = int("c_cand_text"); set(v) = setInt("c_cand_text", v)
    var customCandFont: String get() = str("c_cand_font"); set(v) = setStr("c_cand_font", v)
    var customCandWeight: Int get() = int("c_cand_weight"); set(v) = setInt("c_cand_weight", v)
    var customCandScale: Float get() = scale("c_cand_scale"); set(v) = setScale("c_cand_scale", v)
    var customCandBg: Int get() = int("c_cand_bg"); set(v) = setInt("c_cand_bg", v)

    /** 振動：0 オフ、1 軽い、2 カチッ、3 押し込み。 */
    var vibrationMode: Int
        get() = prefs.getInt("vibration_mode", 2)
        set(v) { prefs.edit().putInt("vibration_mode", v).apply(); bumpRevision() }

    /** 振動の強さ 0.1〜1。 */
    var vibrationStrength: Float
        get() = prefs.getFloat("vibration_strength", 0.6f)
        set(v) { prefs.edit().putFloat("vibration_strength", v).apply(); bumpRevision() }

    /** 出さないようにした候補（長押しで追加、設定で戻せる）。 */
    var hiddenCandidates: Set<String>
        get() = prefs.getStringSet("hidden_candidates", emptySet())?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("hidden_candidates", v.toSet()).apply(); bumpRevision() }

    /** 日付・時刻（きょう・いま など）と計算（12*3= など）の候補を出すか。 */
    /** 空白を、かなの画面でも常に半角にする。 */
    var alwaysHalfSpace: Boolean
        get() = prefs.getBoolean("always_half_space", false)
        set(v) { prefs.edit().putBoolean("always_half_space", v).apply() }

    var smartCandidates: Boolean
        get() = prefs.getBoolean("smart_candidates", true)
        set(v) { prefs.edit().putBoolean("smart_candidates", v).apply(); bumpRevision() }

    /** カスタマイズを全部もとに戻す。 */
    fun resetCustom() {
        prefs.edit().apply {
            for (k in listOf("c_key_text", "c_key_font", "c_key_weight", "c_key_scale", "c_kb_bg",
                "c_cand_text", "c_cand_font", "c_cand_weight", "c_cand_scale", "c_cand_bg")) remove(k)
        }.apply()
        bumpRevision()
    }

    /** 予測変換を2行で出すか（false＝1行で横にスクロール）。 */
    var candidateTwoLines: Boolean
        get() = prefs.getBoolean(KEY_CANDIDATE_TWO_LINES, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CANDIDATE_TWO_LINES, value).apply()
            bumpRevision()
        }

    /** 括弧を入力したら閉じ括弧も入れて、カーソルを括弧の中に置く。 */
    var autoBracket: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BRACKET, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BRACKET, value).apply()

    /** How strongly the background image shows through, 0f–1f. */
    var backgroundOpacity: Float
        get() = prefs.getFloat(KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY)
        set(value) = prefs.edit().putFloat(KEY_BACKGROUND_OPACITY, value.coerceIn(0f, 1f)).apply()

    /**
     * Multiplier on the theme's key height.
     *
     * A scale rather than an absolute height so it composes with whatever theme is active instead
     * of overriding a value the theme author chose. The range stops well short of filling the
     * screen: past that the candidate strip and the editor stop sharing the display usefully.
     *
     * Defaults slightly above the theme's own height. The theme value read well in the layout
     * editor and short in the hand, and 106% is where it stopped feeling cramped without eating
     * screen. Anyone who has already moved the slider keeps what they chose — this is only the
     * value for a setting nobody has touched.
     */
    var keyHeightScale: Float
        get() = prefs.getFloat(KEY_HEIGHT_SCALE, DEFAULT_KEY_HEIGHT_SCALE)
        set(value) = prefs.edit()
            .putFloat(KEY_HEIGHT_SCALE, value.coerceIn(MIN_KEY_HEIGHT_SCALE, MAX_KEY_HEIGHT_SCALE))
            .apply()

    /**
     * Flick, qwerty, or one of each. See [KeyboardStyle].
     *
     * Stored by name so adding a combination later does not invalidate what is on disk; an
     * unreadable value falls back to flick rather than leaving the user with no keyboard.
     */
    var keyboardStyle: KeyboardStyle
        get() = KeyboardStyle.of(prefs.getString(KEY_KEYBOARD_STYLE, null))
        set(value) {
            prefs.edit().putString(KEY_KEYBOARD_STYLE, value.name).apply()
            bumpRevision()
        }

    /**
     * What a flick key shows while it is held. See [FlickGuideStyle].
     *
     * Defaults to the preview: the cross of four directions covers the neighbouring keys, which is
     * where the eye is looking, and most of the time only one of the four is wanted.
     */
    var flickGuideStyle: FlickGuideStyle
        get() = FlickGuideStyle.of(prefs.getString(KEY_FLICK_GUIDE, null))
        set(value) {
            prefs.edit().putString(KEY_FLICK_GUIDE, value.name).apply()
            bumpRevision()
        }

    /**
     * Overrides [flickGuideStyle] with the full cross on the symbol plane.
     *
     * The preview answers "what will this type", which is enough on the kana plane because the four
     * directions of か are already known. The symbol plane is the opposite case: nobody remembers
     * that ※ hides on 7 or that ≦ is up from ±, and the preview only speaks once the finger is
     * already moving the right way. On by default for that reason — the plane it affects is small,
     * and it is the one place the cross earns the keys it covers.
     */
    var showAllDirectionsOnSymbolPlane: Boolean
        get() = prefs.getBoolean(KEY_SYMBOL_ALL_DIRECTIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYMBOL_ALL_DIRECTIONS, value).apply()
            bumpRevision()
        }

    /**
     * The keyboard background image, or null for a plain colour.
     *
     * A copy under our own files directory, not the content URI the user picked: the IME runs in a
     * process that never held the SAF grant, and the picked document can be deleted or unmounted
     * at any time. Copying makes the setting survive both.
     */
    val backgroundImage: File?
        get() = File(backgroundDir, BACKGROUND_FILE).takeIf { it.isFile }

    /** Replaces the background image with [bytes]. */
    fun setBackgroundImage(bytes: ByteArray) {
        backgroundDir.mkdirs()
        File(backgroundDir, BACKGROUND_FILE).writeBytes(bytes)
        bumpRevision()
    }

    fun clearBackgroundImage() {
        File(backgroundDir, BACKGROUND_FILE).delete()
        bumpRevision()
    }

    /**
     * Changes whenever anything here does.
     *
     * The IME compares this against the value it built its current view from, so it can rebuild
     * exactly when something moved instead of on every input session.
     */
    val revision: Int
        get() = prefs.getInt(KEY_REVISION, 0) +
            (if (pureBlack) 1 shl 16 else 0) +
            (backgroundOpacity * 100).toInt() * (1 shl 8) +
            (keyHeightScale * 100).toInt() * (1 shl 20)

    // ---------------------------------------------------------------- 趣味ボード
    //
    //  表のボード ── 辞書・履歴を使える ──▶ 趣味ボード
    //  表のボード ◀── 流れない ─────────── 趣味ボード（ここで覚えた言葉・履歴）

    /** 趣味ボード中か。切り替えるとキーボードを作り直す。 */
    var hobbyMode: Boolean
        get() = prefs.getBoolean("hobby_mode", false)
        set(v) { prefs.edit().putBoolean("hobby_mode", v).apply(); bumpRevision() }

    /** 趣味ボードの背景の色（0＝おまかせの紫）。 */
    var hobbyBg: Int
        get() = prefs.getInt("hobby_bg", 0)
        set(v) { prefs.edit().putInt("hobby_bg", v).apply(); bumpRevision() }

    /** 趣味ボードの文字の色（0＝表のボードと同じ）。 */
    var hobbyText: Int
        get() = prefs.getInt("hobby_text", 0)
        set(v) { prefs.edit().putInt("hobby_text", v).apply(); bumpRevision() }

    var hobbyBackgroundOpacity: Float
        get() = prefs.getFloat("hobby_bg_opacity", DEFAULT_BACKGROUND_OPACITY)
        set(v) { prefs.edit().putFloat("hobby_bg_opacity", v.coerceIn(0f, 1f)).apply(); bumpRevision() }

    val hobbyBackgroundImage: File?
        get() = File(backgroundDir, HOBBY_BACKGROUND_FILE).takeIf { it.isFile }

    fun setHobbyBackgroundImage(bytes: ByteArray) {
        backgroundDir.mkdirs()
        File(backgroundDir, HOBBY_BACKGROUND_FILE).writeBytes(bytes)
        bumpRevision()
    }

    fun clearHobbyBackgroundImage() {
        File(backgroundDir, HOBBY_BACKGROUND_FILE).delete()
        bumpRevision()
    }

    /** 趣味ボードで覚えた言葉（読み→言葉）。新しい順。 */
    var hobbyWords: List<Pair<String, String>>
        get() = prefs.getString("hobby_words", "").orEmpty().split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) null else line.substring(0, tab) to line.substring(tab + 1)
        }
        set(v) {
            prefs.edit().putString(
                "hobby_words",
                v.take(HOBBY_WORD_LIMIT).joinToString("\n") { "${it.first}\t${it.second}" },
            ).apply()
        }

    /** 趣味ボードで覚えた言葉と、記号・絵文字の履歴を消す。 */
    fun clearHobbyLearning() {
        prefs.edit().remove("hobby_words").apply()
        context.applicationContext.getSharedPreferences("symbol_panel", Context.MODE_PRIVATE).edit().apply {
            for (k in listOf("history_symbol", "history_emoji", "history_kaomoji", "history_phrase")) remove(k + HOBBY_SUFFIX)
        }.apply()
    }

    private fun bumpRevision() {
        prefs.edit().putInt(KEY_REVISION, prefs.getInt(KEY_REVISION, 0) + 1).apply()
    }

    companion object {
        /** Also the name [io.github.soichi11208.zinna.mozc.ProfileBackup] stores these under. */
        const val NAME = "ime_settings"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_PRIVATE_MODE = "private_mode"
        private const val KEY_PHRASES = "phrases"
        private const val KEY_SPLIT_WIDE = "split_when_wide"
        private const val KEY_FLICK_THRESHOLD = "flick_threshold_dp"
        private const val KEY_TOGGLE_INPUT = "toggle_input"
        private const val KEY_HOLD_TO_CLEAR = "hold_to_clear"
        private const val KEY_AUTO_BRACKET = "auto_bracket"
        private const val KEY_CLEAR_CHARGE = "clear_charge_ms"
        private const val KEY_CANDIDATE_TWO_LINES = "candidate_two_lines"
        private const val KEY_PRESS_SHAPE = "press_shape"
        private const val KEY_PRESS_COLOR = "press_color"
        private const val KEY_ONE_HAND = "one_hand_mode"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_KEY_LOOK = "key_look"
        const val LOOK_GLASS = "glass"
        const val LOOK_MINIMAL = "minimal"
        const val LOOK_FLAT = "flat"
        const val LOOK_MEMENTO = "memento"
        const val LOOK_MEMENTO_MOON = "memento_moon"
        const val LOOK_MEMENTO_BOLD = "memento_bold"
        const val LOOK_MEMENTO_LINE = "memento_line"
        private const val KEY_GLASS_REFRACT = "glass_refract"
        private const val KEY_GLASS_FROST = "glass_frost"
        private const val KEY_GLASS_LIGHT = "glass_light"
        private const val KEY_GLASS_SHADOW = "glass_shadow"
        private const val KEY_ONE_HAND_SIDE = "one_hand_side"
        const val DEFAULT_FLICK_THRESHOLD_DP = 24f
        const val MIN_FLICK_THRESHOLD_DP = 10f
        const val MAX_FLICK_THRESHOLD_DP = 48f
        private const val DEFAULT_PHRASES = "お疲れさまです。\nよろしくお願いします。\nありがとうございます！\n了解です！\n今から帰ります"
        private const val KEY_ZENZAI_PLUS = "zenzai_plus_enabled"
        private const val KEY_ZENZAI_MOZC = "zenzai_mozc_enabled"
        private const val KEY_BACKGROUND_OPACITY = "background_opacity"
        private const val KEY_REVISION = "revision"
        const val BACKGROUND_DIR = "backgrounds"
        private const val BACKGROUND_FILE = "keyboard_background"
        private const val HOBBY_BACKGROUND_FILE = "hobby_background"
        const val HOBBY_WORD_LIMIT = 500
        /** 趣味ボードの記号・絵文字の履歴は、この印をつけた別の場所に残す。 */
        const val HOBBY_SUFFIX = "_hobby"
        private const val KEY_HEIGHT_SCALE = "key_height_scale"
        private const val KEY_KEYBOARD_STYLE = "keyboard_style"
        private const val KEY_FLICK_GUIDE = "flick_guide_style"
        private const val KEY_SYMBOL_ALL_DIRECTIONS = "symbol_all_directions"
        const val DEFAULT_BACKGROUND_OPACITY = 0.45f
        const val DEFAULT_KEY_HEIGHT_SCALE = 1.06f
        const val MIN_KEY_HEIGHT_SCALE = 0.7f
        const val MAX_KEY_HEIGHT_SCALE = 1.5f
    }
}
