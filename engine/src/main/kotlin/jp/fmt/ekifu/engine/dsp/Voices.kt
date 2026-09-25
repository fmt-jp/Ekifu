package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.Instrument
import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** 鳴っている1音。next() はモノラルの1サンプルを返す（音量・定位は Synth 側）。 */
abstract class Voice(
    val instrument: Instrument,
    val gain: Double,
    val pan: Double,
) {
    // 等パワー定位
    val gainL: Double = gain * cos((pan + 1) * PI / 4)
    val gainR: Double = gain * sin((pan + 1) * PI / 4)

    var finished = false
        protected set

    abstract fun next(): Double

    /** 鳴り続けている音を余韻に入らせる */
    open fun release() {}

    /** 内部状態（位相・エンベロープ・フィルター）ごと複製する */
    abstract fun copy(): Voice
}

private const val TWO_PI = 2 * PI

/** 1サンプルあたりの倍率。seconds で DECAY_FLOOR（-60dB）まで下がる。 */
internal fun decayPerSample(seconds: Double, sampleRate: Int): Double =
    exp(ln(C.DECAY_FLOOR) / (seconds * sampleRate))

/** 立ち上がり（直線）→ 保持 → 余韻（指数）のエンベロープ */
private class SustainEnvelope(
    private val sampleRate: Int,
    private val attackSec: Double,
    private val releaseSec: Double,
) {
    private val attackStep = 1.0 / (attackSec * sampleRate)
    private val releaseMul = decayPerSample(releaseSec, sampleRate)
    var level = 0.0
        private set
    var releasing = false
    val done get() = releasing && level < C.DECAY_FLOOR

    fun next(): Double {
        if (releasing) {
            level *= releaseMul
        } else if (level < 1.0) {
            level = (level + attackStep).coerceAtMost(1.0)
        }
        return level
    }

    fun copy() = SustainEnvelope(sampleRate, attackSec, releaseSec).also {
        it.level = level
        it.releasing = releasing
    }
}

/** 立ち上がり（直線）→ 指数減衰のエンベロープ */
private class DecayEnvelope(
    private val sampleRate: Int,
    private val attackSec: Double,
    private val decaySec: Double,
) {
    private val attackStep = 1.0 / (attackSec * sampleRate)
    private val decayMul = decayPerSample(decaySec, sampleRate)
    private var attacking = true
    var level = 0.0
        private set
    val done get() = !attacking && level < C.DECAY_FLOOR

    fun next(): Double {
        if (attacking) {
            level += attackStep
            if (level >= 1.0) {
                level = 1.0
                attacking = false
            }
        } else {
            level *= decayMul
        }
        return level
    }

    fun copy() = DecayEnvelope(sampleRate, attackSec, decaySec).also {
        it.level = level
        it.attacking = attacking
    }
}

private fun triangle(phase: Double): Double = 4.0 * abs(phase - 0.5) - 1.0

/** パッド：三角波2本（±6セント）→ ローパス1400Hz、立ち上がり2.5秒、余韻3.5秒 */
class PadVoice(
    private val sampleRate: Int,
    private val freq: Double,
    gain: Double,
    pan: Double,
    private val attackSec: Double = C.PAD_ATTACK_SEC,
) : Voice(Instrument.PAD, gain, pan) {
    private val detune = 2.0.pow(C.PAD_DETUNE_CENTS / 1200.0)
    private val inc1 = freq * detune / sampleRate
    private val inc2 = freq / detune / sampleRate
    private var p1 = 0.0
    private var p2 = 0.37 // 位相をずらしてうなりを自然に
    private var filter = Biquad(sampleRate, C.PAD_LOWPASS_HZ)
    private var env = SustainEnvelope(sampleRate, attackSec, C.PAD_RELEASE_SEC)

    override fun next(): Double {
        p1 += inc1; if (p1 >= 1.0) p1 -= 1.0
        p2 += inc2; if (p2 >= 1.0) p2 -= 1.0
        val s = filter.process((triangle(p1) + triangle(p2)) * 0.5) * env.next()
        if (env.done) finished = true
        return s
    }

    override fun release() {
        env.releasing = true
    }

    override fun copy() = PadVoice(sampleRate, freq, gain, pan, attackSec).also {
        it.p1 = p1
        it.p2 = p2
        it.filter = filter.copy()
        it.env = env.copy()
        it.finished = finished
    }
}

/** ベース：サイン波、立ち上がり3秒 */
class BassVoice(
    private val sampleRate: Int,
    private val freq: Double,
    gain: Double,
) : Voice(Instrument.BASS, gain, 0.0) {
    private val inc = freq / sampleRate
    private var p = 0.0
    private var env = SustainEnvelope(sampleRate, C.BASS_ATTACK_SEC, C.BASS_RELEASE_SEC)

    override fun next(): Double {
        p += inc; if (p >= 1.0) p -= 1.0
        val s = sin(TWO_PI * p) * env.next()
        if (env.done) finished = true
        return s
    }

    override fun release() {
        env.releasing = true
    }

    override fun copy() = BassVoice(sampleRate, freq, gain).also {
        it.p = p
        it.env = env.copy()
        it.finished = finished
    }
}

/** プラック：三角波、2.2秒で指数減衰 */
class PluckVoice(
    private val sampleRate: Int,
    private val freq: Double,
    gain: Double,
    pan: Double,
) : Voice(Instrument.PLUCK, gain, pan) {
    private val inc = freq / sampleRate
    private var p = 0.0
    private var env = DecayEnvelope(sampleRate, C.PLUCK_ATTACK_SEC, C.PLUCK_DECAY_SEC)

    override fun next(): Double {
        p += inc; if (p >= 1.0) p -= 1.0
        val s = triangle(p) * env.next()
        if (env.done) finished = true
        return s
    }

    override fun copy() = PluckVoice(sampleRate, freq, gain, pan).also {
        it.p = p
        it.env = env.copy()
        it.finished = finished
    }
}

/** ベル：サイン波の倍音（×1, ×2.76, ×5.4）、減衰4秒 */
class BellVoice(
    private val sampleRate: Int,
    private val freq: Double,
    gain: Double,
    pan: Double,
) : Voice(Instrument.BELL, gain, pan) {
    private val n = C.BELL_PARTIAL_RATIOS.size
    private val incs = DoubleArray(n) { freq * C.BELL_PARTIAL_RATIOS[it] / sampleRate }
    private val amps = DoubleArray(n) {
        // ナイキスト周波数を超える倍音は鳴らさない
        if (freq * C.BELL_PARTIAL_RATIOS[it] < sampleRate * 0.45) C.BELL_PARTIAL_GAINS[it] else 0.0
    }
    private val phases = DoubleArray(n)
    private val norm = 1.0 / C.BELL_PARTIAL_GAINS.sum()
    private var envs = Array(n) {
        DecayEnvelope(sampleRate, C.BELL_ATTACK_SEC, C.BELL_DECAY_SEC * C.BELL_PARTIAL_DECAY_FACTORS[it])
    }

    override fun next(): Double {
        var s = 0.0
        for (i in 0 until n) {
            phases[i] += incs[i]
            if (phases[i] >= 1.0) phases[i] -= 1.0
            s += sin(TWO_PI * phases[i]) * amps[i] * envs[i].next()
        }
        if (envs[0].done) finished = true
        return s * norm
    }

    override fun copy() = BellVoice(sampleRate, freq, gain, pan).also {
        phases.copyInto(it.phases)
        it.envs = Array(n) { i -> envs[i].copy() }
        it.finished = finished
    }
}
