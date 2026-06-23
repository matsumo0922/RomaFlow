package me.matsumo.romaflow.core.ime

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

/**
 * macosArm64: IME bundle に同梱した Mozc compact binary を resource として読み込む。
 *
 * [RomaFlowEngine] は IME プロセス内で生成されるため、[NSBundle.mainBundle] は IME bundle を指す。
 * `mozc_dict.bin` / `mozc_matrix.bin` は bundle の Resources に packaging 済みであり、
 * [NSBundle.pathForResource] で絶対パスを解決して posix で読む。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object MozcBundleLoader {

    /** 同梱 `mozc_dict.bin` の resource 名（拡張子なし）。 */
    private const val DICTIONARY_RESOURCE_NAME = "mozc_dict"

    /** 同梱 `mozc_matrix.bin` の resource 名（拡張子なし）。 */
    private const val MATRIX_RESOURCE_NAME = "mozc_matrix"

    /** compact binary の resource 拡張子。 */
    private const val RESOURCE_EXTENSION = "bin"

    actual fun loadDictionaryBytes(): ByteArray {
        return readBundleResource(DICTIONARY_RESOURCE_NAME)
    }

    actual fun loadMatrixBytes(): ByteArray {
        return readBundleResource(MATRIX_RESOURCE_NAME)
    }

    private fun readBundleResource(resourceName: String): ByteArray {
        val path = NSBundle.mainBundle.pathForResource(resourceName, RESOURCE_EXTENSION)

        requireNotNull(path) {
            "IME bundle に $resourceName.$RESOURCE_EXTENSION が見つかりませんでした"
        }

        return readFileBytes(path)
    }

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
}
