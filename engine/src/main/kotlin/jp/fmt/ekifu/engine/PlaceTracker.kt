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

    /** 離れた（到着中は内側の円、接近中は外側の円から出た）：テーマを一度だけ静かに鳴らして道中へ */
    data class Leave(override val place: RegisteredPlace) : PlaceEvent

    /** 内側の円から離れて30分以内にまた入った：到着の演出なしで滞在に戻る（位置のぶれで演出を繰り返さない） */
    data class Return(override val place: RegisteredPlace) : PlaceEvent
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
    val isSilent: Boolean get() = silent
    private var first = true
    private val leftAtMs = HashMap<String, Long>()
    /** 到着中に内側の円から離れた時刻 */
    private val leftInnerAtMs = HashMap<String, Long>()
    /** 位置なしで（基地局・「着いた」ボタンで）到着した。位置が届くまでは基地局が見えなくなったら離れる */
    private var arrivedWithoutFix = false
    private var lastCellMatchMs = 0L

    /**
     * 曲調を切り替えるときに新しいエンジンへ引き継ぐ状態（12.12）。
     * 内側の円にいれば滞在、音に出している接近中なら接近（音を変えずに追いかけている再接近は道中のまま）
     */
    fun carryOver(scene: Scene?): CarryOver {
        val cur = current
        return when {
            cur != null && state == State.ARRIVED -> CarryOver(scene, stayAt = cur.place)
            cur != null && state == State.APPROACHING && !silent -> CarryOver(scene, approaching = cur.place)
            else -> CarryOver(scene)
        }
    }

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
        // 位置が届いたら、これまでどおり距離で判定する
        arrivedWithoutFix = false
        val events = ArrayList<PlaceEvent>(2)
        val cur = current

        // いま追いかけている地点から出たか（到着中は内側の円、接近中は外側の円。どちらも半径の1.2倍を超えたら）
        if (cur != null) {
            val d = distance(cur, fix)
            // 到着中は省電力の粗い位置で確かめるので、誤差を差し引いても外にいるときだけ離れたとみなす
            // （誤差が大きくても、外側の円の1.2倍を超えれば離れた）
            val leaving = if (state == State.ARRIVED) {
                d - (fix.accuracyM ?: 0.0) > cur.arriveRadiusM * P.EXIT_RADIUS_FACTOR ||
                    d > cur.approachRadiusM * P.EXIT_RADIUS_FACTOR
            } else {
                d > cur.approachRadiusM * P.EXIT_RADIUS_FACTOR
            }
            if (leaving) {
                if (!silent || state == State.ARRIVED) events += PlaceEvent.Leave(cur)
                leftAtMs[cur.id] = fix.timeMs
                if (state == State.ARRIVED) leftInnerAtMs[cur.id] = fix.timeMs
                current = null
                state = State.NONE
                silent = false
            } else if (state != State.ARRIVED && inInner(cur, fix)) {
                arrive(cur, events, fix.timeMs)
                return events
            }
        }

        // 別の地点の内側の円に入ったら、そちらを優先する（到着中の地点は手放さない）
        if (state != State.ARRIVED) {
            val inner = places
                .filter { it.id != current?.id && inInner(it, fix) }
                .minByOrNull { distance(it, fix) }
            if (inner != null) {
                current?.let { leftAtMs[it.id] = fix.timeMs }
                if (isFirst) {
                    current = inner
                    state = State.ARRIVED
                    silent = false
                    events += PlaceEvent.StayAtStart(inner)
                } else {
                    arrive(inner, events, fix.timeMs)
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

    private fun arrive(p: RegisteredPlace, events: MutableList<PlaceEvent>, timeMs: Long) {
        current = p
        state = State.ARRIVED
        silent = false
        val left = leftInnerAtMs[p.id]
        events += if (left != null && timeMs - left < P.REAPPROACH_SUPPRESS_MS) PlaceEvent.Return(p) else PlaceEvent.Arrive(p)
    }

    /**
     * 位置が取れないあいだ（地下の駅など）、基地局で分かった地点 placeId に到着したとみなす。
     * placeId が null（どの地点の基地局でもない）のまま [P.CELL_LEAVE_SEC] たったら、基地局で到着していた地点から離れる
     */
    fun updateByCell(placeId: String?, nowMs: Long): List<PlaceEvent> {
        val events = ArrayList<PlaceEvent>(2)
        val cur = current
        if (placeId == null) {
            if (cur != null && state == State.ARRIVED && arrivedWithoutFix &&
                nowMs - lastCellMatchMs > (P.CELL_LEAVE_SEC * 1000).toLong()
            ) {
                events += PlaceEvent.Leave(cur)
                leaveNow(cur, nowMs)
            }
            return events
        }
        val p = places.firstOrNull { it.id == placeId } ?: return events
        lastCellMatchMs = nowMs
        if (cur?.id == p.id && state == State.ARRIVED) return events
        // 別の地点に到着していたら離れる（音は新しい地点の演出だけ。applyPlaceEvents が決める）
        if (cur != null && state == State.ARRIVED) {
            events += PlaceEvent.Leave(cur)
            leaveNow(cur, nowMs)
        }
        if (first) {
            // 再生を始めたときにすでにその場所にいた：演出なしで滞在
            first = false
            current = p
            state = State.ARRIVED
            silent = false
            events += PlaceEvent.StayAtStart(p)
        } else {
            arrive(p, events, nowMs)
        }
        arrivedWithoutFix = true
        return events
    }

    /**
     * 到着中の地点の内側の円に、精度のよい位置（誤差が内側の円の半径以下）で入っているか。
     * 基地局を覚えるのはこのときだけ（トンネルなど地点の外の基地局を覚えないため）
     */
    fun confirmedInside(fix: LocationFix): RegisteredPlace? {
        val cur = current ?: return null
        if (state != State.ARRIVED) return null
        val acc = fix.accuracyM ?: return null
        return cur.takeIf { acc <= it.arriveRadiusM && distance(it, fix) <= it.arriveRadiusM }
    }

    /**
     * 「着いた」ボタンでどの地点に着いたことにするか：基地局で分かればその地点、
     * 分からなければ最後の位置から [P.MANUAL_ARRIVE_MAX_M] 以内で一番近い地点
     */
    fun manualTarget(cellPlaceId: String?, lastFix: LocationFix?): RegisteredPlace? {
        places.firstOrNull { it.id == cellPlaceId }?.let { return it }
        val fix = lastFix ?: return null
        return nearest(fix)?.takeIf { it.second <= P.MANUAL_ARRIVE_MAX_M }?.first
    }

    /** 「着いた」ボタン：位置によらず placeId に到着する（すでに到着していれば何もしない） */
    fun forceArrive(placeId: String, nowMs: Long): List<PlaceEvent> {
        val p = places.firstOrNull { it.id == placeId } ?: return emptyList()
        val cur = current
        if (cur?.id == p.id && state == State.ARRIVED) return emptyList()
        val events = ArrayList<PlaceEvent>(2)
        if (cur != null && state == State.ARRIVED) {
            events += PlaceEvent.Leave(cur)
            leaveNow(cur, nowMs)
        }
        first = false
        current = p
        state = State.ARRIVED
        silent = false
        events += PlaceEvent.Arrive(p)
        arrivedWithoutFix = true
        lastCellMatchMs = nowMs
        return events
    }

    private fun leaveNow(p: RegisteredPlace, nowMs: Long) {
        leftAtMs[p.id] = nowMs
        if (state == State.ARRIVED) leftInnerAtMs[p.id] = nowMs
        current = null
        state = State.NONE
        silent = false
        arrivedWithoutFix = false
    }

    /** 一番近い登録地点とその距離（画面表示用） */
    fun nearest(fix: LocationFix): Pair<RegisteredPlace, Double>? =
        places.map { it to distance(it, fix) }.minByOrNull { it.second }

    /** 登録地点の近く（distanceM 以内。既定1.5km、乗り物は3km）にいるか。いるあいだは GPS で15秒ごとに取る */
    fun isNear(fix: LocationFix, distanceM: Double = P.NEAR_PLACE_DISTANCE_M): Boolean =
        places.any { distance(it, fix) <= distanceM }

    private fun distance(p: RegisteredPlace, fix: LocationFix) = Geo.distanceM(p.lat, p.lng, fix.lat, fix.lng)

    /**
     * 内側の円に入ったか。駅の屋根の下などでは GPS でも数十mずれるので、
     * 位置の誤差ぶん（内側の円の半径まで）の余裕を見て到着を取りこぼさないようにする
     */
    private fun inInner(p: RegisteredPlace, fix: LocationFix): Boolean =
        distance(p, fix) - minOf(fix.accuracyM ?: 0.0, p.arriveRadiusM.toDouble()) <= p.arriveRadiusM
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
            is PlaceEvent.Return -> composer.stay(e.place.place)
            is PlaceEvent.Leave -> if (!entering) composer.leave()
        }
    }
}
