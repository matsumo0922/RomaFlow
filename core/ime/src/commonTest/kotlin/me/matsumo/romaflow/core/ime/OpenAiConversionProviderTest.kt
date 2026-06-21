package me.matsumo.romaflow.core.ime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.matsumo.romaflow.core.ime.shadow.DecisionRegion
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest
import me.matsumo.romaflow.core.ime.shadow.RegionOption
import me.matsumo.romaflow.core.morphology.LexemeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [OpenAiConversionProvider] の HTTP 経路を [MockEngine] で検証するテスト。
 */
class OpenAiConversionProviderTest {

    @Test
    fun convert_returnsAssistantContentAndSendsBearerAuth() = runTest {
        val engine = MockEngine {
            respond(
                content = SUCCESS_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val converted = provider.convert(conversionRequest("にほんご"))

        assertEquals("日本語", converted)

        val request = engine.requestHistory.single()
        assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
        assertTrue(request.url.toString().endsWith("/chat/completions"))
    }

    @Test
    fun convert_returnsEmptyWhenApiKeyMissing() = runTest {
        val engine = MockEngine { error("API は key 未設定時に呼ばれてはいけない") }
        val provider = OpenAiConversionProvider(testConfig(""), jsonClient(engine))

        assertEquals("", provider.convert(conversionRequest("にほんご")))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun convert_returnsEmptyOnHttpError() = runTest {
        val engine = MockEngine { respond("error", HttpStatusCode.InternalServerError) }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        // 失敗は据え置き。例外は runCatching で握って空文字を返す
        assertEquals("", provider.convert(conversionRequest("にほんご")))
    }

    @Test
    fun convert_returnsEmptyWhenContentBlank() = runTest {
        val engine = MockEngine {
            respond(
                content = BLANK_CONTENT_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        assertEquals("", provider.convert(conversionRequest("にほんご")))
    }

    @Test
    fun convert_usesReconversionSystemPromptWhenPrefixContextPresent() = runTest {
        val engine = MockEngine {
            respond(
                content = SUCCESS_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        provider.convert(ConversionRequest(readingInput = "てんき", prefixContext = "今日は良い"))

        val requestBody = requestBodyText(engine.requestHistory.single().body)
        assertTrue(requestBody.contains("前方文脈は既に確定済み"))
        assertTrue(requestBody.contains("続きの読み"))
    }

    @Test
    fun convert_usesDefaultSystemPromptWhenPrefixContextBlank() = runTest {
        val engine = MockEngine {
            respond(
                content = SUCCESS_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        provider.convert(conversionRequest("にほんご"))

        val requestBody = requestBodyText(engine.requestHistory.single().body)
        assertTrue(requestBody.contains("かな漢字変換エンジン"))
        assertFalse(requestBody.contains("前方文脈は既に確定済み"))
    }

    @Test
    fun convert_stripsEchoedPrefixContextFromResult() = runTest {
        val engine = MockEngine {
            respond(
                content = ECHOED_PREFIX_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val converted = provider.convert(ConversionRequest(readingInput = "てんき", prefixContext = "今日は良い"))

        assertEquals("天気", converted)
    }

    @Test
    fun candidates_returnsRawJsonAndSendsJsonSchemaResponseFormat() = runTest {
        val engine = MockEngine {
            respond(
                content = CANDIDATES_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val candidates = provider.candidates(WordCandidateRequest(reading = "てんき", context = "今日の"))

        assertEquals("""{"candidates":["天気","転機"]}""", candidates)

        val requestBody = requestBodyText(engine.requestHistory.single().body)
        assertTrue(requestBody.contains("response_format"))
        assertTrue(requestBody.contains("json_schema"))
    }

    @Test
    fun candidates_returnsEmptyWhenReadingBlank() = runTest {
        val engine = MockEngine { error("API は blank reading で呼ばれてはいけない") }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        assertEquals("", provider.candidates(WordCandidateRequest(reading = "  ", context = "")))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun convert_doesNotSendResponseFormat() = runTest {
        val engine = MockEngine {
            respond(
                content = SUCCESS_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        provider.convert(conversionRequest("にほんご"))

        val requestBody = requestBodyText(engine.requestHistory.single().body)
        assertFalse(requestBody.contains("response_format"))
    }

    @Test
    fun rerank_returnsIndexFromStructuredOutput() = runTest {
        // モックが `{"index": 2}` を返すとき rerank() が 2 を返すこと（正常系）
        val engine = MockEngine {
            respond(
                content = RERANK_INDEX_2_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        // 候補が3件（index 0-2）あるとき index 2 は有効範囲内
        val result = provider.rerank(rerankRequest(candidatesSize = 3))

        assertEquals(2, result)

        // Structured Outputs の response_format が送出されていること
        val requestBody = requestBodyText(engine.requestHistory.single().body)
        assertTrue(requestBody.contains("response_format"))
        assertTrue(requestBody.contains("json_schema"))
    }

    @Test
    fun rerank_clampsOutOfRangeIndexToMinusOne() = runTest {
        // モックが候補数（3件）より大きい index 99 を返すとき -1 にクランプされること
        val engine = MockEngine {
            respond(
                content = RERANK_INDEX_99_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val result = provider.rerank(rerankRequest(candidatesSize = 3))

        assertEquals(-1, result)
    }

    @Test
    fun rerank_returnsMinusOneWhenApiKeyMissing() = runTest {
        // apiKey 空 → ネットワークに行かず -1
        val engine = MockEngine { error("API は key 未設定時に呼ばれてはいけない") }
        val provider = OpenAiConversionProvider(testConfig(""), jsonClient(engine))

        val result = provider.rerank(rerankRequest(candidatesSize = 3))

        assertEquals(-1, result)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun rerank_returnsMinusOneOnMalformedJson() = runTest {
        // 不正 JSON → parseRerankIndex が失敗して -1
        val engine = MockEngine {
            respond(
                content = MALFORMED_RERANK_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val result = provider.rerank(rerankRequest(candidatesSize = 3))

        assertEquals(-1, result)
    }

    @Test
    fun rerankFactorized_returnsChoicesFromStructuredOutput() = runTest {
        // モックが `{"choices":[{"region_id":"r0","option_id":"r0o1"}]}` を返すとき
        // rerankFactorized() が r0→r0o1 の choices を返すこと（正常系）
        val engine = MockEngine {
            respond(
                content = FACTORIZED_RERANK_SUCCESS_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val result = provider.rerankFactorized(factorizedRerankRequest())

        assertEquals(mapOf("r0" to "r0o1"), result.choices)

        // Structured Outputs の response_format と json_schema が送出されていること
        val requestBody = requestBodyText(engine.requestHistory.single().body)
        assertTrue(requestBody.contains("response_format"))
        assertTrue(requestBody.contains("json_schema"))
        assertTrue(requestBody.contains("factorized_rerank_selection"))
    }

    @Test
    fun rerankFactorized_returnsEmptyChoicesOnMalformedJson() = runTest {
        // 不正 JSON → parseFactorizedRerankResult が choices 空を返すこと
        val engine = MockEngine {
            respond(
                content = MALFORMED_RERANK_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val result = provider.rerankFactorized(factorizedRerankRequest())

        assertTrue(result.choices.isEmpty())
    }

    @Test
    fun rerankFactorized_skipsOutOfRangeOptionId() = runTest {
        // option_id が実在しない "r0o99" を返したとき、当該 region が choices に含まれないこと
        val engine = MockEngine {
            respond(
                content = FACTORIZED_RERANK_OUT_OF_RANGE_RESPONSE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiConversionProvider(testConfig("test-key"), jsonClient(engine))

        val result = provider.rerankFactorized(factorizedRerankRequest())

        // r0o99 は有効な option_id でないため r0 が choices に入らないこと
        assertFalse(result.choices.containsKey("r0"))
    }

    private companion object {
        const val SUCCESS_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"日本語"}}]}"""

        const val BLANK_CONTENT_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"   "}}]}"""

        const val CANDIDATES_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"{\"candidates\":[\"天気\",\"転機\"]}"}}]}"""

        const val ECHOED_PREFIX_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"今日は良い天気"}}]}"""

        // rerank テスト用: Structured Outputs で `{"index": 2}` を返すモックレスポンス
        const val RERANK_INDEX_2_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"{\"index\":2}"}}]}"""

        // rerank テスト用: 候補数（3件）を超える index 99 → -1 クランプを確認
        const val RERANK_INDEX_99_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"{\"index\":99}"}}]}"""

        // rerank テスト用: 不正 JSON（`"index"` キーが無い）→ parseRerankIndex が -1 を返すことを確認
        const val MALFORMED_RERANK_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"not-json"}}]}"""

        // rerankFactorized テスト用: r0→r0o1 の正常レスポンス
        const val FACTORIZED_RERANK_SUCCESS_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"{\"choices\":[{\"region_id\":\"r0\",\"option_id\":\"r0o1\"}]}"}}]}"""

        // rerankFactorized テスト用: 存在しない option_id（r0o99）→ 当該 region が未選択になることを確認
        const val FACTORIZED_RERANK_OUT_OF_RANGE_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"{\"choices\":[{\"region_id\":\"r0\",\"option_id\":\"r0o99\"}]}"}}]}"""
    }
}

/** readingInput だけを設定した lock 無しの [ConversionRequest]。 */
private fun conversionRequest(readingInput: String): ConversionRequest {
    return ConversionRequest(readingInput, "")
}

/**
 * テスト用の [RerankRequest]。reading は "てんき"、prefixContext は空、候補は [candidatesSize] 件。
 *
 * 候補の内容はテスト本体のアサートには影響しない（index の範囲判定のみに使う）。
 */
private fun rerankRequest(candidatesSize: Int): RerankRequest {
    val candidates = List(candidatesSize) { index -> "候補$index" }
    return RerankRequest(reading = "てんき", prefixContext = "", candidates = candidates)
}

/** 送信された [OutgoingContent] を UTF-8 文字列として読み出す。JSON ボディは ByteArrayContent。 */
private fun requestBodyText(content: OutgoingContent): String {
    val byteArrayContent = content as OutgoingContent.ByteArrayContent

    return byteArrayContent.bytes().decodeToString()
}

/** テスト用の [OpenAiConfig]。 */
private fun testConfig(apiKey: String): OpenAiConfig {
    return OpenAiConfig(apiKey = apiKey, baseUrl = "https://example.test/v1", model = "test-model")
}

/** ContentNegotiation を入れた [MockEngine] ベースの [HttpClient]。 */
private fun jsonClient(engine: MockEngine): HttpClient {
    return HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}

/**
 * テスト用の [FactorizedRerankRequest]。
 *
 * region r0 に option r0o0（愛）と r0o1（藍）の2件を持つシンプルな request。
 * MockEngine テストでは LLM 応答内容のみを検証するため、lexemePath は空でよい。
 */
private fun factorizedRerankRequest(): FactorizedRerankRequest {
    val stubLexeme = LexemeEntry(surface = "", reading = "", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 0)
    val options = listOf(
        RegionOption(id = "r0o0", surface = "愛", lexemePath = listOf(stubLexeme)),
        RegionOption(id = "r0o1", surface = "藍", lexemePath = listOf(stubLexeme)),
    )
    val region = DecisionRegion(
        id = "r0",
        reading = "あい",
        readingStart = 0,
        readingEnd = 2,
        options = options,
    )

    return FactorizedRerankRequest(
        template = "{r0}",
        prefixContext = "",
        regions = listOf(region),
    )
}
