package me.matsumo.romaflow.core.morphology

/**
 * IPADIC を素材にした [ReadingLexicon] の実装。
 *
 * [IpadicRichDictReader.readRichEntries] の全エントリ（reading はカタカナ）から
 * ひらがなキー → [LexemeEntry] リストの逆引き index を構築する。
 * index 構築は重いため [lazy] で初回のみ実行する。
 */
class IpadicReadingLexicon(
    private val richReader: IpadicRichDictReader = IpadicRichDictReader(),
) : ReadingLexicon {

    private val reverseIndex: Map<String, List<LexemeEntry>> by lazy {
        buildReverseIndex(richReader.readRichEntries())
    }

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

        private const val KATAKANA_BLOCK_START = 0x30A1
        private const val KATAKANA_BLOCK_END = 0x30F6
        private const val HIRAGANA_BLOCK_START = 0x3041
        private const val KATAKANA_TO_HIRAGANA_OFFSET = KATAKANA_BLOCK_START - HIRAGANA_BLOCK_START

        /** カタカナをひらがなに変換する。カタカナ以外の文字はそのまま返す。 */
        internal fun katakanaToHiragana(reading: String): String {
            val builder = StringBuilder(reading.length)

            for (character in reading) {
                val code = character.code
                val isKatakana = code in KATAKANA_BLOCK_START..KATAKANA_BLOCK_END

                builder.append(if (isKatakana) (code - KATAKANA_TO_HIRAGANA_OFFSET).toChar() else character)
            }

            return builder.toString()
        }

        private fun buildReverseIndex(entries: List<LexemeEntry>): Map<String, List<LexemeEntry>> {
            val mutableMap = mutableMapOf<String, MutableList<LexemeEntry>>()

            for (entry in entries) {
                val key = katakanaToHiragana(entry.reading)

                mutableMap.getOrPut(key) { mutableListOf() }.add(entry)
            }

            return mutableMap.mapValues { (_, list) -> list.sortedBy { it.wcost } }
        }
    }
}
