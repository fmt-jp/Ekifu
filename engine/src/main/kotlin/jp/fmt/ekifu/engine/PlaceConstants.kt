package jp.fmt.ekifu.engine

/** 登録地点と接近の判定のパラメータ（6章） */
object PlaceConstants {
    /** 登録できる地点の数 */
    const val MAX_PLACES = 20

    /** 接近（外側の円）の半径 */
    const val APPROACH_RADIUS_MIN_M = 200
    const val APPROACH_RADIUS_MAX_M = 1000
    const val APPROACH_RADIUS_DEFAULT_M = 500
    /** スライダーの刻み（仕様外・要調整） */
    const val APPROACH_RADIUS_STEP_M = 50

    /** 到着（内側の円）の半径 */
    const val ARRIVE_RADIUS_MIN_M = 50
    const val ARRIVE_RADIUS_MAX_M = 300
    const val ARRIVE_RADIUS_DEFAULT_M = 100
    /** スライダーの刻み（仕様外・要調整） */
    const val ARRIVE_RADIUS_STEP_M = 10

    /** 円の外に出たと判定するのは半径のこの倍を超えてから（ばたつき防止） */
    const val EXIT_RADIUS_FACTOR = 1.2
    /** 同じ地点の接近演出は、離れてからこの時間は繰り返さない */
    const val REAPPROACH_SUPPRESS_MS = 30 * 60 * 1000L

    /** この距離より登録地点に近いあいだは、位置の取得間隔を短くする */
    const val NEAR_PLACE_DISTANCE_M = 1500.0
    /**
     * 登録地点の近くでの位置の取得間隔。近くでは GPS（高精度）で取る
     * （ユーザーと決めたこと：省電力の精度では電車内で数百mずれ、内側の円に入れなかったため）
     */
    const val NEAR_LOCATION_INTERVAL_SEC = 15.0
    /** 到着したあと（内側の円の中）は省電力の精度でこの間隔にする（ユーザーと決めたこと：到着後は GPS を控える） */
    const val ARRIVED_LOCATION_INTERVAL_SEC = 60.0

    /** 接近・到着で振動させる長さ（待ち・振動・待ち・振動…のミリ秒。仕様外・要調整） */
    val APPROACH_VIBRATION_MS = longArrayOf(0, 180)
    val ARRIVE_VIBRATION_MS = longArrayOf(0, 180, 120, 180)
}
