package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BassVoice
import jp.fmt.ekifu.engine.dsp.Biquad
import jp.fmt.ekifu.engine.dsp.BellVoice
import jp.fmt.ekifu.engine.dsp.Compressor
import jp.fmt.ekifu.engine.dsp.FdnReverb
import jp.fmt.ekifu.engine.dsp.PadVoice
import jp.fmt.ekifu.engine.dsp.PluckVoice
import jp.fmt.ekifu.engine.dsp.StereoDelay
import jp.fmt.ekifu.engine.dsp.Voice
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.tanh
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * 合成（音符イベント → PCM）。
 * 鳴っている音、ディレイ・リバーブ、フィルターの状態はこのインスタンスが持ち続けるので、
 * render を何回に分けて呼んでも波形はつながる。
 */
class Synth(private val sampleRate: Int = C.SAMPLE_RATE) : Renderer {

    private class Scheduled(val frame: Long, val order: Long, val event: MusicEvent)

    private var queue = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.frame }, { it.order }))
    private var order = 0L
    private var voices = ArrayList<Voice>()

    private var delay = StereoDelay(sampleRate)
    private var reverb = FdnReverb(sampleRate)
    private var masterL = Biquad(sampleRate, C.MASTER_CUTOFF_HZ)
    private var masterR = Biquad(sampleRate, C.MASTER_CUTOFF_HZ)
    private var compressor = Compressor(sampleRate)

    // 全体ローパスとディレイのフィードバックの移り変わり
    private var cutoff = C.MASTER_CUTOFF_HZ
    private var cutoffStep = 0.0
    private var cutoffTarget = C.MASTER_CUTOFF_HZ
    private var feedbackStep = 0.0
    private var feedbackTarget = C.DELAY_FEEDBACK

    // 終わりのフェードアウト
    private var fade = 1.0
    private var fadeStep = 0.0

    /** これまでに合成したフレーム数 */
    var frame = 0L
        private set

    /** 鳴っている音の数（デバッグ表示用） */
    val activeVoices: Int get() = voices.size

    fun schedule(events: List<MusicEvent>) {
        for (e in events) {
            val f = (e.timeSec * sampleRate).roundToLong().coerceAtLeast(frame)
            queue += Scheduled(f, order++, e)
        }
    }

    /** まだ鳴らしていない予定を捨てる（停止時） */
    fun clearScheduled() {
        queue.clear()
    }

    /** 鳴っている音・予定・エフェクトの内部バッファ・フィルターの状態をまるごと複製する */
    fun copy(): Synth = Synth(sampleRate).also {
        it.queue = PriorityQueue(queue)
        it.order = order
        it.voices = ArrayList<Voice>(voices.size).apply { voices.forEach { v -> add(v.copy()) } }
        it.delay = delay.copy()
        it.reverb = reverb.copy()
        it.masterL = masterL.copy()
        it.masterR = masterR.copy()
        it.compressor = compressor.copy()
        it.cutoff = cutoff
        it.cutoffStep = cutoffStep
        it.cutoffTarget = cutoffTarget
        it.feedbackStep = feedbackStep
        it.feedbackTarget = feedbackTarget
        it.fade = fade
        it.fadeStep = fadeStep
        it.frame = frame
    }

    /** frames 個のステレオフレームを out の offsetFrames 以降（L, R 交互）に書く */
    override fun render(out: ShortArray, frames: Int, offsetFrames: Int) {
        renderInto(out, frames, offsetFrames)
    }

    fun render(out: ShortArray, frames: Int) = renderInto(out, frames, 0)

    private fun renderInto(out: ShortArray, frames: Int, offsetFrames: Int) {
        for (i in 0 until frames) {
            while (true) {
                val head = queue.peek() ?: break
                if (head.frame > frame) break
                queue.poll()
                apply(head.event)
            }

            if (frame and 63L == 0L) updateControls(64)

            var dryL = 0.0
            var dryR = 0.0
            var delayL = 0.0
            var delayR = 0.0
            var reverbIn = 0.0
            var v = 0
            while (v < voices.size) {
                val voice = voices[v]
                val s = voice.next()
                val l = s * voice.gainL
                val r = s * voice.gainR
                dryL += l
                dryR += r
                val ds = delaySend(voice.instrument)
                delayL += l * ds
                delayR += r * ds
                reverbIn += (l + r) * 0.5 * reverbSend(voice.instrument)
                if (voice.finished) voices.removeAt(v) else v++
            }

            delay.process(delayL, delayR)
            reverb.process(reverbIn)
            var l = dryL + delay.outL * C.DELAY_RETURN + reverb.outL * C.REVERB_RETURN
            var r = dryR + delay.outR * C.DELAY_RETURN + reverb.outR * C.REVERB_RETURN

            l = masterL.process(l)
            r = masterR.process(r)
            val g = compressor.gainFor(l, r) * fade * C.MASTER_GAIN
            l = softClip(l * g)
            r = softClip(r * g)

            if (fadeStep > 0) fade = (fade - fadeStep).coerceAtLeast(0.0)

            val o = (offsetFrames + i) * 2
            out[o] = (l * 32767).toInt().toShort()
            out[o + 1] = (r * 32767).toInt().toShort()
            frame++
        }
    }

    private fun apply(e: MusicEvent) {
        when (e) {
            is NoteEvent -> voices += createVoice(e)
            is ReleaseEvent -> voices.forEach { if (it.instrument in e.instruments) it.release() }
            is ControlEvent -> {
                val rampSamples = (e.rampSec * sampleRate).coerceAtLeast(1.0)
                cutoffTarget = e.masterCutoffHz
                cutoffStep = (cutoffTarget - cutoff) / rampSamples
                feedbackTarget = e.delayFeedback
                feedbackStep = (feedbackTarget - delay.feedback) / rampSamples
            }
            is FadeOutEvent -> fadeStep = 1.0 / (e.durationSec * sampleRate)
        }
    }

    private fun createVoice(e: NoteEvent): Voice {
        val hz = Scale.midiToHz(e.midi.toDouble())
        return when (e.instrument) {
            Instrument.PAD -> PadVoice(sampleRate, hz, e.gain, e.pan, e.attackSec ?: C.PAD_ATTACK_SEC)
            Instrument.BASS -> BassVoice(sampleRate, hz, e.gain)
            Instrument.PLUCK -> PluckVoice(sampleRate, hz, e.gain, e.pan)
            Instrument.BELL -> BellVoice(sampleRate, hz, e.gain, e.pan)
        }
    }

    /** 64サンプルごとに、移り変わり中の値を進めてフィルター係数を更新する */
    private fun updateControls(samples: Int) {
        if (cutoff != cutoffTarget) {
            cutoff += cutoffStep * samples
            if (abs(cutoffTarget - cutoff) <= abs(cutoffStep * samples)) cutoff = cutoffTarget
            masterL.setCutoff(cutoff)
            masterR.setCutoff(cutoff)
        }
        if (delay.feedback != feedbackTarget) {
            var fb = delay.feedback + feedbackStep * samples
            if (abs(feedbackTarget - fb) <= abs(feedbackStep * samples)) fb = feedbackTarget
            delay.feedback = fb
        }
    }

    private fun delaySend(i: Instrument) = when (i) {
        Instrument.PAD -> C.PAD_DELAY_SEND
        Instrument.BASS -> C.BASS_DELAY_SEND
        Instrument.PLUCK -> C.PLUCK_DELAY_SEND
        Instrument.BELL -> C.BELL_DELAY_SEND
    }

    private fun reverbSend(i: Instrument) = when (i) {
        Instrument.PAD -> C.PAD_REVERB_SEND
        Instrument.BASS -> C.BASS_REVERB_SEND
        Instrument.PLUCK -> C.PLUCK_REVERB_SEND
        Instrument.BELL -> C.BELL_REVERB_SEND
    }

    private fun softClip(x: Double): Double {
        val k = C.SOFT_CLIP_KNEE
        val a = abs(x)
        if (a <= k) return x
        val y = k + (1 - k) * tanh((a - k) / (1 - k))
        return if (x < 0) -y else y
    }
}
