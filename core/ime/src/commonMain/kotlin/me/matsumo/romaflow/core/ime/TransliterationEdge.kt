package me.matsumo.romaflow.core.ime

/**
 * romaji→kana 変換の1エッジ。
 *
 * [sourceSpan] は対応する [InputAtom] 群のスパン、[reading] は変換後のかな（または Latin 表層）、
 * [state] はこのエッジの確定状態、[unit] はこのエッジの削除正規化単位。
 *
 * `n` など複数 atoms が来て初めて解釈が確定するケース（`n`+子音→ん、`tt`+母音→っ+CV、`kyo` 一括→きょ）は、
 * 対応する全 atoms がそろった時点でエッジを1つ生成する。
 *
 * ## unit と削除写像
 * 通常の romaji→kana エッジは [SourceUnit.Romaji]（atoms id 列を保持）。
 * committed 末尾エッジの backspace で reading が複数文字の場合（`きょ→き`）は、残った reading を
 * [SourceUnit.LiteralReading] として持つエッジに置き換える。これにより A-2 lattice での
 * OOV/literal arc 判定が型で表現される。
 */
internal data class TransliterationEdge(
    val sourceSpan: SourceSpan,
    val reading: String,
    val state: EdgeState,
    val unit: SourceUnit,
) {

    /**
     * エッジの確定状態。
     *
     * - [Committed]: 読みが確定済み（readingInput へ frozen された部分）。
     * - [Pending]: 末尾の未確定ローマ字（まだ readingInput へ移動していない部分）。
     */
    enum class EdgeState {

        /** 確定済みのかな（readingInput に frozen 済み）。 */
        Committed,

        /** 未確定のローマ字（pendingRomaji として残っている末尾部分）。 */
        Pending,
    }
}
