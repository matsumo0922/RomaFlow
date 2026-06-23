package me.matsumo.romaflow.core.ime

import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.HomophoneDictionary
import me.matsumo.romaflow.core.morphology.MozcCompactDictionaryReader
import me.matsumo.romaflow.core.morphology.MozcCompactLexicon
import me.matsumo.romaflow.core.morphology.MozcHomophoneDictionary
import me.matsumo.romaflow.core.morphology.ReadingLexicon

/**
 * 本番経路向けに、アプリへ同梱した Mozc compact binary（`mozc_dict.bin` / `mozc_matrix.bin`）の
 * 生バイト列をロードする platform adapter。
 *
 * macosArm64 は IME bundle の resource、Android は classpath（packaged resource）から読む。
 * いずれも数十 MB を一時確保するため、結果は呼び出し側（[MozcDictionaryFactory]）で 1 度だけ使い、
 * [ReadingLexicon] / [ConnectionCostProvider] 構築後は元のバイト列を解放できるようにする。
 */
internal expect object MozcBundleLoader {

    /** 同梱した `mozc_dict.bin` の生バイト列を返す。 */
    fun loadDictionaryBytes(): ByteArray

    /** 同梱した `mozc_matrix.bin` の生バイト列を返す。 */
    fun loadMatrixBytes(): ByteArray
}

/**
 * 同梱 Mozc binary から [RomaFlowEngine] が使う [ReadingLexicon] / [ConnectionCostProvider] /
 * [HomophoneDictionary] を組み立てる本番 factory。
 *
 * lexicon は素の [MozcCompactLexicon] を返す（OOV fallback の付与・literal 連接 ID の選択は
 * [RomaFlowEngine] の compositeLexicon 側で [me.matsumo.romaflow.core.morphology.LiteralContextIds.Mozc]
 * を用いて行う）。homophone は streaming index 経路（[MozcHomophoneDictionary.fromCompactLexicon]）で
 * 構築し、index 化は [HomophoneDictionary.ensureReady] の遅延実行に委ねる。連接コストは Mozc matrix
 * （[MozcCompactDictionaryReader.readConnectionCostProvider]）を使う。
 *
 * ロードと index 構築は重いため、[RomaFlowEngine] 側の `by lazy` factory から初回 convert() まで遅延される。
 */
internal object MozcDictionaryFactory {

    /** 同梱 `mozc_dict.bin` から素の [MozcCompactLexicon] を構築する（fallback 付与は engine 側）。 */
    fun createReadingLexicon(): ReadingLexicon {
        return MozcCompactLexicon(MozcBundleLoader.loadDictionaryBytes())
    }

    /** 同梱 `mozc_matrix.bin` から Mozc 連接コスト provider を構築する。 */
    fun createConnectionCostProvider(): ConnectionCostProvider {
        return MozcCompactDictionaryReader.readConnectionCostProvider(MozcBundleLoader.loadMatrixBytes())
    }

    /** 同梱 `mozc_dict.bin` から streaming 経路で同音語 [HomophoneDictionary] を構築する。 */
    fun createHomophoneDictionary(): HomophoneDictionary {
        return MozcHomophoneDictionary.fromCompactLexicon {
            MozcCompactLexicon(MozcBundleLoader.loadDictionaryBytes())
        }
    }
}
