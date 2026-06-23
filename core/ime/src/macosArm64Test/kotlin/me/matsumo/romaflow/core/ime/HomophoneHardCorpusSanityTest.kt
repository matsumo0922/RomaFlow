package me.matsumo.romaflow.core.ime

import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * HOMOPHONE_HARD corpus の gold が Mozc 格子上で到達可能であることを保証するサニティテスト。
 *
 * [ReadingLatticeDecoder.findMinCostPathForSurface] で各 gold の (reading, surface) を検証し、
 * 非 null（到達可能）であることを assert する。
 *
 * 到達不能な gold は評価が不公平になるため（gold に辿り着けない以上、resolver が正解しようがない）、
 * このテストで早期に検出して corpus から除外する。
 */
class HomophoneHardCorpusSanityTest {

    private val lexicon by lazy { MozcTestDictionary.compositeLexicon }
    private val costProvider by lazy { MozcTestDictionary.costProvider }

    /**
     * HOMOPHONE_HARD の全 gold が Mozc で格子到達可能であることを assert する。
     *
     * findMinCostPathForSurface が非 null = 格子内に (reading, surface) を実現する path が存在する。
     */
    @Test
    fun allHomophoneHardGoldsAreReachableInMozc() {
        val entries = EvaluationCorpus.homophoneHard

        println("=== HOMOPHONE_HARD corpus sanity check: ${entries.size} entries ===")

        for (entry in entries) {
            val path = ReadingLatticeDecoder.findMinCostPathForSurface(
                reading = entry.reading,
                surface = entry.expectedSurface,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            println(
                "reading=${entry.reading} gold=${entry.expectedSurface} reachable=${path != null}",
            )

            assertNotNull(
                actual = path,
                message = "HOMOPHONE_HARD gold が Mozc で格子到達不能: " +
                    "reading=${entry.reading} gold=${entry.expectedSurface}",
            )
        }

        println("=== All ${entries.size} HOMOPHONE_HARD golds are reachable in Mozc ===")
    }
}
