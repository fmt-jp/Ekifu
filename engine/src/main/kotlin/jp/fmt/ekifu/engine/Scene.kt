package jp.fmt.ekifu.engine

/** 曲の状態（4章） */
enum class Phase(val label: String) {
    START("始まり"),
    JOURNEY("道中"),
    INTERLUDE("区切り"),
    APPROACH("接近"),
    ARRIVE("到着"),
    STAY("滞在"),
    ENDING("終わり"),
}

/** 移動の速さ（5章） */
enum class Speed(val label: String, val beatSec: Double, val noteProb: Double) {
    STILL("止まっている", MusicConstants.STILL_BEAT_SEC, MusicConstants.STILL_PROB),
    WALK("歩いている", MusicConstants.WALK_BEAT_SEC, MusicConstants.WALK_PROB),
    VEHICLE("乗り物で移動中", MusicConstants.VEHICLE_BEAT_SEC, MusicConstants.VEHICLE_PROB),
}

/** なじみ度（5章） */
enum class Familiarity(val label: String) {
    /** 初めての場所：高音のベルの装飾を加える */
    NEW("初めての場所"),
    NORMAL("ときどき来る場所"),
    /** 10日以上訪れた場所：パッド中心で音数を減らす */
    FAMILIAR("なじみの場所"),
}

/** 太陽の高さ（5章） */
enum class SunLevel(val label: String, val cutoffHz: Double, val chordShift: Int) {
    NIGHT("夜", MusicConstants.NIGHT_CUTOFF_HZ, MusicConstants.NIGHT_CHORD_SHIFT),
    TWILIGHT("薄明", MusicConstants.TWILIGHT_CUTOFF_HZ, 0),
    DAY("昼", MusicConstants.DAY_CUTOFF_HZ, 0),
}

/** 地点の雰囲気（6章）。null の項目は場面の値をそのまま使う。 */
enum class Mood(
    val label: String,
    val beatSec: Double?,
    val cutoffHz: Double?,
    val probFactor: Double,
    val delayFeedback: Double?,
    val ornamentProb: Double,
    val melodyExtraSemitones: Int,
) {
    CALM("落ち着く", MusicConstants.CALM_BEAT_SEC, MusicConstants.CALM_CUTOFF_HZ, MusicConstants.CALM_PROB_FACTOR, null, 0.0, 0),
    BRIGHT("明るい", MusicConstants.BRIGHT_BEAT_SEC, null, 1.0, null, MusicConstants.BRIGHT_ORNAMENT_PROB, MusicConstants.BRIGHT_MELODY_EXTRA_SEMITONES),
    NOSTALGIC("懐かしい", null, null, 1.0, MusicConstants.NOSTALGIC_DELAY_FEEDBACK, 0.0, 0),
}

/** 約5分ごとの「場面」（5章）。gridId はジオハッシュ6桁（段階1ではデモ用の架空の値）。 */
data class Scene(
    val gridId: String,
    val speed: Speed,
    val familiarity: Familiarity,
    val sun: SunLevel,
) {
    val label: String get() = "${speed.label}・${familiarity.label}・${sun.label}"

    companion object {
        val DEFAULT = Scene("xn76ur", Speed.WALK, Familiarity.NORMAL, SunLevel.DAY)
    }
}

/** 登録地点（6章のうち音に関わる項目） */
data class Place(
    val id: String,
    val name: String,
    val mood: Mood,
    val themeSeed: Int,
)
