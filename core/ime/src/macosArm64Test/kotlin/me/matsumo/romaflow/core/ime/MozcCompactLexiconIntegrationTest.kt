package me.matsumo.romaflow.core.ime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import me.matsumo.romaflow.core.ime.generated.MozcGeneratedDictionaryPaths
import me.matsumo.romaflow.core.morphology.EntryListReadingLexicon
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LiteralContextIds
import me.matsumo.romaflow.core.morphology.MozcCompactDictionaryReader
import me.matsumo.romaflow.core.morphology.MozcCompactLexicon
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback
import platform.posix.RUSAGE_SELF
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getrusage
import platform.posix.rewind
import platform.posix.rusage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MozcCompactLexicon]（U2c 実装）の integration テスト。
 *
 * ## テストメソッドの隔離ポリシー
 *
 * `ru_maxrss` は **プロセス単位の単調増加ピーク**。[EntryListReadingLexicon]（616MB）を構築する
 * parity テストと同一プロセスで RSS を測ると汚染されるため、以下の 2 メソッドを完全に分離する。
 *
 * - [reportsCompactLexiconRssAndCategoryBaselines]: RSS 計測専用。
 *   [MozcCompactLexicon] のみを構築し、EntryListReadingLexicon 経路を踏まない。
 *   同一テストバイナリ内で先に EntryListReadingLexicon が構築されると ru_maxrss が汚染されるため、
 *   数値は delta（このメソッド内の baseline から compact lexicon 構築後の増分）で評価する。
 *
 * - [fullCorpusParityWithEntryListLexicon]: full-corpus parity（差分ゼロ検証）専用。
 *   EntryListReadingLexicon を構築するが RSS は計測しない。
 *
 * RSS 計測と parity を混ぜないこと。
 */
class MozcCompactLexiconIntegrationTest {

    // ---- RSS 計測テスト（EntryListLexicon 経路を踏まない・隔離） ----

    /**
     * [MozcCompactLexicon] のみで全カテゴリの metrics を実行し、RSS delta を計測する。
     *
     * このメソッドは EntryListReadingLexicon を構築しない。ru_maxrss は単調増加ピークのため、
     * 同一プロセス内で他テストが先に EntryList（~616MB）を構築すると absolute が汚染される。
     * そのため数値は「このメソッドの baseline から compact lexicon 構築後の増分（delta）」で評価する。
     * delta アサートの上限は 400MB（目標 ≪200MB に対し余裕を持たせた値）。
     */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun reportsCompactLexiconRssAndCategoryBaselines() {
        val beforeLoadRssBytes = currentMaxResidentBytes()

        val dictBytes = readFileBytes(MozcGeneratedDictionaryPaths.DICTIONARY_BINARY_PATH)
        val matrixBytes = readFileBytes(MozcGeneratedDictionaryPaths.MATRIX_BINARY_PATH)

        val compactLexicon = MozcCompactLexicon(dictBytes)
        val costProvider = MozcCompactDictionaryReader.readConnectionCostProvider(matrixBytes)
        val lexicon = buildReadingLexiconWithFallback(compactLexicon, LiteralContextIds.Mozc)

        val afterLoadRssBytes = currentMaxResidentBytes()

        val loadDeltaMb = (afterLoadRssBytes - beforeLoadRssBytes).toDouble() / BYTES_PER_MB
        val absoluteMb = afterLoadRssBytes.toDouble() / BYTES_PER_MB

        println("=== MozcCompactLexicon RSS（U2c, delta 評価）===")
        println("baseline=${beforeLoadRssBytes / BYTES_PER_MB.toLong()}MB absolute=${absoluteMb.toInt()}MB delta=${loadDeltaMb.toInt()}MB")

        // 全カテゴリ metrics（U2a と同一数値アサート）
        evaluateAndAssertAllCategories(lexicon, costProvider)

        // RSS delta アサート。
        // absolute は同一プロセス内の他テスト（EntryListReadingLexicon 等）による汚染を受けるため delta で評価する。
        // 目標: compact lexicon が追加で使う delta ≪ 200MB（dictBytes 52MB + index ~20MB + overhead）。
        // 上限は 400MB と余裕を持たせる（sorted index + Kotlin Native runtime overhead を含む）。
        assertTrue(
            loadDeltaMb < RSS_DELTA_UPPER_BOUND_MB,
            "compact 経路の RSS delta が上限 ${RSS_DELTA_UPPER_BOUND_MB}MB を超えた: delta=${loadDeltaMb.toInt()}MB absolute=${absoluteMb.toInt()}MB",
        )

        println("RSS delta assertion OK: ${loadDeltaMb.toInt()}MB < ${RSS_DELTA_UPPER_BOUND_MB}MB (absolute=${absoluteMb.toInt()}MB)")
    }

    // ---- full-corpus parity テスト（RSS 計測なし・EntryList と比較） ----

    /**
     * 全 EvaluationCorpus エントリについて [MozcCompactLexicon] と [EntryListReadingLexicon] の
     * commonPrefixSearch 結果が差分ゼロであることを検証する。
     *
     * このテストは EntryListReadingLexicon を構築するため RSS を計測しない。
     * RSS が先に汚染済みかどうかに関わらず parity 自体は正しく検証できる。
     */
    @Test
    fun fullCorpusParityWithEntryListLexicon() {
        val dictBytes = readFileBytes(MozcGeneratedDictionaryPaths.DICTIONARY_BINARY_PATH)

        val entries: List<LexemeEntry> = MozcCompactDictionaryReader.readEntries(dictBytes)
        val compact = MozcCompactLexicon(dictBytes)

        val entryList = EntryListReadingLexicon(entries)

        println("=== full-corpus parity: CompactLexicon vs EntryListLexicon ===")
        println("entries=${entries.size}")

        var totalSearches = 0
        var totalMatches = 0

        for (corpusEntry in EvaluationCorpus.all) {
            val reading = corpusEntry.reading

            for (startOffset in reading.indices) {
                val compactResult = compact.commonPrefixSearch(reading, startOffset)
                val entryListResult = entryList.commonPrefixSearch(reading, startOffset)

                assertEquals(
                    entryListResult.size,
                    compactResult.size,
                    "reading=$reading startOffset=$startOffset: 件数不一致",
                )

                for (index in entryListResult.indices) {
                    val expected = entryListResult[index]
                    val actual = compactResult[index]

                    assertEquals(
                        expected.readingEndOffset,
                        actual.readingEndOffset,
                        "reading=$reading startOffset=$startOffset index=$index: endOffset 不一致",
                    )
                    assertEquals(
                        expected.lexeme,
                        actual.lexeme,
                        "reading=$reading startOffset=$startOffset index=$index: lexeme 不一致",
                    )
                }

                totalSearches++
                totalMatches += compactResult.size
            }
        }

        println("parity OK: searches=$totalSearches totalMatches=$totalMatches")
    }

    // ---- 共通ヘルパー ----

    private fun evaluateAndAssertAllCategories(
        lexicon: me.matsumo.romaflow.core.morphology.ReadingLexicon,
        costProvider: me.matsumo.romaflow.core.morphology.ConnectionCostProvider,
    ) {
        val categories = listOf(
            "NORMAL" to EvaluationCorpus.normal,
            "HOMOPHONE" to EvaluationCorpus.homophone,
            "HOMOPHONE_HARD" to EvaluationCorpus.homophoneHard,
            "BOUNDARY_CHANGE" to EvaluationCorpus.boundaryChange,
            "PROPER_NOUN" to EvaluationCorpus.properNoun,
            "ASCII_DIGIT_SYMBOL" to EvaluationCorpus.asciiDigitSymbol,
        )

        val reports = categories.map { (name, entries) ->
            evaluateCategory(name, entries, lexicon, costProvider)
        }

        reports.forEach { report -> printCategoryReport(report) }

        assertCategoryBaselines(reports)
    }

    private fun evaluateCategory(
        name: String,
        entries: List<CorpusEntry>,
        lexicon: me.matsumo.romaflow.core.morphology.ReadingLexicon,
        costProvider: me.matsumo.romaflow.core.morphology.ConnectionCostProvider,
    ): CategoryReport {
        var reachableCount = 0
        var top1CorrectCount = 0
        var tieCount = 0

        for (entry in entries) {
            val reachPath = ReadingLatticeDecoder.findMinCostPathForSurface(
                reading = entry.reading,
                surface = entry.expectedSurface,
                lexicon = lexicon,
                costProvider = costProvider,
            )
            if (reachPath != null) reachableCount++

            val ranked = ReadingLatticeDecoder.nBest(entry.reading, lexicon, costProvider, TOP_N_FOR_TIE)

            val topSurface = ranked.firstOrNull()?.second?.joinToString("") { it.surface }
            if (topSurface == entry.expectedSurface) top1CorrectCount++

            if (ranked.size >= 2 && ranked[0].first == ranked[1].first) tieCount++
        }

        return CategoryReport(
            name = name,
            total = entries.size,
            reachableCount = reachableCount,
            top1CorrectCount = top1CorrectCount,
            tieCount = tieCount,
        )
    }

    private fun assertCategoryBaselines(reports: List<CategoryReport>) {
        val minTop1RateByCategory = mapOf(
            "NORMAL" to NORMAL_TOP1_LOWER_BOUND,
            "HOMOPHONE" to HOMOPHONE_TOP1_LOWER_BOUND,
            "HOMOPHONE_HARD" to HOMOPHONE_HARD_TOP1_LOWER_BOUND,
            "BOUNDARY_CHANGE" to BOUNDARY_TOP1_LOWER_BOUND,
            "PROPER_NOUN" to PROPER_NOUN_TOP1_LOWER_BOUND,
            "ASCII_DIGIT_SYMBOL" to ASCII_TOP1_LOWER_BOUND,
        )

        for (report in reports) {
            val minRate = minTop1RateByCategory[report.name] ?: continue

            assertEquals(report.total, report.reachableCount, "${report.name} は全件 gold 到達可能であること")

            val top1Rate = report.top1CorrectCount.toDouble() / report.total.toDouble()

            assertTrue(top1Rate >= minRate, "${report.name} baseline top-1 が下限 $minRate を満たすこと（actual=$top1Rate）")
        }

        val hardReport = reports.first { report -> report.name == "HOMOPHONE_HARD" }

        assertTrue(hardReport.tieCount >= 1, "HOMOPHONE_HARD に cost タイが存在すること（PR-D 要否の根拠）")
    }

    private fun printCategoryReport(report: CategoryReport) {
        val reachRate = (report.reachableCount.toDouble() / report.total.toDouble() * PERCENT_SCALE).toInt()
        val top1Rate = (report.top1CorrectCount.toDouble() / report.total.toDouble() * PERCENT_SCALE).toInt()

        println("[${report.name}] n=${report.total} reach=${report.reachableCount} top1=${report.top1CorrectCount} tie=${report.tieCount} reachRate=$reachRate% top1Rate=$top1Rate%")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun currentMaxResidentBytes(): Long {
        return memScoped {
            val usage = alloc<rusage>()

            getrusage(RUSAGE_SELF, usage.ptr)

            usage.ru_maxrss.toLong()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFileBytes(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("ファイルを開けませんでした: $path")

        try {
            fseek(file, 0, SEEK_END)
            val byteCount = ftell(file)
            rewind(file)

            require(byteCount > 0) { "ファイルが空、またはサイズを取得できません: $path" }

            val buffer = ByteArray(byteCount.toInt())

            buffer.usePinned { pinned ->
                val readCount = fread(pinned.addressOf(0), 1.convert(), byteCount.convert(), file)
                require(readCount.toLong() == byteCount) { "読み込みが不完全です: $path ($readCount/$byteCount)" }
            }

            return buffer
        } finally {
            fclose(file)
        }
    }

    /** 1 カテゴリの計測結果。 */
    private data class CategoryReport(
        val name: String,
        val total: Int,
        val reachableCount: Int,
        val top1CorrectCount: Int,
        val tieCount: Int,
    )

    private companion object {

        /** NORMAL baseline top-1 の下限（U2a 計測 100%）。 */
        private const val NORMAL_TOP1_LOWER_BOUND = 0.80

        /** HOMOPHONE baseline top-1 の下限（U2a 計測 72%）。 */
        private const val HOMOPHONE_TOP1_LOWER_BOUND = 0.50

        /** HOMOPHONE_HARD baseline top-1 の下限（U2a 計測 75%）。 */
        private const val HOMOPHONE_HARD_TOP1_LOWER_BOUND = 0.40

        /** BOUNDARY_CHANGE baseline top-1 の下限（U2a 計測 93%）。 */
        private const val BOUNDARY_TOP1_LOWER_BOUND = 0.70

        /** PROPER_NOUN baseline top-1 の下限（U2a 計測 81%）。 */
        private const val PROPER_NOUN_TOP1_LOWER_BOUND = 0.50

        /** ASCII_DIGIT_SYMBOL baseline top-1 の下限（U2a 計測 53%）。 */
        private const val ASCII_TOP1_LOWER_BOUND = 0.30

        /** cost タイ検出のための N-best 件数。 */
        private const val TOP_N_FOR_TIE = 2

        /**
         * compact 経路の RSS delta 上限（MB）。
         *
         * dictBytes 52MB + sortedOrder/entryOffsets/readingByteStart/readingByteLen（各 ~5MB）+ overhead で
         * delta は 200MB 未満が目標。400MB を緩い上限とする。
         * absolute ではなく delta で評価する理由: 同一プロセスで他テストが先に EntryListReadingLexicon（~616MB）
         * を構築すると ru_maxrss（単調増加）が汚染されるため。
         */
        private const val RSS_DELTA_UPPER_BOUND_MB = 400.0

        /** 百分率スケール。 */
        private const val PERCENT_SCALE = 100

        /** 1 MB のバイト数。 */
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
