package me.matsumo.romaflow.core.morphology

/**
 * 入力読みに対する変換仮説を表す。
 *
 * 各仮説は [reading]（ひらがな）と、変換に要するタイピングコスト [typingCost] を持つ。
 * タイピングコストは無修正 path（入力どおりの読み）では 0、読み変更を伴う path では正の値を設定し、
 * cross-hypothesis 比較で literal/辞書読みとの cost 差を自然に表現する。
 *
 * A-2 では LLM の実呼び出しは行わず、deterministic な読み候補（または fake 候補）で検証する。
 * 実 LLM resolver は A-4 で実装する。
 */
internal data class ReadingHypothesis(
    /** この仮説の読み（ひらがな）。 */
    val reading: String,
    /**
     * タイピングコスト（非負整数）。
     *
     * 入力どおりの読み（無修正 path）は 0。LLM や候補選択による読み変更は固定ペナルティを設定する。
     * このコストを総コストに加算して cross-hypothesis 比較を行う。
     */
    val typingCost: Int,
)

/**
 * 複数の [ReadingHypothesis] を横断して最小コスト経路を選択する cross-hypothesis デコーダ。
 *
 * 各仮説を [ReadingLatticeDecoder.viterbi] でデコードし、デコードコスト + タイピングコストの
 * argmin で最良仮説と経路を決定する（直積でなく k-way merge）。
 * タイピングコスト 0 の無修正 path と提案読みが自然に競合する。
 *
 * 用途: 複数入力候補の横断比較。A-4 以降では LLM が生成した読み仮説を渡す。
 */
internal object CrossHypothesisDecoder {

    /**
     * cross-hypothesis 比較の結果。
     *
     * [hypothesis] が選ばれた仮説、[path] がそのデコード結果、[totalCost] が比較コスト
     * （デコードコスト + タイピングコスト）。
     */
    data class BestResult(
        /** 選ばれた仮説。 */
        val hypothesis: ReadingHypothesis,
        /** デコード結果の lexeme 列。 */
        val path: List<LexemeEntry>,
        /** 総コスト（デコードコスト + タイピングコスト）。 */
        val totalCost: Long,
    )

    /**
     * [hypotheses] を横断して最小コスト経路を選ぶ。
     *
     * 各仮説を Viterbi でデコードし、`デコードコスト + typingCost` の argmin を返す。
     * デコード経路が存在しない仮説は除外する。全仮説でデコード失敗の場合は null を返す。
     */
    fun selectBest(
        hypotheses: List<ReadingHypothesis>,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): BestResult? {
        var bestResult: BestResult? = null

        for (hypothesis in hypotheses) {
            val decodedPath = ReadingLatticeDecoder.viterbi(hypothesis.reading, lexicon, costProvider)

            if (decodedPath.isEmpty()) continue

            val decodeCost = computeViterbiCost(decodedPath, costProvider)
            val combined = decodeCost + hypothesis.typingCost.toLong()

            val currentBest = bestResult
            val isBetter = currentBest == null || combined < currentBest.totalCost

            if (isBetter) {
                bestResult = BestResult(
                    hypothesis = hypothesis,
                    path = decodedPath,
                    totalCost = combined,
                )
            }
        }

        return bestResult
    }

    /**
     * デコード経路の総コスト（BOS 連接 + 各語 wcost + 語間連接 + EOS 連接）を計算する。
     *
     * [viterbi] の返すパスは best path だが、そのコスト値は [LatticeNode.minCost] に保持される。
     * ここでは path の lexeme 列から独立に再計算する（cross-hypothesis 比較のため）。
     */
    private fun computeViterbiCost(
        path: List<LexemeEntry>,
        costProvider: ConnectionCostProvider,
    ): Long {
        if (path.isEmpty()) return Long.MAX_VALUE

        var prevRcAttr = 0
        var totalCost = 0L

        for (lexeme in path) {
            val connectionCost = costProvider.transitionCost(prevRcAttr, lexeme.lcAttr)

            totalCost += connectionCost.toLong() + lexeme.wcost.toLong()
            prevRcAttr = lexeme.rcAttr
        }

        val eosCost = costProvider.transitionCost(prevRcAttr, 0)
        totalCost += eosCost.toLong()

        return totalCost
    }

    /**
     * [hypotheses] を横断して各仮説の N-best 候補を列挙し、全体を cost 昇順で統合する。
     *
     * 各仮説で [ReadingLatticeDecoder.nBest] を実行し、`decodeCost + typingCost` を加えた
     * 統合スコアで全仮説の候補を並び替え、上位 [n] 件を返す。
     * 各エントリは (totalCost, hypothesis, lexemePath) のトリプルで構成される。
     */
    fun rankAllCandidates(
        hypotheses: List<ReadingHypothesis>,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
        n: Int,
    ): List<Triple<Long, ReadingHypothesis, List<LexemeEntry>>> {
        val allCandidates = mutableListOf<Triple<Long, ReadingHypothesis, List<LexemeEntry>>>()

        for (hypothesis in hypotheses) {
            val nBestPaths = ReadingLatticeDecoder.nBest(hypothesis.reading, lexicon, costProvider, n)

            for ((decodeCost, decodedPath) in nBestPaths) {
                val combined = decodeCost + hypothesis.typingCost.toLong()

                allCandidates.add(Triple(combined, hypothesis, decodedPath))
            }
        }

        return allCandidates
            .sortedBy { it.first }
            .take(n)
    }
}
