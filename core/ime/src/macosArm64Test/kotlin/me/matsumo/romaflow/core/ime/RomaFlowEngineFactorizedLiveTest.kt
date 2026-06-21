package me.matsumo.romaflow.core.ime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * factorized rerank の end-to-end live テスト（実 OpenAI + 本番経路 [RomaFlowEngine]）。
 *
 * 本番 constructor [RomaFlowEngine]（既定 AI provider・momiji segmenter・DP aligner・IPADIC・
 * [RerankMode.Factorized]）を使い、romaji 入力 → [RomaFlowEngine.convert] → [RomaFlowEngine.applyConversion]
 * → segment 表層連結、という IME 実フローをそのまま回して期待表層と比較する。
 *
 * [LIVE_CASES] に「romaji 入力 → 期待表層」を追加すれば任意ケースを検証できる。
 *
 * 実 LLM を叩くため二重 opt-in:
 * 1. `BuildKonfig.OPENAI_API_KEY` が非空
 * 2. 環境変数 `A4_LIVE_TEST_ENABLED=true`
 *
 * 実行例:
 * ```
 * A4_LIVE_TEST_ENABLED=true ./gradlew :core:ime:macosArm64Test \
 *   --tests '*RomaFlowEngineFactorizedLiveTest' --no-configuration-cache -i
 * ```
 * いずれかの条件を満たさない場合は SKIP（assert せず戻る）。
 */
@Suppress("FunctionNaming")
class RomaFlowEngineFactorizedLiveTest {

    /**
     * factorized live 検証ケース。
     *
     * @param romaji IME へ入力する romaji 文字列。
     * @param expected applyConversion 後に期待する segment 表層の連結。
     */
    private data class LiveCase(
        val romaji: String,
        val expected: String,
    )

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun factorized_liveConversion_matchesExpectedSurface() = runBlocking {
        val apiKey = BuildKonfig.OPENAI_API_KEY
        val liveTestEnabled = getenv("A4_LIVE_TEST_ENABLED")?.toKString() == "true"

        if (apiKey.isEmpty()) {
            println("=== Factorized Live E2E: SKIPPED (OPENAI_API_KEY is not configured) ===")
            return@runBlocking
        }

        if (!liveTestEnabled) {
            println("=== Factorized Live E2E: SKIPPED (A4_LIVE_TEST_ENABLED != true) ===")
            return@runBlocking
        }

        println("=== Factorized Live E2E (model=${BuildKonfig.OPENAI_MODEL}, cases=${LIVE_CASES.size}) ===")

        val failures = mutableListOf<String>()

        for (liveCase in LIVE_CASES) {
            val actual = runSingleConversion(liveCase.romaji)
            val passed = actual == liveCase.expected

            println("[${if (passed) "OK" else "NG"}] romaji='${liveCase.romaji}' expected='${liveCase.expected}' actual='$actual'")

            if (!passed) {
                failures.add("romaji='${liveCase.romaji}' expected='${liveCase.expected}' actual='$actual'")
            }
        }

        assertTrue(
            failures.isEmpty(),
            "factorized live 変換が期待表層と不一致:\n${failures.joinToString("\n")}",
        )
    }

    /**
     * 本番経路 [RomaFlowEngine] で 1 ケースを変換し、applyConversion 後の segment 表層連結を返す。
     */
    private suspend fun runSingleConversion(romaji: String): String {
        val engine = RomaFlowEngine()

        engine.inputRomaji(romaji)

        val convertResult = engine.convert()

        engine.applyConversion(convertResult)

        return buildString {
            for (segmentIndex in 0 until engine.segmentCount()) {
                append(engine.segmentText(segmentIndex))
            }
        }
    }

    private companion object {
        /** factorized live 検証ケース。任意の「romaji 入力 → 期待表層」をここに追加する。 */
        val LIVE_CASES = listOf(
            LiveCase(romaji = "benkyoushiteseikawoageta", expected = "勉強して成果を上げた"),
        )
    }
}
