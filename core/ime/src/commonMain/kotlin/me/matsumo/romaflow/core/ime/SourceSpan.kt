package me.matsumo.romaflow.core.ime

/**
 * `TransliterationTrace` 上のエッジが参照する atoms のスパン。
 *
 * [fromAtomIndex] は atoms リスト上の開始インデックス（含む）、[toAtomIndex] は終了インデックス（含まない）。
 * pending edge（末尾の未確定ローマ字）では [toAtomIndex] が atoms の末尾を指す。
 */
internal data class SourceSpan(
    val fromAtomIndex: Int,
    val toAtomIndex: Int,
) {

    /** このスパンが atoms を1つも含まない空スパンかどうか。 */
    val isEmpty: Boolean get() = fromAtomIndex >= toAtomIndex

    /** このスパンに含まれる atoms の個数。 */
    val size: Int get() = (toAtomIndex - fromAtomIndex).coerceAtLeast(0)
}
