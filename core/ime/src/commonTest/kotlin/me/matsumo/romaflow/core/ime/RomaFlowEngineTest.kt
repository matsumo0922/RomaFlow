package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RomaFlowEngine] の変換 draft（増分入力・全文変換・混在 preedit・単語選択・per-segment revert・backspace）の
 * 振る舞いを検証するテスト。
 *
 * テストは macosArm64 上で実行する。Android ターゲットは host unit test を有効化していないため
 * 本テストは実行されず、依存がコンパイル時に解決されることのみ確認する。
 * 変換系は決定的な [FakeConversionProvider]、分割は注入する [Segmenter]、対応付けは [FakeAligner] を使う。
 */
class RomaFlowEngineTest {

    @Test
    fun inputRomaji_accumulatesAndConvertsToKana() {
        val engine = newEngine()

        assertEquals("か", engine.inputRomaji("ka"))
        assertEquals("かき", engine.inputRomaji("ki"))
        assertEquals("かきく", engine.inputRomaji("ku"))
    }

    @Test
    fun inputRomaji_handlesSyllabicNUsingImeRule() {
        val engine = newEngine()

        assertEquals("こんにちは", engine.inputRomaji("konnnitiha"))
    }

    @Test
    fun inputRomaji_convertsPunctuation() {
        val engine = newEngine()

        assertEquals("。", engine.inputRomaji("."))
    }

    @Test
    fun inputRomaji_keepsUppercaseWordAsLatin() {
        val engine = newEngine()

        assertEquals("Tokyo です", engine.inputRomaji("Tokyo desu"))
    }

    @Test
    fun inputRomaji_defersTrailingNWhileTyping() {
        val engine = newEngine()

        assertEquals("おn", engine.inputRomaji("on"))
    }

    @Test
    fun deleteBackward_removesLastKana() {
        val engine = newEngine()
        engine.inputRomaji("kai")

        assertEquals("か", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_removesPendingRomajiFirst() {
        val engine = newEngine()
        engine.inputRomaji("ky")

        // pendingRomaji 非空時はまず pending の末尾 1 code unit を削る（優先順位 1）。
        assertEquals("k", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_onEmptyBufferStaysEmpty() {
        val engine = newEngine()

        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun commit_resolvesTrailingNAndClearsBuffer() {
        val engine = newEngine()
        engine.inputRomaji("on")

        assertEquals("おん", engine.commit())
        assertFalse(engine.hasComposition())
        assertEquals("あ", engine.inputRomaji("a"))
    }

    @Test
    fun commit_returnsKanaAndClearsBuffer() {
        val engine = newEngine()
        engine.inputRomaji("nihon")

        assertEquals("にほん", engine.commit())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun cancel_onUnconvertedClearsBuffer() {
        val engine = newEngine()
        engine.inputRomaji("kyou")

        assertEquals("", engine.cancel())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun hasComposition_reflectsBufferState() {
        val engine = newEngine()

        assertFalse(engine.hasComposition())

        engine.inputRomaji("a")

        assertTrue(engine.hasComposition())
    }

    @Test
    fun finalizePendingRomaji_resolvesTrailingNAndMatchesCommit() {
        val engine = newEngine()
        engine.inputRomaji("on")

        // Tab 起点の finalize で末尾 n を ん へ解決し、表示用 preedit を返す（おn→おん）。
        assertEquals("おん", engine.finalizePendingRomaji())

        // finalize 後（=変換失敗・キャンセル相当）の commit は表示と一致する。
        assertEquals("おん", engine.commit())
    }

    @Test
    fun convert_runsProviderAndEntersConvertedState() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")

        assertEquals("日本語", engine.convertAndApply())
        assertTrue(engine.isConverted())
        assertEquals(3, engine.segmentCount())
        assertEquals("日", engine.segmentText(0))
        assertEquals("Converted", engine.segmentStatus(0))
    }

    @Test
    fun convert_onEmptyBufferReturnsEmptyAndStaysUnconverted() = runTest {
        val engine = newEngine()

        assertEquals("", engine.convertAndApply())
        assertFalse(engine.isConverted())
    }

    @Test
    fun convert_sendsEmptyPrefixContextAndFullReadingWhenNoLock() = runTest {
        val recording = RecordingConversionProvider()
        val engine = RomaFlowEngine(recording, FakeSegmenter(), FakeAligner())
        engine.inputRomaji("nihongo")

        engine.convert()

        val request = requireNotNull(recording.lastRequest)
        assertEquals("にほんご", request.readingInput)
        assertEquals("", request.prefixContext)
    }

    @Test
    fun fakeProvider_candidatesAreDeterministicForKnownReading() = runTest {
        val provider = FakeConversionProvider()

        val candidatesJson = provider.candidates(WordCandidateRequest(reading = "てんき", context = ""))

        assertEquals("""{"candidates":["天気","転機","てんき","テンキ"]}""", candidatesJson)
    }

    @Test
    fun fakeProvider_candidatesAreEmptyForUnknownReading() = runTest {
        val provider = FakeConversionProvider()

        assertEquals("", provider.candidates(WordCandidateRequest(reading = "ぬるぽ", context = "")))
        assertEquals("", provider.candidates(WordCandidateRequest(reading = "  ", context = "")))
    }

    @Test
    fun applyConversion_ignoresEmptyResultAndStaysUnconverted() {
        val engine = newEngine()
        engine.inputRomaji("nihongo")

        assertEquals("", engine.applyConversion(""))
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun applyConversion_rejectsStaleResultWhenInputChangedAfterRequest() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        val result = engine.convert()

        engine.inputRomaji("ka")

        assertEquals("", engine.applyConversion(result))
        assertFalse(engine.isConverted())
    }

    @Test
    fun commit_returnsConvertedTextWhenConverted() = runTest {
        val engine = newEngine()
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        assertEquals("漢字", engine.commit())
        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
    }

    @Test
    fun typingAfterConversion_doesNotReinterpretFrozenKana() = runTest {
        val engine = newEngine()
        engine.inputRomaji("hon")

        // 「ほん」を Tab 変換（provider は表に無いのでそのまま）→ 確定済みのかなは frozen。
        engine.convertAndApply()

        // その後 a を追記しても、確定済みの ほん が ほな に再解釈されてはいけない（F1 回帰の番人）。
        assertEquals("ほんあ", engine.inputRomaji("a"))
    }

    @Test
    fun typingAfterConversion_appendsUnconvertedTailToMixedPreedit() = runTest {
        val engine = newEngine()
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        // 変換済 segments（漢字）は残し、追記分は未変換かな tail として混在表示する。
        assertEquals("漢字あ", engine.inputRomaji("a"))
        assertEquals(3, engine.segmentCount())
        assertEquals("Unconverted", engine.segmentStatus(2))

        // 末尾の未変換かなを 1 つ削ると変換済 preedit に戻る（tail の末尾 1 かな削除）。
        assertEquals("漢字", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_onNonExactLastSegmentFallsBackToWholeRevert() = runTest {
        val engine = newEngine()
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        // FakeSegmenter の読みは表層形（漢字）なので readingInput と完全一致せず、末尾 segment の
        // backspace は per-segment ではなく全体 revert にフォールバックする（confidence 低）。
        assertEquals("かんじ", engine.deleteBackward())
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun deleteBackward_revertsOnlyLastSegmentWhenExact() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 読みが完全一致する変換済末尾 segment（天気）だけを打った通りのかなへ戻す（優先順位 4 / range revert）。
        assertEquals("私てんき", engine.deleteBackward())
        assertEquals(2, engine.segmentCount())
        assertEquals("Converted", engine.segmentStatus(0))
        assertEquals("Unconverted", engine.segmentStatus(1))
        assertTrue(engine.isConverted())
    }

    @Test
    fun deleteBackward_onSelectedConvertedSegmentRevertsThatSegment() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 先頭 segment（私）を ← 2 回で選択し backspace → その segment だけ中間かなへ戻す（優先順位 2）。
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()

        assertEquals("わたし天気", engine.deleteBackward())
        assertEquals("Unconverted", engine.segmentStatus(0))
        assertEquals("Converted", engine.segmentStatus(1))
    }

    @Test
    fun deleteBackward_onSelectedUnconvertedSegmentDeletesTrailingKana() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        engine.deleteBackward()

        // 中間かな（わたし）を選択したまま backspace → その segment 末尾の 1 かなだけ削る（優先順位 3）。
        assertEquals("わた天気", engine.deleteBackward())
        assertEquals(0, engine.selectedSegmentIndex())
        assertEquals("Unconverted", engine.segmentStatus(0))
        assertEquals("Converted", engine.segmentStatus(1))
    }

    @Test
    fun deleteBackward_onSelectedNonExactSegmentFallsBackToWholeRevert() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        // 完全一致しない（FakeSegmenter）変換済 segment を ← で選択して backspace → 全体 revert。
        engine.moveSelectionLeft()

        assertEquals("にほんご", engine.deleteBackward())
        assertFalse(engine.isConverted())
    }

    @Test
    fun cancel_clearsInOneStepWhenAllSegmentsReverted() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 末尾 segment（天気）と先頭 segment（私）を ← で順に選択し per-segment revert して、変換済を 1 つも残さない。
        engine.moveSelectionLeft()
        engine.deleteBackward()
        engine.moveSelectionLeft()
        engine.deleteBackward()

        assertFalse(engine.isConverted())

        // 変換済が無いので Esc は 1 回で composition 全体を破棄する（空振りしない）。
        assertEquals("", engine.cancel())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun cancel_revertsConversionThenClears() = runTest {
        val engine = newEngine()
        engine.inputRomaji("toukyou")
        engine.convertAndApply()

        // 1 回目の Esc は segments を破棄して readingInput へ戻す。
        assertEquals("とうきょう", engine.cancel())
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())

        // 2 回目の Esc で composition 全体を破棄する。
        assertEquals("", engine.cancel())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun moveSelection_navigatesSegmentsWithClamp() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        assertEquals(-1, engine.selectedSegmentIndex())

        // 未選択（カーソル末尾）からの → は右に clause が無いので据え置く。
        engine.moveSelectionRight()
        assertEquals(-1, engine.selectedSegmentIndex())

        // ← で末尾 clause から文節選択へ入る。
        engine.moveSelectionLeft()
        assertEquals(2, engine.selectedSegmentIndex())

        // ← は先頭でクランプ。
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        assertEquals(0, engine.selectedSegmentIndex())

        // → で右の clause へ 1 つずつ進む。
        engine.moveSelectionRight()
        assertEquals(1, engine.selectedSegmentIndex())

        // 末尾 clause を → で越えると選択解除しカーソルを末尾へ戻す。
        engine.moveSelectionRight()
        assertEquals(2, engine.selectedSegmentIndex())
        engine.moveSelectionRight()
        assertEquals(-1, engine.selectedSegmentIndex())
    }

    @Test
    fun moveSelectionLeft_fromUnselectedSelectsLastSegment() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        engine.moveSelectionLeft()

        assertEquals(2, engine.selectedSegmentIndex())
    }

    @Test
    fun moveSelectionRight_fromUnselectedDoesNotEnterSelection() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        // 入力直後はカーソルが末尾にあり右に clause が無いため、→ では文節選択へ入らない。
        engine.moveSelectionRight()

        assertEquals(-1, engine.selectedSegmentIndex())
    }

    @Test
    fun candidates_listsObviousCandidatesForSelectedSegment() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 先頭 segment（私 / 読み わたし）を ← 2 回で選択 → 自明候補 [私, わたし, ワタシ]。
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()

        assertEquals(3, engine.candidateCount())
        assertEquals("私", engine.candidateText(0))
        assertEquals("わたし", engine.candidateText(1))
        assertEquals("ワタシ", engine.candidateText(2))
    }

    @Test
    fun previewCandidate_keepsProjectionInvariantWhenLonger() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 1（天気=2 字）を選択し、より長い候補（てんき=3 字）を preview する。
        engine.moveSelectionLeft()
        val preedit = engine.previewCandidate("てんき")

        assertEquals("私てんき", preedit)
        assertEquals("私", engine.segmentText(0))
        assertEquals("てんき", engine.segmentText(1))
        assertEquals(1, engine.selectedSegmentIndex())
        assertEquals(engine.preeditText(), concatSegments(engine))
    }

    @Test
    fun previewCandidate_keepsProjectionInvariantWhenShorter() = runTest {
        // 2 字 surface（天気）に 1 字の preview を当てても Σ segmentText == preedit が成立する。
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        engine.moveSelectionLeft()
        val preedit = engine.previewCandidate("気")

        assertEquals("私気", preedit)
        assertEquals("気", engine.segmentText(1))
        assertEquals(engine.preeditText(), concatSegments(engine))
    }

    @Test
    fun previewCandidate_doesNotMutateDraftAndIsRevertedByClose() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        engine.previewCandidate("てんき")

        // closeCandidates で preview を破棄すると surface も status も元へ戻る。
        val preedit = engine.closeCandidates()

        assertEquals("私天気", preedit)
        assertEquals("天気", engine.segmentText(1))
        assertEquals("Converted", engine.segmentStatus(1))
        assertEquals(1, engine.selectedSegmentIndex())
    }

    @Test
    fun confirmCandidate_appliesSurfaceAndPrefixLocks() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 1（天気）を選択し別候補（転機）で確定 → 0..1 を prefix lock する。
        engine.moveSelectionLeft()
        val preedit = engine.confirmCandidate("転機")

        assertEquals("私転機", preedit)
        assertEquals("転機", engine.segmentText(1))
        assertEquals("Locked", engine.segmentStatus(0))
        assertEquals("Locked", engine.segmentStatus(1))
        assertEquals(1, engine.selectedSegmentIndex())
        assertTrue(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun confirmCandidate_keepsExistingPrefixWhenReselectingEarlierSegment() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // まず segment 1 を確定して 0..1 を lock。
        engine.moveSelectionLeft()
        engine.confirmCandidate("転機")

        // segment 0 を選び直して確定（N<K）→ prefix 0..max(1,0)=0..1 を維持し surface だけ差し替える。
        engine.moveSelectionLeft()
        engine.confirmCandidate("渡し")

        assertEquals("渡し", engine.segmentText(0))
        assertEquals("Locked", engine.segmentStatus(0))
        assertEquals("Locked", engine.segmentStatus(1))
    }

    @Test
    fun selectedSegmentIndex_reflectsCandidatePreviewSelection() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        engine.previewCandidate("てんき")

        // Candidate 中も B1c ハイライト継続のため selectedSegmentIndex は選択 index を返す。
        assertEquals(1, engine.selectedSegmentIndex())
    }

    @Test
    fun requestAndApplyWordCandidates_mergesLlmCandidatesWithObvious() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 1（天気 / 読み てんき）を選択して call2 を発火・適用する。
        engine.moveSelectionLeft()
        val raw = engine.requestWordCandidates()
        engine.applyWordCandidates(raw)

        // 自明 [天気, てんき, テンキ] + LLM [天気, 転機, てんき, テンキ] の重複除外後は 4 件。
        assertEquals(4, engine.candidateCount())
        assertTrue(candidateList(engine).contains("転機"))
    }

    @Test
    fun applyWordCandidates_normalizesControlCharsAndDropsBlanks() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()

        // stale でない状態（request 経由で id を揃える）で正規化対象の生 JSON を適用する。
        engine.requestWordCandidates()
        engine.applyWordCandidates("""{"candidates":["転\n機","","気\t団","転機"]}""")

        val candidates = candidateList(engine)

        // 制御文字（改行・tab）除去後の候補が入り、空候補は除外、重複「転機」は 1 件に畳まれる。
        assertTrue(candidates.contains("転機"))
        assertTrue(candidates.contains("気団"))
        assertFalse(candidates.contains(""))
        assertEquals(1, candidates.count { it == "転機" })
    }

    @Test
    fun applyWordCandidates_isNoOpWhenSessionClosedAfterRequest() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        val raw = engine.requestWordCandidates()

        // 窓を閉じた後に古い結果が返っても LLM 候補を復活させない（stale guard）。
        engine.closeCandidates()
        val countAfterClose = engine.candidateCount()
        engine.applyWordCandidates(raw)

        assertEquals(countAfterClose, engine.candidateCount())
        assertFalse(candidateList(engine).contains("転機"))
    }

    @Test
    fun applyWordCandidates_isNoOpAfterConfirm() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        val raw = engine.requestWordCandidates()

        // confirm 後に古い結果が返っても確定後の状態を書き換えない（stale guard）。
        engine.confirmCandidate("転機")
        engine.applyWordCandidates(raw)

        assertEquals("転機", engine.segmentText(1))
        assertEquals("Locked", engine.segmentStatus(1))
    }

    @Test
    fun applyWordCandidates_isNoOpAfterSelectionMoved() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        val raw = engine.requestWordCandidates()

        // 選択が動いたら古い結果は別 segment に乗らず破棄される（stale guard）。
        engine.moveSelectionLeft()
        engine.applyWordCandidates(raw)

        assertFalse(candidateList(engine).contains("転機"))
    }

    @Test
    fun requestWordCandidates_returnsEmptyWhenNoSegmentSelected() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 選択 None では call2 を発火しない。
        assertEquals("", engine.requestWordCandidates())
    }

    @Test
    fun reconvert_preservesLockedPrefixAndReconvertsOnlyTail() = runTest {
        val engine = newEngine(RECONVERT_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 0（私）を選択して別候補（渡し）で確定 → 0..0 を prefix lock。
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        engine.confirmCandidate("渡し")

        // 再変換すると Locked prefix（渡し）は保持し、tail（てんき）だけ再変換される。
        engine.convertAndApply()

        assertEquals("渡し", engine.segmentText(0))
        assertEquals("Locked", engine.segmentStatus(0))
        assertEquals("天気", engine.segmentText(1))
        assertEquals("Converted", engine.segmentStatus(1))
        assertEquals("渡し天気", engine.preeditText())
    }

    @Test
    fun reconvert_sendsTailReadingAndLockedPrefixContextToProvider() = runTest {
        val recording = RecordingConversionProvider()
        val engine = RomaFlowEngine(recording, RECONVERT_SEGMENTER, FakeAligner())
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 0 を渡しで確定（0..0 Locked）してから 2 回目の変換を発火する。
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        engine.confirmCandidate("渡し")
        engine.convert()

        val request = requireNotNull(recording.lastRequest)
        assertEquals("てんき", request.readingInput)
        assertEquals("渡し", request.prefixContext)
    }

    @Test
    fun reconvert_offsetsTailSegmentRangeByPrefixEnd() = runTest {
        val engine = newEngine(RECONVERT_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        engine.confirmCandidate("渡し")
        engine.convertAndApply()

        // tail segment（天気）の読みは元のまま・range は prefixEnd(=3) 分オフセットされる。
        // exact backspace で tail だけが読み（てんき）へ戻り、prefix（渡し）は保持される。
        assertEquals("てんき", engine.segmentReading(1))
        assertEquals("渡してんき", engine.deleteBackward())
        assertEquals("Locked", engine.segmentStatus(0))
        assertEquals("Unconverted", engine.segmentStatus(1))
    }

    @Test
    fun deleteBackward_demotesOrphanedLockedSegmentsToConverted() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 1（天気）を確定して 0..1 を Locked にする。
        engine.moveSelectionLeft()
        engine.confirmCandidate("転機")

        // segment 0 を選び直して per-segment revert すると、prefix の連続性が崩れる。
        engine.moveSelectionLeft()
        engine.deleteBackward()

        // segment 0 は Unconverted へ戻り、孤立した segment 1 は Locked から Converted へ降格する。
        assertEquals("Unconverted", engine.segmentStatus(0))
        assertEquals("Converted", engine.segmentStatus(1))
    }

    @Test
    fun reconvert_afterOrphanRevertReconvertsWholeInput() = runTest {
        val recording = RecordingConversionProvider()
        val engine = RomaFlowEngine(recording, RECONVERT_SEGMENTER, FakeAligner())
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionLeft()
        engine.confirmCandidate("転機")
        engine.moveSelectionLeft()
        engine.deleteBackward()

        // 孤立 Locked が無いので lockedPrefix は空＝tail は全文。再変換で全体が再変換対象になる。
        engine.convert()

        val request = requireNotNull(recording.lastRequest)
        assertEquals("わたしてんき", request.readingInput)
        assertEquals("", request.prefixContext)
    }

    @Test
    fun reconvert_isNoOpWhenAllSegmentsLocked() = runTest {
        val engine = newEngine(RECONVERT_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // segment 1（天気）を確定 → 0..1 全 Locked。
        engine.moveSelectionLeft()
        engine.confirmCandidate("天気")

        // tail が空なので convert は何もせず空文字を返し、apply 後も両 segment が Locked のまま。
        assertEquals("", engine.convert())
        engine.applyConversion("")

        assertEquals("Locked", engine.segmentStatus(0))
        assertEquals("Locked", engine.segmentStatus(1))
        assertEquals("私天気", engine.preeditText())
    }
}

/** 候補窓の現在候補を index 順に取り出す検証用ヘルパー。 */
private fun candidateList(engine: RomaFlowEngine): List<String> {
    val candidates = mutableListOf<String>()

    for (index in 0 until engine.candidateCount()) {
        candidates.add(engine.candidateText(index))
    }

    return candidates
}

/** preedit 不変条件の検証用に、engine の全 segment surface を連結する。 */
private fun concatSegments(engine: RomaFlowEngine): String {
    val builder = StringBuilder()

    for (index in 0 until engine.segmentCount()) {
        builder.append(engine.segmentText(index))
    }

    return builder.toString()
}

/** 「私天気」を読み付きの 2 token（私=わたし / 天気=てんき）へ分割するテスト用 segmenter。 */
private val WATASHI_TENKI_SEGMENTER = MappedSegmenter(
    mapOf(
        "私天気" to listOf(
            SegmentToken("私", "わたし"),
            SegmentToken("天気", "てんき"),
        ),
    ),
)

/**
 * lock 再変換テスト用 segmenter。初回変換の全文「私天気」と、tail 再変換で返る「天気」の両方を読み付きで分割する。
 */
private val RECONVERT_SEGMENTER = MappedSegmenter(
    mapOf(
        "私天気" to listOf(
            SegmentToken("私", "わたし"),
            SegmentToken("天気", "てんき"),
        ),
        "天気" to listOf(
            SegmentToken("天気", "てんき"),
        ),
        // RecordingConversionProvider は変換せず読みを素通しするため、全文「わたしてんき」も分割対象にする。
        "わたしてんき" to listOf(
            SegmentToken("わたし", "わたし"),
            SegmentToken("てんき", "てんき"),
        ),
    ),
)

/** FakeConversionProvider + 指定 [segmenter] + FakeAligner を注入したテスト用 [RomaFlowEngine]。 */
private fun newEngine(segmenter: Segmenter = FakeSegmenter()): RomaFlowEngine {
    return RomaFlowEngine(FakeConversionProvider(), segmenter, FakeAligner())
}

/** convert() の結果を applyConversion() で反映するテスト用ヘルパー。 */
private suspend fun RomaFlowEngine.convertAndApply(): String {
    val result = convert()

    return applyConversion(result)
}

/** convert() に渡された最後の [ConversionRequest] を記録し、変換結果は素通しで返すテスト用 provider。 */
private class RecordingConversionProvider : ConversionProvider {

    var lastRequest: ConversionRequest? = null
        private set

    override suspend fun convert(request: ConversionRequest): String {
        lastRequest = request

        return request.readingInput
    }

    override suspend fun candidates(request: WordCandidateRequest): String {
        return ""
    }
}

/** 既知の変換結果を読み付き token 列へ分割し、未登録の入力は 1 文字 1 token にフォールバックする segmenter。 */
private class MappedSegmenter(private val table: Map<String, List<SegmentToken>>) : Segmenter {

    override fun segment(text: String): List<SegmentToken> {
        return table[text] ?: text.map { SegmentToken(it.toString(), it.toString()) }
    }
}
