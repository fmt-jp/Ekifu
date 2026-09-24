package jp.fmt.ekifu.engine

/** ある時点での旅程の状態。 */
data class JourneySnapshot(
    /** 進み具合 p（1 以上で到着）。 */
    val progress: Double,
    val underground: Boolean,
    /** 最後に通過した駅の番号（まだなら -1）。 */
    val lastPassedStationIndex: Int,
    /** 到着までの推定残り時間（秒、ルート上の時間）。 */
    val remainingSeconds: Double,
    /** 再生開始からのルート上の経過時間（秒）。 */
    val routeElapsedSeconds: Double,
)

/** 経過時間（再生した音の長さ）から旅程の状態を返す。段階4で位置連動版に差し替える。 */
fun interface JourneySource {
    fun snapshot(audioElapsedSeconds: Double): JourneySnapshot
}

/**
 * 開発用のデモ再生（SPEC 8章）。18 分のルートを 3 分に縮め、位置は時刻表どおりに擬似的に進める。
 */
class DemoJourney(
    val route: Route,
    /** 各駅の到着時刻（ルート上の分）。 */
    val stationMinutes: List<Double>,
    /** 地下区間（p の範囲）。 */
    val undergroundRange: ClosedFloatingPointRange<Double>,
    val playbackMinutes: Double,
) : JourneySource {

    init {
        require(stationMinutes.size == route.stations.size)
    }

    /** ルート上の時間の進む速さ（再生 1 秒あたりのルート秒）。 */
    val timeScale: Double get() = route.expectedMinutes / playbackMinutes

    override fun snapshot(audioElapsedSeconds: Double): JourneySnapshot {
        val routeSeconds = audioElapsedSeconds * timeScale
        val routeMinutes = routeSeconds / 60.0
        val expectedSeconds = route.expectedMinutes * 60.0
        val progress = routeSeconds / expectedSeconds
        val lastPassed = stationMinutes.indexOfLast { it <= routeMinutes + EPSILON }
        return JourneySnapshot(
            progress = progress,
            underground = progress >= undergroundRange.start && progress < undergroundRange.endInclusive,
            lastPassedStationIndex = lastPassed,
            remainingSeconds = (expectedSeconds - routeSeconds).coerceAtLeast(0.0),
            routeElapsedSeconds = routeSeconds,
        )
    }

    companion object {
        private const val EPSILON = 1e-9

        fun create(): DemoJourney {
            val names = listOf("若葉台", "桜坂", "川辺", "中央", "港町", "丘の上", "汐見")
            val minutes = listOf(0.0, 3.0, 6.0, 9.0, 12.0, 15.0, 18.0)
            val range = 0.27..0.72
            val expected = 18.0
            val stations = names.mapIndexed { i, name ->
                // 地下フラグは表示用。区間の始点が地下範囲にかかっていれば地下とみなす
                val start = minutes[i] / expected
                val end = minutes.getOrElse(i + 1) { expected } / expected
                Station(
                    name = name,
                    lat = 35.0 + i * 0.01,
                    lng = 139.0 + i * 0.02,
                    undergroundAfter = i < names.lastIndex && end > range.start && start < range.endInclusive,
                )
            }
            return DemoJourney(
                route = Route("デモ（18分→3分）", expected, stations),
                stationMinutes = minutes,
                undergroundRange = range,
                playbackMinutes = 3.0,
            )
        }
    }
}
