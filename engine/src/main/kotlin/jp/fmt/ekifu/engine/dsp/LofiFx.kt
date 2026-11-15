package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.Mulberry32
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import jp.fmt.ekifu.engine.LofiConstants as L

/**
 * ローファイの加工（全体の最後、コンプレッサーの手前にかける）。
 * - テープの揺れ：遅れを少しずつ揺らして音程をゆらす（ワウ＋フラッター）
 * - テープの飽和：tanh で軽く歪ませる
 * - 低音を少し削る
 * - レコードのノイズ：ヒス（サー）とプチプチ。量は [noiseFactor]（太陽）で変える
 * 乱数はシード付きで、copy() で状態ごと複製できる（チャンクの作り直し用）。
 */
class LofiFx(private val sampleRate: Int, seed: Int) {
    private var rng = Mulberry32(seed)
    private val size = ((L.WOBBLE_BASE_SEC + L.WOW_DEPTH_SEC + L.FLUTTER_DEPTH_SEC) * sampleRate).toInt() + 4
    private var bufL = DoubleArray(size)
    private var bufR = DoubleArray(size)
    private var write = 0
    private var wowPhase = 0.0
    private var flutterPhase = 0.0
    private var hpL = Biquad(sampleRate, L.HIGHPASS_HZ, type = FilterType.HIGHPASS)
    private var hpR = Biquad(sampleRate, L.HIGHPASS_HZ, type = FilterType.HIGHPASS)
    private var hiss = Biquad(sampleRate, L.HISS_LOWPASS_HZ)
    private var crackle = 0.0
    private var crackleSign = 1.0
    private val crackleDecay = exp(-1.0 / (L.CRACKLE_DECAY_SEC * sampleRate))
    private val crackleProb = L.CRACKLE_PER_SEC / sampleRate

    /** ノイズの量（太陽：昼1.0、夜は多め） */
    var noiseFactor = 1.0

    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun process(inL: Double, inR: Double) {
        bufL[write] = inL
        bufR[write] = inR
        wowPhase += L.WOW_HZ / sampleRate
        if (wowPhase >= 1.0) wowPhase -= 1.0
        flutterPhase += L.FLUTTER_HZ / sampleRate
        if (flutterPhase >= 1.0) flutterPhase -= 1.0
        val delaySec = L.WOBBLE_BASE_SEC + L.WOW_DEPTH_SEC * sin(2 * PI * wowPhase) +
            L.FLUTTER_DEPTH_SEC * sin(2 * PI * flutterPhase)
        val d = delaySec * sampleRate
        var l = read(bufL, d)
        var r = read(bufR, d)
        write = (write + 1) % size

        l = hpL.process(saturate(l))
        r = hpR.process(saturate(r))

        // レコードのノイズ（左右同じ。真ん中で鳴る）
        val h = hiss.process(rng.nextDouble() * 2 - 1) * L.HISS_GAIN * noiseFactor
        if (rng.nextDouble() < crackleProb * noiseFactor) {
            crackle = L.CRACKLE_GAIN * (0.4 + 0.6 * rng.nextDouble())
            crackleSign = if (rng.nextDouble() < 0.5) -1.0 else 1.0
        }
        val c = crackle * crackleSign
        crackle *= crackleDecay
        outL = l + h + c
        outR = r + h + c
    }

    private fun read(buf: DoubleArray, delayFrames: Double): Double {
        var pos = write - delayFrames
        while (pos < 0) pos += size
        val i = pos.toInt()
        val frac = pos - i
        val a = buf[i % size]
        val b = buf[(i + 1) % size]
        return a + (b - a) * frac
    }

    private fun saturate(x: Double) = tanh(x * L.SATURATION_DRIVE) / L.SATURATION_DRIVE

    fun copy(): LofiFx = LofiFx(sampleRate, 0).also {
        it.rng = rng.copy()
        it.bufL = bufL.copyOf()
        it.bufR = bufR.copyOf()
        it.write = write
        it.wowPhase = wowPhase
        it.flutterPhase = flutterPhase
        it.hpL = hpL.copy()
        it.hpR = hpR.copy()
        it.hiss = hiss.copy()
        it.crackle = crackle
        it.crackleSign = crackleSign
        it.noiseFactor = noiseFactor
        it.outL = outL
        it.outR = outR
    }
}
