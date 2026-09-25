package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** ステレオのディレイ（0.675秒、フィードバック0.32、フィードバック経路にローパス2200Hz） */
class StereoDelay(sampleRate: Int) {
    private val size = (C.DELAY_SEC * sampleRate).roundToInt()
    private val bufL = DoubleArray(size)
    private val bufR = DoubleArray(size)
    private val lpL = OnePole(sampleRate, C.DELAY_LOWPASS_HZ)
    private val lpR = OnePole(sampleRate, C.DELAY_LOWPASS_HZ)
    private var pos = 0
    var feedback = C.DELAY_FEEDBACK
    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun process(inL: Double, inR: Double) {
        val dl = lpL.process(bufL[pos])
        val dr = lpR.process(bufR[pos])
        bufL[pos] = inL + dl * feedback
        bufR[pos] = inR + dr * feedback
        outL = dl
        outR = dr
        pos++
        if (pos == size) pos = 0
    }
}

/**
 * 8本の遅延線によるフィードバック・ディレイ・ネットワーク（FDN）リバーブ。
 * 各遅延線のゲインを残響時間（RT60）から決める。
 */
class FdnReverb(sampleRate: Int) {
    private val n = C.REVERB_DELAYS_MS.size
    private val lengths = IntArray(n) { (C.REVERB_DELAYS_MS[it] / 1000.0 * sampleRate).roundToInt() }
    private val lines = Array(n) { DoubleArray(lengths[it]) }
    private val positions = IntArray(n)
    private val gains = DoubleArray(n) { 10.0.pow(-3.0 * lengths[it] / (C.REVERB_RT60_SEC * sampleRate)) }
    private val damping = Array(n) { OnePole(sampleRate, C.REVERB_DAMPING_HZ) }
    private val preDelay = DoubleArray((C.REVERB_PREDELAY_SEC * sampleRate).roundToInt().coerceAtLeast(1))
    private var prePos = 0
    private val v = DoubleArray(n)
    private val scale = 1.0 / sqrt(n.toDouble())
    var outL = 0.0
        private set
    var outR = 0.0
        private set

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
class Compressor(sampleRate: Int) {
    private val attack = exp(-1.0 / (C.COMP_ATTACK_SEC * sampleRate))
    private val release = exp(-1.0 / (C.COMP_RELEASE_SEC * sampleRate))
    private val makeup = 10.0.pow(C.COMP_MAKEUP_DB / 20.0)
    private var envDb = -120.0

    /** 今のサンプルにかけるゲインを返す */
    fun gainFor(l: Double, r: Double): Double {
        val peak = maxOf(abs(l), abs(r))
        val db = if (peak > 1e-6) 20 * log10(peak) else -120.0
        val coeff = if (db > envDb) attack else release
        envDb = db + coeff * (envDb - db)
        val over = envDb - C.COMP_THRESHOLD_DB
        val reductionDb = if (over > 0) over * (1 - 1 / C.COMP_RATIO) else 0.0
        return 10.0.pow(-reductionDb / 20.0) * makeup
    }
}
