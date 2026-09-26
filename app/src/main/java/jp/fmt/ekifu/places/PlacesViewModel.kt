package jp.fmt.ekifu.places

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.fmt.ekifu.data.EkifuDatabase
import jp.fmt.ekifu.data.PlaceEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlacesViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = EkifuDatabase.get(app).places()

    val places: StateFlow<List<PlaceEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(place: PlaceEntity) {
        viewModelScope.launch { dao.upsert(place) }
    }

    fun delete(id: String) {
        viewModelScope.launch { dao.delete(id) }
    }
}
