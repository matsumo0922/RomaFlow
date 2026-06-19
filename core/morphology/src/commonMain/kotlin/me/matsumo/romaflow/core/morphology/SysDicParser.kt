package me.matsumo.romaflow.core.morphology

/**
 * IPADIC の sys.dic（MeCab バイナリ）を自前パースし、全エントリを列挙する純関数群。
 *
 * 生バイト列を受け取って動作するため、生バイトの取得方法（base64 decode 等）とは独立に
 * テストできる。Darts(double-array trie)を反復 DFS で全走査して表層形を復元し、終端値の
 * bit-packing から token を引き、feature CSV から読み（カタカナ）と単語コストを取り出す。
 *
 * Darts の遷移規則は momiji `Darts.commonPrefixSearch` と同一で、check には「親ノードの base
 * 値」が格納される（配列インデックスではない）。そのため DFS は配列インデックスではなく
 * 現在の base 値を状態として持ち回る。
 */
internal object SysDicParser {

    /** sys.dic の magic / size 冗長検証に用いる XOR キー。 */
    private const val MAGIC_XOR_KEY = 0xef718f77u

    /** magic フィールドのオフセット。 */
    private const val MAGIC_OFFSET = 0

    /** dsize（Darts セクションサイズ）フィールドのオフセット。 */
    private const val DSIZE_OFFSET = 24

    /** tsize（token セクションサイズ）フィールドのオフセット。 */
    private const val TSIZE_OFFSET = 28

    /** ヘッダ全体のバイト数。 */
    private const val HEADER_SIZE = 40

    /** charset 文字列領域のバイト数。 */
    private const val CHARSET_SIZE = 32

    /** Darts 1 unit のバイト数（base 4B + check 4B）。 */
    private const val DARTS_UNIT_SIZE = 8

    /** Darts unit 内の check フィールドのオフセット。 */
    private const val DARTS_CHECK_OFFSET = 4

    /** token 1 件のバイト数。 */
    private const val TOKEN_SIZE = 16

    /** token 内の wcost（符号付き 16bit）のオフセット。 */
    private const val TOKEN_WCOST_OFFSET = 6

    /** token 内の feature offset（32bit）のオフセット。 */
    private const val TOKEN_FEATURE_OFFSET = 8

    /** token 内の lcAttr（符号なし 16bit）のオフセット。 */
    private const val TOKEN_LCATTR_OFFSET = 0

    /** token 内の rcAttr（符号なし 16bit）のオフセット。 */
    private const val TOKEN_RCATTR_OFFSET = 2

    /** token 内の posId（符号なし 16bit）のオフセット。 */
    private const val TOKEN_POSID_OFFSET = 4

    /** 終端値から token 件数を取り出す下位ビットマスク。 */
    private const val TOKEN_COUNT_MASK = 0xFF

    /** 終端値から token 開始インデックスを取り出すシフト量。 */
    private const val TOKEN_START_SHIFT = 8

    /** 子遷移探索の最大 byte 値。 */
    private const val MAX_BYTE_VALUE = 255

    /** feature CSV の区切り文字。 */
    private const val FEATURE_DELIMITER = ","

    /** feature 中で値なしを表すマーク。 */
    private const val FEATURE_EMPTY_MARK = "*"

    /** feature CSV における読み（カタカナ）の位置。 */
    private const val FEATURE_INDEX_READING = 7

    /**
     * sys.dic 全体のヘッダから切り出した各セクションの境界。
     *
     * MeCab の sys.dic は header(40B) + charset(32B) の後に Darts・token・feature の順で
     * 各セクションが連続する。各 offset は対象バイト列の絶対位置を表す。
     */
    private data class SectionLayout(
        /** Darts セクションの開始オフセット。 */
        val dartsOffset: Int,
        /** Darts unit の総数。 */
        val unitCount: Int,
        /** token セクションの開始オフセット。 */
        val tokenOffset: Int,
        /** feature blob の開始オフセット。 */
        val featureOffset: Int,
    )

    /**
     * 反復 DFS で 1 ノードを処理するためのスタックフレーム。
     *
     * 巨大辞書での stack overflow を避けるため再帰ではなく明示スタックで走査する。
     * [base] は MeCab Darts の遷移状態（現在ノードの base 値）で、[surfaceBytes] は root から
     * 当該ノードまでの経路バイト列（= 表層形の UTF-8 バイト）。
     */
    private data class DfsFrame(
        /** 現在ノードの base 値（次遷移の起点）。 */
        val base: Int,
        /** root からの経路バイト列。 */
        val surfaceBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }

            if (other !is DfsFrame) {
                return false
            }

            return base == other.base && surfaceBytes.contentEquals(other.surfaceBytes)
        }

        override fun hashCode(): Int {
            return 31 * base + surfaceBytes.contentHashCode()
        }
    }

    /**
     * sys.dic の生バイト [bytes] を解析し、列挙可能な全エントリを返す。
     *
     * 読み（reading）が空または "*" のエントリは除外する。同一表層形に複数 token が
     * 紐づく場合は token の数だけ別エントリとして列挙する（重複除去は行わない）。
     */
    fun parseEntries(bytes: ByteArray): List<IpadicEntry> {
        val reader = SysDicByteReader(bytes)
        val layout = readSectionLayout(reader, bytes.size)

        return enumerateEntries(reader, layout)
    }

    private fun readSectionLayout(reader: SysDicByteReader, totalSize: Int): SectionLayout {
        val magic = reader.readUIntAt(MAGIC_OFFSET)
        val expectedSize = (MAGIC_XOR_KEY xor magic).toInt()

        require(expectedSize == totalSize) {
            "sys.dic の magic 検証に失敗しました（expected=$expectedSize, actual=$totalSize）"
        }

        val dartsSize = reader.readUIntAt(DSIZE_OFFSET).toInt()
        val tokenSize = reader.readUIntAt(TSIZE_OFFSET).toInt()

        val dartsOffset = HEADER_SIZE + CHARSET_SIZE
        val tokenOffset = dartsOffset + dartsSize
        val featureOffset = tokenOffset + tokenSize

        return SectionLayout(
            dartsOffset = dartsOffset,
            unitCount = dartsSize / DARTS_UNIT_SIZE,
            tokenOffset = tokenOffset,
            featureOffset = featureOffset,
        )
    }

    private fun enumerateEntries(reader: SysDicByteReader, layout: SectionLayout): List<IpadicEntry> {
        val rootBase = readDartsBase(reader, layout.dartsOffset, 0)
        val entries = mutableListOf<IpadicEntry>()
        val stack = ArrayDeque<DfsFrame>()

        stack.addLast(DfsFrame(base = rootBase, surfaceBytes = byteArrayOf()))

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()

            visitNode(reader, layout, frame, entries, stack)
        }

        return entries
    }

    private fun visitNode(
        reader: SysDicByteReader,
        layout: SectionLayout,
        frame: DfsFrame,
        entries: MutableList<IpadicEntry>,
        stack: ArrayDeque<DfsFrame>,
    ) {
        collectTerminalEntries(reader, layout, frame, entries)
        pushChildren(reader, layout, frame, stack)
    }

    private fun collectTerminalEntries(
        reader: SysDicByteReader,
        layout: SectionLayout,
        frame: DfsFrame,
        entries: MutableList<IpadicEntry>,
    ) {
        val terminalIndex = frame.base
        val isWithinBounds = terminalIndex in 0 until layout.unitCount

        if (!isWithinBounds) {
            return
        }

        val terminalCheck = readDartsCheck(reader, layout.dartsOffset, terminalIndex)
        val terminalBase = readDartsBase(reader, layout.dartsOffset, terminalIndex)
        val isTerminalNode = terminalCheck == frame.base.toUInt() && terminalBase < 0

        if (!isTerminalNode) {
            return
        }

        val value = -terminalBase - 1
        val tokenCount = value and TOKEN_COUNT_MASK
        val tokenStartIndex = value shr TOKEN_START_SHIFT
        val surface = frame.surfaceBytes.decodeToString()

        for (offsetIndex in 0 until tokenCount) {
            val entry = readTokenEntry(reader, layout, surface, tokenStartIndex + offsetIndex)

            if (entry != null) {
                entries.add(entry)
            }
        }
    }

    private fun readTokenEntry(
        reader: SysDicByteReader,
        layout: SectionLayout,
        surface: String,
        tokenIndex: Int,
    ): IpadicEntry? {
        val tokenBase = layout.tokenOffset + tokenIndex * TOKEN_SIZE
        val wcost = reader.readShortAt(tokenBase + TOKEN_WCOST_OFFSET)
        val featurePointer = reader.readUIntAt(tokenBase + TOKEN_FEATURE_OFFSET).toInt()

        val feature = reader.readNullTerminatedString(layout.featureOffset + featurePointer)
        val reading = feature.split(FEATURE_DELIMITER).getOrNull(FEATURE_INDEX_READING)

        val isUsableReading = reading != null && reading.isNotEmpty() && reading != FEATURE_EMPTY_MARK

        if (!isUsableReading) {
            return null
        }

        return IpadicEntry(
            surface = surface,
            reading = reading,
            wcost = wcost,
        )
    }

    private fun pushChildren(
        reader: SysDicByteReader,
        layout: SectionLayout,
        frame: DfsFrame,
        stack: ArrayDeque<DfsFrame>,
    ) {
        for (character in 0..MAX_BYTE_VALUE) {
            val nextIndex = frame.base + character + 1
            val isWithinBounds = nextIndex in 0 until layout.unitCount

            if (!isWithinBounds) {
                continue
            }

            val nextCheck = readDartsCheck(reader, layout.dartsOffset, nextIndex)
            val isValidTransition = nextCheck == frame.base.toUInt()

            if (isValidTransition) {
                pushChild(reader, layout, frame, character, nextIndex, stack)
            }
        }
    }

    private fun pushChild(
        reader: SysDicByteReader,
        layout: SectionLayout,
        frame: DfsFrame,
        character: Int,
        nextIndex: Int,
        stack: ArrayDeque<DfsFrame>,
    ) {
        val childBase = readDartsBase(reader, layout.dartsOffset, nextIndex)
        val childSurface = frame.surfaceBytes + character.toByte()

        stack.addLast(DfsFrame(base = childBase, surfaceBytes = childSurface))
    }

    private fun readDartsBase(reader: SysDicByteReader, dartsOffset: Int, unitIndex: Int): Int {
        val unitOffset = dartsOffset + unitIndex * DARTS_UNIT_SIZE

        return reader.readUIntAt(unitOffset).toInt()
    }

    private fun readDartsCheck(reader: SysDicByteReader, dartsOffset: Int, unitIndex: Int): UInt {
        val unitOffset = dartsOffset + unitIndex * DARTS_UNIT_SIZE

        return reader.readUIntAt(unitOffset + DARTS_CHECK_OFFSET)
    }

    /**
     * sys.dic の生バイト [bytes] を解析し、lcAttr / rcAttr / posId を含む全リッチエントリを返す。
     *
     * 読み（reading）が空または "*" のエントリは除外する。[parseEntries] の [LexemeEntry] 版。
     */
    fun parseRichEntries(bytes: ByteArray): List<LexemeEntry> {
        val reader = SysDicByteReader(bytes)
        val layout = readSectionLayout(reader, bytes.size)

        return enumerateRichEntries(reader, layout)
    }

    private fun enumerateRichEntries(reader: SysDicByteReader, layout: SectionLayout): List<LexemeEntry> {
        val rootBase = readDartsBase(reader, layout.dartsOffset, 0)
        val entries = mutableListOf<LexemeEntry>()
        val stack = ArrayDeque<DfsFrame>()

        stack.addLast(DfsFrame(base = rootBase, surfaceBytes = byteArrayOf()))

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()

            visitRichNode(reader, layout, frame, entries, stack)
        }

        return entries
    }

    private fun visitRichNode(
        reader: SysDicByteReader,
        layout: SectionLayout,
        frame: DfsFrame,
        entries: MutableList<LexemeEntry>,
        stack: ArrayDeque<DfsFrame>,
    ) {
        collectRichTerminalEntries(reader, layout, frame, entries)
        pushChildren(reader, layout, frame, stack)
    }

    private fun collectRichTerminalEntries(
        reader: SysDicByteReader,
        layout: SectionLayout,
        frame: DfsFrame,
        entries: MutableList<LexemeEntry>,
    ) {
        val terminalIndex = frame.base
        val isWithinBounds = terminalIndex in 0 until layout.unitCount

        if (!isWithinBounds) {
            return
        }

        val terminalCheck = readDartsCheck(reader, layout.dartsOffset, terminalIndex)
        val terminalBase = readDartsBase(reader, layout.dartsOffset, terminalIndex)
        val isTerminalNode = terminalCheck == frame.base.toUInt() && terminalBase < 0

        if (!isTerminalNode) {
            return
        }

        val value = -terminalBase - 1
        val tokenCount = value and TOKEN_COUNT_MASK
        val tokenStartIndex = value shr TOKEN_START_SHIFT
        val surface = frame.surfaceBytes.decodeToString()

        for (offsetIndex in 0 until tokenCount) {
            val entry = readRichTokenEntry(reader, layout, surface, tokenStartIndex + offsetIndex)

            if (entry != null) {
                entries.add(entry)
            }
        }
    }

    private fun readRichTokenEntry(
        reader: SysDicByteReader,
        layout: SectionLayout,
        surface: String,
        tokenIndex: Int,
    ): LexemeEntry? {
        val tokenBase = layout.tokenOffset + tokenIndex * TOKEN_SIZE
        val lcAttr = reader.readUShortAt(tokenBase + TOKEN_LCATTR_OFFSET)
        val rcAttr = reader.readUShortAt(tokenBase + TOKEN_RCATTR_OFFSET)
        val posId = reader.readUShortAt(tokenBase + TOKEN_POSID_OFFSET)
        val wcost = reader.readShortAt(tokenBase + TOKEN_WCOST_OFFSET)
        val featurePointer = reader.readUIntAt(tokenBase + TOKEN_FEATURE_OFFSET).toInt()

        val feature = reader.readNullTerminatedString(layout.featureOffset + featurePointer)
        val reading = feature.split(FEATURE_DELIMITER).getOrNull(FEATURE_INDEX_READING)

        val isUsableReading = reading != null && reading.isNotEmpty() && reading != FEATURE_EMPTY_MARK

        if (!isUsableReading) {
            return null
        }

        return LexemeEntry(
            surface = surface,
            reading = reading!!,
            lcAttr = lcAttr,
            rcAttr = rcAttr,
            posId = posId,
            wcost = wcost,
        )
    }
}
