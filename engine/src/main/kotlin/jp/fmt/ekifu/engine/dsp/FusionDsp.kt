package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.Adsr
import jp.fmt.ekifu.engine.FusionConstants as F
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

// ============ オシレーター ============

/** 帯域制限（PolyBLEP）の補正。折り返しノイズ（バリ音）を抑える */
internal fun polyBlep(t: Double, dt: Double): Double = when {
    t < dt -> {
        val x = t / dt
        x + x - x * x - 1.0
    }
    t > 1.0 - dt -> {
        val x = (t - 1.0) / dt
        x * x + x + x + 1.0
    }
    else -> 0.0
}

internal fun blepSaw(t: Double, dt: Double) = 2.0 * t - 1.0 - polyBlep(t, dt)

internal fun blepSquare(t: Double, dt: Double): Double {
    var v = if (t < 0.5) 1.0 else -1.0
    v += polyBlep(t, dt)
    val t2 = (t + 0.5) % 1.0
    v -= polyBlep(t2, dt)
    return v
}

internal fun triangleWave(t: Double) = 4.0 * abs(t - 0.5) - 1.0

internal fun midiToHz(midi: Double) = 440.0 * exp((midi - 69.0) * ln(2.0) / 12.0)

// ============ エンベロープ ============

/**
 * ADSR。どの段階からでも「いまの値」から次の段階へ移るので、音の頭や切り替えでプチ音が出ない。
 * 立ち上がりは直線、減衰と余韻は指数（Tone.js と同じ形）。
 */
class Envelope(private val sampleRate: Int, private val adsr: Adsr) {
    private enum class Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    private var stage = Stage.IDLE
    var level = 0.0
        private set
    private val attackStep = 1.0 / (adsr.attackSec * sampleRate).coerceAtLeast(1.0)
    private val decayMul = F.DECAY_TARGET.pow(1.0 / (adsr.decaySec * sampleRate).coerceAtLeast(1.0))
    private val releaseMul = F.DECAY_TARGET.pow(1.0 / (adsr.releaseSec * sampleRate).coerceAtLeast(1.0))

    val idle: Boolean get() = stage == Stage.IDLE

    fun gateOn() {
        stage = Stage.ATTACK
    }

    fun gateOff() {
        if (stage != Stage.IDLE) stage = Stage.RELEASE
    }

    fun next(): Double {
        when (stage) {
            Stage.IDLE -> return 0.0
            Stage.ATTACK -> {
                level += attackStep
                if (level >= 1.0) {
                    level = 1.0
                    stage = Stage.DECAY
                }
            }
            Stage.DECAY -> {
                level = adsr.sustain + (level - adsr.sustain) * decayMul
                if (abs(level - adsr.sustain) < 1e-5) {
                    level = adsr.sustain
                    stage = if (adsr.sustain <= 0.0) Stage.IDLE else Stage.SUSTAIN
                }
            }
            Stage.SUSTAIN -> Unit
            Stage.RELEASE -> {
                level *= releaseMul
                if (level < 1e-5) {
                    level = 0.0
                    stage = Stage.IDLE
                }
            }
        }
        return level
    }

    fun copy() = Envelope(sampleRate, adsr).also {
        it.stage = stage
        it.level = level
    }
}

// ============ ノイズ ============

/** 決まった列のホワイトノイズ（xorshift32）。同じシードなら同じ音 */
class Noise(seed: Int) {
    private var s = if (seed == 0) 0x9E3779B9.toInt() else seed

    fun white(): Double {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return s.toDouble() / Int.MAX_VALUE
    }

    fun copy() = Noise(1).also { it.s = s }
}

/** ピンクノイズ（Paul Kellet の簡易フィルター） */
class PinkNoise(seed: Int) {
    private var white = Noise(seed)
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0

    fun next(): Double {
        val w = white.white()
        b0 = 0.99765 * b0 + w * 0.0990460
        b1 = 0.96300 * b1 + w * 0.2965164
        b2 = 0.57000 * b2 + w * 1.0526913
        return (b0 + b1 + b2 + w * 0.1848) * PINK_SCALE
    }

    fun copy() = PinkNoise(1).also {
        it.white = white.copy()
        it.b0 = b0
        it.b1 = b1
        it.b2 = b2
    }

    private companion object {
        const val PINK_SCALE = 0.25
    }
}

// ============ 空間系 ============

/** コーラス：揺らした短い遅延を左右逆の位相で重ねる */
class Chorus(private val sampleRate: Int) {
    private val size = (F.CHORUS_DELAY_SEC * 3 * sampleRate).toInt() + 4
    private var buf = DoubleArray(size)
    private var pos = 0
    private var phase = 0.0
    private val inc = F.CHORUS_HZ / sampleRate
    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun process(x: Double) {
        buf[pos] = x
        val base = F.CHORUS_DELAY_SEC * sampleRate
        val s = sin(2 * PI * phase)
        outL = x * (1 - F.CHORUS_WET) + read(base * (1 + F.CHORUS_DEPTH * s)) * F.CHORUS_WET
        outR = x * (1 - F.CHORUS_WET) + read(base * (1 - F.CHORUS_DEPTH * s)) * F.CHORUS_WET
        phase += inc
        if (phase >= 1.0) phase -= 1.0
        pos = (pos + 1) % size
    }

    private fun read(delaySamples: Double): Double {
        val d = delaySamples.coerceIn(1.0, (size - 2).toDouble())
        val i = d.toInt()
        val frac = d - i
        val a = buf[(pos - i + size) % size]
        val b = buf[(pos - i - 1 + size) % size]
        return a + (b - a) * frac
    }

    fun copy() = Chorus(sampleRate).also {
        it.buf = buf.copyOf()
        it.pos = pos
        it.phase = phase
        it.outL = outL
        it.outR = outR
    }
}

/** リミッター：瞬時に下げ、ゆっくり戻す（最後の −1dB の天井） */
class Limiter(private val sampleRate: Int) {
    private val ceiling = F.db(F.LIMITER_CEILING_DB)
    private val release = 1.0 - exp(-1.0 / (F.LIMITER_RELEASE_SEC * sampleRate))
    private var gain = 1.0

    fun gainFor(l: Double, r: Double): Double {
        val peak = maxOf(abs(l), abs(r))
        val target = if (peak > ceiling) ceiling / peak else 1.0
        gain = if (target < gain) target else gain + (target - gain) * release
        return gain
    }

    fun copy() = Limiter(sampleRate).also { it.gain = gain }
}

// ============ 楽器 ============

/** 音ごとの発音体（シンセブラス・ベース・ドラム） */
abstract class FusionVoice {
    var finished = false
        protected set

    abstract fun next(): Double
    abstract fun copy(): FusionVoice
}

/** シンセブラス：ノコギリ波2本（±5セント）。durFrames のあと余韻に入る */
class BrassVoice(
    private val sampleRate: Int,
    private val midi: Int,
    private val velocity: Double,
    private var framesLeft: Long,
) : FusionVoice() {
    private val detune = 2.0.pow(F.BRASS_DETUNE_CENTS / 1200.0)
    private val dt1 = midiToHz(midi.toDouble()) * detune / sampleRate
    private val dt2 = midiToHz(midi.toDouble()) / detune / sampleRate
    private var p1 = 0.0
    private var p2 = 0.5
    private var env = Envelope(sampleRate, F.BRASS_ENV).also { it.gateOn() }

    override fun next(): Double {
        if (framesLeft-- == 0L) env.gateOff()
        val e = env.next()
        if (env.idle) {
            finished = true
            return 0.0
        }
        val s = (blepSaw(p1, dt1) + blepSaw(p2, dt2)) * 0.5
        p1 += dt1; if (p1 >= 1.0) p1 -= 1.0
        p2 += dt2; if (p2 >= 1.0) p2 -= 1.0
        return s * e * velocity
    }

    override fun copy() = BrassVoice(sampleRate, midi, velocity, framesLeft).also {
        it.p1 = p1
        it.p2 = p2
        it.env = env.copy()
        it.finished = finished
    }
}

/** ベース：三角波。音ごとに別の発音体（音程を切り替えないのでプチ音が出ない） */
class BassVoice2(
    private val sampleRate: Int,
    private val midi: Int,
    private val velocity: Double,
    private var framesLeft: Long,
) : FusionVoice() {
    private val dt = midiToHz(midi.toDouble()) / sampleRate
    private var p = 0.25
    private var env = Envelope(sampleRate, F.BASS_ENV).also { it.gateOn() }

    override fun next(): Double {
        if (framesLeft-- == 0L) env.gateOff()
        val e = env.next()
        if (env.idle) {
            finished = true
            return 0.0
        }
        val s = triangleWave(p)
        p += dt; if (p >= 1.0) p -= 1.0
        return s * e * velocity
    }

    override fun copy() = BassVoice2(sampleRate, midi, velocity, framesLeft).also {
        it.p = p
        it.env = env.copy()
        it.finished = finished
    }
}

/** キック・タム：サイン波の音程を高い所から下げる */
class MembraneVoice(
    private val sampleRate: Int,
    private val hz: Double,
    private val octaves: Double,
    private val pitchDecaySec: Double,
    private val decaySec: Double,
    private val velocity: Double,
) : FusionVoice() {
    private var t = 0
    private var p = 0.0
    private var env = Envelope(sampleRate, Adsr(F.DRUM_ATTACK_SEC, decaySec, 0.0, decaySec)).also { it.gateOn() }
    private val pitchFrames = pitchDecaySec * sampleRate

    override fun next(): Double {
        val e = env.next()
        if (env.idle) {
            finished = true
            return 0.0
        }
        val k = (1.0 - t / pitchFrames).coerceAtLeast(0.0)
        val f = hz * 2.0.pow(octaves * k)
        p += f / sampleRate
        if (p >= 1.0) p -= 1.0
        t++
        return sin(2 * PI * p) * e * velocity
    }

    override fun copy() = MembraneVoice(sampleRate, hz, octaves, pitchDecaySec, decaySec, velocity).also {
        it.t = t
        it.p = p
        it.env = env.copy()
        it.finished = finished
    }
}

/** スネア・ハイハット・クラッシュ：ノイズをフィルターに通す */
class NoiseVoice(
    private val sampleRate: Int,
    private val pink: Boolean,
    private val filterType: FilterType,
    private val filterHz: Double,
    private val decaySec: Double,
    private val velocity: Double,
    seed: Int,
) : FusionVoice() {
    private var white = Noise(seed)
    private var pinkNoise = PinkNoise(seed)
    private var filter = Biquad(sampleRate, filterHz, if (filterType == FilterType.BANDPASS) 1.0 else 0.7071, filterType)
    private var env = Envelope(sampleRate, Adsr(F.DRUM_ATTACK_SEC, decaySec, 0.0, decaySec)).also { it.gateOn() }

    override fun next(): Double {
        val e = env.next()
        if (env.idle) {
            finished = true
            return 0.0
        }
        val n = if (pink) pinkNoise.next() else white.white()
        return filter.process(n) * e * velocity
    }

    override fun copy() = NoiseVoice(sampleRate, pink, filterType, filterHz, decaySec, velocity, 1).also {
        it.white = white.copy()
        it.pinkNoise = pinkNoise.copy()
        it.filter = filter.copy()
        it.env = env.copy()
        it.finished = finished
    }
}

/**
 * リード（EWI風）。1つの発音体で音程を滑らせる（モノフォニック）。
 * ノコギリ波2本（±4セント）＋1オクターブ下の矩形波、それぞれフィルターのエンベロープつき。
 * 状態（位相・音程・エンベロープ・フィルター）はチャンクをまたいで引き継ぐ。
 */
class LeadVoice(private val sampleRate: Int) {
    private val detune = 2.0.pow(F.LEAD_DETUNE_CENTS / 1200.0)
    private var p1 = 0.0
    private var p2 = 0.33
    private var ps = 0.0
    private var pitch = 72.0
    private var glideStep = 0.0
    private var glideLeft = 0
    private var vibPhase = 0.0
    private var vibDepth = 0.0
    private var vibTarget = 0.0
    private var vibStep = 0.0
    private var velocity = 0.0
    var filterBaseHz = F.LEAD_FILTER_BASE_HZ
    private var amp = Envelope(sampleRate, F.LEAD_AMP_ENV)
    private var filt = Envelope(sampleRate, F.LEAD_FILTER_ENV)
    private var subAmp = Envelope(sampleRate, F.SUB_AMP_ENV)
    private var subFilt = Envelope(sampleRate, F.SUB_FILTER_ENV)
    private var lp = Biquad(sampleRate, F.LEAD_FILTER_BASE_HZ, F.LEAD_FILTER_Q)
    private var subLp = Biquad(sampleRate, F.SUB_FILTER_BASE_HZ, F.SUB_FILTER_Q)
    private var counter = 0

    val sounding: Boolean get() = !amp.idle || !subAmp.idle

    /**
     * 発音。鳴っている最中なら glideSec かけて音程を滑らせ、エンベロープはいまの値から立ち上げ直す
     * （鳴っていなければ音程はその場で合わせる）
     */
    fun noteOn(midi: Double, glideSec: Double, velocity: Double, vibratoCents: Double, vibratoRampSec: Double) {
        if (amp.level > SILENT_LEVEL) moveTo(midi, glideSec) else {
            pitch = midi
            glideLeft = 0
        }
        this.velocity = velocity
        vibDepth = 0.0
        vibTarget = vibratoCents
        vibStep = if (vibratoCents > 0) vibratoCents / (vibratoRampSec * sampleRate).coerceAtLeast(1.0) else 0.0
        amp.gateOn()
        filt.gateOn()
        subAmp.gateOn()
        subFilt.gateOn()
    }

    /** 音程を sec かけて midi へ（しゃくり・フォール・グライド） */
    fun moveTo(midi: Double, sec: Double) {
        val frames = (sec * sampleRate).toInt()
        if (frames <= 0) {
            pitch = midi
            glideLeft = 0
        } else {
            glideStep = (midi - pitch) / frames
            glideLeft = frames
        }
    }

    fun noteOff() {
        amp.gateOff()
        filt.gateOff()
        subAmp.gateOff()
        subFilt.gateOff()
        vibTarget = 0.0
        vibDepth = 0.0
    }

    fun next(): Double {
        if (!sounding) return 0.0
        if (glideLeft > 0) {
            pitch += glideStep
            glideLeft--
        }
        if (vibDepth < vibTarget) vibDepth = (vibDepth + vibStep).coerceAtMost(vibTarget)
        vibPhase += F.VIBRATO_HZ / sampleRate
        if (vibPhase >= 1.0) vibPhase -= 1.0
        val f = midiToHz(pitch + vibDepth * sin(2 * PI * vibPhase) / 100.0)
        val dt1 = f * detune / sampleRate
        val dt2 = f / detune / sampleRate
        val dts = f * 0.5 / sampleRate

        val a = amp.next()
        val fe = filt.next()
        val sa = subAmp.next()
        val sf = subFilt.next()
        if (counter++ and 15 == 0) {
            lp.setCutoff(filterBaseHz * 2.0.pow(F.LEAD_FILTER_OCTAVES * fe))
            subLp.setCutoff(F.SUB_FILTER_BASE_HZ * 2.0.pow(F.SUB_FILTER_OCTAVES * sf))
        }
        val saw = (blepSaw(p1, dt1) + blepSaw(p2, dt2)) * 0.5
        val sq = blepSquare(ps, dts)
        p1 += dt1; if (p1 >= 1.0) p1 -= 1.0
        p2 += dt2; if (p2 >= 1.0) p2 -= 1.0
        ps += dts; if (ps >= 1.0) ps -= 1.0
        return (lp.process(saw) * a * F.LEAD_GAIN + subLp.process(sq) * sa * F.SUB_GAIN) * velocity
    }

    fun copy() = LeadVoice(sampleRate).also {
        it.p1 = p1
        it.p2 = p2
        it.ps = ps
        it.pitch = pitch
        it.glideStep = glideStep
        it.glideLeft = glideLeft
        it.vibPhase = vibPhase
        it.vibDepth = vibDepth
        it.vibTarget = vibTarget
        it.vibStep = vibStep
        it.velocity = velocity
        it.filterBaseHz = filterBaseHz
        it.amp = amp.copy()
        it.filt = filt.copy()
        it.subAmp = subAmp.copy()
        it.subFilt = subFilt.copy()
        it.lp = lp.copy()
        it.subLp = subLp.copy()
        it.counter = counter
    }

    private companion object {
        /** Tone.js と同じく、まだ音が残っていれば（0.05 超）音程を滑らせる */
        const val SILENT_LEVEL = 0.05
    }
}

/** 等パワーの定位 */
internal fun panLeft(pan: Double) = cos((pan + 1) * PI / 4)
internal fun panRight(pan: Double) = sin((pan + 1) * PI / 4)
