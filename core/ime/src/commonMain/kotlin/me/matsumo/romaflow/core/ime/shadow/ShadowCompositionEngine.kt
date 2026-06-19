package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.RomajiKanaConverter
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback

/**
 * shadow エンジン。[CompositionState] 上の操作を実装する。
 *
 * live の `RomaFlowEngine` とは完全に独立した shadow mode 実装。
 * A-5 cutover で初めて live に差し替える。テスト・ハーネスからのみ駆動する。
 *
 * ## 操作一覧
 * - [inputRomaji]: ローマ字入力を受け付け source を更新する。
 * - [buildGraph]: 現在の reading で lattice graph を構築し selectedPathId を確定する。
 * - [preview]: 候補窓を開き、候補エントリを準備する。
 * - [confirmCandidate]: 候補を確定して prefix lock する。
 * - [revert]: 変換を取り消してかな表示へ戻す。
 * - [deleteBackward]: backspace（pending 削除・preview 破棄・lock 食い込みを含む）。
 * - [moveClause]: 節選択を左右に移動する。
 *
 * ## shadow mode 担保
 * このクラスは `me.matsumo.romaflow.core.ime` パッケージの `internal` クラス群を内部利用するが、
 * `RomaFlowEngine` の公開 API（Swift Export に露出する `class RomaFlowEngine` のメソッド群）を
 * 一切変更しない。`ShadowCompositionEngine` は `internal` クラスであり Swift Export に現れない。
 */
internal class ShadowCompositionEngine(
    private val lexicon: ReadingLexicon,
    private val costProvider: ConnectionCostProvider,
    private val nBest: Int = CompositionGraph.DEFAULT_N_BEST,
) {

    private val converter = RomajiKanaConverter()

    // 次に割り当てる InputAtom の ID（単調増加）
    private var nextAtomId = 0L

    // 入力状態のリビジョン（単調増加）
    private var inputRevision = 0

    // 候補窓セッション ID（単調増加）
    private var nextCandidateSessionId = 1

    /**
     * 現在の状態。初期は空状態。
     *
     * 外部からの参照で現在の [CompositionState] を取得できる。
     * setter は同モジュール内（テストを含む）からのみ呼べる（テスト用状態注入のため `internal set`）。
     */
    var state: CompositionState = CompositionState.empty()
        internal set

    /**
     * ローマ字文字列 [text] を入力し、新しい [CompositionState] を返す。
     *
     * [CompositionSource.appendRomaji] を呼び source を更新する。
     * graph は更新しない（[buildGraph] を別途呼ぶ）。
     * pending romaji がある場合は追記継続、session 封止条件では frozenPrefix で新セッション開始。
     */
    fun inputRomaji(text: String): CompositionState {
        markInputChanged()

        val currentReading = state.source.readingInput
        val hasStalePendingFree = state.source.atoms.isNotEmpty() && state.source.pendingRomaji.isEmpty()
        val hasReadingMismatch = currentReading != state.source.readingInput

        val sessionSource = if (hasStalePendingFree || hasReadingMismatch) {
            CompositionSource.withFrozenPrefix(currentReading, inputRevision)
        } else {
            state.source
        }

        val currentAtomId = nextAtomId
        val newSource = sessionSource.appendRomaji(
            converter = converter,
            input = text,
            nextRevision = inputRevision,
            nextAtomId = currentAtomId,
        )

        nextAtomId += text.length

        state = state.copy(
            source = newSource,
            clauseAnchor = null,
            candidateSession = CandidateSession.empty(),
        )

        return state
    }

    /**
     * 現在の [CompositionState.reading] で lattice graph を構築し [selectedPathId] を確定する。
     *
     * [CompositeLexicon]（primary + [LiteralLexicon] fallback）を使い OOV でもデッドロックしない。
     * [PinnedPathConstraint] が active な場合は、固定 prefix を除いた tail reading のみで graph を構築し、
     * pin 済み経路と合成する。
     *
     * 返した [CompositionState.selectedPathId] は graph の bestPathId（rank=0）。
     */
    fun buildGraph(): CompositionState {
        val reading = state.source.readingInput

        if (reading.isEmpty()) {
            state = state.copy(
                graph = CompositionGraph.empty(),
                selectedPathId = null,
            )

            return state
        }

        val compositeLexicon = buildReadingLexiconWithFallback(lexicon)

        val pinnedConstraint = state.pinnedConstraint
        val tailReading = if (pinnedConstraint.hasLockedPrefix) {
            reading.substring(pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length))
        } else {
            reading
        }

        val graph = CompositionGraph.build(
            reading = tailReading,
            lexicon = compositeLexicon,
            costProvider = costProvider,
            nBest = nBest,
        )

        val newSelectedPathId = graph.bestPathId

        state = state.copy(
            graph = graph,
            selectedPathId = newSelectedPathId,
            clauseAnchor = null,
        )

        return state
    }

    /**
     * 候補窓を開き、現在の graph から [CandidateSession] を構築する。
     *
     * 既存の [CandidateSession] を新しいセッション ID で置き換える。
     * 対象は現在の [CompositionState.graph] 全体（節単位の絞り込みは A-4 以降）。
     */
    fun openCandidates(): CompositionState {
        val session = CandidateSession.buildFrom(
            graph = state.graph,
            nextSessionId = nextCandidateSessionId++,
        )

        state = state.copy(candidateSession = session)

        return state
    }

    /**
     * 候補窓を閉じる。[CandidateSession] を空に戻す。
     */
    fun closeCandidates(): CompositionState {
        state = state.copy(candidateSession = CandidateSession.empty())

        return state
    }

    /**
     * 候補 [surface] を確定して prefix lock する。
     *
     * [CandidateSession] から [surface] に対応するエントリを検索し、
     * その [CompositionGraph.PathId] を [CompositionState.selectedPathId] に設定する。
     * さらに確定した経路を [PinnedPathConstraint] に追記して prefix boundary を拡張する。
     *
     * 候補が見つからない場合は no-op で現在の状態を返す。
     */
    fun confirmCandidate(surface: String): CompositionState {
        val entry = state.candidateSession.entryBySurfaceOrNull(surface) ?: return state

        val newPinnedConstraint = extendPinnedConstraint(entry)
        val newSelectedPathId = entry.pathId

        state = state.copy(
            selectedPathId = newSelectedPathId,
            pinnedConstraint = newPinnedConstraint,
            clauseAnchor = null,
            candidateSession = CandidateSession.empty(),
        )

        return state
    }

    /**
     * 確定した候補のエントリで [PinnedPathConstraint] を拡張する。
     *
     * 現在の locked boundary に確定した lexeme 経路の reading 長を加算する。
     * 固定 prefix の lexeme 経路に新しい経路エントリを追記する。
     */
    private fun extendPinnedConstraint(entry: CandidateSession.CandidateEntry): PinnedPathConstraint {
        val current = state.pinnedConstraint
        val entryReadingLength = computeReadingLength(entry.lexemePath)
        val newBoundary = current.lockedPrefixBoundary + entryReadingLength
        val newPinnedPath = current.pinnedPath + entry.lexemePath

        return PinnedPathConstraint(
            lockedPrefixBoundary = newBoundary,
            pinnedPath = newPinnedPath,
        )
    }

    /** lexeme 経路の reading 総文字数（ひらがな座標での長さ）を計算する。 */
    private fun computeReadingLength(path: List<LexemeEntry>): Int {
        var total = 0

        for (lexeme in path) {
            total += lexeme.reading.length
        }

        return total
    }

    /**
     * 変換を取り消してかな表示へ戻す（revert）。
     *
     * [selectedPathId] を null にし、graph・pinnedConstraint・candidateSession をリセットする。
     * source（入力かな）は保持する（再変換の出発点として残す）。
     */
    fun revert(): CompositionState {
        state = state.copy(
            graph = CompositionGraph.empty(),
            selectedPathId = null,
            clauseAnchor = null,
            pinnedConstraint = PinnedPathConstraint.empty(),
            candidateSession = CandidateSession.empty(),
        )

        return state
    }

    /**
     * backspace を適用する。
     *
     * ## 優先順位
     * 1. **候補 preview 中**: preview を破棄して [CandidateSession] を閉じる（reading は変えない）。
     * 2. **pending romaji あり**: pending 末尾1文字を削る（A-1 の [CompositionSource.deleteLastKana]）。
     * 3. **prefix lock 食い込み**: lock boundary を1文字削り、対応する pinnedPath 末尾を更新する。
     * 4. **変換済み（converted revert）**: 選択節を revert してかな表示に戻す。
     * 5. **未変換**: reading 末尾1かなを削る（A-1 の [CompositionSource.deleteLastKana]）。
     */
    fun deleteBackward(): CompositionState {
        val hasCandidateSession = state.candidateSession.count > 0
        val hasPending = state.source.pendingRomaji.isNotEmpty()
        val hasLockedPrefix = state.pinnedConstraint.hasLockedPrefix
        val hasConverted = state.isConverted

        return when {
            hasCandidateSession -> discardPreview()
            hasPending -> deletePendingTail()
            hasLockedPrefix -> deleteFromLockedPrefix()
            hasConverted -> revertConvertedSegment()
            else -> deleteReadingTail()
        }
    }

    /**
     * 候補 preview を破棄して [CandidateSession] を閉じる。reading は変えない。
     */
    private fun discardPreview(): CompositionState {
        state = state.copy(candidateSession = CandidateSession.empty())

        return state
    }

    /**
     * pending 末尾1文字を削る（A-1 の [CompositionSource.deleteLastKana] を経由）。
     */
    private fun deletePendingTail(): CompositionState {
        markInputChanged()

        val newSource = state.source.deleteLastKana(nextRevision = inputRevision)

        state = state.copy(source = newSource)

        return state
    }

    /**
     * prefix lock 食い込み削除。
     *
     * locked boundary を1文字減らし、pinnedPath の末尾 lexeme が1文字以上の reading を持つ場合は
     * その末尾文字を削る（reading 短縮）。lexeme の reading が1文字の場合はその lexeme ごと削る。
     * boundary が 0 になったら [PinnedPathConstraint.empty] に戻す。
     */
    private fun deleteFromLockedPrefix(): CompositionState {
        markInputChanged()

        val current = state.pinnedConstraint
        val newBoundary = current.lockedPrefixBoundary - 1

        val newConstraint = if (newBoundary <= 0) {
            PinnedPathConstraint.empty()
        } else {
            shrinkPinnedPath(current, newBoundary)
        }

        state = state.copy(
            pinnedConstraint = newConstraint,
            graph = CompositionGraph.empty(),
            selectedPathId = null,
            clauseAnchor = null,
            candidateSession = CandidateSession.empty(),
        )

        return state
    }

    /**
     * pinnedPath を新しい boundary に合わせて縮小する。
     *
     * 末尾 lexeme の reading が1文字以上なら末尾文字を削り、1文字なら lexeme ごと削る。
     */
    private fun shrinkPinnedPath(
        current: PinnedPathConstraint,
        newBoundary: Int,
    ): PinnedPathConstraint {
        val path = current.pinnedPath

        if (path.isEmpty()) {
            return PinnedPathConstraint(lockedPrefixBoundary = newBoundary, pinnedPath = emptyList())
        }

        val lastLexeme = path.last()

        val newPath = if (lastLexeme.reading.length <= 1) {
            path.dropLast(1)
        } else {
            val shortenedLexeme = lastLexeme.copy(reading = lastLexeme.reading.dropLast(1))
            path.dropLast(1) + shortenedLexeme
        }

        return PinnedPathConstraint(lockedPrefixBoundary = newBoundary, pinnedPath = newPath)
    }

    /**
     * 変換済み節を revert してかな表示に戻す（converted revert）。
     *
     * graph・selectedPathId・pinnedConstraint・candidateSession をリセットする。source は保持。
     */
    private fun revertConvertedSegment(): CompositionState {
        return revert()
    }

    /**
     * 未変換状態での reading 末尾1かな削除。
     *
     * [CompositionSource.deleteLastKana] で source を更新する。
     */
    private fun deleteReadingTail(): CompositionState {
        markInputChanged()

        val newSource = state.source.deleteLastKana(nextRevision = inputRevision)

        state = state.copy(
            source = newSource,
            graph = CompositionGraph.empty(),
            selectedPathId = null,
        )

        return state
    }

    /**
     * 節選択を [forward] 方向に1つ移動する。
     *
     * segments リストから現在の [ClauseAnchor] を解消し、隣の segment を [ClauseAnchor] に設定する。
     * 末尾を越えたら [clauseAnchor] を null（選択解除）にする。
     * 先頭より前は0のまま停止する。
     */
    fun moveClause(forward: Boolean): CompositionState {
        val currentSegments = state.segments
        val count = currentSegments.size

        if (count == 0) {
            return state
        }

        val currentAnchorIndex = resolveSegmentIndex(currentSegments, state.clauseAnchor)
        val nextIndex = computeNextIndex(currentAnchorIndex, forward, count)

        val newAnchor = if (nextIndex == null) {
            null
        } else {
            val targetSegment = currentSegments[nextIndex]

            buildClauseAnchor(targetSegment)
        }

        state = state.copy(clauseAnchor = newAnchor)

        return state
    }

    /**
     * [anchor] が指す segment の index を [segments] から解決する。
     *
     * anchor が null または一致なしの場合は -1 を返す。
     */
    private fun resolveSegmentIndex(
        segments: List<ShadowSegment>,
        anchor: ClauseAnchor?,
    ): Int {
        if (anchor == null) return -1

        val anchorFrom = anchor.sourceSpan.fromAtomIndex

        return segments.indexOfFirst { it.readingStart == anchorFrom }
    }

    /**
     * [currentIndex] から [forward] 方向の次の index を計算する。
     *
     * 末尾超えは null（選択解除）、先頭超えは 0 で停止。未選択（-1）は末尾または先頭へ飛ぶ。
     */
    private fun computeNextIndex(
        currentIndex: Int,
        forward: Boolean,
        count: Int,
    ): Int? {
        if (currentIndex < 0) {
            return if (forward) null else count - 1
        }

        return if (forward) {
            val nextIndex = currentIndex + 1

            if (nextIndex >= count) null else nextIndex
        } else {
            (currentIndex - 1).coerceAtLeast(0)
        }
    }

    /**
     * [segment] から [ClauseAnchor] を構築する。
     *
     * segment の reading 座標を atom index 相当として [SourceSpan] に変換する。
     * A-3 スコープでは reading 座標をそのまま atom index として代用する
     * （A-5 以降で正確な source 座標マッピングに置き換える）。
     */
    private fun buildClauseAnchor(segment: ShadowSegment): ClauseAnchor {
        val span = SourceSpan(
            fromAtomIndex = segment.readingStart,
            toAtomIndex = segment.readingEnd,
        )

        return ClauseAnchor(
            sourceSpan = span,
            selectedPathId = state.selectedPathId ?: CompositionGraph.PathId(0),
        )
    }

    /**
     * 状態を完全にリセットする。
     */
    fun reset(): CompositionState {
        nextAtomId = 0L
        inputRevision = 0

        state = CompositionState.empty()

        return state
    }

    private fun markInputChanged() {
        inputRevision++
    }
}
