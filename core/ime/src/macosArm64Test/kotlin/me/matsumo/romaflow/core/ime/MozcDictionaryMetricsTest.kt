package me.matsumo.romaflow.core.ime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import me.matsumo.romaflow.core.ime.generated.MozcGeneratedDictionaryPaths
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.EntryListReadingLexicon
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LiteralContextIds
import me.matsumo.romaflow.core.morphology.MozcCompactDictionaryReader
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback
import me.matsumo.romaflow.core.morphology.isLiteralFallbackArc
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
 * ビルド時生成した Mozc compact binary を読み込み、決定論メトリクス（辞書件数 / per-category baseline top-1 /
 * 到達性 / cost タイ率 / OOV・literal 率 / RSS 実測）を計測する shadow テスト。
 *
 * env 非依存（生成 binary の絶対パスは [MozcGeneratedDictionaryPaths]）で、ライブ挙動・公開経路は不変。
 * U1 の Mozc プローブ数値を bundled データで再現し、cutover（U2b）の go/no-go と PR-D 要否の材料にする。
 */
class MozcDictionaryMetricsTest {

    /** 1 カテゴリの計測結果。 */
    private data class CategoryReport(
        val name: String,
        val total: Int,
        val reachableCount: Int,
        val top1CorrectCount: Int,
        val tieCount: Int,
    )

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun reportsDeterministicMozcMetrics() {
        val baselineMaxResidentBytes = currentMaxResidentBytes()

        val dictBytes = readFileBytes(MozcGeneratedDictionaryPaths.DICTIONARY_BINARY_PATH)
        val matrixBytes = readFileBytes(MozcGeneratedDictionaryPaths.MATRIX_BINARY_PATH)

        val entries = MozcCompactDictionaryReader.readEntries(dictBytes)
        val costProvider = MozcCompactDictionaryReader.readConnectionCostProvider(matrixBytes)
        val lexicon = buildReadingLexiconWithFallback(EntryListReadingLexicon(entries), LiteralContextIds.Mozc)

        val loadedMaxResidentBytes = currentMaxResidentBytes()

        println("=== Mozc Deterministic Metrics (bundled, shadow) ===")
        println("entries=${entries.size} dictBytes=${dictBytes.size} matrixBytes=${matrixBytes.size}")
        printResidentMemory(baselineMaxResidentBytes, loadedMaxResidentBytes)

        val reports = evaluateAllCategories(lexicon, costProvider)
        val literalRate = literalFallbackArcRate(lexicon, costProvider)

        reports.forEach(::printCategoryReport)
        println("literalFallbackArcRate=${formatRate(literalRate)}")

        assertEquals(EXPECTED_DICT_ENTRIES, entries.size, "bundled 辞書件数が pin と一致すること")
        assertNormalCategoryReproducesBaseline(reports)
    }

    private fun evaluateAllCategories(lexicon: ReadingLexicon, costProvider: ConnectionCostProvider): List<CategoryReport> {
        val categories = listOf(
            "NORMAL" to EvaluationCorpus.normal,
            "HOMOPHONE" to EvaluationCorpus.homophone,
            "HOMOPHONE_HARD" to EvaluationCorpus.homophoneHard,
            "BOUNDARY_CHANGE" to EvaluationCorpus.boundaryChange,
            "PROPER_NOUN" to EvaluationCorpus.properNoun,
            "ASCII_DIGIT_SYMBOL" to EvaluationCorpus.asciiDigitSymbol,
        )

        return categories.map { (name, entries) -> evaluateCategory(name, entries, lexicon, costProvider) }
    }

    private fun evaluateCategory(
        name: String,
        entries: List<CorpusEntry>,
        lexicon: ReadingLexicon,
        costProvider: ConnectionCostProvider,
    ): CategoryReport {
        var reachableCount = 0
        var top1CorrectCount = 0
        var tieCount = 0

        for (entry in entries) {
            if (isSurfaceReachable(entry, lexicon, costProvider)) {
                reachableCount++
            }

            val ranked = ReadingLatticeDecoder.nBest(entry.reading, lexicon, costProvider, TOP_N_FOR_TIE)

            if (topSurfaceMatches(ranked, entry.expectedSurface)) {
                top1CorrectCount++
            }

            if (hasTopCostTie(ranked)) {
                tieCount++
            }
        }

        return CategoryReport(
            name = name,
            total = entries.size,
            reachableCount = reachableCount,
            top1CorrectCount = top1CorrectCount,
            tieCount = tieCount,
        )
    }

    private fun isSurfaceReachable(entry: CorpusEntry, lexicon: ReadingLexicon, costProvider: ConnectionCostProvider): Boolean {
        val path = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = entry.reading,
            surface = entry.expectedSurface,
            lexicon = lexicon,
            costProvider = costProvider,
        )

        return path != null
    }

    private fun topSurfaceMatches(ranked: List<Pair<Long, List<LexemeEntry>>>, expectedSurface: String): Boolean {
        val topPath = ranked.firstOrNull()?.second ?: return false

        return topPath.joinToString(separator = "") { lexeme -> lexeme.surface } == expectedSurface
    }

    private fun hasTopCostTie(ranked: List<Pair<Long, List<LexemeEntry>>>): Boolean {
        if (ranked.size < 2) return false

        return ranked[0].first == ranked[1].first
    }

    private fun literalFallbackArcRate(lexicon: ReadingLexicon, costProvider: ConnectionCostProvider): Double {
        var totalArcs = 0
        var literalArcs = 0

        for (entry in EvaluationCorpus.all) {
            val topPath = ReadingLatticeDecoder.nBest(entry.reading, lexicon, costProvider, 1).firstOrNull()?.second.orEmpty()

            totalArcs += topPath.size
            literalArcs += topPath.count { lexeme -> isLiteralFallbackArc(lexeme, LiteralContextIds.Mozc) }
        }

        if (totalArcs == 0) return 0.0

        return literalArcs.toDouble() / totalArcs.toDouble()
    }

    private fun assertNormalCategoryReproducesBaseline(reports: List<CategoryReport>) {
        val normal = reports.first { report -> report.name == "NORMAL" }

        assertEquals(normal.total, normal.reachableCount, "NORMAL は全件 gold 到達可能であること")

        val top1Rate = normal.top1CorrectCount.toDouble() / normal.total.toDouble()

        assertTrue(top1Rate >= NORMAL_TOP1_LOWER_BOUND, "NORMAL baseline top-1 が下限を満たすこと（actual=$top1Rate）")
    }

    private fun printCategoryReport(report: CategoryReport) {
        val reachRate = formatRate(report.reachableCount.toDouble() / report.total.toDouble())
        val top1Rate = formatRate(report.top1CorrectCount.toDouble() / report.total.toDouble())

        println(
            "[${report.name}] n=${report.total} reach=${report.reachableCount} top1=${report.top1CorrectCount}" +
                " tie=${report.tieCount} reachRate=$reachRate top1Rate=$top1Rate",
        )
    }

    private fun printResidentMemory(baselineBytes: Long, loadedBytes: Long) {
        val deltaMegabytes = (loadedBytes - baselineBytes).toDouble() / BYTES_PER_MEGABYTE
        val loadedMegabytes = loadedBytes.toDouble() / BYTES_PER_MEGABYTE

        println("maxResidentMB=${formatMegabytes(loadedMegabytes)} deltaMB=${formatMegabytes(deltaMegabytes)}")
    }

    private fun formatRate(rate: Double): String {
        val percent = (rate * PERCENT_SCALE).toInt()

        return "$percent%"
    }

    private fun formatMegabytes(megabytes: Double): String {
        return megabytes.toInt().toString()
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

    private companion object {

        /** bundled 辞書の期待エントリ数（SPELLING_CORRECTION 62 行を除外した件数）。 */
        private const val EXPECTED_DICT_ENTRIES = 1_289_014

        /** NORMAL baseline top-1 の下限（U1 計測 100% に対し回帰検出用の緩い下限）。 */
        private const val NORMAL_TOP1_LOWER_BOUND = 0.80

        /** cost タイ検出のための N-best 件数。 */
        private const val TOP_N_FOR_TIE = 2

        /** 百分率スケール。 */
        private const val PERCENT_SCALE = 100

        /** 1 MB のバイト数。 */
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
    }
}
