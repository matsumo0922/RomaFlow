package me.matsumo.romaflow.core.morphology

/**
 * 読み（ひらがな）の先頭 prefix でマッチする lexeme を返す decoder 用辞書境界。
 *
 * reading 座標での lexical lattice 構築に使い、各位置から始まる語候補を列挙する。
 */
interface ReadingLexicon {

    /**
     * [reading] の [startOffset] 文字目から始まる全 lexeme マッチを返す。
     *
     * common-prefix 検索: startOffset の位置から読み始めてどこかで終わる辞書エントリを列挙する。
     * 返り値は [LexemeMatch.readingEndOffset] の昇順で返す。
     */
    fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch>
}

/**
 * 1 件の lexeme マッチ。reading 座標での 1 辞書語に対応する。
 *
 * [readingEndOffset] は startOffset + マッチした読み長（exclusive）。
 */
data class LexemeMatch(
    /** 読みの終端オフセット（exclusive）。 */
    val readingEndOffset: Int,
    /** マッチした lexeme。 */
    val lexeme: LexemeEntry,
)
