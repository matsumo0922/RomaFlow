package me.matsumo.romaflow.core.morphology

/**
 * sys.dic（MeCab バイナリ）を little-endian で読み進めるためのカーソル付き reader。
 *
 * すべての整数は little-endian（byte[0] が最下位）で格納されているため、各 read で
 * カーソル [position] を進めながら順次デコードする。固定オフセットへ飛ぶ用途では
 * [readUIntAt] / [readUShortAt] / [readShortAt] を用いる。
 */
internal class SysDicByteReader(private val bytes: ByteArray) {

    /** 次に読み取るバイト位置。 */
    var position: Int = 0
        private set

    /** カーソルを絶対位置 [offset] へ移動する。 */
    fun seek(offset: Int) {
        position = offset
    }

    /** カーソル位置から符号なし 32bit 整数を読み、4 バイト進める。 */
    fun readUInt(): UInt {
        val value = readUIntAt(position)

        position += UINT_BYTES

        return value
    }

    /** [offset] から符号なし 32bit 整数を読む（カーソルは動かさない）。 */
    fun readUIntAt(offset: Int): UInt {
        val byte0 = bytes[offset].toInt() and BYTE_MASK
        val byte1 = bytes[offset + 1].toInt() and BYTE_MASK
        val byte2 = bytes[offset + 2].toInt() and BYTE_MASK
        val byte3 = bytes[offset + 3].toInt() and BYTE_MASK

        val packed = byte0 or (byte1 shl 8) or (byte2 shl 16) or (byte3 shl 24)

        return packed.toUInt()
    }

    /** [offset] から符号なし 16bit 整数を読む（カーソルは動かさない）。 */
    fun readUShortAt(offset: Int): Int {
        val byte0 = bytes[offset].toInt() and BYTE_MASK
        val byte1 = bytes[offset + 1].toInt() and BYTE_MASK

        return byte0 or (byte1 shl 8)
    }

    /** [offset] から符号付き 16bit 整数を読む（カーソルは動かさない）。 */
    fun readShortAt(offset: Int): Int {
        val unsigned = readUShortAt(offset)

        return unsigned.toShort().toInt()
    }

    /** [offset] から最初の 0x00 までを UTF-8 文字列としてデコードする。 */
    fun readNullTerminatedString(offset: Int): String {
        var end = offset

        while (end < bytes.size && bytes[end].toInt() != 0) {
            end++
        }

        return bytes.copyOfRange(offset, end).decodeToString()
    }

    private companion object {
        /** 符号なし整数化のための 1 バイトマスク。 */
        const val BYTE_MASK = 0xFF

        /** 32bit 整数のバイト数。 */
        const val UINT_BYTES = 4
    }
}
