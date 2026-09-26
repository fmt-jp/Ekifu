package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.dsp.BassVoice2
import jp.fmt.ekifu.engine.dsp.Biquad
import jp.fmt.ekifu.engine.dsp.BrassVoice
import jp.fmt.ekifu.engine.dsp.Chorus
import jp.fmt.ekifu.engine.dsp.Compressor
import jp.fmt.ekifu.engine.dsp.FdnReverb
import jp.fmt.ekifu.engine.dsp.FilterType
import jp.fmt.ekifu.engine.dsp.FusionVoice
import jp.fmt.ekifu.engine.dsp.LeadVoice
import jp.fmt.ekifu.engine.dsp.Limiter
import jp.fmt.ekifu.engine.dsp.MembraneVoice
import jp.fmt.ekifu.engine.dsp.NoiseVoice
import jp.fmt.ekifu.engine.dsp.StereoDelay
import jp.fmt.ekifu.engine.dsp.panLeft
import jp.fmt.ekifu.engine.dsp.panRight
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.tanh
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.MusicConstants as C

/**
 * フュージョンの合成（12.8）。音符イベント → PCM。
 * 鳴っている音・リードの状態・空間系の内部バッファ・フィルターは render をまたいで引き継ぎ、copy() で複製できる。
 */
class FusionSynth(private val sampleRate: Int = C.SAMPLE_RATE, noiseSeed: Int = 1) : Renderer {

    /** リードのイベントを発音・音程移動・消音に分けたもの */
    private sealed interface LeadAction {
        data class On(
            val id: Long,
            val midi: Double,
            val glideSec: Double,
            val velocity: Double,
            val vibCents: Double,
            val vibRampSec: Double,
        ) : LeadAction
        data class Move(val id: Long, val midi: Double, val sec: Double) : LeadAction
        data class Off(val id: Long) : LeadAction
    }

    private class Scheduled(val frame: Long, val order: Long, val action: Any)

    private var queue = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.frame }, { it.order }))
    private var order = 0L
    private var noiseSeed = noiseSeed
    /** リードの音の通し番号。前の音の消音・フォールが次の音にかからないようにする */
    private var leadNoteCount = 0L
    private var leadCurrent = -1L

    private var brass = ArrayList<FusionVoice>()
    private var bass = ArrayList<FusionVoice>()
    private var drums = ArrayList<FusionVoice>()
    private var hats = ArrayList<FusionVoice>()
    private var lead = LeadVoice(sampleRate)

    private var brassLp = Biquad(sampleRate, F.BRASS_LOWPASS_HZ)
    private var chorus = Chorus(sampleRate)
    private var bassLp = Biquad(sampleRate, F.BASS_LOWPASS_HZ)
    private var leadLp = Biquad(sampleRate, F.LEAD_POST_LOWPASS_HZ)
    private var delay = StereoDelay(
        sampleRate,
        maxDelaySec = 60.0 / F.MIN_BPM / 4 * F.DELAY_STEPS,
        lowpassHz = F.DELAY_LOWPASS_HZ,
        initialFeedback = F.DELAY_FEEDBACK,
    ).also { it.setDelaySec(60.0 / F.DEMO_BPM / 4 * F.DELAY_STEPS) }
    private var reverb = FdnReverb(sampleRate, F.REVERB_RT60_SEC)
    private var masterL = Biquad(sampleRate, F.DAY_CUTOFF_HZ)
    private var masterR = Biquad(sampleRate, F.DAY_CUTOFF_HZ)
    private var compressor = Compressor(
        sampleRate, F.COMP_THRESHOLD_DB, F.COMP_RATIO, F.COMP_ATTACK_SEC, F.COMP_RELEASE_SEC, 0.0,
    )
    private var limiter = Limiter(sampleRate)

    private var fade = 1.0
    private var fadeStep = 0.0

    var frame = 0L
        private set

    val activeVoices: Int get() = brass.size + bass.size + drums.size + hats.size + (if (lead.sounding) 1 else 0)

    fun schedule(events: List<FusionEvent>) {
        for (e in events) {
            if (e is LeadNote) scheduleLead(e) else add(e.timeSec, e)
        }
    }

    private fun scheduleLead(n: LeadNote) {
        val vib = if (n.vibratoCents > 0 && n.durSec > F.VIBRATO_MIN_SEC) n.vibratoCents else 0.0
        val vibRamp = minOf(F.VIBRATO_RAMP_MAX_SEC, n.durSec * F.VIBRATO_RAMP_FACTOR)
        val id = leadNoteCount++
        add(n.timeSec, LeadAction.On(id, (n.midi - n.scoopSemitones).toDouble(), n.glideSec, n.velocity, vib, vibRamp))
        if (n.scoopSemitones > 0) {
            add(n.timeSec + F.SCOOP_DELAY_SEC, LeadAction.Move(id, n.midi.toDouble(), F.SCOOP_GLIDE_SEC))
        }
        if (n.fall && n.durSec > F.FALL_MIN_SEC) {
            add(
                n.timeSec + n.durSec - F.FALL_BEFORE_END_SEC,
                LeadAction.Move(id, (n.midi - F.FALL_SEMITONES).toDouble(), F.FALL_GLIDE_SEC),
            )
        }
        add(n.timeSec + n.durSec, LeadAction.Off(id))
    }

    private fun add(timeSec: Double, action: Any) {
        val f = (timeSec * sampleRate).roundToLong().coerceAtLeast(frame)
        queue += Scheduled(f, order++, action)
    }

    fun copy(): FusionSynth = FusionSynth(sampleRate).also {
        it.queue = PriorityQueue(queue)
        it.order = order
        it.noiseSeed = noiseSeed
        it.leadNoteCount = leadNoteCount
        it.leadCurrent = leadCurrent
        it.brass = ArrayList(brass.map { v -> v.copy() })
        it.bass = ArrayList(bass.map { v -> v.copy() })
        it.drums = ArrayList(drums.map { v -> v.copy() })
        it.hats = ArrayList(hats.map { v -> v.copy() })
        it.lead = lead.copy()
        it.brassLp = brassLp.copy()
        it.chorus = chorus.copy()
        it.bassLp = bassLp.copy()
        it.leadLp = leadLp.copy()
        it.delay = delay.copy()
        it.reverb = reverb.copy()
        it.masterL = masterL.copy()
        it.masterR = masterR.copy()
        it.compressor = compressor.copy()
        it.limiter = limiter.copy()
        it.fade = fade
        it.fadeStep = fadeStep
        it.frame = frame
    }

    override fun render(out: ShortArray, frames: Int, offsetFrames: Int) {
        val brassL = panLeft(F.BRASS_PAN) * SQRT2
        val brassR = panRight(F.BRASS_PAN) * SQRT2
        val leadL = panLeft(F.LEAD_PAN)
        val leadR = panRight(F.LEAD_PAN)
        val hatL = panLeft(F.HAT_PAN)
        val hatR = panRight(F.HAT_PAN)
        val center = panLeft(0.0)

        for (i in 0 until frames) {
            while (true) {
                val head = queue.peek() ?: break
                if (head.frame > frame) break
                queue.poll()
                apply(head.action)
            }

            // 各パートを足し合わせる
            val b = brassLp.process(sum(brass))
            chorus.process(b)
            val bL = chorus.outL * brassL
            val bR = chorus.outR * brassR
            val bs = bassLp.process(sum(bass)) * center
            val ld = leadLp.process(lead.next())
            val lL = ld * leadL
            val lR = ld * leadR
            val dr = sum(drums) * center
            val h = sum(hats)

            // 空間系への送り（ハイハットは送らない）
            val revIn = (bL + bR) * 0.5 * F.BRASS_SEND.reverb + (lL + lR) * 0.5 * F.LEAD_SEND.reverb +
                bs * F.BASS_SEND.reverb + dr * F.DRUM_SEND.reverb
            val dlL = bL * F.BRASS_SEND.delay + lL * F.LEAD_SEND.delay + bs * F.BASS_SEND.delay + dr * F.DRUM_SEND.delay
            val dlR = bR * F.BRASS_SEND.delay + lR * F.LEAD_SEND.delay + bs * F.BASS_SEND.delay + dr * F.DRUM_SEND.delay
            reverb.process(revIn)
            delay.process(dlL, dlR)

            var l = bL + lL + bs + dr + h * hatL + reverb.outL * F.REVERB_RETURN + delay.outL * F.DELAY_RETURN
            var r = bR + lR + bs + dr + h * hatR + reverb.outR * F.REVERB_RETURN + delay.outR * F.DELAY_RETURN

            l = masterL.process(l)
            r = masterR.process(r)
            val cg = compressor.gainFor(l, r)
            l *= cg
            r *= cg
            val lg = limiter.gainFor(l, r)
            val g = lg * fade * F.OUTPUT_GAIN
            if (fadeStep > 0) fade = (fade - fadeStep).coerceAtLeast(0.0)

            val o = (offsetFrames + i) * 2
            out[o] = (softClip(l * g) * 32767).toInt().toShort()
            out[o + 1] = (softClip(r * g) * 32767).toInt().toShort()
            frame++
        }
    }

    private fun sum(voices: ArrayList<FusionVoice>): Double {
        var s = 0.0
        var i = 0
        while (i < voices.size) {
            val v = voices[i]
            s += v.next()
            if (v.finished) voices.removeAt(i) else i++
        }
        return s
    }

    private fun apply(action: Any) {
        when (action) {
            is LeadAction.On -> {
                leadCurrent = action.id
                lead.noteOn(action.midi, action.glideSec, action.velocity, action.vibCents, action.vibRampSec)
            }
            is LeadAction.Move -> if (action.id == leadCurrent) lead.moveTo(action.midi, action.sec)
            is LeadAction.Off -> if (action.id == leadCurrent) lead.noteOff()
            is PolyNote -> {
                val frames = (action.durSec * sampleRate).roundToLong()
                when (action.part) {
                    PolyPart.BRASS -> brass += BrassVoice(sampleRate, action.midi, action.velocity * F.BRASS_GAIN, frames)
                    PolyPart.BASS -> bass += BassVoice2(sampleRate, action.midi, action.velocity * F.BASS_GAIN, frames)
                }
            }
            is DrumHit -> addDrum(action)
            is FusionControl -> {
                masterL.setCutoff(action.masterCutoffHz)
                masterR.setCutoff(action.masterCutoffHz)
                lead.filterBaseHz = action.leadFilterBaseHz
                delay.setDelaySec(action.delaySec)
                delay.feedback = action.delayFeedback
            }
            is FusionFadeOut -> fadeStep = 1.0 / (action.durationSec * sampleRate)
        }
    }

    private fun addDrum(d: DrumHit) {
        val v = d.velocity
        val seed = noiseSeed++
        when (d.kind) {
            DrumKind.KICK -> drums += MembraneVoice(
                sampleRate, F.KICK_HZ * d.pitchFactor, F.KICK_OCTAVES, F.KICK_PITCH_DECAY_SEC, F.KICK_DECAY_SEC, v * F.KICK_GAIN,
            )
            DrumKind.SNARE -> drums += NoiseVoice(
                sampleRate, true, FilterType.BANDPASS, F.SNARE_BANDPASS_HZ,
                F.SNARE_DECAY_BASE_SEC + F.SNARE_DECAY_VEL_SEC * v, v * F.SNARE_GAIN, seed,
            )
            DrumKind.HAT, DrumKind.OPEN_HAT -> hats += NoiseVoice(
                sampleRate, false, FilterType.HIGHPASS, F.HAT_HIGHPASS_HZ,
                if (d.kind == DrumKind.OPEN_HAT) F.OPEN_HAT_DECAY_SEC else F.HAT_DECAY_BASE_SEC + F.HAT_DECAY_VEL_SEC * v,
                v * F.HAT_GAIN, seed,
            )
            DrumKind.TOM_HIGH, DrumKind.TOM_MID, DrumKind.TOM_LOW -> {
                val hz = when (d.kind) {
                    DrumKind.TOM_HIGH -> F.TOM_HIGH_HZ
                    DrumKind.TOM_MID -> F.TOM_MID_HZ
                    else -> F.TOM_LOW_HZ
                }
                drums += MembraneVoice(
                    sampleRate, hz * d.pitchFactor, F.TOM_OCTAVES, F.TOM_PITCH_DECAY_SEC, F.TOM_DECAY_SEC, v * F.TOM_GAIN,
                )
            }
            DrumKind.CRASH -> drums += NoiseVoice(
                sampleRate, false, FilterType.HIGHPASS, F.CRASH_HIGHPASS_HZ, F.CRASH_DECAY_SEC, v * F.CRASH_GAIN, seed,
            )
        }
    }

    private fun softClip(x: Double): Double {
        val k = C.SOFT_CLIP_KNEE
        val a = abs(x)
        if (a <= k) return x
        val y = k + (1 - k) * tanh((a - k) / (1 - k))
        return if (x < 0) -y else y
    }

    private companion object {
        val SQRT2 = kotlin.math.sqrt(2.0)
    }
}
