package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry

/**
 * shadow エンジンの表示 segment（projection）。
 *
 * live の [me.matsumo.romaflow.core.ime.Segment] は data class として独立管理されるが、
 * shadow エンジンでは selected path + source + UI preview から常に導出する projection として表現する。
 * `ShadowSegment.kt` の data class 自体は shadow 専用であり、live の `Segment.kt` を変更しない。
 *
 * ## 導出元
 * - [surface]: [CompositionGraph] の selected path の lexeme surface 連結。
 *   候補 preview 中は [previewSurface] で上書きする（非破壊 projection）。
 * - [readingSpan]: reading 上のスパン（文字インデックス）。lattice の [LexemeEntry] から導出。
 * - [status]: [PinnedPathConstraint] の boundary と比較して Locked / Converted / Unconverted を決定。
 * - [lexemePath]: このセグメントに対応する [LexemeEntry] 列。候補確定・pin に使う。
 */
internal data class ShadowSegment(
    /** 表示文字列（漢字 or かな）。候補 preview 中は [previewSurface] で上書きされる。 */
    val surface: String,
    /** reading 上の開始インデックス（inclusive、ひらがな座標）。 */
    val readingStart: Int,
    /** reading 上の終端インデックス（exclusive、ひらがな座標）。 */
    val readingEnd: Int,
    /** 変換済み・未変換・固定の状態。 */
    val status: ShadowSegmentStatus,
    /** このセグメントに対応する lexeme 経路（確定・pin 操作に使う）。 */
    val lexemePath: List<LexemeEntry>,
    /**
     * 候補窓での preview 中の表面文字列。
     *
     * preview がない場合は null。null のとき [surface] を表示する。
     * [me.matsumo.romaflow.core.ime.Selection.Candidate] 相当の非破壊 projection。
     */
    val previewSurface: String? = null,
) {

    /** 実際に表示する文字列（preview が優先）。 */
    val displaySurface: String
        get() = previewSurface ?: surface

    /** reading 上のスパンの長さ。 */
    val readingLength: Int
        get() = readingEnd - readingStart
}

/**
 * shadow エンジンの segment 状態。
 *
 * - [Unconverted]: まだ変換していないかな（初期状態・revert 後）。
 * - [Converted]: lattice 変換済みだが prefix lock されていない。
 * - [Locked]: ユーザーが候補確定して固定した状態（再変換でも変えない）。
 */
internal enum class ShadowSegmentStatus {
    /** 未変換のかな。 */
    Unconverted,

    /** lattice 変換済みだが固定されていない。 */
    Converted,

    /** ユーザーが確定した固定状態。 */
    Locked,
}
