package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C

/** 再生位置での状態（画面・通知の表示用） */
data class StreamStatus(
    /** 再生位置（書き出し済みの位置。実際に聞こえるのは AudioTrack のバッファぶん後） */
    val playSec: Double,
    /** 再生位置で鳴っている曲の状態 */
    val engine: EngineStatus?,
    /** 再生待ちの長さ */
    val bufferedSec: Double,
    /** バッファ不足の回数 */
    val underruns: Int,
    /** 停止ボタンが押され、フェードアウト中 */
    val ending: Boolean,
    /** フェードアウトも終わった */
    val finished: Boolean,
)

/**
 * チャンク生成とバッファ（7章）。
 *
 * - 合成スレッドが [work] で10秒のチャンクを先回りして作り、再生待ちを30〜60秒に保つ
 *   （再生開始と作り直しの最初のチャンクだけ2秒）
 * - 書き込みスレッドが [read] で PCM を取り出して AudioTrack に渡す
 * - 各チャンクの頭でエンジン（作曲・合成）の状態を複製して持っておく。
 *   [post] で状態が変わったら、再生位置から [C.REWIND_MARGIN_SEC] 以上先のチャンクを捨て、
 *   その頭の状態から作り直す（反映の遅れは最大で約11秒）
 * - 合成が追いつかないときは無音を挟まず、直前の和音のパッドを伸ばしてつなぐ
 * - [stop] では作り置きの音を消しながら I を鳴らし、約12秒でフェードアウトする
 */
class ChunkStream(engine: SoundEngine) {

    private class Chunk(
        val start: Long,
        val frames: Int,
        val pcm: ShortArray,
        /** このチャンクの頭でのエンジンの状態（作り直し用） */
        val snapshot: SoundEngine,
        /** STATUS_INTERVAL ごとの状態 */
        val statuses: List<EngineStatus>,
    ) {
        val end get() = start + frames
    }

    val sampleRate = engine.sampleRate
    private val lock = Object()

    private val chunkFrames = sec(C.CHUNK_SEC)
    private val firstChunkFrames = sec(C.FIRST_CHUNK_SEC)
    private val lowFrames = sec(C.BUFFER_LOW_SEC)
    private val highFrames = sec(C.BUFFER_HIGH_SEC)
    private val marginFrames = sec(C.REWIND_MARGIN_SEC)
    private val statusFrames = sec(C.STATUS_INTERVAL_SEC)
    private val crossfadeFrames = sec(C.UNDERRUN_CROSSFADE_SEC)
    private val endingBufferFadeFrames = sec(C.ENDING_BUFFER_FADE_SEC)
    private val endingFrames = sec(C.ENDING_FADE_SEC)

    // ---- lock で守る ----
    private val chunks = ArrayDeque<Chunk>()
    private var live = engine
    private var writeFrame = engine.frame
    private var readFrame = engine.frame
    private var shortNext = true
    private var filling = true
    private val inputs = ArrayList<(ComposerInput) -> Unit>()
    private var lastStatus: EngineStatus? = null
    private var started = false
    private var underruns = 0
    private var inUnderrun = false
    private var filler: Renderer? = null
    private var crossfadeLeft = 0
    private var ending: Renderer? = null
    private var endingDone = 0
    private var finished = false
    private var mixBuf = ShortArray(0)

    // ---------------- 合成スレッド ----------------

    /**
     * 入力の反映か、チャンク1つぶんの合成を行う。何もすることがなければ false。
     * 合成スレッドからだけ呼ぶ。
     */
    fun work(): Boolean {
        val engine: SoundEngine
        val frames: Int
        val start: Long
        synchronized(lock) {
            val applied = applyInputsLocked()
            if (!canSynthesizeLocked()) return applied
            frames = nextChunkFramesLocked()
            shortNext = false
            engine = live
            start = writeFrame
        }

        // 重い処理は lock の外で。live とチャンク列を書き換えるのはこのスレッドだけ
        val snapshot = engine.copy()
        val pcm = ShortArray(frames * 2)
        val statuses = ArrayList<EngineStatus>(frames / statusFrames + 1)
        var done = 0
        while (done < frames) {
            statuses += engine.status()
            val n = minOf(statusFrames, frames - done)
            engine.render(pcm, n, done)
            done += n
        }

        synchronized(lock) {
            chunks.addLast(Chunk(start, frames, pcm, snapshot, statuses))
            writeFrame = start + frames
            lock.notifyAll()
        }
        return true
    }

    /** 合成することがないあいだ待つ（post・read・stop で起こされる） */
    fun awaitWork(timeoutMs: Long) {
        synchronized(lock) {
            if (!needsWorkLocked()) lock.wait(timeoutMs)
        }
    }

    fun needsWork(): Boolean = synchronized(lock) { needsWorkLocked() }

    private fun needsWorkLocked() = inputs.isNotEmpty() || canSynthesizeLocked()

    private fun canSynthesizeLocked(): Boolean {
        if (ending != null || finished) return false
        val ahead = writeFrame - readFrame
        if (ahead < lowFrames) filling = true
        if (filling && ahead + nextChunkFramesLocked() > highFrames) filling = false
        return filling
    }

    private fun nextChunkFramesLocked() = if (shortNext) firstChunkFrames else chunkFrames

    /** 入力をエンジンに反映する。先回りしたチャンクがあれば捨てて、その頭の状態から作り直す */
    private fun applyInputsLocked(): Boolean {
        if (inputs.isEmpty()) return false
        val cut = chunks.indexOfFirst { it.start >= readFrame + marginFrames }
        if (cut >= 0) {
            val c = chunks[cut]
            live = c.snapshot
            writeFrame = c.start
            while (chunks.size > cut) chunks.removeLast()
            shortNext = true
            filling = true
        }
        inputs.forEach { live.apply(it) }
        inputs.clear()
        return true
    }

    // ---------------- 他のスレッドから ----------------

    /** 作曲への入力（場面・地点）。次に合成するチャンクから反映する */
    fun post(action: (ComposerInput) -> Unit) {
        synchronized(lock) {
            if (ending != null || finished) return
            inputs += action
            lock.notifyAll()
        }
    }

    /** 停止ボタン：今の再生位置から I を鳴らして約12秒でフェードアウトする */
    fun stop() {
        synchronized(lock) {
            if (ending != null || finished) return
            val st = statusAtLocked(readFrame) ?: live.status()
            ending = live.endingRenderer(st)
            endingDone = 0
            inputs.clear()
            lock.notifyAll()
        }
    }

    fun status(): StreamStatus = synchronized(lock) {
        var engine = statusAtLocked(readFrame) ?: lastStatus
        if (ending != null && engine != null) {
            engine = engine.copy(composer = engine.composer.copy(phase = Phase.ENDING), stopping = true)
        }
        StreamStatus(
            playSec = readFrame.toDouble() / sampleRate,
            engine = engine,
            bufferedSec = (writeFrame - readFrame).toDouble() / sampleRate,
            underruns = underruns,
            ending = ending != null,
            finished = finished,
        )
    }

    // ---------------- 書き込みスレッド ----------------

    /**
     * frames 個のステレオフレームを out に書き、書いたフレーム数を返す。
     * 再生開始前で最初のチャンクがまだなら 0、フェードアウトが終わったら -1。
     */
    fun read(out: ShortArray, frames: Int): Int = synchronized(lock) {
        if (finished) return -1
        var n = copyBufferedLocked(out, 0, frames)

        val end = ending
        if (end != null) {
            out.fill(0, n * 2, frames * 2)
            mixEndingLocked(end, out, frames)
            n = frames
        } else {
            if (n == 0 && !started) return 0
            started = true
            n = fillGapLocked(out, n, frames)
        }

        if (writeFrame - readFrame < lowFrames) lock.notifyAll()
        n
    }

    private fun copyBufferedLocked(out: ShortArray, offset: Int, frames: Int): Int {
        var n = offset
        while (n < frames) {
            val c = chunks.firstOrNull() ?: break
            val pos = (readFrame - c.start).toInt()
            val k = minOf(frames - n, c.frames - pos)
            System.arraycopy(c.pcm, pos * 2, out, n * 2, k * 2)
            n += k
            readFrame += k
            if (readFrame >= c.end) {
                lastStatus = c.statuses.last()
                chunks.removeFirst()
            }
        }
        return n
    }

    /** バッファ不足のあいだは直前の和音のパッドでつなぎ、戻るときはクロスフェードする */
    private fun fillGapLocked(out: ShortArray, n: Int, frames: Int): Int {
        if (n < frames) {
            if (!inUnderrun) {
                inUnderrun = true
                underruns++
                crossfadeLeft = 0
                filler = makeFillerLocked()
            }
            filler!!.render(out, frames - n, n)
            return frames
        }
        if (inUnderrun) {
            inUnderrun = false
            crossfadeLeft = crossfadeFrames
        }
        val f = filler
        if (crossfadeLeft > 0 && f != null) {
            val tmp = mixBuffer(frames)
            f.render(tmp, frames, 0)
            for (i in 0 until frames) {
                if (crossfadeLeft <= 0) break
                val g = crossfadeLeft.toDouble() / crossfadeFrames
                for (ch in 0..1) {
                    val j = i * 2 + ch
                    out[j] = clamp(out[j] * (1 - g) + tmp[j] * g)
                }
                crossfadeLeft--
            }
            if (crossfadeLeft <= 0) filler = null
        }
        return frames
    }

    private fun makeFillerLocked(): Renderer {
        val st = lastStatus ?: statusAtLocked(readFrame) ?: live.status()
        return live.fillerRenderer(st)
    }

    /** 作り置きの音を消しながら、終わりの I を重ねる */
    private fun mixEndingLocked(end: Renderer, out: ShortArray, frames: Int) {
        val tmp = mixBuffer(frames)
        end.render(tmp, frames, 0)
        for (i in 0 until frames) {
            val gOld = (1.0 - (endingDone + i).toDouble() / endingBufferFadeFrames).coerceIn(0.0, 1.0)
            for (ch in 0..1) {
                val j = i * 2 + ch
                out[j] = clamp(out[j] * gOld + tmp[j])
            }
        }
        endingDone += frames
        if (endingDone >= endingFrames) finished = true
    }

    private fun statusAtLocked(frame: Long): EngineStatus? {
        val c = chunks.firstOrNull { frame >= it.start && frame < it.end } ?: return null
        val i = ((frame - c.start) / statusFrames).toInt().coerceIn(0, c.statuses.size - 1)
        return c.statuses[i]
    }

    private fun mixBuffer(frames: Int): ShortArray {
        if (mixBuf.size < frames * 2) mixBuf = ShortArray(frames * 2)
        return mixBuf
    }

    private fun clamp(v: Double): Short = v.toInt().coerceIn(-32768, 32767).toShort()

    private fun sec(s: Double) = (s * sampleRate).toInt()
}
