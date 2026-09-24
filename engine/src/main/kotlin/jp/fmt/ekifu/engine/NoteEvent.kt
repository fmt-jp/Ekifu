package jp.fmt.ekifu.engine

enum class Instrument { PAD, BASS, PLUCK, BELL }

/**
 * 作曲結果の 1 音。時間は拍単位（曲頭からの絶対拍）。
 * [pan] は -1（左）〜 1（右）。
 */
data class NoteEvent(
    val beat: Double,
    val instrument: Instrument,
    val midi: Int,
    val durationBeats: Double,
    val velocity: Double,
    val pan: Double = 0.0,
)
