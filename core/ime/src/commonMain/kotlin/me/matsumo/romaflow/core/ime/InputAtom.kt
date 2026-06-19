package me.matsumo.romaflow.core.ime

/**
 * ユーザーが1ストロークで入力したローマ字の原子単位。
 *
 * [id] はセッション内で単調増加するユニーク識別子。[text] は入力された文字列（通常は 1 文字）。
 * [physicalKeyCode] は将来的な物理キー対応のためのオプション値で、現在は未使用。
 */
internal data class InputAtom(
    val id: Long,
    val text: String,
    val physicalKeyCode: Int? = null,
)

