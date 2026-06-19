package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
     * prefix lock がある場合の segments projection が正しく構成されることを確認する。
     *
     * ## テスト構成
     * - reading: "てんきです"（5文字）
     * - pinnedPath: [lexeme "天気"(テンキ, reading=3)]（lockedPrefixBoundary=3）
     * - graph（tail "です" のみ）の path: [lexeme "です"(デス, reading=2)]
     *
     * これにより segments は:
     *   [0] Locked  "天気" readingStart=0, readingEnd=3
     *   [1] Converted "です" readingStart=3, readingEnd=5
     *
     * と連続していることを確認する。
     *
     * この組み合わせは実フロー（buildGraph → openCandidates → confirmCandidate → buildGraph）後に
     * 生じる状態と同一であり、buildConvertedSegments が pinnedPath を先頭に前置し tail 座標を
     * lockedPrefixBoundary から開始することを検証する。
     */
    @Test
    fun segmentsWithPinnedPathArePrependedAsLockedAndTailCoordinatesAreAbsolute() {
        val lexemeTenki = buildLexeme("天気", "テンキ")
        val lexemeDesu = buildLexeme("です", "デス")

        // tail のみの graph（"てんきです".substring(3) = "です"）
        val tailGraph = CompositionGraph.buildFromPaths(
            reading = "です",
            paths = listOf(0L to listOf(lexemeDesu)),
        )

        val pinnedConstraint = PinnedPathConstraint(
            lockedPrefixBoundary = 3,
            pinnedPath = listOf(lexemeTenki),
        )

        // source の readingInput を "てんきです" にセットした状態を構築
        val sourceWithReading = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
            frozenPrefix = "てんきです",
            revision = 0,
        )

        val state = CompositionState.empty().copy(
            source = sourceWithReading,
            graph = tailGraph,
            selectedPathId = CompositionGraph.PathId(0),
            pinnedConstraint = pinnedConstraint,
        )

        assertTrue(state.isConverted, "selectedPathId が設定されているため isConverted == true")

        val segments = state.segments

        // セグメント数: Locked(天気) + Converted(です) = 2
        assertEquals(2, segments.size, "Locked 1 + Converted 1 = 2 segments")

        // Locked segment（pinnedPath 由来）
        assertEquals(ShadowSegmentStatus.Locked, segments[0].status, "先頭は Locked")
        assertEquals("天気", segments[0].surface, "pinnedPath の surface")
        assertEquals(0, segments[0].readingStart, "Locked segment は reading 先頭から始まる")
        assertEquals(3, segments[0].readingEnd, "テンキ = 3文字")

        // Converted segment（tail graph 由来）
        assertEquals(ShadowSegmentStatus.Converted, segments[1].status, "tail は Converted")
        assertEquals("です", segments[1].surface, "tail の surface")
        assertEquals(3, segments[1].readingStart, "tail の readingStart は lockedPrefixBoundary(=3)")
        assertEquals(5, segments[1].readingEnd, "デス = 2文字なので 3+2=5")

        // 全体の連続性
        assertSegmentsContinuous(segments, "てんきです")
    }

    /**
     * lock なし状態では graph path のみから segments が生成されることを確認する。
     *
     * pinnedPath が空のとき buildConvertedSegments は lock 前置ループをスキップし、
     * graph path が readingCursor=0 から始まる従来どおりの挙動になる。
     */
    @Test
    fun segmentsWithNoPinnedPathStartFromZero() {
        val lexeme1 = buildLexeme("天気", "テンキ")
        val lexeme2 = buildLexeme("です", "デス")

        // graph には全文の path を注入（lock なし）
        val graph = CompositionGraph.buildFromPaths(
            reading = "てんきです",
            paths = listOf(0L to listOf(lexeme1, lexeme2)),
        )

        val state = CompositionState.empty().copy(
            graph = graph,
            selectedPathId = CompositionGraph.PathId(0),
        )

        val segments = state.segments

        assertEquals(2, segments.size)
        assertEquals(ShadowSegmentStatus.Converted, segments[0].status)
        assertEquals(ShadowSegmentStatus.Converted, segments[1].status)
        assertEquals("天気", segments[0].surface)
        assertEquals("です", segments[1].surface)
        assertEquals(0, segments[0].readingStart)
        assertEquals(3, segments[0].readingEnd, "テンキ = 3文字なので readingEnd=3")
        assertEquals(3, segments[1].readingStart)
        assertEquals(5, segments[1].readingEnd, "デス = 2文字なので readingEnd=5")
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

    /**
     * segments が reading 全体に渡って連続していることを検証する。
     *
     * - 先頭 segment.readingStart == 0
     * - 末尾 segment.readingEnd == reading.length
     * - 各 segment の前.readingEnd == 次.readingStart（隙間・重複なし）
     */
    private fun assertSegmentsContinuous(
        segments: List<ShadowSegment>,
        reading: String,
    ) {
        if (segments.isEmpty()) return

        assertEquals(0, segments.first().readingStart, "先頭 segment が reading 先頭から始まること")
        assertEquals(reading.length, segments.last().readingEnd, "末尾 segment が reading 末尾で終わること")

        for (index in 0 until segments.size - 1) {
            val current = segments[index]
            val next = segments[index + 1]

            assertEquals(
                current.readingEnd,
                next.readingStart,
                "segment[$index].readingEnd == segment[${index + 1}].readingStart（連続であること）",
            )
        }
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
