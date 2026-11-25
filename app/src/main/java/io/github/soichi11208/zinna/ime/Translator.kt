package io.github.soichi11208.zinna.ime

import android.content.Context
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 英訳モードの辞書（猫 → cat、たべる → eat）。
 *
 * 言葉ごとの英訳の表（assets/dict/translate.jaen）を、ビルドのときに JMdict から作って入れておく。
 * 文章を訳すAIではなく、単語の辞書を引くだけ。ネットは使わない。
 *
 * 20万語以上あるので、メモリに読み込まずにファイルをそのまま「地図のように」開いて（mmap）、
 * 並んだ行を二分探索で引く。だからキーボードのメモリはほとんど増えない。
 */
object Translator {

    @Volatile
    private var map: MappedByteBuffer? = null
    @Volatile
    private var failed = false

    private fun open(context: Context): MappedByteBuffer? {
        map?.let { return it }
        if (failed) return null
        return runCatching {
            context.applicationContext.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }.onFailure {
            failed = true
            Log.w(TAG, "no translation dictionary", it)
        }.getOrNull()?.also { map = it }
    }

    /** その言葉の英訳（なければ空）。 */
    fun lookup(context: Context, word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val buf = open(context) ?: return emptyList()
        return search(buf, word)
    }

    /** 並んだ行（言葉 ⇥ 英訳 …）の中から word の行を二分探索で探す。 */
    internal fun search(buf: ByteBuffer, word: String): List<String> {
        val key = (word + "\t").toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = buf.limit()
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            var start = mid
            while (start > lo && buf.get(start - 1) != NL) start--
            var end = start
            while (end < buf.limit() && buf.get(end) != NL) end++
            val cmp = compare(buf, start, end, key)
            when {
                cmp == 0 -> {
                    val bytes = ByteArray(end - start - key.size)
                    for (i in bytes.indices) bytes[i] = buf.get(start + key.size + i)
                    return String(bytes, Charsets.UTF_8).split('\t').filter { it.isNotEmpty() }
                }
                cmp > 0 -> hi = start          // 探している言葉はこの行より前
                else -> lo = end + 1           // この行より後
            }
        }
        return emptyList()
    }

    /** 行の頭と key（「言葉＋タブ」）を比べる。key の方が前なら正、後なら負、同じなら 0。 */
    private fun compare(buf: ByteBuffer, start: Int, end: Int, key: ByteArray): Int {
        for (i in key.indices) {
            if (start + i >= end) return -1
            val a = key[i].toInt() and 0xff
            val b = buf.get(start + i).toInt() and 0xff
            if (a != b) return if (a < b) 1 else -1
        }
        return 0
    }

    private const val NL = '\n'.code.toByte()
    private const val ASSET = "dict/translate.jaen"
    private const val TAG = "Translator"
}
