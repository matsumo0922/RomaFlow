package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [CompositionState] の projection・操作の unit テスト。
 *
 * 実辞書ではなく [ZeroConnectionCostProvider] と単純な stub lexeme を使う。
 * lattice graph はテスト内で直接構築するか、[CompositionGraph.empty] を使う。
 */
class CompositionStateTest {

    /**
     * 空の初期状態が正しい値を持つことを確認する。
     */
    @Test
    fun emptyStateHasCorrectInitialValues() {
        val state = CompositionState.empty()

        assertEquals("", state.reading)
        assertEquals("", state.pendingRomaji)
        assertFalse(state.isConverted)
        assertNull(state.clauseAnchor)
        assertFalse(state.pinnedConstraint.hasLockedPrefix)
        assertEquals(0, state.candidateSession.count)
    }

    /**
     * 未変換状態では segments が全体を1 Unconverted セグメントとして返すことを確認する。
     */
    @Test
    fun unconvertedStateReturnsReadingAsUnconvertedSegment() {
        val engine = ShadowCompositionEngine(
            lexicon = EmptyReadingLexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        engine.inputRomaji("ka")

        val state = engine.state
        val segments = state.segments

        assertEquals(1, segments.size)
        assertEquals(ShadowSegmentStatus.Unconverted, segments[0].status)
        assertEquals(state.reading, segments[0].surface)
    }

    /**
     * [PinnedPathConstraint] が active なとき、boundary 以内の segments が Locked になることを確認する。
     */
    @Test
    fun lockedPrefixBoundaryMakesSegmentsLocked() {
        val lexeme1 = buildLexeme("天気", "テンキ")
        val lexeme2 = buildLexeme("です", "デス")

        val graph = CompositionGraph.buildFromPaths(
            reading = "てんきです",
            paths = listOf(0L to listOf(lexeme1, lexeme2)),
        )

        val pinnedConstraint = PinnedPathConstraint(
            lockedPrefixBoundary = 3,
            pinnedPath = listOf(lexeme1),
        )

        val state = CompositionState.empty().copy(
            graph = graph,
            selectedPathId = CompositionGraph.PathId(0),
            pinnedConstraint = pinnedConstraint,
        )

        val segments = state.segments

        assertEquals(2, segments.size)
        assertEquals(ShadowSegmentStatus.Locked, segments[0].status)
        assertEquals(ShadowSegmentStatus.Converted, segments[1].status)
        assertEquals("天気", segments[0].surface)
        assertEquals("です", segments[1].surface)
    }

    /**
     * graph が空の場合、segments が全体を Unconverted として返すことを確認する。
     */
    @Test
    fun emptyGraphReturnsUnconvertedSegments() {
        val engine = ShadowCompositionEngine(
            lexicon = EmptyReadingLexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        engine.inputRomaji("te")

        val state = engine.state

        assertFalse(state.isConverted)

        val segments = state.segments

        assertEquals(1, segments.size)
        assertEquals(ShadowSegmentStatus.Unconverted, segments[0].status)
    }

    private fun buildLexeme(surface: String, reading: String): LexemeEntry {
        return LexemeEntry(
            surface = surface,
            reading = reading,
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 100,
        )
    }
}
