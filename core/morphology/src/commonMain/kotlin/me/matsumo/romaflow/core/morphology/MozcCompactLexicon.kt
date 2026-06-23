package me.matsumo.romaflow.core.morphology

/**
 * Mozc compact binary（MZD1 形式）の生バイト列を常駐させ、エントリを on-demand decode する [ReadingLexicon]。
 *
 * [EntryListReadingLexicon] が全エントリを List<LexemeEntry> として materialized するのに対し、
 * 本クラスは [dictBytes] をそのまま保持し（52MB 常駐）、エントリ List は一切常駐させない。
 *
 * 構築時に 1 パスで以下のインデックスを作成する:
 * - [entryOffsets]: 各エントリの先頭バイト offset（[dictBytes] 内の絶対位置）。
 * - [sortedOrder]: エントリ index を reading バイト列辞書順 → wcost 昇順で安定ソートした配列。
 *
 * `commonPrefixSearch` は endOffset ごとに [sortedOrder] 上で二分探索し、ヒット範囲のエントリを
 * その場で decode して [LexemeMatch] を返す（LexemeEntry は呼び出し毎の短命オブジェクト）。
 *
 * ## parity 保証
 * - readingKey は Mozc 辞書が hiragana を格納するため、[IpadicReadingLexicon.katakanaToHiragana] 正規化後と
 *   バイト列辞書順が一致する（純 hiragana の UTF-8 はコードポイント順）。
 * - `sortedOrder` の ソート順（reading 昇順 → wcost 昇順・安定）は [EntryListReadingLexicon] の
 *   `buildReverseIndex`（`sortedBy { wcost }`・stable）と等価であるため、結果の順序も一致する。
 *
 * ## homophone 逆引き
 * [buildStreamingHomophoneIndex] で [sortedOrder] の連続同 readingKey をストリーミンググループ化し、
 * 中間 Map<String, List<LexemeEntry>> を作らずに同音語 index を構築できる。
 */
class MozcCompactLexicon(dictBytes: ByteArray) : ReadingLexicon {

    /**
     * MZD1 バイト列（52MB）。エントリ先頭バイト offset の参照元として保持し、常駐させる。
     * List<LexemeEntry> は作らない。
     */
    private val dictBytes: ByteArray = dictBytes

    /** 各エントリの先頭バイト offset（[this.dictBytes] 内の絶対位置）。count 件。 */
    private val entryOffsets: IntArray

    /**
     * エントリ index を **reading バイト列辞書順 → wcost 昇順** で安定ソートした配列。
     *
     * 二分探索で prefix の equal-range を特定し、on-demand decode のカーソル位置を得るのに使う。
     */
    private val sortedOrder: IntArray

    /**
     * [sortedOrder] 上の各エントリの reading バイト列の先頭 offset と長さを保持する配列。
     *
     * 二分探索時に String alloc せず辞書バイト列同士を直接比較するためにキャッシュする。
     * `readingByteStart[i]` と `readingByteLen[i]` が sortedOrder[i] のエントリの reading を示す。
     */
    private val readingByteStart: IntArray

    /** reading バイト列の長さ（バイト数）。[readingByteStart] と対をなす。 */
    private val readingByteLen: IntArray

    init {
        val cursor = MozcByteCursor(dictBytes)

        cursor.expectMagic(DICT_MAGIC_EXPECTED)

        val entryCount = cursor.readInt32()

        require(entryCount >= 0) { "エントリ数が不正です: $entryCount" }
        require(entryCount.toLong() * MIN_ENTRY_BYTES <= cursor.remaining()) {
            "エントリ数が残バイトに対し過大です: count=$entryCount remaining=${cursor.remaining()}"
        }

        val offsets = IntArray(entryCount)
        val rByteStart = IntArray(entryCount)
        val rByteLen = IntArray(entryCount)
        val wcostArray = IntArray(entryCount)

        // 1 パスでエントリ先頭 offset・reading バイト位置・wcost を収集する（LexemeEntry alloc なし）。
        for (index in 0 until entryCount) {
            offsets[index] = cursor.currentOffset()

            val (readStart, readLen) = cursor.readStringRaw()

            rByteStart[index] = readStart
            rByteLen[index] = readLen

            // surface を読み飛ばす
            cursor.readStringRaw()

            // lid / rid / wcost を読む（lid=2B, rid=2B, wcost=2B の順）
            cursor.readUInt16() // lid
            cursor.readUInt16() // rid
            wcostArray[index] = cursor.readInt16()
        }

        entryOffsets = offsets
        readingByteStart = rByteStart
        readingByteLen = rByteLen

        // reading バイト列辞書順 → wcost 昇順 で安定ソート（Kotlin の sortedArray は stable）。
        val order = (0 until entryCount).toMutableList()

        order.sortedWith(
            Comparator { indexA, indexB ->
                val readingComparison = compareReadingBytes(dictBytes, rByteStart, rByteLen, indexA, indexB)

                if (readingComparison != 0) readingComparison else wcostArray[indexA] - wcostArray[indexB]
            },
        ).forEachIndexed { sortedIndex, entryIndex -> order[sortedIndex] = entryIndex }

        sortedOrder = order.toIntArray()
    }

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val results = mutableListOf<LexemeMatch>()

        for (endOffset in (startOffset + 1)..reading.length) {
            val prefixBytes = reading.substring(startOffset, endOffset).encodeToByteArray()

            val (rangeStart, rangeEnd) = equalRange(prefixBytes)

            for (sortedIndex in rangeStart until rangeEnd) {
                val entryIndex = sortedOrder[sortedIndex]
                val lexeme = MozcCompactDictionaryReader.decodeEntryAt(dictBytes, entryOffsets[entryIndex])

                results.add(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
            }
        }

        return results
    }

    /**
     * [sortedOrder] 上で [prefixBytes] と reading バイト列が完全一致するエントリの
     * equal-range（[start, end)）を二分探索で返す。
     *
     * Mozc の reading は純 hiragana のため UTF-8 バイト順 = コードポイント順 = 辞書順が一致する。
     */
    private fun equalRange(prefixBytes: ByteArray): Pair<Int, Int> {
        val lowerBound = lowerBound(prefixBytes)
        val upperBound = upperBound(prefixBytes, lowerBound)

        return Pair(lowerBound, upperBound)
    }

    /** [prefixBytes] 以上の最初のエントリ位置（lower bound）を返す。 */
    private fun lowerBound(prefixBytes: ByteArray): Int {
        var low = 0
        var high = sortedOrder.size

        while (low < high) {
            val mid = (low + high).ushr(1)
            val entryIndex = sortedOrder[mid]
            val cmp = compareReadingWithBytes(entryIndex, prefixBytes)

            if (cmp < 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        return low
    }

    /** [prefixBytes] より大きい最初のエントリ位置（upper bound）を [hint] から探す。 */
    private fun upperBound(prefixBytes: ByteArray, hint: Int): Int {
        var low = hint
        var high = sortedOrder.size

        while (low < high) {
            val mid = (low + high).ushr(1)
            val entryIndex = sortedOrder[mid]
            val cmp = compareReadingWithBytes(entryIndex, prefixBytes)

            if (cmp <= 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        return low
    }

    /**
     * [sortedOrder[entryIndex]] のエントリの reading バイト列と [targetBytes] を比較する。
     *
     * reading バイト列が [targetBytes] と完全一致する場合 0、辞書順で前なら負値、後なら正値を返す。
     * reading バイト列長が [targetBytes] より長い場合は、[targetBytes] 長分を比較したあとで
     * 超過分があれば正値（= prefix としては一致しない）を返す。
     */
    private fun compareReadingWithBytes(entryIndex: Int, targetBytes: ByteArray): Int {
        val readStart = readingByteStart[entryIndex]
        val readLen = readingByteLen[entryIndex]

        val compareLength = minOf(readLen, targetBytes.size)

        for (byteIndex in 0 until compareLength) {
            val entryByte = dictBytes[readStart + byteIndex].toInt() and BYTE_MASK_VALUE
            val targetByte = targetBytes[byteIndex].toInt() and BYTE_MASK_VALUE
            val diff = entryByte - targetByte

            if (diff != 0) return diff
        }

        return readLen - targetBytes.size
    }

    /**
     * [MozcHomophoneDictionary] の streaming 経路向けに同音語逆引き index を構築する。
     *
     * [sortedOrder] は reading 昇順でソート済みのため、連続する同 readingKey を 1 パスで
     * グループ化できる（中間 Map<String, List<LexemeEntry>> も LexemeEntry の全 List も作らない）。
     * 各グループの表層形を wcost 昇順・distinct・MAX_CANDIDATES_PER_READING 上限で収集する。
     *
     * 返す index のキーはひらがな（Mozc 辞書は reading が hiragana のためそのまま使用）。
     * 表層 = 読みの素通しエントリは除外する。
     */
    internal fun buildStreamingHomophoneIndex(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        var currentReadingKey = ""
        var currentSurfaces = mutableListOf<String>()

        for (sortedIndex in sortedOrder.indices) {
            val entryIndex = sortedOrder[sortedIndex]
            val lexeme = MozcCompactDictionaryReader.decodeEntryAt(dictBytes, entryOffsets[entryIndex])
            val readingKey = IpadicReadingLexicon.katakanaToHiragana(lexeme.reading)

            if (readingKey != currentReadingKey) {
                if (currentReadingKey.isNotEmpty() && currentSurfaces.isNotEmpty()) {
                    result[currentReadingKey] = currentSurfaces
                }

                currentReadingKey = readingKey
                currentSurfaces = mutableListOf()
            }

            val isPassthrough = lexeme.surface == lexeme.reading
            val isWithinLimit = currentSurfaces.size < MozcHomophoneDictionary.MAX_CANDIDATES_PER_READING
            val isDuplicate = lexeme.surface in currentSurfaces

            if (!isPassthrough && isWithinLimit && !isDuplicate) {
                currentSurfaces.add(lexeme.surface)
            }
        }

        // 最後のグループをフラッシュする。
        if (currentReadingKey.isNotEmpty() && currentSurfaces.isNotEmpty()) {
            result[currentReadingKey] = currentSurfaces
        }

        return result
    }

    private companion object {

        /** MZD1 マジック文字列。 */
        private const val DICT_MAGIC_EXPECTED = "MZD1"

        /** 確保前境界チェック用の 1 エントリ最小バイト数。 */
        private const val MIN_ENTRY_BYTES = 10

        /** バイト→符号なし変換マスク。 */
        private const val BYTE_MASK_VALUE = 0xFF

        /**
         * [dictBytes] 上の 2 エントリ（[indexA]・[indexB]）の reading バイト列を辞書順比較する。
         *
         * ソート時の Comparator として使う純関数。
         */
        private fun compareReadingBytes(
            dictBytes: ByteArray,
            rByteStart: IntArray,
            rByteLen: IntArray,
            indexA: Int,
            indexB: Int,
        ): Int {
            val startA = rByteStart[indexA]
            val lenA = rByteLen[indexA]
            val startB = rByteStart[indexB]
            val lenB = rByteLen[indexB]

            val compareLength = minOf(lenA, lenB)

            for (byteIndex in 0 until compareLength) {
                val byteA = dictBytes[startA + byteIndex].toInt() and BYTE_MASK_VALUE
                val byteB = dictBytes[startB + byteIndex].toInt() and BYTE_MASK_VALUE
                val diff = byteA - byteB

                if (diff != 0) return diff
            }

            return lenA - lenB
        }
    }
}
