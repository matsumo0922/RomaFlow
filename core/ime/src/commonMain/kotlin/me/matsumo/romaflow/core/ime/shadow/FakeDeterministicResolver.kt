package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.SourceSpan

/**
 * 決定的な rule-based [ConversionResolver]（§8 strategy 0）。
 *
 * 実 API を必要とせず、[READING_TO_PREFERRED_SURFACE] の変換表に従って決定的に提案を返す。
 * [FakeConversionProvider][me.matsumo.romaflow.core.ime.FakeConversionProvider] の CONVERSION_TABLE に整合する。
 *
 * ## 動作
 * - reading が [READING_TO_PREFERRED_SURFACE] にある場合: [ResolutionProposal.ProposeJointCorrection] を返す。
 *   [preferredSurface] を格子上の完全経路探索ヒントとして渡す。実 LLM なしに §6 の検証フローを通せる。
 * - reading が変換表にある場合でも graph に既に正しい path が存在する場合: [ResolutionProposal.SelectExistingPath]。
 * - 上記以外: [ResolutionProposal.KeepCurrent]。
 *
 * ## ユニットテストの主役
 * このクラスは Fake と §6 検証フローのユニットテスト全体で使う。LLM を呼ばないため CI で常時実行可能。
 */
internal class FakeDeterministicResolver : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        // 変換表で preferredSurface を検索する
        val preferredSurface = lookupPreferredSurface(reading)

        if (preferredSurface == null) {
            return ResolutionProposal.KeepCurrent
        }

        // graph の既存 path に preferredSurface と完全一致するものがあれば SelectExistingPath
        val existingPathId = findMatchingPathId(state, preferredSurface)

        if (existingPathId != null) {
            return ResolutionProposal.SelectExistingPath(
                pathId = existingPathId,
                rationale = "fake: preferred surface '$preferredSurface' found in existing graph",
            )
        }

        // graph 上に存在しない場合は ProposeJointCorrection で格子再解決を促す
        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(fromAtomIndex = 0, toAtomIndex = reading.length),
            intendedReading = reading,
            preferredSurface = preferredSurface,
        )
    }

    /**
     * reading を正規化（trim）し、[READING_TO_PREFERRED_SURFACE] で完全一致する変換先を返す。
     *
     * 変換表は長い reading を先に評価するため、サブストリングマッチの誤爆を防ぐ。
     * 完全一致のみサポートする（部分マッチは ProposeJointCorrection で LLM に委ねる役割）。
     */
    private fun lookupPreferredSurface(reading: String): String? {
        return READING_TO_PREFERRED_SURFACE[reading]
    }

    /**
     * graph 上の N-best 経路に [preferredSurface] と表層形が完全一致するものを探す。
     *
     * 複数一致する場合は最小 rank（最小コスト）の [CompositionGraph.PathId] を返す。
     * 一致なしの場合は null を返す。
     */
    private fun findMatchingPathId(
        state: CompositionState,
        preferredSurface: String,
    ): CompositionGraph.PathId? {
        for (pathId in state.graph.allPathIds) {
            val path = state.graph.pathOrNull(pathId) ?: continue
            val surface = buildSurface(path)

            if (surface == preferredSurface) {
                return pathId
            }
        }

        return null
    }

    /**
     * lexeme 経路の surface を連結して表示文字列を作る。
     */
    private fun buildSurface(path: List<me.matsumo.romaflow.core.morphology.LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.surface)
        }

        return builder.toString()
    }

    private companion object {
        /**
         * かな読みから優先表層形への変換表。
         *
         * [me.matsumo.romaflow.core.ime.FakeConversionProvider] の CONVERSION_TABLE と整合する。
         * 長い reading を先に置くことで部分一致の誤爆を防ぐ。
         * タイポ訂正系（silent typo / 誤変換）を含め §8 戦略比較で差が出やすい例を追加。
         */
        val READING_TO_PREFERRED_SURFACE = linkedMapOf(
            "にほんご" to "日本語",
            "とうきょう" to "東京",
            "へんかん" to "変換",
            "かんじ" to "漢字",
            "わたし" to "私",
            "てんき" to "天気",
            // タイポ訂正系: silent typo（えいご → 英語, がっこう → 学校）
            "えいご" to "英語",
            "がっこう" to "学校",
            "しんぶん" to "新聞",
        )
    }
}
