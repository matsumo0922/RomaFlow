package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.morphology.LexemeEntry

/**
 * shadow エンジンの変換状態。
 *
 * A-1 の [CompositionSource]、A-2 の [CompositionGraph]（reading lattice）、
 * [selectedPathId]（全文の選択経路）、[clauseAnchor]（節単位の選択位置）、
 * [pinnedConstraint]（固定 prefix）、[candidateSession]（候補窓セッション）を保持する。
 *
 * shadow モード専用であり、live の `RomaFlowEngine` の公開 API を変更しない。
 * A-5 cutover で初めて live に差し替える。
 *
 * ## 設計原則
 * - すべての操作は新しい [CompositionState] を返すイミュータブルスタイル。
 * - [segments] は projection であり、[source] + [graph] + [selectedPathId] + [pinnedConstraint] から
 *   常に導出する（直接格納しない）。
 * - [candidateSession] は外部から差し替えるのではなく [ShadowCompositionEngine] が管理する。
 *
 * ## 不変条件
 * - [selectedPathId] は [graph] の [CompositionGraph.allPathIds] の範囲内（graph が空なら null）。
 * - [pinnedConstraint].lockedPrefixBoundary は 0 〜 [source].readingInput.length の範囲。
 */
internal data class CompositionState(
    /** romaji 入力層の source of truth（A-1）。 */
    val source: CompositionSource,
    /** reading lattice グラフ（A-2）。 */
    val graph: CompositionGraph,
    /**
     * 全文に対して選択している経路の ID。
     *
     * 変換前（graph が空、または未変換）は null。
     * lattice 変換後は [CompositionGraph.bestPathId] を初期値とし、
     * 節選択・candidate 確定で特定 path に変更する。
     */
    val selectedPathId: CompositionGraph.PathId?,
    /** 節単位の選択位置（source 座標）。未選択は null。 */
    val clauseAnchor: ClauseAnchor?,
    /** 左からの連続 prefix lock 制約。 */
    val pinnedConstraint: PinnedPathConstraint,
    /** 候補窓セッション（開いていなければ entries 空）。 */
    val candidateSession: CandidateSession,
) {

    /**
     * 現在表示すべき reading 全体（source からの projection）。
     *
     * pending romaji は含まない。確定済みかな（readingInput）のみ。
     */
    val reading: String
        get() = source.readingInput

    /** pending ローマ字（表示末尾の未確定部分）。 */
    val pendingRomaji: String
        get() = source.pendingRomaji

    /** graph が変換済み経路を持つか。 */
    val isConverted: Boolean
        get() = selectedPathId != null && graph.hasValidPath

    /**
     * 現在の selected path から導出した [ShadowSegment] リストを返す。
     *
     * selected path が無い（未変換）場合は reading 全体を1つの Unconverted セグメントとして返す。
     * [pinnedConstraint] の boundary に基づき Locked / Converted を決定する。
     *
     * ## Preview 非破壊性
     * [clauseAnchor] が指す節に [previewSurface] がある場合、対応セグメントの displaySurface を
     * preview で上書きする（[ShadowSegment.previewSurface] を設定）。
     * 元の [ShadowSegment.surface]（graph 由来）は変更しない。
     */
    val segments: List<ShadowSegment>
        get() {
            val currentReading = reading

            if (!isConverted) {
                return buildUnconvertedSegments(currentReading)
            }

            val path = graph.pathOrNull(requireNotNull(selectedPathId)) ?: return buildUnconvertedSegments(currentReading)

            return buildConvertedSegments(path, currentReading)
        }

    private fun buildUnconvertedSegments(currentReading: String): List<ShadowSegment> {
        if (currentReading.isEmpty()) {
            return emptyList()
        }

        return listOf(
            ShadowSegment(
                surface = currentReading,
                readingStart = 0,
                readingEnd = currentReading.length,
                status = ShadowSegmentStatus.Unconverted,
                lexemePath = emptyList(),
            ),
        )
    }

    private fun buildConvertedSegments(
        path: List<LexemeEntry>,
        currentReading: String,
    ): List<ShadowSegment> {
        val segments = mutableListOf<ShadowSegment>()
        var readingCursor = 0
        val boundary = pinnedConstraint.lockedPrefixBoundary

        for (lexeme in path) {
            val lexemeReading = lexeme.reading
            val segmentReadingStart = readingCursor
            val segmentReadingEnd = readingCursor + lexemeReading.length
            val status = determineStatus(segmentReadingEnd, boundary)

            segments.add(
                ShadowSegment(
                    surface = lexeme.surface,
                    readingStart = segmentReadingStart,
                    readingEnd = segmentReadingEnd,
                    status = status,
                    lexemePath = listOf(lexeme),
                    // Preview 上書きは A-3 スコープ外（null = preview なし）。A-5 以降で実装する。
                    previewSurface = null,
                ),
            )

            readingCursor = segmentReadingEnd
        }

        val unconvertedTail = buildUnconvertedTail(currentReading, readingCursor)

        if (unconvertedTail != null) {
            segments.add(unconvertedTail)
        }

        return segments
    }

    private fun determineStatus(
        segmentReadingEnd: Int,
        boundary: Int,
    ): ShadowSegmentStatus {
        val isFullyLocked = segmentReadingEnd <= boundary

        return when {
            isFullyLocked -> ShadowSegmentStatus.Locked
            else -> ShadowSegmentStatus.Converted
        }
    }

    private fun buildUnconvertedTail(
        currentReading: String,
        readingCursor: Int,
    ): ShadowSegment? {
        if (readingCursor >= currentReading.length) {
            return null
        }

        val tail = currentReading.substring(readingCursor)

        return ShadowSegment(
            surface = tail,
            readingStart = readingCursor,
            readingEnd = currentReading.length,
            status = ShadowSegmentStatus.Unconverted,
            lexemePath = emptyList(),
        )
    }

    companion object {

        /** 空の初期状態を返す。 */
        fun empty(): CompositionState {
            return CompositionState(
                source = CompositionSource.empty(revision = 0),
                graph = CompositionGraph.empty(),
                selectedPathId = null,
                clauseAnchor = null,
                pinnedConstraint = PinnedPathConstraint.empty(),
                candidateSession = CandidateSession.empty(),
            )
        }
    }
}
