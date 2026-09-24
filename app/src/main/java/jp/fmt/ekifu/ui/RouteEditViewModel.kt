package jp.fmt.ekifu.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.fmt.ekifu.data.RouteRepository
import jp.fmt.ekifu.data.StoredRoute
import jp.fmt.ekifu.engine.Route
import jp.fmt.ekifu.engine.RouteValidation
import jp.fmt.ekifu.engine.Station
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 編集中の駅。座標は地図で指定するまで null。 */
data class StationDraft(
    val id: Long,
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
)

/**
 * ルート登録画面の状態。地下フラグは「駅」ではなく「区間（i 番目と i+1 番目の間）」に持たせ、
 * 駅を並べ替えても区間の設定がそのまま残るようにする。
 */
class RouteEditViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RouteRepository.get(application)
    private var nextId = 0L

    var loaded by mutableStateOf(false)
        private set
    var isNew by mutableStateOf(true)
        private set
    var name by mutableStateOf(DEFAULT_NAME)
    var minutesText by mutableStateOf("")
    val stations = mutableStateListOf<StationDraft>()
    /** 区間ごとの地下フラグ（要素数は駅の数 - 1）。 */
    val segmentUnderground = mutableStateListOf<Boolean>()
    var problems by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            val stored = repository.route.first { it != StoredRoute.Loading }
            if (stored is StoredRoute.Registered) {
                load(stored.route)
                isNew = false
            } else {
                repeat(2) { addStation() }
            }
            loaded = true
        }
    }

    private fun load(route: Route) {
        name = route.name
        minutesText = formatMinutes(route.expectedMinutes)
        stations.clear()
        segmentUnderground.clear()
        route.stations.forEachIndexed { i, s ->
            stations += StationDraft(nextId++, s.name, s.lat, s.lng)
            if (i < route.stations.lastIndex) segmentUnderground += s.undergroundAfter
        }
    }

    fun addStation() {
        if (stations.isNotEmpty()) segmentUnderground += false
        stations += StationDraft(nextId++)
    }

    fun removeStation(index: Int) {
        stations.removeAt(index)
        if (segmentUnderground.isNotEmpty()) {
            // 終点を消すときは手前の区間、それ以外はその駅から先の区間を消す
            segmentUnderground.removeAt(index.coerceAtMost(segmentUnderground.lastIndex))
        }
    }

    fun moveStation(from: Int, to: Int) {
        if (to !in stations.indices) return
        val s = stations.removeAt(from)
        stations.add(to, s)
    }

    fun updateStation(index: Int, transform: (StationDraft) -> StationDraft) {
        stations[index] = transform(stations[index])
    }

    fun setUnderground(segment: Int, value: Boolean) {
        segmentUnderground[segment] = value
    }

    /** 入力内容を確かめて保存する。問題があれば [problems] に入れて false。 */
    fun save(onSaved: () -> Unit) {
        val missingCoordinates = stations.withIndex()
            .filter { it.value.lat == null || it.value.lng == null }
            .map { "${it.index + 1} 番目の駅の位置を地図で指定してください" }
        val minutes = minutesText.trim().toDoubleOrNull() ?: 0.0
        val route = Route(
            name = name.trim(),
            expectedMinutes = minutes,
            stations = stations.mapIndexed { i, s ->
                Station(
                    name = s.name.trim(),
                    lat = s.lat ?: 0.0,
                    lng = s.lng ?: 0.0,
                    undergroundAfter = segmentUnderground.getOrElse(i) { false },
                )
            },
        )
        problems = RouteValidation.problems(route) + missingCoordinates
        if (problems.isNotEmpty()) return
        viewModelScope.launch {
            repository.save(route)
            onSaved()
        }
    }

    private companion object {
        const val DEFAULT_NAME = "いつもの通勤"

        fun formatMinutes(m: Double) = if (m % 1.0 == 0.0) m.toInt().toString() else m.toString()
    }
}
