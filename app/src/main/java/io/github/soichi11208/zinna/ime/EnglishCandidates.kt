package io.github.soichi11208.zinna.ime

import android.content.Context
import android.util.Log

/**
 * カタカナ語の読みから英語の綴りを出す（あびりてぃ → ability）。
 *
 * 辞書はビルドのときに入れる（assets/dict/katakana_english.tsv：読み ⇥ 英語）。
 * 伸ばし棒「ー」のあるなしで見つからないことが多いので、「ー」を取りのぞいた形で引く。
 *   あびりてぃー → あびりてぃ  ←  あびりてぃ と打っても同じ
 * 読み込みは裏で1回だけ。読み込み中は何も出さない（打つのを待たせない）。
 */
object EnglishCandidates {

    @Volatile
    private var index: Map<String, List<String>>? = null
    @Volatile
    private var loading = false

    fun preload(context: Context) {
        if (index != null || loading) return
        loading = true
        val app = context.applicationContext
        Thread {
            index = runCatching {
                val map = HashMap<String, MutableList<String>>(32_000)
                app.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val word = line.substring(tab + 1).trim()
                        if (word.isEmpty()) continue
                        val list = map.getOrPut(normalize(line.substring(0, tab))) { mutableListOf() }
                        if (word !in list) list += word
                    }
                }
                map as Map<String, List<String>>
            }.onFailure { Log.w(TAG, "no katakana-English dictionary", it) }.getOrDefault(emptyMap())
            loading = false
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** 今の読みに合う英語（なければ空）。 */
    fun lookup(reading: String): List<String> {
        if (reading.length < 2) return emptyList()
        return index?.get(normalize(reading)).orEmpty().take(MAX)
    }

    private fun normalize(reading: String): String = reading.replace("ー", "")

    private const val ASSET = "dict/katakana_english.tsv"
    private const val TAG = "EnglishCandidates"
    private const val MAX = 3
}
