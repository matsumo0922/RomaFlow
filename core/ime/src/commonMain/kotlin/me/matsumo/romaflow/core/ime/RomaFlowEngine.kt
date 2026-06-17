package me.matsumo.romaflow.core.ime

import kotlinx.serialization.json.Json

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

    // 候補窓セッションのリビジョン。inputRevision とは独立に call2 の stale を判定する。
    // 選択変更・入力変更・session 終了操作で増やす（previewCandidate の窓内ナビでは増やさない）。
    private var candidateRequestId = 0

    // 実行中の call2 要求が発行されたときの candidateRequestId。結果適用時にこれが現在値と一致するか確認する。
    private var pendingCandidateRequestId = -1

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

        invalidateCandidateSession()

        pendingConversionRevision = inputRevision

        val prefixEnd = lockedPrefixEnd()
        val prefixContext = lockedPrefixContext()
        val tailReading = readingInput.substring(prefixEnd.coerceIn(0, readingInput.length))

        // 全 segment が Locked なら変換対象が無い。applyConversion 側で no-op になる空文字を返す。
        if (tailReading.isEmpty()) {
            return ""
        }

        val request = ConversionRequest(tailReading, prefixContext)

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

        val prefixSegments = lockedPrefixSegments()
        val prefixEnd = lockedPrefixEnd()
        val tailReading = draft.input.readingInput.substring(prefixEnd.coerceIn(0, draft.input.readingInput.length))
        val tailSegments = buildTailSegments(result, tailReading, prefixEnd)

        draft = draft.copy(segments = prefixSegments + tailSegments, selection = Selection.None)

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

        for (segment in displayProjection()) {
            builder.append(segment.surface)
        }

        builder.append(draft.input.pendingRomaji)

        return builder.toString()
    }

    fun segmentCount(): Int {
        return displayProjection().size
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
        return selectedSegmentIndexOrNull() ?: -1
    }

    /** 選択中の文節の候補数。選択が無い・未変換対象なら 0。 */
    fun candidateCount(): Int {
        return currentCandidates().size
    }

    /** 選択中の文節の [index] 番目の候補文字列。範囲外なら空文字。 */
    fun candidateText(index: Int): String {
        return currentCandidates().getOrNull(index).orEmpty()
    }

    /**
     * 候補窓で preview 中の候補 [text] を反映し、更新後の preedit を返す。
     *
     * draft の segment は破壊せず、selection を [Selection.Candidate] にして display projection 側で
     * surface を差し替える。選択が文節を指していなければ no-op で現在の preedit を返す。
     */
    fun previewCandidate(text: String): String {
        val segmentIndex = selectedSegmentIndexOrNull()

        if (segmentIndex == null) {
            return preeditText()
        }

        draft = draft.copy(selection = Selection.Candidate(segmentIndex, text))

        return preeditText()
    }

    /**
     * 選択中の文節へ候補 [text] を適用し、先頭からその文節までを prefix lock して preedit を返す。
     *
     * commit はせず draft の更新だけ行う（lock 再変換の consume は後続タスク）。選択が draft.segments の
     * 範囲外・None なら no-op。lock 範囲は 0..max(現在の最大 Locked index, 選択 index) で、確定済み prefix を
     * 壊さず途中の文節だけ差し替えられるようにする。
     */
    fun confirmCandidate(text: String): String {
        invalidateCandidateSession()

        val segmentIndex = selectedSegmentIndexOrNull() ?: return preeditText()

        if (segmentIndex !in draft.segments.indices) {
            return preeditText()
        }

        applyCandidateAndLockPrefix(segmentIndex, text)

        draft = draft.copy(selection = Selection.Word(segmentIndex))

        return preeditText()
    }

    /**
     * 候補窓を閉じ、preview を破棄して [Selection.Word] へ戻し preedit を返す。
     *
     * [Selection.Candidate] 中のみ Word に戻す（surface は projection で元へ戻る）。それ以外は据え置く。
     */
    fun closeCandidates(): String {
        invalidateCandidateSession()

        val selection = draft.selection

        if (selection is Selection.Candidate) {
            draft = draft.copy(selection = Selection.Word(selection.segmentIndex))
        }

        return preeditText()
    }

    /**
     * 選択中の文節について call2（LLM 単語候補）を provider へ要求し、生の JSON 文字列を返す。
     *
     * 選択が draft.segments の範囲内（変換済 / lock 済の実 segment）でなければ no-op で空文字を返す。
     * 発行時の [candidateRequestId] を [pendingCandidateRequestId] に記録し、結果適用時の stale 判定に使う。
     * パース・正規化・自明候補とのマージは [applyWordCandidates] が担うため、ここでは生の戻り値をそのまま返す。
     * provider は失敗時に空文字を返す契約なので、追加の try/catch は行わない。
     */
    suspend fun requestWordCandidates(): String {
        val segmentIndex = selectedSegmentIndexOrNull()
        val isInRange = segmentIndex != null && segmentIndex in draft.segments.indices

        if (!isInRange) {
            return ""
        }

        pendingCandidateRequestId = candidateRequestId

        val segment = draft.segments[requireNotNull(segmentIndex)]
        val request = WordCandidateRequest(segment.reading, convertedContext())

        return conversionProvider.candidates(request)
    }

    /**
     * call2 の生 JSON [result] をパース・正規化し、選択中の文節の候補 [Segment.candidates] へ格納して preedit を返す。
     *
     * 発行時から [candidateRequestId] が変わっていれば（窓を閉じた・別 segment へ移った・入力が変わった等）
     * stale として no-op。選択が範囲外・[result] が空・パース失敗の場合も no-op で現在の preedit を返す。
     * 格納する候補は制御文字を除去し trim・空除外・重複除外した上で、[candidateCount] / [candidateText] が
     * 自明候補とマージして表示する。
     */
    fun applyWordCandidates(result: String): String {
        val isStale = candidateRequestId != pendingCandidateRequestId

        if (isStale) {
            return preeditText()
        }

        val segmentIndex = selectedSegmentIndexOrNull()
        val isInRange = segmentIndex != null && segmentIndex in draft.segments.indices

        if (!isInRange || result.isEmpty()) {
            return preeditText()
        }

        val parsed = parseCandidates(result) ?: return preeditText()
        val normalized = normalizeCandidates(parsed)

        storeCandidates(requireNotNull(segmentIndex), normalized)

        return preeditText()
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
        val display = displayProjection()

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

    // tail（Locked prefix を除いた未確定読み）の変換結果を segment 化する。
    // [readingForAlignment] は tail の読み、[offset] は readingInput 上での tail 開始位置。
    // align は tail 内の相対 range を返すので、各 range を +offset して readingInput 全体の絶対座標へ直す。
    // prefix run が空のときは offset=0・readingForAlignment=全文となり従来の全文変換と同一になる。
    private fun buildTailSegments(
        result: String,
        readingForAlignment: String,
        offset: Int,
    ): List<Segment> {
        val tokens = segmenter.segment(result)
        val effectiveTokens = tokens.ifEmpty { listOf(SegmentToken(result, result)) }
        val aligned = aligner.align(readingForAlignment, effectiveTokens)

        return aligned.map { toConvertedSegment(it, offset) }
    }

    private fun toConvertedSegment(aligned: AlignedSegment, offset: Int): Segment {
        return Segment(
            surface = aligned.token.surface,
            reading = aligned.token.reading,
            range = offsetRange(aligned.range, offset),
            status = SegmentStatus.Converted,
            candidates = emptyList(),
        )
    }

    private fun offsetRange(range: TextRange, offset: Int): TextRange {
        if (offset == 0) {
            return range
        }

        return range.copy(
            startInclusive = range.startInclusive + offset,
            endExclusive = range.endExclusive + offset,
        )
    }

    private fun moveSelection(forward: Boolean) {
        invalidateCandidateSession()

        val count = displayProjection().size

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

    // Word / Candidate のどちらでも選択中の segment index を返す（B1c ハイライトを Candidate 中も継続させる）。
    private fun selectedSegmentIndexOrNull(): Int? {
        return when (val selection = draft.selection) {
            is Selection.Word -> selection.index
            is Selection.Candidate -> selection.segmentIndex
            Selection.None -> null
        }
    }

    // 選択中の文節へ候補 surface を適用し、0..max(現在の最大 Locked index, 選択 index) を Locked にする。
    private fun applyCandidateAndLockPrefix(segmentIndex: Int, text: String) {
        val maxLockedIndex = draft.segments.indexOfLast { it.status == SegmentStatus.Locked }
        val lockEnd = maxOf(maxLockedIndex, segmentIndex)
        val updated = draft.segments.mapIndexed { index, segment ->
            lockSegmentIfWithinPrefix(segment, index, segmentIndex, text, lockEnd)
        }

        draft = draft.copy(segments = updated)
    }

    private fun lockSegmentIfWithinPrefix(
        segment: Segment,
        index: Int,
        targetIndex: Int,
        text: String,
        lockEnd: Int,
    ): Segment {
        val surface = if (index == targetIndex) text else segment.surface
        val status = if (index <= lockEnd) SegmentStatus.Locked else segment.status

        return segment.copy(surface = surface, status = status)
    }

    // 先頭から連続する Locked segment（prefix run）。Option A で lock は常に左から育つので 0..k の連続列になる。
    private fun lockedPrefixSegments(): List<Segment> {
        return draft.segments.takeWhile { it.status == SegmentStatus.Locked }
    }

    // prefix run が readingInput 上でカバーする末尾位置。空なら 0、各 range の最大 endExclusive を採る（null は無視）。
    private fun lockedPrefixEnd(): Int {
        val prefix = lockedPrefixSegments()

        if (prefix.isEmpty()) {
            return 0
        }

        return prefix.maxOfOrNull { it.range?.endExclusive ?: 0 } ?: 0
    }

    // prefix run の surface 連結。再変換時に provider へ渡す前方文脈になる。
    private fun lockedPrefixContext(): String {
        val builder = StringBuilder()

        for (segment in lockedPrefixSegments()) {
            builder.append(segment.surface)
        }

        return builder.toString()
    }

    // 選択中の文節に対する候補列（自明候補 + LLM 候補）。未選択 / 未変換対象なら空。
    private fun currentCandidates(): List<String> {
        val segmentIndex = selectedSegmentIndexOrNull() ?: return emptyList()
        val segment = draft.segments.getOrNull(segmentIndex) ?: return emptyList()

        if (segment.status == SegmentStatus.Unconverted) {
            return emptyList()
        }

        val merged = obviousCandidates(segment) + segment.candidates

        return merged.filter { it.isNotEmpty() }.distinct()
    }

    // call2 の文脈に渡す変換済み全文。preview は反映せず draft.segments の実 surface を連結する。
    private fun convertedContext(): String {
        val builder = StringBuilder()

        for (segment in draft.segments) {
            builder.append(segment.surface)
        }

        return builder.toString()
    }

    // 生 JSON から候補列を取り出す。空・パース失敗は null（呼び出し側で据え置き）。throw しない。
    private fun parseCandidates(result: String): List<String>? {
        val parsed = runCatching { candidateJson.decodeFromString<WordCandidatePayload>(result) }

        return parsed.getOrNull()?.candidates
    }

    // 制御文字（CR/LF/tab 等）を除去 → trim → 空除外 → 重複除外する。
    private fun normalizeCandidates(candidates: List<String>): List<String> {
        return candidates.map(::stripControlChars)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun stripControlChars(candidate: String): String {
        return candidate.filterNot { it.isISOControl() }
    }

    // 正規化済み LLM 候補を選択 segment の candidates へ格納する（自明候補とのマージは currentCandidates が担う）。
    private fun storeCandidates(segmentIndex: Int, candidates: List<String>) {
        val updated = draft.segments.toMutableList()

        updated[segmentIndex] = updated[segmentIndex].copy(candidates = candidates)

        draft = draft.copy(segments = updated)
    }

    // 元文節から決定的に作る自明候補: surface（漢字等） / reading（ひらがな） / reading のカタカナ化。重複・空は後段で除外。
    private fun obviousCandidates(segment: Segment): List<String> {
        return listOf(segment.surface, segment.reading, toKatakana(segment.reading))
    }

    // ひらがな（U+3041..U+3096）を +0x60 でカタカナへ決定的に写像する。長音符など範囲外はそのまま残す。
    private fun toKatakana(reading: String): String {
        val builder = StringBuilder(reading.length)

        for (character in reading) {
            builder.append(katakanaOf(character))
        }

        return builder.toString()
    }

    private fun katakanaOf(character: Char): Char {
        val isHiragana = character.code in HIRAGANA_START..HIRAGANA_END

        return if (isHiragana) character + KATAKANA_OFFSET else character
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

    // preedit / segmentText / 選択ハイライトを導出する唯一の表示用 segment 列。
    // candidate preview を反映した base segments に、末尾の未変換 tail を 1 つ足して構成する。
    // preedit も segmentText もこの projection から導出するため「Σ segmentText == preedit（pendingRomaji 除く）」が常に成立する。
    private fun displayProjection(): List<Segment> {
        val baseSegments = projectedBaseSegments()
        val tail = unconvertedTail()

        if (tail.isEmpty()) {
            return baseSegments
        }

        val start = convertedEnd()
        val tailRange = TextRange(start, draft.input.readingInput.length, EXACT_MATCH_CONFIDENCE)
        val tailSegment = Segment(tail, tail, tailRange, SegmentStatus.Unconverted, emptyList())

        return baseSegments + tailSegment
    }

    // candidate preview 中なら選択 segment の surface だけを previewSurface に差し替えた segments を返す。
    // reading / range / status は元のまま保ち、draft は破壊しない（preview の非破壊性）。
    private fun projectedBaseSegments(): List<Segment> {
        val selection = draft.selection

        if (selection !is Selection.Candidate || selection.previewSurface.isEmpty()) {
            return draft.segments
        }

        val targetIndex = selection.segmentIndex

        if (targetIndex !in draft.segments.indices) {
            return draft.segments
        }

        val projected = draft.segments.toMutableList()

        projected[targetIndex] = projected[targetIndex].copy(surface = selection.previewSurface)

        return projected
    }

    private fun displaySegmentCountFor(segments: List<Segment>, readingInput: String): Int {
        val end = convertedEndOf(segments)
        val hasTail = end < readingInput.length

        return segments.size + if (hasTail) 1 else 0
    }

    private fun segmentAt(index: Int): Segment? {
        return displayProjection().getOrNull(index)
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

        invalidateCandidateSession()
    }

    // 候補窓セッションを無効化し、進行中の call2 結果を stale として破棄させる（Finding B）。
    private fun invalidateCandidateSession() {
        candidateRequestId++
    }

    private fun clearState() {
        draft = ConversionDraft(InputBuffer("", ""), emptyList(), Selection.None)
    }

    private companion object {
        /** call2 の生 JSON を寛容にパースする Json。未知キーは無視する。 */
        val candidateJson = Json { ignoreUnknownKeys = true }

        /** per-segment revert を許可する confidence の下限（完全一致）。 */
        const val EXACT_MATCH_CONFIDENCE = 1f

        /** ひらがなブロック先頭（U+3041 ぁ）。カタカナ化の写像範囲の下限。 */
        const val HIRAGANA_START = 0x3041

        /** ひらがなブロック末尾（U+3096 ゖ）。カタカナ化の写像範囲の上限。 */
        const val HIRAGANA_END = 0x3096

        /** ひらがな→カタカナの code point オフセット（U+3041→U+30A1）。 */
        const val KATAKANA_OFFSET = 0x60
    }
}
