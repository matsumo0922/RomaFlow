package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.morphology.ArcKey
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.ReadingLexicon

/**
 * open surface proposal + full-lattice 検証 resolver（§A-rerank 方針転換 PR-A）。
 *
 * ## 動作フロー
 * 1. tail reading（locked prefix を除いた変換対象）を取得する。
 * 2. `CompositionGraph.build` で baseline（Viterbi rank-0 経路）を取得する。
 * 3. baseline の各 span を走査し、同一 reading span に複数 surface がある **content 語**（baseline が漢字を含む）
 *    の箇所だけを [DecisionRegion] に変換する。baseline が漢字を含まない機能語 span（助詞「を」・
 *    する/して の「し」「て」等）と 1 surface のみの span は確定部としてテンプレートに固定する。
 *    これにより LLM が機能語を同音漢字（市/手/死…）へ過変換するのを防ぐ。
 *    options には literal reading（span のひらがな読みそのもの）を必ず含める（[withLiteralHint]）。
 * 4. region が 0 個なら LLM を呼ばずに baseline surface を [ResolutionProposal.ProposeJointCorrection] で返す。
 * 5. region がある場合は template + regions を [FactorizedRerankRequest] に詰めて
 *    [ConversionProvider.rerankFactorized] を呼ぶ。
 * 6. decisions（regionId → 提案 surface）を受け取り、各 region の提案 surface を full lattice で検証する
 *    （[acceptedRegionSurfaceOrNull]）。格子内なら採用・格子外・長さ不一致は baseline に fallback する。
 * 7. それらを連結した「表層文字列」を [ResolutionProposal.ProposeJointCorrection.preferredSurface] で返す。
 *
 * ## surface-carry 不変条件（path identity は保持しない）
 * 本 resolver が返すのは選択結果を反映した **surface**（表層文字列）であり、`applyConversion()` 側の
 * [ProposalApplier] がその surface から格子上の合法 path を再探索して採用する。`convert()` が String を返し
 * applier が再検証する契約は変えない（[applyConversion] / `buildResolverState` /
 * `buildVerifiedOrFallbackTailSegments` は無改造）。
 *
 * ## open-surface の格子検証
 * LLM は pack（hint）外の surface も提案できる（例: `以下` が pack になくても提案可能）。
 * 提案 surface は [ReadingLatticeDecoder.findMinCostPathForSurface] で region の reading span に対して検証し、
 * 非 null であれば合法（採用）・null は格子外（baseline fallback）とする。
 *
 * ## literal/KEEP baseline
 * OOV / literal の span は baseline lexeme の surface がそのまま候補に含まれる（単一候補 → 確定部扱い）。
 *
 * ## fallback
 * - region 0 個: baseline を surface-carry で返す。
 * - LLM 失敗 / decisions 空: baseline を surface-carry で返す。
 * - region 欠落 / 格子外提案 / reading 長不一致: その region は baseline lexeme を使う。
 *
 * @param conversionProvider LLM 呼び出しを担う provider。rerankFactorized を呼ぶ。
 * @param lexicon 辞書（CompositionGraph 構築用・格子検証用）。
 * @param costProvider 連接コスト provider（Viterbi デコード用・格子検証用）。
 * @param nBest baseline 構築の N-best 件数（デフォルト: 16）。
 */
internal class FactorizedRerankResolver(
    private val conversionProvider: ConversionProvider,
    private val lexicon: ReadingLexicon,
    private val costProvider: ConnectionCostProvider,
    private val nBest: Int = DEFAULT_N_BEST,
) : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val prefixContext = state.pinnedConstraint.pinnedSurface
        val prefixBoundary = state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)
        val tailReading = reading.substring(prefixBoundary)

        if (tailReading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val graph = CompositionGraph.build(
            reading = tailReading,
            lexicon = lexicon,
            costProvider = costProvider,
            nBest = nBest,
        )

        if (!graph.hasValidPath) {
            return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)
        }

        val bestPathId = graph.bestPathId ?: return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)
        val baselinePath = graph.pathOrNull(bestPathId) ?: return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)

        val arcMarginals = ReadingLatticeDecoder.arcMarginalCosts(tailReading, lexicon, costProvider)
        val globalBest = graph.costOrNull(bestPathId) ?: Long.MAX_VALUE

        val baselineSpans = buildBaselineSpans(tailReading, baselinePath)
        val regions = extractDecisionRegions(tailReading, baselineSpans, arcMarginals, globalBest)

        val preferredSurface = if (regions.isEmpty()) {
            buildSurfaceFromPath(baselinePath)
        } else {
            resolveWithFactorizedRerank(baselineSpans, regions, prefixContext)
        }

        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(
                fromAtomIndex = prefixBoundary,
                toAtomIndex = reading.length,
            ),
            intendedReading = tailReading,
            preferredSurface = preferredSurface,
        )
    }

    /**
     * baseline 経路の各 lexeme を reading span 情報と一緒に [BaselineSpan] として構築する。
     *
     * lexeme の reading はカタカナだが文字数はひらがな mora 数と一致するため、
     * [tailReading]（ひらがな）の mora 長で span を計算する。
     */
    private fun buildBaselineSpans(tailReading: String, baselinePath: List<LexemeEntry>): List<BaselineSpan> {
        val spans = mutableListOf<BaselineSpan>()
        var readingCursor = 0

        for (lexeme in baselinePath) {
            val spanStart = readingCursor
            val spanEnd = (readingCursor + lexeme.reading.length).coerceAtMost(tailReading.length)

            if (spanStart >= tailReading.length) break

            spans.add(
                BaselineSpan(
                    readingStart = spanStart,
                    readingEnd = spanEnd,
                    lexeme = lexeme,
                ),
            )

            readingCursor = spanEnd
        }

        return spans
    }

    /**
     * baseline の各 span に対して同一 reading span を覆う別 surface を収集し、[DecisionRegion] を構築する。
     *
     * baseline が純ひらがな以外（漢字を含む、またはカタカナ）の span のうち、同一 reading span に 2 件以上の
     * surface がある場合のみ [DecisionRegion] とする。baseline が純ひらがなの機能語 span（[isAllHiragana] が
     * true。する/して の「し」「て」・助詞「を」等）と 1 件のみ（baseline のみ）の span は確定部としてスキップする。
     *
     * [arcMarginals] と [globalBest] を受け取り、[buildHintPack] でΔCベースの候補選抜を行う（PR-C）。
     */
    private fun extractDecisionRegions(
        tailReading: String,
        baselineSpans: List<BaselineSpan>,
        arcMarginals: Map<ArcKey, Long>,
        globalBest: Long,
    ): List<DecisionRegion> {
        val regions = mutableListOf<DecisionRegion>()
        var regionIndex = 0

        for (span in baselineSpans) {
            // baseline が純ひらがなの span（する/して の「し」「て」・助詞「を」等の機能語）は region 化しない。
            // これらに同音漢字（市/手/死…）を候補提示すると LLM が機能語を過変換するため確定部に固定する。
            // 一方、カタカナ baseline（content 語が誤って片仮名 rank-0 になった イカ=以下 / カナ=仮名 等）は
            // 正しい漢字・かな代替を候補に出すため region 化する（純ひらがなだけを機能語とみなす）。
            val baselineIsFunctionKana = isAllHiragana(span.lexeme.surface)

            if (baselineIsFunctionKana) continue

            val spanReading = tailReading.substring(span.readingStart, span.readingEnd)
            val alternativeLexemes = collectAlternativeLexemes(tailReading, span)
            val allLexemes = buildHintPack(
                span = span,
                alternatives = alternativeLexemes,
                arcMarginals = arcMarginals,
                globalBest = globalBest,
            )

            val hasMultipleSurfaces = allLexemes.size >= 2

            if (!hasMultipleSurfaces) continue

            val regionId = "r$regionIndex"
            val options = buildRegionOptions(regionId, allLexemes)
                .withLiteralHint(regionId, spanReading)
            val region = DecisionRegion(
                id = regionId,
                reading = spanReading,
                readingStart = span.readingStart,
                readingEnd = span.readingEnd,
                options = options,
            )

            regions.add(region)
            regionIndex++
        }

        return regions
    }

    /**
     * 同一 reading span を覆う baseline 以外の lexeme を辞書から収集する。
     *
     * `commonPrefixSearch` で span.readingStart から検索し、readingEndOffset が span.readingEnd と
     * 一致するもの（＝同一 span を覆う lexeme）のみを採用する。
     * baseline 自体は別途追加するため、ここでは baseline の surface と異なるものだけを返す。
     */
    private fun collectAlternativeLexemes(tailReading: String, span: BaselineSpan): List<LexemeEntry> {
        val matches = lexicon.commonPrefixSearch(tailReading, span.readingStart)
        val baselineSurface = span.lexeme.surface

        return matches
            .filter { match -> match.readingEndOffset == span.readingEnd }
            .map { match -> match.lexeme }
            .filter { lexeme -> lexeme.surface != baselineSurface }
    }

    /**
     * baseline lexeme を先頭に、代替 lexeme をΔC（文脈つき強制全文パスコスト差）昇順で選抜し、
     * surface dedup・旧字体降格・最大6件でクランプした hint pack を構築する。
     *
     * ## ΔC の計算
     * `ΔC(lex) = arcMarginals[ArcKey(span.readingStart, span.readingEnd, lex.surface)] - globalBest`。
     * arcMarginals に該当エントリがなければ [Long.MAX_VALUE] とし最劣後扱いにする。
     * [globalBest] が [Long.MAX_VALUE] の場合は比較不能のため ΔC も [Long.MAX_VALUE] にする。
     *
     * ## quota（最大 [MAX_OPTIONS_PER_REGION] = 6）
     * - baseline: 必ず先頭に1枠
     * - ΔC 上位（非旧字体）: [MAX_OPTIONS_PER_REGION] - 1 枠まで（literal hint 用に1枠確保）
     * - 旧字体: deferred 降格（非旧字体が [MAX_OPTIONS_PER_REGION] - 1 に満たない場合のみ追加）
     * - literal hint（spanReading そのもの）: [withLiteralHint] が末尾に保証
     *
     * ## クランプ順序（重要）
     * ΔC ソートを先に行い、その後でクランプする（wcost でクランプしてから ΔC ソートすると意味がない）。
     *
     * @param span baseline span（readingStart/readingEnd で arc を特定）
     * @param alternatives 同一 reading span を覆う baseline 以外の lexeme（件数未絞り）
     * @param arcMarginals reading 全体の arc 最良全文コスト map（[ReadingLatticeDecoder.arcMarginalCosts] 出力）
     * @param globalBest 全文の最良コスト（[CompositionGraph.costOrNull] の最安経路コスト）
     */
    private fun buildHintPack(
        span: BaselineSpan,
        alternatives: List<LexemeEntry>,
        arcMarginals: Map<ArcKey, Long>,
        globalBest: Long,
    ): List<LexemeEntry> {
        fun deltaC(lex: LexemeEntry): Long {
            val isGlobalBestInvalid = globalBest == Long.MAX_VALUE
            if (isGlobalBestInvalid) return Long.MAX_VALUE

            val arcKey = ArcKey(
                startOffset = span.readingStart,
                endOffset = span.readingEnd,
                surface = lex.surface,
            )
            val forced = arcMarginals[arcKey] ?: Long.MAX_VALUE

            return if (forced == Long.MAX_VALUE) Long.MAX_VALUE else forced - globalBest
        }

        val seenSurfaces = mutableSetOf<String>()
        val result = mutableListOf<LexemeEntry>()

        // baseline を必ず先頭に含める（ΔC に依らず固定）
        seenSurfaces.add(span.lexeme.surface)
        result.add(span.lexeme)

        // ΔC 昇順でソートし、クランプ前に全候補に適用する（wcost クランプより前に ΔC ソートを行う）。
        // 旧字体は seenSurfaces を汚さずに deferredArchaic へ退避し、枠が余れば 2 pass 目で追加する（真の降格）。
        val sortedByDelta = alternatives.sortedBy { deltaC(it) }
        val deferredArchaic = mutableListOf<LexemeEntry>()

        for (lex in sortedByDelta) {
            // literal hint 用に1枠残す（withLiteralHint が末尾に追加するため MAX - 1 でクランプ）
            if (result.size >= MAX_OPTIONS_PER_REGION - 1) break

            val isDuplicateSurface = lex.surface in seenSurfaces

            if (isDuplicateSurface) continue

            val isArchaicForm = isArchaicKanji(lex.surface)

            if (isArchaicForm) {
                deferredArchaic.add(lex)
                continue
            }

            seenSurfaces.add(lex.surface)
            result.add(lex)
        }

        // 旧字体は最大件数に満たない場合のみ追加する（削除ではなく降格）
        for (lex in deferredArchaic) {
            if (result.size >= MAX_OPTIONS_PER_REGION - 1) break

            val isNewSurface = seenSurfaces.add(lex.surface)

            if (isNewSurface) {
                result.add(lex)
            }
        }

        return result
    }

    /**
     * surface が純ひらがな（ひらがなブロックの文字のみ）かどうかを判定する。
     *
     * region 化対象から機能語 span（する/して の「し」「て」・助詞「を」等、辞書 rank-0 が入力かなのまま）を
     * 除外するために使う。漢字を含む surface・カタカナ surface（イカ=以下 等の content 語）は false。
     */
    private fun isAllHiragana(surface: String): Boolean {
        if (surface.isEmpty()) return false

        return surface.all { character -> character.code in 0x3041..0x309F }
    }

    /**
     * 旧字体・異体字かどうかを判定する。
     *
     * 実機問題（top-40 flat rerank が `聖歌を擧げた` を選んで悪化）の背景に沿い、IPADIC に現れやすい
     * 代表的な旧字体・異体字（[ARCHAIC_KANJI]）を降格対象とする。加えて CJK 拡張 A 面（基本多言語面・
     * `Char` で表現可能）の文字も旧字体・異体字が多いため降格候補とみなす。常用表記が別途あれば pack の枠を
     * 消費させない。なお拡張 B 面（U+20000 以降）は `Char` が surrogate pair になり単一 code では届かないため、
     * BMP の `ARCHAIC_KANJI` と拡張 A のみを対象とする。
     */
    private fun isArchaicKanji(surface: String): Boolean {
        return surface.any { character ->
            val isExtensionA = character.code in 0x3400..0x4DBF

            character in ARCHAIC_KANJI || isExtensionA
        }
    }

    /**
     * lexeme リストから [RegionOption] リストを構築する。
     *
     * 各 option の id は "${regionId}o${optionIndex}" 形式。
     * lexemePath は通常 1 件（単一 lexeme で span を覆う）。
     */
    private fun buildRegionOptions(regionId: String, lexemes: List<LexemeEntry>): List<RegionOption> {
        return lexemes.mapIndexed { optionIndex, lexeme ->
            RegionOption(
                id = "${regionId}o$optionIndex",
                surface = lexeme.surface,
                lexemePath = listOf(lexeme),
            )
        }
    }

    /**
     * options リストに literal reading（span のひらがな読みそのもの）を必ず含める。
     *
     * open-surface では hint の lexemePath は assembly に使わない（surface だけ LLM へ見せる）ので、
     * literal option は surface=spanReading の合成で良い。lexemePath は空。
     * 既に同一 surface を持つ option があれば追加しない（dedup）。
     */
    private fun List<RegionOption>.withLiteralHint(regionId: String, spanReading: String): List<RegionOption> {
        val hasLiteral = any { it.surface == spanReading }

        if (hasLiteral) return this

        val literalOption = RegionOption(
            id = "${regionId}o$size",
            surface = spanReading,
            lexemePath = emptyList(),
        )

        return this + literalOption
    }

    /**
     * factorized rerank リクエストを構築・実行し、提案 surface を格子検証して完全 surface を返す。
     *
     * LLM 失敗（decisions 空）の場合は baseline surface を返す。
     */
    private suspend fun resolveWithFactorizedRerank(
        baselineSpans: List<BaselineSpan>,
        regions: List<DecisionRegion>,
        prefixContext: String,
    ): String {
        val template = buildTemplate(baselineSpans, regions)
        val factorizedRequest = FactorizedRerankRequest(
            template = template,
            prefixContext = prefixContext,
            regions = regions,
        )

        // 任意 provider 実装が契約を破って throw しても最終防衛網として握る（baseline fallback）。
        val result = runCatching { conversionProvider.rerankFactorized(factorizedRequest) }
        val decisions = result.getOrElse { FactorizedRerankResult(decisions = emptyMap()) }.decisions

        return assembleSurfaceFromDecisions(baselineSpans, regions, decisions)
    }

    /**
     * 確定部を表層で、region を {rN} プレースホルダで展開してテンプレートを構築する。
     *
     * 例: baselineSpans = [勉強して, 成果, を, 上げた]、regions = [r0=成果, r1=上げた]
     * → "勉強して{r0}を{r1}"
     */
    private fun buildTemplate(
        baselineSpans: List<BaselineSpan>,
        regions: List<DecisionRegion>,
    ): String {
        val regionsByStart = regions.associateBy { it.readingStart }
        val builder = StringBuilder()

        for (span in baselineSpans) {
            val region = regionsByStart[span.readingStart]

            if (region != null) {
                builder.append("{${region.id}}")
            } else {
                builder.append(span.lexeme.surface)
            }
        }

        return builder.toString()
    }

    /**
     * decisions（regionId → 提案 surface）を baseline に差し込んで全文 tail surface を組む。
     * 各 region の提案 surface は full lattice で検証し、合法なら採用・非合法/未提案は baseline へ fallback する。
     * 返すのは surface のみ（path identity は持たない。applier が後段で全文を再検証する）。
     */
    private fun assembleSurfaceFromDecisions(
        baselineSpans: List<BaselineSpan>,
        regions: List<DecisionRegion>,
        decisions: Map<String, String>,
    ): String {
        val regionsByStart = regions.associateBy { it.readingStart }
        val builder = StringBuilder()

        for (span in baselineSpans) {
            val region = regionsByStart[span.readingStart]

            if (region == null) {
                builder.append(span.lexeme.surface)
                continue
            }

            val proposed = decisions[region.id]
            val accepted = proposed?.let { acceptedRegionSurfaceOrNull(region, it) }

            builder.append(accepted ?: span.lexeme.surface)
        }

        return builder.toString()
    }

    /**
     * region の提案 surface が、その region の reading span の合法な実現なら surface を返す。さもなくば null。
     * 検証は [ReadingLatticeDecoder.findMinCostPathForSurface] の非 null 性で行う
     * （reading 長不一致・格子外は null）。
     *
     * open-surface では surface は option ID ではなくモデル生成の自由文字列のため、前後の空白・改行が
     * 混入し得る。raw のまま検証すると格子と一致せず合法な提案まで baseline へ落ちるので、検証・採用の前に
     * trim する（採用する surface も trim 後の値）。
     */
    private fun acceptedRegionSurfaceOrNull(region: DecisionRegion, proposed: String): String? {
        val trimmedSurface = proposed.trim()

        if (trimmedSurface.isBlank()) return null

        val found = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = region.reading,
            surface = trimmedSurface,
            lexicon = lexicon,
            costProvider = costProvider,
        )

        return if (found != null) trimmedSurface else null
    }

    /**
     * lexeme 経路の surface を連結して表示文字列を構築する。
     */
    private fun buildSurfaceFromPath(path: List<LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.surface)
        }

        return builder.toString()
    }

    private companion object {
        /** デフォルトの N-best 件数（§4.1 に従い 16）。 */
        const val DEFAULT_N_BEST = 16

        /** region ごとの最大候補件数（§4.2）。 */
        const val MAX_OPTIONS_PER_REGION = 6

        /**
         * region 候補から降格する代表的な旧字体・異体字。
         *
         * IPADIC に現れやすく、同音の常用表記（新字体）が別途存在するものを中心に列挙する。
         * これらは 1 pass 目では pack に入れず、枠が余った 2 pass 目でのみ追加する。
         * 完全網羅は目的でなく、`擧`（聖歌を擧げた の擧）など実機で悪さをした旧字体の取りこぼし防止が主眼。
         */
        val ARCHAIC_KANJI = setOf(
            '擧', '國', '體', '萬', '圖', '學', '寫', '廣', '區', '來',
            '應', '會', '變', '數', '鐵', '觀', '醫', '藝', '戰', '燈',
            '號', '處', '證', '雜', '缺', '舊', '黨', '當', '兒', '齒',
            '廳', '臺', '禮', '彈', '聲', '櫻', '澤', '濱', '齋', '惠',
        )
    }
}

/**
 * baseline 経路の 1 span の情報を保持する内部データクラス。
 *
 * reading 座標での span 範囲と対応する lexeme を保持する。
 * [FactorizedRerankResolver] 内部で region 抽出・テンプレート構築・surface 組み立てに使う。
 *
 * @param readingStart tail reading 内での開始 mora offset（inclusive）。
 * @param readingEnd tail reading 内での終了 mora offset（exclusive）。
 * @param lexeme この span に対応する baseline の lexeme。
 */
private data class BaselineSpan(
    /** tail reading 内での開始 mora offset（inclusive）。 */
    val readingStart: Int,
    /** tail reading 内での終了 mora offset（exclusive）。 */
    val readingEnd: Int,
    /** この span に対応する baseline の lexeme。 */
    val lexeme: LexemeEntry,
)
