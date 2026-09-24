package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import jp.fmt.ekifu.engine.dsp.BassVoice
import jp.fmt.ekifu.engine.dsp.BellVoice
import jp.fmt.ekifu.engine.dsp.BiquadLowpass
import jp.fmt.ekifu.engine.dsp.Compressor
import jp.fmt.ekifu.engine.dsp.FdnReverb
import jp.fmt.ekifu.engine.dsp.FeedbackDelay
import jp.fmt.ekifu.engine.dsp.MixBus
import jp.fmt.ekifu.engine.dsp.PadVoice
import jp.fmt.ekifu.engine.dsp.PluckVoice
import jp.fmt.ekifu.engine.dsp.Voice
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

/** 画面表示・デバッグ用の状態。 */
data class EngineStatus(
    val audioElapsedSeconds: Double,
    val journey: JourneySnapshot,
    val phase: Phase,
    /** 地上 0 〜 地下 1 のなめらかな値。 */
    val undergroundMix: Double,
    val finished: Boolean,
)

/**
 * 作曲器とシンセをつなぎ、PCM を連続して書き出す。
 * シンセの状態（鳴っている音、ディレイ・リバーブ・フィルター）は呼び出しをまたいで保持するので、
 * 任意の長さに区切って [render] しても音はつながる。
 */
class SoundEngine(
    route: Route,
    private val journey: JourneySource,
    seed: Int,
    private val sampleRate: Int = C.SAMPLE_RATE,
) {
    private val composer = Composer(seed)
    private val motifs = route.stations.mapIndexed { i, s -> StationMotif.generate(s.name, i == route.stations.lastIndex) }

    private val samplesPerBeat = C.BEAT_SECONDS * sampleRate
    private var samplePosition = 0L
    private var nextBeat = 0L
    private var lastPassedStation = -1

    private val pending = ArrayDeque<ScheduledNote>()
    private val voices = ArrayList<Voice>()
    private val bus = MixBus(C.CONTROL_BLOCK_FRAMES)

    private val delay = FeedbackDelay(sampleRate, C.DELAY_SECONDS, C.DELAY_FEEDBACK, C.DELAY_LOWPASS_HZ)
    private val reverb = FdnReverb(sampleRate, C.REVERB_DECAY_SECONDS, C.REVERB_DAMPING_HZ, C.REVERB_OUTPUT_GAIN)
    private val reverbOut = DoubleArray(2)
    private val masterLowpassLeft = BiquadLowpass(sampleRate)
    private val masterLowpassRight = BiquadLowpass(sampleRate)
    private val compressor = Compressor(
        sampleRate, C.COMPRESSOR_THRESHOLD_DB, C.COMPRESSOR_RATIO,
        C.COMPRESSOR_ATTACK_SECONDS, C.COMPRESSOR_RELEASE_SECONDS, C.COMPRESSOR_MAKEUP_DB,
    )

    private var undergroundTarget = 0.0
    private var undergroundMix = 0.0
    private var reverbMix = C.REVERB_MIX_ABOVE
    private val undergroundSmoothing =
        1 - exp(-C.CONTROL_BLOCK_FRAMES / (C.UNDERGROUND_SMOOTHING_SECONDS * sampleRate))

    private var fadeStartSample = -1L
    private val fadeSamples = (C.ARRIVAL_FADE_SECONDS * sampleRate).roundToLong()

    private var lastSnapshot = journey.snapshot(0.0)
    private var lastPhase = Phase.DEPARTURE

    val finished: Boolean get() = fadeStartSample >= 0 && samplePosition >= fadeStartSample + fadeSamples

    val status: EngineStatus
        get() = EngineStatus(
            audioElapsedSeconds = samplePosition.toDouble() / sampleRate,
            journey = lastSnapshot,
            phase = composer.activePhase ?: lastPhase,
            undergroundMix = undergroundMix,
            finished = finished,
        )

    /** ステレオ 16bit のインターリーブで [frames] フレーム書く。終了後は無音で埋める。 */
    fun render(out: ShortArray, offsetFrames: Int, frames: Int) {
        var done = 0
        while (done < frames) {
            if (samplePosition >= beatSample(nextBeat)) composeNextBeat()
            // ブロックの区切りを絶対位置にそろえ、呼び出し側の区切り方で音が変わらないようにする
            val untilGrid = C.CONTROL_BLOCK_FRAMES - samplePosition % C.CONTROL_BLOCK_FRAMES
            val untilBeat = (beatSample(nextBeat) - samplePosition).coerceAtLeast(1L)
            val block = minOf(untilGrid, (frames - done).toLong(), untilBeat).toInt()
            renderBlock(out, (offsetFrames + done) * C.CHANNELS, block)
            done += block
        }
    }

    private fun beatSample(beat: Long): Long = (beat * samplesPerBeat).roundToLong()

    private fun composeNextBeat() {
        val snapshot = journey.snapshot(samplePosition.toDouble() / sampleRate)
        lastSnapshot = snapshot
        lastPhase = Phase.fromProgress(snapshot.progress)
        undergroundTarget = if (snapshot.underground) 1.0 else 0.0

        var motif: List<Int>? = null
        if (snapshot.lastPassedStationIndex > lastPassedStation) {
            // 複数の駅を一度に通過した場合は、最後の駅だけ鳴らす
            lastPassedStation = snapshot.lastPassedStationIndex
            motif = motifs.getOrNull(lastPassedStation)
        }
        val wasArrived = composer.arrived
        val events = composer.composeBeat(nextBeat, BeatContext(snapshot.progress, snapshot.underground, motif))
        if (!wasArrived && composer.arrived) fadeStartSample = beatSample(nextBeat)
        for (e in events) schedule(e)
        nextBeat++
    }

    private fun schedule(event: NoteEvent) {
        val start = (event.beat * samplesPerBeat).roundToLong()
        val note = ScheduledNote(start, event)
        // 拍の順に作曲するのでほぼ整列済み。挿入位置だけ後ろから探す
        var index = pending.size
        while (index > 0 && pending[index - 1].startSample > start) index--
        pending.add(index, note)
    }

    private fun startVoice(note: NoteEvent) {
        val hold = (note.durationBeats * samplesPerBeat).roundToLong()
        voices += when (note.instrument) {
            Instrument.PAD -> PadVoice(sampleRate, note.midi, note.velocity, note.pan, hold)
            Instrument.BASS -> BassVoice(sampleRate, note.midi, note.velocity, hold)
            Instrument.PLUCK -> PluckVoice(sampleRate, note.midi, note.velocity, note.pan)
            Instrument.BELL -> BellVoice(sampleRate, note.midi, note.velocity, note.pan)
        }
    }

    private fun renderBlock(out: ShortArray, outIndex: Int, frames: Int) {
        val blockEnd = samplePosition + frames
        while (pending.isNotEmpty() && pending.first().startSample < blockEnd) {
            startVoice(pending.removeFirst().event)
        }

        bus.clear(frames)
        for (v in voices) v.render(bus, frames)
        voices.removeAll { it.finished }

        // 制御値は格子の頭でだけ更新する（区切り方によらず同じ音にするため）
        if (samplePosition % C.CONTROL_BLOCK_FRAMES == 0L) updateControls()

        var o = outIndex
        for (i in 0 until frames) {
            val delayWet = delay.process(bus.delaySend[i]) * C.DELAY_WET
            var left = bus.left[i] + delayWet
            var right = bus.right[i] + delayWet
            reverb.process(bus.reverbSend[i] + delayWet, reverbOut)
            left += reverbOut[0] * reverbMix
            right += reverbOut[1] * reverbMix

            left = masterLowpassLeft.process(left)
            right = masterLowpassRight.process(right)
            val gain = compressor.gainFor(left, right) * fadeGain(samplePosition + i) * C.MASTER_GAIN
            out[o++] = toPcm(left * gain)
            out[o++] = toPcm(right * gain)
        }
        samplePosition = blockEnd
    }

    private fun updateControls() {
        undergroundMix += (undergroundTarget - undergroundMix) * undergroundSmoothing
        val cutoff = exp(lerp(ln(C.MASTER_LOWPASS_ABOVE_HZ), ln(C.MASTER_LOWPASS_UNDERGROUND_HZ), undergroundMix))
        masterLowpassLeft.set(cutoff, C.MASTER_LOWPASS_Q)
        masterLowpassRight.set(cutoff, C.MASTER_LOWPASS_Q)
        reverbMix = lerp(C.REVERB_MIX_ABOVE, C.REVERB_MIX_UNDERGROUND, undergroundMix)
    }

    private fun fadeGain(sample: Long): Double {
        if (fadeStartSample < 0 || sample < fadeStartSample) return 1.0
        val t = (sample - fadeStartSample).toDouble() / fadeSamples
        if (t >= 1) return 0.0
        // 終わりに向かってなめらかに消える（cos カーブ）
        return 0.5 * (1 + kotlin.math.cos(Math.PI * t))
    }

    private fun toPcm(x: Double): Short = (x.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private class ScheduledNote(val startSample: Long, val event: NoteEvent)
}
