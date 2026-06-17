package me.matsumo.romaflow.core.ime

/**
 * preedit 上の選択状態。
 */
internal sealed interface Selection {

    /** 未選択（変換直後の既定状態）。 */
    data object None : Selection

    /** [index] 番目の segment（変換済 / 未変換かな）を選択している状態。 */
    data class Word(val index: Int) : Selection

    /** [segmentIndex] 番目の segment の候補窓で [candidateIndex] 番目を選択している状態（B2）。 */
    data class Candidate(
        val segmentIndex: Int,
        val candidateIndex: Int,
    ) : Selection
}
