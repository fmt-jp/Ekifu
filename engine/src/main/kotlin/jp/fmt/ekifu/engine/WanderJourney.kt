package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/** ルートなしモードの移動状態。移動が多いほど音数の多いフェーズを使う。 */
enum class Motion(val phase: Phase) {
    /** 止まっている：出発フェーズ（I → IV、静か）。 */
    STILL(Phase.DEPARTURE),
    /** 歩いている：到着前フェーズ（IV → Vsus → IV → I、ほどほど）。 */
    WALK(Phase.PRE_ARRIVAL),
    /** 乗り物に乗っている：道中フェーズ（8 和音の進行、にぎやか）。 */
    RIDE(Phase.MIDDLE),
}

/** 位置情報 1 回ぶん。 */
data class LocationSample(
    val timeMillis: Long,
    val lat: Double,
    val lng: Double,
    /** 位置の誤差（メートル）。 */
    val accuracyMeters: Double,
)

/**
 * 地図を約 500m 四方に区切った区画。区画ごとに固有のモチーフを持つ。
 * 経度方向の幅は緯度で変わるので、区画の行の中心の緯度で決める。
 */
data class PlaceCell(val row: Long, val col: Long) {
    val key: String get() = "cell:$row:$col"

    val centerLat: Double get() = (row + 0.5) * C.PLACE_CELL_METERS / METERS_PER_DEGREE
    val centerLng: Double get() = (col + 0.5) * C.PLACE_CELL_METERS / metersPerDegreeLng(centerLat)

    companion object {
        private const val METERS_PER_DEGREE = 111_320.0

        private fun metersPerDegreeLng(lat: Double) = METERS_PER_DEGREE * max(cos(lat * PI / 180), 0.01)

        fun of(lat: Double, lng: Double): PlaceCell {
            val row = floor(lat * METERS_PER_DEGREE / C.PLACE_CELL_METERS).toLong()
            val rowCenterLat = (row + 0.5) * C.PLACE_CELL_METERS / METERS_PER_DEGREE
            val col = floor(lng * metersPerDegreeLng(rowCenterLat) / C.PLACE_CELL_METERS).toLong()
            return PlaceCell(row, col)
        }
    }
}

/**
 * ルートを決めずに、ときどき取る位置情報から曲を作るモード。
 *
 * - 場所：新しい区画に入ったら、その区画のモチーフをベルで鳴らす（同じ場所なら毎回同じメロディ）
 * - 移動の速さ：前回の位置からの速さで [Motion] を決め、和音進行と発音の多さを変える
 * - 時間帯：夜や早朝ほど音をこもらせ、残響を深くする
 * - 終わりはなく、停止するまで続く。地上/地下は使わない
 *
 * [onLocation] は位置情報のスレッドから、[snapshot] は合成スレッドから呼ばれる。
 */
class WanderJourney(
    /** いまの時刻（0〜24 時、分は小数）。テストでは固定値を渡す。 */
    private val hourOfDay: () -> Double,
) : JourneySource {

    private data class State(
        val motion: Motion = Motion.STILL,
        val cell: PlaceCell? = null,
        val landmark: Landmark? = null,
        val placesVisited: Int = 0,
    )

    @Volatile private var state = State()
    /** 速さを測る基準の位置。 */
    private var anchor: LocationSample? = null

    val motion: Motion get() = state.motion
    val currentCell: PlaceCell? get() = state.cell

    /**
     * 新しい位置を受け取る。曲に関わる変化（区画・移動状態）があれば true を返すので、
     * 呼び出し側は先読みした音を作り直す。
     */
    @Synchronized
    fun onLocation(sample: LocationSample): Boolean {
        val before = state
        var motion = before.motion
        val base = anchor
        if (base == null) {
            anchor = sample
        } else {
            val seconds = (sample.timeMillis - base.timeMillis) / 1000.0
            if (seconds >= C.MOTION_MIN_SAMPLE_SECONDS) {
                // 位置の誤差ぶんは動いたとみなさない
                val meters = TimeOnlyJourney.distanceMeters(base.lat, base.lng, sample.lat, sample.lng)
                val moved = (meters - base.accuracyMeters - sample.accuracyMeters).coerceAtLeast(0.0)
                motion = classify(moved / seconds)
                anchor = sample
            }
        }

        val cell = PlaceCell.of(sample.lat, sample.lng)
        val newPlace = cell != before.cell
        val landmark = if (newPlace) {
            Landmark(before.placesVisited, placeName(cell), cell.key)
        } else {
            before.landmark
        }
        state = State(
            motion = motion,
            cell = cell,
            landmark = landmark,
            placesVisited = before.placesVisited + if (newPlace) 1 else 0,
        )
        return newPlace || motion != before.motion
    }

    override fun snapshot(audioElapsedSeconds: Double): JourneySnapshot {
        val s = state
        return JourneySnapshot(
            progress = 0.0,
            underground = false,
            lastPassedStationIndex = -1,
            remainingSeconds = 0.0,
            routeElapsedSeconds = audioElapsedSeconds,
            phase = s.motion.phase,
            landmark = s.landmark,
            darkness = darknessAt(hourOfDay()),
            motion = s.motion,
        )
    }

    companion object {
        fun classify(speedMetersPerSecond: Double): Motion = when {
            speedMetersPerSecond < C.MOTION_WALK_MIN_SPEED -> Motion.STILL
            speedMetersPerSecond < C.MOTION_RIDE_MIN_SPEED -> Motion.WALK
            else -> Motion.RIDE
        }

        /** 時間帯のこもり具合（[C.DARKNESS_HOURS] の表を直線でつなぐ）。 */
        fun darknessAt(hour: Double): Double {
            val h = ((hour % 24.0) + 24.0) % 24.0
            val hours = C.DARKNESS_HOURS
            val values = C.DARKNESS_VALUES
            val i = (0 until hours.size - 1).first { h < hours[it + 1] }
            val t = (h - hours[i]) / (hours[i + 1] - hours[i])
            return values[i] + (values[i + 1] - values[i]) * t
        }

        private fun placeName(cell: PlaceCell) =
            "%.3f, %.3f 付近".format(java.util.Locale.US, cell.centerLat, cell.centerLng)
    }
}
