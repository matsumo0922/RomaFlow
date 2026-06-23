package me.matsumo.romaflow.core.morphology

/**
 * Mozc compact binary（MZD1 形式）の生バイト列を常駐させ、エントリを on-demand decode する [ReadingLexicon]。
 *
 * [EntryListReadingLexicon] が全エントリを List<LexemeEntry> として materialized するのに対し、
 * 本クラスは [dictBytes] をそのまま保持し（52MB 常駐）、エントリ List は一切常駐させない。
 *
 * 構築時に 1 パスで以下のインデックスを作成する:
 * - [entryOffsets]: 各エントリの先頭バイト offset（[dictBytes] 内の絶対位置）。
 * - [normalizedReadingPool]: 各エントリの [IpadicReadingLexicon.katakanaToHiragana] 正規化済み
 *   reading を連結した ByteArray。[EntryListReadingLexicon.buildReverseIndex] と同じ正規化を通す
 *   ことで、カタカナ混じり reading（例 `ヶ`→`ゖ`）でも parity が保たれる。
 * - [normalizedReadingStart] / [normalizedReadingLen]: pool 上の各エントリの reading バイト範囲。
 * - [sortedOrder]: エントリ index を **正規化 reading バイト列辞書順 → wcost 昇順** で安定ソートした配列。
 *
 * `commonPrefixSearch` は endOffset ごとに [sortedOrder] 上で二分探索し、ヒット範囲のエントリを
 * その場で decode して [LexemeMatch] を返す（LexemeEntry は呼び出し毎の短命オブジェクト）。
 *
 * ## parity 保証
 * - 正規化 reading は [IpadicReadingLexicon.katakanaToHiragana] を通すため、
 *   [EntryListReadingLexicon.buildReverseIndex] の key 生成と完全一致する。
 * - `sortedOrder` のソート順（正規化 reading 昇順 → wcost 昇順・stable）は
 *   [EntryListReadingLexicon] の `buildReverseIndex`（`sortedBy { wcost }`・stable）と等価。
 * - `commonPrefixSearch` の query は EntryList と同様に **正規化しない**（生 prefix のまま）。
 *   query の hiragana と辞書の正規化済み hiragana は同一バイト列になるため問題ない。
 *
 * ## homophone 逆引き
 * [buildStreamingHomophoneIndex] で [sortedOrder] の連続同 readingKey をストリーミンググループ化し、
 * 中間 Map<String, List<LexemeEntry>> を作らずに同音語 index を構築できる。
 * [sortedOrder] が正規化キー順であるためカタカナ reading でもグループが連続する。
 *
 * ## 壊れた binary の検証
 * ヘッダの count・各フィールド長境界チェックに加え、構築 scan 完了後に
 * [MozcByteCursor.requireExhausted] で末尾余剰バイトを検出する（[MozcCompactDictionaryReader] と同水準）。
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
     * 各エントリの [IpadicReadingLexicon.katakanaToHiragana] 正規化済み reading を連結した ByteArray。
     *
     * [EntryListReadingLexicon] と同一の正規化を通すことで、カタカナ混じり reading（例 `ヶ`→`ゖ`）でも
     * parity が保たれる。読み合計バイト数は ~10MB 程度。
     */
    private val normalizedReadingPool: ByteArray

    /** [normalizedReadingPool] 上の各エントリの読み開始バイト offset。 */
    private val normalizedReadingStart: IntArray

    /** [normalizedReadingPool] 上の各エントリの読みバイト長。 */
    private val normalizedReadingLen: IntArray

    /**
     * エントリ index を **正規化 reading バイト列辞書順 → wcost 昇順** で安定ソートした配列。
     *
     * 二分探索で prefix の equal-range を特定し、on-demand decode のカーソル位置を得るのに使う。
     * 正規化 reading を使うため、カタカナ混じり reading も hiragana として正しく並ぶ。
     */
    private val sortedOrder: IntArray

    init {
        val cursor = MozcByteCursor(dictBytes)

        cursor.expectMagic(DICT_MAGIC_EXPECTED)

        val entryCount = cursor.readInt32()

        require(entryCount >= 0) { "エントリ数が不正です: $entryCount" }
        require(entryCount.toLong() * MIN_ENTRY_BYTES <= cursor.remaining()) {
            "エントリ数が残バイトに対し過大です: count=$entryCount remaining=${cursor.remaining()}"
        }

        val offsets = IntArray(entryCount)
        val wcostArray = IntArray(entryCount)

        // 1 パスで reading を正規化して primitive な pool に蓄積する（Byte boxing を避けるため ByteAccumulator を使う）。
        val normalizedReadingBytes = ByteAccumulator(entryCount * ESTIMATED_READING_BYTES_PER_ENTRY)
        val nReadingStart = IntArray(entryCount)
        val nReadingLen = IntArray(entryCount)

        for (index in 0 until entryCount) {
            offsets[index] = cursor.currentOffset()

            val (rawReadingStart, rawReadingLen) = cursor.readStringRaw()

            nReadingStart[index] = normalizedReadingBytes.size
            nReadingLen[index] = appendNormalizedReading(normalizedReadingBytes, dictBytes, rawReadingStart, rawReadingLen)

            cursor.readStringRaw() // surface は索引に不要なので読み飛ばす
            cursor.readUInt16() // lid
            cursor.readUInt16() // rid
            wcostArray[index] = cursor.readInt16()
        }

        // 壊れた binary（count 過小や末尾余剰バイト）を確定的に弾く。
        cursor.requireExhausted()

        entryOffsets = offsets
        normalizedReadingPool = normalizedReadingBytes.toByteArray()
        normalizedReadingStart = nReadingStart
        normalizedReadingLen = nReadingLen

        // 正規化 reading バイト列辞書順 → wcost 昇順 で安定ソート（Kotlin の sortedWith は stable）。
        sortedOrder = (0 until entryCount).sortedWith(
            Comparator { indexA, indexB -> compareForSort(indexA, indexB, wcostArray) },
        ).toIntArray()
    }

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val results = mutableListOf<LexemeMatch>()

        for (endOffset in (startOffset + 1)..reading.length) {
            // query は EntryListReadingLexicon と同様に正規化しない（生 prefix のまま）。
            // 辞書側が hiragana 正規化済みであり、query は検索時点で hiragana であるため問題ない。
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
     * [sortedOrder] 上で [prefixBytes] と正規化 reading バイト列が完全一致するエントリの
     * equal-range（[start, end)）を二分探索で返す。
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
            val cmp = compareNormalizedReadingWithBytes(entryIndex, prefixBytes)

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
            val cmp = compareNormalizedReadingWithBytes(entryIndex, prefixBytes)

            if (cmp <= 0) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        return low
    }

    /**
     * [entryIndex] の正規化 reading バイト列と [targetBytes] を辞書順比較する。
     *
     * 完全一致なら 0、辞書順で前なら負値、後なら正値を返す。
     * reading 長が [targetBytes] より長い場合（prefix でない）は正値を返す。
     */
    private fun compareNormalizedReadingWithBytes(entryIndex: Int, targetBytes: ByteArray): Int {
        val readStart = normalizedReadingStart[entryIndex]
        val readLen = normalizedReadingLen[entryIndex]

        val compareLength = minOf(readLen, targetBytes.size)

        for (byteIndex in 0 until compareLength) {
            val entryByte = normalizedReadingPool[readStart + byteIndex].toInt() and BYTE_MASK_VALUE
            val targetByte = targetBytes[byteIndex].toInt() and BYTE_MASK_VALUE
            val diff = entryByte - targetByte

            if (diff != 0) return diff
        }

        return readLen - targetBytes.size
    }

    /**
     * [MozcHomophoneDictionary] の streaming 経路向けに同音語逆引き index を構築する。
     *
     * [sortedOrder] は正規化 reading 昇順でソート済みのため、連続する同 readingKey を 1 パスで
     * グループ化できる（中間 Map<String, List<LexemeEntry>> も LexemeEntry の全 List も作らない）。
     * カタカナ reading も [IpadicReadingLexicon.katakanaToHiragana] 正規化済みのため非連続にならない。
     * 各グループの表層形を wcost 昇順・distinct・MAX_CANDIDATES_PER_READING 上限で収集する。
     *
     * 返す index のキーはひらがな正規化済み。表層 = 読みの素通しエントリは除外する。
     */
    internal fun buildStreamingHomophoneIndex(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        var currentReadingKey = ""
        var currentSurfaces = mutableListOf<String>()

        for (sortedIndex in sortedOrder.indices) {
            val entryIndex = sortedOrder[sortedIndex]
            val readingKey = normalizedReadingPool
                .decodeToString(normalizedReadingStart[entryIndex], normalizedReadingStart[entryIndex] + normalizedReadingLen[entryIndex])

            if (readingKey != currentReadingKey) {
                if (currentReadingKey.isNotEmpty() && currentSurfaces.isNotEmpty()) {
                    result[currentReadingKey] = currentSurfaces
                }

                currentReadingKey = readingKey
                currentSurfaces = mutableListOf()
            }

            val lexeme = MozcCompactDictionaryReader.decodeEntryAt(dictBytes, entryOffsets[entryIndex])
            // 素通し判定は classic buildReverseIndex（surface != raw reading）と一致させる。
            // 正規化キーと比較するとカタカナ reading（例 surface=ヶ / reading=ヶ）で parity がズレる。
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

    /**
     * ソート比較。1 次キー = 正規化 reading バイト列辞書順、2 次キー = [wcosts] 昇順。
     *
     * `sortedWith` が安定ソートのため、同 reading・同 wcost のエントリは入力（file）順を保つ。
     */
    private fun compareForSort(indexA: Int, indexB: Int, wcosts: IntArray): Int {
        val readingComparison = compareNormalizedReadingBytes(indexA, indexB)

        if (readingComparison != 0) {
            return readingComparison
        }

        return wcosts[indexA] - wcosts[indexB]
    }

    /**
     * ソート時の Comparator として使う純関数。
     * [normalizedReadingPool] 上の 2 エントリ（[indexA]・[indexB]）の正規化 reading を辞書順比較する。
     */
    private fun compareNormalizedReadingBytes(indexA: Int, indexB: Int): Int {
        val startA = normalizedReadingStart[indexA]
        val lenA = normalizedReadingLen[indexA]
        val startB = normalizedReadingStart[indexB]
        val lenB = normalizedReadingLen[indexB]

        val compareLength = minOf(lenA, lenB)

        for (byteIndex in 0 until compareLength) {
            val byteA = normalizedReadingPool[startA + byteIndex].toInt() and BYTE_MASK_VALUE
            val byteB = normalizedReadingPool[startB + byteIndex].toInt() and BYTE_MASK_VALUE
            val diff = byteA - byteB

            if (diff != 0) return diff
        }

        return lenA - lenB
    }

    private companion object {

        /** MZD1 マジック文字列。 */
        private const val DICT_MAGIC_EXPECTED = "MZD1"

        /** 確保前境界チェック用の 1 エントリ最小バイト数。 */
        private const val MIN_ENTRY_BYTES = 10

        /** バイト→符号なし変換マスク。 */
        private const val BYTE_MASK_VALUE = 0xFF

        /** 正規化 reading pool の初期容量見積もり（1 エントリあたりの平均 reading バイト数 × 3 bytes/char）。 */
        private const val ESTIMATED_READING_BYTES_PER_ENTRY = 9

        /** ひらがな/カタカナ（U+3000 ブロック）を表す 3 バイト UTF-8 のリードバイト。 */
        private const val CJK_KANA_LEAD_BYTE = 0xE3

        /** 正規化対象カタカナ前半（U+30A1..U+30BF）の 2 バイト目。 */
        private const val KATAKANA_LOWER_SECOND_BYTE = 0x82

        /** 正規化対象カタカナ後半（U+30C0..U+30F6）の 2 バイト目。 */
        private const val KATAKANA_UPPER_SECOND_BYTE = 0x83

        /** U+30A1（カタカナ最小・正規化対象）の 3 バイト目（前半側）。 */
        private const val KATAKANA_LOWER_THIRD_MIN = 0xA1

        /** U+30BF（前半側カタカナ最大）の 3 バイト目。 */
        private const val KATAKANA_LOWER_THIRD_MAX = 0xBF

        /** U+30C0（後半側カタカナ最小）の 3 バイト目。 */
        private const val KATAKANA_UPPER_THIRD_MIN = 0x80

        /** U+30F6（カタカナ最大・正規化対象）の 3 バイト目（後半側）。 */
        private const val KATAKANA_UPPER_THIRD_MAX = 0xB6

        /**
         * [dictBytes] の `[readStart]..[readStart]+[readLen]` の raw reading を正規化して [pool] に追記し、
         * 追記したバイト数を返す。
         *
         * 大半の reading は純 hiragana で正規化不要なため、カタカナを含まなければ raw バイトをそのまま
         * コピーする（1.29M 件の transient String alloc を避け、構築の GC 圧を抑える）。カタカナを含む稀な
         * ケースのみ [IpadicReadingLexicon.katakanaToHiragana] で正規化する（共有ロジックを再利用）。
         */
        private fun appendNormalizedReading(
            pool: ByteAccumulator,
            dictBytes: ByteArray,
            readStart: Int,
            readLen: Int,
        ): Int {
            if (!containsNormalizableKatakana(dictBytes, readStart, readLen)) {
                pool.append(dictBytes, readStart, readLen)

                return readLen
            }

            val rawReading = dictBytes.decodeToString(readStart, readStart + readLen)
            val normalizedBytes = IpadicReadingLexicon.katakanaToHiragana(rawReading).encodeToByteArray()

            pool.append(normalizedBytes)

            return normalizedBytes.size
        }

        /**
         * [dictBytes] の `[start]..[start]+[len]` の UTF-8 バイト列に、正規化対象カタカナ
         * （U+30A1..U+30F6・3 バイト UTF-8）が含まれるかを判定する。
         *
         * UTF-8 のリードバイト 0xE3 は継続バイトには現れないため、各バイト位置を直接走査して安全に検出できる。
         */
        private fun containsNormalizableKatakana(dictBytes: ByteArray, start: Int, len: Int): Boolean {
            val end = start + len
            var index = start

            while (index < end) {
                if (isNormalizableKatakanaAt(dictBytes, index, end)) {
                    return true
                }

                index++
            }

            return false
        }

        /** [dictBytes] の [index] から始まる 3 バイトが正規化対象カタカナかを判定する。 */
        private fun isNormalizableKatakanaAt(dictBytes: ByteArray, index: Int, end: Int): Boolean {
            if (index + 2 >= end) {
                return false
            }

            val leadByte = dictBytes[index].toInt() and BYTE_MASK_VALUE

            if (leadByte != CJK_KANA_LEAD_BYTE) {
                return false
            }

            val secondByte = dictBytes[index + 1].toInt() and BYTE_MASK_VALUE
            val thirdByte = dictBytes[index + 2].toInt() and BYTE_MASK_VALUE
            val isLowerKatakana = secondByte == KATAKANA_LOWER_SECOND_BYTE && thirdByte in KATAKANA_LOWER_THIRD_MIN..KATAKANA_LOWER_THIRD_MAX
            val isUpperKatakana = secondByte == KATAKANA_UPPER_SECOND_BYTE && thirdByte in KATAKANA_UPPER_THIRD_MIN..KATAKANA_UPPER_THIRD_MAX

            return isLowerKatakana || isUpperKatakana
        }
    }

    /**
     * バイト列を末尾追記で蓄積し、容量不足時に倍々で拡張する可変バッファ。
     *
     * 正規化 reading プールを 1 パスで組み立てるために使う。`ArrayList<Byte>` の boxing を避けて
     * primitive な [ByteArray] のまま扱い、最終的に [toByteArray] で確定長へ縮める。
     */
    private class ByteAccumulator(initialCapacity: Int) {

        private var buffer = ByteArray(initialCapacity.coerceAtLeast(MIN_CAPACITY))

        private var length = 0

        val size: Int get() = length

        fun append(bytes: ByteArray) {
            append(bytes, 0, bytes.size)
        }

        fun append(source: ByteArray, sourceOffset: Int, sourceLength: Int) {
            ensureCapacity(length + sourceLength)
            source.copyInto(buffer, length, sourceOffset, sourceOffset + sourceLength)
            length += sourceLength
        }

        fun toByteArray(): ByteArray = buffer.copyOf(length)

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) {
                return
            }

            var newCapacity = buffer.size

            while (newCapacity < required) {
                newCapacity *= GROWTH_FACTOR
            }

            buffer = buffer.copyOf(newCapacity)
        }

        private companion object {

            /** 初期容量の下限。 */
            private const val MIN_CAPACITY = 16

            /** 容量拡張倍率。 */
            private const val GROWTH_FACTOR = 2
        }
    }
}
