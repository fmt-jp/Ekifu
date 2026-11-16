package jp.fmt.ekifu.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import jp.fmt.ekifu.engine.AmbientConstants as A

/** アンビエントの和音。root は根音の音名（5=F）、tones は根音から数えた音（パッドで鳴らす。開いた響き） */
data class AmbChord(val name: String, val root: Int, val tones: List<Int>) {
    companion object {
        val FADD9 = AmbChord("Fadd9", 5, listOf(0, 7, 14, 16))
        val BBMAJ7S11 = AmbChord("B♭maj7♯11", 10, listOf(0, 7, 11, 18))
        val DM9 = AmbChord("Dm9", 2, listOf(0, 7, 10, 14))
        val CSUS2 = AmbChord("Csus2", 0, listOf(0, 7, 14))
        val AM7 = AmbChord("Am7", 9, listOf(0, 7, 10, 15))
        val GM11 = AmbChord("Gm11", 7, listOf(0, 7, 10, 17))
        val EBMAJ9 = AmbChord("E♭maj9", 3, listOf(0, 7, 11, 14))
        val DBMAJ7 = AmbChord("D♭maj7", 1, listOf(0, 7, 11, 16))

        val ALL = listOf(FADD9, BBMAJ7S11, DM9, CSUS2, AM7, GM11, EBMAJ9, DBMAJ7)
        fun byName(name: String?) = ALL.firstOrNull { it.name == name }
    }
}

/** 和音の選び方（なじみ度）：なじみ＝主和音のまわりを行き来、ときどき＝調の中をさまよう、初めて＝調の外の和音にも寄り道 */
object AmbientHarmony {
    val FAMILIAR = listOf(AmbChord.FADD9, AmbChord.BBMAJ7S11, AmbChord.FADD9, AmbChord.DM9)
    val NORMAL = listOf(AmbChord.FADD9, AmbChord.AM7, AmbChord.BBMAJ7S11, AmbChord.CSUS2, AmbChord.DM9, AmbChord.GM11)
    val NEW = listOf(AmbChord.FADD9, AmbChord.DBMAJ7, AmbChord.EBMAJ9, AmbChord.BBMAJ7S11, AmbChord.GM11, AmbChord.AM7)
    /** 滞在：主和音のまわり */
    val STAY = listOf(AmbChord.FADD9, AmbChord.BBMAJ7S11, AmbChord.FADD9, AmbChord.CSUS2)
}

/** アンビエントの作曲の設定 */
data class AmbientComposerConfig(
    /** 道中がこれだけ続くごとに区切りを1回 */
    val interludeEverySec: Double = MusicConstants.INTERLUDE_EVERY_SEC,
)

/**
 * アンビエントの作曲（段階8b）。拍はなく、和音ごと（8〜18秒）に作る。
 * - 始まり：ドローンだけがふわっと入り、次の和音からパッドと粒
 * - 道中：パッド・ドローン・粒、2和音ごとにピアノが場所のモチーフ。マスが変わったらベルでもモチーフ
 * - 区切り（道中20分ごと）：パッドを引いてドローンと粒だけの静かな2和音
 * - 接近：テーマをベルが遠く（小さく・残響多め）から鳴らし、和音ごとに少しずつ近く。途中からピアノも重なる
 * - 到着：パッドを引いて合図（ベルの F・A・C。読み上げだけならベルなし）→ ピアノとベルでテーマ → Fadd9 が大きく広がって着地
 * - 滞在：主和音のまわりをゆったり、粒は少なく。ときどきベルでテーマを静かに
 * - 離れる：次の和音から道中。テーマを一度、遠ざかるように（だんだん小さく）
 * 地点の判定が来たら [react] で鳴っている和音を切り上げ、数秒で次の和音（演出）へ移る。
 * 同じシード・同じ入力なら同じ結果。
 */
class AmbientComposer(seed: Int, private val config: AmbientComposerConfig = AmbientComposerConfig()) : ComposerInput {

    private var rng = Mulberry32(seed)
    private val out = ArrayList<AmbientEvent>()

    private var scene = Scene.DEFAULT
    private var pendingScene: Scene? = null
    private var index = 0
    private var startSec = 0.0
    private var chord = AmbChord.FADD9
    private var familiarStep = 0
    private var lastVoicing: List<Int>? = null
    private var lastBrightness = -1.0
    private var droneUntilSec = 0.0
    private var lastGrid: String? = null
    private var pendingCut: Double? = null

    private var phase = Phase.START
    private var phaseChords = 0
    private var journeyStartSec = 0.0
    private var place: Place? = null
    private var pendingPhase: Phase? = null
    private var pendingPlace: Place? = null
    private var pendingLeave = false
    private var deferredApproach: Place? = null
    private var leftPlace: Place? = null
    private var arrivalChimeOn = true
    private var arrivalSerial = 0
    private var arrival: Int? = null

    private val history = ArrayDeque<ChordInfo>()

    data class ChordInfo(
        val startSec: Double,
        val durSec: Double,
        val chord: AmbChord,
        val scene: Scene,
        val brightnessHz: Double,
        val phase: Phase,
        val place: Place?,
        val arrival: Int?,
    )

    // ---------------- 入力 ----------------

    override fun setScene(newScene: Scene) {
        pendingScene = newScene
    }

    override fun approach(target: Place) {
        pendingPhase = Phase.APPROACH
        deferredApproach = null
        pendingPlace = target
    }

    override fun arrive(target: Place) {
        pendingPhase = Phase.ARRIVE
        deferredApproach = null
        pendingPlace = target
    }

    override fun leave() {
        pendingPhase = Phase.JOURNEY
        deferredApproach = null
        pendingLeave = true
    }

    override fun stay(target: Place) {
        pendingPhase = Phase.STAY
        deferredApproach = null
        pendingPlace = target
    }

    override fun approachAfterStart(target: Place) {
        deferredApproach = target
    }

    override fun setArrivalChime(on: Boolean) {
        arrivalChimeOn = on
    }

    /**
     * 地点の判定が来ていたら、鳴っている和音を nowSec + [A.REACT_SEC] で切り上げる（エンジンが入力のあとに呼ぶ）
     */
    fun react(nowSec: Double) {
        if (pendingPhase == null) return
        val at = nowSec + A.REACT_SEC
        if (startSec > at) {
            startSec = at
            pendingCut = at
        }
    }

    fun copy(): AmbientComposer = AmbientComposer(0, config).also {
        it.rng = rng.copy()
        it.scene = scene
        it.pendingScene = pendingScene
        it.index = index
        it.startSec = startSec
        it.chord = chord
        it.familiarStep = familiarStep
        it.lastVoicing = lastVoicing
        it.lastBrightness = lastBrightness
        it.droneUntilSec = droneUntilSec
        it.lastGrid = lastGrid
        it.pendingCut = pendingCut
        it.phase = phase
        it.phaseChords = phaseChords
        it.journeyStartSec = journeyStartSec
        it.place = place
        it.pendingPhase = pendingPhase
        it.pendingPlace = pendingPlace
        it.pendingLeave = pendingLeave
        it.deferredApproach = deferredApproach
        it.leftPlace = leftPlace
        it.arrivalChimeOn = arrivalChimeOn
        it.arrivalSerial = arrivalSerial
        it.arrival = arrival
        it.history.addAll(history)
    }

    fun composeUntil(tSec: Double): List<AmbientEvent> {
        out.clear()
        // 切り上げは先に出す（あとから出す新しい和音の音を取り消さないように）
        pendingCut?.let { out += AmbCut(it) }
        pendingCut = null
        while (startSec <= tSec) composeChord()
        return out.toList()
    }

    fun chordAt(tSec: Double): ChordInfo? = history.lastOrNull { it.startSec <= tSec + 1e-9 }

    // ---------------- 和音を作る ----------------

    private fun composeChord() {
        pendingScene?.let { scene = it }
        pendingScene = null
        decidePhase()
        val shift = A.registerShift(scene.sun)
        val base = A.chordSec(scene.speed)
        val brightness = A.brightnessHz(scene.sun)
        if (brightness != lastBrightness) {
            out += AmbControl(startSec, brightness)
            lastBrightness = brightness
        }

        val dur: Double
        when (phase) {
            Phase.START -> {
                // ドローンだけがふわっと入る
                dur = base
                chord = AmbChord.FADD9
                drone(dur)
            }
            Phase.INTERLUDE -> {
                dur = base
                chord = nextChord()
                drone(dur)
                grains(dur, shift, 1.0)
            }
            Phase.ARRIVE -> dur = arriveChord(base, shift)
            Phase.STAY -> {
                dur = base
                chord = if (phaseChords == 0) AmbChord.FADD9 else AmbientHarmony.STAY[phaseChords % AmbientHarmony.STAY.size]
                drone(dur)
                pads(dur, shift)
                grains(dur, shift, A.STAY_GRAIN_FACTOR)
                if (phaseChords % A.STAY_BELL_EVERY_CHORDS == A.STAY_BELL_EVERY_CHORDS - 1) {
                    place?.let { bellTheme(it, startSec + 1.5, A.STAY_BELL_GAIN, A.BELL_SEND) }
                }
            }
            Phase.APPROACH -> {
                dur = base
                chord = nextChord()
                drone(dur)
                pads(dur, shift)
                grains(dur, shift, 1.0)
                place?.let { p ->
                    // 遠くから少しずつ近く
                    val gain = minOf(A.APPROACH_BELL_START * 10.0.pow(A.APPROACH_BELL_STEP_DB * phaseChords / 20.0), A.APPROACH_BELL_MAX)
                    val far = phaseChords < A.APPROACH_PIANO_FROM
                    bellTheme(p, startSec + 1.0, gain, if (far) A.BELL_FAR_SEND else A.BELL_SEND)
                    if (!far) pianoTheme(p, startSec + 1.0 + A.THEME_GAP_SEC * 0.5, A.PIANO_VELOCITY, shift)
                }
            }
            else -> {
                // 道中
                dur = base
                chord = if (index == 0) AmbChord.FADD9 else nextChord()
                drone(dur)
                pads(dur, shift)
                grains(dur, shift, 1.0)
                val left = leftPlace
                if (left != null && phaseChords == 0) {
                    leaveTheme(left, startSec + 1.0)
                } else {
                    if (lastGrid != null && lastGrid != scene.gridId) bellMotif(startSec + 1.0)
                    if (phaseChords % A.PIANO_EVERY_CHORDS == 1) piano(shift)
                }
                lastGrid = scene.gridId
            }
        }

        history.addLast(ChordInfo(startSec, dur, chord, scene, brightness, phase, place, arrival))
        while (history.size > HISTORY) history.removeFirst()
        startSec += dur
        index++
        phaseChords++
    }

    /** この和音のフェーズを決める。入力があればその演出、なければ自動の変わり目 */
    private fun decidePhase() {
        val pend = pendingPhase
        if (pend != null) {
            pendingPhase = null
            pendingPlace?.let { place = it }
            pendingPlace = null
            leftPlace = null
            if (pendingLeave) {
                pendingLeave = false
                leftPlace = place
                place = null
            }
            startPhase(pend)
            return
        }
        when (phase) {
            Phase.START -> if (phaseChords >= 1) {
                val d = deferredApproach
                if (d != null) {
                    deferredApproach = null
                    place = d
                    startPhase(Phase.APPROACH)
                } else {
                    startPhase(Phase.JOURNEY)
                }
            }
            Phase.INTERLUDE -> if (phaseChords >= A.INTERLUDE_CHORDS) startPhase(Phase.JOURNEY)
            Phase.ARRIVE -> if (phaseChords >= ARRIVE_CHORDS) startPhase(Phase.STAY)
            Phase.JOURNEY -> {
                if (phaseChords > 0) leftPlace = null
                if (phaseChords > 0 && startSec - journeyStartSec >= config.interludeEverySec) startPhase(Phase.INTERLUDE)
            }
            else -> Unit
        }
    }

    private fun startPhase(p: Phase) {
        phase = p
        phaseChords = 0
        if (p == Phase.JOURNEY || p == Phase.INTERLUDE) journeyStartSec = startSec
        arrival = if (p == Phase.ARRIVE) ++arrivalSerial else null
        if (p == Phase.JOURNEY) lastGrid = scene.gridId
    }

    /** 到着の3つの和音：合図 → テーマ → 着地。返り値は和音の長さ */
    private fun arriveChord(base: Double, shift: Int): Double {
        val p = place
        return when (phaseChords) {
            0 -> {
                // パッドを引いて（鳴っている和音を余韻に入らせて）合図だけ
                out += AmbCut(startSec)
                chord = AmbChord.FADD9
                drone(A.ARRIVE_CUE_SEC + base)
                if (arrivalChimeOn) {
                    A.ARRIVAL_CHIME_MIDI.forEachIndexed { i, midi ->
                        out += AmbBell(startSec + i * A.ARRIVAL_CHIME_GAP_SEC, midi, A.ARRIVAL_CHIME_GAIN)
                    }
                }
                A.ARRIVE_CUE_SEC
            }
            1 -> {
                chord = AmbChord.FADD9
                val themeLen = (p?.let { Motifs.theme(it.themeSeed).size } ?: 3) * A.THEME_GAP_SEC + 3.0
                val dur = maxOf(base, themeLen)
                drone(dur)
                pads(dur, shift)
                p?.let {
                    pianoTheme(it, startSec + 0.8, A.PIANO_VELOCITY * 1.2, shift)
                    bellTheme(it, startSec + 0.8, A.ARRIVE_BELL_GAIN, A.BELL_SEND)
                }
                dur
            }
            else -> {
                // 着地：Fadd9 を大きく広げて長く
                chord = AmbChord.FADD9
                val dur = base * A.ARRIVE_LAND_FACTOR
                drone(dur)
                pads(dur, shift, wide = true)
                grains(dur, shift, A.STAY_GRAIN_FACTOR)
                dur
            }
        }
    }

    private fun nextChord(): AmbChord = when (scene.familiarity) {
        Familiarity.FAMILIAR -> AmbientHarmony.FAMILIAR[(++familiarStep) % AmbientHarmony.FAMILIAR.size]
        Familiarity.NORMAL -> rng.pick(AmbientHarmony.NORMAL.filter { it != chord })
        Familiarity.NEW -> rng.pick(AmbientHarmony.NEW.filter { it != chord })
    }

    /** ドローン：途切れないよう前の音に重ねて鳴らし直す */
    private fun drone(dur: Double) {
        if (startSec + dur > droneUntilSec - A.DRONE_ATTACK_SEC) {
            val len = dur * 2 + A.DRONE_ATTACK_SEC
            out += AmbDrone(startSec, A.DRONE_MIDI, len)
            out += AmbDrone(startSec, A.DRONE_LOW_MIDI, len)
            droneUntilSec = startSec + len
        }
    }

    /** 和音をパッドで。前の和音から近い形で、ゆっくり膨らませて次の和音に重ねる。wide なら1オクターブ上の音も足す */
    private fun pads(dur: Double, shift: Int, wide: Boolean = false) {
        val lo = A.PAD_MIN_MIDI + shift
        val hi = A.PAD_MAX_MIDI + shift
        val candidates = (lo..hi).filter { (it - chord.root) % 12 == 0 }.map { base -> chord.tones.map { base + it } }
            .filter { v -> v.last() <= hi + 4 }
            .ifEmpty { listOf(chord.tones.map { lo - ((lo - chord.root) % 12 + 12) % 12 + 12 + it }) }
        val prev = lastVoicing
        val center = (lo + hi) / 2.0
        val v0 = if (prev == null) candidates.minBy { abs(it.average() - center) } else candidates.minBy { c ->
            abs(c.average() - prev.average()) + abs(c.average() - center) * 0.5
        }
        lastVoicing = v0
        val v = if (wide) v0 + v0.takeLast(A.LAND_PAD_EXTRA + 1).map { it + 12 } else v0
        val cutoff = A.padCutoffHz(scene.sun) * if (wide) 1.4 else 1.0
        v.forEachIndexed { i, midi ->
            val pan = if (v.size == 1) 0.0 else -0.5 + i.toDouble() / (v.size - 1)
            out += AmbPad(startSec + i * 0.15, midi, dur, dur * A.PAD_ATTACK_FACTOR, cutoff, pan)
        }
    }

    /** 粒：和音の音から選んで、ばらばらの時刻に（ポアソン過程） */
    private fun grains(dur: Double, shift: Int, factor: Double) {
        val rate = A.grainsPerSec(scene.speed) * factor
        val pool = (A.GRAIN_MIN_MIDI + shift..A.GRAIN_MAX_MIDI + shift).filter { n ->
            chord.tones.any { (n - chord.root - it) % 12 == 0 }
        }
        if (pool.isEmpty()) return
        var t = startSec + expo(rate)
        while (t < startSec + dur) {
            out += AmbGrain(t, rng.pick(pool), A.GRAIN_GAIN * rng.nextDouble(0.4, 1.0), rng.nextDouble(-0.7, 0.7))
            t += expo(rate)
        }
    }

    private fun expo(rate: Double) = -ln(1.0 - rng.nextDouble()) / rate

    /** ピアノ：場所のモチーフをゆっくり、間をあけて */
    private fun piano(shift: Int) {
        val motif = Motifs.placeMotif(scene.gridId).map { (it + shift).coerceIn(A.PIANO_MIN_MIDI, A.PIANO_MAX_MIDI) }
        var t = startSec + 2.0 + rng.nextDouble(0.0, 1.0)
        for (m in motif) {
            out += AmbPiano(t, m, A.PIANO_VELOCITY * rng.nextDouble(0.8, 1.1), A.PIANO_HOLD_SEC, rng.nextDouble(-0.3, 0.3))
            t += A.PIANO_NOTE_GAP_SEC * rng.nextDouble(0.8, 1.3)
        }
    }

    /** マスが変わったら場所のモチーフをベルで */
    private fun bellMotif(at: Double) {
        Motifs.placeMotif(scene.gridId).forEachIndexed { i, midi ->
            out += AmbBell(at + i * A.THEME_GAP_SEC, midi, A.MOTIF_BELL_GAIN, A.BELL_SEND)
        }
    }

    /** 地点のテーマを試聴と同じ音の並び・音域でベルが鳴らす */
    private fun bellTheme(p: Place, at: Double, gain: Double, send: Double) {
        Motifs.theme(p.themeSeed).forEachIndexed { i, midi ->
            out += AmbBell(at + i * A.THEME_GAP_SEC, midi, gain, send)
        }
    }

    /** 地点のテーマをピアノで（音の並びはそのまま。夜は低め） */
    private fun pianoTheme(p: Place, at: Double, velocity: Double, shift: Int) {
        Motifs.theme(p.themeSeed).forEachIndexed { i, midi ->
            out += AmbPiano(at + i * A.THEME_GAP_SEC, (midi + shift).coerceIn(A.PIANO_MIN_MIDI, A.PIANO_MAX_MIDI), velocity, A.PIANO_HOLD_SEC, 0.0)
        }
    }

    /** 離れる：テーマを一度、遠ざかるように（1音ごとに小さく、空間へ多めに） */
    private fun leaveTheme(p: Place, at: Double) {
        Motifs.theme(p.themeSeed).forEachIndexed { i, midi ->
            out += AmbBell(at + i * A.THEME_GAP_SEC, midi, A.LEAVE_BELL_GAIN * 10.0.pow(-A.LEAVE_FADE_DB_PER_NOTE * i / 20.0), A.BELL_FAR_SEND)
        }
    }

    private companion object {
        const val HISTORY = 32
        /** 到着の和音の数（合図・テーマ・着地） */
        const val ARRIVE_CHORDS = 3
    }
}
