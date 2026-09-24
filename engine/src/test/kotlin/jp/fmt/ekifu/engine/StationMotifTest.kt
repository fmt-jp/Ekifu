package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationMotifTest {
    private val names = listOf("若葉台", "桜坂", "川辺", "中央", "港町", "丘の上", "汐見", "新宿", "渋谷", "東京")

    @Test
    fun motifIsDeterministicAndPentatonic() {
        for (name in names) {
            val motif = StationMotif.generate(name, isTerminal = false)
            assertEquals(motif, StationMotif.generate(name, isTerminal = false))
            assertTrue(motif.size in C.STATION_MOTIF_MIN_NOTES..C.STATION_MOTIF_MAX_NOTES)
            assertTrue(motif.all { it in MELODY_NOTES })
        }
    }

    @Test
    fun terminalMotifEndsOnTonic() {
        for (name in names) {
            assertEquals(C.TONIC_PITCH_CLASS, StationMotif.generate(name, isTerminal = true).last() % 12)
        }
    }

    @Test
    fun differentStationsUsuallySoundDifferent() {
        val motifs = names.map { StationMotif.generate(it, isTerminal = false) }.toSet()
        assertTrue(motifs.size >= names.size - 1)
    }
}
