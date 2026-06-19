package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.SourceSpan

/**
 * preedit 上のカーソル位置を source 座標（atom span）で固定した節アンカー。
 *
 * live の [me.matsumo.romaflow.core.ime.Selection.Word] が segment index（表示座標）を保持するのに対し、
 * [ClauseAnchor] は [sourceSpan]（atoms 上のスパン）で選択を固定する。表示時に segment index へ再投影する。
 * これにより、再変換で segment 境界が変わっても選択が安定して追随する。
 *
 * [selectedPathId] は現在この節で選択されている候補の経路 ID。
 * 候補窓を開いていない通常の選択状態では [CompositionGraph.PathId] rank=0（最安経路）を指す。
 */
internal data class ClauseAnchor(
    /**
     * source atoms 座標上でのスパン。
     *
     * [SourceSpan.fromAtomIndex] 〜 [SourceSpan.toAtomIndex] の atoms が
     * この節に対応するローマ字入力を構成する。
     */
    val sourceSpan: SourceSpan,
    /** この節で現在選択している経路の ID。 */
    val selectedPathId: CompositionGraph.PathId,
)
