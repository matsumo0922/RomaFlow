package me.matsumo.romaflow.core.morphology

/**
 * 読み文字列の正規化ユーティリティ。
 *
 * 辞書（IPADIC / Mozc）由来の読みや検索クエリを、逆引き index のキーとして揃えるために
 * カタカナをひらがなへ正規化する。複数の [ReadingLexicon] / [HomophoneDictionary] 実装が
 * 同一の正規化を共有することで、辞書をまたいでも逆引きキーが一致する。
 */
object ReadingNormalizer {

    /** カタカナブロックの開始コードポイント（U+30A1 ァ）。 */
    private const val KATAKANA_BLOCK_START = 0x30A1

    /** カタカナブロックの終了コードポイント（U+30F6 ヶ）。 */
    private const val KATAKANA_BLOCK_END = 0x30F6

    /** ひらがなブロックの開始コードポイント（U+3041 ぁ）。 */
    private const val HIRAGANA_BLOCK_START = 0x3041

    /** カタカナ→ひらがなのコードポイント差分。 */
    private const val KATAKANA_TO_HIRAGANA_OFFSET = KATAKANA_BLOCK_START - HIRAGANA_BLOCK_START

    /** カタカナをひらがなに変換する。カタカナ以外の文字はそのまま返す。 */
    fun katakanaToHiragana(reading: String): String {
        val builder = StringBuilder(reading.length)

        for (character in reading) {
            val code = character.code
            val isKatakana = code in KATAKANA_BLOCK_START..KATAKANA_BLOCK_END

            builder.append(if (isKatakana) (code - KATAKANA_TO_HIRAGANA_OFFSET).toChar() else character)
        }

        return builder.toString()
    }
}
