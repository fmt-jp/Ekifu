package jp.fmt.ekifu.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.fmt.ekifu.engine.Route
import jp.fmt.ekifu.engine.RouteJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 保存したルートの読み込み状態。 */
sealed interface StoredRoute {
    data object Loading : StoredRoute
    data object None : StoredRoute
    data class Registered(val route: Route) : StoredRoute
}

private val Context.routeStore: DataStore<Preferences> by preferencesDataStore(name = "route")

/**
 * 通勤ルート 1 本を DataStore に保存する。中身は SPEC 5章の JSON そのまま。
 * アプリを再起動しても残る。
 */
class RouteRepository private constructor(context: Context) {
    private val store = context.routeStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val route: StateFlow<StoredRoute> = store.data
        .map { prefs ->
            val text = prefs[ROUTE_JSON] ?: return@map StoredRoute.None
            val decoded = RouteJson.decode(text)
            if (decoded == null) {
                Log.w(TAG, "保存されたルートを読めませんでした")
                StoredRoute.None
            } else {
                StoredRoute.Registered(decoded)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, StoredRoute.Loading)

    suspend fun save(route: Route) {
        store.edit { it[ROUTE_JSON] = RouteJson.encode(route) }
    }

    suspend fun delete() {
        store.edit { it.remove(ROUTE_JSON) }
    }

    companion object {
        private const val TAG = "Ekifu"
        private val ROUTE_JSON = stringPreferencesKey("route_json")

        @Volatile private var instance: RouteRepository? = null

        fun get(context: Context): RouteRepository =
            instance ?: synchronized(this) {
                instance ?: RouteRepository(context.applicationContext).also { instance = it }
            }
    }
}
