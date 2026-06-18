package me.matsumo.romaflow.core.ime

/**
 * preedit 上の選択状態。
 */
internal sealed interface Selection {

    /** 未選択（変換直後の既定状態）。 */
    data object None : Selection

    /** [index] 番目の segment（変換済 / 未変換かな）を選択している状態。 */
    data class Word(val index: Int) : Selection

    /**
     * [segmentIndex] 番目の segment の候補窓で preview 中の状態（B2）。
     *
     * [previewSurface] は候補窓で現在 preview している候補文字列を保持する。index は窓（IMKCandidates）が
     * 所有し、engine は選択中の候補文字列をミラーするだけにする（Swift Export 境界で List/index を授受しないため）。
     */
    data class Candidate(
        val segmentIndex: Int,
        val previewSurface: String,
    ) : Selection
}
