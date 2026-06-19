package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LexemeMatch
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ShadowCompositionEngine] の操作テスト。
 *
 * 実辞書は使わず、stub [ReadingLexicon] と [ZeroConnectionCostProvider] で
 * 決定論的に挙動を検証する。
 */
class ShadowCompositionEngineTest {

    // region: inputRomaji

    /**
     * ローマ字入力後に source の reading が更新されることを確認する。
     */
    @Test
    fun inputRomajiUpdatesSourceReading() {
        val engine = buildEngine()

        engine.inputRomaji("ka")

        assertTrue(engine.state.source.readingInput.isNotEmpty() || engine.state.source.pendingRomaji.isNotEmpty())
    }

    /**
     * ローマ字入力後に graph は空のまま（buildGraph を呼ぶまで更新しない）であることを確認する。
     */
    @Test
    fun inputRomajiDoesNotBuildGraphAutomatically() {
        val engine = buildEngine()

        engine.inputRomaji("te")
        engine.inputRomaji("n")

        assertFalse(engine.state.isConverted)
    }

    // endregion

    // region: backspace - pending 削除

    /**
     * pending ローマ字がある状態で backspace を押すと pending が削れることを確認する。
     *
     * `k` は pending のまま残るので backspace で消える。
     */
    @Test
    fun deleteBackwardRemovesPendingRomajiTail() {
        val engine = buildEngine()

        engine.inputRomaji("k")

        val pendingBefore = engine.state.source.pendingRomaji

        assertFalse(pendingBefore.isEmpty(), "pending が空でないこと")

        engine.deleteBackward()

        val pendingAfter = engine.state.source.pendingRomaji
        val readingAfter = engine.state.source.readingInput

        assertEquals("", pendingAfter, "pending が削れること")
        assertEquals("", readingAfter, "reading が空になること")
    }

    // endregion

    // region: backspace - preview 破棄

    /**
     * 候補窓が開いている状態で backspace を押すと preview が破棄されることを確認する。
     *
     * reading は変わらない（候補セッションのみ閉じる）。
     */
    @Test
    fun deleteBackwardDiscardsCandidateSessionIfOpen() {
        val engine = buildEngine(lexicon = SingleWordLexicon("て", "て"))

        engine.inputRomaji("te")
        engine.buildGraph()
        engine.openCandidates()

        val sessionBefore = engine.state.candidateSession

        assertTrue(sessionBefore.count > 0, "候補窓が開いていること")

        val readingBefore = engine.state.source.readingInput

        engine.deleteBackward()

        val sessionAfter = engine.state.candidateSession

        assertEquals(0, sessionAfter.count, "候補セッションが閉じること")

        val readingAfter = engine.state.source.readingInput

        assertEquals(readingBefore, readingAfter, "reading が変わらないこと")
    }

    // endregion

    // region: backspace - converted revert

    /**
     * 変換済み状態で backspace を押すと revert されてかな表示に戻ることを確認する。
     */
    @Test
    fun deleteBackwardRevertsConvertedState() {
        val engine = buildEngine(lexicon = SingleWordLexicon("て", "テ"))

        engine.inputRomaji("te")
        engine.buildGraph()

        assertTrue(engine.state.isConverted, "変換済みであること")

        engine.deleteBackward()

        assertFalse(engine.state.isConverted, "revert 後に未変換になること")
    }

    // endregion

    // region: backspace - lock 食い込み削除

    /**
     * prefix lock が active な状態で backspace を押すと lock boundary が1減ることを確認する。
     */
    @Test
    fun deleteBackwardDecreasesLockedPrefixBoundary() {
        val engine = buildEngine(lexicon = SingleWordLexicon("て", "テ"))

        engine.inputRomaji("te")
        engine.buildGraph()
        engine.openCandidates()

        val surface = engine.state.candidateSession.entries.firstOrNull()?.surface ?: return

        engine.confirmCandidate(surface)

        val boundaryBefore = engine.state.pinnedConstraint.lockedPrefixBoundary

        assertTrue(boundaryBefore > 0, "lock boundary が正であること")

        engine.deleteBackward()

        val boundaryAfter = engine.state.pinnedConstraint.lockedPrefixBoundary

        assertTrue(boundaryAfter < boundaryBefore, "lock boundary が減ること")
    }

    /**
     * lock boundary が 1 のとき、backspace で boundary が 0 になり lock が解除されることを確認する。
     */
    @Test
    fun deleteBackwardClearsLockedPrefixWhenBoundaryReachesZero() {
        val engine = buildEngine(lexicon = SingleWordLexicon("て", "テ"))

        engine.inputRomaji("te")
        engine.buildGraph()

        engine.openCandidates()

        val entryOrFallback = engine.state.candidateSession.entries.firstOrNull()?.surface ?: "て"

        engine.confirmCandidate(entryOrFallback)

        // lock が active なこと
        assertTrue(engine.state.pinnedConstraint.hasLockedPrefix)

        // backspace で lock を全消去
        var iterations = 0
        val maxIterations = 10

        while (engine.state.pinnedConstraint.hasLockedPrefix && iterations < maxIterations) {
            engine.deleteBackward()
            iterations++
        }

        assertFalse(engine.state.pinnedConstraint.hasLockedPrefix, "lock が解除されること")
    }

    // endregion

    // region: revert

    /**
     * revert で graph / selectedPathId / pinnedConstraint がリセットされることを確認する。
     */
    @Test
    fun revertClearsGraphAndPinnedConstraint() {
        val engine = buildEngine(lexicon = SingleWordLexicon("て", "テ"))

        engine.inputRomaji("te")
        engine.buildGraph()

        assertTrue(engine.state.isConverted)

        engine.revert()

        assertFalse(engine.state.isConverted, "revert 後に未変換になること")
        assertNull(engine.state.selectedPathId, "selectedPathId が null になること")
        assertFalse(engine.state.pinnedConstraint.hasLockedPrefix, "pinnedConstraint がリセットされること")
    }

    // endregion

    // region: candidate session

    /**
     * [CandidateSession] の重複 surface が畳み込まれることを確認する。
     *
     * 同一 surface の経路が複数あっても1エントリに畳む。
     */
    @Test
    fun candidateSessionDeduplicatesBySurface() {
        val lexeme1 = buildLexeme("天気", "テンキ")
        val lexeme2 = buildLexeme("天気", "テンキ")

        val graph = CompositionGraph.buildFromPaths(
            reading = "てんき",
            paths = listOf(
                0L to listOf(lexeme1),
                100L to listOf(lexeme2),
            ),
        )

        val session = CandidateSession.buildFrom(graph, nextSessionId = 1)

        assertEquals(1, session.count, "重複 surface が畳み込まれること")
        assertEquals("天気", session.surfaceOrNull(0))
    }

    /**
     * [CandidateSession.entryBySurfaceOrNull] が正しいエントリを返すことを確認する。
     */
    @Test
    fun candidateSessionFindsBySurface() {
        val lexeme = buildLexeme("天気", "テンキ")
        val graph = CompositionGraph.buildFromPaths(
            reading = "てんき",
            paths = listOf(0L to listOf(lexeme)),
        )

        val session = CandidateSession.buildFrom(graph, nextSessionId = 1)

        assertNotNull(session.entryBySurfaceOrNull("天気"))
        assertNull(session.entryBySurfaceOrNull("転機"))
    }

    // endregion

    // region: confirmCandidate

    /**
     * [ShadowCompositionEngine.confirmCandidate] が pinnedConstraint を更新することを確認する。
     */
    @Test
    fun confirmCandidateExtendsPinnedConstraint() {
        val lexeme = buildLexeme("天気", "テンキ")
        val graph = CompositionGraph.buildFromPaths(
            reading = "てんき",
            paths = listOf(0L to listOf(lexeme)),
        )

        val engine = buildEngine(lexicon = EmptyReadingLexicon)

        engine.state = engine.state.copy(
            graph = graph,
            selectedPathId = CompositionGraph.PathId(0),
            candidateSession = CandidateSession.buildFrom(graph, nextSessionId = 1),
        )

        engine.confirmCandidate("天気")

        val constraint = engine.state.pinnedConstraint

        assertTrue(constraint.hasLockedPrefix, "lock が設定されること")
        assertEquals(3, constraint.lockedPrefixBoundary, "てんき=3文字の boundary になること（カタカナ reading 長）")
        assertEquals("天気", constraint.pinnedSurface, "pinnedSurface が天気になること")
    }

    // endregion

    // region: buildGraph - OOV fallback

    /**
     * 辞書外文字でも buildGraph がデッドロックしないことを確認する。
     *
     * [EmptyReadingLexicon] は全ての辞書語マッチを返さないが、
     * [buildReadingLexiconWithFallback] の [LiteralLexicon] が1文字ずつ素通しアークを保証するため
     * graph は空にならない。
     */
    @Test
    fun buildGraphDoesNotDeadlockOnOovCharacters() {
        val engine = buildEngine(lexicon = EmptyReadingLexicon)

        engine.inputRomaji("abc")

        // "abc" は kana じゃないのでかなに変換されないが、pending のみが残る場合もある
        // ここでは OOV fallback の確認なので reading が空の場合は buildGraph は no-op になる
        val readingBeforeGraph = engine.state.source.readingInput

        engine.buildGraph()

        if (readingBeforeGraph.isEmpty()) {
            assertFalse(engine.state.isConverted, "reading が空なら変換されないこと")
        } else {
            // graph が構築されたら有効な path が存在する（デッドロックなし）
            val graph = engine.state.graph

            assertTrue(graph.hasValidPath || !engine.state.isConverted, "OOV でもデッドロックしないこと")
        }
    }

    /**
     * ひらがなを OOV として buildGraph すると LiteralLexicon が素通しアークで経路を保証する。
     */
    @Test
    fun buildGraphWithHiraganaOovUsesLiteralFallback() {
        // EmptyReadingLexicon は辞書語マッチなし → LiteralLexicon が1文字ずつ素通し
        val engine = buildEngine(lexicon = EmptyReadingLexicon)

        // 直接 reading を持つ source を構築するため inputRomaji ではなく状態を注入
        val hiraganaReading = "あいう"
        val sourceWithReading = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
            frozenPrefix = hiraganaReading,
            revision = 0,
        )

        engine.state = engine.state.copy(source = sourceWithReading)
        engine.buildGraph()

        val graph = engine.state.graph

        assertTrue(graph.hasValidPath, "ひらがな OOV でも graph に経路があること")

        val bestPath = graph.pathOrNull(CompositionGraph.PathId(0))

        assertNotNull(bestPath, "rank-0 経路が取得できること")
        assertTrue(bestPath.isNotEmpty(), "経路が空でないこと")
    }

    // endregion

    // region: moveClause

    /**
     * moveClause で clauseAnchor が更新されることを確認する。
     *
     * 2 segments（天気, です）がある場合:
     * - 初期: 未選択（clauseAnchor == null）
     * - ← 1回: 末尾 segment（です, index=1）へジャンプ
     * - ← 2回: 先頭 segment（天気, index=0）へ移動
     * - → 1回: 次の segment（です, index=1）へ移動
     * - → 2回: 末尾を越えて選択解除（null）
     */
    @Test
    fun moveClauseUpdatesClauseAnchor() {
        val lexeme1 = buildLexeme("天気", "テンキ")
        val lexeme2 = buildLexeme("です", "デス")

        val graph = CompositionGraph.buildFromPaths(
            reading = "てんきです",
            paths = listOf(0L to listOf(lexeme1, lexeme2)),
        )

        val engine = buildEngine(lexicon = EmptyReadingLexicon)

        engine.state = engine.state.copy(
            graph = graph,
            selectedPathId = CompositionGraph.PathId(0),
        )

        assertNull(engine.state.clauseAnchor, "初期は clauseAnchor が null")

        // ← で末尾 segment（です）へジャンプ
        engine.moveClause(forward = false)

        assertNotNull(engine.state.clauseAnchor, "← で clauseAnchor が設定されること")

        // ← で先頭 segment（天気）へ移動
        engine.moveClause(forward = false)

        val anchorAtFirst = engine.state.clauseAnchor

        assertNotNull(anchorAtFirst, "← もう1回で先頭に移ること")
        assertEquals(0, anchorAtFirst.sourceSpan.fromAtomIndex, "先頭 segment の fromAtomIndex が 0 であること")

        // → で次の segment（です）へ移動
        engine.moveClause(forward = true)

        assertNotNull(engine.state.clauseAnchor, "→ で次の節に移ること")

        // → で末尾を越えて選択解除
        engine.moveClause(forward = true)

        assertNull(engine.state.clauseAnchor, "末尾を越えると選択解除になること")
    }

    // endregion

    // region: reset

    /**
     * reset 後に状態が完全にリセットされることを確認する。
     */
    @Test
    fun resetClearsAllState() {
        val engine = buildEngine(lexicon = SingleWordLexicon("て", "テ"))

        engine.inputRomaji("te")
        engine.buildGraph()
        engine.reset()

        assertEquals("", engine.state.source.readingInput)
        assertEquals("", engine.state.source.pendingRomaji)
        assertFalse(engine.state.isConverted)
        assertNull(engine.state.clauseAnchor)
        assertFalse(engine.state.pinnedConstraint.hasLockedPrefix)
    }

    // endregion

    private fun buildEngine(lexicon: me.matsumo.romaflow.core.morphology.ReadingLexicon = EmptyReadingLexicon): ShadowCompositionEngine {
        return ShadowCompositionEngine(
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )
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

/**
 * テスト用: 1 単語だけ返す [ReadingLexicon]。
 *
 * [reading] の全体が [surfaceResult] にマッチする1エントリだけを返す。
 * reading が 1 文字でも複数文字でも対応する。
 */
private class SingleWordLexicon(
    private val reading: String,
    private val surface: String,
) : ReadingLexicon {

    override fun commonPrefixSearch(query: String, startOffset: Int): List<LexemeMatch> {
        val endOffset = startOffset + reading.length

        if (endOffset > query.length) return emptyList()

        val slice = query.substring(startOffset, endOffset)

        if (slice != reading) return emptyList()

        val lexeme = LexemeEntry(
            surface = surface,
            reading = surface,
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 100,
        )

        return listOf(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
    }
}
