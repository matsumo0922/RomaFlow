package me.matsumo.romaflow.core.ime

/**
 * romaji 入力層の唯一の source of truth。
 *
 * [revision] はこのオブジェクトが生成されたときの入力リビジョン、[frozenPrefix] は変換確定前に
 * finalize された既固定のかな文字列、[atoms] は現在の romaji セッションで入力された [InputAtom] 群、
 * [transliteration] は現セッションの romaji→kana 変換履歴（[TransliterationTrace]）。
 *
 * `readingInput`（確定済みかな）は `frozenPrefix + transliteration.committedReading` の projection。
 * `pendingRomaji`（未確定ローマ字）は `transliteration.pendingDisplay` の projection。
 *
 * 外部から直接 [InputBuffer] を操作するのではなく、[appendRomaji] / [deleteLastKana] / [finalizePending]
 * / [startFreshSession] の API を通じてのみ状態を変える。これにより二重 source of truth を防ぐ。
 *
 * ## 不変条件
 * - [frozenPrefix] + [transliteration].committedReading が [readingInput] と一致する。
 * - [transliteration].pendingDisplay が [pendingRomaji] と一致する。
 * - [revision] と [transliteration] は同時に更新される（別々に変わることはない）。
 * - pending エッジは最大1つで末尾にのみ存在する。
 *
 * ## finalize 後の新規入力
 * Tab / commit の前段として [finalizePending] を呼ぶと pending ローマ字が committed に昇格する。
 * その後さらに romaji が追記された場合は、次の [appendRomaji] 呼び出し前に [startFreshSession] を
 * 呼び、現在の [readingInput] を [frozenPrefix] として封止し、atoms を空にリセットする。
 * これにより `hon→ほん→convert→a` のとき `ほん` が `ほな` に再解釈されるのを防ぐ。
 */
internal class CompositionSource private constructor(
    val revision: Int,
    val frozenPrefix: String,
    val atoms: List<InputAtom>,
    val transliteration: TransliterationTrace,
) {

    /** 確定済みのかな全体（frozenPrefix + 現セッションの committed reading の projection）。 */
    val readingInput: String get() = frozenPrefix + transliteration.committedReading

    /** 末尾の未確定ローマ字表示文字列（[transliteration] からの projection）。 */
    val pendingRomaji: String get() = transliteration.pendingDisplay

    /** [InputBuffer] との互換 projection。既存コードとの接続に使う。 */
    val asInputBuffer: InputBuffer
        get() = InputBuffer(readingInput = readingInput, pendingRomaji = pendingRomaji)

    /**
     * ローマ字文字列を追記した新しい [CompositionSource] を返す。
     *
     * [converter] を使って現在の atoms + 新規 atoms 全体を再変換し、新しいトレースを構築する。
     * `n` / `tt` / `kyo` のような直前 pending の解釈が変わるケースに対応するため、prefix 再生で
     * 全エッジを再生成する（既存 committed + pending を込みで再計算）。
     * [frozenPrefix] は変更しない（変換確定済みかなには触れない）。
     */
    fun appendRomaji(
        converter: RomajiKanaConverter,
        input: String,
        nextRevision: Int,
        nextAtomId: Long,
    ): CompositionSource {
        val newAtoms = input.mapIndexed { index, char ->
            InputAtom(id = nextAtomId + index, text = char.toString())
        }
        val allAtoms = atoms + newAtoms
        val newTrace = converter.buildTrace(allAtoms)

        return CompositionSource(
            revision = nextRevision,
            frozenPrefix = frozenPrefix,
            atoms = allAtoms,
            transliteration = newTrace,
        )
    }

    /**
     * 末尾のかな1文字（または pending 末尾1文字）を削除した新しい [CompositionSource] を返す。
     *
     * pending エッジがある場合は pending の末尾1文字と対応する末尾 atom を削る。
     * pending が空の場合は committed 末尾エッジの最後のかなを削る（atoms は変更しない）。
     *
     * pending 削除時に atoms を削るのは、再生成アプローチで削除済み文字が再解釈されるのを防ぐため。
     * committed 削除（`きょ→き` 等）は [SourceUnit.LiteralReading] エッジとして表現するため atoms を変えない。
     */
    fun deleteLastKana(nextRevision: Int): CompositionSource {
        val newTrace = transliteration.deleteLastKana()

        return CompositionSource(
            revision = nextRevision,
            frozenPrefix = frozenPrefix,
            atoms = newTrace.atoms,
            transliteration = newTrace,
        )
    }

    /**
     * pending ローマ字を確定かなへ昇格させた新しい [CompositionSource] を返す。
     *
     * [converter] を使って pending ローマ字（`n` など）を確定かなへ変換する。
     */
    fun finalizePending(converter: RomajiKanaConverter): CompositionSource {
        val pending = this.pendingRomaji

        if (pending.isEmpty()) {
            return this
        }

        val finalizedReading = converter.finalize(pending)
        val newTrace = transliteration.finalizePending(finalizedReading)

        return CompositionSource(
            revision = revision,
            frozenPrefix = frozenPrefix,
            atoms = atoms,
            transliteration = newTrace,
        )
    }

    /**
     * 現在の [readingInput] を [frozenPrefix] として封止し、atoms と transliteration をリセットした
     * 新しいセッションの [CompositionSource] を返す。
     *
     * Tab 変換後など、確定済みかなを変えずに次の romaji 入力セッションを始めるときに使う。
     * これにより `hon→ほん（確定）→a` のとき `ほん` が `ほな` に再解釈されるのを防ぐ。
     */
    fun startFreshSession(nextRevision: Int): CompositionSource {
        return CompositionSource(
            revision = nextRevision,
            frozenPrefix = readingInput,
            atoms = emptyList(),
            transliteration = TransliterationTrace.empty(),
        )
    }

    companion object {

        /** 空の [CompositionSource] を生成する factory メソッド。 */
        fun empty(revision: Int = 0): CompositionSource {
            return CompositionSource(
                revision = revision,
                frozenPrefix = "",
                atoms = emptyList(),
                transliteration = TransliterationTrace.empty(),
            )
        }

        /**
         * 指定した [frozenPrefix] を持ち、atoms と transliteration が空の [CompositionSource] を生成する。
         *
         * 変換確定済みのかなを固定したまま、新しい romaji 入力セッションを開始するときに使う。
         */
        fun withFrozenPrefix(frozenPrefix: String, revision: Int): CompositionSource {
            return CompositionSource(
                revision = revision,
                frozenPrefix = frozenPrefix,
                atoms = emptyList(),
                transliteration = TransliterationTrace.empty(),
            )
        }
    }
}
