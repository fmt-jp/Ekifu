package jp.fmt.ekifu.engine.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import jp.fmt.ekifu.engine.AmbientConstants as A

private const val TWO_PI = 2 * PI
private const val FLOOR = 1e-4

private fun tri(p: Double) = 4.0 * abs(p - 0.5) - 1.0

/** 立ち上がり（直線）→ 保持（durFrames まで）→ 指数の余韻 */
private class Swell(sampleRate: Int, attackSec: Double, releaseSec: Double, private var holdFrames: Long) {
    private val up = 1.0 / (attackSec * sampleRate).coerceAtLeast(1.0)
    private val down = exp(ln(FLOOR) / (releaseSec * sampleRate))
    var level = 0.0
    private var releasing = false
    val done get() = releasing && level < FLOOR

    fun next(): Double {
        if (!releasing && holdFrames-- <= 0) releasing = true
        if (releasing) level *= down else if (level < 1.0) level = (level + up).coerceAtMost(1.0)
        return level
    }

    fun copyInto(o: Swell) {
        o.level = level
        o.releasing = releasing
        o.holdFrames = holdFrames
    }

    fun clone(sampleRate: Int, attackSec: Double, releaseSec: Double) = Swell(sampleRate, attackSec, releaseSec, holdFrames).also { copyInto(it) }
}

/**
 * アンビエントのパッド：三角波3本（少しずつずらす）＋サイン波 → ローパス（ゆっくり開いたり閉じたり）。
 * 立ち上がりと余韻が長い。durSec のあと余韻に入る
 */
class AmbientPadVoice(
    private val sampleRate: Int,
    private val freq: Double,
    private val gain: Double,
    private val attackSec: Double,
    private val durSec: Double,
    private val cutoffHz: Double,
    /** 揺れの位相（音ごとにずらす） */
    private val lfoPhase0: Double,
) : FusionVoice() {
    private val d = 2.0.pow(A.PAD_DETUNE_CENTS / 1200.0)
    private val incs = doubleArrayOf(freq / sampleRate, freq * d / sampleRate, freq / d / sampleRate)
    private var ph = doubleArrayOf(0.0, 0.31, 0.67)
    private var sine = 0.0
    private var lfo = lfoPhase0
    private var filter = Biquad(sampleRate, cutoffHz)
    private var env = Swell(sampleRate, attackSec, A.PAD_RELEASE_SEC, (durSec * sampleRate).toLong())
    private var counter = 0

    override fun next(): Double {
        var s = 0.0
        for (i in 0..2) {
            ph[i] += incs[i]
            if (ph[i] >= 1.0) ph[i] -= 1.0
            s += tri(ph[i])
        }
        sine += incs[0]
        if (sine >= 1.0) sine -= 1.0
        s = s / 3 * 0.7 + sin(TWO_PI * sine) * 0.3
        lfo += A.PAD_FILTER_LFO_HZ / sampleRate
        if (lfo >= 1.0) lfo -= 1.0
        if (counter++ % 64 == 0) filter.setCutoff(cutoffHz * (1.0 + A.PAD_FILTER_LFO_DEPTH * sin(TWO_PI * lfo)))
        val out = filter.process(s) * env.next() * gain
        if (env.done) finished = true
        return out
    }

    override fun copy() = AmbientPadVoice(sampleRate, freq, gain, attackSec, durSec, cutoffHz, lfoPhase0).also {
        it.ph = ph.copyOf()
        it.sine = sine
        it.lfo = lfo
        it.filter = filter.copy()
        it.env = env.clone(sampleRate, attackSec, A.PAD_RELEASE_SEC)
        it.counter = counter
        it.finished = finished
    }
}

/** ドローン：サイン波＋1オクターブ上を少し、低いローパス。ゆっくり膨らんでゆっくり消える */
class DroneVoice(
    private val sampleRate: Int,
    private val freq: Double,
    private val gain: Double,
    private val durSec: Double,
) : FusionVoice() {
    private var p = 0.0
    private var p2 = 0.25
    private var filter = Biquad(sampleRate, A.DRONE_CUTOFF_HZ)
    private var env = Swell(sampleRate, A.DRONE_ATTACK_SEC, A.DRONE_RELEASE_SEC, (durSec * sampleRate).toLong())

    override fun next(): Double {
        p += freq / sampleRate
        if (p >= 1.0) p -= 1.0
        p2 += freq * 2.003 / sampleRate
        if (p2 >= 1.0) p2 -= 1.0
        val s = filter.process(sin(TWO_PI * p) * 0.8 + tri(p2) * 0.2) * env.next() * gain
        if (env.done) finished = true
        return s
    }

    override fun copy() = DroneVoice(sampleRate, freq, gain, durSec).also {
        it.p = p
        it.p2 = p2
        it.filter = filter.copy()
        it.env = env.clone(sampleRate, A.DRONE_ATTACK_SEC, A.DRONE_RELEASE_SEC)
        it.finished = finished
    }
}

/** 音の粒：やわらかいサイン波（＋少しの倍音）の短い音 */
class GrainVoice(
    private val sampleRate: Int,
    private val freq: Double,
    private val gain: Double,
) : FusionVoice() {
    private var p = 0.0
    private var p3 = 0.0
    private var level = 0.0
    private var attacking = true
    private val up = 1.0 / (A.GRAIN_ATTACK_SEC * sampleRate)
    private val down = exp(ln(FLOOR) / (A.GRAIN_DECAY_SEC * sampleRate))

    override fun next(): Double {
        p += freq / sampleRate
        if (p >= 1.0) p -= 1.0
        p3 += freq * 3.0 / sampleRate
        if (p3 >= 1.0) p3 -= 1.0
        if (attacking) {
            level += up
            if (level >= 1.0) {
                level = 1.0
                attacking = false
            }
        } else {
            level *= down
            if (level < FLOOR) finished = true
        }
        return (sin(TWO_PI * p) + 0.12 * sin(TWO_PI * p3)) * level * gain
    }

    override fun copy() = GrainVoice(sampleRate, freq, gain).also {
        it.p = p
        it.p3 = p3
        it.level = level
        it.attacking = attacking
        it.finished = finished
    }
}
