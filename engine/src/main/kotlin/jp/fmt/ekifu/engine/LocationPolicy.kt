package jp.fmt.ekifu.engine

/** GPS（高精度）の使い方 */
enum class GpsMode(val label: String) {
    OFF("使わない"),
    NEAR_PLACES("地点の近くだけ"),
    ALWAYS("いつも"),
}

/**
 * 位置の取り方（ユーザーと決めたこと：設定で「おまかせ」から選ぶか、カスタムで項目ごとに変える）。
 * 値はすべて仕様外・要調整。[STANDARD] がこれまでの既定
 */
data class LocationPolicy(
    val gpsMode: GpsMode = GpsMode.NEAR_PLACES,
    /** 歩き・静止のとき、登録地点の「近く」とみなす距離。乗り物のあいだはその2倍 */
    val nearDistanceM: Double = PlaceConstants.NEAR_PLACE_DISTANCE_M,
    /** 歩き・静止のときの位置の取得間隔（場面はマスか速さが変わったら切り替え、変わらなければ5分ごと） */
    val normalIntervalSec: Double = MusicConstants.SCENE_INTERVAL_SEC,
    /** 乗り物のあいだの取得間隔 */
    val vehicleIntervalSec: Double = MusicConstants.VEHICLE_SCENE_INTERVAL_SEC,
    /** 登録地点の近く（まだ到着していない）の取得間隔 */
    val nearIntervalSec: Double = PlaceConstants.NEAR_LOCATION_INTERVAL_SEC,
    /** 到着したあとも GPS と地点の近くの間隔を続けるか（false なら省電力で1分ごとに戻す） */
    val keepGpsAfterArrival: Boolean = false,
) {
    companion object {
        /** 電池優先：地点の近くの範囲を狭め、間隔を長めに */
        val BATTERY = LocationPolicy(
            nearDistanceM = 1000.0,
            vehicleIntervalSec = 120.0,
            nearIntervalSec = 30.0,
        )

        /** 標準：これまでの既定 */
        val STANDARD = LocationPolicy()

        /** 精度優先：地点の近くの範囲を広げ、間隔を短く。到着後も GPS を続ける */
        val ACCURACY = LocationPolicy(
            nearDistanceM = 3000.0,
            normalIntervalSec = 60.0,
            vehicleIntervalSec = 30.0,
            nearIntervalSec = 10.0,
            keepGpsAfterArrival = true,
        )

        /** 設定画面の選択肢 */
        val NEAR_DISTANCE_CHOICES_M = listOf(1000.0, 1500.0, 3000.0, 5000.0)
        val NORMAL_INTERVAL_CHOICES_SEC = listOf(60.0, 120.0, 300.0)
        val VEHICLE_INTERVAL_CHOICES_SEC = listOf(30.0, 60.0, 120.0)
        val NEAR_INTERVAL_CHOICES_SEC = listOf(10.0, 15.0, 30.0, 60.0)
    }
}

/** 設定画面の「おまかせ」 */
enum class LocationPreset(val label: String, val description: String, val policy: LocationPolicy?) {
    BATTERY("電池優先", "地点の1km以内だけGPS・30秒。乗り物は2分ごと", LocationPolicy.BATTERY),
    STANDARD("標準", "地点の1.5km以内（乗り物は3km）だけGPS・15秒。ふだん5分・乗り物1分ごと", LocationPolicy.STANDARD),
    ACCURACY("精度優先", "地点の3km以内（乗り物は6km）でGPS・10秒、到着後も続ける。ふだん1分・乗り物30秒ごと", LocationPolicy.ACCURACY),
    CUSTOM("カスタム", "下の項目を自分で決める", null),
}
