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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [FactorizedRerankResolver] のユニットテスト。
 *
 * 実 API 不要。[AmbiguousSpanLexicon]・[SingleSurfaceLexicon]・決定論的 provider スタブで検証する。
 *
 * ## 検証項目
 * - **region 抽出**: 同一 reading span に複数 surface がある場合に [DecisionRegion] が生成され、
 *   1 surface のみの span は確定部になること。
 * - **option pack**: baseline 必須・surface dedup・最大6件が守られること。
 * - **組み立て**: choices が返ると選択 option の surface が完全 surface に反映されること。
 * - **fallback（region 0）**: 曖昧箇所なしのとき LLM を呼ばずに baseline surface を返すこと。
 * - **fallback（全未選択）**: choices 空のとき baseline surface を返すこと。
 * - **fallback（reading 空）**: KeepCurrent を返すこと。
 */
@Suppress("FunctionNaming")
class FactorizedRerankResolverTest {

    /**
     * 同一 reading span に複数 surface がある場合、[DecisionRegion] が生成されることを確認する。
     *
     * [AmbiguousSpanLexicon] は "あい"（2文字）に "愛" (wcost=10) と "藍" (wcost=200) を返す。
     * baseline（rank-0="愛"）と代替（"藍"）の2 surface がある → region として抽出されるはずである。
     * recording provider で request を受け取り、regions が1件以上あることを assert する。
     */
    @Test
    fun extractsRegionForAmbiguousSpan() {
        runBlocking {
            val recordingProvider = RecordingFactorizedProvider()
            val lexicon = buildReadingLexiconWithFallback(AmbiguousSpanLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading("あい")
            resolver.propose(buildRequest(state))

            val request = recordingProvider.lastRequest

            assertTrue(
                request != null && request.regions.isNotEmpty(),
                "曖昧 span がある場合に regions が1件以上生成されること（実際: ${request?.regions?.size ?: "未呼び出し"}）",
            )
        }
    }

    /**
     * 1 surface のみの span は確定部としてテンプレートに固定され、region にならないことを確認する。
     *
     * [SingleSurfaceLexicon] は "きき"（2文字）に "木木" のみを返す（baseline のみ・代替なし）。
     * LiteralLexicon は1文字 arc（き）しか提供できないため、"木木"（2文字 span）の代替にはならず
     * region が 0 個になる。LLM を呼ばずに baseline surface をそのまま返すこと。
     */
    @Test
    fun noRegionForSingleSurfaceSpan() {
        runBlocking {
            val recordingProvider = RecordingFactorizedProvider()
            val lexicon = buildReadingLexiconWithFallback(SingleSurfaceLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading("きき")
            val proposal = resolver.propose(buildRequest(state))

            assertTrue(
                recordingProvider.lastRequest == null,
                "単一 surface の span では rerankFactorized が呼ばれないこと",
            )
            assertIs<ResolutionProposal.ProposeJointCorrection>(proposal)
        }
    }

    /**
     * region の options に baseline surface が必ず含まれることを確認する。
     *
     * baseline が選択肢から外れると LLM が「変換しない」を選べなくなるため、必ず含める必要がある。
     */
    @Test
    fun baselineOptionAlwaysPresentInRegionOptions() {
        runBlocking {
            val recordingProvider = RecordingFactorizedProvider()
            val lexicon = buildReadingLexiconWithFallback(AmbiguousSpanLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading("あい")
            resolver.propose(buildRequest(state))

            val region = recordingProvider.lastRequest?.regions?.firstOrNull()
            assertTrue(region != null, "region が1件以上あること")

            // baseline の rank-0 surface（"愛"）が options に含まれること
            val optionSurfaces = region.options.map { it.surface }
            assertTrue(
                optionSurfaces.contains("愛"),
                "baseline surface '愛' が options に含まれること（実際: $optionSurfaces）",
            )
        }
    }

    /**
     * region の options が最大6件にクランプされることを確認する。
     *
     * [ManyAlternativesLexicon] は同一 reading span に 10 件以上の alternative を返す。
     * options は最大6件に制限されることを確認する。
     */
    @Test
    fun optionsClampedToMaxSix() {
        runBlocking {
            val recordingProvider = RecordingFactorizedProvider()
            val lexicon = buildReadingLexiconWithFallback(ManyAlternativesLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading("あ")
            resolver.propose(buildRequest(state))

            val region = recordingProvider.lastRequest?.regions?.firstOrNull()
            assertTrue(region != null, "region が生成されること")
            assertTrue(
                region.options.size <= 6,
                "options が最大6件にクランプされること（実際: ${region.options.size}）",
            )
        }
    }

    /**
     * 旧字体が「削除」ではなく「降格」されることを確認する（非旧字体が最大件数未満なら枠が余り旧字体が後段に入る）。
     *
     * [ArchaicDemotionLexicon] は "あ" に 亜(wcost5)・阿(wcost10)・擧(wcost20, 旧字体) を返す。
     * 非旧字体は 亜・阿 の2件のみで6件に満たないため、旧字体 擧 は降格されつつ pack に残るべき。
     * 旧 2-pass 実装（seenSurfaces を汚すバグ）では 擧 が削除され options に出てこない。
     */
    @Test
    fun archaicKanjiIsDemotedNotDeleted() {
        runBlocking {
            val recordingProvider = RecordingFactorizedProvider()
            val lexicon = buildReadingLexiconWithFallback(ArchaicDemotionLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = costProvider,
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

    /**
     * choices が返ると選択 option の surface が preferredSurface に反映されることを確認する。
     *
     * [AmbiguousSpanLexicon] で "あい" に "愛"(rank-0) と "藍"(rank-1) がある。
     * "藍" を選択する choices を返す [FixedChoiceProvider] で、preferredSurface が "藍" になることを確認する。
     */
    @Test
    fun selectedOptionSurfaceIsReflectedInPreferredSurface() {
        runBlocking {
            val lexicon = buildReadingLexiconWithFallback(AmbiguousSpanLexicon)
            val costProvider = ZeroConnectionCostProvider

            // まず region と option を確認する
            val recordingProvider = RecordingFactorizedProvider()
            val recordingResolver = FactorizedRerankResolver(
                conversionProvider = recordingProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )
            recordingResolver.propose(buildRequest(buildStateWithReading("あい")))
            val region = requireNotNull(recordingProvider.lastRequest).regions.first()
            val alternativeOption = region.options.first { it.surface != "愛" }

            // alternativeOption を選択する provider で preferredSurface を確認する
            val fixedChoiceProvider = FixedChoiceProvider(
                mapOf(region.id to alternativeOption.id),
            )
            val resolver = FactorizedRerankResolver(
                conversionProvider = fixedChoiceProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val proposal = resolver.propose(buildRequest(buildStateWithReading("あい")))

            val jointProposal = assertIs<ResolutionProposal.ProposeJointCorrection>(proposal)
            assertEquals(
                alternativeOption.surface,
                jointProposal.preferredSurface,
                "choices で選択した option の surface が preferredSurface になること",
            )
        }
    }

    /**
     * choices が空（LLM 失敗）のとき baseline surface（Viterbi rank-0）が preferredSurface になることを確認する。
     */
    @Test
    fun emptyChoicesFallsBackToBaseline() {
        runBlocking {
            val emptyChoiceProvider = EmptyChoiceProvider()
            val lexicon = buildReadingLexiconWithFallback(AmbiguousSpanLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = emptyChoiceProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val proposal = resolver.propose(buildRequest(buildStateWithReading("あい")))

            val jointProposal = assertIs<ResolutionProposal.ProposeJointCorrection>(proposal)
            // baseline rank-0 は wcost 最小の "愛"
            assertEquals(
                "愛",
                jointProposal.preferredSurface,
                "choices 空のとき baseline（rank-0='愛'）が採用されること",
            )
        }
    }

    /**
     * reading が空のとき [ResolutionProposal.KeepCurrent] を返すことを確認する。
     */
    @Test
    fun keepCurrentForEmptyReading() {
        runBlocking {
            val provider = EmptyChoiceProvider()
            val lexicon = buildReadingLexiconWithFallback(EmptyReadingLexicon)
            val costProvider = ZeroConnectionCostProvider
            val resolver = FactorizedRerankResolver(
                conversionProvider = provider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val proposal = resolver.propose(buildRequest(CompositionState.empty()))

            assertIs<ResolutionProposal.KeepCurrent>(
                proposal,
                "空の reading には KeepCurrent を返すこと",
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

// ---- テスト用 lexicon ----

/**
 * "あい"（2文字）に "愛"(wcost=10) と "藍"(wcost=200) の 2 surface を返すテスト用 lexicon。
 *
 * [ZeroConnectionCostProvider] 下では wcost のみで順序が決まるため、rank-0="愛"、rank-1="藍"。
 * region 抽出テストの主役として使う。
 */
private object AmbiguousSpanLexicon : ReadingLexicon {

    private val lexemes = listOf(
        LexemeEntry(surface = "愛", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 10),
        LexemeEntry(surface = "藍", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 200),
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
 * "きき"（2文字）に "木木"（1 surface のみ）を返すテスト用 lexicon。
 *
 * LiteralLexicon は1文字 arc（き）しか提供できないため、"木木"（2文字 span）の代替にはならない。
 * 結果として同一 reading span に surface が1件のみとなり、region が生成されない（確定部扱い）
 * ことを確認するために使う。
 */
private object SingleSurfaceLexicon : ReadingLexicon {

    private val lexeme = LexemeEntry(
        surface = "木木",
        reading = "キキ",
        lcAttr = 0,
        rcAttr = 0,
        posId = 0,
        wcost = 100,
    )

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val suffix = reading.substring(startOffset)

        if (!suffix.startsWith("きき")) return emptyList()

        return listOf(LexemeMatch(readingEndOffset = startOffset + 2, lexeme = lexeme))
    }
}

/**
 * "あ"（1文字）に 10 件以上の distinct surface を返すテスト用 lexicon。
 *
 * options の最大6件クランプを確認するために使う。
 */
private object ManyAlternativesLexicon : ReadingLexicon {

    private const val ENTRY_COUNT = 15

    private val lexemes = List(ENTRY_COUNT) { index ->
        LexemeEntry(
            surface = "語$index",
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
 * "あ"（1文字）に 亜(wcost5)・阿(wcost10)・擧(wcost20, 旧字体) を返すテスト用 lexicon。
 *
 * 非旧字体（亜・阿）が6件未満なので、旧字体 擧 が削除されず降格されて pack に残ることの確認に使う。
 */
private object ArchaicDemotionLexicon : ReadingLexicon {

    private val lexemes = listOf(
        LexemeEntry(surface = "亜", reading = "ア", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 5),
        LexemeEntry(surface = "阿", reading = "ア", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 10),
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
 * [rerankFactorized] リクエストを記録して空 choices を返すプロバイダ。
 *
 * region の構造を外部から確認するために使う。
 */
private class RecordingFactorizedProvider : ConversionProvider {

    var lastRequest: FactorizedRerankRequest? = null
        private set

    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        lastRequest = request
        return FactorizedRerankResult(choices = emptyMap())
    }
}

/**
 * 固定の choices を返すプロバイダ。
 *
 * 選択した option が preferredSurface に反映されることを確認するために使う。
 */
private class FixedChoiceProvider(
    private val fixedChoices: Map<String, String>,
) : ConversionProvider {

    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        return FactorizedRerankResult(choices = fixedChoices)
    }
}

/**
 * 空の choices を返すプロバイダ（LLM 失敗シミュレーション）。
 */
private class EmptyChoiceProvider : ConversionProvider {

    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        return FactorizedRerankResult(choices = emptyMap())
    }
}
