package jp.fmt.ekifu.engine

/** 作曲の結果（音符イベント）。時刻は再生開始からの秒。 */
sealed interface MusicEvent {
    val timeSec: Double
}

enum class Instrument { PAD, BASS, PLUCK, BELL }

/** durationSec が null の音は、ReleaseEvent を受けるまで鳴り続ける（パッド・ベース用） */
data class NoteEvent(
    override val timeSec: Double,
    val instrument: Instrument,
    val midi: Int,
    val gain: Double,
    val pan: Double = 0.0,
    val durationSec: Double? = null,
) : MusicEvent

/** 指定パートの鳴り続けている音を余韻に入らせる */
data class ReleaseEvent(
    override val timeSec: Double,
    val instruments: Set<Instrument>,
) : MusicEvent

/** 全体ローパスとディレイのフィードバックを rampSec かけて移す */
data class ControlEvent(
    override val timeSec: Double,
    val masterCutoffHz: Double,
    val delayFeedback: Double,
    val rampSec: Double,
) : MusicEvent

/** 全体の音量を durationSec かけて0にする（終わり） */
data class FadeOutEvent(
    override val timeSec: Double,
    val durationSec: Double,
) : MusicEvent
