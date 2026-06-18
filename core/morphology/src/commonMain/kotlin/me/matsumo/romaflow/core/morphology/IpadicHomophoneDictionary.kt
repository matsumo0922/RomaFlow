package me.matsumo.romaflow.core.morphology

/**
 * IPADIC を素材にした [HomophoneDictionary] の本番実装。
 *
 * [IpadicEntrySource.readEntries] が返す全エントリ（reading はカタカナ）を、
 * ひらがなキー → 表層候補リストの逆引き index に1回だけ変換してキャッシュする。
 * index 構築は重い（数十万件）ため [reverseIndex] を `by lazy` とし、初回の
 * [homophoneCandidates] 呼び出し時にのみ構築する。
 */
class IpadicHomophoneDictionary(private val source: IpadicEntrySource = IpadicDictReader()) : HomophoneDictionary {

    private val reverseIndex: Map<String, List<String>> by lazy { buildReverseIndex(source.readEntries()) }

    override fun homophoneCandidates(reading: String): List<String> {
        val normalizedKey = normalizeReadingKey(reading)

        return reverseIndex[normalizedKey].orEmpty()
    }

    companion object {

        /**
         * 1 つの読みに対して保持する表層候補数の上限。
         *
         * 候補窓を無闇に膨らませないための cap。低頻度（高 wcost）まで含めると候補が
         * 数十〜数百件に達する読みがあるため、出現しやすい上位のみを残す。
         */
        const val MAX_CANDIDATES_PER_READING = 30

        /** ひらがなブロックの開始コードポイント（U+3041 = ぁ）。 */
        private const val HIRAGANA_BLOCK_START = 0x3041

        /** カタカナ正規化の対象開始コードポイント（U+30A1 = ァ）。 */
        private const val KATAKANA_BLOCK_START = 0x30A1

        /** カタカナ正規化の対象終了コードポイント（U+30F6 = ヶ）。 */
        private const val KATAKANA_BLOCK_END = 0x30F6

        /** カタカナ→ひらがなのコードポイント差分（0x60）。 */
        private const val KATAKANA_TO_HIRAGANA_OFFSET = KATAKANA_BLOCK_START - HIRAGANA_BLOCK_START

        /**
         * [entries] から、ひらがなキー → 表層候補リストの逆引き index を構築する純関数。
         *
         * 各読みの候補は単語コスト昇順（同コストは安定）に並べ、同一表層形は最初の1件のみ残し、
         * [MAX_CANDIDATES_PER_READING] 件で打ち切る。変換価値のない候補
         * （表層形が元のカタカナ読みと同一の記号・カナ素通しエントリ）は除外する。
         */
        internal fun buildReverseIndex(entries: List<IpadicEntry>): Map<String, List<String>> {
            val grouped = groupConvertibleEntriesByReadingKey(entries)

            return grouped.mapValues { (_, readingEntries) -> selectSurfaces(readingEntries) }
        }

        /**
         * [reading] をひらがなに正規化したキーを返す。
         *
         * カタカナ（U+30A1..U+30F6）は -0x60 でひらがな化し、長音符「ー」や範囲外の文字は
         * そのまま残す。engine はひらがなを渡すが、堅牢性のため引数 reading も同じ正規化を通す。
         */
        internal fun normalizeReadingKey(reading: String): String {
            val builder = StringBuilder(reading.length)

            for (character in reading) {
                builder.append(toHiraganaCharacter(character))
            }

            return builder.toString()
        }

        private fun toHiraganaCharacter(character: Char): Char {
            val code = character.code
            val isKatakana = code in KATAKANA_BLOCK_START..KATAKANA_BLOCK_END

            return if (isKatakana) (code - KATAKANA_TO_HIRAGANA_OFFSET).toChar() else character
        }

        private fun groupConvertibleEntriesByReadingKey(entries: List<IpadicEntry>): Map<String, List<IpadicEntry>> {
            return entries.filter(::isConvertibleEntry)
                .groupBy { entry -> normalizeReadingKey(entry.reading) }
        }

        /**
         * 変換価値のあるエントリかを判定する。
         *
         * 表層形が元のカタカナ読みと完全一致するエントリは「変換結果が入力と同じ」になるため、
         * 同音異義候補としては不要として除外する。記号や、カナのまま登録された語が該当する。
         */
        private fun isConvertibleEntry(entry: IpadicEntry): Boolean {
            return entry.surface != entry.reading
        }

        private fun selectSurfaces(readingEntries: List<IpadicEntry>): List<String> {
            return readingEntries.sortedBy(IpadicEntry::wcost)
                .map(IpadicEntry::surface)
                .distinct()
                .take(MAX_CANDIDATES_PER_READING)
        }
    }
}
