package io.github.soichi11208.zinna.ime

import android.content.Context
import android.util.Log

/**
 * 郵便番号 → 住所（1000001 / 100-0001 → 東京都千代田区千代田）。
 *
 * 住所のデータは日本郵便の一覧を、ビルドのときに assets/dict/zipcode.tsv に入れておく
 * （番号 ⇥ 住所、番号の順に並んでいる）。スマホの中で引くだけでネットは使わない。
 * 12万行あるので、表（Map）は作らずにファイルの中身をそのまま持って、二分探索で引く。
 * 初めて数字を打ったときに裏で読み込む（読み込み中は何も出さない）。
 */
object ZipCodes {

    @Volatile
    private var data: ByteArray? = null
    @Volatile
    private var lineStarts: IntArray? = null
    @Volatile
    private var loading = false

    fun preload(context: Context) {
        if (data != null || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            runCatching {
                val bytes = app.assets.open(ASSET).use { it.readBytes() }
                val starts = ArrayList<Int>(130_000)
                starts += 0
                for (i in bytes.indices) if (bytes[i] == '\n'.code.toByte() && i + 1 < bytes.size) starts += i + 1
                lineStarts = starts.toIntArray()
                data = bytes
            }.onFailure { Log.w(TAG, "no postal-code list", it) }
            // 失敗しても何度も読みに行かない
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** 打った文字が郵便番号なら、その番号（7けたの数字）。違えば null。 */
    fun codeOf(preedit: String): String? {
        val t = buildString {
            for (c in preedit) append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    'ー', '−', '－', '‐' -> '-'
                    '〒' -> ' '
                    else -> c
                }
            )
        }.trim()
        return when {
            t.length == 7 && t.all { it.isDigit() } -> t
            t.length == 8 && t[3] == '-' && t.removeRange(3, 4).all { it.isDigit() } -> t.removeRange(3, 4)
            else -> null
        }
    }

    /** 住所の候補（見つからなければ空）。 */
    fun lookup(preedit: String): List<String> {
        val code = codeOf(preedit) ?: return emptyList()
        val bytes = data ?: return emptyList()
        val starts = lineStarts ?: return emptyList()
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compare(bytes, starts[mid], code)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> {
                    val start = starts[mid]
                    var end = start
                    while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
                    val cols = String(bytes, start, end - start, Charsets.UTF_8).split('\t')
                    val pretty = code.substring(0, 3) + "-" + code.substring(3)
                    val addresses = cols.drop(1).filter { it.isNotEmpty() }
                    return addresses + addresses.take(1).map { "〒$pretty $it" }
                }
            }
        }
        return emptyList()
    }

    private fun compare(bytes: ByteArray, start: Int, code: String): Int {
        for (i in 0 until 7) {
            val b = if (start + i < bytes.size) bytes[start + i].toInt() else 0
            val d = b - code[i].code
            if (d != 0) return d
        }
        return 0
    }

    private const val ASSET = "dict/zipcode.tsv"
    private const val TAG = "ZipCodes"
}
