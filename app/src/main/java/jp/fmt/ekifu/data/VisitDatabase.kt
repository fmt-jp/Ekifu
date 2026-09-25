package jp.fmt.ekifu.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 訪問履歴。保存するのはマスID（ジオハッシュ6桁）と訪問日だけ。
 * 正確な緯度経度や移動の軌跡は保存しない（8章）。
 */
@Entity(tableName = "visits", primaryKeys = ["gridId", "day"])
data class Visit(
    val gridId: String,
    /** yyyy-MM-dd（端末のタイムゾーン） */
    val day: String,
)

@Dao
interface VisitDao {
    /** 同じマス・同じ日は1件だけ */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(visit: Visit)

    /** そのマスを訪れた日数 */
    @Query("SELECT COUNT(*) FROM visits WHERE gridId = :gridId")
    suspend fun countDays(gridId: String): Int

    /** 設定画面の「訪問履歴を消去」用（段階5） */
    @Query("DELETE FROM visits")
    suspend fun clear()
}

@Database(entities = [Visit::class], version = 1, exportSchema = false)
abstract class EkifuDatabase : RoomDatabase() {
    abstract fun visits(): VisitDao

    companion object {
        @Volatile private var instance: EkifuDatabase? = null

        fun get(context: Context): EkifuDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, EkifuDatabase::class.java, "ekifu.db")
                    .build()
                    .also { instance = it }
            }
    }
}
