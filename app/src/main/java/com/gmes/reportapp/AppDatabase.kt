package com.gmes.reportapp

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val project: String = "",
    val jobNumber: String = "",
    val address: String = "",
    val date: String = "",
    val client: String = "",
    val engineer: String = "",
    val itemRef: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "supply_criteria",
    foreignKeys = [ForeignKey(
        entity = ReportEntity::class,
        parentColumns = ["id"],
        childColumns = ["reportId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["reportId"], unique = true)]
)
data class SupplyCriteriaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: Long,
    val client: String = "",
    val engineer: String = "",
    val testInstrument: String = "",
    val calCertNo: String = ""
)

@Entity(
    tableName = "library_items",
    indices = [Index(value = ["libraryType", "value"], unique = true)]
)
data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val libraryType: String,
    val value: String
)

@Entity(
    tableName = "data_record_rows",
    foreignKeys = [ForeignKey(
        entity = ReportEntity::class,
        parentColumns = ["id"],
        childColumns = ["reportId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("reportId")]
)
data class DataRecordRowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: Long,
    val rowNumber: Int,
    val testItem: String = "",
    val unit: String = "",
    val measuredValue: String = "",
    val inspectionItemNumber: String = "",
    val comment: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun isBlank(): Boolean = testItem.isBlank() && unit.isBlank() && measuredValue.isBlank() &&
        inspectionItemNumber.isBlank() && comment.isBlank()
}

@Dao
interface ReportDao {
    @Query("SELECT * FROM reports ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE id = :id LIMIT 1")
    fun observe(id: Long): Flow<ReportEntity?>

    @Query("SELECT * FROM reports WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ReportEntity?

    @Insert
    suspend fun insert(report: ReportEntity): Long

    @Update
    suspend fun update(report: ReportEntity)

    @Delete
    suspend fun delete(report: ReportEntity)
}

@Dao
interface SupplyCriteriaDao {
    @Query("SELECT * FROM supply_criteria WHERE reportId = :reportId LIMIT 1")
    fun observe(reportId: Long): Flow<SupplyCriteriaEntity?>

    @Query("SELECT * FROM supply_criteria WHERE reportId = :reportId LIMIT 1")
    suspend fun get(reportId: Long): SupplyCriteriaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SupplyCriteriaEntity): Long

    @Update
    suspend fun update(item: SupplyCriteriaEntity)

    @Transaction
    suspend fun upsert(item: SupplyCriteriaEntity) {
        val existing = get(item.reportId)
        if (existing == null) insert(item.copy(id = 0)) else update(item.copy(id = existing.id))
    }
}

@Dao
interface LibraryItemDao {
    @Query("SELECT * FROM library_items WHERE libraryType = :type ORDER BY value COLLATE NOCASE")
    fun observeByType(type: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: LibraryItemEntity): Long

    @Delete
    suspend fun delete(item: LibraryItemEntity)
}

@Dao
interface DataRecordRowDao {
    @Query("SELECT * FROM data_record_rows WHERE reportId = :reportId ORDER BY rowNumber ASC")
    fun observe(reportId: Long): Flow<List<DataRecordRowEntity>>

    @Query("SELECT * FROM data_record_rows WHERE reportId = :reportId ORDER BY rowNumber ASC")
    suspend fun get(reportId: Long): List<DataRecordRowEntity>

    @Insert
    suspend fun insert(row: DataRecordRowEntity): Long

    @Insert
    suspend fun insertAll(rows: List<DataRecordRowEntity>)

    @Update
    suspend fun update(row: DataRecordRowEntity)

    @Delete
    suspend fun delete(row: DataRecordRowEntity)

    @Query("DELETE FROM data_record_rows WHERE reportId = :reportId")
    suspend fun deleteForReport(reportId: Long)

    @Transaction
    suspend fun ensureTrailingBlank(reportId: Long) {
        val rows = get(reportId)
        if (rows.isEmpty() || !rows.last().isBlank()) {
            insert(DataRecordRowEntity(reportId = reportId, rowNumber = rows.size + 1))
        }
    }

    @Transaction
    suspend fun deleteAndRenumber(row: DataRecordRowEntity) {
        delete(row)
        val remaining = get(row.reportId).filterNot { it.isBlank() }
        deleteForReport(row.reportId)
        val rebuilt = remaining.mapIndexed { index, item ->
            item.copy(id = 0, rowNumber = index + 1, updatedAt = System.currentTimeMillis())
        }.toMutableList()
        rebuilt += DataRecordRowEntity(reportId = row.reportId, rowNumber = rebuilt.size + 1)
        insertAll(rebuilt)
    }
}

@Database(
    entities = [ReportEntity::class, SupplyCriteriaEntity::class, LibraryItemEntity::class, DataRecordRowEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao
    abstract fun supplyCriteriaDao(): SupplyCriteriaDao
    abstract fun libraryItemDao(): LibraryItemDao
    abstract fun dataRecordRowDao(): DataRecordRowDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "gmes_report_app.db"
            ).fallbackToDestructiveMigration(dropAllTables = true).build().also { INSTANCE = it }
        }
    }
}

object LibraryTypes {
    const val CLIENT = "client"
    const val ENGINEER = "engineer"
    const val TEST_INSTRUMENT = "test_instrument"
    const val CAL_CERT = "cal_cert"
}
