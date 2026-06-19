package me.matsumo.romaflow.core.ime.shadow

/**
 * 左からの連続 prefix lock を表す制約。
 *
 * ユーザーが候補を確定した節は「固定 prefix」として扱い、後続の再変換から除外する。
 * [lockedPrefixBoundary] は source 上の読みインデックス（ひらがな文字インデックス）で、
 * 0 〜 boundary（exclusive）の読み範囲が確定済みとなる。
 *
 * [pinnedPath] は固定された prefix 範囲に対応する lexeme 経路。
 * 候補確定時に [CompositionGraph] の選択経路から切り出して格納する。
 *
 * ## 設計上の制約（A-3 スコープ）
 * - lock は常に先頭から連続した prefix のみ（`0..lockedPrefixBoundary`）をサポートする。
 * - 任意複数 locked span の一般化は §11 で延期する。
 */
internal data class PinnedPathConstraint(
    /**
     * 固定済み prefix の終端（reading 上の文字インデックス、exclusive）。
     *
     * 値が 0 の場合は固定なし（unlock 状態）を意味する。
     */
    val lockedPrefixBoundary: Int,
    /**
     * 固定された prefix に対応する確定済み lexeme 経路。
     *
     * `lockedPrefixBoundary == 0` のときは空リスト。
     * 各エントリの surface 連結が確定済みの表示文字列になる。
     */
    val pinnedPath: List<me.matsumo.romaflow.core.morphology.LexemeEntry>,
) {

    /** 固定済み prefix の表示文字列（surface 連結）。 */
    val pinnedSurface: String
        get() {
            val builder = StringBuilder()

            for (lexeme in pinnedPath) {
                builder.append(lexeme.surface)
            }

            return builder.toString()
        }

    /** prefix が固定されているか（boundary > 0）。 */
    val hasLockedPrefix: Boolean
        get() = lockedPrefixBoundary > 0

    companion object {

        /** 固定なし（初期状態）。 */
        fun empty(): PinnedPathConstraint {
            return PinnedPathConstraint(
                lockedPrefixBoundary = 0,
                pinnedPath = emptyList(),
            )
        }
    }
}
