package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** 各ボイスが書き込むミックスバス（1 制御ブロックぶん）。 */
class MixBus(size: Int) {
    val left = DoubleArray(size)
    val right = DoubleArray(size)
    val delaySend = DoubleArray(size)
    val reverbSend = DoubleArray(size)

    fun clear(frames: Int) {
        left.fill(0.0, 0, frames)
        right.fill(0.0, 0, frames)
        delaySend.fill(0.0, 0, frames)
        reverbSend.fill(0.0, 0, frames)
    }
}

fun midiToHz(midi: Double): Double = 440.0 * 2.0.pow((midi - 69) / 12)

/** 指定秒数で [C.DECAY_FLOOR] まで下がる、サンプルごとの減衰係数。 */
fun decayCoefficient(seconds: Double, sampleRate: Int): Double =
    exp(ln(C.DECAY_FLOOR) / (seconds * sampleRate))

abstract class Voice(pan: Double, private val gain: Double, private val delaySend: Double, private val reverbSend: Double) {
    // 等パワーの定位
    private val gainLeft = cos((pan.coerceIn(-1.0, 1.0) + 1) * PI / 4) * gain
    private val gainRight = sin((pan.coerceIn(-1.0, 1.0) + 1) * PI / 4) * gain

    var finished = false
        protected set

    /** 1 サンプル進めて、エンベロープ込みのモノラル値を返す。 */
    protected abstract fun next(): Double

    fun render(bus: MixBus, frames: Int) {
        for (i in 0 until frames) {
            if (finished) return
            val v = next()
            bus.left[i] += v * gainLeft
            bus.right[i] += v * gainRight
            bus.delaySend[i] += v * gain * delaySend
            bus.reverbSend[i] += v * gain * reverbSend
        }
    }
}

/** 立ち上がりは直線、ノートオフ後は指数で消えるエンベロープ。 */
class AttackReleaseEnvelope(sampleRate: Int, attackSeconds: Double, releaseSeconds: Double, private val holdSamples: Long) {
    private val attackStep = 1.0 / (attackSeconds * sampleRate)
    private val releaseCoef = decayCoefficient(releaseSeconds, sampleRate)
    private var position = 0L
    private var level = 0.0

    val done: Boolean get() = position > holdSamples && level < C.VOICE_SILENCE

    fun next(): Double {
        if (position < holdSamples) {
            if (level < 1.0) level = (level + attackStep).coerceAtMost(1.0)
        } else {
            level *= releaseCoef
        }
        position++
        return level
    }
}

private fun triangle(phase: Double): Double = 4 * kotlin.math.abs(phase - 0.5) - 1

/** パッド：三角波 2 本（±デチューン）→ ローパス。 */
class PadVoice(sampleRate: Int, midi: Int, velocity: Double, pan: Double, holdSamples: Long) :
    Voice(pan, C.PAD_GAIN * velocity, C.PAD_DELAY_SEND, C.PAD_REVERB_SEND) {
    private val baseHz = midiToHz(midi.toDouble())
    private val detune = 2.0.pow(C.PAD_DETUNE_CENTS / 1200)
    private val inc1 = baseHz * detune / sampleRate
    private val inc2 = baseHz / detune / sampleRate
    private var phase1 = 0.0
    private var phase2 = 0.37 // 位相をずらしてうなりを自然に
    private val filter = BiquadLowpass(sampleRate).apply { set(C.PAD_LOWPASS_HZ, C.PAD_LOWPASS_Q) }
    private val envelope = AttackReleaseEnvelope(sampleRate, C.PAD_ATTACK_SECONDS, C.PAD_RELEASE_SECONDS, holdSamples)

    override fun next(): Double {
        val raw = 0.5 * (triangle(phase1) + triangle(phase2))
        phase1 += inc1; if (phase1 >= 1) phase1 -= 1
        phase2 += inc2; if (phase2 >= 1) phase2 -= 1
        val v = filter.process(raw) * envelope.next()
        if (envelope.done) finished = true
        return v
    }
}

/** ベース：サイン波、ゆっくり立ち上がる。 */
class BassVoice(sampleRate: Int, midi: Int, velocity: Double, holdSamples: Long) :
    Voice(0.0, C.BASS_GAIN * velocity, C.BASS_DELAY_SEND, C.BASS_REVERB_SEND) {
    private val inc = midiToHz(midi.toDouble()) / sampleRate
    private var phase = 0.0
    private val envelope = AttackReleaseEnvelope(sampleRate, C.BASS_ATTACK_SECONDS, C.BASS_RELEASE_SECONDS, holdSamples)

    override fun next(): Double {
        val v = sin(2 * PI * phase) * envelope.next()
        phase += inc; if (phase >= 1) phase -= 1
        if (envelope.done) finished = true
        return v
    }
}

/** プラック：三角波、指数減衰。 */
class PluckVoice(sampleRate: Int, midi: Int, velocity: Double, pan: Double) :
    Voice(pan, C.PLUCK_GAIN * velocity, C.PLUCK_DELAY_SEND, C.PLUCK_REVERB_SEND) {
    private val inc = midiToHz(midi.toDouble()) / sampleRate
    private var phase = 0.0
    private val attackSamples = (C.PLUCK_ATTACK_SECONDS * sampleRate).toInt().coerceAtLeast(1)
    private val decay = decayCoefficient(C.PLUCK_DECAY_SECONDS, sampleRate)
    private var level = 1.0
    private var position = 0

    override fun next(): Double {
        val attack = if (position < attackSamples) position.toDouble() / attackSamples else 1.0
        val v = triangle(phase) * level * attack
        phase += inc; if (phase >= 1) phase -= 1
        if (position >= attackSamples) level *= decay
        position++
        if (level < C.VOICE_SILENCE) finished = true
        return v
    }
}

/** ベル：非整数倍音のサイン波を重ねる。 */
class BellVoice(sampleRate: Int, midi: Int, velocity: Double, pan: Double) :
    Voice(pan, C.BELL_GAIN * velocity, C.BELL_DELAY_SEND, C.BELL_REVERB_SEND) {
    private val partials = C.BELL_PARTIAL_RATIOS.size
    private val incs = DoubleArray(partials) { midiToHz(midi.toDouble()) * C.BELL_PARTIAL_RATIOS[it] / sampleRate }
    private val phases = DoubleArray(partials)
    private val levels = DoubleArray(partials) { C.BELL_PARTIAL_AMPS[it] }
    private val decays = DoubleArray(partials) { decayCoefficient(C.BELL_PARTIAL_DECAY_SECONDS[it], sampleRate) }
    private val attackSamples = (C.BELL_ATTACK_SECONDS * sampleRate).toInt().coerceAtLeast(1)
    private var position = 0

    override fun next(): Double {
        val attack = if (position < attackSamples) position.toDouble() / attackSamples else 1.0
        var v = 0.0
        for (p in 0 until partials) {
            v += sin(2 * PI * phases[p]) * levels[p]
            phases[p] += incs[p]; if (phases[p] >= 1) phases[p] -= 1
            if (position >= attackSamples) levels[p] *= decays[p]
        }
        position++
        if (levels[0] < C.VOICE_SILENCE) finished = true
        return v * attack
    }
}
