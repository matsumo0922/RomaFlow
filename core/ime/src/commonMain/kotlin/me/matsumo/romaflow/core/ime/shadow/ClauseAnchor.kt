package me.matsumo.romaflow.core.ime.shadow

/**
 * preedit 上のカーソル位置を reading 座標（ひらがな文字インデックス）で固定した節アンカー。
 *
 * live の [me.matsumo.romaflow.core.ime.Selection.Word] が segment index（表示座標）を保持するのに対し、
 * [ClauseAnchor] は [readingStart]/[readingEnd]（reading 上のひらがな文字インデックス）で選択を固定する。
 * 表示時に segment index へ再投影する。これにより、再変換で segment 境界が変わっても選択が安定して追随する。
 *
 * ## 座標系について
 * A-3 では reading（ひらがな文字列）が決定的に source atoms と対応するため、
 * reading 座標で節を固定することで十分な安定性が得られる。
 * A-5 以降では typo 訂正により reading が source atoms と乖離し得るため、
 * [me.matsumo.romaflow.core.ime.TransliterationTrace] の
 * [me.matsumo.romaflow.core.ime.TransliterationEdge.sourceSpan] を用いて
 * 真の atom 座標へマッピングする拡張を行う。
 * A-1 の [me.matsumo.romaflow.core.ime.SourceSpan]（atom 座標）は A-5 まで使わない。
 *
 * [selectedPathId] は現在この節で選択されている候補の経路 ID。
 * 候補窓を開いていない通常の選択状態では [CompositionGraph.PathId] rank=0（最安経路）を指す。
 */
internal data class ClauseAnchor(
    /**
     * reading 上の開始インデックス（inclusive、ひらがな文字座標）。
     *
     * `source.readingInput[readingStart..readingEnd)` がこの節に対応する読み範囲。
     */
    val readingStart: Int,
    /**
     * reading 上の終端インデックス（exclusive、ひらがな文字座標）。
     */
    val readingEnd: Int,
    /** この節で現在選択している経路の ID。 */
    val selectedPathId: CompositionGraph.PathId,
)
