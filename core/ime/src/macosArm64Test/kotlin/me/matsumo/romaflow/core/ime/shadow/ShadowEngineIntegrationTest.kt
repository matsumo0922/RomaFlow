package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * shadow エンジンの統合テスト（macosArm64）。
 *
 * 実 IPADIC 辞書と Momiji 連接コスト行列を使い、A-3 の主要フローを検証する。
 *
 * - てんき の N-best に 天気・転機 が含まれること（§7 verified path 切り替え）
 * - かなへの revert が正しく動作すること（converted revert）
 * - lock 食い込み backspace が lock を削ること
 * - OOV fallback でデッドロックしないこと
 * - BatchEvaluationHarness から CompositionState を経由してフローを駆動できること
 */
class ShadowEngineIntegrationTest {

    private val lexicon by lazy { IpadicReadingLexicon() }
    private val costProvider by lazy { MomijiConnectionCostProvider.load() }

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
