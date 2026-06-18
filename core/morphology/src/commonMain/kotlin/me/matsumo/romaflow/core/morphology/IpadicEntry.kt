package me.matsumo.romaflow.core.morphology

import androidx.compose.runtime.Immutable

/**
 * IPADIC の sys.dic を自前パースして得た 1 辞書エントリ。
 *
 * Darts(double-array trie)側に格納された表層形と、token から引いた feature CSV 由来の
 * 読み・単語コストを 1 件として表す。読み（[reading]）をキーに同音異義候補を逆引きするための
 * 素材となる。同一表層形に複数 token が紐づく場合は、その数だけ別エントリとして列挙される。
 */
@Immutable
data class IpadicEntry(
    /** 表層形（Darts trie のキーから復元した文字列）。 */
    val surface: String,
    /** 読み（カタカナ）。feature CSV の index 7。空や "*" のエントリは列挙時に除外される。 */
    val reading: String,
    /** 単語生起コスト（token の wcost）。値が小さいほど出現しやすい。 */
    val wcost: Int,
)
