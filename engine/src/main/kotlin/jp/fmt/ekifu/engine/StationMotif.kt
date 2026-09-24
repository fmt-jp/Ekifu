package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.abs

/** 駅ごとの 3〜4 音のモチーフ。駅名から決定的に作る。 */
object StationMotif {

    fun generate(stationName: String, isTerminal: Boolean): List<Int> {
        val rng = Mulberry32(fnv1a(stationName))
        val length = C.STATION_MOTIF_MIN_NOTES +
            rng.nextInt(C.STATION_MOTIF_MAX_NOTES - C.STATION_MOTIF_MIN_NOTES + 1)
        val starts = MELODY_NOTES.filter { it in C.STATION_MOTIF_START_MIN_MIDI..C.STATION_MOTIF_START_MAX_MIDI }
        val notes = mutableListOf(starts[rng.nextInt(starts.size)])
        while (notes.size < length) {
            val prev = notes.last()
            // 同じ音の連打は避けて、近い音へ動かす
            val candidates = MELODY_NOTES.filter { it != prev && abs(it - prev) <= C.MELODY_MAX_LEAP }
            notes += candidates[rng.nextInt(candidates.size)]
        }
        if (isTerminal) {
            val beforeLast = if (notes.size >= 2) notes[notes.size - 2] else notes.last()
            notes[notes.size - 1] = MELODY_NOTES
                .filter { it % 12 == C.TONIC_PITCH_CLASS }
                .minBy { abs(it - beforeLast) }
        }
        return notes
    }

    /** 端末や JVM に依存しない文字列ハッシュ（32bit FNV-1a、UTF-16 単位）。 */
    internal fun fnv1a(text: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (ch in text) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return hash
    }
}
