package me.matsumo.romaflow.core.ime

/**
 * alignment 結果の 1 segment。[token] は形態素、[range] は対応付けられた readingInput 上のスパン。
 *
 * [range] の confidence は readingInput[range] と [token] の読みが完全一致したときのみ 1f になる。
 * 完全一致＝LLM がその語の読みを訂正していないことを意味し、per-segment revert を許可してよい根拠になる。
 */
internal data class AlignedSegment(
    val token: SegmentToken,
    val range: TextRange,
)

/**
 * 変換結果を分割した token 列を、打った通りのかな（readingInput）上のスパンへ対応付ける seam。
 *
 * LLM は誤字訂正を行うため、token の読み（補正後）と readingInput（打った通り）は一致しないことがある。
 * 実装はこのズレを許容しつつ各 token に [TextRange] を与える。テストでは [FakeAligner] を注入する。
 */
internal interface ReadingAligner {

    /** [tokens] の読み列を [readingInput] へ対応付け、各 token に range を与えた [AlignedSegment] 列を返す。 */
    fun align(readingInput: String, tokens: List<SegmentToken>): List<AlignedSegment>
}
