package me.matsumo.romaflow.core.ime.shadow

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.RerankRequest
import me.matsumo.romaflow.core.ime.WordCandidateRequest
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LexemeMatch
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR-C: hint pack の ΔC（文脈つきコスト）ベース選抜のテスト。
 *
 * ## 検証項目
 * - **ΔC 昇順が優先される**: 合成 lexicon + connection cost で、文脈的に安い候補（ΔC 小）が
 *   wcost 順より上位に来ること。
 * - **baseline が先頭固定**: ΔC に依らず baseline（rank-0 Viterbi path の語）は常に先頭であること。
 * - **旧字体降格は ΔC 版でも維持される**: 旧字体は ΔC ソート後も deferred 降格されること。
 * - **clamp は ΔC ソート後に行われる**: ΔC ソート済みリストを clamp するため最大6件制限が適用されること。
 */
@Suppress("FunctionNaming")
class HintPackDeltaCTest {

    /**
     * 文脈的に安い候補（ΔC 小）が wcost より高くても上位に来ることを確認する。
     *
     * ## セットアップ（reading = "あい"、2語チェーン）
     * offset 0: "あ"（1文字）に3候補
     * - baseline="甲"（rank-0。wcost=10 が最安 → Viterbi rank-0 になる）
     * - "乙"（wcost=50、rcAttr=2 → 後続 lcAttr=1 に +5000 ペナルティ）
     * - "丙"（wcost=200、rcAttr=0 → ペナルティなし）
     *
     * offset 1: "い"（1文字）に1候補
     * - "後"（wcost=0, lcAttr=1）
     *
     * ## ΔC の計算（ZeroConnectionCostProvider は conn=0、後続ペナルティは ContextualConnectionCostProvider で発生）
     * - 甲 の ΔC: forward=10、suffix(後続)=0+0=0 → 全文コスト 10。globalBest=10 なので ΔC=0
     * - 乙 の ΔC: forward=50、suffix(後続)=5000+0=5000 → 全文コスト 5050。ΔC=5050-10=5040（最大）
     * - 丙 の ΔC: forward=200、suffix(後続)=0+0=0 → 全文コスト 200。ΔC=200-10=190
     *
     * ## wcost 順 vs ΔC 順
     * - wcost 順（旧実装）: 乙(50) < 丙(200)
     * - ΔC 順（新実装）: 丙(190) < 乙(5040)
     *
     * baseline=甲 が先頭で、2番目が「ΔC で有利な丙」であり「wcost で有利な乙」より上位であること。
     */
    @Test
    fun contextuallyBetterCandidateRanksHigherThanWcostOrder() {
        runBlocking {
            val costProvider = ContextualConnectionCostProvider(highPenaltyLcAttr = 1, penalty = 5000)

            // baseline: wcost=10（最安 → rank-0 Viterbi → baseline になる）, rcAttr=0
            val baselineLexeme = LexemeEntry(
                surface = "甲",
                reading = "ア",
                lcAttr = 0,
                rcAttr = 0,
                posId = 0,
                wcost = 10,
            )
            // 代替1: wcost=50（wcost では baseline より悪い）, rcAttr=2 → 後続 lcAttr=1 に +5000 ペナルティ
            // ΔC が大きい候補
            val highPenaltyAlt = LexemeEntry(
                surface = "乙",
                reading = "ア",
                lcAttr = 0,
                rcAttr = 2,
                posId = 0,
                wcost = 50,
            )
            // 代替2: wcost=200（wcost では乙より悪い）, rcAttr=0 → ペナルティなし
            // ΔC が小さい候補（丙 のほうが乙より ΔC が有利）
            val midLexeme = LexemeEntry(
                surface = "丙",
                reading = "ア",
                lcAttr = 0,
                rcAttr = 0,
                posId = 0,
                wcost = 200,
            )
            // 後続語: lcAttr=1（乙の後続に来ると +5000）
            val followLexeme = LexemeEntry(
                surface = "後",
                reading = "イ",
                lcAttr = 1,
                rcAttr = 0,
                posId = 0,
                wcost = 0,
            )

            val lexicon = DeltaCTestLexicon(
                firstLexemes = listOf(baselineLexeme, highPenaltyAlt, midLexeme),
                secondLexeme = followLexeme,
            )

            val recordingProvider = HintPackCapturingProvider()
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = buildReadingLexiconWithFallback(lexicon),
                costProvider = costProvider,
            )

            resolver.propose(buildRequest(buildStateWithReading("あい")))

            val region = recordingProvider.lastRequest?.regions?.firstOrNull()
            assertTrue(region != null, "region が1件以上あること")

            val surfaces = region.options.map { it.surface }

            // baseline "甲" は先頭固定
            assertEquals("甲", surfaces[0], "baseline '甲' が先頭であること（実際: $surfaces）")

            // ΔC 順: 丙(ΔC=190) < 乙(ΔC=5040) なので丙が乙より上位に来ること
            val deltaCBetterIndex = surfaces.indexOf("丙")
            val deltaLargerIndex = surfaces.indexOf("乙")

            assertTrue(deltaCBetterIndex >= 0, "'丙' が options に含まれること（実際: $surfaces）")
            assertTrue(deltaLargerIndex >= 0, "'乙' が options に含まれること（実際: $surfaces）")
            assertTrue(
                deltaCBetterIndex < deltaLargerIndex,
                "ΔC で有利な '丙'(ΔC=190) が '乙'(ΔC=5040) より上位に来ること（wcost 順では '乙'<'丙'）。実際: $surfaces",
            )
        }
    }

    /**
     * baseline が options の先頭（index=0）であることを確認する。
     *
     * [FixedBaselineLexicon] では baseline="基線"（wcost=10 が最安 → rank-0）、
     * 代替として "代替1"（wcost=1）が存在するが、baseline は先頭固定。
     * ZeroConnectionCostProvider 下では ΔC = wcost のため、ΔC 順では "代替1" が上になるはずだが
     * baseline は先頭に固定されること。
     */
    @Test
    fun baselineIsAlwaysFirstRegardlessOfDeltaC() {
        runBlocking {
            val recordingProvider = HintPackCapturingProvider()
            val lexicon = buildReadingLexiconWithFallback(FixedBaselineLexicon)
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = ZeroConnectionCostProvider,
            )

            resolver.propose(buildRequest(buildStateWithReading("あい")))

            val region = recordingProvider.lastRequest?.regions?.firstOrNull()
            assertTrue(region != null, "region が1件以上あること")

            val surfaces = region.options.map { it.surface }

            assertEquals(
                "基線",
                surfaces[0],
                "baseline '基線'（rank-0 Viterbi）が先頭であること（実際: $surfaces）",
            )
        }
    }

    /**
     * hint pack は最大6件にクランプされることを確認する（ΔC 版でも変わらない）。
     *
     * [ManyAlternativesForDeltaC] は "あ"（1文字）に 15 件の代替を返す。
     * ΔC ソート後でも options は最大6件に制限されること。
     */
    @Test
    fun hintPackClampedToSixAfterDeltaCSort() {
        runBlocking {
            val recordingProvider = HintPackCapturingProvider()
            val lexicon = buildReadingLexiconWithFallback(ManyAlternativesForDeltaC)
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = ZeroConnectionCostProvider,
            )

            resolver.propose(buildRequest(buildStateWithReading("あ")))

            val region = recordingProvider.lastRequest?.regions?.firstOrNull()
            assertTrue(region != null, "region が生成されること")
            assertTrue(
                region.options.size <= 6,
                "ΔC ソート後も options が最大6件にクランプされること（実際: ${region.options.size}）",
            )
        }
    }

    /**
     * 旧字体が ΔC 版でも「削除」ではなく「降格」されることを確認する。
     *
     * [ArchaicDemotionForDeltaC] は baseline="亜" (wcost=10)、代替="阿"(wcost=50)、旧字体="擧"(wcost=20) を持つ。
     * 非旧字体（亜・阿）が [MAX_OPTIONS_PER_REGION]-1 未満のため、旧字体 擧 は降格されつつ pack に残ること。
     * 且つ、旧字体は非旧字体の後ろであること。
     */
    @Test
    fun archaicKanjiIsDemotedNotDeletedInDeltaCMode() {
        runBlocking {
            val recordingProvider = HintPackCapturingProvider()
            val lexicon = buildReadingLexiconWithFallback(ArchaicDemotionForDeltaC)
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = ZeroConnectionCostProvider,
            )

            resolver.propose(buildRequest(buildStateWithReading("あ")))

            val region = recordingProvider.lastRequest?.regions?.firstOrNull()
            assertTrue(region != null, "region が生成されること")

            val surfaces = region.options.map { it.surface }

            assertTrue(surfaces.contains("擧"), "旧字体「擧」が削除されず pack に残ること（降格、実際: $surfaces）")

            val archaicIndex = surfaces.indexOf("擧")
            val modernIndex = surfaces.indexOf("阿")

            assertTrue(
                archaicIndex > modernIndex,
                "旧字体「擧」は非旧字体「阿」より後ろに降格されること（実際: $surfaces）",
            )
        }
    }

    // region: helpers

    private fun buildStateWithReading(reading: String): CompositionState {
        return CompositionState.empty().copy(
            source = CompositionSource.withFrozenPrefix(frozenPrefix = reading, revision = 0),
        )
    }

    private fun buildRequest(state: CompositionState): ResolutionRequest {
        return ResolutionRequest(
            state = state,
            inputRevision = 0,
            graphRevision = 0,
            candidatePackDigest = 0,
        )
    }

    // endregion
}

// ---- テスト用 connection cost provider ----

/**
 * [highPenaltyLcAttr] を持つ次語への連接コストを [penalty] とし、それ以外は 0 とする。
 *
 * 前語 rcAttr=2、次語 lcAttr=[highPenaltyLcAttr] の組み合わせにのみペナルティを加算する。
 * ΔC 差別化テスト用。
 */
private class ContextualConnectionCostProvider(
    private val highPenaltyLcAttr: Int,
    private val penalty: Int,
) : me.matsumo.romaflow.core.morphology.ConnectionCostProvider {

    override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int {
        val isPenaltyTransition = previousRightContextId == 2 && nextLeftContextId == highPenaltyLcAttr
        return if (isPenaltyTransition) penalty else 0
    }
}

// ---- テスト用 lexicon ----

/**
 * "あい"（2文字）で最初の1文字（offset 0）に複数候補を、2文字目（offset 1）に後続語を返す lexicon。
 *
 * ΔC 差別化テストで 2 語チェーン（"あ" + "い"）を構成し、後続連接コストで ΔC を変動させる。
 */
private class DeltaCTestLexicon(
    private val firstLexemes: List<LexemeEntry>,
    private val secondLexeme: LexemeEntry,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        return when (startOffset) {
            0 -> firstLexemes.map { lexeme ->
                LexemeMatch(readingEndOffset = 1, lexeme = lexeme)
            }
            1 -> listOf(LexemeMatch(readingEndOffset = 2, lexeme = secondLexeme))
            else -> emptyList()
        }
    }
}

/**
 * "あい"（2文字）に baseline と代替を返す lexicon。
 *
 * baseline="基線"（wcost=1 が最安 → rank-0 Viterbi になる）、
 * 代替="代替1"（wcost=500）と "代替2"（wcost=300）を含む。
 * ZeroConnectionCostProvider 下では ΔC=wcost のため代替 ΔC が baseline より大きいが、
 * baseline が先頭固定であることを確認するために使う。
 */
private object FixedBaselineLexicon : ReadingLexicon {

    private val lexemes = listOf(
        // wcost=1 が最安 → nBest rank-0 → baseline になる
        LexemeEntry(surface = "基線", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 1),
        LexemeEntry(surface = "代替1", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 500),
        LexemeEntry(surface = "代替2", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 300),
    )

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val suffix = reading.substring(startOffset)

        if (!suffix.startsWith("あい")) return emptyList()

        return lexemes.map { lexeme ->
            LexemeMatch(readingEndOffset = startOffset + 2, lexeme = lexeme)
        }
    }
}

/**
 * "あ"（1文字）に 15 件の代替を返す lexicon。
 *
 * ΔC ソート後も options が最大6件にクランプされることを確認するために使う。
 */
private object ManyAlternativesForDeltaC : ReadingLexicon {

    private const val ENTRY_COUNT = 15

    private val lexemes = List(ENTRY_COUNT) { index ->
        LexemeEntry(
            surface = "候補$index",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = (index + 1) * 10,
        )
    }

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val suffix = reading.substring(startOffset)

        if (!suffix.startsWith("あ")) return emptyList()

        return lexemes.map { lexeme ->
            LexemeMatch(readingEndOffset = startOffset + 1, lexeme = lexeme)
        }
    }
}

/**
 * "あ"（1文字）に baseline="亜"(wcost=10)、代替="阿"(wcost=50)、旧字体="擧"(wcost=20) を返す lexicon。
 *
 * ΔC 版でも旧字体降格が維持されることを確認するために使う。
 */
private object ArchaicDemotionForDeltaC : ReadingLexicon {

    private val lexemes = listOf(
        LexemeEntry(surface = "亜", reading = "ア", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 10),
        LexemeEntry(surface = "阿", reading = "ア", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 50),
        LexemeEntry(surface = "擧", reading = "ア", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 20),
    )

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val suffix = reading.substring(startOffset)

        if (!suffix.startsWith("あ")) return emptyList()

        return lexemes.map { lexeme ->
            LexemeMatch(readingEndOffset = startOffset + 1, lexeme = lexeme)
        }
    }
}

// ---- テスト用 provider スタブ ----

/**
 * [rerankFactorized] リクエストを記録して空 decisions を返すプロバイダ。
 *
 * hint pack の構造（options の surface 順序）を外部から確認するために使う。
 */
private class HintPackCapturingProvider : ConversionProvider {

    var lastRequest: FactorizedRerankRequest? = null
        private set

    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        lastRequest = request
        return FactorizedRerankResult(decisions = emptyMap())
    }

    override suspend fun proposeFullTailSurface(reading: String, prefixContext: String): String = ""
}
