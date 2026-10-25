package jp.fmt.ekifu.engine.dsp

import jp.fmt.ekifu.engine.BandConstants as B
import jp.fmt.ekifu.engine.FusionConstants as F
import jp.fmt.ekifu.engine.sf2.BankPreset
import jp.fmt.ekifu.engine.sf2.BankZone
import jp.fmt.ekifu.engine.sf2.SampleBank
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * サウンドフォントのゾーン1つを鳴らす。サンプルを音程に合わせた速さで読み（直線補間）、
 * SF2 の音量エンベロープ（遅れ・立ち上がり・保持・減衰・持続・余韻）とループに従う。
 * サンプルの中身は共有し、読み位置とエンベロープだけを複製する（チャンクの作り直し用）。
 */
internal class ZonePlayer(
    private val zone: BankZone,
    private val data: ShortArray,
    private val srcRate: Int,
    private val outRate: Int,
    private val gain: Double,
) {
    private enum class Stage { DELAY, ATTACK, HOLD, DECAY, SUSTAIN, RELEASE, DONE }

    private var pos = zone.start.toDouble()
    private var inc = 1.0
    private var stage = Stage.DELAY
    private var stageLeft = frames(zone.delaySec)
    private var amp = 0.0
    private var attackStep = 1.0 / frames(zone.attackSec).coerceAtLeast(1)
    /** 減衰・余韻：1フレームあたりの音量の倍率（cB の割合で下げる＝指数で減る） */
    private val decayFactor = cbFactor(FULL_CB / frames(zone.decaySec).coerceAtLeast(1))
    private val releaseFactor = cbFactor(FULL_CB / frames(zone.releaseSec).coerceAtLeast(1))
    private val sustainAmp = cbToAmp(zone.sustainCb)
    private val zoneGain = cbToAmp(zone.attenuationCb)
    private var released = false
    private var fadeLeft = 0
    private var fadeGain = 1.0
    private var fadeStep = 0.0
    private var lp: Biquad? = if (zone.filterHz < FILTER_OFF_HZ) Biquad(outRate, zone.filterHz) else null

    val done: Boolean get() = stage == Stage.DONE

    fun setPitch(midi: Double) {
        val cents = (midi - zone.rootKey) * zone.scaleTuning + zone.tuneCents
        inc = srcRate.toDouble() / outRate * 2.0.pow(cents / 1200.0)
    }

    fun release() {
        if (released || stage == Stage.DONE) return
        released = true
        stage = Stage.RELEASE
    }

    /** 余韻を待たずに frames かけて消す（リードの鳴らし直し・ハイハットの止め） */
    fun fadeOut(frames: Int) {
        if (stage == Stage.DONE) return
        val f = frames.coerceAtLeast(1)
        fadeLeft = f
        fadeStep = fadeGain / f
    }

    fun next(): Double {
        if (stage == Stage.DONE) return 0.0
        val env = envelope()
        if (stage == Stage.DONE) return 0.0
        // 読み位置
        val i = pos.toInt()
        if (i + 1 >= data.size || (i >= zone.end - 1 && !looping())) {
            stage = Stage.DONE
            return 0.0
        }
        val frac = pos - i
        var s = (data[i] + (data[i + 1] - data[i]) * frac) / 32768.0
        pos += inc
        if (looping() && pos >= zone.loopEnd && zone.loopEnd > zone.loopStart) pos -= (zone.loopEnd - zone.loopStart)
        lp?.let { s = it.process(s) }
        if (fadeLeft > 0) {
            fadeGain -= fadeStep
            if (--fadeLeft == 0) stage = Stage.DONE
        }
        return s * env * zoneGain * gain * fadeGain
    }

    private fun looping() = zone.loopMode == 1 || (zone.loopMode == 3 && !released)

    private fun envelope(): Double {
        when (stage) {
            Stage.DELAY -> if (stageLeft-- <= 0) { stage = Stage.ATTACK }
            Stage.ATTACK -> {
                amp += attackStep
                if (amp >= 1.0) {
                    amp = 1.0
                    stage = Stage.HOLD
                    stageLeft = frames(zone.holdSec)
                }
            }
            Stage.HOLD -> if (stageLeft-- <= 0) stage = Stage.DECAY
            Stage.DECAY -> {
                amp *= decayFactor
                if (amp <= sustainAmp) {
                    amp = sustainAmp
                    stage = Stage.SUSTAIN
                }
            }
            Stage.SUSTAIN -> Unit
            Stage.RELEASE -> {
                amp *= releaseFactor
                if (amp < SILENT_AMP) stage = Stage.DONE
            }
            Stage.DONE -> Unit
        }
        if (stage == Stage.SUSTAIN && amp < SILENT_AMP) stage = Stage.DONE
        return amp
    }

    private fun frames(sec: Double) = (sec * outRate).roundToInt()

    fun copy() = ZonePlayer(zone, data, srcRate, outRate, gain).also {
        it.pos = pos
        it.inc = inc
        it.stage = stage
        it.stageLeft = stageLeft
        it.amp = amp
        it.attackStep = attackStep
        it.released = released
        it.fadeLeft = fadeLeft
        it.fadeGain = fadeGain
        it.fadeStep = fadeStep
        it.lp = lp?.copy()
    }

    companion object {
        /** SF2 の減衰・余韻の時間は 100dB（1000cB）下がるまでの時間 */
        private const val FULL_CB = 1000.0
        private const val SILENT_AMP = 1e-4
        private const val FILTER_OFF_HZ = 12000.0
        fun cbToAmp(cb: Double) = 10.0.pow(-cb / 200.0)
        private fun cbFactor(cbPerFrame: Double) = 10.0.pow(-cbPerFrame / 200.0)
        fun ampToCb(amp: Double) = -200.0 * log10(amp.coerceAtLeast(1e-9))
    }
}

/** 強さ（0〜1）→ SF2 の強さ（1〜127）と音量 */
internal fun midiVelocity(v: Double) = (v * 127).roundToInt().coerceIn(1, 127)
internal fun velocityGain(v: Double) = v.coerceIn(0.0, 1.0).pow(B.VELOCITY_EXPONENT)

/** 鳴らす楽器を音源から引く */
class BandSounds(val bank: SampleBank) {
    val lead: BankPreset = bank.preset(B.LEAD_BANK, B.LEAD_PROGRAM) ?: error("リードの楽器がない")
    val keys: BankPreset = bank.preset(B.KEYS_BANK, B.KEYS_PROGRAM) ?: error("鍵盤の楽器がない")
    val bass: BankPreset = bank.preset(B.BASS_BANK, B.BASS_PROGRAM) ?: error("ベースの楽器がない")
    val drums: BankPreset = bank.preset(B.DRUM_BANK, B.DRUM_PROGRAM) ?: error("ドラムセットがない")

    internal fun players(preset: BankPreset, key: Int, velocity: Double, sampleRate: Int, gain: Double): List<ZonePlayer> {
        val zones = preset.zonesFor(key, midiVelocity(velocity))
        // ステレオの左右などが重なるときは平均して1つの音にする
        val g = gain * velocityGain(velocity) / zones.size.coerceAtLeast(1)
        return zones.map { z ->
            val s = bank.samples[z.sample]
            ZonePlayer(z, s.data, s.sampleRate, sampleRate, g)
        }
    }
}

/** 鍵盤・ベース・ドラムの1音。durFrames のあと余韻に入る（ドラムは鳴らしきり） */
class SampleVoice internal constructor(
    private var players: List<ZonePlayer>,
    private var framesLeft: Long,
) : FusionVoice() {

    constructor(
        sounds: BandSounds, preset: BankPreset, key: Int, velocity: Double, sampleRate: Int, gain: Double,
        durFrames: Long, pitchFactor: Double = 1.0,
    ) : this(sounds.players(preset, key, velocity, sampleRate, gain), durFrames) {
        val pitch = key + 12.0 * kotlin.math.ln(pitchFactor) / kotlin.math.ln(2.0)
        players.forEach { it.setPitch(pitch) }
    }

    override fun next(): Double {
        if (framesLeft-- == 0L) players.forEach { it.release() }
        var s = 0.0
        var live = false
        for (p in players) {
            s += p.next()
            if (!p.done) live = true
        }
        if (!live) finished = true
        return s
    }

    /** ハイハットの止め（同じ組の音が鳴ったら短く消す） */
    override fun release() {
        players.forEach { it.fadeOut(CHOKE_FRAMES) }
    }

    override fun copy() = SampleVoice(players.map { it.copy() }, framesLeft).also { it.finished = finished }

    private companion object {
        const val CHOKE_FRAMES = 400
    }
}

/**
 * リード（アルトサックスなど）。1音ずつサンプルを鳴らし直し、前の音は短く消す。
 * しゃくり・フォール・グライドは音程を動かして、ビブラートは音程をゆらして出す
 */
class SampleLead(private val sampleRate: Int, private val sounds: BandSounds) : LeadSound {
    private var current: List<ZonePlayer> = emptyList()
    private var fading: List<ZonePlayer> = emptyList()
    private var pitch = 72.0
    private var glideStep = 0.0
    private var glideLeft = 0
    private var vibPhase = 0.0
    private var vibDepth = 0.0
    private var vibTarget = 0.0
    private var vibStep = 0.0
    private var counter = 0
    override var filterBaseHz = F.LEAD_FILTER_BASE_HZ

    override val sounding: Boolean get() = current.any { !it.done } || fading.any { !it.done }

    override fun noteOn(midi: Double, glideSec: Double, velocity: Double, vibratoCents: Double, vibratoRampSec: Double) {
        val legato = current.any { !it.done }
        fading = (fading + current).filter { !it.done }
        fading.forEach { it.fadeOut((B.LEAD_RETRIGGER_FADE_SEC * sampleRate).roundToInt()) }
        if (legato) moveTo(midi, glideSec) else {
            pitch = midi
            glideLeft = 0
        }
        current = sounds.players(sounds.lead, midi.roundToInt(), velocity, sampleRate, B.LEAD_GAIN)
        current.forEach { it.setPitch(pitch) }
        vibDepth = 0.0
        vibTarget = vibratoCents
        vibStep = if (vibratoCents > 0) vibratoCents / (vibratoRampSec * sampleRate).coerceAtLeast(1.0) else 0.0
    }

    override fun moveTo(midi: Double, sec: Double) {
        val frames = (sec * sampleRate).toInt()
        if (frames <= 0) {
            pitch = midi
            glideLeft = 0
        } else {
            glideStep = (midi - pitch) / frames
            glideLeft = frames
        }
    }

    override fun noteOff() {
        current.forEach { it.release() }
        vibTarget = 0.0
        vibDepth = 0.0
    }

    override fun next(): Double {
        if (glideLeft > 0) {
            pitch += glideStep
            glideLeft--
        }
        if (vibDepth < vibTarget) vibDepth = (vibDepth + vibStep).coerceAtMost(vibTarget)
        vibPhase += F.VIBRATO_HZ / sampleRate
        if (vibPhase >= 1.0) vibPhase -= 1.0
        if (counter++ % B.PITCH_UPDATE_FRAMES == 0) {
            val p = pitch + vibDepth * sin(2 * PI * vibPhase) / 100.0
            current.forEach { it.setPitch(p) }
        }
        var s = 0.0
        for (z in current) s += z.next()
        if (fading.isNotEmpty()) {
            for (z in fading) s += z.next()
            if (fading.all { it.done }) fading = emptyList()
        }
        return s
    }

    override fun copy() = SampleLead(sampleRate, sounds).also {
        it.current = current.map { z -> z.copy() }
        it.fading = fading.map { z -> z.copy() }
        it.pitch = pitch
        it.glideStep = glideStep
        it.glideLeft = glideLeft
        it.vibPhase = vibPhase
        it.vibDepth = vibDepth
        it.vibTarget = vibTarget
        it.vibStep = vibStep
        it.counter = counter
        it.filterBaseHz = filterBaseHz
    }
}
