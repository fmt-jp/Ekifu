package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C

/** 和音（ルートからの半音数）。先頭が根音。 */
enum class Chord(val intervals: IntArray) {
    I(intArrayOf(0, 4, 7, 11, 14)),
    IV(intArrayOf(5, 9, 12, 16, 19)),
    VI(intArrayOf(9, 12, 16, 19)),
    II(intArrayOf(2, 5, 9, 12, 16)),
    III(intArrayOf(4, 7, 11, 14)),
    VSUS(intArrayOf(7, 12, 14, 17));

    val pitchClasses: Set<Int> = intervals.map { (C.ROOT_MIDI + it) % 12 }.toSet()
}

/** 旅程のフェーズ。進み具合 p で決まる。 */
enum class Phase(
    val melodyProbability: Double,
    val progression: List<Chord>,
) {
    DEPARTURE(C.MELODY_PROBABILITY_DEPARTURE, listOf(Chord.I, Chord.IV)),
    MIDDLE(
        C.MELODY_PROBABILITY_MIDDLE,
        listOf(Chord.VI, Chord.IV, Chord.I, Chord.VSUS, Chord.II, Chord.III, Chord.IV, Chord.I),
    ),
    PRE_ARRIVAL(C.MELODY_PROBABILITY_PRE_ARRIVAL, listOf(Chord.IV, Chord.VSUS, Chord.IV, Chord.I)),
    ARRIVAL(C.MELODY_PROBABILITY_ARRIVAL, listOf(Chord.I));

    companion object {
        fun fromProgress(p: Double): Phase = when {
            p < C.PHASE_MIDDLE_START -> DEPARTURE
            p < C.PHASE_PRE_ARRIVAL_START -> MIDDLE
            p < C.PHASE_ARRIVAL_START -> PRE_ARRIVAL
            else -> ARRIVAL
        }
    }
}

/** メロディに使える音（音域内のペンタトニック）。 */
val MELODY_NOTES: List<Int> =
    (C.MELODY_MIN_MIDI..C.MELODY_MAX_MIDI).filter { it % 12 in C.PENTATONIC_PITCH_CLASSES }
