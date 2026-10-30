package io.github.marlonpya.pos.data.local

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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Bitácora del terminal. Solo PAN enmascarado: ni PAN completo, ni pista, ni PIN. */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val stan: String,
    val transmissionDateTime: String,
    val amountCents: Long,
    val currency: String,
    val maskedPan: String?,
    val status: String,
    val responseCode: String?,
    val authCode: String?,
    val rrn: String?,
    val ksn: String?,
    val message: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Cola de store and forward. [payload] va cifrado con la llave del Keystore. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: String,
    val kind: String,
    val payload: ByteArray,
    val attempts: Int,
    val createdAt: Long,
)

@Dao
interface TransactionDao {
    @Upsert
    suspend fun upsert(entity: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun find(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE status IN (:status) ORDER BY createdAt")
    suspend fun withStatus(status: List<String>): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE transactionId = :transactionId AND kind = :kind")
    suspend fun count(transactionId: String, kind: String): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>
}

@Database(entities = [TransactionEntity::class, OutboxEntity::class], version = 1, exportSchema = true)
abstract class PosDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    abstract fun outbox(): OutboxDao

    companion object {
        fun create(context: Context): PosDatabase =
            Room.databaseBuilder(context, PosDatabase::class.java, "pos.db").build()
    }
}
