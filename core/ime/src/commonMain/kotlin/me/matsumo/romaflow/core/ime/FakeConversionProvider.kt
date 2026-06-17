package me.matsumo.romaflow.core.ime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 決定的な rule-based の [ConversionProvider] スタブ。
 *
 * AI を使わず固定の変換表で既知のかな語を漢字へ置換し、表にない部分はかなのまま残す。候補列挙（call2）も
 * 固定の候補表から決定的に返す。テスト・開発用で、同じ入力には常に同じ結果を返す。
 */
internal class FakeConversionProvider : ConversionProvider {

    override suspend fun convert(request: ConversionRequest): String {
        var converted = request.readingInput

        for ((reading, kanji) in CONVERSION_TABLE) {
            converted = converted.replace(reading, kanji)
        }

        return converted
    }

    override suspend fun candidates(request: WordCandidateRequest): String {
        val reading = request.reading

        if (reading.isBlank()) {
            return ""
        }

        val candidates = CANDIDATE_TABLE[reading] ?: return ""

        return Json.encodeToString(WordCandidatePayload(candidates))
    }

    private companion object {
        /**
         * かなの読みと変換後の漢字の対応表。
         *
         * 長い読みを先に置換して部分一致による取りこぼしを避けるため、挿入順を保つ [LinkedHashMap] を使う。
         */
        val CONVERSION_TABLE = linkedMapOf(
            "にほんご" to "日本語",
            "とうきょう" to "東京",
            "へんかん" to "変換",
            "かんじ" to "漢字",
            "わたし" to "私",
            "てんき" to "天気",
        )

        /**
         * 読みと候補列の対応表。
         *
         * call2 の決定的スタブ用。[CONVERSION_TABLE] と整合する漢字を先頭に、同音異義語・ひらがな・カタカナを
         * 並べる。表にない読みは候補なし（空文字）とする。
         */
        val CANDIDATE_TABLE = mapOf(
            "てんき" to listOf("天気", "転機", "てんき", "テンキ"),
            "かんじ" to listOf("漢字", "幹事", "かんじ", "カンジ"),
            "わたし" to listOf("私", "わたし", "ワタシ"),
        )
    }
}

/**
 * call2 の出力 JSON `{"candidates":[...]}` に対応する payload。
 *
 * [FakeConversionProvider] が決定的に生成する候補列を [kotlinx.serialization] でシリアライズするために使う。
 */
@Serializable
private data class WordCandidatePayload(
    @SerialName("candidates")
    val candidates: List<String>,
)
