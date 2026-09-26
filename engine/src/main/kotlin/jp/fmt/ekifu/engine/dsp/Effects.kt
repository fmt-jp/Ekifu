package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ステレオのディレイ（既定は癒し：0.675秒、フィードバック0.32、フィードバック経路にローパス2200Hz）。
 * フュージョンではテンポに合わせて長さを変えるので、最大長のバッファを取って [delaySec] で使う長さを決める。
 */
class StereoDelay(
    private val sampleRate: Int,
    private val maxDelaySec: Double = C.DELAY_SEC,
    private val lowpassHz: Double = C.DELAY_LOWPASS_HZ,
    initialFeedback: Double = C.DELAY_FEEDBACK,
) {
    private val size = (maxDelaySec * sampleRate).roundToInt()
    private val bufL = DoubleArray(size)
    private val bufR = DoubleArray(size)
    private var lpL = OnePole(sampleRate, lowpassHz)
    private var lpR = OnePole(sampleRate, lowpassHz)
    private var pos = 0
    /** 使う長さ（サンプル） */
    private var length = size
    var feedback = initialFeedback

    /** ディレイの長さを変える（最大長まで） */
    fun setDelaySec(sec: Double) {
        length = (sec * sampleRate).roundToInt().coerceIn(1, size)
        if (pos >= length) pos = 0
    }
    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun copy(): StereoDelay = StereoDelay(sampleRate, maxDelaySec, lowpassHz, feedback).also {
        it.length = length
        bufL.copyInto(it.bufL)
        bufR.copyInto(it.bufR)
        it.lpL = lpL.copy()
        it.lpR = lpR.copy()
        it.pos = pos
        it.feedback = feedback
        it.outL = outL
        it.outR = outR
    }

    fun process(inL: Double, inR: Double) {
        val dl = lpL.process(bufL[pos])
        val dr = lpR.process(bufR[pos])
        bufL[pos] = inL + dl * feedback
        bufR[pos] = inR + dr * feedback
        outL = dl
        outR = dr
        pos++
        if (pos >= length) pos = 0
    }
}

/**
 * 8本の遅延線によるフィードバック・ディレイ・ネットワーク（FDN）リバーブ。
 * 各遅延線のゲインを残響時間（RT60）から決める。
 */
class FdnReverb(private val sampleRate: Int, private val rt60Sec: Double = C.REVERB_RT60_SEC) {
    private val n = C.REVERB_DELAYS_MS.size
    private val lengths = IntArray(n) { (C.REVERB_DELAYS_MS[it] / 1000.0 * sampleRate).roundToInt() }
    private val lines = Array(n) { DoubleArray(lengths[it]) }
    private val positions = IntArray(n)
    private val gains = DoubleArray(n) { 10.0.pow(-3.0 * lengths[it] / (rt60Sec * sampleRate)) }
    private var damping = Array(n) { OnePole(sampleRate, C.REVERB_DAMPING_HZ) }
    private val preDelay = DoubleArray((C.REVERB_PREDELAY_SEC * sampleRate).roundToInt().coerceAtLeast(1))
    private var prePos = 0
    private val v = DoubleArray(n)
    private val scale = 1.0 / sqrt(n.toDouble())
    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun copy(): FdnReverb = FdnReverb(sampleRate, rt60Sec).also {
        for (i in 0 until n) lines[i].copyInto(it.lines[i])
        positions.copyInto(it.positions)
        it.damping = Array(n) { i -> damping[i].copy() }
        preDelay.copyInto(it.preDelay)
        it.prePos = prePos
        it.outL = outL
        it.outR = outR
    }

    fun process(input: Double) {
        val x = preDelay[prePos]
        preDelay[prePos] = input
        prePos = (prePos + 1) % preDelay.size

        for (i in 0 until n) v[i] = damping[i].process(lines[i][positions[i]]) * gains[i]
        var l = 0.0
        var r = 0.0
        for (i in 0 until n) if (i % 2 == 0) l += v[i] else r += v[i]
        outL = l * scale
        outR = r * scale

        hadamard(v)
        for (i in 0 until n) {
            val sign = if (i % 2 == 0) 1.0 else -1.0
            lines[i][positions[i]] = v[i] * scale + x * sign
            positions[i]++
            if (positions[i] == lengths[i]) positions[i] = 0
        }
    }

    /** 高速ウォルシュ・アダマール変換（正規化なし） */
    private fun hadamard(a: DoubleArray) {
        var h = 1
        while (h < a.size) {
            var i = 0
            while (i < a.size) {
                for (j in i until i + h) {
                    val x = a[j]
                    val y = a[j + h]
                    a[j] = x + y
                    a[j + h] = x - y
                }
                i += h * 2
            }
            h *= 2
        }
    }
}

/** ステレオ連動のコンプレッサー（ピーク検出、dB領域） */
class Compressor(
    private val sampleRate: Int,
    private val thresholdDb: Double = C.COMP_THRESHOLD_DB,
    private val ratio: Double = C.COMP_RATIO,
    private val attackSec: Double = C.COMP_ATTACK_SEC,
    private val releaseSec: Double = C.COMP_RELEASE_SEC,
    private val makeupDb: Double = C.COMP_MAKEUP_DB,
) {
    private val attack = exp(-1.0 / (attackSec * sampleRate))
    private val release = exp(-1.0 / (releaseSec * sampleRate))
    private val makeup = 10.0.pow(makeupDb / 20.0)
    private var envDb = -120.0

    fun copy(): Compressor =
        Compressor(sampleRate, thresholdDb, ratio, attackSec, releaseSec, makeupDb).also { it.envDb = envDb }

    /** 今のサンプルにかけるゲインを返す */
    fun gainFor(l: Double, r: Double): Double {
        val peak = maxOf(abs(l), abs(r))
        val db = if (peak > 1e-6) 20 * log10(peak) else -120.0
        val coeff = if (db > envDb) attack else release
        envDb = db + coeff * (envDb - db)
        val over = envDb - thresholdDb
        val reductionDb = if (over > 0) over * (1 - 1 / ratio) else 0.0
        return 10.0.pow(-reductionDb / 20.0) * makeup
    }
}
