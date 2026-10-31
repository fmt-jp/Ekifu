package jp.fmt.ekifu.engine

/**
 * いま見えている携帯の基地局（端末の中で読むだけ。外部には送らない）。
 * キーは「方式:事業者:エリア:セル番号」（例 "LTE:44010:1234:5678901"）。番号が分からない基地局は入れない
 */
data class CellSnapshot(
    /** つながっている基地局（デュアルSIM・5G のときは複数） */
    val serving: Set<String>,
    /** 近くに見えている基地局 */
    val neighbors: Set<String> = emptySet(),
) {
    val all: Set<String> get() = serving + neighbors
}

/**
 * 地点ごとに覚えた基地局（ユーザーと決めたこと：地下の駅など位置が取れない場所で登録地点を見分ける。
 * 地上では GPS を入れるきっかけに使う）。何回見えたかも数え、同じ基地局を複数の地点で覚えたら多いほうを選ぶ
 */
class CellBook {
    private val cells = HashMap<String, HashMap<String, Int>>() // 地点ID → 基地局 → 回数

    fun clear() {
        cells.clear()
    }

    /** 保存してあった分を入れる */
    fun put(placeId: String, cellKey: String, count: Int) {
        cells.getOrPut(placeId) { HashMap() }[cellKey] = count
    }

    /** その地点で見えている基地局を覚える。覚えた（数えた）キーを返す（保存用） */
    fun learn(placeId: String, keys: Collection<String>): List<String> {
        val m = cells.getOrPut(placeId) { HashMap() }
        keys.forEach { m[it] = (m[it] ?: 0) + 1 }
        return keys.toList()
    }

    /** つながっている基地局から分かる地点（覚えた回数が多い地点）。見分けられなければ null */
    fun placeFor(serving: Set<String>, placeIds: Set<String>? = null): String? =
        cells.entries
            .filter { placeIds == null || it.key in placeIds }
            .mapNotNull { (id, m) -> serving.mapNotNull { m[it] }.maxOrNull()?.let { id to it } }
            .maxByOrNull { it.second }?.first

    /** その地点で覚えた基地局の数 */
    fun count(placeId: String): Int = cells[placeId]?.size ?: 0

    fun forget(placeId: String) {
        cells.remove(placeId)
    }
}
