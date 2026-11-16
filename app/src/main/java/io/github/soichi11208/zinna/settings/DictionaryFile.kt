package io.github.soichi11208.zinna.settings

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 辞書ファイルを文字列にする。
 *
 *  ・Gboard の書き出しは ZIP の中に dictionary.txt が入っている → 中のテキストを取り出す
 *  ・ATOK や MS-IME は UTF-16 のことがある → 先頭の印（BOM）を見て読み分ける
 *  ・それ以外は UTF-8 として読む
 */
object DictionaryFile {
    fun read(bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) {
                        return decode(zip.readBytes())
                    }
                }
            }
            return ""
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.UTF_8)
    }
}
