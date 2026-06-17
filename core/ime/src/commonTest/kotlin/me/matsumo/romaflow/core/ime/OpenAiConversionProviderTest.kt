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

    private companion object {
        const val SUCCESS_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"日本語"}}]}"""

        const val BLANK_CONTENT_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"   "}}]}"""

        const val CANDIDATES_RESPONSE_JSON =
            """{"choices":[{"message":{"role":"assistant","content":"{\"candidates\":[\"天気\",\"転機\"]}"}}]}"""
    }
}

/** readingInput だけを設定した lock 無しの [ConversionRequest]。 */
private fun conversionRequest(readingInput: String): ConversionRequest {
    return ConversionRequest(readingInput, "")
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
