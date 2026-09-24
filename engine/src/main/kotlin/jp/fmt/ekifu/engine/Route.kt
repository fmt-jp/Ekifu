package jp.fmt.ekifu.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 通勤ルート（SPEC 5章のデータ形式）。 */
@Serializable
data class Route(
    val name: String,
    val expectedMinutes: Double,
    val stations: List<Station>,
)

@Serializable
data class Station(
    val name: String,
    val lat: Double,
    val lng: Double,
    /** この駅から次の駅までが地下区間か。 */
    val undergroundAfter: Boolean,
)

/** ルートごとに固定の乱数シード。同じルートなら毎回同じ曲（自分の通勤のテーマ曲）になる。 */
fun Route.seed(): Int = StationMotif.fnv1a(name + stations.joinToString("|") { it.name })

/** ルートを SPEC 5章の JSON 形式で読み書きする。 */
object RouteJson {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(route: Route): String = json.encodeToString(Route.serializer(), route)

    /** 壊れた JSON や形式違いのときは null。 */
    fun decode(text: String): Route? = runCatching { json.decodeFromString(Route.serializer(), text) }.getOrNull()
}

/** 登録できるルートかどうかの確認結果。空なら問題なし。 */
object RouteValidation {
    const val MIN_STATIONS = 2
    const val MAX_EXPECTED_MINUTES = 600.0

    fun problems(route: Route): List<String> = buildList {
        if (route.name.isBlank()) add("ルート名を入力してください")
        if (!(route.expectedMinutes > 0 && route.expectedMinutes <= MAX_EXPECTED_MINUTES)) {
            add("予定所要時間は 1〜${MAX_EXPECTED_MINUTES.toInt()} 分で入力してください")
        }
        if (route.stations.size < MIN_STATIONS) add("駅を $MIN_STATIONS つ以上登録してください")
        route.stations.forEachIndexed { i, s ->
            if (s.name.isBlank()) add("${i + 1} 番目の駅名を入力してください")
            if (s.lat !in -90.0..90.0 || s.lng !in -180.0..180.0) add("${i + 1} 番目の駅の座標が正しくありません")
        }
    }
}
