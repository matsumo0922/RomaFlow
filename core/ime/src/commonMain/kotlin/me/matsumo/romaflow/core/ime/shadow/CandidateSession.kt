package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry

/**
 * 候補窓セッション。display surface → backend 経路の対応表。
 *
 * 候補窓を開いたとき、[CompositionGraph] の N-best 経路から候補エントリを構築し保持する。
 * 各エントリは表示文字列（surface）と対応する [LexemeEntry] 経路・[CompositionGraph.PathId] を持つ。
 *
 * ## 重複 surface の畳み込み
 * 同一 surface かつ異なる内部経路を持つ候補は、最小コストの経路を代表として1エントリに畳む（§3.4/§6 step7）。
 * これにより候補窓で同じ漢字が重複して表示されるのを防ぐ。
 *
 * ## セッション ID
 * [sessionId] は候補窓の開閉・入力変更のたびに単調増加する。
 * 結果の stale 判定に使用する。
 */
internal class CandidateSession private constructor(
    /** このセッションの識別子（単調増加）。 */
    val sessionId: Int,
    /** 重複除去済みの候補エントリリスト（表示順）。 */
    val entries: List<CandidateEntry>,
) {

    /**
     * 1 件の候補エントリ。
     *
     * [surface] は候補窓に表示する文字列、[pathId] は対応する [CompositionGraph.PathId]、
     * [lexemePath] は確認・適用に使う完全 lexeme 経路。
     */
    data class CandidateEntry(
        /** 候補窓に表示する文字列（lexeme surface の連結）。 */
        val surface: String,
        /** 対応する経路 ID。 */
        val pathId: CompositionGraph.PathId,
        /** 完全 lexeme 経路。 */
        val lexemePath: List<LexemeEntry>,
    )

    /** 候補数。 */
    val count: Int get() = entries.size

    /**
     * [index] 番目の候補の表示文字列を返す。
     *
     * 範囲外の場合は null を返す。
     */
    fun surfaceOrNull(index: Int): String? {
        return entries.getOrNull(index)?.surface
    }

    /**
     * [surface] に対応する [CandidateEntry] を返す。
     *
     * 該当なしは null を返す。
     */
    fun entryBySurfaceOrNull(surface: String): CandidateEntry? {
        return entries.firstOrNull { it.surface == surface }
    }

    companion object {

        /**
         * [CompositionGraph] から候補セッションを構築する。
         *
         * N-best 経路を走査し、surface（lexeme の surface 連結）が重複するものは
         * 最小コスト経路のみを採用して畳み込む。
         *
         * @param graph 対象の [CompositionGraph]
         * @param nextSessionId 新しいセッション ID
         */
        fun buildFrom(
            graph: CompositionGraph,
            nextSessionId: Int,
        ): CandidateSession {
            val seenSurfaces = mutableSetOf<String>()
            val entries = mutableListOf<CandidateEntry>()

            for (pathId in graph.allPathIds) {
                val lexemePath = graph.pathOrNull(pathId) ?: continue
                val surface = buildSurface(lexemePath)

                if (surface.isEmpty()) continue

                val isNew = seenSurfaces.add(surface)

                if (isNew) {
                    entries.add(
                        CandidateEntry(
                            surface = surface,
                            pathId = pathId,
                            lexemePath = lexemePath,
                        ),
                    )
                }
            }

            return CandidateSession(sessionId = nextSessionId, entries = entries)
        }

        /** lexeme 経路の surface を連結して表示文字列を作る。 */
        private fun buildSurface(lexemePath: List<LexemeEntry>): String {
            val builder = StringBuilder()

            for (lexeme in lexemePath) {
                builder.append(lexeme.surface)
            }

            return builder.toString()
        }

        /** 候補なし（初期状態）のセッション。 */
        fun empty(): CandidateSession {
            return CandidateSession(sessionId = 0, entries = emptyList())
        }
    }
}
