package jp.fmt.ekifu.engine.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** RBJ の2次ローパス（1チャンネル） */
class Biquad(private val sampleRate: Int, cutoffHz: Double, private val q: Double = 0.7071) {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        setCutoff(cutoffHz)
    }

    fun setCutoff(cutoffHz: Double) {
        val f = cutoffHz.coerceIn(20.0, sampleRate * 0.45)
        val w0 = 2 * PI * f / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosw = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 - cosw) / 2 / a0
        b1 = (1 - cosw) / a0
        b2 = b0
        a1 = -2 * cosw / a0
        a2 = (1 - alpha) / a0
    }

    /** 係数と内部状態をまるごと複製する */
    fun copy(): Biquad = Biquad(sampleRate, 1000.0, q).also {
        it.b0 = b0; it.b1 = b1; it.b2 = b2; it.a1 = a1; it.a2 = a2
        it.x1 = x1; it.x2 = x2; it.y1 = y1; it.y2 = y2
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

/** 1次ローパス（フィードバック経路の高域減衰用） */
class OnePole(private val sampleRate: Int, private val cutoffHz: Double) {
    private val a = exp(-2 * PI * cutoffHz / sampleRate)
    private var z = 0.0

    fun copy(): OnePole = OnePole(sampleRate, cutoffHz).also { it.z = z }

    fun process(x: Double): Double {
        z = x * (1 - a) + z * a
        return z
    }
}
