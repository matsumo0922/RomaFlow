package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.shadow.CompositionState
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResolver
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult
import me.matsumo.romaflow.core.ime.shadow.ResolutionRequest
import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 実 IPADIC を使って [FactorizedRerankResolver] が組み立てる region pack の内容を検証する統合テスト。
 *
 * 最終 surface だけでなく、LLM へ渡す前の [FactorizedRerankRequest]（region 構成・候補）を
 * recording provider で捕まえて assert する（issue #23 の肝＝局所 region pack を正しく作ること）。
 */
@Suppress("FunctionNaming")
class FactorizedRegionPackIntegrationTest {

    private val lexicon by lazy { IpadicReadingLexicon() }
    private val costProvider by lazy { MomijiConnectionCostProvider.load() }

    /**
     * `べんきょうしてせいかをあげた` の region pack を検証する。
     *
     * - 機能語 span（する/して の「し」「て」・助詞「を」）は region 化されない（過変換防止）。
     * - content 語 span「せいか」の候補に「成果」が最大6件内で含まれる。
     * - content 語 span「あげ」の候補に「上げ」「挙げ」「揚げ」が含まれる。
     * - 旧字体「擧」は現代表記（上げ/挙げ/揚げ）より前に出ない。
     * - すべての region が 1 回の request に同時提示される。
     */
    @Test
    fun benkyoushiteseikawoageta_regionPackIsContentScopedAndCovered() = runBlocking {
        val recording = RecordingRegionPackProvider()
        val resolver = FactorizedRerankResolver(recording, lexicon, costProvider)

        resolver.propose(buildRequest("べんきょうしてせいかをあげた"))

        val request = recording.lastRequest
        assertNotNull(request, "content 語の曖昧 span があるため rerankFactorized が1回呼ばれること")

        val regionReadings = request.regions.map { it.reading }
        printRegionPack(request)

        // 機能語 span は region 化されない（漢字を含まない baseline は確定部）
        assertFalse(regionReadings.contains("を"), "助詞「を」は region 化されないこと（実際の region 読み: $regionReadings）")
        assertFalse(regionReadings.contains("し"), "機能語「し」は region 化されないこと（実際の region 読み: $regionReadings）")
        assertFalse(regionReadings.contains("て"), "機能語「て」は region 化されないこと（実際の region 読み: $regionReadings）")

        // content 語「せいか」の候補に「成果」が最大6件内で含まれる
        val seikaRegion = request.regions.firstOrNull { it.reading == "せいか" }
        assertNotNull(seikaRegion, "「せいか」が region 化されること（実際の region 読み: $regionReadings）")
        val seikaSurfaces = seikaRegion.options.map { it.surface }
        assertTrue(seikaRegion.options.size <= 6, "せいか region は最大6件（実際: ${seikaRegion.options.size}）")
        assertTrue(seikaSurfaces.contains("成果"), "せいか region に「成果」が含まれること（実際: $seikaSurfaces）")

        // content 語「あげ」の候補に「上げ」「挙げ」「揚げ」が含まれる
        val ageRegion = request.regions.firstOrNull { it.reading == "あげ" }
        assertNotNull(ageRegion, "「あげ」が region 化されること（実際の region 読み: $regionReadings）")
        val ageSurfaces = ageRegion.options.map { it.surface }
        assertTrue(ageSurfaces.contains("上げ"), "あげ region に「上げ」が含まれること（実際: $ageSurfaces）")

        // 旧字体「擧」は現代表記より前に出ない（含まれる場合のみ検証）
        assertArchaicNotBeforeModern(ageSurfaces, archaic = "擧", modern = listOf("上げ", "挙げ", "揚げ"))

        // すべての region が 1 回の request に同時提示される（recording は 1 回だけ呼ばれる）
        assertTrue(recording.callCount == 1, "全 region が 1 回の rerankFactorized 呼び出しで提示されること（実際: ${recording.callCount}）")
    }

    private fun assertArchaicNotBeforeModern(
        surfaces: List<String>,
        archaic: String,
        modern: List<String>,
    ) {
        val archaicIndex = surfaces.indexOf(archaic)

        if (archaicIndex < 0) return

        val firstModernIndex = modern.mapNotNull { surface -> surfaces.indexOf(surface).takeIf { it >= 0 } }.minOrNull()

        assertTrue(
            firstModernIndex != null && archaicIndex > firstModernIndex,
            "旧字体「$archaic」は現代表記より後ろであること（surfaces: $surfaces）",
        )
    }

    private fun printRegionPack(request: FactorizedRerankRequest) {
        println("REGION PACK template='${request.template}'")
        for (region in request.regions) {
            val options = region.options.joinToString(", ") { "${it.id}:${it.surface}" }
            println("REGION ${region.id} reading='${region.reading}' [$options]")
        }
    }

    private fun buildRequest(reading: String): ResolutionRequest {
        val state = CompositionState.empty().copy(
            source = CompositionSource.withFrozenPrefix(frozenPrefix = reading, revision = 0),
        )

        return ResolutionRequest(
            state = state,
            inputRevision = 0,
            graphRevision = 0,
            candidatePackDigest = 0,
        )
    }
}

/** 受け取った [FactorizedRerankRequest] を記録し、空 choices を返す provider。 */
private class RecordingRegionPackProvider : ConversionProvider {

    var lastRequest: FactorizedRerankRequest? = null
        private set

    var callCount: Int = 0
        private set

    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        lastRequest = request
        callCount++

        return FactorizedRerankResult(choices = emptyMap())
    }
}
