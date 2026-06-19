package me.matsumo.romaflow.core.morphology

/**
 * IPADIC の sys.dic からリッチエントリ（[LexemeEntry]）を読み込む辞書リーダー。
 *
 * [IpadicDictReader] が [IpadicEntry] を返すのに対し、こちらは lcAttr / rcAttr / posId も含む
 * [LexemeEntry] を返す。sys.dic バイト列の取得は [loadSysDicBytes] に委譲する。
 * パース結果は [lazy] でキャッシュされ、初回のみ実行される。
 */
class IpadicRichDictReader {

    private val cachedEntries: List<LexemeEntry> by lazy { parseAllRichEntries() }

    /** sys.dic を解析した全 [LexemeEntry] を返す。結果はキャッシュされる。 */
    fun readRichEntries(): List<LexemeEntry> = cachedEntries

    private fun parseAllRichEntries(): List<LexemeEntry> {
        val bytes = loadSysDicBytes()

        return SysDicParser.parseRichEntries(bytes)
    }
}
