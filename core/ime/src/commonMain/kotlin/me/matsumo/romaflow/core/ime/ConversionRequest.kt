package me.matsumo.romaflow.core.ime

/**
 * call1（全文かな漢字変換）で LLM へ渡す lock 制約の 1 件。
 *
 * 全文再変換時に locked された segment を固定スプライスし直すための再同定アンカー。
 * [lockId] は session-local 一意 ID、[range] は readingInput 上のスパン、[leftReading] / [rightReading] は
 * 近傍読み（同一 surface が複数あっても固定先を誤らないための曖昧性回避）。B1a では生成せず B2 で使用する。
 */
internal data class LockedSpan(
    val lockId: Int,
    val range: TextRange,
    val surface: String,
    val leftReading: String,
    val rightReading: String,
)

/**
 * call1（全文かな漢字変換）の provider への入力。
 *
 * [readingInput] は打った通りのかな全体、[locked] は固定する segment の制約。B1a では [locked] は常に空。
 */
internal data class ConversionRequest(
    val readingInput: String,
    val locked: List<LockedSpan>,
)
