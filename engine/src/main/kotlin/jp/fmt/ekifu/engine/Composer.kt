package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.abs

/** 1 拍ぶんの作曲に必要な旅程の状態。 */
data class BeatContext(
    val progress: Double,
    val underground: Boolean,
    /** この拍までに新しく通過した駅のモチーフ（なければ null）。 */
    val passedStationMotif: List<Int>? = null,
)

/**
 * 拍ごとに音符イベントを返す作曲器。波形は作らない。
 * 乱数はシード付きなので、同じシード・同じ入力列なら必ず同じイベント列になる。
 */
class Composer(seed: Int) {
    private val rng = Mulberry32(seed)

    /** 現在鳴らしている和音進行のフェーズ（和音の切り替え時にだけ変わる）。 */
    var activePhase: Phase? = null
        private set
    private var progressionIndex = 0
    var currentChord: Chord = Chord.I
        private set
    private var lastMelodyNote = C.MELODY_START_MIDI
    private var lastMotif: List<Int>? = null
    /** 駅のモチーフが鳴っている間はメロディを休む。 */
    private var melodyRestUntilBeat = Double.NEGATIVE_INFINITY

    /** 到着の和音を鳴らし終えたか。以降はイベントを返さない。 */
    var arrived = false
        private set

    fun composeBeat(beat: Long, ctx: BeatContext): List<NoteEvent> {
        if (arrived) return emptyList()
        val events = mutableListOf<NoteEvent>()
        val octaveShift = if (ctx.underground) C.UNDERGROUND_OCTAVE_SHIFT else 0

        ctx.passedStationMotif?.let { motif ->
            lastMotif = motif
            events += motifEvents(beat, motif, Instrument.BELL, C.STATION_BELL_STEP_BEATS, C.STATION_BELL_VELOCITY)
        }

        if (beat % C.BEATS_PER_CHORD == 0L) {
            val phase = Phase.fromProgress(ctx.progress)
            if (phase != activePhase) {
                activePhase = phase
                progressionIndex = 0
            } else {
                progressionIndex = (progressionIndex + 1) % phase.progression.size
            }
            currentChord = phase.progression[progressionIndex]
            val arriving = phase == Phase.ARRIVAL
            val duration = if (arriving) C.ARRIVAL_HOLD_BEATS else C.BEATS_PER_CHORD.toDouble()
            events += chordEvents(beat, currentChord, duration, octaveShift)
            if (arriving) {
                arrived = true
                return events
            }
        }

        val phase = activePhase ?: return events
        val motif = lastMotif
        if (phase == Phase.MIDDLE && motif != null && beat % C.MOTIF_REPRISE_INTERVAL_BEATS == 0L &&
            ctx.passedStationMotif == null
        ) {
            events += motifEvents(beat, motif, Instrument.PLUCK, C.MOTIF_REPRISE_STEP_BEATS, C.MOTIF_REPRISE_VELOCITY)
        }

        val undergroundFactor = if (ctx.underground) C.UNDERGROUND_PROBABILITY_FACTOR else 1.0
        for (offset in doubleArrayOf(0.0, C.OFFBEAT_POSITION)) {
            val position = beat + offset
            val offbeatFactor = if (offset == 0.0) 1.0 else C.OFFBEAT_PROBABILITY_FACTOR
            val probability = phase.melodyProbability * offbeatFactor * undergroundFactor
            // 休み中も乱数は消費し、モチーフの有無で後続の曲が変わりすぎないようにする
            val hit = rng.nextDouble() < probability
            if (!hit || position < melodyRestUntilBeat) continue
            val note = pickMelodyNote(currentChord)
            lastMelodyNote = note
            events += NoteEvent(
                beat = position,
                instrument = Instrument.PLUCK,
                midi = note + octaveShift,
                durationBeats = 1.0,
                velocity = C.PLUCK_VELOCITY_MIN + C.PLUCK_VELOCITY_RANGE * rng.nextDouble(),
                pan = (rng.nextDouble() * 2 - 1) * C.PLUCK_PAN_WIDTH,
            )
        }
        return events
    }

    private fun chordEvents(beat: Long, chord: Chord, durationBeats: Double, octaveShift: Int): List<NoteEvent> {
        val events = mutableListOf<NoteEvent>()
        val count = chord.intervals.size
        chord.intervals.forEachIndexed { i, interval ->
            val pan = if (count > 1) (i.toDouble() / (count - 1) * 2 - 1) * C.PAD_PAN_SPREAD else 0.0
            events += NoteEvent(beat.toDouble(), Instrument.PAD, C.ROOT_MIDI + interval + octaveShift, durationBeats, 1.0, pan)
        }
        events += NoteEvent(
            beat.toDouble(),
            Instrument.BASS,
            C.ROOT_MIDI + chord.intervals[0] + C.BASS_OCTAVE_SHIFT + octaveShift,
            durationBeats,
            1.0,
        )
        return events
    }

    private fun motifEvents(
        beat: Long,
        motif: List<Int>,
        instrument: Instrument,
        stepBeats: Double,
        velocity: Double,
    ): List<NoteEvent> {
        melodyRestUntilBeat = beat + motif.size * stepBeats
        return motif.mapIndexed { i, midi ->
            NoteEvent(beat + i * stepBeats, instrument, midi, stepBeats, velocity)
        }
    }

    /** 直前の音から跳躍の上限以内で、和音の構成音を優先して選ぶ。 */
    private fun pickMelodyNote(chord: Chord): Int {
        val candidates = MELODY_NOTES.filter { abs(it - lastMelodyNote) <= C.MELODY_MAX_LEAP }
        val chordTones = candidates.filter { it % 12 in chord.pitchClasses }
        val others = candidates.filter { it % 12 !in chord.pitchClasses }
        val allowOther = rng.nextDouble() < C.NON_CHORD_TONE_PROBABILITY
        val pool = when {
            chordTones.isEmpty() -> others
            others.isNotEmpty() && allowOther -> others
            else -> chordTones
        }
        return pool[rng.nextInt(pool.size)]
    }
}
