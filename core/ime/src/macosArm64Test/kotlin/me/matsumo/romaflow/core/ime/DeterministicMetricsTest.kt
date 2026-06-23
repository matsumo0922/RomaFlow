package me.matsumo.romaflow.core.ime

import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.LiteralContextIds
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.isLiteralFallbackArc
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §9 / §10 の決定論指標を corpus 全体に対して per-category でレポートするテスト。
 *
 * LLM を呼ばずに計測できる以下の指標を実装する:
 * - **lattice coverage**: [ReadingLatticeDecoder.findMinCostPathForSurface] が expectedSurface への
 *   完全経路を返せる割合。
 * - **oracle N-best recall**: expectedSurface が [ReadingLatticeDecoder.nBest] の表層集合に現れる割合。
 * - **辞書のみ top-1 精度**: nBest 第1位の連結表層が expectedSurface と一致する割合。
 * - **境界変更被覆率**: BOUNDARY_CHANGE カテゴリで、期待分割を連結した全体表層への完全経路が
 *   格子上に存在する割合（per-segment の厳密境界チェックは A-5 以降に延期）。
 * - **OOV / literal fallback 率**: PROPER_NOUN / ASCII_DIGIT_SYMBOL で
 *   [isLiteralFallbackArc] が真となる arc に依存した割合。
 *
 * assert は緩い下限に留め（例: recall/coverage が一定%以上）、gold ズレで脆くしない。
 * 数値の主目的は println によるレポート出力である。
 */
class DeterministicMetricsTest {

    /**
     * NORMAL カテゴリの lattice coverage・oracle N-best recall・辞書のみ top-1 精度をレポートする。
     *
     * NORMAL corpus は辞書にある一般的な変換を対象とするため、coverage・recall ともに高い値が期待できる。
     * 下限 assert:
     * - lattice coverage >= 70%
     * - oracle N-best recall (n=16) >= 70%
     * - 辞書のみ top-1 >= 30%（同音異義語が含まれない NORMAL でも top-1 は resolver 依存のため緩い）
     */
    @Test
    fun normalCategoryDeterministicMetrics() {
        val lexicon = MozcTestDictionary.readingLexicon
        val costProvider = MozcTestDictionary.costProvider
        val corpus = EvaluationCorpus.normal

        val result = measureCoverageRecallTop1(
            corpus = corpus,
            lexicon = lexicon,
            costProvider = costProvider,
            nBestCount = 16,
            categoryLabel = "NORMAL",
        )

        printCategoryReport("NORMAL", result, corpus.size)

        assertTrue(
            result.latticeCoverageRate >= 0.70,
            "NORMAL lattice coverage が 70% 以上であること（実際: ${formatPercent(result.latticeCoverageRate)}）",
        )
        assertTrue(
            result.oracleNBestRecallRate >= 0.70,
            "NORMAL oracle N-best recall (n=16) が 70% 以上であること（実際: ${formatPercent(result.oracleNBestRecallRate)}）",
        )
        assertTrue(
            result.dictTop1Rate >= 0.30,
            "NORMAL 辞書のみ top-1 が 30% 以上であること（実際: ${formatPercent(result.dictTop1Rate)}）",
        )
    }

    /**
     * HOMOPHONE カテゴリの oracle N-best recall をレポートする。
     *
     * 同音異義語は文脈なしでの top-1 正解が難しいため、N-best recall に重点を置く。
     * 下限 assert:
     * - oracle N-best recall (n=16) >= 60%
     */
    @Test
    fun homophoneCategoryDeterministicMetrics() {
        val lexicon = MozcTestDictionary.readingLexicon
        val costProvider = MozcTestDictionary.costProvider
        val corpus = EvaluationCorpus.homophone

        val result = measureCoverageRecallTop1(
            corpus = corpus,
            lexicon = lexicon,
            costProvider = costProvider,
            nBestCount = 16,
            categoryLabel = "HOMOPHONE",
        )

        printCategoryReport("HOMOPHONE", result, corpus.size)

        assertTrue(
            result.oracleNBestRecallRate >= 0.60,
            "HOMOPHONE oracle N-best recall (n=16) が 60% 以上であること（実際: ${formatPercent(result.oracleNBestRecallRate)}）",
        )
    }

    /**
     * BOUNDARY_CHANGE カテゴリの分割表層到達可能率をレポートする。
     *
     * [CorpusEntry.expectedSegmentation] の各 segment を連結した全体表層に対して
     * [ReadingLatticeDecoder.findMinCostPathForSurface] が経路を返せる割合を計測する。
     *
     * 実装の注意: 格子探索は全体表層の reachability を確認するのみで、per-segment の
     * 厳密な境界一致（各 segment が独立した lexeme に対応するかどうか）は検証していない。
     * per-segment の厳密チェックは A-5 以降に延期する。
     *
     * 下限 assert:
     * - 分割表層到達可能率 >= 50%
     */
    @Test
    fun boundaryChangeCategoryDeterministicMetrics() {
        val lexicon = MozcTestDictionary.readingLexicon
        val costProvider = MozcTestDictionary.costProvider
        val corpus = EvaluationCorpus.boundaryChange

        val entriesWithSegmentation = corpus.filter { it.expectedSegmentation.isNotEmpty() }
        var segmentationCoveredCount = 0

        println("=== BOUNDARY_CHANGE: 分割表層到達可能率レポート ===")
        println("  (期待分割を連結した全体表層が格子上で完全経路として到達可能かを確認。per-segment 厳密チェックは A-5 以降)")

        for (entry in entriesWithSegmentation) {
            val segmentationSurface = entry.expectedSegmentation.joinToString("")
            val isCovered = checkSegmentationSurfaceCoverage(
                reading = entry.reading,
                segmentation = entry.expectedSegmentation,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            if (isCovered) segmentationCoveredCount++

            println(
                "  reading=${entry.reading} expectedSegmentation=${entry.expectedSegmentation}" +
                    " surface=$segmentationSurface covered=$isCovered",
            )
        }

        val coverageRate = if (entriesWithSegmentation.isEmpty()) {
            0.0
        } else {
            segmentationCoveredCount.toDouble() / entriesWithSegmentation.size
        }

        println("  分割表層到達可能率: ${formatPercent(coverageRate)} ($segmentationCoveredCount/${entriesWithSegmentation.size})")

        assertTrue(
            coverageRate >= 0.50,
            "BOUNDARY_CHANGE 分割表層到達可能率が 50% 以上であること（実際: ${formatPercent(coverageRate)}）",
        )
    }

    /**
     * PROPER_NOUN カテゴリの OOV / literal fallback 率をレポートする。
     *
     * Mozc に収録されている固有名詞と OOV（辞書外語）の両方を含む corpus で、
     * [isLiteralFallbackArc] が真となる arc に依存した top-1 経路の割合を計測する。
     * assert は設けず、レポートのみ（PROPER_NOUN の OOV 率は gold 設計に依存するため）。
     */
    @Test
    fun properNounCategoryOovFallbackRate() {
        val costProvider = MozcTestDictionary.costProvider
        val compositeLexicon = MozcTestDictionary.compositeLexicon
        val corpus = EvaluationCorpus.properNoun

        var literalFallbackCount = 0

        println("=== PROPER_NOUN: OOV / literal fallback 率レポート ===")

        for (entry in corpus) {
            val paths = ReadingLatticeDecoder.nBest(
                reading = entry.reading,
                lexicon = compositeLexicon,
                costProvider = costProvider,
                n = 1,
            )

            val top1Path = paths.firstOrNull()?.second ?: emptyList()
            val hasLiteralArc = top1Path.any { isLiteralFallbackArc(it, LiteralContextIds.Mozc) }

            if (hasLiteralArc) literalFallbackCount++

            println(
                "  reading=${entry.reading} expected=${entry.expectedSurface}" +
                    " top1Surface=${top1Path.joinToString("") { it.surface }}" +
                    " hasLiteralArc=$hasLiteralArc",
            )
        }

        val fallbackRate = if (corpus.isEmpty()) 0.0 else literalFallbackCount.toDouble() / corpus.size

        println("  OOV/literal fallback 率: ${formatPercent(fallbackRate)} ($literalFallbackCount/${corpus.size})")
    }

    /**
     * ASCII_DIGIT_SYMBOL カテゴリの literal fallback 率をレポートする。
     *
     * ASCII・数字・記号が混在する reading では [isLiteralFallbackArc] が真となる arc が多用されることを確認する。
     * 下限 assert: literal fallback 率 >= 50%（ASCII/数字混在では literal が必ず使われる）。
     */
    @Test
    fun asciiDigitSymbolCategoryLiteralFallbackRate() {
        val costProvider = MozcTestDictionary.costProvider
        val compositeLexicon = MozcTestDictionary.compositeLexicon
        val corpus = EvaluationCorpus.asciiDigitSymbol

        var literalFallbackCount = 0

        println("=== ASCII_DIGIT_SYMBOL: literal fallback 率レポート ===")

        for (entry in corpus) {
            val paths = ReadingLatticeDecoder.nBest(
                reading = entry.reading,
                lexicon = compositeLexicon,
                costProvider = costProvider,
                n = 1,
            )

            val top1Path = paths.firstOrNull()?.second ?: emptyList()
            val hasLiteralArc = top1Path.any { isLiteralFallbackArc(it, LiteralContextIds.Mozc) }

            if (hasLiteralArc) literalFallbackCount++

            println(
                "  reading=${entry.reading} expected=${entry.expectedSurface}" +
                    " top1Surface=${top1Path.joinToString("") { it.surface }}" +
                    " hasLiteralArc=$hasLiteralArc",
            )
        }

        val fallbackRate = if (corpus.isEmpty()) 0.0 else literalFallbackCount.toDouble() / corpus.size

        println("  literal fallback 率: ${formatPercent(fallbackRate)} ($literalFallbackCount/${corpus.size})")

        assertTrue(
            fallbackRate >= 0.50,
            "ASCII_DIGIT_SYMBOL literal fallback 率が 50% 以上であること（実際: ${formatPercent(fallbackRate)}）",
        )
    }

    /**
     * 全カテゴリを横断した決定論指標サマリをレポートする。
     *
     * NORMAL / HOMOPHONE / BOUNDARY_CHANGE は coverage・recall・top-1 精度を、
     * PROPER_NOUN / ASCII_DIGIT_SYMBOL は OOV / literal fallback 率をレポートする。
     * 各カテゴリの assert は個別テストが担うため、このテスト自体には厳しい assert を設けない。
     */
    @Test
    fun allCategoriesSummaryReport() {
        val lexicon = MozcTestDictionary.readingLexicon
        val costProvider = MozcTestDictionary.costProvider
        val compositeLexicon = MozcTestDictionary.compositeLexicon

        println("=== A-0 全カテゴリ決定論指標サマリ ===")
        println("corpus 合計: ${EvaluationCorpus.all.size} 件")
        println()

        val coverageCategories = listOf(
            "NORMAL" to EvaluationCorpus.normal,
            "HOMOPHONE" to EvaluationCorpus.homophone,
            "BOUNDARY_CHANGE" to EvaluationCorpus.boundaryChange,
        )

        for ((label, corpus) in coverageCategories) {
            val result = measureCoverageRecallTop1(
                corpus = corpus,
                lexicon = lexicon,
                costProvider = costProvider,
                nBestCount = 16,
                categoryLabel = label,
            )

            printCategoryReport(label, result, corpus.size)
        }

        val fallbackCategories = listOf(
            "PROPER_NOUN" to EvaluationCorpus.properNoun,
            "ASCII_DIGIT_SYMBOL" to EvaluationCorpus.asciiDigitSymbol,
        )

        for ((label, corpus) in fallbackCategories) {
            val fallbackCount = corpus.count { entry ->
                val paths = ReadingLatticeDecoder.nBest(
                    reading = entry.reading,
                    lexicon = compositeLexicon,
                    costProvider = costProvider,
                    n = 1,
                )
                val top1Path = paths.firstOrNull()?.second ?: emptyList()

                top1Path.any { isLiteralFallbackArc(it, LiteralContextIds.Mozc) }
            }

            val fallbackRate = if (corpus.isEmpty()) 0.0 else fallbackCount.toDouble() / corpus.size

            println()
            println("--- $label (n=${corpus.size}) ---")
            println("  OOV/literal fallback 率: ${formatPercent(fallbackRate)} ($fallbackCount/${corpus.size})")
        }

        // サマリテストは常に通過（レポート目的）
        assertTrue(true, "サマリレポートは常に通過")
    }

    // region: ヘルパー

    /**
     * lattice coverage / oracle N-best recall / 辞書のみ top-1 の計測結果。
     */
    private data class CoverageRecallResult(
        /** lattice coverage: findMinCostPathForSurface が経路を返した割合。 */
        val latticeCoverageRate: Double,
        /** oracle N-best recall: expectedSurface が N-best の表層集合に含まれた割合。 */
        val oracleNBestRecallRate: Double,
        /** 辞書のみ top-1: N-best 第1位の連結表層が expectedSurface と一致した割合。 */
        val dictTop1Rate: Double,
        /** 計測エントリ数。 */
        val totalCount: Int,
    )

    /**
     * corpus に対して lattice coverage / oracle N-best recall / 辞書のみ top-1 を計測する。
     *
     * [categoryLabel] は per-entry ログのヘッダラベルとして println に使用する。
     * 詳細な per-entry ログは println で出力する。
     */
    private fun measureCoverageRecallTop1(
        corpus: List<CorpusEntry>,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
        nBestCount: Int,
        categoryLabel: String,
    ): CoverageRecallResult {
        var latticeCoveredCount = 0
        var nBestRecalledCount = 0
        var top1CorrectCount = 0

        println("=== $categoryLabel: per-entry 指標ログ ===")

        for (entry in corpus) {
            val coveragePath = ReadingLatticeDecoder.findMinCostPathForSurface(
                reading = entry.reading,
                surface = entry.expectedSurface,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val nBestPaths = ReadingLatticeDecoder.nBest(
                reading = entry.reading,
                lexicon = lexicon,
                costProvider = costProvider,
                n = nBestCount,
            )

            val nBestSurfaces = nBestPaths.map { (_, path) ->
                path.joinToString("") { it.surface }
            }.toSet()

            val top1Surface = nBestPaths.firstOrNull()?.second?.joinToString("") { it.surface } ?: ""

            val isCovered = coveragePath != null
            val isInNBest = entry.expectedSurface in nBestSurfaces
            val isTop1Correct = top1Surface == entry.expectedSurface

            if (isCovered) latticeCoveredCount++
            if (isInNBest) nBestRecalledCount++
            if (isTop1Correct) top1CorrectCount++

            println(
                "  reading=${entry.reading} expected=${entry.expectedSurface}" +
                    " coverage=$isCovered nBestRecall=$isInNBest top1=$top1Surface top1Correct=$isTop1Correct",
            )
        }

        val total = corpus.size

        return CoverageRecallResult(
            latticeCoverageRate = if (total == 0) 0.0 else latticeCoveredCount.toDouble() / total,
            oracleNBestRecallRate = if (total == 0) 0.0 else nBestRecalledCount.toDouble() / total,
            dictTop1Rate = if (total == 0) 0.0 else top1CorrectCount.toDouble() / total,
            totalCount = total,
        )
    }

    /**
     * [segmentation] の各 segment を連結した全体表層への完全経路が格子上に存在するかを確認する。
     *
     * [ReadingLatticeDecoder.findMinCostPathForSurface] で全体表層の reachability を確認する。
     * per-segment の厳密な境界一致（各 segment が独立した lexeme に対応するかどうか）は
     * 検証せず、A-5 以降に延期する。
     */
    private fun checkSegmentationSurfaceCoverage(
        reading: String,
        segmentation: List<String>,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): Boolean {
        val surface = segmentation.joinToString("")
        val path = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = reading,
            surface = surface,
            lexicon = lexicon,
            costProvider = costProvider,
        )

        return path != null
    }

    /**
     * Double を百分率文字列にフォーマットする（例: 0.75 → "75.0%"）。
     *
     * 小数第1位を四捨五入して表示する。
     */
    private fun formatPercent(value: Double): String {
        val tenths = (value * 1000.0).roundToInt()
        val intPart = tenths / 10
        val fracPart = tenths % 10

        return "$intPart.$fracPart%"
    }

    /**
     * カテゴリ別レポートを println で出力する。
     */
    private fun printCategoryReport(
        label: String,
        result: CoverageRecallResult,
        totalCount: Int,
    ) {
        println()
        println("--- $label (n=$totalCount) ---")
        println("  lattice coverage   : ${formatPercent(result.latticeCoverageRate)}")
        println("  oracle N-best recall: ${formatPercent(result.oracleNBestRecallRate)}")
        println("  辞書のみ top-1 精度 : ${formatPercent(result.dictTop1Rate)}")
    }

    // endregion
}
