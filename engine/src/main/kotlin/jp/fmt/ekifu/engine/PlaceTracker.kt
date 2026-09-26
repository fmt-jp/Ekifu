package jp.fmt.ekifu.engine

import jp.fmt.ekifu.engine.PlaceConstants as P

/** 登録地点（6章）。座標と半径は判定用で、音に関わるのは [place] */
data class RegisteredPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val approachRadiusM: Int,
    val arriveRadiusM: Int,
    val mood: Mood,
    val themeSeed: Int,
) {
    val place: Place get() = Place(id, name, mood, themeSeed)
}

/** 判定の結果として作曲に送る出来事 */
sealed interface PlaceEvent {
    val place: RegisteredPlace

    /** 外側の円に入った：接近の演出 */
    data class Approach(override val place: RegisteredPlace) : PlaceEvent

    /** 内側の円に入った：到着の演出 */
    data class Arrive(override val place: RegisteredPlace) : PlaceEvent

    /** 再生を始めたときすでに内側の円の中にいた：演出なしで滞在から */
    data class StayAtStart(override val place: RegisteredPlace) : PlaceEvent

    /** 外側の円から出た：テーマを一度だけ静かに鳴らして道中へ */
    data class Leave(override val place: RegisteredPlace) : PlaceEvent
}

/**
 * 登録地点への接近・到着・離脱の判定（6章）。位置を取得するたびに [update] を呼ぶ。
 *
 * - 円の外に出た判定は半径の1.2倍を超えてから（ばたつき防止）
 * - 同じ地点の接近演出は、離れてから30分以内は繰り返さない（そのあいだは音を変えずに追いかけ、
 *   内側の円に入れば到着の演出は鳴らす）
 * - 円が重なるときは、内側の円に入っている地点を優先し、なければ近い地点を優先する
 * - 最初の位置ですでに内側の円の中なら、到着の演出なしで滞在から始める
 * - 速く通り過ぎて外側の円の判定が間に合わなければ、到着だけを演出する
 */
class PlaceTracker(places: List<RegisteredPlace> = emptyList()) {

    enum class State { NONE, APPROACHING, ARRIVED }

    var places: List<RegisteredPlace> = places
        private set

    /** 追いかけている地点と状態 */
    var current: RegisteredPlace? = null
        private set
    var state = State.NONE
        private set

    /** 接近を音に出さずに追いかけている（30分以内の再接近） */
    private var silent = false
    private var first = true
    private val leftAtMs = HashMap<String, Long>()

    /** 地点の登録・編集・削除を反映する */
    fun setPlaces(newPlaces: List<RegisteredPlace>) {
        places = newPlaces
        val cur = current ?: return
        val updated = newPlaces.firstOrNull { it.id == cur.id }
        if (updated == null) {
            current = null
            state = State.NONE
            silent = false
        } else {
            current = updated
        }
    }

    fun update(fix: LocationFix): List<PlaceEvent> {
        val isFirst = first
        first = false
        val events = ArrayList<PlaceEvent>(2)
        val cur = current

        // いま追いかけている地点から出たか
        if (cur != null) {
            val d = distance(cur, fix)
            if (d > cur.approachRadiusM * P.EXIT_RADIUS_FACTOR) {
                if (!silent || state == State.ARRIVED) events += PlaceEvent.Leave(cur)
                leftAtMs[cur.id] = fix.timeMs
                current = null
                state = State.NONE
                silent = false
            } else if (state != State.ARRIVED && d <= cur.arriveRadiusM) {
                arrive(cur, events)
                return events
            }
        }

        // 別の地点の内側の円に入ったら、そちらを優先する（到着中の地点は手放さない）
        if (state != State.ARRIVED) {
            val inner = places
                .filter { it.id != current?.id && distance(it, fix) <= it.arriveRadiusM }
                .minByOrNull { distance(it, fix) }
            if (inner != null) {
                current?.let { leftAtMs[it.id] = fix.timeMs }
                if (isFirst) {
                    current = inner
                    state = State.ARRIVED
                    silent = false
                    events += PlaceEvent.StayAtStart(inner)
                } else {
                    arrive(inner, events)
                }
                return events
            }
        }

        // どこも追いかけていなければ、外側の円に入っている一番近い地点
        if (current == null) {
            val outer = places
                .filter { distance(it, fix) <= it.approachRadiusM }
                .minByOrNull { distance(it, fix) }
            if (outer != null) {
                current = outer
                state = State.APPROACHING
                val left = leftAtMs[outer.id]
                silent = left != null && fix.timeMs - left < P.REAPPROACH_SUPPRESS_MS
                if (!silent) events += PlaceEvent.Approach(outer)
            }
        }
        return events
    }

    private fun arrive(p: RegisteredPlace, events: MutableList<PlaceEvent>) {
        current = p
        state = State.ARRIVED
        silent = false
        events += PlaceEvent.Arrive(p)
    }

    /** 一番近い登録地点とその距離（画面表示用） */
    fun nearest(fix: LocationFix): Pair<RegisteredPlace, Double>? =
        places.map { it to distance(it, fix) }.minByOrNull { it.second }

    /** 登録地点の近く（1.5km以内）にいるか。いるあいだは位置の取得間隔を1分にする */
    fun isNear(fix: LocationFix): Boolean =
        places.any { distance(it, fix) <= P.NEAR_PLACE_DISTANCE_M }

    private fun distance(p: RegisteredPlace, fix: LocationFix) = Geo.distanceM(p.lat, p.lng, fix.lat, fix.lng)
}

/**
 * 判定の結果を作曲に送る。同じ位置で「離れる」と別の地点への接近・到着が重なったときは、
 * 離れるテーマは鳴らさずに新しい地点の演出だけにする。
 */
fun applyPlaceEvents(events: List<PlaceEvent>, composer: ComposerInput) {
    val entering = events.any { it !is PlaceEvent.Leave }
    for (e in events) {
        when (e) {
            is PlaceEvent.Approach -> composer.approach(e.place.place)
            is PlaceEvent.Arrive -> composer.arrive(e.place.place)
            is PlaceEvent.StayAtStart -> composer.stay(e.place.place)
            is PlaceEvent.Leave -> if (!entering) composer.leave()
        }
    }
}
