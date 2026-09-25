package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.MusicConstants as C

/** 場面を決めた結果（画面のデバッグ表示用の情報つき） */
data class SceneDecision(
    val scene: Scene,
    /** 前回の位置から求めた速さ。求められなかったときは null */
    val speedKmh: Double?,
    /** 今回の位置が取れたか（取れなければ直前の場面を続けている） */
    val located: Boolean,
)

/**
 * 約5分ごとの位置から場面を決める（5章）。
 * 覚えておくのは直前の位置と場面だけ（保存はしない）。
 */
class SceneDecider {

    private var lastFix: LocationFix? = null
    private var lastDecision: SceneDecision? = null

    /**
     * @param fix 今回の位置。地下や屋内で取れなかったときは null
     * @param visitDays マスIDを訪れた日数（今日を含む）
     * @return 決まった場面。まだ一度も位置が取れていなければ null
     */
    fun decide(fix: LocationFix?, nowMs: Long, visitDays: (String) -> Int): SceneDecision? {
        val prev = lastDecision
        if (fix == null) {
            // 直前の場面を続け、速さも直前の値を保つ。太陽の高さだけは時刻で進める
            val base = lastFix ?: return null
            val scene = prev!!.scene.copy(sun = Sun.level(base.lat, base.lng, nowMs))
            return SceneDecision(scene, prev.speedKmh, located = false).also { lastDecision = it }
        }

        val speedKmh = lastFix?.let { p ->
            val hours = (fix.timeMs - p.timeMs) / 3_600_000.0
            if (hours > 0) Geo.distanceM(p, fix) / 1000.0 / hours else null
        }
        val speed = speedKmh?.let { speedOf(it) } ?: prev?.scene?.speed ?: Speed.WALK
        val grid = Geo.geohash(fix.lat, fix.lng)
        val scene = Scene(
            gridId = grid,
            speed = speed,
            familiarity = familiarityOf(visitDays(grid)),
            sun = Sun.level(fix.lat, fix.lng, nowMs),
        )
        lastFix = fix
        return SceneDecision(scene, speedKmh ?: prev?.speedKmh, located = true).also { lastDecision = it }
    }

    companion object {
        fun speedOf(kmh: Double): Speed = when {
            kmh < C.STILL_MAX_KMH -> Speed.STILL
            kmh <= C.WALK_MAX_KMH -> Speed.WALK
            else -> Speed.VEHICLE
        }

        /** 訪れた日数（今日を含む）：1日＝初めて、2〜9日＝ときどき、10日以上＝なじみ */
        fun familiarityOf(days: Int): Familiarity = when {
            days <= 1 -> Familiarity.NEW
            days < C.FAMILIAR_DAYS -> Familiarity.NORMAL
            else -> Familiarity.FAMILIAR
        }

        /**
         * 位置の許可がないとき：経過時間5分ごとに架空の場所へ移る。
         * 太陽の高さは緯度35度・端末のタイムゾーンから概算した経度で求める。
         */
        fun withoutLocation(sessionId: Long, sceneIndex: Int, nowMs: Long, tzOffsetMs: Int): Scene {
            val lng = tzOffsetMs / 3_600_000.0 * 15.0
            return Scene(
                gridId = "nolocation-$sessionId-$sceneIndex",
                speed = Speed.WALK,
                familiarity = Familiarity.NORMAL,
                sun = Sun.level(C.NO_LOCATION_LATITUDE, lng, nowMs),
            )
        }
    }
}
