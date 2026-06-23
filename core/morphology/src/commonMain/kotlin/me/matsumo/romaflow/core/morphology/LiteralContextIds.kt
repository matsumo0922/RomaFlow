package me.matsumo.romaflow.core.morphology

/**
 * [LiteralLexicon] が生成する literal/OOV arc の連接 ID セット。
 *
 * 辞書（IPADIC / Mozc）ごとに POS 空間が異なり、OOV arc に妥当でない連接 ID を与えると
 * フォールバック経路の連接コストが歪むため、辞書に応じて差し替えられるよう値オブジェクトとして切り出す。
 */
data class LiteralContextIds(
    /** ひらがな・カタカナ素通し arc の lcAttr / rcAttr。 */
    val kanaContextId: Int,
    /** ASCII・数字・記号 arc の lcAttr / rcAttr。 */
    val symbolContextId: Int,
) {

    companion object {

        /**
         * IPADIC（momiji sys.dic）の代表 ID。既定値。
         *
         * 一般名詞（lcAttr≈1285）付近を kana arc に、独立語相当（1289）を記号 arc に割り当てる。
         * 既存のライブ挙動（A-2 で固定した値）を保つため変更しない。
         */
        val Ipadic = LiteralContextIds(
            kanaContextId = 1285,
            symbolContextId = 1289,
        )

        /**
         * Mozc OSS 辞書の代表 ID。
         *
         * Mozc `id.def` の「名詞,一般」＝id 1851 を kana / 記号 arc に割り当てる。Mozc の POS 空間
         * （0〜2671、0=BOS/EOS）に対し OOV arc が妥当な連接コストを得るための値。
         */
        val Mozc = LiteralContextIds(
            kanaContextId = 1851,
            symbolContextId = 1851,
        )
    }
}
