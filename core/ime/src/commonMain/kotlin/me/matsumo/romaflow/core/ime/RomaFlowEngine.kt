package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core の変換 draft を保持するエンジン。
 *
 * IMKInputController インスタンスごとに1つ生成され、[ConversionDraft]（romaji 入力層・変換済 segment・
 * 単語選択）を持つ。入力は 2 レイヤに分かれ、romaji→kana は末尾の pendingRomaji にだけ増分適用し、
 * 確定したかなは readingInput に frozen として積む。kana→kanji は Tab 起動で readingInput 全体を
 * provider（call1）に投入する。この分離により、Tab 変換後に追記しても確定済みのかなが再解釈されない。
 * 公開 API は Swift Export に合わせ String / Int / Boolean / suspend のみを受け渡しする（List は出さない）。
 */
class RomaFlowEngine internal constructor(
    private val conversionProvider: ConversionProvider,
    private val segmenter: Segmenter,
) {

    private val converter = RomajiKanaConverter()

    private var draft = ConversionDraft(
        input = InputBuffer(readingInput = "", pendingRomaji = ""),
        segments = emptyList(),
        selection = Selection.None,
    )

    // 変換済 segments が読みとしてカバーする readingInput の文字数（UTF-16 code unit）。
    // 変換済領域と未変換かな tail の境界で、range=null の B1a ではこの 1 値で境界を管理する。
    private var convertedReadingLength = 0

    // 入力状態のリビジョン。入力を変える操作のたびに増やし、非同期変換の stale 判定に使う。
    private var inputRevision = 0

    // 実行中の変換要求が発行されたときの入力リビジョン。結果適用時にこれが現在値と一致するか確認する。
    private var pendingConversionRevision = -1

    /** Swift Export / 本番経路向けに既定の AI provider と momiji segmenter を使う constructor。 */
    constructor() : this(defaultConversionProvider(), MomijiSegmenter())

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

        deleteFromEnd()

        return preeditText()
    }

    suspend fun convert(): String {
        // サスペンド前に main で pendingRomaji を確定し、Tab の境界を固定する（lone n→ん 等）。
        // segment への反映は applyConversion で main から行い、競合を避ける（#15 の二段構え）。
        val finalized = converter.finalize(draft.input.pendingRomaji)
        val readingInput = draft.input.readingInput + finalized

        if (readingInput.isEmpty()) {
            return ""
        }

        draft = draft.copy(input = InputBuffer(readingInput, ""))
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
        convertedReadingLength = draft.input.readingInput.length

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

        // 変換済なら segments だけ破棄して打った通りのかな（readingInput）へ戻す。未変換なら全体を破棄する。
        if (draft.segments.isNotEmpty()) {
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
        return draft.segments.isNotEmpty()
    }

    private fun deletePendingTail() {
        val shortened = draft.input.pendingRomaji.dropLast(1)

        draft = draft.copy(input = draft.input.copy(pendingRomaji = shortened))
    }

    private fun deleteFromEnd() {
        val hasUnconvertedTail = draft.input.readingInput.length > convertedReadingLength

        // 末尾が未変換かな tail ならその 1 かなを削る。tail が無く変換済なら、その変換を打った通りのかなへ戻す
        // （per-segment 削除は range が必要なため B1b 以降）。どちらでもなければ readingInput 末尾を削る。
        if (hasUnconvertedTail) {
            deleteReadingInputTail()

            return
        }

        if (draft.segments.isNotEmpty()) {
            revertConversion()

            return
        }

        if (draft.input.readingInput.isNotEmpty()) {
            deleteReadingInputTail()
        }
    }

    private fun deleteReadingInputTail() {
        val shortened = draft.input.readingInput.dropLast(1)

        draft = draft.copy(input = draft.input.copy(readingInput = shortened), selection = Selection.None)
    }

    private fun revertConversion() {
        draft = draft.copy(segments = emptyList(), selection = Selection.None)
        convertedReadingLength = 0
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

        if (tokens.isEmpty()) {
            return listOf(toConvertedSegment(SegmentToken(result, result)))
        }

        return tokens.map(::toConvertedSegment)
    }

    private fun toConvertedSegment(token: SegmentToken): Segment {
        return Segment(
            surface = token.surface,
            reading = token.reading,
            range = null,
            status = SegmentStatus.Converted,
            candidates = emptyList(),
        )
    }

    private fun moveSelection(forward: Boolean) {
        val count = displaySegments().size

        if (count == 0) {
            return
        }

        val nextIndex = nextSelectionIndex(forward, count)

        draft = draft.copy(selection = Selection.Word(nextIndex))
    }

    private fun nextSelectionIndex(forward: Boolean, count: Int): Int {
        val current = selectedSegmentIndex()

        // 未選択からは → で先頭、← で末尾へ。選択中は範囲内で 1 つずつ動かす。
        if (current < 0) {
            return if (forward) 0 else count - 1
        }

        val candidate = if (forward) current + 1 else current - 1

        return candidate.coerceIn(0, count - 1)
    }

    private fun displaySegments(): List<Segment> {
        val tail = unconvertedTail()

        if (tail.isEmpty()) {
            return draft.segments
        }

        val tailSegment = Segment(tail, tail, null, SegmentStatus.Unconverted, emptyList())

        return draft.segments + tailSegment
    }

    private fun segmentAt(index: Int): Segment? {
        return displaySegments().getOrNull(index)
    }

    private fun unconvertedTail(): String {
        val readingInput = draft.input.readingInput
        val boundary = convertedReadingLength.coerceIn(0, readingInput.length)

        return readingInput.substring(boundary)
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }

    private fun markInputChanged() {
        inputRevision++
    }

    private fun clearState() {
        draft = ConversionDraft(InputBuffer("", ""), emptyList(), Selection.None)
        convertedReadingLength = 0
    }
}
