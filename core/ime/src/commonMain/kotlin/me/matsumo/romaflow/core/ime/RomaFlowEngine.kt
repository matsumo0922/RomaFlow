package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core の変換 draft を保持するエンジン。
 *
 * IMKInputController インスタンスごとに1つ生成され、[ConversionDraft]（romaji 入力層・変換済 segment・
 * 単語選択）を持つ。入力は 2 レイヤに分かれ、romaji→kana は末尾の pendingRomaji にだけ増分適用し、
 * 確定したかなは readingInput に frozen として積む。kana→kanji は Tab 起動で readingInput 全体を
 * provider（call1）に投入する。この分離により、Tab 変換後に追記しても確定済みのかなが再解釈されない。
 * 変換結果の各 segment は [ReadingAligner] で readingInput 上の [TextRange] に対応付け、per-segment の
 * revert・削除を range 単位で扱う。公開 API は Swift Export に合わせ String / Int / Boolean / suspend のみを
 * 受け渡しする（List は出さない）。
 */
class RomaFlowEngine internal constructor(
    private val conversionProvider: ConversionProvider,
    private val segmenter: Segmenter,
    private val aligner: ReadingAligner,
) {

    private val converter = RomajiKanaConverter()

    private var draft = ConversionDraft(
        input = InputBuffer(readingInput = "", pendingRomaji = ""),
        segments = emptyList(),
        selection = Selection.None,
    )

    // 入力状態のリビジョン。入力を変える操作のたびに増やし、非同期変換の stale 判定に使う。
    private var inputRevision = 0

    // 実行中の変換要求が発行されたときの入力リビジョン。結果適用時にこれが現在値と一致するか確認する。
    private var pendingConversionRevision = -1

    /** Swift Export / 本番経路向けに既定の AI provider・momiji segmenter・DP aligner を使う constructor。 */
    constructor() : this(defaultConversionProvider(), MomijiSegmenter(), DpReadingAligner())

    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    fun inputRomaji(text: String): String {
        markInputChanged()

        val composition = converter.appendRomaji(draft.input.pendingRomaji, text)
        val readingInput = draft.input.readingInput + composition.committedKanaDelta

        draft = draft.copy(
            input = InputBuffer(readingInput, composition.pendingRomaji),
            selection = Selection.None,
        )

        return preeditText()
    }

    fun deleteBackward(): String {
        markInputChanged()

        if (draft.input.pendingRomaji.isNotEmpty()) {
            deletePendingTail()

            return preeditText()
        }

        deleteFromTargetSegment()

        return preeditText()
    }

    fun finalizePendingRomaji(): String {
        // Tab の起点で pendingRomaji を確定かなへ移し、境界を固定する（lone n→ん 等）。
        // 非同期変換が失敗・空・キャンセルでも表示中の marked text と commit 内容を一致させるため、
        // Swift 側はこの戻り値を即 setMarkedText して await 中のかな表示を確定後のかなへ揃える。
        applyPendingFinalization()

        return preeditText()
    }

    suspend fun convert(): String {
        // サスペンド前に main で pendingRomaji を確定し、Tab の境界を固定する（lone n→ん 等）。
        // segment への反映は applyConversion で main から行い、競合を避ける（#15 の二段構え）。
        applyPendingFinalization()

        val readingInput = draft.input.readingInput

        if (readingInput.isEmpty()) {
            return ""
        }

        pendingConversionRevision = inputRevision

        val request = ConversionRequest(readingInput, emptyList())

        return conversionProvider.convert(request)
    }

    fun applyConversion(result: String): String {
        // 失敗(空文字)・入力消失・要求発行後に入力が変わった(stale)場合は据え置く。
        // stale 判定により、入力モード切替や追加入力で取り消したあとに遅れて返った結果の復活を防ぐ。
        val isStale = inputRevision != pendingConversionRevision
        val isUnusable = result.isEmpty() || draft.input.readingInput.isEmpty()

        if (isUnusable || isStale) {
            return ""
        }

        draft = draft.copy(segments = buildSegments(result), selection = Selection.None)

        return preeditText()
    }

    fun commit(): String {
        markInputChanged()

        val committed = committedText()
        clearState()

        return committed
    }

    fun cancel(): String {
        markInputChanged()

        // 変換済 segment が残るなら segments だけ破棄して打った通りのかな（readingInput）へ戻す。
        // per-segment revert で全て未変換かなに戻った場合は変換済が無いので、一度の Esc で全体を破棄する。
        if (isConverted()) {
            revertConversion()

            return preeditText()
        }

        clearState()

        return ""
    }

    fun moveSelectionLeft(): String {
        moveSelection(forward = false)

        return preeditText()
    }

    fun moveSelectionRight(): String {
        moveSelection(forward = true)

        return preeditText()
    }

    fun preeditText(): String {
        val builder = StringBuilder()

        for (segment in draft.segments) {
            builder.append(segment.surface)
        }

        builder.append(unconvertedTail())
        builder.append(draft.input.pendingRomaji)

        return builder.toString()
    }

    fun segmentCount(): Int {
        return displaySegments().size
    }

    fun segmentText(index: Int): String {
        return segmentAt(index)?.surface.orEmpty()
    }

    fun segmentReading(index: Int): String {
        return segmentAt(index)?.reading.orEmpty()
    }

    fun segmentStatus(index: Int): String {
        return segmentAt(index)?.status?.name.orEmpty()
    }

    fun selectedSegmentIndex(): Int {
        val selection = draft.selection

        return if (selection is Selection.Word) selection.index else -1
    }

    fun hasComposition(): Boolean {
        val input = draft.input
        val hasInput = input.readingInput.isNotEmpty() || input.pendingRomaji.isNotEmpty()

        return hasInput || draft.segments.isNotEmpty()
    }

    fun isConverted(): Boolean {
        return draft.segments.any { it.status != SegmentStatus.Unconverted }
    }

    private fun applyPendingFinalization() {
        val finalized = converter.finalize(draft.input.pendingRomaji)
        val readingInput = draft.input.readingInput + finalized

        draft = draft.copy(input = InputBuffer(readingInput, ""))
    }

    private fun deletePendingTail() {
        val shortened = draft.input.pendingRomaji.dropLast(1)

        draft = draft.copy(input = draft.input.copy(pendingRomaji = shortened))
    }

    private fun deleteFromTargetSegment() {
        val display = displaySegments()

        if (display.isEmpty()) {
            return
        }

        val targetIndex = backspaceTargetIndex(display.size)

        applyBackspaceOnSegment(display[targetIndex])
    }

    // 選択中ならその segment、未選択なら末尾 segment を backspace 対象にする（優先順位 2/3/4）。
    private fun backspaceTargetIndex(count: Int): Int {
        val selection = draft.selection

        if (selection is Selection.Word) {
            return selection.index.coerceIn(0, count - 1)
        }

        return count - 1
    }

    private fun applyBackspaceOnSegment(segment: Segment) {
        // 未変換は末尾 1 かな削除、変換済は exact なら かなへ revert・不一致なら全体 revert。
        if (segment.status == SegmentStatus.Unconverted) {
            deleteSegmentTrailingKana(segment)

            return
        }

        revertSegmentOrFallback(segment)
    }

    private fun revertSegmentOrFallback(segment: Segment) {
        val range = segment.range
        val isExactMatch = range != null && range.confidence >= EXACT_MATCH_CONFIDENCE

        // 完全一致時のみ per-segment で打った通りのかなへ戻す。訂正済み（不一致）は range が曖昧なので全体 revert。
        if (!isExactMatch) {
            revertConversion()

            return
        }

        revertSegmentToReading(segment, range)
    }

    private fun revertSegmentToReading(segment: Segment, range: TextRange) {
        val targetIndex = draft.segments.indexOf(segment)

        if (targetIndex < 0) {
            return
        }

        val kana = draft.input.readingInput.substring(range.startInclusive, range.endExclusive)
        val reverted = segment.copy(surface = kana, reading = kana, status = SegmentStatus.Unconverted)
        val updated = draft.segments.toMutableList()

        updated[targetIndex] = reverted

        draft = draft.copy(segments = updated)
    }

    private fun deleteSegmentTrailingKana(segment: Segment) {
        val range = segment.range ?: return

        if (range.endExclusive <= range.startInclusive) {
            return
        }

        deleteReadingInputCharAt(range.endExclusive - 1)
    }

    // readingInput の [position] を1文字削り、各 segment の range を追従させる（中間削除では後続が左へ詰まる）。
    private fun deleteReadingInputCharAt(position: Int) {
        val readingInput = draft.input.readingInput

        if (position !in readingInput.indices) {
            return
        }

        val newReadingInput = readingInput.removeRange(position, position + 1)
        val shiftedSegments = draft.segments.mapNotNull { shiftSegmentForDeletion(it, position, newReadingInput) }
        val displayCount = displaySegmentCountFor(shiftedSegments, newReadingInput)

        draft = draft.copy(
            input = draft.input.copy(readingInput = newReadingInput),
            segments = shiftedSegments,
            selection = clampSelection(draft.selection, displayCount),
        )
    }

    private fun shiftSegmentForDeletion(segment: Segment, position: Int, newReadingInput: String): Segment? {
        val range = segment.range ?: return segment

        return when {
            range.endExclusive <= position -> segment
            range.startInclusive > position -> shiftSegmentRange(segment, range)
            else -> shrinkSegmentEnd(segment, range, newReadingInput)
        }
    }

    private fun shiftSegmentRange(segment: Segment, range: TextRange): Segment {
        val shifted = range.copy(
            startInclusive = range.startInclusive - 1,
            endExclusive = range.endExclusive - 1,
        )

        return segment.copy(range = shifted)
    }

    private fun shrinkSegmentEnd(segment: Segment, range: TextRange, newReadingInput: String): Segment? {
        val newEnd = range.endExclusive - 1

        if (newEnd <= range.startInclusive) {
            return null
        }

        val shrunkRange = range.copy(endExclusive = newEnd)
        val text = newReadingInput.substring(range.startInclusive, newEnd)

        return segment.copy(surface = text, reading = text, range = shrunkRange)
    }

    private fun revertConversion() {
        draft = draft.copy(segments = emptyList(), selection = Selection.None)
    }

    private fun committedText(): String {
        val builder = StringBuilder()

        for (segment in draft.segments) {
            builder.append(segment.surface)
        }

        builder.append(unconvertedTail())
        builder.append(converter.finalize(draft.input.pendingRomaji))

        return builder.toString()
    }

    private fun buildSegments(result: String): List<Segment> {
        val tokens = segmenter.segment(result)
        val effectiveTokens = tokens.ifEmpty { listOf(SegmentToken(result, result)) }
        val aligned = aligner.align(draft.input.readingInput, effectiveTokens)

        return aligned.map(::toConvertedSegment)
    }

    private fun toConvertedSegment(aligned: AlignedSegment): Segment {
        return Segment(
            surface = aligned.token.surface,
            reading = aligned.token.reading,
            range = aligned.range,
            status = SegmentStatus.Converted,
            candidates = emptyList(),
        )
    }

    private fun moveSelection(forward: Boolean) {
        val count = displaySegments().size

        if (count == 0) {
            return
        }

        draft = draft.copy(selection = nextSelection(forward, count))
    }

    // 入力直後のカーソルは末尾にあるため ← で末尾 clause から文節選択へ入り、→ は右に clause が無いので据え置く。
    // 選択中は ← で左の clause（先頭で停止）、→ で右の clause へ進み、末尾を越えたら選択解除してカーソルを末尾へ戻す。
    private fun nextSelection(forward: Boolean, count: Int): Selection {
        val current = selectedSegmentIndex()

        if (current < 0) {
            return if (forward) Selection.None else Selection.Word(count - 1)
        }

        if (!forward) {
            return Selection.Word((current - 1).coerceAtLeast(0))
        }

        val next = current + 1

        return if (next > count - 1) Selection.None else Selection.Word(next)
    }

    private fun clampSelection(selection: Selection, displayCount: Int): Selection {
        if (selection !is Selection.Word) {
            return selection
        }

        if (displayCount == 0) {
            return Selection.None
        }

        return Selection.Word(selection.index.coerceIn(0, displayCount - 1))
    }

    private fun displaySegments(): List<Segment> {
        val tail = unconvertedTail()

        if (tail.isEmpty()) {
            return draft.segments
        }

        val start = convertedEnd()
        val tailRange = TextRange(start, draft.input.readingInput.length, EXACT_MATCH_CONFIDENCE)
        val tailSegment = Segment(tail, tail, tailRange, SegmentStatus.Unconverted, emptyList())

        return draft.segments + tailSegment
    }

    private fun displaySegmentCountFor(segments: List<Segment>, readingInput: String): Int {
        val end = convertedEndOf(segments)
        val hasTail = end < readingInput.length

        return segments.size + if (hasTail) 1 else 0
    }

    private fun segmentAt(index: Int): Segment? {
        return displaySegments().getOrNull(index)
    }

    private fun unconvertedTail(): String {
        val readingInput = draft.input.readingInput
        val boundary = convertedEnd().coerceIn(0, readingInput.length)

        return readingInput.substring(boundary)
    }

    // 変換済 segment 群が readingInput 上でカバーする末尾位置。tail との境界に使う。
    private fun convertedEnd(): Int {
        return convertedEndOf(draft.segments)
    }

    private fun convertedEndOf(segments: List<Segment>): Int {
        return segments.maxOfOrNull { it.range?.endExclusive ?: 0 } ?: 0
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }

    private fun markInputChanged() {
        inputRevision++
    }

    private fun clearState() {
        draft = ConversionDraft(InputBuffer("", ""), emptyList(), Selection.None)
    }

    private companion object {
        /** per-segment revert を許可する confidence の下限（完全一致）。 */
        const val EXACT_MATCH_CONFIDENCE = 1f
    }
}
