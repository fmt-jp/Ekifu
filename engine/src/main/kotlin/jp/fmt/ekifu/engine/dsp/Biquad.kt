package jp.fmt.ekifu.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RBJ 型の 2 次ローパス。状態はチャンクをまたいで保持する。 */
class BiquadLowpass(private val sampleRate: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun set(cutoffHz: Double, q: Double) {
        val w0 = 2 * PI * cutoffHz.coerceIn(10.0, sampleRate * 0.45) / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosW = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 - cosW) / 2 / a0
        b1 = (1 - cosW) / a0
        b2 = b0
        a1 = -2 * cosW / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}

/** 1 次ローパス（ディレイ・リバーブの帰還路用）。 */
class OnePoleLowpass(sampleRate: Int, cutoffHz: Double) {
    private val coef = 1 - kotlin.math.exp(-2 * PI * cutoffHz / sampleRate)
    private var state = 0.0

    fun process(x: Double): Double {
        state += coef * (x - state)
        return state
    }
}
