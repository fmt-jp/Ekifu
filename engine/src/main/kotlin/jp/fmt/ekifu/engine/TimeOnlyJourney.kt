package jp.fmt.ekifu.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 経過時間だけで進むモード（SPEC 5章：位置情報が使えないとき）。
 * 予定所要時間を駅間の直線距離で割り振って、各駅に着く時刻を見積もる。
 * 段階3では登録ルートの再生にこれを使い、段階4では位置の許可がないときの代わりになる。
 */
class TimeOnlyJourney(override val route: Route) : JourneyPlan {

    override val stationProgress: List<Double>
    override val undergroundRanges: List<ClosedFloatingPointRange<Double>>

    private val expectedSeconds = route.expectedMinutes * 60.0

    init {
        require(route.stations.size >= RouteValidation.MIN_STATIONS)
        val cumulative = DoubleArray(route.stations.size)
        for (i in 1 until route.stations.size) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(route.stations[i - 1], route.stations[i])
        }
        val total = cumulative.last()
        val last = route.stations.lastIndex
        // 座標が全部同じなど距離が測れないときは等間隔にする
        stationProgress = List(route.stations.size) { i ->
            if (total > 0) cumulative[i] / total else i.toDouble() / last
        }
        undergroundRanges = buildList {
            var start: Double? = null
            for (i in 0 until last) {
                val underground = route.stations[i].undergroundAfter
                if (underground && start == null) start = stationProgress[i]
                if (!underground && start != null) {
                    add(start..stationProgress[i])
                    start = null
                }
            }
            if (start != null) add(start..stationProgress[last])
        }
    }

    override fun snapshot(audioElapsedSeconds: Double): JourneySnapshot {
        val progress = audioElapsedSeconds / expectedSeconds
        val lastPassed = stationProgress.indexOfLast { it <= progress + EPSILON }
        val underground = progress < 1.0 && route.stations.getOrNull(lastPassed)?.undergroundAfter == true
        return JourneySnapshot(
            progress = progress,
            underground = underground,
            lastPassedStationIndex = lastPassed,
            remainingSeconds = (expectedSeconds - audioElapsedSeconds).coerceAtLeast(0.0),
            routeElapsedSeconds = audioElapsedSeconds,
            landmark = route.stationLandmark(lastPassed),
        )
    }

    companion object {
        private const val EPSILON = 1e-9
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** 2 駅間の大円距離（メートル）。 */
        fun distanceMeters(a: Station, b: Station): Double = distanceMeters(a.lat, a.lng, b.lat, b.lng)

        /** 2 地点間の大円距離（メートル）。 */
        fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val lat1 = Math.toRadians(aLat)
            val lat2 = Math.toRadians(bLat)
            val dLat = lat2 - lat1
            val dLng = Math.toRadians(bLng - aLng)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
