package me.matsumo.romaflow.core.morphology

/**
 * JVM/Android では base64 の `SYS` 定数は提供されず、momiji-ipadic-resources が classpath 上に
 * 置く `mecab-ipadic/sys.dic`（生バイナリ）を読み込む。momiji 本体の `momijiLoadSysDic` と
 * 同じリソースを参照する。
 */
internal actual fun loadSysDicBytes(): ByteArray {
    val classLoader = object {}.javaClass.classLoader

    requireNotNull(classLoader) {
        "sys.dic を読み込むための ClassLoader を取得できませんでした"
    }

    val resourceStream = classLoader.getResourceAsStream(SYS_DIC_RESOURCE_PATH)

    requireNotNull(resourceStream) {
        "classpath に $SYS_DIC_RESOURCE_PATH が見つかりませんでした"
    }

    return resourceStream.use { stream -> stream.readBytes() }
}

/** momiji-ipadic-resources が classpath に配置する sys.dic のリソースパス。 */
private const val SYS_DIC_RESOURCE_PATH = "mecab-ipadic/sys.dic"
