import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.build.konfig)
}

// local.properties から OpenAI 設定を読み込む。未設定なら空文字などの既定値でビルドを通す。
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localProperty(key: String, defaultValue: String): String {
    return localProperties.getProperty(key, defaultValue)
}

// === Mozc OSS 辞書 compact binary のビルド時生成（hermetic・shadow・U2a）===
// 固定 commit から dictionary00〜09.txt / connection_single_column.txt を取得し SHA-256 を検証してから、
// compact binary（mozc_dict.bin / mozc_matrix.bin）と参照パス定数を build/generated/mozc 配下へ生成する。
// 生成物は build 配下＝git 管理外。出荷 app には同梱せず（U2b cutover で同梱）、:core:ime macosArm64Test の
// 決定論メトリクスからのみ参照する＝ライブ挙動・APK サイズは不変。
val mozcSourcesPropertiesFile = projectDir.resolve("../morphology/mozc-dictionary-sources.properties")
val mozcGeneratedRoot = layout.buildDirectory.dir("generated/mozc")
val mozcGeneratedBinDir = mozcGeneratedRoot.map { it.dir("bin") }
val mozcGeneratedKotlinDir = mozcGeneratedRoot.map { it.dir("kotlin") }

// U2b: 生成した Mozc compact binary を JVM/Android variant の classpath リソース（`mozc/` 配下）として
// 同梱するための staging ディレクトリ。`MozcBundleLoader.android` が getResourceAsStream で読む。
val mozcGeneratedResourceDir = mozcGeneratedRoot.map { it.dir("resources") }
val mozcDownloadCacheDir = gradle.gradleUserHomeDir.resolve("caches/romaflow-mozc")

val generateMozcDictionary = tasks.register("generateMozcDictionary") {
    description = "Download pinned Mozc OSS dictionary, verify SHA-256, and emit compact binaries (shadow)."

    val sourcesFile = mozcSourcesPropertiesFile
    val binDir = mozcGeneratedBinDir.get().asFile
    val kotlinDir = mozcGeneratedKotlinDir.get().asFile
    val cacheDir = mozcDownloadCacheDir

    inputs.file(sourcesFile)
    outputs.dir(mozcGeneratedBinDir)
    outputs.dir(mozcGeneratedKotlinDir)

    doLast {
        generateMozcCompactBinaries(sourcesFile, binDir, kotlinDir, cacheDir)
    }
}

// U2b: 生成済み `mozc_dict.bin` / `mozc_matrix.bin` を classpath リソース（`mozc/` 配下）へ複製する。
// JVM/Android variant の resources に登録し、Android（`MozcBundleLoader.android`）が getResourceAsStream で読む。
val stageMozcAndroidResources = tasks.register<Copy>("stageMozcAndroidResources") {
    description = "Stage generated Mozc compact binaries as classpath resources under mozc/."

    dependsOn(generateMozcDictionary)

    from(mozcGeneratedBinDir) {
        include("mozc_dict.bin", "mozc_matrix.bin")
    }
    into(mozcGeneratedResourceDir.map { it.dir("mozc") })
}

kotlin {
    android {
        namespace = "me.matsumo.romaflow.core.ime"
    }

    macosArm64()

    @OptIn(ExperimentalSwiftExportDsl::class)
    swiftExport {
        moduleName = "RomaFlowImeCore"
        flattenPackage = "me.matsumo.romaflow.core.ime"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.morphology)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.wanakana)
            implementation(libs.napier)
            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        androidMain {
            dependencies {
                implementation(libs.ktor.okhttp)
            }

            // U2b: 同梱 Mozc binary を Android variant の classpath リソース（`mozc/` 配下）へ載せる。
            // staging task を builtBy に指定し、リソース収集前に生成・複製を走らせる。
            resources.srcDir(files(mozcGeneratedResourceDir).builtBy(stageMozcAndroidResources))
        }

        macosArm64Main.dependencies {
            implementation(libs.ktor.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }

        macosArm64Test {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }

            // 生成した Mozc 参照パス定数を macosArm64Test に追加し、コンパイル前に生成 task を走らせる。
            kotlin.srcDir(files(mozcGeneratedKotlinDir).builtBy(generateMozcDictionary))
        }
    }
}

buildkonfig {
    packageName = "me.matsumo.romaflow.core.ime"

    defaultConfigs {
        buildConfigField(STRING, "OPENAI_API_KEY", localProperty("OPENAI_API_KEY", ""))
        buildConfigField(STRING, "OPENAI_BASE_URL", localProperty("OPENAI_BASE_URL", "https://api.openai.com/v1"))
        buildConfigField(STRING, "OPENAI_MODEL", localProperty("OPENAI_MODEL", "gpt-5-nano"))
    }
}

// === Mozc compact binary 生成のヘルパー（hermetic 取得・検証・パース・書き出し）===

fun generateMozcCompactBinaries(
    sourcesFile: File,
    binDir: File,
    kotlinDir: File,
    cacheDir: File,
) {
    val properties = Properties().apply {
        sourcesFile.inputStream().use { load(it) }
    }

    val commit = requireMozcProperty(properties, "mozc.commit")
    val baseUrl = requireMozcProperty(properties, "mozc.baseUrl")
    val subdir = requireMozcProperty(properties, "mozc.subdir")
    val expectedDictEntries = requireMozcProperty(properties, "mozc.expectedDictEntries").toInt()
    val expectedMatrixDim = requireMozcProperty(properties, "mozc.expectedMatrixDim").toInt()

    val sha256ByFileName = properties.stringPropertyNames()
        .filter { name -> name.startsWith("sha256.") }
        .associate { name -> name.removePrefix("sha256.") to properties.getProperty(name) }

    val resolvedFiles = sha256ByFileName.entries.associate { (fileName, expectedSha256) ->
        val url = "$baseUrl/$commit/$subdir/$fileName"
        val cacheFile = cacheDir.resolve(expectedSha256).resolve(fileName)

        fileName to downloadAndVerifyMozcFile(url, expectedSha256, cacheFile)
    }

    binDir.mkdirs()

    val dictionaryFiles = resolvedFiles.filterKeys { name -> name.startsWith("dictionary") && name.endsWith(".txt") }
        .toSortedMap()
        .values
        .toList()
    val connectionFile = resolvedFiles["connection_single_column.txt"]
        ?: error("connection_single_column.txt が pin にありません")

    val dictBinFile = binDir.resolve("mozc_dict.bin")
    val matrixBinFile = binDir.resolve("mozc_matrix.bin")

    writeMozcCompactDictionary(dictionaryFiles, dictBinFile, expectedDictEntries)
    writeMozcCompactMatrix(connectionFile, matrixBinFile, expectedMatrixDim)

    resolvedFiles["README.txt"]?.copyTo(binDir.resolve("mozc_dictionary_README.txt"), overwrite = true)

    writeMozcGeneratedPathConstants(kotlinDir, dictBinFile, matrixBinFile)
}

fun requireMozcProperty(properties: Properties, key: String): String {
    return requireNotNull(properties.getProperty(key)) { "Mozc pin プロパティが見つかりません: $key" }
}

fun downloadAndVerifyMozcFile(url: String, expectedSha256: String, cacheFile: File): File {
    if (cacheFile.exists() && sha256Hex(cacheFile.readBytes()) == expectedSha256) {
        return cacheFile
    }

    cacheFile.parentFile.mkdirs()

    // ネットワーク取得は cold cache 時のみ（SHA-256 キャッシュは clean 耐性）。CI ハング回避に timeout を設定する。
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = mozcDownloadTimeoutMs
        readTimeout = mozcDownloadTimeoutMs
    }
    val downloadedBytes = connection.getInputStream().use { stream -> stream.readBytes() }
    val actualSha256 = sha256Hex(downloadedBytes)

    require(actualSha256 == expectedSha256) {
        "SHA-256 不一致: $url expected=$expectedSha256 actual=$actualSha256"
    }

    cacheFile.writeBytes(downloadedBytes)

    return cacheFile
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

    return digest.joinToString(separator = "") { byte ->
        ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
    }
}

fun writeMozcCompactDictionary(dictionaryFiles: List<File>, outputFile: File, expectedEntries: Int) {
    val body = ByteArrayOutputStream(64 * 1024 * 1024)
    var totalRows = 0
    var writtenRows = 0

    for (dictionaryFile in dictionaryFiles) {
        dictionaryFile.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue

                val fields = line.split('\t')

                when (fields.size) {
                    mozcDictColumnCount -> {
                        totalRows++
                        writtenRows++
                        writeMozcDictEntry(body, fields)
                    }
                    mozcDictLabeledColumnCount -> {
                        require(fields[mozcDictLabelIndex] == mozcSpellingCorrectionLabel) {
                            "未知の 6 列ラベルです: ${fields[mozcDictLabelIndex]}"
                        }
                        totalRows++
                    }
                    else -> error("辞書の列数が不正です（${fields.size}）: $line")
                }
            }
        }
    }

    require(totalRows == expectedEntries) {
        "辞書行数が pin と不一致: expected=$expectedEntries actual=$totalRows"
    }

    outputFile.outputStream().buffered().use { out ->
        out.write(mozcDictMagic.toByteArray(Charsets.US_ASCII))
        writeInt32Le(out, writtenRows)
        body.writeTo(out)
    }

    println("[mozc] dict rows=$totalRows written=$writtenRows excluded=${totalRows - writtenRows}")
}

fun writeMozcDictEntry(out: OutputStream, fields: List<String>) {
    val reading = fields[0]
    val leftContextId = fields[1].toInt()
    val rightContextId = fields[2].toInt()
    val wordCost = fields[3].toInt()
    val surface = fields[4]

    require(leftContextId in 0..uint16Max && rightContextId in 0..uint16Max) {
        "連接 ID が u16 範囲外です: lid=$leftContextId rid=$rightContextId"
    }
    require(wordCost in Short.MIN_VALUE..Short.MAX_VALUE) {
        "コストが i16 範囲外です: $wordCost"
    }

    writeUtf8String(out, reading)
    writeUtf8String(out, surface)
    writeUInt16Le(out, leftContextId)
    writeUInt16Le(out, rightContextId)
    writeUInt16Le(out, wordCost and uint16Mask)
}

fun writeMozcCompactMatrix(connectionFile: File, outputFile: File, expectedDimension: Int) {
    connectionFile.bufferedReader().use { reader ->
        val dimensionLine = reader.readLine()?.trim() ?: error("連接ファイルが空です")
        val dimension = dimensionLine.toInt()

        require(dimension == expectedDimension) {
            "連接行列の次元が pin と不一致: expected=$expectedDimension actual=$dimension"
        }

        val expectedValues = dimension.toLong() * dimension.toLong()

        outputFile.outputStream().buffered().use { out ->
            out.write(mozcMatrixMagic.toByteArray(Charsets.US_ASCII))
            writeInt32Le(out, dimension)

            val valueCount = writeMozcMatrixValues(reader, out)

            require(valueCount == expectedValues) {
                "連接コスト数が不一致: expected=$expectedValues actual=$valueCount"
            }
        }
    }

    println("[mozc] matrix dim=$expectedDimension written")
}

fun writeMozcMatrixValues(reader: BufferedReader, out: OutputStream): Long {
    var valueCount = 0L
    var line = reader.readLine()

    while (line != null) {
        val trimmed = line.trim()

        if (trimmed.isNotEmpty()) {
            val cost = trimmed.toInt()

            require(cost in Short.MIN_VALUE..Short.MAX_VALUE) {
                "連接コストが i16 範囲外です: $cost"
            }

            writeUInt16Le(out, cost and uint16Mask)
            valueCount++
        }

        line = reader.readLine()
    }

    return valueCount
}

fun writeMozcGeneratedPathConstants(kotlinDir: File, dictBinFile: File, matrixBinFile: File) {
    val packageDir = kotlinDir.resolve("me/matsumo/romaflow/core/ime/generated")
    packageDir.mkdirs()

    val dictPath = dictBinFile.absolutePath.escapeForKotlinLiteral()
    val matrixPath = matrixBinFile.absolutePath.escapeForKotlinLiteral()

    val source = """
        package me.matsumo.romaflow.core.ime.generated

        /**
         * ビルド時生成: Mozc compact binary の絶対パス（生成物のため commit しない）。
         *
         * `:core:ime` macosArm64Test の決定論メトリクスが env 非依存で生成済み binary を読むために参照する。
         */
        internal object MozcGeneratedDictionaryPaths {

            /** `mozc_dict.bin` の絶対パス。 */
            const val DICTIONARY_BINARY_PATH: String = "$dictPath"

            /** `mozc_matrix.bin` の絶対パス。 */
            const val MATRIX_BINARY_PATH: String = "$matrixPath"
        }

    """.trimIndent()

    packageDir.resolve("MozcGeneratedDictionaryPaths.kt").writeText(source + "\n")
}

fun String.escapeForKotlinLiteral(): String {
    return replace("\\", "\\\\").replace("$", "\\$").replace("\"", "\\\"")
}

fun writeUtf8String(out: OutputStream, text: String) {
    val encoded = text.toByteArray(Charsets.UTF_8)

    require(encoded.size <= uint16Max) { "文字列が長すぎます: ${encoded.size}B" }

    writeUInt16Le(out, encoded.size)
    out.write(encoded)
}

fun writeUInt16Le(out: OutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
}

fun writeInt32Le(out: OutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 24) and 0xFF)
}

/** Mozc 辞書 1 行の通常列数（読み/左ID/右ID/コスト/表層）。 */
val mozcDictColumnCount = 5

/** SPELLING_CORRECTION ラベル付きの列数。 */
val mozcDictLabeledColumnCount = 6

/** ラベル列の index。 */
val mozcDictLabelIndex = 5

/** 誤記補正エントリのラベル（通常候補から除外）。 */
val mozcSpellingCorrectionLabel = "SPELLING_CORRECTION"

/** `mozc_dict.bin` の先頭マジック。 */
val mozcDictMagic = "MZD1"

/** `mozc_matrix.bin` の先頭マジック。 */
val mozcMatrixMagic = "MZM1"

/** u16 の最大値。 */
val uint16Max = 0xFFFF

/** u16 マスク。 */
val uint16Mask = 0xFFFF

/** Mozc 入力ファイル取得の connect/read timeout（ms）。cold cache 時のみ使用。 */
val mozcDownloadTimeoutMs = 60_000
