package me.matsumo.romaflow.core.ime

/**
 * 編集距離 DP で token の読み列と readingInput を対応付ける [ReadingAligner]。
 *
 * token の読みを連結した文字列（補正後）と readingInput（打った通り）を Needleman-Wunsch 型の
 * 文字単位アライメントで対応付け、各 token 境界が readingInput 上のどの位置に対応するかを求める。
 * これにより 1 文字程度の誤字訂正があっても各 token に妥当な [TextRange] を与えられる。
 * confidence は readingInput[range] と token の読みが完全一致したときのみ 1f、それ以外は 0f とする。
 */
internal class DpReadingAligner : ReadingAligner {

    override fun align(readingInput: String, tokens: List<SegmentToken>): List<AlignedSegment> {
        if (tokens.isEmpty()) {
            return emptyList()
        }

        val readingBoundaries = cumulativeReadingBoundaries(tokens)
        val concatenatedReading = tokens.joinToString(separator = "") { it.reading }
        val inputBoundaries = mapBoundariesToInput(concatenatedReading, readingInput, readingBoundaries)

        return tokens.mapIndexed { index, token ->
            toAlignedSegment(token, readingInput, inputBoundaries[index], inputBoundaries[index + 1])
        }
    }

    /** token ごとの読み長を累積し、連結読み上の token 境界位置（size = tokens.size + 1）を返す。 */
    private fun cumulativeReadingBoundaries(tokens: List<SegmentToken>): IntArray {
        val boundaries = IntArray(tokens.size + 1)

        for (index in tokens.indices) {
            boundaries[index + 1] = boundaries[index] + tokens[index].reading.length
        }

        return boundaries
    }

    /**
     * 連結読み [source] と [target]（readingInput）を文字単位でアライメントし、
     * [sourceBoundaries] の各位置が [target] 上のどの位置に対応するかを返す。
     */
    private fun mapBoundariesToInput(
        source: String,
        target: String,
        sourceBoundaries: IntArray,
    ): IntArray {
        val distance = buildDistanceTable(source, target)
        val operations = backtrackOperations(distance, source, target)

        return resolveBoundaryPositions(operations, sourceBoundaries, target.length)
    }

    /** dp[i][j] = source[0,i) と target[0,j) の編集距離。 */
    private fun buildDistanceTable(source: String, target: String): Array<IntArray> {
        val sourceLength = source.length
        val targetLength = target.length
        val distance = Array(sourceLength + 1) { IntArray(targetLength + 1) }

        for (sourceIndex in 0..sourceLength) {
            distance[sourceIndex][0] = sourceIndex
        }

        for (targetIndex in 0..targetLength) {
            distance[0][targetIndex] = targetIndex
        }

        fillDistanceTable(distance, source, target)

        return distance
    }

    private fun fillDistanceTable(distance: Array<IntArray>, source: String, target: String) {
        for (sourceIndex in 1..source.length) {
            for (targetIndex in 1..target.length) {
                val substitutionCost = if (source[sourceIndex - 1] == target[targetIndex - 1]) 0 else 1
                val matchOrSub = distance[sourceIndex - 1][targetIndex - 1] + substitutionCost
                val insertion = distance[sourceIndex - 1][targetIndex] + 1
                val deletion = distance[sourceIndex][targetIndex - 1] + 1

                distance[sourceIndex][targetIndex] = minOf(matchOrSub, insertion, deletion)
            }
        }
    }

    /**
     * distance を末尾から逆追跡し、前から順のアライメント操作列を返す。
     *
     * [Operation.MATCH_OR_SUB] は source/target を 1 文字ずつ消費、[Operation.INSERTION] は source のみ、
     * [Operation.DELETION] は target のみを消費する。
     */
    private fun backtrackOperations(
        distance: Array<IntArray>,
        source: String,
        target: String,
    ): List<Operation> {
        val operations = ArrayList<Operation>(source.length + target.length)

        var sourceIndex = source.length
        var targetIndex = target.length

        while (sourceIndex > 0 || targetIndex > 0) {
            val operation = chooseOperation(distance, source, target, sourceIndex, targetIndex)

            when (operation) {
                Operation.MATCH_OR_SUB -> {
                    sourceIndex--
                    targetIndex--
                }

                Operation.INSERTION -> sourceIndex--
                Operation.DELETION -> targetIndex--
            }

            operations.add(operation)
        }

        operations.reverse()

        return operations
    }

    private fun chooseOperation(
        distance: Array<IntArray>,
        source: String,
        target: String,
        sourceIndex: Int,
        targetIndex: Int,
    ): Operation {
        if (sourceIndex > 0 && targetIndex > 0) {
            val substitutionCost = if (source[sourceIndex - 1] == target[targetIndex - 1]) 0 else 1
            val isDiagonal = distance[sourceIndex][targetIndex] == distance[sourceIndex - 1][targetIndex - 1] + substitutionCost

            if (isDiagonal) {
                return Operation.MATCH_OR_SUB
            }
        }

        val isInsertion = sourceIndex > 0 && distance[sourceIndex][targetIndex] == distance[sourceIndex - 1][targetIndex] + 1

        return if (isInsertion) Operation.INSERTION else Operation.DELETION
    }

    /**
     * アライメント操作列を前から辿り、source 上の各境界位置に対応する target 位置を求める。
     *
     * 末尾境界は必ず target 全長に固定し、末尾側の余り（target だけの削除）を最後の token に含める。
     */
    private fun resolveBoundaryPositions(
        operations: List<Operation>,
        sourceBoundaries: IntArray,
        targetLength: Int,
    ): IntArray {
        val positions = IntArray(sourceBoundaries.size)
        val cursor = AlignmentCursor()

        for (operation in operations) {
            recordReachedBoundaries(positions, sourceBoundaries, cursor)
            cursor.advance(operation)
        }

        recordReachedBoundaries(positions, sourceBoundaries, cursor)

        positions[0] = 0
        positions[positions.lastIndex] = targetLength

        return positions
    }

    private fun recordReachedBoundaries(
        positions: IntArray,
        sourceBoundaries: IntArray,
        cursor: AlignmentCursor,
    ) {
        while (cursor.nextBoundaryIndex < sourceBoundaries.size &&
            sourceBoundaries[cursor.nextBoundaryIndex] == cursor.sourcePosition
        ) {
            positions[cursor.nextBoundaryIndex] = cursor.targetPosition
            cursor.nextBoundaryIndex++
        }
    }

    private fun toAlignedSegment(
        token: SegmentToken,
        readingInput: String,
        startInclusive: Int,
        endExclusive: Int,
    ): AlignedSegment {
        val safeStart = startInclusive.coerceIn(0, readingInput.length)
        val safeEnd = endExclusive.coerceIn(safeStart, readingInput.length)
        val covered = readingInput.substring(safeStart, safeEnd)
        val confidence = if (covered == token.reading) EXACT_MATCH_CONFIDENCE else NO_MATCH_CONFIDENCE

        return AlignedSegment(token, TextRange(safeStart, safeEnd, confidence))
    }

    /** 文字単位アライメントの 1 操作。 */
    private enum class Operation {
        /** source/target を 1 文字ずつ消費（一致 or 置換）。 */
        MATCH_OR_SUB,

        /** source のみ消費（target に対応文字なし）。 */
        INSERTION,

        /** target のみ消費（source に対応文字なし）。 */
        DELETION,
    }

    /** 前向き走査中の source/target カーソル位置と、次に記録すべき境界 index を保持する。 */
    private class AlignmentCursor {
        var sourcePosition = 0
        var targetPosition = 0
        var nextBoundaryIndex = 0

        fun advance(operation: Operation) {
            when (operation) {
                Operation.MATCH_OR_SUB -> {
                    sourcePosition++
                    targetPosition++
                }

                Operation.INSERTION -> sourcePosition++
                Operation.DELETION -> targetPosition++
            }
        }
    }

    private companion object {
        /** 読みが完全一致したときの confidence。 */
        const val EXACT_MATCH_CONFIDENCE = 1f

        /** 読みが一致しないときの confidence。 */
        const val NO_MATCH_CONFIDENCE = 0f
    }
}
