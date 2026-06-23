package me.matsumo.romaflow.core.morphology

/**
 * 辞書（Mozc compact binary 等）から復元した形態素の完全情報。
 *
 * 連接 ID（[lcAttr] / [rcAttr]）と品詞 ID（[posId]）も保持するため、Viterbi デコーダが連接コストを
 * 計算する際に必要な全情報を一括管理できる。[reading] は辞書由来のまま格納される
 * （Mozc はひらがな）。
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
