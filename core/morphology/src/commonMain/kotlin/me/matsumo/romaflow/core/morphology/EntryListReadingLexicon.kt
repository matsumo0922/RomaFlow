package me.matsumo.romaflow.core.morphology

/**
 * 任意の [LexemeEntry] 列から逆引き index を構築する汎用 [ReadingLexicon] 実装。
 *
 * Mozc などの辞書に固定されず、事前に構築済みのエントリ列を受け取るため、
 * UniDic など別辞書由来のエントリでも逆引き格子を組める。
 * U1 の UniDic shadow 比較で使用する。index 構築はコンストラクタで即時実行されるため、
 * 大規模辞書では呼び出し側がインスタンス生成のタイミングを制御すること。
 */
class EntryListReadingLexicon(
    entries: List<LexemeEntry>,
) : ReadingLexicon {

    private val reverseIndex: Map<String, List<LexemeEntry>> = buildReverseIndex(entries)

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val results = mutableListOf<LexemeMatch>()

        for (endOffset in (startOffset + 1)..reading.length) {
            val prefix = reading.substring(startOffset, endOffset)
            val matches = reverseIndex[prefix] ?: continue

            for (lexeme in matches) {
                results.add(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
            }
        }

        return results
    }

    companion object {

        private fun buildReverseIndex(entries: List<LexemeEntry>): Map<String, List<LexemeEntry>> {
            val mutableMap = mutableMapOf<String, MutableList<LexemeEntry>>()

            for (entry in entries) {
                val key = ReadingNormalizer.katakanaToHiragana(entry.reading)

                mutableMap.getOrPut(key) { mutableListOf() }.add(entry)
            }

            return mutableMap.mapValues { (_, list) -> list.sortedBy { it.wcost } }
        }
    }
}
