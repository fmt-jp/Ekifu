package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.BufferConstants as B
import jp.fmt.ekifu.engine.MusicConstants as C
import jp.fmt.ekifu.engine.dsp.MixBus
import jp.fmt.ekifu.engine.dsp.PadVoice
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 音を 10 秒単位のチャンクで先回りして作り、30〜60 秒ぶんを再生待ちに置く（SPEC 6章）。
 *
 * - 合成側（[produceOne]）と再生側（[read]）は別スレッドから呼んでよい
 * - 各チャンクは合成開始時点のエンジンの複製を持つ。[requestResync] されると、
 *   再生位置から [B.RESYNC_KEEP_SECONDS] より先のチャンクを捨て、その複製から合成し直す
 * - 合成が間に合わないときは無音を挟まず、直前のパッドを伸ばしてつなぐ
 */
class ChunkedStream(initialEngine: SoundEngine, private val sampleRate: Int = C.SAMPLE_RATE) {

    private class Chunk(
        val startFrame: Long,
        val pcm: ShortArray,
        val frames: Int,
        /** このチャンクを合成する直前のエンジン（作り直し用）。 */
        val engineAtStart: SoundEngine,
        /** チャンク末尾で鳴っていたパッド（音切れのつなぎ用）。 */
        val padsAtEnd: List<PadVoice>,
        val statusFrames: IntArray,
        val statuses: List<EngineStatus>,
    )

    private val lock = Object()

    // 合成スレッドだけが触る
    private var engine = initialEngine

    // 以下は lock で守る
    private val chunks = ArrayDeque<Chunk>()
    private var readOffset = 0
    private var producedEndFrame = 0L
    private var playheadFrame = 0L
    private var filling = true
    private var active = true
    private var resyncRequested = false
    private var engineFinished = false
    private var underruns = 0
    private var status = initialEngine.status
    private var lastPads: List<PadVoice> = emptyList()

    private val chunkFrames = frames(B.CHUNK_SECONDS)
    private val firstChunkFrames = frames(B.FIRST_CHUNK_SECONDS)
    private val lowWaterFrames = frames(B.LOW_WATER_SECONDS)
    private val highWaterFrames = frames(B.HIGH_WATER_SECONDS)
    private val resyncKeepFrames = frames(B.RESYNC_KEEP_SECONDS)
    private val statusIntervalFrames = frames(B.STATUS_INTERVAL_SECONDS)

    // 再生側だけが触る（lock の中）
    private val filler = SustainFiller(sampleRate)
    private var fillerActive = false
    private var switched = false
    private var lastLeft = 0.0
    private var lastRight = 0.0
    private var offsetLeft = 0.0
    private var offsetRight = 0.0
    private val declickDecay = exp(-1.0 / (B.DECLICK_SECONDS * sampleRate))

    private fun frames(seconds: Double) = (seconds * sampleRate).roundToInt()

    /** 再生待ちの長さ（フレーム）。 */
    val bufferedFrames: Long get() = synchronized(lock) { producedEndFrame - playheadFrame }

    /** 合成が間に合わず、つなぎを入れた回数。 */
    val underrunCount: Int get() = synchronized(lock) { underruns }

    /** 再生位置での状態。 */
    val playheadStatus: EngineStatus get() = synchronized(lock) { status }

    /** 曲が終わり、すべて再生し終えたか。 */
    val ended: Boolean get() = synchronized(lock) { engineFinished && chunks.isEmpty() }

    /** 最初のチャンクができているか（再生を始めてよいか）。 */
    val readyToPlay: Boolean get() = synchronized(lock) { chunks.isNotEmpty() || engineFinished }

    /** 一時停止中は false にして合成も止める。 */
    fun setActive(value: Boolean) = synchronized(lock) {
        active = value
        lock.notifyAll()
    }

    /** 旅程の状態が変わったので、再生位置の少し先から作り直してほしい。 */
    fun requestResync() = synchronized(lock) {
        resyncRequested = true
        lock.notifyAll()
    }

    /** 合成側が待つ。仕事があれば true。 */
    fun awaitWork(timeoutMs: Long): Boolean = synchronized(lock) {
        if (!hasWorkLocked()) lock.wait(timeoutMs)
        hasWorkLocked()
    }

    /** 待っているスレッドを起こす（終了時など）。 */
    fun wakeUp() = synchronized(lock) { lock.notifyAll() }

    private fun hasWorkLocked() = active && (resyncRequested || (filling && !engineFinished))

    /** チャンクを 1 つ合成して積む。重い処理はロックの外で行う。合成したら true。 */
    fun produceOne(): Boolean {
        val startFrame: Long
        val frames: Int
        synchronized(lock) {
            if (resyncRequested) applyResyncLocked()
            if (!active || !filling || engineFinished) return false
            startFrame = producedEndFrame
            frames = if (startFrame == 0L) firstChunkFrames else chunkFrames
        }

        val engineAtStart = engine.copy()
        val pcm = ShortArray(frames * C.CHANNELS)
        val statusFrames = ArrayList<Int>()
        val statuses = ArrayList<EngineStatus>()
        var done = 0
        while (done < frames) {
            val n = minOf(statusIntervalFrames, frames - done)
            engine.render(pcm, done, n)
            done += n
            statusFrames += done
            statuses += engine.status
        }
        val chunk = Chunk(
            startFrame, pcm, frames, engineAtStart, engine.padVoiceCopies(),
            statusFrames.toIntArray(), statuses,
        )

        synchronized(lock) {
            chunks.addLast(chunk)
            producedEndFrame = startFrame + frames
            engineFinished = engine.finished
            val buffered = producedEndFrame - playheadFrame
            if (buffered + chunkFrames > highWaterFrames) filling = false
        }
        return true
    }

    private fun applyResyncLocked() {
        resyncRequested = false
        val keepUntil = playheadFrame + resyncKeepFrames
        val firstDropped = chunks.indexOfFirst { it.startFrame >= keepUntil }
        if (firstDropped < 0) return
        engine = chunks[firstDropped].engineAtStart
        producedEndFrame = chunks[firstDropped].startFrame
        while (chunks.size > firstDropped) chunks.removeLast()
        engineFinished = false
        filling = true
    }

    /**
     * ステレオ 16bit のインターリーブで [frames] フレーム読み出す。
     * 合成が追いついていなければパッドを伸ばしてつなぐ。曲の終わり以降は無音。
     */
    fun read(out: ShortArray, offsetFrames: Int, frames: Int) = synchronized(lock) {
        var i = 0
        while (i < frames) {
            val chunk = chunks.firstOrNull()
            if (chunk == null) {
                val n = frames - i
                if (engineFinished) {
                    out.fill(0, (offsetFrames + i) * C.CHANNELS, (offsetFrames + frames) * C.CHANNELS)
                    lastLeft = 0.0; lastRight = 0.0; offsetLeft = 0.0; offsetRight = 0.0
                } else {
                    if (!fillerActive) {
                        underruns++
                        fillerActive = true
                        switched = true
                        filler.start(lastPads)
                    }
                    filler.render(out, offsetFrames + i, n)
                    declick(out, offsetFrames + i, n)
                }
                i = frames
                break
            }
            if (fillerActive) {
                fillerActive = false
                switched = true
            }
            val n = minOf(chunk.frames - readOffset, frames - i)
            chunk.pcm.copyInto(
                out,
                (offsetFrames + i) * C.CHANNELS,
                readOffset * C.CHANNELS,
                (readOffset + n) * C.CHANNELS,
            )
            declick(out, offsetFrames + i, n)
            readOffset += n
            playheadFrame += n
            i += n
            val statusIndex = chunk.statusFrames.indexOfLast { it <= readOffset }
            if (statusIndex >= 0) status = chunk.statuses[statusIndex]
            if (readOffset == chunk.frames) {
                lastPads = chunk.padsAtEnd
                chunks.removeFirst()
                readOffset = 0
            }
        }
        if (!filling && !engineFinished && producedEndFrame - playheadFrame < lowWaterFrames) {
            filling = true
            lock.notifyAll()
        }
    }

    /** 音源が切り替わった直後の段差を、数ミリ秒かけて消す。 */
    private fun declick(out: ShortArray, offsetFrames: Int, frames: Int) {
        var o = offsetFrames * C.CHANNELS
        if (switched) {
            switched = false
            offsetLeft = lastLeft - out[o]
            offsetRight = lastRight - out[o + 1]
        }
        if (offsetLeft == 0.0 && offsetRight == 0.0) {
            if (frames > 0) {
                lastLeft = out[o + (frames - 1) * C.CHANNELS].toDouble()
                lastRight = out[o + (frames - 1) * C.CHANNELS + 1].toDouble()
            }
            return
        }
        repeat(frames) {
            val l = (out[o] + offsetLeft).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
            val r = (out[o + 1] + offsetRight).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
            out[o] = l.toInt().toShort()
            out[o + 1] = r.toInt().toShort()
            lastLeft = l
            lastRight = r
            offsetLeft *= declickDecay
            offsetRight *= declickDecay
            if (kotlin.math.abs(offsetLeft) < 0.5 && kotlin.math.abs(offsetRight) < 0.5) {
                offsetLeft = 0.0
                offsetRight = 0.0
            }
            o += C.CHANNELS
        }
    }
}

/** 合成が間に合わないとき、直前のパッドをそのままの音量で伸ばす。 */
internal class SustainFiller(sampleRate: Int) {
    private var voices: List<PadVoice> = emptyList()
    private val bus = MixBus(C.CONTROL_BLOCK_FRAMES)

    fun start(pads: List<PadVoice>) {
        voices = pads.map { it.copy().apply { sustainForever() } }
    }

    fun render(out: ShortArray, offsetFrames: Int, frames: Int) {
        var done = 0
        var o = offsetFrames * C.CHANNELS
        while (done < frames) {
            val n = minOf(C.CONTROL_BLOCK_FRAMES, frames - done)
            bus.clear(n)
            for (v in voices) v.render(bus, n)
            for (i in 0 until n) {
                out[o++] = toPcm(bus.left[i] * B.FILLER_GAIN)
                out[o++] = toPcm(bus.right[i] * B.FILLER_GAIN)
            }
            done += n
        }
    }

    private fun toPcm(x: Double): Short = (x.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
}
