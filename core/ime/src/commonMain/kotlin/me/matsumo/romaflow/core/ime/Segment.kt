package me.matsumo.romaflow.core.ime

/**
 * preedit を構成する 1 segment。
 *
 * [surface] は表示文字列（漢字 or かな）、[reading] は形態素解析で得た補正後の読み（ひらがな）。
 * [range] は readingInput 上のスパンで、B1a では常に null、B1b 以降の DP alignment で付与する。
 * [candidates] は単語候補で B2 で使用する。
 */
internal data class Segment(
    val surface: String,
    val reading: String,
    val range: TextRange?,
    val status: SegmentStatus,
    val candidates: List<String>,
)
