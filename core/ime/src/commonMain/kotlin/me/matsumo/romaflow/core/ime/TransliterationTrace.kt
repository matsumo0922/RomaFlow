package me.matsumo.romaflow.core.ime

/**
 * romaji→kana の変換履歴全体を保持するトレース。
 *
 * [edges] は確定済みエッジ（[TransliterationEdge.EdgeState.Committed]）と
 * 末尾の未確定エッジ（[TransliterationEdge.EdgeState.Pending]、最大1つ）を順序付きで保持する。
 *
 * ## 不変条件
 * - committed エッジの [TransliterationEdge.reading] 連結が [CompositionSource.readingInput] と一致する。
 * - pending エッジは末尾に最大1つだけ存在する。
 * - 全エッジの [TransliterationEdge.sourceSpan] が atoms を隙間なく覆う（gaps がない）。
 *
 * ## Backspace 削除写像
 * backspace による削除は [deleteLastKana] で行う。raw atom 単純削除ではなく、かな単位で edge を削る。
 * `kyo→きょ` は1エッジなので BS は `き` を [LiteralReading] として残す。
 * `か` は `k`+`a` の2 atoms からなるエッジなので BS で丸ごと消える（readingInput の `か` が消える）。
 */
internal class TransliterationTrace private constructor(
    val atoms: List<InputAtom>,
    val edges: List<TransliterationEdge>,
) {

    /**
     * 末尾の pending kana（pendingRomaji に相当するローマ字 / pending 表示文字列）。
     *
     * Pending エッジが存在する場合はその reading、なければ空文字。
     */
    val pendingDisplay: String
        get() = edges.lastOrNull()?.takeIf { it.state == TransliterationEdge.EdgeState.Pending }?.reading.orEmpty()

    /**
     * 確定済みのかな列（readingInput に相当）。
     *
     * Committed エッジの reading を順に連結して返す。
     */
    val committedReading: String
        get() {
            val builder = StringBuilder()

            for (edge in edges) {
                if (edge.state == TransliterationEdge.EdgeState.Committed) {
                    builder.append(edge.reading)
                }
            }

            return builder.toString()
        }

    /**
     * 末尾のかな1文字を削除した新しいトレースを返す。
     *
     * 削除対象は末尾の Committed エッジの reading の最後の文字。エッジの reading が2文字以上なら
     * その文字だけ削り [SourceUnit.LiteralReading] エッジとして再挿入する（`kyo→きょ→BS→き` ケース）。
     * reading が1文字なら丸ごとエッジを削除する（`ka→か→BS→(空)` ケース）。
     *
     * Pending エッジがある場合はまず pending の末尾1文字を削る（pendingRomaji の dropLast 相当）。
     * pending が空になった場合はエッジを削除する。
     */
    fun deleteLastKana(): TransliterationTrace {
        if (edges.isEmpty()) {
            return this
        }

        val lastEdge = edges.last()

        if (lastEdge.state == TransliterationEdge.EdgeState.Pending) {
            return deletePendingTail(lastEdge)
        }

        return deleteCommittedTail(lastEdge)
    }

    /**
     * 新しい [InputAtom] を追記し、変換結果を反映した新しいトレースを返す。
     *
     * [newAtoms] は今回追加する atoms、[newEdges] は変換器が生成した全エッジ（committed + pending）。
     * 既存の全 atoms に [newAtoms] を追記し、全エッジを [newEdges] で置き換える。
     *
     * 既存 committed エッジはここで保持しない（呼び出し側が全エッジを再生成して渡す）。
     * これにより `n`/`tt`/`kyo` のように直前 pending の解釈が変わるケースに対応する。
     */
    fun appendAtoms(
        newAtoms: List<InputAtom>,
        newEdges: List<TransliterationEdge>,
    ): TransliterationTrace {
        val mergedAtoms = atoms + newAtoms

        return TransliterationTrace(
            atoms = mergedAtoms,
            edges = newEdges,
        )
    }

    /**
     * pending エッジを確定済みに昇格させた新しいトレースを返す。
     *
     * [finalizedReading] は pending ローマ字を確定かなへ変換した文字列（`n`→`ん` 等）。
     */
    fun finalizePending(finalizedReading: String): TransliterationTrace {
        val pendingEdge = edges.lastOrNull() ?: return this
        val hasPending = pendingEdge.state == TransliterationEdge.EdgeState.Pending

        if (!hasPending) {
            return this
        }

        val committed = pendingEdge.copy(
            reading = finalizedReading,
            state = TransliterationEdge.EdgeState.Committed,
        )
        val updatedEdges = edges.dropLast(1) + committed

        return TransliterationTrace(atoms = atoms, edges = updatedEdges)
    }

    private fun deletePendingTail(pendingEdge: TransliterationEdge): TransliterationTrace {
        val trimmed = pendingEdge.reading.dropLast(1)
        val trimmedAtoms = atoms.dropLast(1)

        if (trimmed.isEmpty()) {
            val updatedEdges = edges.dropLast(1)

            return TransliterationTrace(atoms = trimmedAtoms, edges = updatedEdges)
        }

        val shrunkSpan = pendingEdge.sourceSpan.copy(toAtomIndex = trimmedAtoms.size)
        val shortenedEdge = pendingEdge.copy(reading = trimmed, sourceSpan = shrunkSpan)
        val updatedEdges = edges.dropLast(1) + shortenedEdge

        return TransliterationTrace(atoms = trimmedAtoms, edges = updatedEdges)
    }

    private fun deleteCommittedTail(lastEdge: TransliterationEdge): TransliterationTrace {
        val reading = lastEdge.reading

        if (reading.length <= 1) {
            val updatedEdges = edges.dropLast(1)

            return TransliterationTrace(atoms = atoms, edges = updatedEdges)
        }

        val trimmedReading = reading.dropLast(1)
        val literalEdge = TransliterationEdge(
            sourceSpan = lastEdge.sourceSpan,
            reading = trimmedReading,
            state = TransliterationEdge.EdgeState.Committed,
        )
        val updatedEdges = edges.dropLast(1) + literalEdge

        return TransliterationTrace(atoms = atoms, edges = updatedEdges)
    }

    companion object {

        /** 空のトレースを生成する factory メソッド。 */
        fun empty(): TransliterationTrace {
            return TransliterationTrace(atoms = emptyList(), edges = emptyList())
        }

        /**
         * atoms と edges を直接指定してトレースを生成する factory メソッド。
         *
         * 主にテストや [CompositionSource] の内部 factory から使用する。
         */
        fun of(
            atoms: List<InputAtom>,
            edges: List<TransliterationEdge>,
        ): TransliterationTrace {
            return TransliterationTrace(atoms = atoms, edges = edges)
        }
    }
}
