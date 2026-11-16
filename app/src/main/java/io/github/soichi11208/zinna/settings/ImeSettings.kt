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
class ImeSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val backgroundDir = File(context.applicationContext.filesDir, BACKGROUND_DIR)

    /** Forces a true-black panel. On OLED those pixels are switched off entirely. */
    var pureBlack: Boolean
        get() = prefs.getBoolean(KEY_PURE_BLACK, false)
        set(value) = prefs.edit().putBoolean(KEY_PURE_BLACK, value).apply()

    var zenzaiPlusEnabled: Boolean
        get() = prefs.getBoolean(KEY_ZENZAI_PLUS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ZENZAI_PLUS, value).apply()
            bumpRevision()
        }

    var zenzaiMozcEnabled: Boolean
        get() = prefs.getBoolean(KEY_ZENZAI_MOZC, false)
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

    /** リキッドグラス風の見た目（半透明のキー）。 */
    var liquidGlass: Boolean
        get() = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
            bumpRevision()
        }

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
        private const val KEY_ONE_HAND = "one_hand_mode"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
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
