package me.matsumo.romaflow.core.morphology

/**
 * IPADIC の sys.dic を自前パースして得た形態素の完全情報。
 *
 * [IpadicEntry] が wcost と reading のみ保持するのに対し、連接 ID（[lcAttr] / [rcAttr]）と
 * 品詞 ID（[posId]）も保持するため、Viterbi デコーダが連接コストを計算する際に必要な
 * 全情報を一括管理できる。[reading] はカタカナで格納される（辞書由来のまま）。
 */
data class LexemeEntry(
    /** 表層形（Darts trie のキーから復元した文字列）。 */
    val surface: String,
    /** 読み（カタカナ）。feature CSV の index 7。 */
    val reading: String,
    /** 左文脈 ID。前の語との連接コスト計算に使う（token の lcAttr）。 */
    val lcAttr: Int,
    /** 右文脈 ID。次の語との連接コスト計算に使う（token の rcAttr）。 */
    val rcAttr: Int,
    /** 品詞 ID（token の posId）。 */
    val posId: Int,
    /** 単語生起コスト（token の wcost）。値が小さいほど出現しやすい。 */
    val wcost: Int,
)
