package me.matsumo.romaflow.core.ime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import me.matsumo.romaflow.core.ime.generated.MozcGeneratedDictionaryPaths
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.LiteralContextIds
import me.matsumo.romaflow.core.morphology.MozcCompactDictionaryReader
import me.matsumo.romaflow.core.morphology.MozcCompactLexicon
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

/**
 * macosArm64Test 共通の Mozc 実辞書フィクスチャ。
 *
 * 生成済み Mozc compact binary（[MozcGeneratedDictionaryPaths]）から本番経路と同じ素材で
 * [ReadingLexicon] / [ConnectionCostProvider] を一度だけ構築してキャッシュする。U2b cutover で
 * IPADIC（`IpadicReadingLexicon` / `MomijiConnectionCostProvider`）を撤去した integration テスト群が
 * これを共有する。OOV fallback 付き格子は本番（[RomaFlowEngine]）と同じく [LiteralContextIds.Mozc] を使う。
 */
@OptIn(ExperimentalForeignApi::class)
object MozcTestDictionary {

    /** 素の [MozcCompactLexicon]（fallback なし）。本番 factory が engine へ渡すものと同じ素材。 */
    val readingLexicon: ReadingLexicon by lazy {
        MozcCompactLexicon(readMozcBinaryFile(MozcGeneratedDictionaryPaths.DICTIONARY_BINARY_PATH))
    }

    /** Mozc matrix（`mozc_matrix.bin`）由来の連接コスト provider。 */
    val costProvider: ConnectionCostProvider by lazy {
        MozcCompactDictionaryReader.readConnectionCostProvider(readMozcBinaryFile(MozcGeneratedDictionaryPaths.MATRIX_BINARY_PATH))
    }

    /** OOV fallback（[LiteralContextIds.Mozc]）付きの composite 格子。 */
    val compositeLexicon: ReadingLexicon by lazy {
        buildReadingLexiconWithFallback(readingLexicon, LiteralContextIds.Mozc)
    }
}

/**
 * macosArm64Test 共通: [path] の生成済み Mozc binary を posix で全読みして返す。
 *
 * 生成物（数十 MB）を一括ロードするテスト専用ヘルパー。複数の integration テストが同一処理を
 * 重複させていたため共有する。production の [MozcBundleLoader] は別 source set（macosArm64Main・
 * bundle resource 経由）の独立実装で、本ヘルパーとは統合しない。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readMozcBinaryFile(path: String): ByteArray {
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
