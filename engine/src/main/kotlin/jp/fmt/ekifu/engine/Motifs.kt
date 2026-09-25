package jp.fmt.ekifu.engine

import kotlin.math.abs

/** モチーフ（4章）。3〜4音、ペンタトニック内。 */
object Motifs {

    /** 場所のモチーフ：マスIDをシードに作る。同じマスなら常に同じ。 */
    fun placeMotif(gridId: String): List<Int> =
        generate(Mulberry32(Mulberry32.seedOf(gridId)), endOnTonic = false)

    /** 地点のテーマ：themeSeed から作る。最後の音は主音（F）。 */
    fun theme(themeSeed: Int): List<Int> = generate(Mulberry32(themeSeed), endOnTonic = true)

    private fun generate(rng: Mulberry32, endOnTonic: Boolean): List<Int> {
        val c = MusicConstants
        val pool = Scale.pentatonic(c.MOTIF_MIN_MIDI, c.MOTIF_MAX_MIDI)
        val length = c.MOTIF_MIN_LENGTH + rng.nextInt(c.MOTIF_MAX_LENGTH - c.MOTIF_MIN_LENGTH + 1)
        val notes = mutableListOf(rng.pick(pool))
        while (notes.size < length) {
            val prev = notes.last()
            val isLast = notes.size == length - 1
            val next = if (isLast && endOnTonic) {
                pool.filter { it % 12 == c.TONIC_PITCH_CLASS }.minBy { abs(it - prev) }
            } else {
                val steps = pool.filter { it != prev && abs(it - prev) <= c.MOTIF_MAX_LEAP }
                rng.pick(steps)
            }
            notes += next
        }
        return notes
    }
}

/** メロディの音選び（4章） */
object Melody {
    /**
     * 直前の音から7半音以内のペンタトニックの音を選ぶ。
     * 現在の和音の構成音を優先し、NON_CHORD_TONE_PROB の確率で和音外の音も候補に入れる。
     */
    fun pickNote(prev: Int?, chord: Chord, minMidi: Int, maxMidi: Int, rng: Mulberry32): Int {
        val all = Scale.pentatonic(minMidi, maxMidi)
        val near = if (prev == null) all else all.filter { abs(it - prev) <= MusicConstants.MELODY_MAX_LEAP }
        val candidates = near.ifEmpty { listOf(all.minBy { abs(it - prev!!) }) }
        val chordTones = candidates.filter { chord.contains(it) }
        val allowNonChord = rng.nextDouble() < MusicConstants.NON_CHORD_TONE_PROB
        val pool = if (allowNonChord || chordTones.isEmpty()) candidates else chordTones
        return rng.pick(pool)
    }
}
