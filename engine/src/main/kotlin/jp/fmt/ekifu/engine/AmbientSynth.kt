package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.AmbientPadVoice
import jp.fmt.ekifu.engine.dsp.BandSounds
import jp.fmt.ekifu.engine.dsp.BellVoice
import jp.fmt.ekifu.engine.dsp.Biquad
import jp.fmt.ekifu.engine.dsp.Compressor
import jp.fmt.ekifu.engine.dsp.DroneVoice
import jp.fmt.ekifu.engine.dsp.FdnReverb
import jp.fmt.ekifu.engine.dsp.FusionVoice
import jp.fmt.ekifu.engine.dsp.GrainVoice
import jp.fmt.ekifu.engine.dsp.Limiter
import jp.fmt.ekifu.engine.dsp.SampleVoice
import jp.fmt.ekifu.engine.dsp.StereoDelay
import jp.fmt.ekifu.engine.dsp.Voice
import jp.fmt.ekifu.engine.dsp.panLeft
import jp.fmt.ekifu.engine.dsp.panRight
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.tanh
import jp.fmt.ekifu.engine.AmbientConstants as A
import jp.fmt.ekifu.engine.MusicConstants as C

/** アンビエントの音符イベント */
sealed interface AmbientEvent {
    val timeSec: Double
}

/** パッドの1音。attackSec かけて膨らみ、durSec のあと余韻 */
data class AmbPad(override val timeSec: Double, val midi: Int, val durSec: Double, val attackSec: Double, val cutoffHz: Double, val pan: Double) : AmbientEvent

/** 低い持続音 */
data class AmbDrone(override val timeSec: Double, val midi: Int, val durSec: Double) : AmbientEvent

/** 音の粒 */
data class AmbGrain(override val timeSec: Double, val midi: Int, val gain: Double, val pan: Double) : AmbientEvent

/** ピアノ（音源の Grand Piano。音源がなければ粒の音で代わりに鳴らす） */
data class AmbPiano(override val timeSec: Double, val midi: Int, val velocity: Double, val durSec: Double, val pan: Double) : AmbientEvent

/** 地点のテーマ・到着の合図のベル（試聴と同じ音色） */
data class AmbBell(override val timeSec: Double, val midi: Int, val gain: Double) : AmbientEvent

/** 全体の明るさ（太陽） */
data class AmbControl(override val timeSec: Double, val brightnessHz: Double) : AmbientEvent

data class AmbFadeOut(override val timeSec: Double, val durationSec: Double) : AmbientEvent

/**
 * アンビエントの合成（段階8）。パッド・ドローン・粒は自前のシンセ、ピアノは音源（ローファイと同じ Grand Piano）。
 * 深いリバーブと長いディレイに多めに送る。状態は render をまたいで引き継ぎ、copy() で複製できる。
 */
class AmbientSynth(
    private val sampleRate: Int = C.SAMPLE_RATE,
    private val sounds: BandSounds? = null,
) : Renderer {

    private class Scheduled(val frame: Long, val order: Long, val event: AmbientEvent)

    /** 鳴っている音と定位・空間への送り */
    private class Playing(val voice: FusionVoice, val l: Double, val r: Double, val send: Double) {
        fun copy() = Playing(voice.copy(), l, r, send)
    }

    private var queue = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.frame }, { it.order }))
    private var order = 0L
    private var playing = ArrayList<Playing>()
    private var bells = ArrayList<Voice>()
    private var reverb = FdnReverb(sampleRate, A.REVERB_RT60_SEC)
    private var delay = StereoDelay(sampleRate, A.DELAY_SEC + 0.1, A.DELAY_LOWPASS_HZ, A.DELAY_FEEDBACK).also { it.setDelaySec(A.DELAY_SEC) }
    private var masterL = Biquad(sampleRate, A.brightnessHz(SunLevel.DAY))
    private var masterR = Biquad(sampleRate, A.brightnessHz(SunLevel.DAY))
    private var compressor = Compressor(sampleRate, A.COMP_THRESHOLD_DB, A.COMP_RATIO, A.COMP_ATTACK_SEC, A.COMP_RELEASE_SEC, 0.0)
    private var limiter = Limiter(sampleRate)
    private var fade = 1.0
    private var fadeStep = 0.0

    var frame = 0L
        private set

    val activeVoices: Int get() = playing.size + bells.size

    fun schedule(events: List<AmbientEvent>) {
        for (e in events) {
            val f = (e.timeSec * sampleRate).roundToLong().coerceAtLeast(frame)
            queue += Scheduled(f, order++, e)
        }
    }

    fun copy(): AmbientSynth = AmbientSynth(sampleRate, sounds).also {
        it.queue = PriorityQueue(queue)
        it.order = order
        it.playing = ArrayList(playing.map { p -> p.copy() })
        it.bells = ArrayList(bells.map { b -> b.copy() })
        it.reverb = reverb.copy()
        it.delay = delay.copy()
        it.masterL = masterL.copy()
        it.masterR = masterR.copy()
        it.compressor = compressor.copy()
        it.limiter = limiter.copy()
        it.fade = fade
        it.fadeStep = fadeStep
        it.frame = frame
    }

    override fun render(out: ShortArray, frames: Int, offsetFrames: Int) {
        for (i in 0 until frames) {
            while (true) {
                val head = queue.peek() ?: break
                if (head.frame > frame) break
                queue.poll()
                start(head.event)
            }
            var l = 0.0
            var r = 0.0
            var send = 0.0
            var k = 0
            while (k < playing.size) {
                val p = playing[k]
                val s = p.voice.next()
                l += s * p.l
                r += s * p.r
                send += s * p.send
                if (p.voice.finished) playing.removeAt(k) else k++
            }
            k = 0
            while (k < bells.size) {
                val v = bells[k]
                val s = v.next()
                l += s * v.gainL
                r += s * v.gainR
                send += s * (A.REVERB_SEND + A.GRAIN_EXTRA_SEND) * v.gain
                if (v.finished) bells.removeAt(k) else k++
            }
            reverb.process(send)
            delay.process(send * A.DELAY_SEND, send * A.DELAY_SEND)
            l += reverb.outL * A.REVERB_RETURN + delay.outL * A.DELAY_RETURN
            r += reverb.outR * A.REVERB_RETURN + delay.outR * A.DELAY_RETURN

            l = masterL.process(l)
            r = masterR.process(r)
            val cg = compressor.gainFor(l, r)
            l *= cg
            r *= cg
            val g = limiter.gainFor(l, r) * fade * A.OUTPUT_GAIN
            if (fadeStep > 0) fade = (fade - fadeStep).coerceAtLeast(0.0)
            val o = (offsetFrames + i) * 2
            out[o] = (softClip(l * g) * 32767).toInt().toShort()
            out[o + 1] = (softClip(r * g) * 32767).toInt().toShort()
            frame++
        }
    }

    private fun add(v: FusionVoice, pan: Double, send: Double) {
        playing += Playing(v, panLeft(pan), panRight(pan), send)
    }

    private fun start(e: AmbientEvent) {
        when (e) {
            is AmbPad -> add(
                AmbientPadVoice(sampleRate, hz(e.midi), A.PAD_GAIN, e.attackSec, e.durSec, e.cutoffHz, (e.midi % 7) / 7.0),
                e.pan, A.REVERB_SEND,
            )
            is AmbDrone -> add(DroneVoice(sampleRate, hz(e.midi), A.DRONE_GAIN, e.durSec), 0.0, A.REVERB_SEND * 0.5)
            is AmbGrain -> add(GrainVoice(sampleRate, hz(e.midi), e.gain), e.pan, A.REVERB_SEND + A.GRAIN_EXTRA_SEND)
            is AmbPiano -> {
                val s = sounds
                val v = if (s != null) {
                    SampleVoice(s, s.lead, e.midi, e.velocity, sampleRate, A.PIANO_GAIN, (e.durSec * sampleRate).roundToLong())
                } else {
                    GrainVoice(sampleRate, hz(e.midi), A.GRAIN_GAIN * 2)
                }
                add(v, e.pan, A.REVERB_SEND + A.GRAIN_EXTRA_SEND)
            }
            is AmbBell -> bells += BellVoice(sampleRate, hz(e.midi), e.gain, 0.0)
            is AmbControl -> {
                masterL.setCutoff(e.brightnessHz)
                masterR.setCutoff(e.brightnessHz)
            }
            is AmbFadeOut -> fadeStep = 1.0 / (e.durationSec * sampleRate)
        }
    }

    private fun hz(midi: Int) = Scale.midiToHz(midi.toDouble())

    private fun softClip(x: Double): Double {
        val k = C.SOFT_CLIP_KNEE
        val a = abs(x)
        if (a <= k) return x
        val y = k + (1 - k) * tanh((a - k) / (1 - k))
        return if (x < 0) -y else y
    }
}
