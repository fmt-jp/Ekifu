package jp.fmt.ekifu.engine.dsp

import kotlin.math.pow

/**
 * 8 本のディレイ線によるフィードバック・ディレイ・ネットワーク残響。
 * 帰還ゲインを残響時間（RT60）から計算するので、減衰時間を定数で直接指定できる。
 */
class FdnReverb(sampleRate: Int, decaySeconds: Double, dampingHz: Double, private val outputGain: Double) {
    private val lines: Array<DoubleArray>
    private val indices = IntArray(LINE_COUNT)
    private val gains = DoubleArray(LINE_COUNT)
    private val dampers = Array(LINE_COUNT) { OnePoleLowpass(sampleRate, dampingHz) }
    private val diffusers = DIFFUSER_LENGTHS.map { Allpass((it * sampleRate / 44_100.0).toInt(), DIFFUSER_GAIN) }
    private val taps = DoubleArray(LINE_COUNT)

    init {
        val scale = sampleRate / 44_100.0
        lines = Array(LINE_COUNT) { DoubleArray((LINE_LENGTHS[it] * scale).toInt().coerceAtLeast(1)) }
        for (i in 0 until LINE_COUNT) {
            gains[i] = 10.0.pow(-3.0 * lines[i].size / (decaySeconds * sampleRate))
        }
    }

    /** モノラル入力を処理して、左右の残響を [out] に書く。 */
    fun process(input: Double, out: DoubleArray) {
        var x = input
        for (d in diffusers) x = d.process(x)
        var sum = 0.0
        for (i in 0 until LINE_COUNT) {
            val v = dampers[i].process(lines[i][indices[i]])
            taps[i] = v
            sum += v
        }
        // ハウスホルダー行列で混ぜる（エネルギーを保つ）
        val mix = sum * (2.0 / LINE_COUNT)
        var left = 0.0
        var right = 0.0
        for (i in 0 until LINE_COUNT) {
            val line = lines[i]
            line[indices[i]] = x + (taps[i] - mix) * gains[i]
            indices[i]++
            if (indices[i] == line.size) indices[i] = 0
            val sign = if ((i / 2) % 2 == 0) 1.0 else -1.0
            if (i % 2 == 0) left += taps[i] * sign else right += taps[i] * sign
        }
        out[0] = left * outputGain
        out[1] = right * outputGain
    }

    private class Allpass(length: Int, private val gain: Double) {
        private val buffer = DoubleArray(length.coerceAtLeast(1))
        private var index = 0

        fun process(x: Double): Double {
            val delayed = buffer[index]
            val y = -gain * x + delayed
            buffer[index] = x + gain * y
            index++
            if (index == buffer.size) index = 0
            return y
        }
    }

    private companion object {
        const val LINE_COUNT = 8
        /** 44.1kHz 基準の長さ（互いに素に近い値で、響きの周期性を避ける）。 */
        val LINE_LENGTHS = intArrayOf(1931, 2213, 2477, 2803, 3121, 3469, 3803, 4153)
        val DIFFUSER_LENGTHS = intArrayOf(142, 379, 557)
        const val DIFFUSER_GAIN = 0.6
    }
}
