package me.matsumo.romaflow.core.ime

/**
 * 変換 draft 内の 1 segment が取り得る状態。
 *
 * - [Unconverted]: まだ変換していないかな。
 * - [Converted]: LLM 変換済みだが lock されておらず、全文再変換で変わってよい。
 * - [Locked]: ユーザーが候補確定して固定した状態（再変換でも変えない）。B2 で使用する。
 */
internal enum class SegmentStatus {
    Unconverted,
    Converted,
    Locked,
}
