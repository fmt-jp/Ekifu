package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C

data class ComposerConfig(
    val startPhaseSec: Double = C.START_PHASE_SEC,
    val interludeEverySec: Double = C.INTERLUDE_EVERY_SEC,
    /** 再生開始時にベルで最初の場所のモチーフを鳴らすか（位置の取得を待つおまかせ再生では鳴らさない） */
    val playMotifAtStart: Boolean = true,
)

/** 画面のデバッグ表示用 */
data class ComposerStatus(
    val phase: Phase,
    val chord: Chord,
    val beatSec: Double,
    val noteProb: Double,
    val masterCutoffHz: Double,
    val scene: Scene,
    val place: Place?,
)

/**
 * 作曲（音符イベントを決める）。波形は作らない。
 *
 * 同じシード・同じ入力（setScene / approach / arrive / leave / stop を呼ぶ順番と時刻）なら
 * 必ず同じイベント列を返す。時刻の単位は再生開始からの秒。
 *
 * 入力による変化は、次の和音の切り替え時に反映する（4章）。
 */
class Composer(seed: Int, private val config: ComposerConfig = ComposerConfig()) {

    private var rng = Mulberry32(seed)
    private val out = ArrayList<MusicEvent>()

    /** 次に処理する半拍の時刻 */
    var timeSec = 0.0
        private set

    /** 終わりのフェードアウトが済む時刻（stop 後のみ） */
    var endTimeSec: Double? = null
        private set

    private var started = false
    private var ending = false

    private var phase = Phase.START
    private var phaseStartSec = 0.0
    private var progression = Progressions.START
    private var progIndex = 0
    private var chord = Chord.I
    private var chordBeats = C.CHORD_BEATS
    private var halfStepInChord = 0
    private var journeyBeats = 0

    private var scene = Scene.DEFAULT
    private var pendingScene: Scene? = null
    private var sceneMotif = Motifs.placeMotif(scene.gridId)
    private var previousSceneMotif: List<Int>? = null

    private var place: Place? = null
    private var placeTheme: List<Int>? = null
    private var pendingPhase: Phase? = null
    private var pendingPlace: Place? = null
    private var pendingLeave = false

    private var beatSec = Ramp(C.BASE_BEAT_SEC)
    private var noteProb = Ramp(C.PROB_START)
    private var moodActive = false
    private var cutoffHz = C.MASTER_CUTOFF_HZ
    private var delayFeedback = C.DELAY_FEEDBACK

    private var lastMelody: Int? = null
    private var melodyMuteUntilSec = 0.0

    // ---------------- 入力 ----------------

    /** 場面を変える（次の和音の切り替えで反映） */
    fun setScene(newScene: Scene) {
        pendingScene = newScene
    }

    /** 登録地点の外側の円に入った */
    fun approach(target: Place) {
        pendingPhase = Phase.APPROACH
        pendingPlace = target
    }

    /** 登録地点の内側の円に入った */
    fun arrive(target: Place) {
        pendingPhase = Phase.ARRIVE
        pendingPlace = target
    }

    /** 登録地点の外側の円から出た */
    fun leave() {
        pendingPhase = Phase.JOURNEY
        pendingLeave = true
    }

    /** 再生開始時にすでに内側の円の中にいる場合、始まりの代わりに滞在から始める */
    fun startInStay(target: Place) {
        check(!started) { "startInStay は再生開始前に呼ぶ" }
        stay(target)
    }

    /**
     * 到着の演出なしで滞在に入る。再生を始めてから最初の位置が取れたとき、
     * すでに内側の円の中にいた場合に使う（次の和音の切り替えで反映）
     */
    fun stay(target: Place) {
        pendingPhase = Phase.STAY
        pendingPlace = target
    }

    /** 停止ボタン：atSec に I を鳴らして約12秒でフェードアウトする */
    fun stop(atSec: Double): List<MusicEvent> {
        if (ending) return emptyList()
        ending = true
        phase = Phase.ENDING
        chord = Progressions.ENDING.first()
        endTimeSec = atSec + C.ENDING_FADE_SEC
        return endingEvents(atSec, scene)
    }

    /** 作曲の状態（乱数・フェーズ・場面・移り変わり途中の値）をまるごと複製する */
    fun copy(): Composer = Composer(0, config).also {
        it.rng = rng.copy()
        it.timeSec = timeSec
        it.endTimeSec = endTimeSec
        it.started = started
        it.ending = ending
        it.phase = phase
        it.phaseStartSec = phaseStartSec
        it.progression = progression
        it.progIndex = progIndex
        it.chord = chord
        it.chordBeats = chordBeats
        it.halfStepInChord = halfStepInChord
        it.journeyBeats = journeyBeats
        it.scene = scene
        it.pendingScene = pendingScene
        it.sceneMotif = sceneMotif
        it.previousSceneMotif = previousSceneMotif
        it.place = place
        it.placeTheme = placeTheme
        it.pendingPhase = pendingPhase
        it.pendingPlace = pendingPlace
        it.pendingLeave = pendingLeave
        it.beatSec = beatSec.copy()
        it.noteProb = noteProb.copy()
        it.moodActive = moodActive
        it.cutoffHz = cutoffHz
        it.delayFeedback = delayFeedback
        it.lastMelody = lastMelody
        it.melodyMuteUntilSec = melodyMuteUntilSec
    }

    /** tSec まで作曲を進め、新しく決まったイベントを返す */
    fun composeUntil(tSec: Double): List<MusicEvent> {
        out.clear()
        while (!ending && timeSec < tSec) step()
        return out.toList()
    }

    fun status() = ComposerStatus(
        phase = phase,
        chord = chord,
        beatSec = beatSec.value,
        noteProb = noteProb.value,
        masterCutoffHz = cutoffHz,
        scene = pendingScene ?: scene,
        place = place,
    )

    // ---------------- 内部 ----------------

    private fun step() {
        if (halfStepInChord == 0) onChordBoundary()
        val onBeat = halfStepInChord % 2 == 0
        melody(onBeat)
        if (onBeat) ornament()

        timeSec += beatSec.value / 2
        beatSec.advance(0.5)
        noteProb.advance(0.5)
        if (onBeat && phase == Phase.JOURNEY) journeyBeats++
        halfStepInChord = (halfStepInChord + 1) % (chordBeats * 2)
    }

    private fun onChordBoundary() {
        val first = !started
        started = true

        // 地点
        pendingPlace?.let {
            place = it
            placeTheme = Motifs.theme(it.themeSeed)
        }
        pendingPlace = null
        var leaveTheme: List<Int>? = null
        if (pendingLeave) {
            pendingLeave = false
            leaveTheme = placeTheme
            place = null
            placeTheme = null
        }

        // フェーズ
        val next = decideNextPhase(first)
        val entered = first || next != phase
        if (entered) {
            phase = next
            phaseStartSec = timeSec
            progression = Progressions.of(phase, activeMood())
            progIndex = 0
            if (phase == Phase.JOURNEY) journeyBeats = 0
        } else {
            progIndex = (progIndex + 1) % progression.size
        }

        // 場面
        // ベルで場所のモチーフを鳴らすのはマスが変わったときだけ。
        // 同じマスなら速さ・なじみ度・太陽の値だけ移す
        var sceneChanged = first && config.playMotifAtStart
        pendingScene?.let { newScene ->
            if (newScene.gridId != scene.gridId) {
                if (!first) previousSceneMotif = sceneMotif
                sceneMotif = Motifs.placeMotif(newScene.gridId)
                sceneChanged = !first || config.playMotifAtStart
            }
            scene = newScene
        }
        pendingScene = null

        updateTargets(first)

        // 和音
        chord = progression[progIndex]
        chordBeats = if (phase == Phase.ARRIVE) C.ARRIVE_CHORD_BEATS else C.CHORD_BEATS
        out += ReleaseEvent(timeSec, setOf(Instrument.PAD, Instrument.BASS))
        emitChord(timeSec, chord)

        // モチーフ（同じ切り替えで重ならないよう、地点のテーマを優先）
        val theme = placeTheme
        when {
            phase == Phase.ARRIVE && entered && theme != null ->
                playMotif(theme, Instrument.BELL, C.THEME_BELL_GAIN)
            phase == Phase.APPROACH && theme != null ->
                playMotif(theme, Instrument.PLUCK, C.QUIET_PLUCK_GAIN)
            leaveTheme != null ->
                playMotif(leaveTheme, Instrument.PLUCK, C.QUIET_PLUCK_GAIN)
            sceneChanged ->
                playMotif(sceneMotif, Instrument.BELL, C.BELL_GAIN)
            phase == Phase.JOURNEY && journeyBeats > 0 &&
                journeyBeats % C.JOURNEY_MOTIF_ECHO_BEATS == 0 ->
                previousSceneMotif?.let { playMotif(it, Instrument.PLUCK, C.QUIET_PLUCK_GAIN) }
        }
    }

    private fun decideNextPhase(first: Boolean): Phase {
        pendingPhase?.let {
            pendingPhase = null
            return it
        }
        if (first) return Phase.START
        val elapsed = timeSec - phaseStartSec
        val progressionDone = progIndex + 1 >= progression.size
        return when (phase) {
            Phase.START -> if (elapsed >= config.startPhaseSec) Phase.JOURNEY else Phase.START
            Phase.JOURNEY -> if (elapsed >= config.interludeEverySec) Phase.INTERLUDE else Phase.JOURNEY
            Phase.INTERLUDE -> if (progressionDone) Phase.JOURNEY else Phase.INTERLUDE
            Phase.ARRIVE -> if (progressionDone) Phase.STAY else Phase.ARRIVE
            else -> phase
        }
    }

    private fun activeMood(): Mood? =
        if (phase == Phase.APPROACH || phase == Phase.ARRIVE || phase == Phase.STAY) place?.mood else null

    /** テンポ・発音確率・フィルターの目標値を決め、8拍（雰囲気の出入りは16拍）かけて移す */
    private fun updateTargets(first: Boolean) {
        val mood = activeMood()
        val beats = if ((mood != null) != moodActive) C.MOOD_TRANSITION_BEATS else C.SCENE_TRANSITION_BEATS
        moodActive = mood != null

        val targetBeat = mood?.beatSec ?: scene.speed.beatSec
        val phaseProb = when (phase) {
            Phase.START -> C.PROB_START
            Phase.JOURNEY -> scene.speed.noteProb
            Phase.INTERLUDE -> C.PROB_INTERLUDE
            Phase.APPROACH -> C.PROB_APPROACH
            Phase.ARRIVE -> C.PROB_ARRIVE
            Phase.STAY -> C.PROB_STAY
            Phase.ENDING -> 0.0
        }
        val familiarFactor = if (scene.familiarity == Familiarity.FAMILIAR) C.FAMILIAR_PROB_FACTOR else 1.0
        val targetProb = phaseProb * familiarFactor * (mood?.probFactor ?: 1.0)
        val targetCutoff = minOf(scene.sun.cutoffHz, mood?.cutoffHz ?: Double.MAX_VALUE)
        val targetFeedback = mood?.delayFeedback ?: C.DELAY_FEEDBACK

        if (first) {
            beatSec.jump(targetBeat)
            noteProb.jump(targetProb)
        } else {
            beatSec.set(targetBeat, beats)
            noteProb.set(targetProb, beats)
        }
        if (first || targetCutoff != cutoffHz || targetFeedback != delayFeedback) {
            cutoffHz = targetCutoff
            delayFeedback = targetFeedback
            val rampSec = if (first) 0.0 else beats * beatSec.value
            out += ControlEvent(timeSec, cutoffHz, delayFeedback, rampSec)
        }
    }

    private fun emitChord(atSec: Double, chord: Chord) {
        out += chordEvents(atSec, chord, scene)
    }

    private fun playMotif(notes: List<Int>, instrument: Instrument, gain: Double) {
        val interval = C.MOTIF_NOTE_BEATS * beatSec.value
        notes.forEachIndexed { i, midi ->
            out += NoteEvent(timeSec + i * interval, instrument, midi, gain, pan = 0.0)
        }
        melodyMuteUntilSec = timeSec + (notes.size + 1) * interval
    }

    private fun melody(onBeat: Boolean) {
        if (timeSec < melodyMuteUntilSec) return
        val p = noteProb.value * (if (onBeat) 1.0 else C.OFFBEAT_PROB_FACTOR)
        if (rng.nextDouble() >= p) return
        val extra = activeMood()?.melodyExtraSemitones ?: 0
        val midi = Melody.pickNote(lastMelody, chord, C.MELODY_MIN_MIDI, C.MELODY_MAX_MIDI + extra, rng)
        lastMelody = midi
        val velocity = rng.nextDouble(C.MELODY_VELOCITY_MIN, 1.0)
        val pan = rng.nextDouble(-C.MELODY_PAN_WIDTH, C.MELODY_PAN_WIDTH)
        out += NoteEvent(timeSec, Instrument.PLUCK, midi, C.PLUCK_GAIN * velocity, pan)
    }

    private fun ornament() {
        val p = (if (scene.familiarity == Familiarity.NEW) C.NEW_PLACE_ORNAMENT_PROB else 0.0) +
            (activeMood()?.ornamentProb ?: 0.0)
        if (p <= 0.0 || rng.nextDouble() >= p) return
        val midi = rng.pick(Scale.pentatonic(C.ORNAMENT_MIN_MIDI, C.ORNAMENT_MAX_MIDI))
        val pan = rng.nextDouble(-C.MELODY_PAN_WIDTH, C.MELODY_PAN_WIDTH)
        out += NoteEvent(timeSec, Instrument.BELL, midi, C.ORNAMENT_BELL_GAIN, pan)
    }
}

/** 和音（パッドとベース）の発音イベント */
fun chordEvents(atSec: Double, chord: Chord, scene: Scene, padAttackSec: Double? = null): List<NoteEvent> {
    val shift = scene.sun.chordShift
    val padGain = C.PAD_GAIN *
        (if (scene.familiarity == Familiarity.FAMILIAR) C.FAMILIAR_PAD_GAIN_FACTOR else 1.0)
    val n = chord.intervals.size
    val events = ArrayList<NoteEvent>(n + 1)
    chord.intervals.forEachIndexed { i, interval ->
        // 構成音を左右に少しずつ広げる
        val pan = if (n > 1) -0.4 + 0.8 * i / (n - 1) else 0.0
        events += NoteEvent(atSec, Instrument.PAD, C.ROOT_MIDI + interval + shift, padGain, pan, attackSec = padAttackSec)
    }
    events += NoteEvent(atSec, Instrument.BASS, C.ROOT_MIDI + chord.rootInterval - 12 + shift, C.BASS_GAIN)
    return events
}

/** 終わり：鳴っているパッドとベースを余韻に入らせ、I を鳴らして約12秒でフェードアウトする */
fun endingEvents(atSec: Double, scene: Scene): List<MusicEvent> = buildList {
    add(ReleaseEvent(atSec, setOf(Instrument.PAD, Instrument.BASS)))
    addAll(chordEvents(atSec, Progressions.ENDING.first(), scene))
    add(FadeOutEvent(atSec, C.ENDING_FADE_SEC))
}

/** 拍単位で目標値へ直線的に移る値 */
internal class Ramp(initial: Double) {
    var value = initial
        private set
    private var target = initial
    private var perBeat = 0.0

    fun set(newTarget: Double, beats: Int) {
        target = newTarget
        perBeat = (newTarget - value) / beats
    }

    fun copy() = Ramp(value).also {
        it.target = target
        it.perBeat = perBeat
    }

    fun jump(v: Double) {
        value = v
        target = v
        perBeat = 0.0
    }

    fun advance(beats: Double) {
        if (value == target) return
        value += perBeat * beats
        if ((perBeat > 0 && value > target) || (perBeat < 0 && value < target)) value = target
    }
}
