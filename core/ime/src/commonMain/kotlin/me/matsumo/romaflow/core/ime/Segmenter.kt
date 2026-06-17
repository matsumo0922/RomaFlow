package me.matsumo.romaflow.core.ime

/**
 * 形態素解析で得た 1 token。[surface] は表層形、[reading] はひらがな読み。
 */
internal data class SegmentToken(
    val surface: String,
    val reading: String,
)

/**
 * 変換結果テキストを segment へ分割する seam。テストでは [FakeSegmenter] を注入する。
 */
internal interface Segmenter {

    /** [text] を token 列へ分割する。surface を連結すると [text] に戻る。 */
    fun segment(text: String): List<SegmentToken>
}
