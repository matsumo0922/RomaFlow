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

        // content 語「あげ」の候補に「上げ」「挙げ」「揚げ」が全て含まれる
        val ageRegion = request.regions.firstOrNull { it.reading == "あげ" }
        assertNotNull(ageRegion, "「あげ」が region 化されること（実際の region 読み: $regionReadings）")
        val ageSurfaces = ageRegion.options.map { it.surface }
        for (expectedSurface in listOf("上げ", "挙げ", "揚げ")) {
            assertTrue(
                ageSurfaces.contains(expectedSurface),
                "あげ region に「$expectedSurface」が含まれること（実際: $ageSurfaces）",
            )
        }

        // 旧字体（例: 擧 を含む surface）は現代表記より前に出ない（含まれる場合のみ検証）
        assertArchaicCharNotBeforeModern(ageSurfaces, archaicChar = '擧', modern = listOf("上げ", "挙げ", "揚げ"))

        // すべての region が 1 回の request に同時提示される（recording は 1 回だけ呼ばれる）
        assertTrue(recording.callCount == 1, "全 region が 1 回の rerankFactorized 呼び出しで提示されること（実際: ${recording.callCount}）")
    }

    /**
     * カタカナ baseline の content 語が region 化され、正しい漢字代替が候補に出ることを検証する。
     *
     * 辞書 Viterbi rank-0 が誤って片仮名になる語（いか→イカ、かな→カナ）は、純ひらがなでないため region 化され、
     * 正解候補（以下 / 仮名）が pack に入る。これにより「カタカナで固定されて正解を選べない」過去の不具合
     * （いかのしりょうによれば→イカの…、かんじかなまじりぶん→…カナ…）を防ぐ。
     * なお最終的にどれが選ばれるかは LLM 依存（本テストは候補被覆のみを決定論で保証する）。
     */
    @Test
    fun katakanaBaselineContentWords_areRegionizedWithKanjiAlternatives() = runBlocking {
        val recordingForIka = RecordingRegionPackProvider()
        FactorizedRerankResolver(recordingForIka, lexicon, costProvider)
            .propose(buildRequest("いかのしりょうによれば"))
        val ikaRequest = assertNotNull(recordingForIka.lastRequest, "content 語の曖昧 span があり request が作られること")
        printRegionPack(ikaRequest)

        // ① 検証: カタカナ baseline の「いか」が確定部に固定されず region 化されること（イカ ロック解消）。
        // 注: 正解「以下」が最大6件に入るかは候補ランキング（wcost）依存で、本テストでは保証しない（既知の限界 ②）。
        val ikaRegion = ikaRequest.regions.firstOrNull { it.reading == "いか" }
        assertNotNull(ikaRegion, "カタカナ baseline の「いか」が region 化されること（読み: ${ikaRequest.regions.map { it.reading }}）")
        assertTrue(
            ikaRegion.options.size >= 2,
            "いか region に複数候補が提示されること（実際: ${ikaRegion.options.map { it.surface }}）",
        )

        val recordingForKanji = RecordingRegionPackProvider()
        FactorizedRerankResolver(recordingForKanji, lexicon, costProvider)
            .propose(buildRequest("かんじかなまじりぶん"))
        val kanjiRequest = assertNotNull(recordingForKanji.lastRequest, "request が作られること")
        printRegionPack(kanjiRequest)

        val kanaRegion = kanjiRequest.regions.firstOrNull { it.reading == "かな" }
        assertNotNull(kanaRegion, "カタカナ baseline の「かな」が region 化されること（読み: ${kanjiRequest.regions.map { it.reading }}）")

        val kanjiRegion = kanjiRequest.regions.firstOrNull { it.reading == "かんじ" }
        assertNotNull(kanjiRegion, "「かんじ」が region 化されること")
        assertTrue(
            kanjiRegion.options.any { it.surface == "漢字" },
            "かんじ region に「漢字」が含まれること（実際: ${kanjiRegion.options.map { it.surface }}）",
        )

        val bunRegion = kanjiRequest.regions.firstOrNull { it.reading == "ぶん" }
        assertNotNull(bunRegion, "「ぶん」が region 化されること")
        assertTrue(
            bunRegion.options.any { it.surface == "文" },
            "ぶん region に「文」が含まれること（実際: ${bunRegion.options.map { it.surface }}）",
        )
    }

    private fun assertArchaicCharNotBeforeModern(
        surfaces: List<String>,
        archaicChar: Char,
        modern: List<String>,
    ) {
        // 旧字体 char を含む surface（例: 擧げ）を部分一致で探す。exact 一致だと擧げ等を取りこぼす。
        val archaicIndex = surfaces.indexOfFirst { surface -> surface.contains(archaicChar) }

        if (archaicIndex < 0) return

        val firstModernIndex = modern.mapNotNull { surface -> surfaces.indexOf(surface).takeIf { it >= 0 } }.minOrNull()

        assertTrue(
            firstModernIndex != null && archaicIndex > firstModernIndex,
            "旧字体「$archaicChar」を含む候補は現代表記より後ろであること（surfaces: $surfaces）",
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

/** 受け取った [FactorizedRerankRequest] を記録し、空 decisions を返す provider。 */
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

        return FactorizedRerankResult(decisions = emptyMap())
    }
}
