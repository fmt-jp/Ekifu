package jp.fmt.ekifu.engine

import kotlin.math.abs
import kotlin.math.ln
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
}

/**
 * アンビエントの作曲（段階8a：音色の試聴。和音・ドローン・粒・ピアノ）。拍はなく、和音ごとに作る。
 * - 和音の長さと粒の多さは速さ（静止はほぼ止まった音、乗り物は細かい粒が流れる）
 * - 音域と明るさは太陽（昼は高く明るく、夜は低く暗く）
 * - 和音のさまよい方はなじみ度
 * - ドローン（主音 F）がずっと下で鳴り、ピアノは2和音ごとに場所のモチーフをゆっくり弾く
 * previewScenes があれば（段階8aの試聴）、場面を [A.PREVIEW_CHORDS_PER_SCENE] 和音ごとに順に切り替える。
 * 同じシード・同じ入力なら同じ結果。
 */
class AmbientComposer(seed: Int, private val previewScenes: List<Scene>? = null) : ComposerInput {

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

    private val history = ArrayDeque<ChordInfo>()

    data class ChordInfo(val startSec: Double, val durSec: Double, val chord: AmbChord, val scene: Scene, val brightnessHz: Double)

    override fun setScene(newScene: Scene) {
        pendingScene = newScene
    }

    // 段階8aでは登録地点の演出はまだ作らない（8bで作る）
    override fun approach(target: Place) {}
    override fun arrive(target: Place) {}
    override fun leave() {}
    override fun stay(target: Place) {}
    override fun approachAfterStart(target: Place) {}

    fun copy(): AmbientComposer = AmbientComposer(0, previewScenes).also {
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
        it.history.addAll(history)
    }

    fun composeUntil(tSec: Double): List<AmbientEvent> {
        out.clear()
        while (startSec <= tSec) composeChord()
        return out.toList()
    }

    fun chordAt(tSec: Double): ChordInfo? = history.lastOrNull { it.startSec <= tSec + 1e-9 }

    private fun composeChord() {
        val preview = previewScenes
        val s = if (preview != null) preview[(index / A.PREVIEW_CHORDS_PER_SCENE) % preview.size] else pendingScene
        pendingScene = null
        if (s != null) scene = s
        val dur = A.chordSec(scene.speed)
        chord = if (index == 0) AmbChord.FADD9 else nextChord()
        val shift = A.registerShift(scene.sun)

        val brightness = A.brightnessHz(scene.sun)
        if (brightness != lastBrightness) {
            out += AmbControl(startSec, brightness)
            lastBrightness = brightness
        }
        // ドローン：途切れないよう前の音に重ねて鳴らし直す
        if (startSec + dur > droneUntilSec - A.DRONE_ATTACK_SEC) {
            val len = dur * 2 + A.DRONE_ATTACK_SEC
            out += AmbDrone(startSec, A.DRONE_MIDI, len)
            out += AmbDrone(startSec, A.DRONE_LOW_MIDI, len)
            droneUntilSec = startSec + len
        }
        pads(dur, shift)
        grains(dur, shift)
        if (index % A.PIANO_EVERY_CHORDS == 1) piano(shift)

        history.addLast(ChordInfo(startSec, dur, chord, scene, brightness))
        while (history.size > HISTORY) history.removeFirst()
        startSec += dur
        index++
    }

    private fun nextChord(): AmbChord = when (scene.familiarity) {
        Familiarity.FAMILIAR -> AmbientHarmony.FAMILIAR[(++familiarStep) % AmbientHarmony.FAMILIAR.size]
        Familiarity.NORMAL -> rng.pick(AmbientHarmony.NORMAL.filter { it != chord })
        Familiarity.NEW -> rng.pick(AmbientHarmony.NEW.filter { it != chord })
    }

    /** 和音をパッドで。前の和音から近い形で、ゆっくり膨らませて次の和音に重ねる */
    private fun pads(dur: Double, shift: Int) {
        val lo = A.PAD_MIN_MIDI + shift
        val hi = A.PAD_MAX_MIDI + shift
        val candidates = (lo..hi).filter { (it - chord.root) % 12 == 0 }.map { base -> chord.tones.map { base + it } }
            .filter { v -> v.last() <= hi + 4 }
            .ifEmpty { listOf(chord.tones.map { lo - ((lo - chord.root) % 12 + 12) % 12 + 12 + it }) }
        val prev = lastVoicing
        val center = (lo + hi) / 2.0
        val v = if (prev == null) candidates.minBy { abs(it.average() - center) } else candidates.minBy { c ->
            abs(c.average() - prev.average()) + abs(c.average() - center) * 0.5
        }
        lastVoicing = v
        val cutoff = A.padCutoffHz(scene.sun)
        v.forEachIndexed { i, midi ->
            val pan = if (v.size == 1) 0.0 else -0.5 + i.toDouble() / (v.size - 1)
            out += AmbPad(startSec + i * 0.15, midi, dur, dur * A.PAD_ATTACK_FACTOR, cutoff, pan)
        }
    }

    /** 粒：和音の音から選んで、ばらばらの時刻に（ポアソン過程） */
    private fun grains(dur: Double, shift: Int) {
        val rate = A.grainsPerSec(scene.speed)
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

    private companion object {
        const val HISTORY = 32
    }
}
