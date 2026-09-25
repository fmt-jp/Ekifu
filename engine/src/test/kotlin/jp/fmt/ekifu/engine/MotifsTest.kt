package jp.fmt.ekifu.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import jp.fmt.ekifu.engine.MusicConstants as C

class MotifsTest {

    @Test
    fun sameGridAlwaysGivesSameMotif() {
        for (grid in listOf("xn76ur", "xn774c", "u4pruy")) {
            assertEquals(Motifs.placeMotif(grid), Motifs.placeMotif(grid))
        }
    }

    @Test
    fun differentGridsUsuallyGiveDifferentMotifs() {
        val motifs = (0 until 200).map { Motifs.placeMotif("grid$it") }.toSet()
        assertTrue(motifs.size > 100, "モチーフの種類が少なすぎる: ${motifs.size}")
    }

    @Test
    fun motifsArePentatonicShortAndInRange() {
        for (i in 0 until 500) {
            val m = Motifs.placeMotif("g$i")
            assertTrue(m.size in C.MOTIF_MIN_LENGTH..C.MOTIF_MAX_LENGTH)
            m.forEach {
                assertTrue(Scale.isPentatonic(it))
                assertTrue(it in C.MOTIF_MIN_MIDI..C.MOTIF_MAX_MIDI)
            }
            m.zipWithNext().forEach { (a, b) -> assertTrue(abs(a - b) <= C.MOTIF_MAX_LEAP) }
        }
    }

    @Test
    fun themeEndsOnTonicAndIsReproducible() {
        for (seed in 0 until 500) {
            val t = Motifs.theme(seed)
            assertEquals(t, Motifs.theme(seed))
            assertEquals(C.TONIC_PITCH_CLASS, t.last() % 12)
            assertTrue(t.size in C.MOTIF_MIN_LENGTH..C.MOTIF_MAX_LENGTH)
            t.forEach { assertTrue(Scale.isPentatonic(it)) }
        }
    }

    @Test
    fun rerollingSeedChangesTheme() {
        assertNotEquals((0 until 20).map { Motifs.theme(it) }.toSet().size, 1)
    }
}

class MelodyTest {

    @Test
    fun notesStayInPentatonicRangeWithinLeap() {
        val rng = Mulberry32(7)
        var prev: Int? = null
        for (i in 0 until 5000) {
            val chord = Chord.entries[i / 16 % Chord.entries.size]
            val n = Melody.pickNote(prev, chord, C.MELODY_MIN_MIDI, C.MELODY_MAX_MIDI, rng)
            assertTrue(Scale.isPentatonic(n))
            assertTrue(n in C.MELODY_MIN_MIDI..C.MELODY_MAX_MIDI)
            if (prev != null) assertTrue(abs(n - prev) <= C.MELODY_MAX_LEAP)
            prev = n
        }
    }

    @Test
    fun chordTonesArePreferred() {
        val rng = Mulberry32(11)
        var inChord = 0
        val total = 5000
        repeat(total) {
            if (Chord.IV.contains(Melody.pickNote(77, Chord.IV, C.MELODY_MIN_MIDI, C.MELODY_MAX_MIDI, rng))) inChord++
        }
        // 候補のうち和音の構成音は約半分。優先するので明らかに多くなる
        assertTrue(inChord.toDouble() / total > 0.75, "和音の構成音の割合: ${inChord.toDouble() / total}")
    }
}

class Mulberry32Test {

    @Test
    fun sameSeedSameSequence() {
        val a = Mulberry32(42)
        val b = Mulberry32(42)
        repeat(1000) { assertEquals(a.nextDouble(), b.nextDouble()) }
    }

    @Test
    fun valuesAreInUnitInterval() {
        val r = Mulberry32(1)
        var sum = 0.0
        repeat(100_000) {
            val v = r.nextDouble()
            assertTrue(v >= 0.0 && v < 1.0)
            sum += v
        }
        assertTrue(abs(sum / 100_000 - 0.5) < 0.01)
    }

    @Test
    fun seedOfIsStable() {
        assertEquals(Mulberry32.seedOf("xn76ur"), Mulberry32.seedOf("xn76ur"))
        assertNotEquals(Mulberry32.seedOf("xn76ur"), Mulberry32.seedOf("xn76us"))
    }
}
