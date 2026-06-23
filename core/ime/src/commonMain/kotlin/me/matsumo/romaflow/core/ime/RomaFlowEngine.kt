package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.matsumo.romaflow.core.ime.shadow.CompositionGraph
import me.matsumo.romaflow.core.ime.shadow.CompositionState
import me.matsumo.romaflow.core.ime.shadow.ConversionResolver
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResolver
import me.matsumo.romaflow.core.ime.shadow.LegacyFullTextResolver
import me.matsumo.romaflow.core.ime.shadow.PinnedPathConstraint
import me.matsumo.romaflow.core.ime.shadow.ProposalApplier
import me.matsumo.romaflow.core.ime.shadow.RerankResolver
import me.matsumo.romaflow.core.ime.shadow.ResolutionProposal
import me.matsumo.romaflow.core.ime.shadow.ResolutionRequest
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.HomophoneDictionary
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LiteralContextIds
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback

/**
 * rerank の動作モード。
 *
 * - [FactorizedSurface]: open surface proposal + 格子検証（既定）。LLM が表層を提案し resolver が full lattice 検証する。
 * - [Flat]: 全文 N-best から1つ選ぶ flat rerank（比較・回帰用に残す）。
 */
internal enum class RerankMode {
    /** open surface proposal + 格子検証（既定）。hint pack 外の表層も格子内なら採用できる。 */
    FactorizedSurface,

    /** 全文 N-best rerank（比較・回帰用）。 */
    Flat,
}

/**
 * RomaFlow IME core の変換 draft を保持するエンジン。
 *
 * IMKInputController インスタンスごとに1つ生成され、[ConversionDraft]（romaji 入力層・変換済 segment・
 * 単語選択）を持つ。入力は 2 レイヤに分かれ、romaji→kana は末尾の pendingRomaji にだけ増分適用し、
 * 確定したかなは readingInput に frozen として積む。kana→kanji は Tab 起動で readingInput 全体を
 * 既定 resolver（[FactorizedRerankResolver]、[RerankMode.FactorizedSurface]）に投入し、曖昧箇所を region へ
 * 因数分解して LLM に region ごとの表層を提案させ、full lattice 検証後に §6 applier（[ProposalApplier]）を経由して
 * verified path → projected segments へ変換する（A-rerank / open surface）。[RerankMode.Flat] の
 * [RerankResolver]（全文 N-best index 選択）と [LegacyFullTextResolver] は比較・回帰用に温存。
 * 変換結果の各 segment は [ReadingAligner] で readingInput 上の [TextRange] に対応付け、
 * per-segment の revert・削除を range 単位で扱う。
 * rerank 失敗・preferredSurface が格子外の場合は辞書 Viterbi 1位（rank-0）へ fallback する
 * （[buildViterbiFallbackTailSegments]）。OOV/自由漢字は compositeLexicon の literal arc で
 * verified literal path となるため、legacy [buildTailSegments] は graph が空の最終手段としてのみ残す。
 * 公開 API は Swift Export に合わせ String / Int / Boolean / suspend のみを受け渡しする（List は出さない）。
 *
 * ## surface-carry（factorized の path identity は保持しない）
 * resolver は LLM が選んだ region surface を baseline に差し込んだ「表層文字列」を [convert] の戻り値として返し、
 * [applyConversion] 側の [ProposalApplier] がその surface から格子上の合法 path を再探索して採用する。
 * したがって LLM が選んだ subpath の path identity（連接 ID / POS / lexeme 同一性）は最終状態へ pin されない。
 * 選択 subpath を pin した上での未確定区間の constrained Viterbi 再補完は本実装では out of scope（#23 後続）。
 *
 * ## A-5 cutover: lexicon / costProvider 注入
 * テスト注入用に internal constructor で [readingLexiconFactory] と [connectionCostProviderFactory] を受け取る。
 * public constructor は本番向けに同梱 Mozc binary（[MozcDictionaryFactory]）から lexicon / costProvider /
 * homophone を構築する。dict / matrix ロード（重い）は初回 [convert] まで遅延するため、engine 構築時の
 * レイテンシを避ける。
 * factory はラムダとして渡され、[readingLexicon] / [connectionCostProvider] の by lazy フィールドで
 * 初回 convert() 時に評価される。これにより engine 構築時点ではいかなるロードも走らない。
 *
 * ## スコープ外（follow-up）
 * inputRomaji / deleteBackward / candidates / lock / marked-text / Swift adapter の shadow 化、
 * atom 座標化・lock 跨ぎ厳密化・preview surface、OneShotJointPatch 本実装は A-5 以降の follow-up。
 */
class RomaFlowEngine internal constructor(
    private val conversionProvider: ConversionProvider,
    private val segmenter: Segmenter,
    private val aligner: ReadingAligner,
    private val homophoneDictionary: HomophoneDictionary = EmptyHomophoneDictionary,
    private val readingLexiconFactory: () -> ReadingLexicon = MozcDictionaryFactory::createReadingLexicon,
    private val connectionCostProviderFactory: () -> ConnectionCostProvider = MozcDictionaryFactory::createConnectionCostProvider,
    /** rerank の動作モード。既定は [RerankMode.FactorizedSurface]（open surface proposal + 格子検証）。 */
    private val rerankMode: RerankMode = RerankMode.FactorizedSurface,
) {

    private val converter = RomajiKanaConverter()

    private var draft = ConversionDraft(
        input = InputBuffer(readingInput = "", pendingRomaji = ""),
        segments = emptyList(),
        selection = Selection.None,
    )

    // romaji 入力層の source of truth。appendRomaji / finalizePending / pending 削除の経路でのみ更新する。
    // readingInput / pendingRomaji はここからの projection として算出できる（CompositionSource.asInputBuffer）。
    // セグメント range に基づく直接削除（deleteReadingInputCharAt）は draft.input を更新し、compositionSource は
    // その後の appendRomaji で再構築されるため、セグメント削除中の compositionSource は一時的に stale になる
    // （これは A-3 で統合する際に解消する予定の既知の制約）。
    private var compositionSource = CompositionSource.empty(revision = 0)

    // 次に生成する InputAtom の ID。単調増加させてセッション内でユニーク性を保証する。
    private var nextAtomId = 0L

    // 入力状態のリビジョン。入力を変える操作のたびに増やし、非同期変換の stale 判定に使う。
    private var inputRevision = 0

    // 実行中の変換要求が発行されたときの入力リビジョン。結果適用時にこれが現在値と一致するか確認する。
    private var pendingConversionRevision = -1

    // 候補窓セッションのリビジョン。inputRevision とは独立に call2 の stale を判定する。
    // 選択変更・入力変更・session 終了操作で増やす（previewCandidate の窓内ナビでは増やさない）。
    private var candidateRequestId = 0

    // 実行中の call2 要求が発行されたときの candidateRequestId。結果適用時にこれが現在値と一致するか確認する。
    private var pendingCandidateRequestId = -1

    // readingLexicon / connectionCostProvider は factory ラムダから遅延生成する。
    // matrix ロードは重いため engine 構築時ではなく初回 convert() まで先送りする。
    private val readingLexicon: ReadingLexicon by lazy { readingLexiconFactory() }

    private val connectionCostProvider: ConnectionCostProvider by lazy { connectionCostProviderFactory() }

    // OOV fallback 付き格子（composite）・proposalApplier・legacyResolver は初回 convert() まで遅延して構築する。
    // readingLexicon / connectionCostProvider も lazy なので、こちらの初期化も自動的に遅延する。
    // literal arc の連接 ID は Mozc の POS 空間に合わせる（cutover で本番辞書が Mozc になったため）。
    // legacyResolver は stateless なので 1 インスタンスを再利用する。
    private val compositeLexicon: ReadingLexicon by lazy {
        buildReadingLexiconWithFallback(readingLexicon, LiteralContextIds.Mozc)
    }

    private val proposalApplier: ProposalApplier by lazy {
        ProposalApplier(
            lexicon = compositeLexicon,
            costProvider = connectionCostProvider,
        )
    }

    // A4 比較用に残す（A4ResolverComparisonTest で直接インスタンス化されるため engine 側は未参照になるが意図的）
    @Suppress("UnusedPrivateProperty")
    private val legacyResolver: LegacyFullTextResolver by lazy { LegacyFullTextResolver(conversionProvider) }

    // flat rerank resolver（A-rerank）。格子 N-best から LLM に index で選ばせ、捏造を構造的に防ぐ。
    // A4 比較用・flat モード切替用に残す。
    private val flatRerankResolver: RerankResolver by lazy {
        RerankResolver(
            conversionProvider = conversionProvider,
            lexicon = compositeLexicon,
            costProvider = connectionCostProvider,
        )
    }

    // factorized rerank resolver（既定）。曖昧箇所を region に因数分解し LLM に region ごとに選ばせる。
    private val factorizedRerankResolver: FactorizedRerankResolver by lazy {
        FactorizedRerankResolver(
            conversionProvider = conversionProvider,
            lexicon = compositeLexicon,
            costProvider = connectionCostProvider,
        )
    }

    // feature flag で flat / factorized surface を切替。既定は FactorizedSurface（open surface + 格子検証）。
    private val activeResolver: ConversionResolver by lazy {
        when (rerankMode) {
            RerankMode.FactorizedSurface -> factorizedRerankResolver
            RerankMode.Flat -> flatRerankResolver
        }
    }

    /**
     * Swift Export / 本番経路向けに既定の AI provider・momiji segmenter・DP aligner・同梱 Mozc 辞書を使う constructor。
     *
     * lexicon / costProvider は internal constructor のデフォルト factory（[MozcDictionaryFactory]）が
     * 適用されるため省略する。homophone も同梱 Mozc binary から streaming index で構築する。
     * いずれも by lazy フィールド・[HomophoneDictionary.ensureReady] で初回 convert() / call2 まで
     * 遅延生成されるため、engine 構築コストはゼロ。
     */
    constructor() : this(
        conversionProvider = defaultConversionProvider(),
        segmenter = MomijiSegmenter(),
        aligner = DpReadingAligner(),
        homophoneDictionary = MozcDictionaryFactory.createHomophoneDictionary(),
    )

    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    fun inputRomaji(text: String): String {
        markInputChanged()

        // セッション封止の判定: 以下のいずれかの場合に現在の readingInput を frozenPrefix として封止し、
        // 新しい romaji セッションを開始する。
        //
        // (1) readingInput が compositionSource と乖離している（変換確定・per-segment revert 等の後）。
        // (2) compositionSource に atoms があるが pending がない（全 atoms が commit 済みで、次の入力は
        //     新しい音節を始める）かつ既存 atoms を再生すると frozen kana が変わってしまうケース。
        //     具体例: `hon` → atoms=[h,o,n] committed="ほん" pending="" → 追記 `a` → "hona"="ほな" ❌
        //     `ky` → atoms=[k,y] pending="ky" → 追記 `a` → "kya" pending="" committed="きゃ" ✓（継続して良い）
        //
        // 規則: pending が空かつ atoms が存在する場合は次の入力が直前 atoms の再解釈を引き起こし得るので
        // セッションを封止する。pending が非空のときは現在の pending に続く文字を追記するので再生成は正当。
        val currentReadingInput = draft.input.readingInput
        val hasStalePendingFree = compositionSource.atoms.isNotEmpty() && compositionSource.pendingRomaji.isEmpty()
        val hasReadingMismatch = currentReadingInput != compositionSource.readingInput
        val sessionSource = if (hasStalePendingFree || hasReadingMismatch) {
            CompositionSource.withFrozenPrefix(currentReadingInput, inputRevision)
        } else {
            compositionSource
        }

        val newAtomId = nextAtomId
        val newSource = sessionSource.appendRomaji(
            converter = converter,
            input = text,
            nextRevision = inputRevision,
            nextAtomId = newAtomId,
        )

        nextAtomId += text.length
        compositionSource = newSource

        draft = draft.copy(
            input = newSource.asInputBuffer,
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

        // A-rerank cutover: LegacyFullTextResolver から activeResolver（既定 factorized）に差し替える。
        // factorizedRerankResolver は曖昧箇所を region に因数分解し LLM に region ごとに選ばせ、捏造を構造的に防ぐ。
        // flatRerankResolver は feature flag（rerankMode=Flat）での比較・回帰用に残す。
        // legacyResolver は A4 比較用に残す（参照あり: A4ResolverComparisonTest）。
        // rerank 失敗時: resolver 内で Viterbi rank-0 surface に fallback し ProposeJointCorrection を返す。
        // Failure（hasValidPath=false 等）/ KeepCurrent は "" を返すことで「空 = 据え置き」の no-op になる。
        val state = buildResolverState(readingInput, prefixEnd, prefixContext)
        val request = ResolutionRequest(
            state = state,
            inputRevision = 0,
            graphRevision = 0,
            candidatePackDigest = 0,
        )
        val proposal = activeResolver.propose(request)

        return when (proposal) {
            is ResolutionProposal.ProposeJointCorrection -> proposal.preferredSurface.orEmpty()
            else -> ""
        }
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
        val readingInput = draft.input.readingInput
        val tailReading = readingInput.substring(prefixEnd.coerceIn(0, readingInput.length))
        val prefixContext = lockedPrefixContext()

        // A-rerank: §6 検証（ProposalApplier）を経由して verified path → projected segments へ変換する。
        // ProposalApplier の apply 内部は決定論・network なし。
        // preferredSurface が格子外で verified path が作れない場合は辞書 Viterbi 1位（rank-0）へ fallback する
        // （buildVerifiedOrFallbackTailSegments）。OOV/自由漢字は compositeLexicon の literal arc で
        // verified literal path になるため、legacy buildTailSegments は graph が空の最終手段としてのみ使う。
        val proposal = ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(fromAtomIndex = prefixEnd, toAtomIndex = readingInput.length),
            intendedReading = tailReading,
            preferredSurface = result,
        )
        val state = buildResolverState(readingInput, prefixEnd, prefixContext)
        // revision を全て 0 で渡すのは意図的。stale 判定は live 層の pendingConversionRevision（冒頭の guard）が
        // 担保しているため、ProposalApplier 内部の revision 比較は常に一致（no-op）にしてよい。
        // 二重の revision 体系を作らず、live 層 1 箇所だけで stale を判定するための設計。
        val request = ResolutionRequest(
            state = state,
            inputRevision = 0,
            graphRevision = 0,
            candidatePackDigest = 0,
        )
        val resolved = proposalApplier.apply(
            proposal = proposal,
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        val tailSegments = buildVerifiedOrFallbackTailSegments(resolved, result, tailReading, prefixEnd)

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
     * Enter 確定時に呼ぶ。選択中の文節を確定して prefix lock し、選択解除（カーソル文末）して preedit を返す。
     *
     * 確定する候補は、候補窓で preview 中（live preview = ユーザーが見ているハイライト）ならその文字列を優先し、
     * preview が無い（candidateSelectionChanged が発火しない client 等）ときだけ引数 [text] を使う。候補窓の reload で
     * IMK の選択 index と確定文字列がズレても、ユーザーが見ている preview と確定内容が一致するようにするための優先順。
     * commit はせず draft の更新だけ行う。選択が draft.segments の範囲外・None なら no-op。lock 範囲は
     * 0..max(現在の最大 Locked index, 選択 index) で確定済み prefix を壊さない。確定後の selection は [Selection.None]。
     */
    fun confirmCandidate(text: String): String {
        invalidateCandidateSession()

        val segmentIndex = selectedSegmentIndexOrNull() ?: return preeditText()

        if (segmentIndex !in draft.segments.indices) {
            return preeditText()
        }

        val surface = currentPreviewSurfaceOrNull() ?: text

        applyCandidateAndLockPrefix(segmentIndex, surface)

        draft = draft.copy(selection = Selection.None)

        return preeditText()
    }

    /**
     * ←/→ で隣文節へ移る直前に呼ぶ。現在の文節を preview（無ければ現 surface）で確定＋prefix lock し、選択 index は維持して preedit を返す。
     *
     * azooKey 式に「通った文節まで lock」する。lock 範囲は [confirmCandidate] と同じく 0..max(現在の最大 Locked index, 選択 index) で
     * 単調増加する。選択が draft.segments の範囲外・None なら no-op。確定後も選択 index を保つため、直後に Swift が moveSelection
     * すると隣の文節へ移れる。
     */
    fun lockSelectedClause(): String {
        invalidateCandidateSession()

        val segmentIndex = selectedSegmentIndexOrNull() ?: return preeditText()

        if (segmentIndex !in draft.segments.indices) {
            return preeditText()
        }

        val surface = clauseSurfaceToLock(segmentIndex)

        applyCandidateAndLockPrefix(segmentIndex, surface)

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

        // 候補表示で使う逆引き index を、main をブロックしないよう Default で1回だけ構築する。
        withContext(Dispatchers.Default) { homophoneDictionary.ensureReady() }

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
        // 防御的 resync: deleteReadingInputCharAt 等が compositionSource を経由せずに
        // draft.input.readingInput を更新した後、ここで再投影しないよう乖離を事前に解消する。
        // pending が空の場合のみ乖離が起こり得る（pending 非空時は inputRomaji が必ず整合させる）。
        val currentReadingInput = draft.input.readingInput
        val hasMismatch = draft.input.pendingRomaji.isEmpty() && currentReadingInput != compositionSource.readingInput

        if (hasMismatch) {
            compositionSource = CompositionSource.withFrozenPrefix(currentReadingInput, inputRevision)
        }

        val newSource = compositionSource.finalizePending(converter)

        compositionSource = newSource
        draft = draft.copy(input = newSource.asInputBuffer)
    }

    private fun deletePendingTail() {
        val newSource = compositionSource.deleteLastKana(nextRevision = inputRevision)

        compositionSource = newSource
        draft = draft.copy(input = newSource.asInputBuffer)
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

        draft = draft.copy(segments = enforceLeadingLockedPrefix(updated))
    }

    private fun deleteSegmentTrailingKana(segment: Segment) {
        val range = segment.range ?: return

        if (range.endExclusive <= range.startInclusive) {
            return
        }

        deleteReadingInputCharAt(range.endExclusive - 1)
    }

    // readingInput の [position] を1文字削り、各 segment の range を追従させる（中間削除では後続が左へ詰まる）。
    // この経路は segment range に基づく直接削除で compositionSource を経由しないため、削除後に
    // compositionSource を resync する（pending 空・segment 削除なので frozenPrefix + 空 pending で正しい）。
    // resync しないと finalize/convert 経路で削除が巻き戻る（compositionSource が stale なまま再投影される）。
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

        compositionSource = CompositionSource.withFrozenPrefix(newReadingInput, inputRevision)
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

    // ←/→ 直前の lock で確定する surface を決める。preview 中（Candidate）かつ previewSurface 非空ならその候補、
    // そうでなければ現 surface を維持する。
    private fun clauseSurfaceToLock(segmentIndex: Int): String {
        return currentPreviewSurfaceOrNull() ?: draft.segments[segmentIndex].surface
    }

    // 候補窓で現在 preview 中の候補文字列。Candidate 選択かつ非空のときだけ返す（live preview = ユーザーが見ているハイライト）。
    private fun currentPreviewSurfaceOrNull(): String? {
        val selection = draft.selection
        val hasPreview = selection is Selection.Candidate && selection.previewSurface.isNotEmpty()

        if (!hasPreview) {
            return null
        }

        return (selection as Selection.Candidate).previewSurface
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

    // 「Locked は常に先頭連続 prefix（0..k）」の不変条件を回復する。先頭から最初の非 Locked 以降に残る Locked を
    // Converted へ降格し（surface/reading/range/candidates は保持）、孤立 Locked が再変換から除外されるのを防ぐ。
    // 中間 segment の per-segment revert で prefix が分断され得る経路でのみ呼ぶ。
    private fun enforceLeadingLockedPrefix(segments: List<Segment>): List<Segment> {
        val firstGap = segments.indexOfFirst { it.status != SegmentStatus.Locked }

        if (firstGap < 0) {
            return segments
        }

        return segments.mapIndexed { index, segment ->
            demoteOrphanedLock(segment, index, firstGap)
        }
    }

    private fun demoteOrphanedLock(segment: Segment, index: Int, firstGap: Int): Segment {
        val isOrphanedLock = index > firstGap && segment.status == SegmentStatus.Locked

        if (!isOrphanedLock) {
            return segment
        }

        return segment.copy(status = SegmentStatus.Converted)
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

    // 選択中の文節に対する候補列（自明候補 + 辞書候補 + LLM 候補）。未選択 / 未変換対象なら空。
    private fun currentCandidates(): List<String> {
        val segmentIndex = selectedSegmentIndexOrNull() ?: return emptyList()
        val segment = draft.segments.getOrNull(segmentIndex) ?: return emptyList()

        if (segment.status == SegmentStatus.Unconverted) {
            return emptyList()
        }

        val merged = obviousCandidates(segment) + dictionaryCandidates(segment) + segment.candidates

        return merged.filter { it.isNotEmpty() }.distinct()
    }

    // 辞書（同音異義逆引き）由来の候補。segment.reading はひらがな。empty 辞書なら空。
    private fun dictionaryCandidates(segment: Segment): List<String> {
        return homophoneDictionary.homophoneCandidates(segment.reading)
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

    /**
     * resolver / applier へ渡す [CompositionState] を最小ブリッジとして構築する。
     *
     * [fullReading] は readingInput 全体、[prefixEnd] は locked prefix の終端（0 = lock なし）、
     * [prefixContext] は locked prefix の surface 連結。[FactorizedRerankResolver]（既定）/ [RerankResolver]
     * / [LegacyFullTextResolver] と [ProposalApplier] は `state.pinnedConstraint.pinnedSurface`（prefixContext）と
     * `lockedPrefixBoundary`（prefixEnd）のみを参照するため、synthetic な [LexemeEntry] で pinnedPath を埋める。
     * synthetic の連接 ID は不問（tail path の segment 化に pinnedPath は使わない）。
     * lock 跨ぎの厳密化は A-5 以降の follow-up（設計ドキュメント参照）。
     */
    private fun buildResolverState(
        fullReading: String,
        prefixEnd: Int,
        prefixContext: String,
    ): CompositionState {
        val source = CompositionSource.withFrozenPrefix(frozenPrefix = fullReading, revision = 0)
        val pinnedConstraint = if (prefixEnd <= 0) {
            PinnedPathConstraint.empty()
        } else {
            buildSyntheticPinnedConstraint(fullReading, prefixEnd, prefixContext)
        }

        return CompositionState.empty().copy(
            source = source,
            pinnedConstraint = pinnedConstraint,
        )
    }

    private fun buildSyntheticPinnedConstraint(
        fullReading: String,
        prefixEnd: Int,
        prefixContext: String,
    ): PinnedPathConstraint {
        // LegacyFullTextResolver が pinnedSurface と lockedPrefixBoundary のみを参照するため、
        // synthetic entry の連接 ID / posId / wcost は 0 で問題ない。
        val syntheticLexeme = LexemeEntry(
            surface = prefixContext,
            reading = fullReading.substring(0, prefixEnd.coerceIn(0, fullReading.length)),
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 0,
        )

        return PinnedPathConstraint(
            lockedPrefixBoundary = prefixEnd,
            pinnedPath = listOf(syntheticLexeme),
        )
    }

    /**
     * ProposalApplier の適用結果から tail segment を構築する。
     *
     * [resolved] が verified path を持つ場合（[CompositionState.isConverted] = true）は
     * [buildTailSegmentsFromPath] で verified path から segment を生成する。
     *
     * 格子上に経路が存在しない場合（① fallback 厳格化）:
     * - Viterbi 1位（rank-0）が取れる場合はその経路から segment を構築する（LLM 全文 fallback を不採用）。
     * - graph が空のとき（compositeLexicon に literal arc があるため通常到達しない）のみ
     *   最終手段として legacy [buildTailSegments] fallback を使う（回帰ゼロ保証）。
     */
    private fun buildVerifiedOrFallbackTailSegments(
        resolved: CompositionState,
        result: String,
        tailReading: String,
        offset: Int,
    ): List<Segment> {
        val isVerified = resolved.isConverted
        val selectedPathId = resolved.selectedPathId

        if (!isVerified || selectedPathId == null) {
            return buildViterbiFallbackTailSegments(tailReading, offset, result)
        }

        val verifiedPath = resolved.graph.pathOrNull(selectedPathId)

        if (verifiedPath == null) {
            return buildViterbiFallbackTailSegments(tailReading, offset, result)
        }

        return buildTailSegmentsFromPath(verifiedPath, tailReading, offset)
    }

    /**
     * Viterbi 1位（辞書 rank-0）の経路から tail segment を構築する（① fallback 厳格化）。
     *
     * [resolved] が verified でない場合のフォールバック。格子を再構築して rank-0 を採用し、
     * LLM 全文 fallback（旧 [buildTailSegments]）を不採用にする。
     * compositeLexicon は literal arc を持つため [CompositionGraph.hasValidPath] は通常 true になる。
     * graph が空の場合のみ最終手段として legacy [buildTailSegments] を使う。
     */
    private fun buildViterbiFallbackTailSegments(
        tailReading: String,
        offset: Int,
        legacyFallbackSurface: String,
    ): List<Segment> {
        if (tailReading.isEmpty()) {
            return buildTailSegments(legacyFallbackSurface, tailReading, offset)
        }

        val graph = CompositionGraph.build(
            reading = tailReading,
            lexicon = compositeLexicon,
            costProvider = connectionCostProvider,
        )

        val bestPathId = graph.bestPathId

        if (bestPathId == null) {
            // compositeLexicon に literal arc があるため通常ここには到達しない
            return buildTailSegments(legacyFallbackSurface, tailReading, offset)
        }

        val bestPath = graph.pathOrNull(bestPathId) ?: return buildTailSegments(legacyFallbackSurface, tailReading, offset)

        return buildTailSegmentsFromPath(bestPath, tailReading, offset)
    }

    /**
     * verified path（[LexemeEntry] リスト）から tail [Segment] リストを構築する。
     *
     * reading cursor を 0 から進め、各 lexeme について surface・reading・TextRange を確定する。
     * lexeme の reading はカタカナだが文字数はひらがな mora 数と一致するため、[tailReading]（ひらがな）
     * を当該長さで切り出して reading とする。range は [offset] 分オフセットして readingInput 全体の絶対座標へ直す。
     * [LexemeEntry.reading] の長さで切ると cursor が tailReading の長さを
     * 超える場合は境界でクランプし、残りを 1 segment にまとめる。
     */
    private fun buildTailSegmentsFromPath(
        path: List<LexemeEntry>,
        tailReading: String,
        offset: Int,
    ): List<Segment> {
        val segments = mutableListOf<Segment>()
        var readingCursor = 0

        for (lexeme in path) {
            val segmentStart = readingCursor
            val segmentEnd = (readingCursor + lexeme.reading.length).coerceAtMost(tailReading.length)

            if (segmentStart >= tailReading.length) break

            val segmentReading = tailReading.substring(segmentStart, segmentEnd)
            val range = TextRange(
                startInclusive = segmentStart + offset,
                endExclusive = segmentEnd + offset,
                confidence = EXACT_MATCH_CONFIDENCE,
            )

            segments.add(
                Segment(
                    surface = lexeme.surface,
                    reading = segmentReading,
                    range = range,
                    status = SegmentStatus.Converted,
                    candidates = emptyList(),
                ),
            )

            readingCursor = segmentEnd
        }

        return segments
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
        compositionSource = CompositionSource.empty(revision = inputRevision)
        nextAtomId = 0L
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
