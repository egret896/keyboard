package io.github.soichi11208.zinna.ime

import android.content.Context
import android.util.Log

/**
 * 記号の読み（やじるし → → ← ↑ ↓、ほし → ☆ ★、おんぷ → ♪）。
 *
 * 読みと記号の表は Mozc の記号表（data/symbol/symbol.tsv）から作った assets/symbols.tsv。
 * Mozc も記号を出すが、変換キーを押したあとにしか出てこないので、打っている途中でも出す。
 */
object SymbolReadings {

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
                val map = HashMap<String, List<String>>(1_500)
                app.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val cols = line.split('\t')
                        if (cols.size >= 2) map[cols[0]] = cols.drop(1)
                    }
                }
                map as Map<String, List<String>>
            }.onFailure { Log.w(TAG, "no symbol table", it) }.getOrDefault(emptyMap())
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun lookup(reading: String): List<String> =
        if (reading.length < 2) emptyList() else index?.get(reading).orEmpty().take(MAX)

    private const val ASSET = "symbols.tsv"
    private const val TAG = "SymbolReadings"
    private const val MAX = 12
}
