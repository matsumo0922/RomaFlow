package me.matsumo.romaflow.core.ime

/**
 * romaji 入力層の状態。
 *
 * [readingInput] は確定済みのかな（frozen）で、LLM 投入・revert 先・alignment 対象となる source of truth。
 * [pendingRomaji] は編集カーソル末尾の未確定ローマ字（`ky` や lone `n` 等）で、romaji→kana 変換は
 * この部分にだけ増分的に適用する。
 */
internal data class InputBuffer(
    val readingInput: String,
    val pendingRomaji: String,
)
