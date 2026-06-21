package me.matsumo.romaflow.core.morphology

/**
 * 格子上の 1 アーク（lexeme が覆う span）を一意に識別するキー。
 *
 * [startOffset] と [endOffset] は tail reading（ひらがな）の mora 座標（exclusive end）。
 * [surface] は対応する lexeme の表層形。
 * 同一 surface が複数の [ReadingLatticeDecoder] 内部 node を通じて格子に現れた場合、
 * [ReadingLatticeDecoder.arcMarginalCosts] は最小コストを採用する。
 */
data class ArcKey(
    /** アーク開始 mora offset（inclusive）。 */
    val startOffset: Int,
    /** アーク終了 mora offset（exclusive）。 */
    val endOffset: Int,
    /** 表層形。 */
    val surface: String,
)

/**
 * 読み（ひらがな）から lexical lattice を構築し、Viterbi アルゴリズムで最小コスト経路を求める。
 *
 * [ReadingLexicon] と [ConnectionCostProvider] を受け取り、reading 座標の lexical lattice を
 * 構築し、単語コスト＋連接コストの最小化で最良経路・真のグローバル N-best 経路を求める。
 *
 * ## N-best アルゴリズム（A-2 改）
 *
 * 連接コストは [io.github.tokuhirom.momiji.core.matrix.Matrix.find] が返す [Short] を符号付きの
 * まま使うため**負になり得る**（例: BOS→BOS = -434）。Dijkstra 型の単純前向き best-first は
 * 負コストの存在下でグローバル N-best を保証しない（部分コストが小さくても suffix が大きく
 * 完全コストが上位でない経路を早期確定してしまう）。
 *
 * 修正アルゴリズム: **後ろ向き DP + 前向き A***
 * 1. 後ろ向き DP（[computeMinSuffixCost]）: DAG 上で各ノードの「end から EOS までの最小コスト
 *    （EOS 連接コスト込み）」を end offset の降順に厳密に求める。DAG の辺は前方向のみのため
 *    トポロジカル順（offset 降順）で解けば負コストでも厳密な最小値が得られる。
 * 2. 前向き A*（[collectNBestPaths]）: 優先度を `f = g（prefix 実コスト）+ h（最小 suffix cost）`
 *    とする。h は**厳密な下界**（DAG の最小 suffix）であるため admissible かつ consistent。
 *    これにより complete state を pop した順が真のグローバル昇順になる。
 *
 * EOS 連接コストは suffix cost に含め、complete state の f 値がそのまま総コストになる。
 * 二重加算しない（既存の [viterbi] / EOS regression テストを壊さない）。
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
     * 後ろ向き DP で各ノードの最小 suffix cost を求め、A* の厳密下界ヒューリスティックとして
     * 使うことで、連接コストが負の場合でもグローバル N-best を保証する。
     * EOS 連接コスト（最終語 rcAttr → BOS_EOS_CONTEXT_ID）を総コストに含め、二重加算しない。
     */
    fun nBest(
        reading: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
        n: Int = DEFAULT_N_BEST,
    ): List<Pair<Long, List<LexemeEntry>>> {
        val (beginNodes, endNodes) = buildNodes(reading, lexicon)
        val minSuffixCost = computeMinSuffixCost(reading.length, beginNodes, endNodes, costProvider)

        return collectNBestPaths(reading.length, beginNodes, costProvider, minSuffixCost, n)
    }

    /**
     * tail reading の格子で、各アークを通る最小コスト全文 path のコストを返す。
     *
     * ## アルゴリズム
     * 前向き DP（[runForwardPass]）で各 node に `node.minCost`（BOS→node 終端の最小 prefix コスト、
     * BOS 連接コスト + 語コスト + incoming 最良連接コスト込み）を設定し、後ろ向き DP（[computeMinSuffixCost]）
     * で `suffixCost[node]`（node 終端→EOS の最小コスト、EOS 連接コスト込み）を求める。
     * 各アーク（node）の最小全文コストは `node.minCost + suffixCost[node]`。
     *
     * ## 注意事項
     * - forward[startNode] 相当の BOS→node コストは node.minCost に畳み込み済み（二重加算しない）。
     * - EOS 連接コストは suffixCost に含む（二重加算しない）。
     * - suffixCost が [Long.MAX_VALUE] の node（EOS まで到達不能）はアーク除外する。
     * - overflow 防止: minCost と suffixCost の両方が [Long.MAX_VALUE] でない場合のみ加算する。
     * - 同一 [ArcKey]（同一 span・同一 surface）が複数 node から現れた場合は最小コストを採用する。
     *
     * @param reading ひらがな reading（格子の主軸）
     * @param lexicon reading 座標の辞書
     * @param costProvider 連接コスト提供者
     * @return (startOffset, endOffset, surface) → 最小全文コスト の map（到達不能アークは含まない）
     */
    fun arcMarginalCosts(
        reading: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): Map<ArcKey, Long> {
        if (reading.isEmpty()) return emptyMap()

        val (beginNodes, endNodes) = buildNodes(reading, lexicon)

        runForwardPass(reading, beginNodes, endNodes, costProvider)

        val suffixCost = computeMinSuffixCost(reading.length, beginNodes, endNodes, costProvider)
        val result = HashMap<ArcKey, Long>()

        for (nodeList in beginNodes) {
            for (node in nodeList) {
                val forward = node.minCost
                val suffix = suffixCost[node] ?: Long.MAX_VALUE

                val isForwardReachable = forward != Long.MAX_VALUE
                val isSuffixReachable = suffix != Long.MAX_VALUE

                if (!isForwardReachable || !isSuffixReachable) continue

                val fullPathCost = forward + suffix
                val arcKey = ArcKey(
                    startOffset = node.startOffset,
                    endOffset = node.endOffset,
                    surface = node.lexeme.surface,
                )
                val existing = result[arcKey]

                if (existing == null || fullPathCost < existing) {
                    result[arcKey] = fullPathCost
                }
            }
        }

        return result
    }

    /**
     * surface 制約付き最小コスト完全経路探索。
     *
     * [reading]（ひらがな）と [surface]（表層形）の両方を完全にカバーする lexeme 列を格子上で探し、
     * 最小コスト経路を返す。見つからない場合は null を返す。
     *
     * ## アルゴリズム（Markov 前向き DP + surface 制約）
     *
     * ### DP 状態 (readingOffset, surfaceOffset, rcAttr)
     * 連接コスト `transitionCost(prevRcAttr, nextLcAttr)` は直前ノードの rcAttr に依存する。
     * rcAttr を状態に含めないと「prefix cost は高いが rcAttr の違いで下流連接が安い」経路を
     * 誤って枝刈りし、真の最小コストを取りこぼす（反例: 以下）。
     * よって DP 状態は `(readingOffset, surfaceOffset, rcAttr)` の3要素で構成し、
     * 同一3要素ごとに prefix cost の最小値を保持する（Markov 最適原理）。
     *
     * 反例（rcAttr なしでは誤る）:
     * ```
     * A: surface"X" rc=1 wcost=0  ; B: surface"X" rc=2 wcost=10
     * C: surface"Y" lc=3 wcost=0
     * transitionCost(1,3)=+1000, transitionCost(2,3)=-1000, それ以外=0
     * 真の最小コスト: B,C = 10-1000 = -990
     * rcAttr を状態に持たないと: (1,"XY") で A を残し A,C = 1000 を誤って返す
     * ```
     *
     * ### DAG トポロジカル順保証
     * lexeme は reading を 1 文字以上消費するため `readingOffset` は遷移先で必ず増加する。
     * `readingOffset` 昇順の外ループ内で「同一 readingOffset マップへの書き込み（後退辺）」が
     * 発生しないため、前向き DP は負連接コストの下でも厳密に最小コストを求められる。
     *
     * ### ConcurrentModification 回避
     * `dpTable` は `readingOffset → HashMap<Pair(surfaceOffset,rcAttr), DpValue>` の2層マップ。
     * readingOffset ごとに独立したマップとし、ループ内では現在 offset のマップを読み取りのみ、
     * 書き込みは必ず別（大きい）readingOffset のマップに行う。
     * Kotlin/Native の `HashMap.EntryRef` がバッキングマップ変更後に CME を投げる問題を回避する。
     *
     * @param reading ひらがな reading（格子の主軸）
     * @param surface 探索する表層形（surface 制約）
     * @param lexicon reading 座標の辞書
     * @param costProvider 連接コスト提供者
     * @return (最小コスト, lexeme 列) のペア。一致経路が存在しない場合は null。
     */
    fun findMinCostPathForSurface(
        reading: String,
        surface: String,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): Pair<Long, List<LexemeEntry>>? {
        if (reading.isEmpty() || surface.isEmpty()) return null

        /**
         * DP 状態の識別子。
         *
         * [readingOffset] と [surfaceOffset] に加え [rcAttr] を含む（Markov 境界 context）。
         * rcAttr が異なる状態は連接コストが異なるため独立して最小コストを追跡する。
         */
        data class DpKey(
            val readingOffset: Int,
            val surfaceOffset: Int,
            val rcAttr: Int,
        )

        /**
         * DP テーブルの1エントリ。
         *
         * [minCost] = BOS〜この状態終端までの最小 prefix コスト（EOS 連接コストは含まない）。
         * [prevKey] と [lexeme] はバックポインタ（経路復元用）。
         */
        data class DpValue(
            val minCost: Long,
            val prevKey: DpKey?,
            val lexeme: LexemeEntry?,
        )

        // 外層キー: readingOffset。内層キー: (surfaceOffset, rcAttr) のペア。
        // 分離理由: 現在 readingOffset のマップを読み取りながら、より大きい readingOffset の
        // マップにだけ書き込むことで CME を回避する（DAG 性によりこれは常に成立）。
        val dpTable = HashMap<Int, HashMap<Pair<Int, Int>, DpValue>>()

        // BOS 初期状態: readingOffset=0, surfaceOffset=0, rcAttr=BOS_EOS_CONTEXT_ID
        val bosInnerKey = Pair(0, BOS_EOS_CONTEXT_ID)
        dpTable.getOrPut(0) { HashMap() }[bosInnerKey] = DpValue(
            minCost = 0L,
            prevKey = null,
            lexeme = null,
        )

        // readingOffset 昇順でトポロジカルに処理
        for (readingOffset in 0 until reading.length) {
            val statesAtOffset = dpTable[readingOffset] ?: continue
            val candidates = lexicon.commonPrefixSearch(reading, readingOffset)

            for (match in candidates) {
                val lexeme = match.lexeme
                val lexemeSurface = lexeme.surface
                val newReadingOffset = match.readingEndOffset

                // statesAtOffset は読み取りのみ。書き込み先は newReadingOffset > readingOffset なので CME なし。
                for ((innerKey, dpValue) in statesAtOffset) {
                    val (surfaceOffset, prevRcAttr) = innerKey
                    val surfaceEnd = surfaceOffset + lexemeSurface.length

                    // surface 制約: surface[surfaceOffset..surfaceEnd) が lexeme.surface と一致するか
                    if (surfaceEnd > surface.length) continue

                    val surfaceSlice = surface.substring(surfaceOffset, surfaceEnd)

                    if (surfaceSlice != lexemeSurface) continue

                    // コスト計算: 連接コスト（prevRcAttr → lexeme.lcAttr）+ wcost
                    val connectionCost = costProvider.transitionCost(prevRcAttr, lexeme.lcAttr)
                    val newCost = dpValue.minCost + connectionCost.toLong() + lexeme.wcost.toLong()

                    // 遷移先状態: readingOffset が大きくなるため別マップへの書き込み
                    val targetMap = dpTable.getOrPut(newReadingOffset) { HashMap() }
                    val newInnerKey = Pair(surfaceEnd, lexeme.rcAttr)
                    val existing = targetMap[newInnerKey]

                    if (existing == null || newCost < existing.minCost) {
                        targetMap[newInnerKey] = DpValue(
                            minCost = newCost,
                            prevKey = DpKey(
                                readingOffset = readingOffset,
                                surfaceOffset = surfaceOffset,
                                rcAttr = prevRcAttr,
                            ),
                            lexeme = lexeme,
                        )
                    }
                }
            }
        }

        // ゴール走査: readingOffset==reading.length の状態のうち surfaceOffset==surface.length を満たす
        // 全 rcAttr 状態に EOS 連接コストを加えて真の最小総コストを選ぶ。
        // （rcAttr ごとに EOS コストが異なるため全状態を走査する必要がある。）
        val goalStates = dpTable[reading.length] ?: return null

        var bestTotalCost = Long.MAX_VALUE
        var bestGoalKey: DpKey? = null

        for ((innerKey, dpValue) in goalStates) {
            val (surfaceOffset, rcAttr) = innerKey

            if (surfaceOffset != surface.length) continue

            val eosCost = costProvider.transitionCost(rcAttr, BOS_EOS_CONTEXT_ID)
            val totalCost = dpValue.minCost + eosCost.toLong()

            if (totalCost < bestTotalCost) {
                bestTotalCost = totalCost
                bestGoalKey = DpKey(
                    readingOffset = reading.length,
                    surfaceOffset = surface.length,
                    rcAttr = rcAttr,
                )
            }
        }

        val goalKey = bestGoalKey ?: return null

        // バックポインタをたどって経路を復元（BOS→EOS 正順）
        val reversedPath = mutableListOf<LexemeEntry>()
        var currentKey: DpKey? = goalKey

        while (currentKey != null) {
            val innerKey = Pair(currentKey.surfaceOffset, currentKey.rcAttr)
            val value = dpTable[currentKey.readingOffset]?.get(innerKey) ?: break
            val lexeme = value.lexeme

            if (lexeme != null) {
                reversedPath.add(lexeme)
            }

            currentKey = value.prevKey
        }

        val path = reversedPath.reversed()

        return bestTotalCost to path
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
     * 後ろ向き DP で各ノードの「end から EOS までの最小コスト（EOS 連接コスト込み）」を求める。
     *
     * DAG 上の辺は前方向のみのため、end offset の降順（トポロジカル逆順）で DP を解けば
     * 負コストがあっても厳密な最小値が得られる。
     *
     * 各エントリ key は [LatticeNode] のインスタンス、value はその node の end から EOS までの
     * 最小コスト。EOS 連接コスト（rcAttr → 0）は suffix cost に含む。
     *
     * 到達不能（EOS まで経路がない）ノードは [Long.MAX_VALUE] を持つ。
     *
     * [minSuffixByRcAttr]: end offset ごとの rcAttr → 最小 suffix コストのマップを
     * 内部で保持し、後続ノードが前ノードの suffix を参照する際に使用する。
     */
    private fun computeMinSuffixCost(
        readingLength: Int,
        beginNodes: Array<MutableList<LatticeNode>>,
        endNodes: Array<MutableList<LatticeNode>>,
        costProvider: ConnectionCostProvider,
    ): Map<LatticeNode, Long> {
        val suffixCost = HashMap<LatticeNode, Long>()

        // end offset 降順に DP を解く（DAG トポロジカル逆順）
        for (endPosition in readingLength downTo 0) {
            val nodesEndingHere = endNodes[endPosition]

            for (node in nodesEndingHere) {
                if (endPosition == readingLength) {
                    // EOS 直前ノード: suffix cost = EOS 連接コスト（rcAttr → 0）
                    val eosCost = costProvider.transitionCost(node.lexeme.rcAttr, BOS_EOS_CONTEXT_ID)
                    suffixCost[node] = eosCost.toLong()
                } else {
                    // 後続ノードの suffix cost 経由で最小化
                    val nextNodes = if (endPosition < beginNodes.size) beginNodes[endPosition] else emptyList()
                    var minReachable = Long.MAX_VALUE

                    for (nextNode in nextNodes) {
                        val nextSuffix = suffixCost[nextNode] ?: Long.MAX_VALUE

                        if (nextSuffix == Long.MAX_VALUE) continue

                        val connectionCost = costProvider.transitionCost(
                            node.lexeme.rcAttr,
                            nextNode.lexeme.lcAttr,
                        )
                        val candidate = connectionCost.toLong() + nextNode.lexeme.wcost.toLong() + nextSuffix

                        if (candidate < minReachable) {
                            minReachable = candidate
                        }
                    }

                    suffixCost[node] = minReachable
                }
            }
        }

        return suffixCost
    }

    /**
     * 後ろ向き DP の suffix cost を A* ヒューリスティックとした前向き探索で真のグローバル N-best を列挙する。
     *
     * ## アルゴリズム
     * - 優先度: `f = g（BOS から現位置までの実コスト）+ h（現位置ノードの最小 suffix cost）`
     * - h は厳密下界（DAG の最適 suffix）のため admissible（f ≤ 実際の完全コスト）かつ consistent。
     * - 完全経路（offset == readingLength）を pop したとき `f = g + eosCost` が真の総コスト。
     *   負コストが存在しても h の厳密性により、complete を pop した順が真のグローバル昇順になる。
     *
     * ## 負コストへの対応
     * - BOS start（h 値）は readingLength == 0 ならすぐ EOS コスト、そうでなければ
     *   位置 0 にある全ノードの `(BOS→node 連接コスト + wcost + suffixCost[node])` の最小。
     * - isComplete フラグで「EOS コストを h から取り除き g に畳み込んだ」状態を管理する。
     *
     * ## キュー実装
     * [MutableList] + `minByOrNull` は O(n)。N ≤ 16 の実用条件では十分。
     */
    private fun collectNBestPaths(
        readingLength: Int,
        beginNodes: Array<MutableList<LatticeNode>>,
        costProvider: ConnectionCostProvider,
        minSuffixCost: Map<LatticeNode, Long>,
        maxPaths: Int,
    ): List<Pair<Long, List<LexemeEntry>>> {
        /**
         * キューに積む探索状態。
         *
         * [fScore] = g（prefix 実コスト）+ h（最小 suffix コスト）。
         * [isComplete] が true のときは [fScore] が EOS 込みの真の総コスト。
         */
        data class SearchState(
            /** A* の f スコア（g + h）。complete のときは総コスト。 */
            val fScore: Long,
            /** BOS から現位置の語終端（exclusive）までの実コスト g。 */
            val gCost: Long,
            /** 現在の reading オフセット。 */
            val offset: Int,
            /** BOS から現位置までのパス（正順）。 */
            val path: List<LexemeEntry>,
            /** 直前語の rcAttr（次の連接コスト計算に使用）。 */
            val prevRcAttr: Int,
            /** EOS コストを g に畳み込み済みの完全経路かどうか。 */
            val isComplete: Boolean,
        )

        val results = mutableListOf<Pair<Long, List<LexemeEntry>>>()
        val openQueue = mutableListOf<SearchState>()

        // BOS 初期状態: f = BOS 位置の最小 suffix（後続展開時に正確な f を計算）
        // offset=0、g=0、prevRcAttr=BOS_EOS_CONTEXT_ID
        openQueue.add(
            SearchState(
                fScore = 0L,
                gCost = 0L,
                offset = 0,
                path = emptyList(),
                prevRcAttr = BOS_EOS_CONTEXT_ID,
                isComplete = false,
            ),
        )

        while (openQueue.isNotEmpty() && results.size < maxPaths) {
            val minIndex = openQueue.indices.minByOrNull { openQueue[it].fScore } ?: break
            val current = openQueue.removeAt(minIndex)

            if (current.isComplete) {
                results.add(current.gCost to current.path)
                continue
            }

            if (current.offset == readingLength) {
                // EOS 到達: EOS コストを g に畳み込んで complete 状態としてキューに再投入
                val eosCost = costProvider.transitionCost(current.prevRcAttr, BOS_EOS_CONTEXT_ID)
                val totalCost = current.gCost + eosCost.toLong()

                openQueue.add(
                    SearchState(
                        fScore = totalCost,
                        gCost = totalCost,
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
                val newG = current.gCost + connectionCost.toLong() + node.lexeme.wcost.toLong()

                // h = 後続ノードの最小 suffix cost（到達不能なら Long.MAX_VALUE で探索から除外）
                val heuristic = minSuffixCost[node] ?: Long.MAX_VALUE

                if (heuristic == Long.MAX_VALUE) continue

                val newF = newG + heuristic

                openQueue.add(
                    SearchState(
                        fScore = newF,
                        gCost = newG,
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
