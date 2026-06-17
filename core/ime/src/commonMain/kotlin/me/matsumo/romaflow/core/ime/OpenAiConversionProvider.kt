package me.matsumo.romaflow.core.ime

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI 互換 chat completions API でかな読みを漢字交じり文へ変換する [ConversionProvider]。
 *
 * リクエストは互換性最優先で最小限（model と messages のみ）にし、OpenAI・Gemini 互換・ローカル LLM で
 * 同じ経路が通るようにする。失敗時（API key 未設定・通信エラー・タイムアウト等）は空文字を返し、
 * 呼び出し側で「変換せず据え置き」として扱う。
 *
 * 【暫定実装の注意】これは「全文を LLM に丸ごと変換させる」call1 方式で、応答崩れ（前置き・引用符・markdown・
 * hallucination）への防御を意図的に入れていない。変換状態モデル v7 では、ここで打った通りのかな全体
 * （[ConversionRequest.readingInput]）と lock 制約を投入して全文変換し、単語単位の候補は別経路（call2）で
 * 出す二段構えへ進める。[ConversionRequest.locked] による lock 制約の送出は B2 で実装する。それまでの繋ぎなので、
 * プロンプト強化・サニタイズ・Structured Outputs はここでは未実装のままとする。
 * 参考: Sumibi（プロンプト + API n で複数候補）, azooKey/Zenzai（従来変換器を draft とした投機的デコード）。
 */
internal class OpenAiConversionProvider(
    private val config: OpenAiConfig,
    private val httpClient: HttpClient,
) : ConversionProvider {

    override suspend fun convert(request: ConversionRequest): String {
        val kana = request.readingInput

        if (kana.isBlank() || config.apiKey.isBlank()) {
            return ""
        }

        val result = runCatching { requestConversion(kana) }
        val converted = result.getOrNull()

        if (converted == null) {
            Napier.w("OpenAI conversion failed", result.exceptionOrNull())

            return ""
        }

        return converted.trim()
    }

    override suspend fun candidates(request: WordCandidateRequest): String {
        val reading = request.reading

        if (reading.isBlank() || config.apiKey.isBlank()) {
            return ""
        }

        val result = runCatching { requestCandidates(reading, request.context) }
        val candidatesJson = result.getOrNull()

        if (candidatesJson == null) {
            Napier.w("OpenAI candidates failed", result.exceptionOrNull())

            return ""
        }

        return candidatesJson.trim()
    }

    private suspend fun requestConversion(kana: String): String {
        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = kana),
            ),
            reasoningEffort = REASONING_EFFORT,
        )
        val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val completion = response.body<ChatCompletionResponse>()

        return completion.choices.firstOrNull()?.message?.content.orEmpty()
    }

    private suspend fun requestCandidates(
        reading: String,
        context: String,
    ): String {
        val userContent = buildCandidateUserContent(reading, context)
        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = CANDIDATE_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userContent),
            ),
            reasoningEffort = REASONING_EFFORT,
            responseFormat = buildCandidateResponseFormat(),
        )
        val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val completion = response.body<ChatCompletionResponse>()

        return completion.choices.firstOrNull()?.message?.content.orEmpty()
    }

    private fun buildCandidateUserContent(
        reading: String,
        context: String,
    ): String {
        if (context.isBlank()) {
            return "読み: $reading"
        }

        return "文脈: $context\n読み: $reading"
    }

    private fun buildCandidateResponseFormat(): JsonObject {
        val jsonSchema = buildJsonObject {
            put("name", "word_candidates")
            put("strict", true)
            put("schema", buildCandidateSchema())
        }

        return buildJsonObject {
            put("type", "json_schema")
            put("json_schema", jsonSchema)
        }
    }

    private fun buildCandidateSchema(): JsonObject {
        val candidatesProperty = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
        }
        val properties = buildJsonObject {
            put("candidates", candidatesProperty)
        }

        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray(listOf(JsonPrimitive("candidates"))))
            put("additionalProperties", false)
        }
    }

    private companion object {
        /** gpt-5 系の推論量。IME 変換に推論は不要なため最小化してレイテンシを抑える。 */
        const val REASONING_EFFORT = "minimal"

        /** 変換結果のみを出力させ、余計な説明・引用符を抑制する system prompt。 */
        const val SYSTEM_PROMPT =
            "あなたは日本語IMEのかな漢字変換エンジンです。" +
                "入力された読み（ひらがな・英字混じり）を最も自然な漢字かな交じり文へ変換し、" +
                "変換結果の文字列のみを出力してください。説明・引用符・前置きは出力しないこと。" +
                "英単語や記号は変換せずそのまま保持してください。"

        /** 選択文節の読みに対する同音異義語・別変換候補を、文脈に沿って JSON で列挙させる system prompt。 */
        const val CANDIDATE_SYSTEM_PROMPT =
            "あなたは日本語IMEの単語候補生成エンジンです。" +
                "与えられた読み（ひらがな）に対する変換候補を、与えられた文脈に最も自然に合う順で複数挙げてください。" +
                "同音異義語・漢字表記・ひらがな・カタカナを含めてよいですが、読みに対応しないものは含めないこと。" +
                "出力は {\"candidates\":[...]} という JSON のみとし、説明・前置きは出力しないこと。"
    }
}

/**
 * 既定の [ConversionProvider]。BuildKonfig（local.properties 由来）の設定で OpenAI 互換 API を呼ぶ実装を返す。
 */
internal fun defaultConversionProvider(): ConversionProvider {
    return OpenAiConversionProvider(OpenAiConfig.fromBuildKonfig(), createOpenAiHttpClient())
}

/**
 * OpenAI 互換 API 呼び出し用の [HttpClient] を生成する。engine は各 target の既定（Darwin / OkHttp）を使う。
 */
internal fun createOpenAiHttpClient(): HttpClient {
    return HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }
}

/** chat completions リクエストのタイムアウト（ミリ秒）。 */
private const val REQUEST_TIMEOUT_MILLIS = 15_000L

/**
 * chat completions API のリクエストボディ。互換性のため最小限のフィールドのみを送る。
 *
 * [reasoningEffort] は gpt-5 系の推論量で、null のときは送出しない（互換エンドポイント向け）。
 * IME 変換は推論不要なので `"minimal"` を指定し、隠れ reasoning token によるレイテンシを潰す。
 * [responseFormat] は call2（候補生成）でのみ付与する Structured Outputs 指定で、null のときは送出しない。
 * call1（全文変換）は plain text のままにするため null とする。
 */
@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    @SerialName("response_format")
    val responseFormat: JsonObject? = null,
)

/**
 * chat completions のメッセージ1件。
 */
@Serializable
internal data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * chat completions API のレスポンスボディ。必要な choices のみを読む。
 */
@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice>,
)

/**
 * chat completions レスポンスの候補1件。
 */
@Serializable
internal data class ChatChoice(
    val message: ChatMessage,
)
