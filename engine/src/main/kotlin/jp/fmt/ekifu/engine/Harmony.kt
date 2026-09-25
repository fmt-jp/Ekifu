package jp.fmt.ekifu.engine

import kotlin.math.pow

/** 和音。構成音はルート（F3）からの半音数。先頭が根音。 */
enum class Chord(val label: String, val intervals: IntArray) {
    I("I", intArrayOf(0, 4, 7, 11, 14)),
    IV("IV", intArrayOf(5, 9, 12, 16, 19)),
    VI("vi", intArrayOf(9, 12, 16, 19)),
    II("ii", intArrayOf(2, 5, 9, 12, 16)),
    III("iii", intArrayOf(4, 7, 11, 14)),
    VSUS("Vsus", intArrayOf(7, 12, 14, 17));

    val rootInterval: Int get() = intervals[0]

    /** 和音の構成音のピッチクラス */
    val pitchClasses: Set<Int> = intervals.map { (MusicConstants.ROOT_MIDI + it) % 12 }.toSet()

    fun contains(midi: Int): Boolean = (midi % 12) in pitchClasses
}

object Progressions {
    val START = listOf(Chord.I, Chord.IV)
    val JOURNEY = listOf(
        Chord.VI, Chord.IV, Chord.I, Chord.VSUS, Chord.II, Chord.III, Chord.IV, Chord.I,
    )
    val INTERLUDE = listOf(Chord.IV, Chord.VSUS, Chord.IV, Chord.I)
    val APPROACH = listOf(Chord.IV, Chord.VSUS, Chord.IV, Chord.I)
    val ARRIVE = listOf(Chord.I)
    val STAY = listOf(Chord.I, Chord.IV, Chord.VI, Chord.IV)
    val STAY_NOSTALGIC = listOf(Chord.VI, Chord.IV, Chord.I, Chord.III)
    val ENDING = listOf(Chord.I)

    fun of(phase: Phase, mood: Mood?): List<Chord> = when (phase) {
        Phase.START -> START
        Phase.JOURNEY -> JOURNEY
        Phase.INTERLUDE -> INTERLUDE
        Phase.APPROACH -> APPROACH
        Phase.ARRIVE -> ARRIVE
        Phase.STAY -> if (mood == Mood.NOSTALGIC) STAY_NOSTALGIC else STAY
        Phase.ENDING -> ENDING
    }
}

object Scale {
    fun isPentatonic(midi: Int): Boolean =
        MusicConstants.PENTATONIC_PITCH_CLASSES.contains(((midi % 12) + 12) % 12)

    /** [min, max] に含まれるペンタトニックの音（昇順） */
    fun pentatonic(min: Int, max: Int): List<Int> = (min..max).filter { isPentatonic(it) }

    fun midiToHz(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
}
