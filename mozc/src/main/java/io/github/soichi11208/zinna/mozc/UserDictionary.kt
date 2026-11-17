package io.github.soichi11208.zinna.mozc

import android.content.Context
import android.util.Log
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryImportData
import java.io.File

/**
 * The user's own words, editable one at a time.
 *
 * This version of mozc no longer exposes a per-entry protocol — `SEND_USER_DICTIONARY_COMMAND` is
 * reserved and the only way in is `IMPORT_USER_DICTIONARY`, which replaces a whole dictionary by
 * name. So we keep the authoritative list here and re-import the lot after every edit. A personal
 * dictionary is small enough that rewriting it costs nothing, and the whole-dictionary replacement
 * means add, edit and delete are all the same operation.
 *
 * Entries live in the exact TSV mozc consumes, which makes the file itself importable into desktop
 * Mozc or Google Japanese Input without conversion.
 */
class UserDictionary(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    /** Decrypted contents, or "" when there is nothing readable. */
    private fun contents(): String = SecureStore.read(file).orEmpty()

    data class Entry(
        val reading: String,
        val word: String,
        val pos: String = DEFAULT_POS,
        val comment: String = "",
    )

    fun entries(): List<Entry> {
        return runCatching {
            contents().lines().mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val f = line.split('\t')
                if (f.size < 2 || f[0].isEmpty() || f[1].isEmpty()) return@mapNotNull null
                Entry(f[0], f[1], f.getOrNull(2)?.ifEmpty { DEFAULT_POS } ?: DEFAULT_POS,
                    f.getOrNull(3).orEmpty())
            }
        }.onFailure { Log.e(TAG, "cannot read $FILE_NAME", it) }.getOrDefault(emptyList())
    }

    /** The list as mozc's own TSV, or null when there is nothing stored. See [ProfileBackup]. */
    fun exportTsv(): String? = SecureStore.read(file)

    /**
     * Replaces the whole list with [tsv], as restoring a backup does.
     *
     * Does not push the result into mozc: this runs during start-up, before the engine exists, and
     * the start-up path syncs immediately afterwards anyway.
     */
    fun importTsv(tsv: String) {
        SecureStore.write(file, tsv)
    }

    /**
     * ほかのキーボードが書き出した辞書ファイルの中身を、今の辞書に足す。足した語数を返す。
     *
     * 読める形（1行に1語、タブ区切り）:
     *   Google日本語入力・Mozc : よみ ⇥ 単語 ⇥ 品詞 ⇥ コメント
     *   Gboard                : よみ ⇥ 単語 ⇥ ja-JP
     *   ATOK・MS-IME など      : よみ ⇥ 単語 ⇥ 品詞
     * 「#」や「!」で始まる行（説明行）は飛ばす。同じ「よみ＋単語」がすでにあれば足さない。
     */
    fun importFrom(text: String): Int {
        val current = entries()
        val known = current.map { it.reading to it.word }.toHashSet()
        val added = mutableListOf<Entry>()
        for (raw in text.lines()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) continue
            val f = line.split('\t')
            if (f.size < 2) continue
            val reading = f[0].trim()
            val word = f[1].trim()
            if (reading.isEmpty() || word.isEmpty()) continue
            // 文字ではないもの（画像やバックアップのような中身）は単語にしない
            if (looksGarbled(reading) || looksGarbled(word)) continue
            val posField = f.getOrNull(2)?.trim().orEmpty()
            // Gboard は3列目が品詞ではなく言語（ja-JP）。品詞が分からないものは名詞にする。
            val pos = if (posField.isEmpty() || posField.matches(Regex("[A-Za-z]{2}(-[A-Za-z]{2})?"))) {
                DEFAULT_POS
            } else {
                posField
            }
            val entry = Entry(reading, word, pos, f.getOrNull(3)?.trim().orEmpty()).sanitized() ?: continue
            if (known.add(entry.reading to entry.word)) added += entry
        }
        if (added.isNotEmpty()) write(current + added)
        return added.size
    }

    /** 文字化けした単語（読めない文字・制御文字を含むもの）をまとめて消す。消した語数を返す。 */
    fun removeGarbled(): Int {
        val current = entries()
        val kept = current.filterNot { looksGarbled(it.reading) || looksGarbled(it.word) || looksGarbled(it.comment) }
        val removed = current.size - kept.size
        if (removed > 0) write(kept)
        return removed
    }

    /** Appends [entry], or replaces the one at [replacing] when editing. */
    fun save(entry: Entry, replacing: Entry? = null): List<Entry> {
        val sanitized = entry.sanitized() ?: return entries()
        val current = entries().toMutableList()
        val index = replacing?.let { current.indexOf(it) } ?: -1
        if (index >= 0) current[index] = sanitized else current += sanitized
        return write(current)
    }

    fun delete(entry: Entry): List<Entry> = write(entries() - entry)

    private fun write(entries: List<Entry>): List<Entry> {
        SecureStore.write(
            file,
            entries.joinToString("\n") { "${it.reading}\t${it.word}\t${it.pos}\t${it.comment}" },
        )
        appContext.getSharedPreferences("ime_settings", Context.MODE_PRIVATE).let { prefs ->
            prefs.edit().putInt(REVISION_KEY, prefs.getInt(REVISION_KEY, 0) + 1).apply()
        }
        sync()
        return entries
    }

    /**
     * Pushes the current list into mozc.
     *
     * An empty payload makes mozc drop the dictionary entirely, which is what should happen when
     * the last entry goes. Called after every edit and once at startup, so a profile wiped by
     * "clear app data" refills itself.
     */
    fun sync() {
        val prefs = appContext.getSharedPreferences("ime_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("zenzai_plus_enabled", false) &&
            !prefs.getBoolean("zenzai_mozc_enabled", true)) return
        sync(MozcEngine.get(appContext) ?: return)
    }

    /** Overload for callers that already hold the engine, e.g. its own start-up path. */
    fun sync(engine: MozcEngine) {
        val tsv = contents()
        val output = engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.IMPORT_USER_DICTIONARY)
                .setUserDictionaryImportData(
                    UserDictionaryImportData.newBuilder()
                        .setDictionaryName(DICTIONARY_NAME)
                        .setData(tsv)
                )
        )
        if (output == null) Log.e(TAG, "IMPORT_USER_DICTIONARY failed for $DICTIONARY_NAME")
    }

    /**
     * Removes the bundled dictionaries that older versions imported here.
     *
     * They live in the system dictionary now, but the move only stopped writing them — it could not
     * take back what was already on the device, because [Input.CommandType.IMPORT_USER_DICTIONARY]
     * replaces one dictionary by name and these carry names of their own. Left alone they keep the
     * user-dictionary cost bonus, which is large enough to put カタカナ from a Wikipedia heading
     * above the ordinary word someone was reaching for.
     *
     * An empty payload drops a dictionary, and dropping one that was never there is not an error, so
     * this needs no record of which version installed what.
     *
     * Repeated on every start rather than stamped as done once. The import is asynchronous — the
     * call returns as soon as the request is queued, and nothing reports back — so a stamp would
     * record that the work was *requested*, and an app killed before the queue drained would carry
     * the dictionaries forever while claiming to have removed them. That is likeliest right after an
     * update, which is the only moment this matters. Repeating costs nothing: [sync] already makes
     * the same pass load and save the storage on every start.
     */
    fun dropLegacyBundledDictionaries(engine: MozcEngine) {
        for (name in LEGACY_DICTIONARY_NAMES) {
            engine.eval(
                Input.newBuilder()
                    .setType(Input.CommandType.IMPORT_USER_DICTIONARY)
                    .setUserDictionaryImportData(
                        UserDictionaryImportData.newBuilder()
                            .setDictionaryName(name)
                            .setData("")
                    )
            ) ?: Log.e(TAG, "could not drop legacy dictionary $name")
        }
    }

    /**
     * TSV has no escapes, so a tab or newline in a field would silently shift every later column.
     * Strip them rather than rejecting the edit — the user meant the text, not the whitespace.
     */
    private fun Entry.sanitized(): Entry? {
        fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]"), " ").trim()
        val r = clean(reading)
        val w = clean(word)
        if (r.isEmpty() || w.isEmpty()) return null
        return Entry(r, w, clean(pos).ifEmpty { DEFAULT_POS }, clean(comment))
    }

    companion object {
        /** 読めない文字（�）や制御文字が入っていたら、文字化けとみなす。 */
        fun looksGarbled(text: String): Boolean =
            text.any { it == '\uFFFD' || (Character.isISOControl(it) && it != '\t') } || text.contains("ZINNABAK")


        const val REVISION_KEY = "zenzai_dictionary_revision"
        private const val TAG = "UserDictionary"
        private const val FILE_NAME = "user_dictionary.enc"

        /** Shown in mozc's dictionary list, and kept distinct from the bundled dictionaries. */
        const val DICTIONARY_NAME = "ユーザー辞書"

        const val DEFAULT_POS = "名詞"

        /**
         * What the bundled dictionaries were called while they lived in the user dictionary.
         *
         * Every name any released version ever used: they were introduced together and removed
         * together, and none was renamed in between, so nothing else can be out there under a name
         * this list misses.
         */
        private val LEGACY_DICTIONARY_NAMES = listOf(
            "ニコニコ大百科×ピクシブ百科事典",
            "カタカナ語→英語",
            "Wikipedia 見出し",
        )

        /**
         * Part-of-speech names mozc accepts in TSV, in the order its own proto declares them —
         * the common ones come first, so the picker needs no separate curation.
         *
         * Source: UserDictionary.PosType in protocol/user_dictionary_storage.proto.
         */
        val POS_TYPES = listOf(
            "名詞", "短縮よみ", "サジェストのみ", "固有名詞", "人名", "姓", "名",
            "組織", "地名", "名詞サ変", "名詞形動", "数", "アルファベット", "記号", "顔文字",
            "副詞", "連体詞", "接続詞", "感動詞",
            "接頭語", "助数詞", "接尾一般", "接尾人名", "接尾地名",
            "動詞ワ行五段", "動詞カ行五段", "動詞サ行五段", "動詞タ行五段", "動詞ナ行五段",
            "動詞マ行五段", "動詞ラ行五段", "動詞ガ行五段", "動詞バ行五段", "動詞ハ行四段",
            "動詞一段", "動詞カ変", "動詞サ変", "動詞ザ変", "動詞ラ変",
            "形容詞", "終助詞", "句読点", "独立語", "抑制単語", "品詞なし",
        )
    }
}
