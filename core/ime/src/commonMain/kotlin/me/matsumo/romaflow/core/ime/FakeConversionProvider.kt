package me.matsumo.romaflow.core.ime

import kotlinx.serialization.json.Json
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult

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

    /**
     * 決定的な rerank スタブ。
     *
     * テスト・開発用。[RERANK_TABLE] に読みが存在する場合はその優先候補を候補リストから探して
     * index を返す。見つからない場合は 0（コスト最小の Viterbi 1位相当）を返す。
     * 候補が空の場合は -1（失敗）を返す。
     */
    override suspend fun rerank(request: RerankRequest): Int {
        if (request.candidates.isEmpty()) {
            return -1
        }

        val preferredSurface = RERANK_TABLE[request.reading]

        if (preferredSurface != null) {
            val preferredIndex = request.candidates.indexOf(preferredSurface)

            if (preferredIndex >= 0) {
                return preferredIndex
            }
        }

        return 0
    }

    /**
     * 決定的な factorized rerank スタブ。
     *
     * テスト・開発用。各 region の reading に対して [FACTORIZED_RERANK_TABLE] から優先 surface を引き、
     * その surface を持つ option の id を choices として返す。
     * 表にない読みは当該 region を未選択（choices に含めない）扱いとする。
     * region が空の場合は空 choices を返す。
     */
    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        if (request.regions.isEmpty()) {
            return FactorizedRerankResult(choices = emptyMap())
        }

        val choices = buildFactorizedChoices(request)

        return FactorizedRerankResult(choices = choices)
    }

    private fun buildFactorizedChoices(request: FactorizedRerankRequest): Map<String, String> {
        val result = mutableMapOf<String, String>()

        for (region in request.regions) {
            val preferredSurface = FACTORIZED_RERANK_TABLE[region.reading] ?: continue
            val matchedOption = region.options.firstOrNull { it.surface == preferredSurface } ?: continue

            result[region.id] = matchedOption.id
        }

        return result
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

        /**
         * rerank（call3）の決定的スタブ用変換表。
         *
         * 読みから優先すべき候補表層形を定義する。[CONVERSION_TABLE] と整合し、同音異義語文脈で
         * 正しい候補を選択できることをテストで確認するために使う。
         */
        val RERANK_TABLE = mapOf(
            "てんき" to "天気",
            "かんじ" to "漢字",
            "わたし" to "私",
            "にほんご" to "日本語",
            "とうきょう" to "東京",
            "へんかん" to "変換",
        )

        /**
         * factorized rerank（call3-factorized）の決定的スタブ用変換表。
         *
         * region の reading から優先すべき候補表層形を定義する。
         * 統合テストの回帰ケース「べんきょうしてせいかをあげた → 勉強して成果を上げた」で
         * せいか→成果 / あげ→上げ の選択を再現するために使う。
         * 「あげた」は IPADIC では「あげ(動詞連用形)＋た(助動詞)」に分割されるため、
         * 動詞 span の reading は「あげ」になる。複合語キー「あげた」も後方互換で残す。
         */
        val FACTORIZED_RERANK_TABLE = mapOf(
            "せいか" to "成果",
            "あげ" to "上げ",
            "あげた" to "上げた",
            "べんきょうして" to "勉強して",
            "てんき" to "天気",
            "かんじ" to "漢字",
            "わたし" to "私",
            "にほんご" to "日本語",
            "とうきょう" to "東京",
            "へんかん" to "変換",
        )
    }
}
