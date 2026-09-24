package jp.fmt.ekifu.engine

/** 通勤ルート（SPEC 5章のデータ形式）。 */
data class Route(
    val name: String,
    val expectedMinutes: Double,
    val stations: List<Station>,
)

data class Station(
    val name: String,
    val lat: Double,
    val lng: Double,
    /** この駅から次の駅までが地下区間か。 */
    val undergroundAfter: Boolean,
)

/** ルートごとに固定の乱数シード。同じルートなら毎回同じ曲（自分の通勤のテーマ曲）になる。 */
fun Route.seed(): Int = StationMotif.fnv1a(name + stations.joinToString("|") { it.name })
