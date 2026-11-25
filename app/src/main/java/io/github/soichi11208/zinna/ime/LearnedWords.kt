package io.github.soichi11208.zinna.ime

import android.content.Context
import io.github.soichi11208.zinna.mozc.SecureStore
import java.io.File

/**
 * 確定した言葉（読み → 言葉）を覚えておき、次に同じ読みを打ったとき候補の先頭に出す。
 *
 * mozc もふつうは学習するが、AI変換の候補を選んだときは mozc を通らないので覚えられない。
 * その穴をうめるため、どの候補を選んでも、ここに覚える。
 *
 *   表のボード  → learned_main.enc   （表と趣味の両方で使う）
 *   趣味ボード → learned_hobby.enc  （趣味ボードだけで使う。表には出さない）
 *
 * 中身はスマホの中だけの鍵（Keystore）で暗号化して保存する。
 */
class LearnedWords(context: Context, fileName: String) {

    private val file = File(context.applicationContext.filesDir, fileName)

    /** 新しい順。読み込みは最初の1回だけ（打つたびに暗号を解かないように）。 */
    private var cache: MutableList<Pair<String, String>>? = null

    private fun list(): MutableList<Pair<String, String>> = cache ?: load().also { cache = it }

    private fun load(): MutableList<Pair<String, String>> =
        SecureStore.read(file).orEmpty().split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMutableList()

    val size: Int get() = list().size

    /** ほかの画面（設定）で消されたかもしれないので、次に使うとき読み直す。 */
    fun reload() { cache = null }

    /** 覚える（同じ組み合わせは先頭に移す）。 */
    fun remember(reading: String, word: String) {
        if (reading.isEmpty() || word.isEmpty() || reading == word) return
        if (reading.length > 50 || word.length > 50 || '\n' in word || '\t' in word || '\n' in reading) return
        val items = list()
        val pair = reading to word
        if (items.firstOrNull() == pair) return
        items.remove(pair)
        items.add(0, pair)
        while (items.size > LIMIT) items.removeAt(items.size - 1)
        SecureStore.write(file, items.joinToString("\n") { "${it.first}\t${it.second}" })
    }

    /** 今の読みに合う言葉（同じ読み → 読みの続き3つ まで）。 */
    fun lookup(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val items = list()
        val exact = items.filter { it.first == reading }.map { it.second }
        val more = items.filter { it.first != reading && it.first.startsWith(reading) }.take(3).map { it.second }
        return (exact + more).distinct()
    }

    /** 読み（ここでは「前の言葉」）がぴったり同じものだけ。続きの予測に使う。 */
    fun lookupExact(key: String): List<String> =
        if (key.isEmpty()) emptyList() else list().filter { it.first == key }.map { it.second }.distinct()

    fun clear() {
        cache = mutableListOf()
        file.delete()
    }

    companion object {
        const val LIMIT = 1000
        const val MAIN_FILE = "learned_main.enc"
        const val HOBBY_FILE = "learned_hobby.enc"
        /** 「この言葉のあとに、この言葉を打った」の組（続きの予測）。 */
        const val NEXT_MAIN_FILE = "learned_next_main.enc"
        const val NEXT_HOBBY_FILE = "learned_next_hobby.enc"
    }
}
