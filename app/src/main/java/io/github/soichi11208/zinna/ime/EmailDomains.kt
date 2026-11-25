package io.github.soichi11208.zinna.ime

/**
 * メールアドレスの「@」のあとを予測する。
 *   taro@      → gmail.com / icloud.com / yahoo.co.jp …
 *   taro@ya    → yahoo.co.jp / yahoo.com
 * 返すのは「今打っている続き」だけ（taro@ya なら hoo.co.jp ではなく、表示用に yahoo.co.jp）。
 */
object EmailDomains {

    private val DOMAINS = listOf(
        "gmail.com", "icloud.com", "yahoo.co.jp", "outlook.jp", "outlook.com", "hotmail.com",
        "docomo.ne.jp", "ezweb.ne.jp", "au.com", "softbank.ne.jp", "i.softbank.jp", "ymobile.ne.jp",
        "yahoo.com", "me.com",
    )

    // 「名前@途中まで」で終わっているか
    private val PATTERN = Regex("""[A-Za-z0-9._%+\-]+@([A-Za-z0-9.\-]*)$""")

    /** @ のあとに打ちかけの部分と、それに合うドメインの一覧。メールアドレスでなければ null。 */
    fun match(text: String): Pair<String, List<String>>? {
        val m = PATTERN.find(text) ?: return null
        val partial = m.groupValues[1].lowercase()
        val hits = DOMAINS.filter { it.startsWith(partial) && it != partial }
        return if (hits.isEmpty()) null else partial to hits.take(6)
    }
}
