package me.matsumo.romaflow.core.morphology

/**
 * ひらがな・カタカナ素通し、ASCII、数字、記号、未知語の literal arc を生成する [ReadingLexicon]。
 *
 * 辞書外 1 文字でデコード経路が消失するデッドロックを防ぐため、全位置で最低 1 件の arc を保証する。
 * 連接 ID は品詞ごとの代表 ID を使い（全0回避）、wcost は意図的に高く設定して通常語に負ける。
 *
 * Unicode カテゴリで文字種を分類し、種別に応じたコストと連接 ID を割り当てる:
 * - ひらがな/カタカナ: 読み素通し arc（wcost=[HIRAGANA_WCOST]）
 * - ASCII 英字: ASCII arc（wcost=[ASCII_WCOST]）
 * - 数字: 数字 arc（wcost=[DIGIT_WCOST]）
 * - その他（記号・未知語）: 記号 arc（wcost=[SYMBOL_WCOST]）
 *
 * surface は入力そのまま、reading はひらがな/カタカナはそのまま、その他は空文字。
 *
 * 連接 ID は [contextIds] で辞書（IPADIC / Mozc）に応じて差し替える。既定は [LiteralContextIds.Ipadic]
 * のためライブ挙動は不変。
 */
internal class LiteralLexicon(
    private val contextIds: LiteralContextIds = LiteralContextIds.Ipadic,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        if (startOffset >= reading.length) return emptyList()

        val character = reading[startOffset]
        val endOffset = startOffset + 1

        val lexeme = buildLiteralLexeme(character, contextIds)

        return listOf(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
    }

    companion object {

        /**
         * ひらがな・カタカナ 1 文字の素通し arc の wcost。
         * 通常辞書語より高く、全0コスト（[ZeroConnectionCostProvider]）より適切な値。
         */
        const val HIRAGANA_WCOST = 8000

        /** ASCII 英字 1 文字の arc の wcost。 */
        const val ASCII_WCOST = 10000

        /** 数字 1 文字の arc の wcost。 */
        const val DIGIT_WCOST = 9000

        /** 記号・その他文字の arc の wcost。 */
        const val SYMBOL_WCOST = 12000

        /**
         * 1 文字 [character] に対応する literal [LexemeEntry] を生成する。
         *
         * 連接 ID は [contextIds]（既定 [LiteralContextIds.Ipadic]）から引く。
         * ひらがな/カタカナ: reading = character.toString() で素通し。その他: reading = surface（変換なし）。
         */
        fun buildLiteralLexeme(
            character: Char,
            contextIds: LiteralContextIds = LiteralContextIds.Ipadic,
        ): LexemeEntry {
            val surface = character.toString()
            val isHiragana = character.code in 0x3041..0x3096
            val isKatakana = character.code in 0x30A1..0x30F6
            val isKanaCharacter = isHiragana || isKatakana
            val isAsciiLetter = character in 'A'..'Z' || character in 'a'..'z'
            val isDigit = character.isDigit()

            return when {
                isKanaCharacter -> LexemeEntry(
                    surface = surface,
                    reading = surface,
                    lcAttr = contextIds.kanaContextId,
                    rcAttr = contextIds.kanaContextId,
                    posId = 0,
                    wcost = HIRAGANA_WCOST,
                )
                isDigit -> LexemeEntry(
                    surface = surface,
                    reading = surface,
                    lcAttr = contextIds.symbolContextId,
                    rcAttr = contextIds.symbolContextId,
                    posId = 0,
                    wcost = DIGIT_WCOST,
                )
                isAsciiLetter -> LexemeEntry(
                    surface = surface,
                    reading = surface,
                    lcAttr = contextIds.symbolContextId,
                    rcAttr = contextIds.symbolContextId,
                    posId = 0,
                    wcost = ASCII_WCOST,
                )
                else -> LexemeEntry(
                    surface = surface,
                    reading = surface,
                    lcAttr = contextIds.symbolContextId,
                    rcAttr = contextIds.symbolContextId,
                    posId = 0,
                    wcost = SYMBOL_WCOST,
                )
            }
        }
    }
}
