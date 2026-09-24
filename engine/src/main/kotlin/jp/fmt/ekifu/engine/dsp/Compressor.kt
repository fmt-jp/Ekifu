package jp.fmt.ekifu.engine.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** 左右連動のピーク型コンプレッサー。 */
class Compressor(
    sampleRate: Int,
    private val thresholdDb: Double,
    private val ratio: Double,
    attackSeconds: Double,
    releaseSeconds: Double,
    private val makeupDb: Double,
) {
    private val attackCoef = exp(-1.0 / (attackSeconds * sampleRate))
    private val releaseCoef = exp(-1.0 / (releaseSeconds * sampleRate))
    private var envelope = 0.0

    /** この 1 サンプルに掛けるゲインを返す。 */
    fun gainFor(left: Double, right: Double): Double {
        val level = max(abs(left), abs(right))
        val coef = if (level > envelope) attackCoef else releaseCoef
        envelope = coef * envelope + (1 - coef) * level
        val levelDb = 20 * log10(envelope + 1e-9)
        val over = levelDb - thresholdDb
        val reductionDb = if (over > 0) over * (1 - 1 / ratio) else 0.0
        return 10.0.pow((makeupDb - reductionDb) / 20)
    }
}
