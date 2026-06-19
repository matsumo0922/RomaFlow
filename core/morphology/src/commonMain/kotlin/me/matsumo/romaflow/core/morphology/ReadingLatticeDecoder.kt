package me.matsumo.romaflow.core.morphology

/**
 * 読み（ひらがな）から lexical lattice を構築し、Viterbi アルゴリズムで最小コスト経路を求める。
 *
 * A-spike の核となる実装。[ReadingLexicon] と [ConnectionCostProvider] を受け取り、
 * reading 座標の lexical lattice を構築し、単語コスト＋連接コストの最小化で
 * 最良経路・N-best 経路を求める。
 */
object ReadingLatticeDecoder {

    /** BOS/EOS の連接 ID。momiji CostManager の実装に倣い 0 を使う。 */
    private const val BOS_EOS_CONTEXT_ID = 0

    /** N-best 探索のデフォルト候補数。 */
    const val DEFAULT_N_BEST = 8

    /**
     * reading 座標の 1 ノード。Viterbi の DP 状態を保持する。
     */
    internal class LatticeNode(
        /** 読みの開始オフセット（inclusive）。 */
        val startOffset: Int,
        /** 読みの終端オフセット（exclusive）。 */
        val endOffset: Int,
        /** このノードに対応する lexeme。 */
        val lexeme: LexemeEntry,
        /** Viterbi 前向きパスで確定した累積最小コスト。初期値は未確定を示す Long.MAX_VALUE。 */
        var minCost: Long = Long.MAX_VALUE,
        /** 最小コスト経路上の前ノード（Viterbi backpointer）。BOS 起点の語は null。 */
        var bestPrev: LatticeNode? = null,
    )

    /**
     * Viterbi アルゴリズムで最小コスト経路を求め、[LexemeEntry] のリストとして返す。
     *
     * BOS から EOS まで連接コスト＋単語コストを最小化する経路を返す。
     * reading 全体をカバーする経路が存在しない場合は空リストを返す。
     */
    fun viterbi(
        reading: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): List<LexemeEntry> {
        val (beginNodes, endNodes) = buildNodes(reading, lexicon)

        runForwardPass(reading, beginNodes, endNodes, costProvider)

        return traceBackBestPath(reading, beginNodes)
    }

    /**
     * N-best 経路を cumulative cost 昇順で返す。
     *
     * 全完全経路を DFS で列挙し、コスト昇順で上位 [n] 件を返す。
     * 短い reading（7 文字程度）での利用を想定しており、長い reading では経路数が増える点に注意。
     */
    fun nBest(
        reading: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
        n: Int = DEFAULT_N_BEST,
    ): List<Pair<Long, List<LexemeEntry>>> {
        val (beginNodes, _) = buildNodes(reading, lexicon)

        return collectAllCompletePaths(reading.length, beginNodes, costProvider, n)
    }

    /**
     * reading 座標の beginNodes と endNodes を構築する。
     *
     * beginNodes[i] = reading[i..] から始まる [LatticeNode] のリスト。
     * endNodes[j] = reading[..j] で終わる [LatticeNode] のリスト。
     */
    private fun buildNodes(
        reading: String,
        lexicon: ReadingLexicon,
    ): Pair<Array<MutableList<LatticeNode>>, Array<MutableList<LatticeNode>>> {
        val beginNodes = Array<MutableList<LatticeNode>>(reading.length) { mutableListOf() }
        val endNodes = Array<MutableList<LatticeNode>>(reading.length + 1) { mutableListOf() }

        for (startIndex in reading.indices) {
            val matches = lexicon.commonPrefixSearch(reading, startIndex)

            for (match in matches) {
                val node = LatticeNode(
                    startOffset = startIndex,
                    endOffset = match.readingEndOffset,
                    lexeme = match.lexeme,
                )

                beginNodes[startIndex].add(node)
                endNodes[match.readingEndOffset].add(node)
            }
        }

        return beginNodes to endNodes
    }

    /**
     * Viterbi 前向きパスを実行する。
     *
     * BOS（contextId=0）を起点に各ノードの累積最小コストと前ノードを更新する。
     */
    private fun runForwardPass(
        reading: String,
        beginNodes: Array<MutableList<LatticeNode>>,
        endNodes: Array<MutableList<LatticeNode>>,
        costProvider: ConnectionCostProvider,
    ) {
        for (node in beginNodes[0]) {
            val connectionCost = costProvider.transitionCost(BOS_EOS_CONTEXT_ID, node.lexeme.lcAttr)

            node.minCost = connectionCost.toLong() + node.lexeme.wcost.toLong()
        }

        for (endPosition in 1..reading.length) {
            for (prevNode in endNodes[endPosition]) {
                if (prevNode.minCost == Long.MAX_VALUE) continue

                val nextCandidates = if (endPosition < reading.length) beginNodes[endPosition] else emptyList<LatticeNode>()

                for (nextNode in nextCandidates) {
                    val connectionCost = costProvider.transitionCost(
                        prevNode.lexeme.rcAttr,
                        nextNode.lexeme.lcAttr,
                    )
                    val totalCost = prevNode.minCost + connectionCost.toLong() + nextNode.lexeme.wcost.toLong()

                    if (totalCost < nextNode.minCost) {
                        nextNode.minCost = totalCost
                        nextNode.bestPrev = prevNode
                    }
                }
            }
        }
    }

    /**
     * Viterbi backpointer をたどり、最小コスト経路の [LexemeEntry] リストを返す。
     *
     * EOS に到達するノード（endOffset == reading.length）で最小コストのものから逆順にたどる。
     */
    private fun traceBackBestPath(
        reading: String,
        beginNodes: Array<MutableList<LatticeNode>>,
    ): List<LexemeEntry> {
        val endNodes = beginNodes.flatMap { it }.filter { node -> node.endOffset == reading.length }
        val reachableEndNodes = endNodes.filter { node -> node.minCost != Long.MAX_VALUE }

        if (reachableEndNodes.isEmpty()) return emptyList()

        val bestEndNode = reachableEndNodes.minByOrNull { it.minCost } ?: return emptyList()

        val path = mutableListOf<LexemeEntry>()
        var currentNode: LatticeNode? = bestEndNode

        while (currentNode != null) {
            path.add(currentNode.lexeme)
            currentNode = currentNode.bestPrev
        }

        return path.reversed()
    }

    /**
     * DFS で全完全経路を列挙し、コスト昇順で上位 [maxPaths] 件を返す。
     *
     * [beginNodes][0] から始まり [readingLength] に到達する全経路を探索する。
     * 経路の爆発を防ぐため、収集数が [maxPaths] * 20 を超えた時点で探索を打ち切る。
     */
    private fun collectAllCompletePaths(
        readingLength: Int,
        beginNodes: Array<MutableList<LatticeNode>>,
        costProvider: ConnectionCostProvider,
        maxPaths: Int,
    ): List<Pair<Long, List<LexemeEntry>>> {
        val results = mutableListOf<Pair<Long, List<LexemeEntry>>>()
        val collectLimit = maxPaths * 20

        data class Frame(
            val offset: Int,
            val path: List<LexemeEntry>,
            val cost: Long,
            val prevRcAttr: Int,
        )

        val stack = ArrayDeque<Frame>()

        stack.addLast(Frame(offset = 0, path = emptyList(), cost = 0L, prevRcAttr = BOS_EOS_CONTEXT_ID))

        while (stack.isNotEmpty() && results.size < collectLimit) {
            val frame = stack.removeLast()

            if (frame.offset == readingLength) {
                results.add(frame.cost to frame.path)
                continue
            }

            if (frame.offset >= beginNodes.size) continue

            for (node in beginNodes[frame.offset]) {
                val connectionCost = costProvider.transitionCost(frame.prevRcAttr, node.lexeme.lcAttr)
                val newCost = frame.cost + connectionCost.toLong() + node.lexeme.wcost.toLong()

                stack.addLast(
                    Frame(
                        offset = node.endOffset,
                        path = frame.path + node.lexeme,
                        cost = newCost,
                        prevRcAttr = node.lexeme.rcAttr,
                    ),
                )
            }
        }

        // 注意: ここでの sortedBy/take は「収集済み経路内のベスト」であり、グローバル N-best を保証しない。
        // 経路爆発防止のため collectLimit で DFS を打ち切るため、長い reading ではグローバル最良経路を
        // 取りこぼす可能性がある。長文対応・真のグローバル N-best は A-2 で改善予定。
        return results.sortedBy { it.first }.take(maxPaths)
    }
}
