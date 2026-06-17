package me.matsumo.romaflow.core.ime

/**
 * 1 入力セッションの変換 draft。
 *
 * [input] は romaji 入力層、[segments] は直近の全文変換で得た segment 列（未変換時は空）、
 * [selection] は単語選択カーソルの状態。
 */
internal data class ConversionDraft(
    val input: InputBuffer,
    val segments: List<Segment>,
    val selection: Selection,
)
