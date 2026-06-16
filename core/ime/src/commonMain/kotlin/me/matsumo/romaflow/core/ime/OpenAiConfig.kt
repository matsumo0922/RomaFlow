package me.matsumo.romaflow.core.ime

/**
 * OpenAI 互換 chat completions API への接続設定。
 *
 * [baseUrl] を差し替えることで OpenAI 本家・Gemini の OpenAI 互換エンドポイント・ローカル LLM
 * （Ollama / LM Studio 等）を同じクライアントで扱える。slice1 では [fromBuildKonfig] で
 * local.properties 由来の値を読み込み、設定 UI による実行時切替は後続スライスで対応する。
 */
internal data class OpenAiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
) {
    companion object {
        /** BuildKonfig（local.properties 由来）から設定を構築する。 */
        fun fromBuildKonfig(): OpenAiConfig {
            return OpenAiConfig(
                apiKey = BuildKonfig.OPENAI_API_KEY,
                baseUrl = BuildKonfig.OPENAI_BASE_URL,
                model = BuildKonfig.OPENAI_MODEL,
            )
        }
    }
}
