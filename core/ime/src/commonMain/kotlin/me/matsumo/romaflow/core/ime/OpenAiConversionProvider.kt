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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.matsumo.romaflow.core.ime.shadow.DecisionRegion
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult

/**
 * OpenAI 互換 chat completions API でかな読みを漢字交じり文へ変換する [ConversionProvider]。
 *
 * リクエストは互換性最優先で最小限（model と messages のみ）にし、OpenAI・Gemini 互換・ローカル LLM で
 * 同じ経路が通るようにする。失敗時（API key 未設定・通信エラー・タイムアウト等）は空文字を返し、
 * 呼び出し側で「変換せず据え置き」として扱う。
 *
 * [convert] は call1（全文かな漢字変換）で、[ConversionRequest.readingInput] を変換する。lock 済みの prefix が
 * あるときは [ConversionRequest.prefixContext] を前方文脈として渡し、tail（未確定の末尾）だけを変換させる
 * （prefix-commit 再変換）。[candidates] は call2（単語候補）で、選択文節の読みと文脈から Structured Outputs で
 * 同音異義語・別変換候補を得る。[convert] 側は「全文を LLM に丸ごと変換させる」暫定方式のままで、応答崩れ
 * （前置き・引用符・markdown・hallucination）への防御は意図的に最小限にとどめている。
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

        val result = runCatching { requestConversion(kana, request.prefixContext) }
        val converted = result.getOrNull()

        if (converted == null) {
            Napier.w("OpenAI conversion failed", result.exceptionOrNull())

            return ""
        }

        return stripEchoedPrefix(converted, request.prefixContext).trim()
    }

    // LLM が前方文脈をそのまま echo して返した厳密一致ケースの保険として、結果が prefixContext で
    // 始まるなら先頭の prefix を 1 回だけ剥がす。prefixContext を一部だけ含む部分 echo は
    // ここでは防げないため、tail-only を促す再変換 system prompt 側で抑制する前提とする。
    private fun stripEchoedPrefix(
        converted: String,
        prefixContext: String,
    ): String {
        if (prefixContext.isBlank()) {
            return converted
        }

        return converted.trim().removePrefix(prefixContext)
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

    override suspend fun rerank(request: RerankRequest): Int {
        val reading = request.reading
        val candidates = request.candidates

        if (reading.isBlank() || candidates.isEmpty() || config.apiKey.isBlank()) {
            return -1
        }

        val result = runCatching { requestRerank(reading, request.prefixContext, candidates) }
        val rerankIndex = result.getOrNull()

        if (rerankIndex == null) {
            Napier.w("OpenAI rerank failed", result.exceptionOrNull())

            return -1
        }

        return rerankIndex
    }

    override suspend fun proposeFullTailSurface(reading: String, prefixContext: String): String {
        if (reading.isBlank() || config.apiKey.isBlank()) {
            return ""
        }

        val result = runCatching { requestConversion(reading, prefixContext) }
        val converted = result.getOrNull()

        if (converted == null) {
            Napier.w("OpenAI proposeFullTailSurface failed", result.exceptionOrNull())

            return ""
        }

        // convert() と同じく echo された prefix を除去する。reconversion prompt は prefix+tail を返し得るが、
        // resolver は tailReading に対して検証するため、prefix が残ると検証に落ちて baseline へ倒れる。
        return stripEchoedPrefix(converted, prefixContext).trim()
    }

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        val regions = request.regions

        if (regions.isEmpty() || config.apiKey.isBlank()) {
            return FactorizedRerankResult(decisions = emptyMap())
        }

        return runCatching { requestFactorizedRerank(request.template, request.prefixContext, regions) }
            .getOrElse {
                Napier.w("OpenAI factorized rerank failed", it)
                FactorizedRerankResult(decisions = emptyMap())
            }
    }

    private suspend fun requestFactorizedRerank(
        template: String,
        prefixContext: String,
        regions: List<DecisionRegion>,
    ): FactorizedRerankResult {
        val userContent = buildFactorizedRerankUserContent(template, prefixContext, regions)
        val rerankRequest = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = FACTORIZED_RERANK_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userContent),
            ),
            reasoningEffort = REASONING_EFFORT,
            responseFormat = buildFactorizedRerankResponseFormat(),
        )
        val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(rerankRequest)
        }
        val completion = response.body<ChatCompletionResponse>()
        val content = completion.choices.firstOrNull()?.message?.content.orEmpty()

        return parseFactorizedRerankResult(content, regions)
    }

    // テンプレート + 全 region の参考候補（hint）を LLM に提示するユーザーコンテンツを構築する。
    // hint は先頭 min(4, options.size) 件のみ（参考と明記）。
    private fun buildFactorizedRerankUserContent(
        template: String,
        prefixContext: String,
        regions: List<DecisionRegion>,
    ): String {
        val builder = StringBuilder()

        if (prefixContext.isNotBlank()) {
            builder.appendLine("前方文脈: $prefixContext")
        }

        builder.appendLine("テンプレート: $template")

        for (region in regions) {
            val hintOptions = region.options.take(4)
            val hintSurfaces = hintOptions.joinToString(", ") { it.surface }

            builder.appendLine("region ${region.id} (読み: ${region.reading}) 参考候補: $hintSurfaces")
        }

        builder.appendLine("※参考候補は一例。読みが一致すれば候補外の表記を提案してよい。")

        return builder.toString().trimEnd()
    }

    // Structured Outputs で {"decisions":[{"region_id": string, "surface": string}]} を強制する
    // json_schema を構築する（strict=true）。surface は enum 制約なし（自由表層）。
    private fun buildFactorizedRerankResponseFormat(): JsonObject {
        val regionIdProperty = buildJsonObject { put("type", "string") }
        val surfaceProperty = buildJsonObject { put("type", "string") }
        val decisionProperties = buildJsonObject {
            put("region_id", regionIdProperty)
            put("surface", surfaceProperty)
        }
        val decisionSchema = buildJsonObject {
            put("type", "object")
            put("properties", decisionProperties)
            put("required", JsonArray(listOf(JsonPrimitive("region_id"), JsonPrimitive("surface"))))
            put("additionalProperties", false)
        }
        val decisionsProperty = buildJsonObject {
            put("type", "array")
            put("items", decisionSchema)
        }
        val properties = buildJsonObject { put("decisions", decisionsProperty) }
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray(listOf(JsonPrimitive("decisions"))))
            put("additionalProperties", false)
        }
        val jsonSchema = buildJsonObject {
            put("name", "factorized_surface_proposal")
            put("strict", true)
            put("schema", schema)
        }

        return buildJsonObject {
            put("type", "json_schema")
            put("json_schema", jsonSchema)
        }
    }

    // LLM の返答 JSON をパースして FactorizedRerankResult に変換する。
    // parse 失敗・未知 region_id・blank surface は当該 region を未採用（decisions に含めない）扱いとする。
    private fun parseFactorizedRerankResult(content: String, regions: List<DecisionRegion>): FactorizedRerankResult {
        if (content.isBlank()) {
            return FactorizedRerankResult(decisions = emptyMap())
        }

        val validRegionIds = regions.mapTo(mutableSetOf()) { it.id }

        val decisions = runCatching {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(content).jsonObject["decisions"]?.jsonArray
                ?: return FactorizedRerankResult(decisions = emptyMap())

            buildDecisionsMap(arr, validRegionIds)
        }.getOrElse { return FactorizedRerankResult(decisions = emptyMap()) }

        return FactorizedRerankResult(decisions = decisions)
    }

    private fun buildDecisionsMap(
        decisionsArray: JsonArray,
        validRegionIds: Set<String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        for (element in decisionsArray) {
            val obj = element.jsonObject
            val regionId = obj["region_id"]?.jsonPrimitive?.content ?: continue
            val surface = obj["surface"]?.jsonPrimitive?.content ?: continue

            val isValidRegion = regionId in validRegionIds

            if (isValidRegion && surface.isNotBlank()) {
                result[regionId] = surface
            }
        }

        return result
    }

    private suspend fun requestConversion(
        kana: String,
        prefixContext: String,
    ): String {
        val userContent = buildConversionUserContent(kana, prefixContext)
        val systemPrompt = selectConversionSystemPrompt(prefixContext)
        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userContent),
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

    // lock 再変換（prefixContext 非空）では前方文脈の echo を禁じる専用 system prompt を使い、
    // tail だけを変換させる。lock 無しの初回変換（prefixContext 空）は従来の汎用 prompt のまま。
    private fun selectConversionSystemPrompt(prefixContext: String): String {
        if (prefixContext.isBlank()) {
            return SYSTEM_PROMPT
        }

        return RECONVERSION_SYSTEM_PROMPT
    }

    // lock 再変換では先頭の確定済み文節を前方文脈として渡し、tail の読みだけを変換させる。
    // prefixContext が空（lock 無しの初回変換）なら従来どおり読みだけを送り、プロンプトを変えない。
    private fun buildConversionUserContent(
        kana: String,
        prefixContext: String,
    ): String {
        if (prefixContext.isBlank()) {
            return kana
        }

        return "前方文脈: $prefixContext\n続きの読み: $kana"
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

    private suspend fun requestRerank(
        reading: String,
        prefixContext: String,
        candidates: List<String>,
    ): Int {
        val userContent = buildRerankUserContent(reading, prefixContext, candidates)
        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = RERANK_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userContent),
            ),
            reasoningEffort = REASONING_EFFORT,
            responseFormat = buildRerankResponseFormat(),
        )
        val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val completion = response.body<ChatCompletionResponse>()
        val content = completion.choices.firstOrNull()?.message?.content.orEmpty()

        return parseRerankIndex(content, candidates.size)
    }

    // 番号付き候補一覧と読み・前方文脈を組み合わせたユーザーコンテンツを構築する。
    private fun buildRerankUserContent(
        reading: String,
        prefixContext: String,
        candidates: List<String>,
    ): String {
        val builder = StringBuilder()

        if (prefixContext.isNotBlank()) {
            builder.appendLine("前方文脈: $prefixContext")
        }

        builder.appendLine("読み: $reading")
        builder.appendLine("候補:")

        for ((candidateIndex, candidate) in candidates.withIndex()) {
            builder.appendLine("$candidateIndex: $candidate")
        }

        return builder.toString().trimEnd()
    }

    // Structured Outputs の JSON レスポンス `{"index": <int>}` をパースし、
    // 範囲外・parse 失敗時は -1 を返す。
    private fun parseRerankIndex(content: String, candidatesSize: Int): Int {
        if (content.isBlank()) {
            return -1
        }

        val parsedIndex = runCatching {
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(content)
            val indexElement = obj.jsonObject["index"] ?: return -1

            indexElement.jsonPrimitive.int
        }.getOrElse { return -1 }

        val isInRange = parsedIndex in 0 until candidatesSize

        return if (isInRange) parsedIndex else -1
    }

    private fun buildRerankResponseFormat(): JsonObject {
        val jsonSchema = buildJsonObject {
            put("name", "rerank_selection")
            put("strict", true)
            put("schema", buildRerankSchema())
        }

        return buildJsonObject {
            put("type", "json_schema")
            put("json_schema", jsonSchema)
        }
    }

    private fun buildRerankSchema(): JsonObject {
        val indexProperty = buildJsonObject {
            put("type", "integer")
        }
        val properties = buildJsonObject {
            put("index", indexProperty)
        }

        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray(listOf(JsonPrimitive("index"))))
            put("additionalProperties", false)
        }
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

        /**
         * lock 再変換専用の system prompt。前方文脈は確定済みの文脈情報であり、出力へ含めてはいけない。
         * tail（続きの読み）の変換結果のみを返させ、二重化（前方文脈の再掲）を防ぐ。1-shot 例で tail-only をアンカーする。
         */
        const val RECONVERSION_SYSTEM_PROMPT =
            "あなたは日本語IMEのかな漢字変換エンジンです。" +
                "前方文脈は既に確定済みの文脈情報であり、絶対に出力へ含めないでください。" +
                "出力は『続きの読み』を最も自然な漢字かな交じり文へ変換した文字列のみとし、" +
                "説明・引用符・前置き・前方文脈の再掲を一切しないこと。" +
                "英単語や記号は変換せずそのまま保持してください。" +
                "例: 前方文脈『私は』続きの読み『がっこうにいく』→ 出力『学校に行く』（『私は』は出力しない）。"

        /**
         * rerank（候補選択）専用の system prompt。
         *
         * 格子から導出した番号付き候補一覧を提示し、前方文脈に最も自然な候補の番号を1つ選ばせる。
         * 変換文字列を「生成」させず「index で選択」させることで構造的に捏造を不可にする。
         */
        const val RERANK_SYSTEM_PROMPT =
            "あなたは日本語IMEの変換候補選択エンジンです。" +
                "与えられた読みと候補リストから、前方文脈（あれば）に最も自然に合う候補の番号（index）を1つ選んでください。" +
                "必ず {\"index\": <number>} の JSON のみを出力し、説明・前置きは出力しないこと。" +
                "候補リストにない文字列を出力したり、新しい変換を生成したりしてはいけません。" +
                "候補リストには「変換しない（ひらがなのまま保持）」の選択肢も含まれる場合があります。その場合もその index を選べます。"

        /** 選択文節の読みに対する同音異義語・別変換候補を、文脈に沿って JSON で列挙させる system prompt。 */
        const val CANDIDATE_SYSTEM_PROMPT =
            "あなたは日本語IMEの単語候補生成エンジンです。" +
                "与えられた読み（ひらがな）に対する変換候補を、与えられた文脈に最も自然に合う順で複数挙げてください。" +
                "同音異義語・漢字表記・ひらがな・カタカナを含めてよいですが、読みに対応しないものは含めないこと。" +
                "出力は {\"candidates\":[...]} という JSON のみとし、説明・前置きは出力しないこと。"

        /**
         * factorized rerank（surface 提案・region 分割）専用の system prompt。
         *
         * テンプレート全体と各 region の参考候補（hint）を提示し、region ごとに最も自然な表記を提案させる。
         * closed-set index 選択から open surface 提案へ転換（PR-A）。
         */
        const val FACTORIZED_RERANK_SYSTEM_PROMPT =
            "あなたは日本語IMEの変換エンジンです。テンプレート全体（前後の確定文字列）の意味を踏まえ、各 region について" +
                "最も自然な現代日本語の表記を提案してください。必ず {\"decisions\":[{\"region_id\":\"r0\",\"surface\":\"...\"}]} の" +
                "JSON のみを出力し、説明・前置きは出力しないこと。\n" +
                "- 文全体の意味から、現代日本語で通常使われる表記を選ぶ\n" +
                "- 人名・固有名詞・専門用語・旧字体は、文脈上必要な場合だけ使う\n" +
                "- 助詞・助動詞・補助動詞など、かなが自然な箇所はかなを保つ\n" +
                "- 迷う場合は現在の表記（候補リストの先頭）を維持する\n" +
                "- 全 region を相互に整合するよう同時に決める\n" +
                "- surface は読みが region の読みと一致する表記にすること（読みを変えない）"
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
 * [responseFormat] は Structured Outputs（json_schema, strict）指定で、null のときは送出しない。
 * call2（候補生成）・flat rerank（index 選択）・factorized rerank（choices 選択）で付与し、
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
