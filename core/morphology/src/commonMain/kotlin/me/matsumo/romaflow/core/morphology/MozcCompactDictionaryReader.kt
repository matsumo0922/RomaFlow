package me.matsumo.romaflow.core.morphology

/**
 * ビルド時に生成した Mozc compact binary（`mozc_dict.bin` / `mozc_matrix.bin`）を decode する reader。
 *
 * 生成側（Gradle task）と本 reader はバイト列フォーマットを共有する。すべて little-endian。
 *
 * `mozc_dict.bin`:
 * - magic 4B（[DICT_MAGIC]）
 * - count: i32（SPELLING_CORRECTION 除外後のエントリ数）
 * - count 回反復: readingLen:u16, reading:UTF-8, surfaceLen:u16, surface:UTF-8, lid:u16, rid:u16, cost:i16
 *
 * `mozc_matrix.bin`:
 * - magic 4B（[MATRIX_MAGIC]）
 * - dim: i32
 * - dim*dim 回反復: cost:i16（row-major、`cost[prevRid*dim + nextLid]`）
 *
 * 復元するエントリは reading=hiragana の [LexemeEntry]（lcAttr=lid, rcAttr=rid, posId=lid, wcost=cost）。
 */
object MozcCompactDictionaryReader {

    /** `mozc_dict.bin` の先頭マジック。 */
    private const val DICT_MAGIC = "MZD1"

    /** `mozc_matrix.bin` の先頭マジック。 */
    private const val MATRIX_MAGIC = "MZM1"

    /** マジックのバイト長。 */
    internal const val MAGIC_LENGTH = 4

    /** i32 のバイト長。 */
    internal const val INT32_BYTES = 4

    /** i16 / u16 のバイト長。 */
    internal const val INT16_BYTES = 2

    /** 1 エントリの最小バイト数（readingLen + surfaceLen + lid + rid + cost、文字列 0 長時）。 */
    internal const val MIN_ENTRY_BYTES = 10

    /** 1 バイト分のビット幅。 */
    internal const val BITS_PER_BYTE = 8

    /** バイト→符号なし変換のマスク。 */
    internal const val BYTE_MASK = 0xFF

    /**
     * [dictBytes]（`mozc_dict.bin`）を decode して全 [LexemeEntry] を返す。
     *
     * count ヘッダや各フィールド長は信頼せず、すべての読みを境界チェックし、末尾余剰バイトも拒否する
     * （生成物は信頼できるが、本 reader は U2c ランタイム経路にもなるため壊れた binary を確定的に弾く）。
     */
    fun readEntries(dictBytes: ByteArray): List<LexemeEntry> {
        val cursor = MozcByteCursor(dictBytes)

        cursor.expectMagic(DICT_MAGIC)

        val entryCount = cursor.readInt32()

        require(entryCount >= 0) { "エントリ数が不正です: $entryCount" }

        // 確保前に残バイトで上限を検証する（壊れた count での OOM を防ぐ）。1 エントリは最低 [MIN_ENTRY_BYTES]。
        require(entryCount.toLong() * MIN_ENTRY_BYTES <= cursor.remaining()) {
            "エントリ数が残バイトに対し過大です: count=$entryCount remaining=${cursor.remaining()}"
        }

        val entries = ArrayList<LexemeEntry>(entryCount)

        repeat(entryCount) {
            entries.add(readEntry(cursor))
        }

        cursor.requireExhausted()

        return entries
    }

    /** [matrixBytes]（`mozc_matrix.bin`）を decode して連接コスト provider を返す。 */
    fun readConnectionCostProvider(matrixBytes: ByteArray): MozcConnectionCostProvider {
        val cursor = MozcByteCursor(matrixBytes)

        cursor.expectMagic(MATRIX_MAGIC)

        val dimension = cursor.readInt32()

        require(dimension > 0) { "連接行列の次元が不正です: $dimension" }

        val valueCount = dimension.toLong() * dimension.toLong()

        require(valueCount <= Int.MAX_VALUE) { "連接行列が大きすぎます: dim=$dimension" }

        // 確保前に残バイトが dim² 個の i16 と厳密一致することを検証する（過大 dim での OOM を防ぐ）。
        require(valueCount * INT16_BYTES == cursor.remaining().toLong()) {
            "連接行列のサイズが残バイトと不一致: dim=$dimension remaining=${cursor.remaining()}"
        }

        val costs = ShortArray(valueCount.toInt())

        for (index in costs.indices) {
            costs[index] = cursor.readInt16().toShort()
        }

        cursor.requireExhausted()

        return MozcConnectionCostProvider(dimension, costs)
    }

    /**
     * [dictBytes] の [byteOffset] から 1 エントリを decode して [LexemeEntry] を返す。
     *
     * [MozcCompactLexicon] の on-demand decode 経路で使う。[byteOffset] は entryOffsets が保持する
     * エントリ先頭バイト位置（マジック + count ヘッダより後の実データ部分）。
     */
    internal fun decodeEntryAt(dictBytes: ByteArray, byteOffset: Int): LexemeEntry {
        val cursor = MozcByteCursor(dictBytes, startOffset = byteOffset)

        return readEntry(cursor)
    }

    private fun readEntry(cursor: MozcByteCursor): LexemeEntry {
        val reading = cursor.readString()
        val surface = cursor.readString()
        val leftContextId = cursor.readUInt16()
        val rightContextId = cursor.readUInt16()
        val wordCost = cursor.readInt16()

        return LexemeEntry(
            surface = surface,
            reading = reading,
            lcAttr = leftContextId,
            rcAttr = rightContextId,
            posId = leftContextId,
            wcost = wordCost,
        )
    }
}

/**
 * MZD1 / MZM1 バイト列を指定 offset から順に読み進めるカーソル。すべて little-endian。
 *
 * [MozcCompactDictionaryReader] の decode ロジックと [MozcCompactLexicon] の on-demand decode
 * 経路で共用する。[startOffset] を指定することで、バイト列の途中から読み始めることができる。
 */
internal class MozcByteCursor(
    private val bytes: ByteArray,
    startOffset: Int = 0,
) {

    private var offset = startOffset

    fun readInt32(): Int = readLittleEndian(MozcCompactDictionaryReader.INT32_BYTES)

    fun readUInt16(): Int = readLittleEndian(MozcCompactDictionaryReader.INT16_BYTES)

    fun readInt16(): Int = readUInt16().toShort().toInt()

    fun readString(): String {
        val length = readUInt16()

        requireAvailable(length)

        val endIndex = offset + length
        val text = bytes.decodeToString(offset, endIndex)

        offset = endIndex

        return text
    }

    /**
     * u16 で読み始め、その長さ分のバイト列の先頭バイト offset を返す（文字列を decode しない）。
     *
     * [MozcCompactLexicon] の構築パスで、reading バイト列のオフセット・長さを on-the-fly で
     * 取得するために使う（String alloc を避ける）。呼び出し後にカーソルは文字列末尾に進む。
     */
    fun readStringRaw(): Pair<Int, Int> {
        val length = readUInt16()

        requireAvailable(length)

        val startIndex = offset

        offset += length

        return Pair(startIndex, length)
    }

    fun expectMagic(magic: String) {
        requireAvailable(MozcCompactDictionaryReader.MAGIC_LENGTH)

        val actual = bytes.decodeToString(offset, offset + MozcCompactDictionaryReader.MAGIC_LENGTH)

        require(actual == magic) {
            "compact binary のマジックが不正です: expected=$magic actual=$actual"
        }

        offset += MozcCompactDictionaryReader.MAGIC_LENGTH
    }

    fun requireExhausted() {
        require(offset == bytes.size) {
            "compact binary に余剰バイトがあります: offset=$offset size=${bytes.size}"
        }
    }

    fun remaining(): Int = bytes.size - offset

    /** 現在のカーソル位置を返す（エントリ先頭 offset の記録に使う）。 */
    fun currentOffset(): Int = offset

    private fun readLittleEndian(byteCount: Int): Int {
        requireAvailable(byteCount)

        var value = 0

        for (byteIndex in 0 until byteCount) {
            val byteValue = bytes[offset + byteIndex].toInt() and MozcCompactDictionaryReader.BYTE_MASK
            value = value or (byteValue shl (byteIndex * MozcCompactDictionaryReader.BITS_PER_BYTE))
        }

        offset += byteCount

        return value
    }

    private fun requireAvailable(byteCount: Int) {
        require(offset + byteCount <= bytes.size) {
            "compact binary が途中で終端しました: offset=$offset need=$byteCount size=${bytes.size}"
        }
    }
}
