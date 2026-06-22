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
    private const val MAGIC_LENGTH = 4

    /** i32 のバイト長。 */
    private const val INT32_BYTES = 4

    /** i16 / u16 のバイト長。 */
    private const val INT16_BYTES = 2

    /** 1 バイト分のビット幅。 */
    private const val BITS_PER_BYTE = 8

    /** バイト→符号なし変換のマスク。 */
    private const val BYTE_MASK = 0xFF

    /** [dictBytes]（`mozc_dict.bin`）を decode して全 [LexemeEntry] を返す。 */
    fun readEntries(dictBytes: ByteArray): List<LexemeEntry> {
        val cursor = ByteCursor(dictBytes)

        cursor.expectMagic(DICT_MAGIC)

        val entryCount = cursor.readInt32()
        val entries = ArrayList<LexemeEntry>(entryCount)

        repeat(entryCount) {
            entries.add(readEntry(cursor))
        }

        return entries
    }

    /** [matrixBytes]（`mozc_matrix.bin`）を decode して連接コスト provider を返す。 */
    fun readConnectionCostProvider(matrixBytes: ByteArray): MozcConnectionCostProvider {
        val cursor = ByteCursor(matrixBytes)

        cursor.expectMagic(MATRIX_MAGIC)

        val dimension = cursor.readInt32()
        val valueCount = dimension * dimension
        val costs = ShortArray(valueCount)

        for (index in 0 until valueCount) {
            costs[index] = cursor.readInt16().toShort()
        }

        return MozcConnectionCostProvider(dimension, costs)
    }

    private fun readEntry(cursor: ByteCursor): LexemeEntry {
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

    /**
     * バイト列を先頭から順に読み進めるカーソル。すべて little-endian。
     */
    private class ByteCursor(private val bytes: ByteArray) {

        private var offset = 0

        fun readInt32(): Int = readLittleEndian(INT32_BYTES)

        fun readUInt16(): Int = readLittleEndian(INT16_BYTES)

        fun readInt16(): Int = readUInt16().toShort().toInt()

        fun readString(): String {
            val length = readUInt16()
            val endIndex = offset + length
            val text = bytes.decodeToString(offset, endIndex)

            offset = endIndex

            return text
        }

        fun expectMagic(magic: String) {
            val actual = bytes.decodeToString(offset, offset + MAGIC_LENGTH)

            require(actual == magic) {
                "compact binary のマジックが不正です: expected=$magic actual=$actual"
            }

            offset += MAGIC_LENGTH
        }

        private fun readLittleEndian(byteCount: Int): Int {
            var value = 0

            for (byteIndex in 0 until byteCount) {
                val byteValue = bytes[offset + byteIndex].toInt() and BYTE_MASK
                value = value or (byteValue shl (byteIndex * BITS_PER_BYTE))
            }

            offset += byteCount

            return value
        }
    }
}
