package me.matsumo.romaflow.core.morphology

/**
 * 読み（ひらがな）から lexical lattice を構築し、Viterbi アルゴリズムで最小コスト経路を求める。
 *
 * [ReadingLexicon] と [ConnectionCostProvider] を受け取り、reading 座標の lexical lattice を
 * 構築し、単語コスト＋連接コストの最小化で最良経路・真のグローバル N-best 経路を求める。
 *
 * N-best は A-2 で DFS 打ち切り近似から前向き best-first 探索（Dijkstra-like）に変更した。
 * 優先度付きキューで常に最小コストの部分経路を優先展開するため、グローバル N-best を保証する。
 * EOS 連接コストを含む総コストで順位付けし、[viterbi] の rank-0 と一致する。
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
     * EOS 連接コスト（最終語の rcAttr → BOS_EOS_CONTEXT_ID）も含めた総コストで比較する。
     * reading 全体をカバーする経路が存在しない場合は空リストを返す。
     */
    fun viterbi(
        reading: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): List<LexemeEntry> {
        val (beginNodes, endNodes) = buildNodes(reading, lexicon)

        runForwardPass(reading, beginNodes, endNodes, costProvider)

        return traceBackBestPath(reading, beginNodes, costProvider)
    }

    /**
     * 真のグローバル N-best 経路を cumulative cost 昇順で返す。
     *
     * 前向き best-first 探索（Dijkstra-like / A*）で EOS までの最小コスト完全経路を上位 [n] 件
     * 列挙する。優先度付きキュー（binary heap 相当）で常に最小コスト部分経路を展開するため、
     * DFS 打ち切り近似（A-spike 実装）と異なり**グローバル N-best を保証する**。
     * EOS 連接コスト（最終語 rcAttr → BOS_EOS_CONTEXT_ID）を総コストに含め、二重加算しない。
     */
    fun nBest(
        reading: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
        n: Int = DEFAULT_N_BEST,
    ): List<Pair<Long, List<LexemeEntry>>> {
        val (beginNodes, _) = buildNodes(reading, lexicon)

        return collectNBestPaths(reading.length, beginNodes, costProvider, n)
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
     * EOS に到達するノード（endOffset == reading.length）について、
     * `minCost + EOS 連接コスト` で比較して最小のものから逆順にたどる。
     * EOS コストは比較にのみ使い、[LatticeNode.minCost] 自体は書き換えない（二重加算防止）。
     */
    private fun traceBackBestPath(
        reading: String,
        beginNodes: Array<MutableList<LatticeNode>>,
        costProvider: ConnectionCostProvider,
    ): List<LexemeEntry> {
        val allNodes = beginNodes.flatMap { it }
        val reachableEndNodes = allNodes.filter { node ->
            node.endOffset == reading.length && node.minCost != Long.MAX_VALUE
        }

        if (reachableEndNodes.isEmpty()) return emptyList()

        val bestEndNode = reachableEndNodes.minByOrNull { node ->
            val eosCost = costProvider.transitionCost(node.lexeme.rcAttr, BOS_EOS_CONTEXT_ID)
            node.minCost + eosCost.toLong()
        } ?: return emptyList()

        val path = mutableListOf<LexemeEntry>()
        var currentNode: LatticeNode? = bestEndNode

        while (currentNode != null) {
            path.add(currentNode.lexeme)
            currentNode = currentNode.bestPrev
        }

        return path.reversed()
    }

    /**
     * 前向き best-first 探索で真のグローバル N-best を列挙する。
     *
     * BOS から出発し、優先度付きキュー（min-heap 相当）で常に最小コストの部分経路を展開する。
     * EOS 到達時はすぐに記録せず、EOS コストを加算した総コストでキューに再投入する。
     * これにより EOS コストの大小が経路間で逆転するケースでも正しい順位が保証される。
     *
     * アルゴリズムの性質:
     * - 完全経路を初めて pop したときは、そのコストは真の最小（グローバル N-best 保証）。
     * - キューは [MutableList] + `minByOrNull` で実装。N ≤ 16 の実用条件では十分。
     *
     * EOS フラグ付き SearchState をキューに再投入することで、
     * EOS コスト込みの総コストが他の部分経路と正しく比較される。
     */
    private fun collectNBestPaths(
        readingLength: Int,
        beginNodes: Array<MutableList<LatticeNode>>,
        costProvider: ConnectionCostProvider,
        maxPaths: Int,
    ): List<Pair<Long, List<LexemeEntry>>> {
        /**
         * キューに積む探索状態。
         *
         * [isComplete] が true の場合、[partialCost] は EOS コストを含む総コストを表し、
         * キューから pop されたとき即座に結果として記録される。
         */
        data class SearchState(
            /** 累積コスト。[isComplete] が false なら部分コスト、true なら EOS 込み総コスト。 */
            val partialCost: Long,
            /** 現在の reading オフセット（[isComplete] == true のときは readingLength）。 */
            val offset: Int,
            /** BOS から現位置までのパス（正順）。 */
            val path: List<LexemeEntry>,
            /** 直前語の rcAttr（次の連接コスト計算に使用）。[isComplete] のとき不要だが保持する。 */
            val prevRcAttr: Int,
            /** EOS コストを加算済みの完全経路かどうか。 */
            val isComplete: Boolean,
        )

        val results = mutableListOf<Pair<Long, List<LexemeEntry>>>()
        val openQueue = mutableListOf<SearchState>()

        openQueue.add(
            SearchState(
                partialCost = 0L,
                offset = 0,
                path = emptyList(),
                prevRcAttr = BOS_EOS_CONTEXT_ID,
                isComplete = false,
            ),
        )

        while (openQueue.isNotEmpty() && results.size < maxPaths) {
            // 最小コスト状態を取り出す（min-heap の代用）
            val minIndex = openQueue.indices.minByOrNull { openQueue[it].partialCost } ?: break
            val current = openQueue.removeAt(minIndex)

            if (current.isComplete) {
                // EOS 済みの完全経路を記録（このコストはグローバル最小が保証されている）
                results.add(current.partialCost to current.path)
                continue
            }

            if (current.offset == readingLength) {
                // EOS に到達: EOS コストを加算して完全状態としてキューに再投入
                val eosCost = costProvider.transitionCost(current.prevRcAttr, BOS_EOS_CONTEXT_ID)
                val totalCost = current.partialCost + eosCost.toLong()

                openQueue.add(
                    SearchState(
                        partialCost = totalCost,
                        offset = readingLength,
                        path = current.path,
                        prevRcAttr = current.prevRcAttr,
                        isComplete = true,
                    ),
                )
                continue
            }

            if (current.offset >= beginNodes.size) continue

            for (node in beginNodes[current.offset]) {
                val connectionCost = costProvider.transitionCost(current.prevRcAttr, node.lexeme.lcAttr)
                val newCost = current.partialCost + connectionCost.toLong() + node.lexeme.wcost.toLong()

                openQueue.add(
                    SearchState(
                        partialCost = newCost,
                        offset = node.endOffset,
                        path = current.path + node.lexeme,
                        prevRcAttr = node.lexeme.rcAttr,
                        isComplete = false,
                    ),
                )
            }
        }

        return results
    }
}
