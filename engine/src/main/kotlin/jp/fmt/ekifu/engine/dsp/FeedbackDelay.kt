package jp.fmt.ekifu.engine.dsp

/** フィードバック路にローパスを入れたモノラルのディレイ。 */
class FeedbackDelay(sampleRate: Int, delaySeconds: Double, private val feedback: Double, lowpassHz: Double) {
    private val buffer = DoubleArray((delaySeconds * sampleRate).toInt().coerceAtLeast(1))
    private var index = 0
    private val lowpass = OnePoleLowpass(sampleRate, lowpassHz)

    fun copyFrom(other: FeedbackDelay) {
        other.buffer.copyInto(buffer)
        index = other.index
        lowpass.copyFrom(other.lowpass)
    }

    /** 1 サンプル処理して、ウェット信号を返す。 */
    fun process(input: Double): Double {
        val wet = lowpass.process(buffer[index])
        buffer[index] = input + wet * feedback
        index++
        if (index == buffer.size) index = 0
        return wet
    }
}
