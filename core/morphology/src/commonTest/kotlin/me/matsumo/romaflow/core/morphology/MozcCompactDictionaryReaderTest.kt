package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [MozcCompactDictionaryReader] の compact binary decode 挙動を検証する。
 *
 * 生成側と同一フォーマット（little-endian）をテスト内の encoder で再現し、round-trip で
 * エントリ・連接コストが復元できること、マジック不一致で失敗することを確認する。
 */
class MozcCompactDictionaryReaderTest {

    @Test
    fun readsDictionaryEntriesRoundTrip() {
        val expected = listOf(
            LexemeEntry(surface = "以下", reading = "いか", lcAttr = 1845, rcAttr = 1845, posId = 1845, wcost = 1801),
            LexemeEntry(surface = "アアルト", reading = "ああると", lcAttr = 1851, rcAttr = 1851, posId = 1851, wcost = 7129),
        )

        val decoded = MozcCompactDictionaryReader.readEntries(encodeDictionary(expected))

        assertEquals(expected, decoded)
    }

    @Test
    fun readsConnectionMatrixRoundTrip() {
        val dimension = 2
        val costs = shortArrayOf(0, 1500, -42, 300)

        val provider = MozcCompactDictionaryReader.readConnectionCostProvider(encodeMatrix(dimension, costs))

        assertEquals(0, provider.transitionCost(0, 0))
        assertEquals(1500, provider.transitionCost(0, 1))
        assertEquals(-42, provider.transitionCost(1, 0))
        assertEquals(300, provider.transitionCost(1, 1))
    }

    @Test
    fun rejectsDictionaryWithWrongMagic() {
        val corrupted = encodeDictionary(emptyList()).copyOf().also { bytes -> bytes[0] = 'X'.code.toByte() }

        assertFailsWith<IllegalArgumentException> {
            MozcCompactDictionaryReader.readEntries(corrupted)
        }
    }

    @Test
    fun preservesMultiByteUtf8Readings() {
        val entries = listOf(
            LexemeEntry(surface = "ヴァイオリン", reading = "う゛ぁいおりん", lcAttr = 1, rcAttr = 2, posId = 1, wcost = 9000),
        )

        val decoded = MozcCompactDictionaryReader.readEntries(encodeDictionary(entries))

        assertTrue(decoded.single().surface == "ヴァイオリン")
        assertEquals("う゛ぁいおりん", decoded.single().reading)
    }

    private fun encodeDictionary(entries: List<LexemeEntry>): ByteArray {
        val builder = ByteListBuilder()

        builder.putMagic("MZD1")
        builder.putInt32(entries.size)

        for (entry in entries) {
            builder.putString(entry.reading)
            builder.putString(entry.surface)
            builder.putUInt16(entry.lcAttr)
            builder.putUInt16(entry.rcAttr)
            builder.putUInt16(entry.wcost)
        }

        return builder.toByteArray()
    }

    private fun encodeMatrix(dimension: Int, costs: ShortArray): ByteArray {
        val builder = ByteListBuilder()

        builder.putMagic("MZM1")
        builder.putInt32(dimension)

        for (cost in costs) {
            builder.putUInt16(cost.toInt() and UINT16_MASK)
        }

        return builder.toByteArray()
    }

    /** little-endian でバイト列を組み立てるテスト用ヘルパー。 */
    private class ByteListBuilder {

        private val bytes = mutableListOf<Byte>()

        fun putMagic(magic: String) {
            bytes.addAll(magic.encodeToByteArray().toList())
        }

        fun putInt32(value: Int) {
            for (byteIndex in 0 until INT32_BYTES) {
                bytes.add(((value ushr (byteIndex * BITS_PER_BYTE)) and BYTE_MASK).toByte())
            }
        }

        fun putUInt16(value: Int) {
            bytes.add((value and BYTE_MASK).toByte())
            bytes.add(((value ushr BITS_PER_BYTE) and BYTE_MASK).toByte())
        }

        fun putString(text: String) {
            val encoded = text.encodeToByteArray()

            putUInt16(encoded.size)
            bytes.addAll(encoded.toList())
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private companion object {

        /** i32 のバイト長。 */
        private const val INT32_BYTES = 4

        /** 1 バイト分のビット幅。 */
        private const val BITS_PER_BYTE = 8

        /** バイトマスク。 */
        private const val BYTE_MASK = 0xFF

        /** u16 マスク。 */
        private const val UINT16_MASK = 0xFFFF
    }
}
