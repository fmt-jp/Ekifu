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
    /** 和音進行と発音の多さを決めるフェーズ。ルートがあるときは p から決まる。 */
    val phase: Phase = Phase.fromProgress(progress),
    /** 最後に着いた場所（駅や、ルートなしモードの区画）。変わったらそのモチーフを鳴らす。 */
    val landmark: Landmark? = null,
    /** 音のこもり具合 0〜1（時間帯による。地下のときは別に 1 になる）。 */
    val darkness: Double = 0.0,
    /** ルートなしモードの移動状態（ルートがあるときは null）。 */
    val motion: Motion? = null,
)

/**
 * モチーフを鳴らす場所。[sequence] が増えたら新しく着いたとみなす。
 * モチーフは [motifKey] から決まるので、同じ場所なら何度来ても同じメロディになる。
 */
data class Landmark(
    val sequence: Int,
    val name: String,
    val motifKey: String,
    /** 終点（モチーフを主音で終わらせる）。 */
    val isTerminal: Boolean = false,
)

/** ルート上の [index] 番目の駅を場所として表す（まだどこも通過していなければ null）。 */
fun Route.stationLandmark(index: Int): Landmark? {
    val station = stations.getOrNull(index) ?: return null
    return Landmark(index, station.name, station.name, isTerminal = index == stations.lastIndex)
}

/** 経過時間（再生した音の長さ）から旅程の状態を返す。 */
fun interface JourneySource {
    fun snapshot(audioElapsedSeconds: Double): JourneySnapshot
}

/** 路線図に描くための旅程の見取り図。 */
interface JourneyPlan : JourneySource {
    val route: Route
    /** 各駅に着くときの進み具合 p（先頭は 0、終点は 1）。 */
    val stationProgress: List<Double>
    /** 地下区間（p の範囲）。 */
    val undergroundRanges: List<ClosedFloatingPointRange<Double>>
}

/**
 * 開発用のデモ再生（SPEC 8章）。18 分のルートを 3 分に縮め、位置は時刻表どおりに擬似的に進める。
 */
class DemoJourney(
    override val route: Route,
    /** 各駅の到着時刻（ルート上の分）。 */
    val stationMinutes: List<Double>,
    /** 地下区間（p の範囲）。 */
    val undergroundRange: ClosedFloatingPointRange<Double>,
    val playbackMinutes: Double,
) : JourneyPlan {

    init {
        require(stationMinutes.size == route.stations.size)
    }

    override val stationProgress: List<Double> = stationMinutes.map { it / route.expectedMinutes }
    override val undergroundRanges: List<ClosedFloatingPointRange<Double>> = listOf(undergroundRange)

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
            landmark = route.stationLandmark(lastPassed),
        )
    }

    companion object {
        private const val EPSILON = 1e-9

        /** 通常のデモ（3 分）。 */
        const val SHORT_PLAYBACK_MINUTES = 3.0
        /** 画面オフで長時間鳴らし続ける確認用（同じルートを 30 分かけて進む）。 */
        const val LONG_PLAYBACK_MINUTES = 30.0

        fun create(playbackMinutes: Double = SHORT_PLAYBACK_MINUTES): DemoJourney {
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
                route = Route("デモ（18分→${playbackMinutes.toInt()}分）", expected, stations),
                stationMinutes = minutes,
                undergroundRange = range,
                playbackMinutes = playbackMinutes,
            )
        }
    }
}
