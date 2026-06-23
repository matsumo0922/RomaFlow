package me.matsumo.romaflow.core.ime

/**
 * Android/JVM: classpath（packaged resource）上の Mozc compact binary を読み込む。
 *
 * `:core:ime` の build が `mozc/mozc_dict.bin` / `mozc/mozc_matrix.bin` を JVM/Android variant の
 * resource として同梱する。`mecab-ipadic/` 配下の classpath ロードと同様、ClassLoader 経由で取得する。
 *
 * NOTE: 現状 Android には IME（InputMethodService）の本番 consumer が存在せず、この actual は将来の
 * Android IME 配線に備えた forward-looking 実装。実機での読み込みは Android IME 実装時に検証する。
 */
internal actual object MozcBundleLoader {

    /** 同梱 `mozc_dict.bin` の classpath リソースパス。 */
    private const val DICTIONARY_RESOURCE_PATH = "mozc/mozc_dict.bin"

    /** 同梱 `mozc_matrix.bin` の classpath リソースパス。 */
    private const val MATRIX_RESOURCE_PATH = "mozc/mozc_matrix.bin"

    actual fun loadDictionaryBytes(): ByteArray {
        return readClasspathResource(DICTIONARY_RESOURCE_PATH)
    }

    actual fun loadMatrixBytes(): ByteArray {
        return readClasspathResource(MATRIX_RESOURCE_PATH)
    }

    private fun readClasspathResource(resourcePath: String): ByteArray {
        val classLoader = MozcBundleLoader::class.java.classLoader

        requireNotNull(classLoader) {
            "$resourcePath を読み込むための ClassLoader を取得できませんでした"
        }

        val resourceStream = classLoader.getResourceAsStream(resourcePath)

        requireNotNull(resourceStream) {
            "classpath に $resourcePath が見つかりませんでした"
        }

        return resourceStream.use { stream -> stream.readBytes() }
    }
}
