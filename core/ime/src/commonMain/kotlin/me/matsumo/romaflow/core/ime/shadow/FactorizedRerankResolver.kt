package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ReadingLexicon

/**
 * atomic factorized rerank 戦略 resolver（§A-factorized 設計）。
 *
 * ## 動作フロー
 * 1. tail reading（locked prefix を除いた変換対象）を取得する。
 * 2. `CompositionGraph.build` で baseline（Viterbi rank-0 経路）を取得する。
 * 3. baseline の各 span を走査し、同一 reading span に複数 surface がある箇所を [DecisionRegion] に変換する。
 *    1 surface のみの span は確定部としてテンプレートに固定する。
 * 4. region が 0 個なら LLM を呼ばずに baseline surface を [ResolutionProposal.ProposeJointCorrection] で返す。
 * 5. region がある場合は template + regions を [FactorizedRerankRequest] に詰めて
 *    [ConversionProvider.rerankFactorized] を呼ぶ。
 * 6. choices（region ID → option ID）を受け取り、各 region の選択 option の lexemePath を baseline に差し込む。
 *    未選択 region は baseline の lexeme を使う。
 * 7. 完全 lexeme path の surface を連結して [ResolutionProposal.ProposeJointCorrection.preferredSurface] で返す。
 *
 * ## surface-carry 不変条件
 * `convert()` が String を返し `applyConversion()` / [ProposalApplier] が再検証する契約は変えない。
 * [applyConversion] / `buildResolverState` / `buildVerifiedOrFallbackTailSegments` は無改造。
 *
 * ## literal/KEEP baseline
 * OOV / literal の span は baseline lexeme の surface がそのまま候補に含まれる（単一候補 → 確定部扱い）。
 *
 * ## fallback
 * - region 0 個: baseline を surface-carry で返す。
 * - LLM 失敗 / choices 空: baseline を surface-carry で返す。
 * - region 欠落 / option_id 範囲外: その region は baseline lexeme を使う。
 *
 * @param conversionProvider LLM 呼び出しを担う provider。rerankFactorized を呼ぶ。
 * @param lexicon 辞書（CompositionGraph 構築用）。
 * @param costProvider 連接コスト provider（Viterbi デコード用）。
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

        val baselineSpans = buildBaselineSpans(tailReading, baselinePath)
        val regions = extractDecisionRegions(tailReading, baselineSpans)

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
     * 同一 reading span に 2 件以上の surface がある場合のみ [DecisionRegion] とし、
     * 1 件のみ（baseline のみ）の場合は確定部としてスキップする。
     */
    private fun extractDecisionRegions(tailReading: String, baselineSpans: List<BaselineSpan>): List<DecisionRegion> {
        val regions = mutableListOf<DecisionRegion>()
        var regionIndex = 0

        for (span in baselineSpans) {
            val spanReading = tailReading.substring(span.readingStart, span.readingEnd)
            val alternativeLexemes = collectAlternativeLexemes(tailReading, span)
            val allLexemes = buildDedupedLexemeList(span.lexeme, alternativeLexemes)

            val hasMultipleSurfaces = allLexemes.size >= 2

            if (!hasMultipleSurfaces) continue

            val regionId = "r$regionIndex"
            val options = buildRegionOptions(regionId, allLexemes)
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
     * baseline lexeme を先頭に、代替 lexeme を cost 昇順に並べ、surface dedup・旧字体降格・最大6件でクランプした
     * リストを構築する。
     *
     * baseline の surface は必ず含める（issue #23 A 受入条件）。
     */
    private fun buildDedupedLexemeList(baselineLexeme: LexemeEntry, alternatives: List<LexemeEntry>): List<LexemeEntry> {
        val seenSurfaces = mutableSetOf<String>()
        val result = mutableListOf<LexemeEntry>()

        // baseline を必ず先頭に含める
        seenSurfaces.add(baselineLexeme.surface)
        result.add(baselineLexeme)

        // 代替を cost 昇順で追加（surface dedup・旧字体降格）
        val sortedAlternatives = alternatives.sortedBy { it.wcost }

        for (lexeme in sortedAlternatives) {
            if (result.size >= MAX_OPTIONS_PER_REGION) break

            val isNewSurface = seenSurfaces.add(lexeme.surface)

            if (!isNewSurface) continue

            val isArchaicForm = isArchaicKanji(lexeme.surface)

            if (isArchaicForm) continue

            result.add(lexeme)
        }

        // 旧字体も最大件数に満たない場合は追加する（多様性確保）
        if (result.size < MAX_OPTIONS_PER_REGION) {
            for (lexeme in sortedAlternatives) {
                if (result.size >= MAX_OPTIONS_PER_REGION) break

                val isNewSurface = seenSurfaces.add(lexeme.surface)

                if (isNewSurface) {
                    result.add(lexeme)
                }
            }
        }

        return result
    }

    /**
     * 旧字体・異体字かどうかを簡易判定する。
     *
     * 常用漢字外の漢字（基本 CJK 統合漢字の拡張面）を含む場合を旧字体候補とみなす。
     * 完全な旧字体リストは持たず、同音の常用表記がある場合の降格用の簡易フィルタ。
     */
    private fun isArchaicKanji(surface: String): Boolean {
        return surface.any { character ->
            val codePoint = character.code
            // CJK Unified Ideographs Extension-A/B は旧字体・異体字が多い
            val isExtensionA = codePoint in 0x3400..0x4DBF
            val isExtensionB = codePoint in 0x20000..0x2A6DF

            isExtensionA || isExtensionB
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
     * factorized rerank リクエストを構築・実行し、選択結果を baseline に差し込んで完全 surface を返す。
     *
     * LLM 失敗（choices 空）の場合は baseline surface を返す。
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

        // ConversionProvider の実装は失敗時に空 choices を返す契約だが、ここでの runCatching は
        // 任意の provider 実装（契約を破って例外を投げるもの）に対する最終防衛網として残す。
        // どちらの経路でも空 choices に倒し、assembleSurface が baseline へ fallback する。
        val result = runCatching { conversionProvider.rerankFactorized(factorizedRequest) }
        val choices = result.getOrElse { FactorizedRerankResult(choices = emptyMap()) }.choices

        return assembleSurface(baselineSpans, regions, choices)
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
     * choices（region ID → option ID）を使って baseline に選択 option の lexemePath を差し込み、
     * 完全 lexeme path の surface を連結して返す。
     *
     * 未選択 region・option_id 範囲外 → baseline lexeme を使う。
     */
    private fun assembleSurface(
        baselineSpans: List<BaselineSpan>,
        regions: List<DecisionRegion>,
        choices: Map<String, String>,
    ): String {
        val regionsByStart = regions.associateBy { it.readingStart }
        val builder = StringBuilder()

        for (span in baselineSpans) {
            val region = regionsByStart[span.readingStart]

            if (region == null) {
                builder.append(span.lexeme.surface)
                continue
            }

            val selectedOptionId = choices[region.id]
            val selectedOption = region.options.firstOrNull { it.id == selectedOptionId }

            if (selectedOption == null) {
                // 未選択・範囲外 → baseline lexeme を使う
                builder.append(span.lexeme.surface)
            } else {
                for (lexeme in selectedOption.lexemePath) {
                    builder.append(lexeme.surface)
                }
            }
        }

        return builder.toString()
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
