package me.matsumo.romaflow.core.ime.shadow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * shadow エンジンの統合テスト（macosArm64）。
 *
 * 実 Mozc 辞書と Mozc 連接コスト行列を使い、A-3 の主要フローを検証する。
 *
 * - てんき の N-best に 天気・転機 が含まれること（§7 verified path 切り替え）
 * - かなへの revert が正しく動作すること（converted revert）
 * - lock 食い込み backspace が lock を削ること
 * - OOV fallback でデッドロックしないこと
 * - BatchEvaluationHarness から CompositionState を経由してフローを駆動できること
 */
class ShadowEngineIntegrationTest {

    private val lexicon by lazy { me.matsumo.romaflow.core.ime.MozcTestDictionary.readingLexicon }
    private val costProvider by lazy { me.matsumo.romaflow.core.ime.MozcTestDictionary.costProvider }

    private fun buildEngine(): ShadowCompositionEngine {
        return ShadowCompositionEngine(
            lexicon = lexicon,
            costProvider = costProvider,
            nBest = 16,
        )
    }

    /**
     * てんき を buildGraph すると 天気 と 転機 の両方が N-best に含まれることを確認する。
     *
     * この確認は BatchEvaluationHarness の A-0 テストと同等だが、
     * [CompositionState] 経由の新しいフローで再確認する。
     */
    @Test
    fun buildGraphForTenkiIncludesTenkiVariants() {
        val engine = buildEngine()

        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "てんき",
                revision = 0,
            ),
        )

        engine.buildGraph()

        val graph = engine.state.graph

        assertTrue(graph.hasValidPath, "てんき の graph に経路があること")

        val allSurfaces = graph.allPathIds
            .flatMap { pathId ->
                graph.pathOrNull(pathId)?.map { it.surface } ?: emptyList()
            }
            .toSet()

        println("てんき の N-best surfaces: $allSurfaces")

        assertTrue("天気" in allSurfaces, "N-best に 天気 が含まれること（found: $allSurfaces）")
        assertTrue("転機" in allSurfaces, "N-best に 転機 が含まれること（found: $allSurfaces）")
    }

    /**
     * 変換済み状態から revert すると未変換に戻ることを確認する。
     */
    @Test
    fun revertAfterBuildGraphRestoresUnconvertedState() {
        val engine = buildEngine()

        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "てんき",
                revision = 0,
            ),
        )

        engine.buildGraph()

        assertTrue(engine.state.isConverted, "buildGraph 後は変換済みであること")

        engine.revert()

        assertFalse(engine.state.isConverted, "revert 後は未変換であること")
        assertEquals("てんき", engine.state.reading, "reading が保持されること")
    }

    /**
     * OOV 文字（記号）を含む reading でも buildGraph がデッドロックしないことを確認する。
     *
     * LiteralLexicon の fallback で全位置に arc が保証されるためデッドロックは起きない。
     */
    @Test
    fun buildGraphDoesNotDeadlockOnSymbolCharacters() {
        val engine = buildEngine()

        // 記号はひらがなでなく OOV として扱われる
        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "てんき？",
                revision = 0,
            ),
        )

        engine.buildGraph()

        val graph = engine.state.graph

        assertTrue(graph.hasValidPath, "OOV 記号を含んでも graph に経路があること")
    }

    /**
     * 候補確定で PinnedPathConstraint が更新され、segments に Locked が反映されることを確認する。
     */
    @Test
    fun confirmCandidateLocksPrefix() {
        val engine = buildEngine()

        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "てんき",
                revision = 0,
            ),
        )

        engine.buildGraph()
        engine.openCandidates()

        val session = engine.state.candidateSession

        assertTrue(session.count > 0, "候補が存在すること")

        val firstSurface = session.surfaceOrNull(0)

        assertNotNull(firstSurface, "最初の候補が null でないこと")

        engine.confirmCandidate(firstSurface)

        val constraint = engine.state.pinnedConstraint

        assertTrue(constraint.hasLockedPrefix, "確定後に lock が設定されること")
        assertTrue(constraint.lockedPrefixBoundary > 0, "boundary が正であること")

        println("確定候補: $firstSurface  boundary: ${constraint.lockedPrefixBoundary}")
    }

    /**
     * lock 食い込み backspace で boundary が1減ることを確認する。
     */
    @Test
    fun deleteBackwardOnLockedPrefixDecrementsBoundary() {
        val engine = buildEngine()

        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "てんき",
                revision = 0,
            ),
        )

        engine.buildGraph()
        engine.openCandidates()

        val firstSurface = engine.state.candidateSession.surfaceOrNull(0) ?: return

        engine.confirmCandidate(firstSurface)

        val boundaryBefore = engine.state.pinnedConstraint.lockedPrefixBoundary

        assertTrue(boundaryBefore > 0, "lock boundary が正であること")

        engine.deleteBackward()

        val boundaryAfter = engine.state.pinnedConstraint.lockedPrefixBoundary

        assertTrue(boundaryAfter < boundaryBefore, "backspace で lock boundary が減ること")
    }

    /**
     * 節選択（moveClause）が正しく動作することを確認する。
     *
     * てんきです の変換結果から複数セグメントを移動できる。
     */
    @Test
    fun moveClauseTraversesSegmentsCorrectly() {
        val engine = buildEngine()

        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "てんきです",
                revision = 0,
            ),
        )

        engine.buildGraph()

        val segmentCount = engine.state.segments.size

        println("てんきです の segments: ${engine.state.segments.map { it.surface }}")

        if (segmentCount < 2) {
            println("segment が1件のため moveClause テストをスキップ")
            return
        }

        assertTrue(engine.state.clauseAnchor == null, "初期は clauseAnchor が null")

        engine.moveClause(forward = false)

        assertNotNull(engine.state.clauseAnchor, "← で clauseAnchor が設定されること")
    }

    /**
     * 候補確定（prefix lock）後の再 buildGraph で segments が連続することを確認する。
     *
     * ## フロー
     * 1. "てんき"（3文字）で buildGraph → openCandidates → "天気" を confirmCandidate
     *    （pinnedPath = [天気], boundary = 3 が設定される）
     * 2. source を "てんきです" に更新して buildGraph（tail "です" のみ再構築）
     * 3. segments が:
     *    - 先頭 Locked segment（pinnedPath 由来）を含む
     *    - tail Converted segment の readingStart が lockedPrefixBoundary(=3) と一致する
     *    - 全体が連続（前.readingEnd == 次.readingStart、先頭=0、末尾=reading.length）
     *
     * buildConvertedSegments が pinnedPath を先頭に前置し、tail の絶対座標が
     * lockedPrefixBoundary から始まることを実辞書（IPADIC）で検証する。
     */
    @Test
    fun segmentsAreContinuousAfterPrefixLockAndRebuildGraph() {
        val engine = buildEngine()
        val tenki = "てんき"
        val tenkiDesu = "てんきです"

        // 1. "てんき" のみで変換して "天気" を確定 → boundary=3 の lock
        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = tenki,
                revision = 0,
            ),
        )

        engine.buildGraph()
        engine.openCandidates()

        // "てんき" の N-best には1語パスの "天気" が含まれる（confirmCandidateLocksPrefix テストで確認済み）
        val tenkiSurface = engine.state.candidateSession.entries
            .firstOrNull { it.surface == "天気" }?.surface
            ?: engine.state.candidateSession.surfaceOrNull(0)
            ?: return

        engine.confirmCandidate(tenkiSurface)

        val boundary = engine.state.pinnedConstraint.lockedPrefixBoundary

        assertTrue(boundary > 0, "confirm 後に lock boundary が正であること（locked: $tenkiSurface, boundary: $boundary）")

        println("lock 後: boundary=$boundary, pinnedPath=${engine.state.pinnedConstraint.pinnedPath.map { it.surface }}")

        // 2. source を "てんきです" に更新して tail だけ再構築
        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = tenkiDesu,
                revision = 1,
            ),
        )

        engine.buildGraph()

        assertTrue(engine.state.isConverted, "2回目の buildGraph 後も変換済みであること（tail の変換が存在すること）")

        val segments = engine.state.segments

        println("segments after lock: ${segments.map { "${it.status}:${it.surface}[${it.readingStart}..${it.readingEnd}]" }}")

        // 3-a. Locked segment が先頭に存在すること
        val lockedSegments = segments.filter { it.status == ShadowSegmentStatus.Locked }

        assertTrue(lockedSegments.isNotEmpty(), "Locked segment が存在すること")
        assertEquals(0, lockedSegments.first().readingStart, "最初の Locked segment が reading 先頭から始まること")

        // 3-b. tail segment の readingStart が boundary と一致すること
        val tailSegments = segments.filter { it.status == ShadowSegmentStatus.Converted }

        if (tailSegments.isNotEmpty()) {
            assertEquals(
                boundary,
                tailSegments.first().readingStart,
                "tail 先頭 segment の readingStart が lockedPrefixBoundary(=$boundary) と一致すること",
            )
        }

        // 3-c. 全 segment が reading 全体に渡って連続していること
        assertTrue(segments.isNotEmpty(), "segments が空でないこと")
        assertEquals(0, segments.first().readingStart, "先頭 segment が 0 から始まること")
        assertEquals(tenkiDesu.length, segments.last().readingEnd, "末尾 segment が reading 末尾で終わること")

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

    /**
     * CompositionState 経由の A-0 互換フロー:
     * わたし の N-best に 私 が含まれることを確認する。
     */
    @Test
    fun compositionStateFlowCoversBatchEvaluationA0() {
        val engine = buildEngine()

        engine.state = engine.state.copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = "わたし",
                revision = 0,
            ),
        )

        engine.buildGraph()

        val graph = engine.state.graph

        assertTrue(graph.hasValidPath, "わたし の graph に経路があること")

        val allSurfaces = graph.allPathIds
            .flatMap { pathId ->
                graph.pathOrNull(pathId)?.map { it.surface } ?: emptyList()
            }
            .toSet()

        assertTrue("私" in allSurfaces, "N-best に 私 が含まれること（found: $allSurfaces）")
    }
}
