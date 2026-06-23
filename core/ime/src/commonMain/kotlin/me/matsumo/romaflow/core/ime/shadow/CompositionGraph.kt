package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import kotlin.jvm.JvmInline

/**
 * shadow エンジンが保持する reading lattice グラフ。
 *
 * [reading]（ひらがな全体）に対して [ReadingLatticeDecoder] で構築した
 * N-best 経路リストを内包する。経路は cumulative cost 昇順で保持する。
 *
 * shadow モード専用のため、live の `RomaFlowEngine` とは完全に独立している。
 * A-5 cutover で初めて live に接続する。
 *
 * ## 経路 ID
 * 各経路には [PathId]（[Int] の値クラス）で識別子を付与する。
 * 経路 ID は 0 始まりのインデックスで、cost 昇順に割り振る（rank-0 が最安経路）。
 *
 * ## OOV 保証
 * [ReadingLatticeDecoder.nBest] は [me.matsumo.romaflow.core.morphology.CompositeLexicon] +
 * [me.matsumo.romaflow.core.morphology.LiteralLexicon] の fallback で全位置に arc が存在するため、
 * reading が辞書外でも経路が空になることはない（デッドロック不可）。
 */
internal class CompositionGraph private constructor(
    /** Viterbi デコードに使った読み（ひらがな）。 */
    val reading: String,
    /**
     * N-best 経路リスト。各エントリは (cumulative cost, lexeme 経路) のペア。
     * cost 昇順で保持する（rank-0 が最安）。
     */
    val paths: List<Pair<Long, List<LexemeEntry>>>,
) {

    /**
     * 経路を識別する値クラス。
     *
     * 値は cost 昇順の rank インデックス（0 = 最安経路）。
     * Android/JVM target は `@JvmInline` を要求するため明示する（K/N は不要だが共通化のため付与）。
     */
    @JvmInline
    value class PathId(val rank: Int)

    /** 利用可能な全 [PathId] のリスト（rank 昇順）。 */
    val allPathIds: List<PathId>
        get() = paths.indices.map { PathId(it) }

    /** 最安経路の [PathId]（rank=0）。経路が空の場合は null。 */
    val bestPathId: PathId?
        get() = if (paths.isEmpty()) null else PathId(0)

    /** グラフが有効な経路を 1 件以上持つか。 */
    val hasValidPath: Boolean
        get() = paths.isNotEmpty()

    /**
     * [pathId] に対応する lexeme 経路を返す。
     *
     * 範囲外の場合は null を返す。
     */
    fun pathOrNull(pathId: PathId): List<LexemeEntry>? {
        return paths.getOrNull(pathId.rank)?.second
    }

    /**
     * [pathId] に対応する累積コストを返す。
     *
     * 範囲外の場合は null を返す。
     */
    fun costOrNull(pathId: PathId): Long? {
        return paths.getOrNull(pathId.rank)?.first
    }

    companion object {

        /** デフォルトの N-best 件数。 */
        const val DEFAULT_N_BEST = 8

        /**
         * [reading] と [lexicon]・[costProvider] から [CompositionGraph] を構築する。
         *
         * [ReadingLatticeDecoder.nBest] で N-best 経路を列挙し、[CompositionGraph] として返す。
         * reading が空の場合は経路なしのグラフを返す。
         */
        fun build(
            reading: String,
            lexicon: ReadingLexicon,
            costProvider: ConnectionCostProvider,
            nBest: Int = DEFAULT_N_BEST,
        ): CompositionGraph {
            if (reading.isEmpty()) {
                return CompositionGraph(reading = reading, paths = emptyList())
            }

            val paths = ReadingLatticeDecoder.nBest(
                reading = reading,
                lexicon = lexicon,
                costProvider = costProvider,
                n = nBest,
            )

            return CompositionGraph(reading = reading, paths = paths)
        }

        /**
         * 経路が空のグラフ（初期状態）を返す。
         *
         * [CompositionState.empty] の初期値として使用する。
         */
        fun empty(): CompositionGraph {
            return CompositionGraph(reading = "", paths = emptyList())
        }

        /**
         * テスト用: 指定した経路リストから直接グラフを構築する。
         *
         * 実辞書を必要とせず、テスト内で決定論的な経路を注入できる。
         */
        internal fun buildFromPaths(
            reading: String,
            paths: List<Pair<Long, List<LexemeEntry>>>,
        ): CompositionGraph {
            return CompositionGraph(reading = reading, paths = paths)
        }
    }
}
