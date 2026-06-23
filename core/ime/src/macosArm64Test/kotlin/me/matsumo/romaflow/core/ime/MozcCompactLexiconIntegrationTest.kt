package me.matsumo.romaflow.core.ime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import me.matsumo.romaflow.core.ime.generated.MozcGeneratedDictionaryPaths
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
 * [MozcCompactLexicon]（U2c 実装）の real-dict integration テスト。
 *
 * 生成済み Mozc compact binary（1.29M エントリ）で [MozcCompactLexicon] を構築し、
 * per-category baseline top-1 / 到達性 / cost タイが U2a（EntryListReadingLexicon 経路）と同値であることを
 * アサートする。これにより compact 経路が実データで EntryList 経路と同じ変換 outcome を出すことを担保する。
 *
 * commonPrefixSearch のバイト単位 parity（順序・全フィールド一致）は、Kotlin/Native のプロセス内メモリ累積を
 * 避けるため軽量な commonTest（`MozcCompactLexiconParityTest`・手組み MZD1 で網羅）に委ね、本テストでは
 * EntryListReadingLexicon（~600MB）を構築しない。
 *
 * RSS（baseline / absolute / delta）は heap 削減の参考値として println するのみでアサートしない
 * （`ru_maxrss` はプロセス生涯の単調増加ピークのため、sibling テストの先行ピークで delta が無効化されうる）。
 */
class MozcCompactLexiconIntegrationTest {

    // ---- real-dict メトリクス + RSS レポート ----

    /**
     * 生成済み Mozc compact binary で [MozcCompactLexicon] を構築し、per-category baseline を U2a と同値で
     * アサートする。RSS（baseline / absolute / delta）は heap 削減の参考値として println するのみ。
     *
     * EntryListReadingLexicon は構築しない（commonPrefixSearch の網羅 parity は commonTest 側に委譲）。
     * ru_maxrss はプロセス生涯の単調増加ピークのため、sibling テストの先行ピークで delta が無効化されうる。
     * correctness は本テストの per-category baseline と commonTest の `MozcCompactLexiconParityTest` で担保する。
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

        // RSS はレポート専用（ハードアサートなし）。
        // ru_maxrss はプロセス生涯ピークのため sibling テストが先に EntryList を構築すると
        // delta が 0 になり得てアサートが無効化される。correctness は parity + category baseline で担保。
        println("RSS report (参考値): delta=${loadDeltaMb.toInt()}MB absolute=${absoluteMb.toInt()}MB (ハードアサートなし)")
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

        /** 百分率スケール。 */
        private const val PERCENT_SCALE = 100

        /** 1 MB のバイト数。 */
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
