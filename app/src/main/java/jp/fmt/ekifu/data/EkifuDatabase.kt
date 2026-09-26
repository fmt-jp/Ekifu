package jp.fmt.ekifu.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import jp.fmt.ekifu.engine.Mood
import jp.fmt.ekifu.engine.RegisteredPlace
import kotlinx.coroutines.flow.Flow

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

/** 登録地点（6章）。端末内にだけ保存し、外部に送らない */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val approachRadiusM: Int,
    val arriveRadiusM: Int,
    /** Mood の名前（CALM / BRIGHT / NOSTALGIC） */
    val mood: String,
    val themeSeed: Int,
    val createdAt: Long,
) {
    val moodValue: Mood get() = Mood.entries.firstOrNull { it.name == mood } ?: Mood.CALM

    fun toRegistered() = RegisteredPlace(id, name, lat, lng, approachRadiusM, arriveRadiusM, moodValue, themeSeed)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY createdAt")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: String): PlaceEntity?

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Visit::class, PlaceEntity::class], version = 2, exportSchema = false)
abstract class EkifuDatabase : RoomDatabase() {
    abstract fun visits(): VisitDao
    abstract fun places(): PlaceDao

    companion object {
        @Volatile private var instance: EkifuDatabase? = null

        fun get(context: Context): EkifuDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, EkifuDatabase::class.java, "ekifu.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}

/** 段階3（訪問履歴のみ）→ 段階4（登録地点を追加）。訪問履歴はそのまま残す */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`lat` REAL NOT NULL, `lng` REAL NOT NULL, `approachRadiusM` INTEGER NOT NULL, " +
                "`arriveRadiusM` INTEGER NOT NULL, `mood` TEXT NOT NULL, `themeSeed` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}
