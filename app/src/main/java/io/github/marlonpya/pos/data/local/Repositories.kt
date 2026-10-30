package io.github.marlonpya.pos.data.local

import android.content.Context
import io.github.marlonpya.pos.data.security.BlobCipher
import io.github.marlonpya.pos.domain.OutboxItem
import io.github.marlonpya.pos.domain.OutboxKind
import io.github.marlonpya.pos.domain.OutboxRepository
import io.github.marlonpya.pos.domain.StanSource
import io.github.marlonpya.pos.domain.Transaction
import io.github.marlonpya.pos.domain.TransactionRepository
import io.github.marlonpya.pos.domain.TxStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTransactionRepository(private val dao: TransactionDao) : TransactionRepository {

    override suspend fun save(tx: Transaction) = dao.upsert(tx.toEntity())

    override suspend fun find(id: String) = dao.find(id)?.toDomain()

    override suspend fun withStatus(vararg status: TxStatus) = dao.withStatus(status.map { it.name }).map { it.toDomain() }

    override fun observeAll(): Flow<List<Transaction>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    private fun Transaction.toEntity() = TransactionEntity(
        id, stan, transmissionDateTime, amountCents, currency, maskedPan, status.name,
        responseCode, authCode, rrn, ksn, message, createdAt, updatedAt,
    )

    private fun TransactionEntity.toDomain() = Transaction(
        id, stan, transmissionDateTime, amountCents, currency, maskedPan, TxStatus.valueOf(status),
        responseCode, authCode, rrn, ksn, message, createdAt, updatedAt,
    )
}

/** La cola cifra al guardar y descifra al leer: el dominio nunca ve un blob cifrado. */
class RoomOutboxRepository(private val dao: OutboxDao, private val cipher: BlobCipher) : OutboxRepository {

    override suspend fun enqueue(transactionId: String, kind: OutboxKind, payload: ByteArray, now: Long): Long =
        dao.insert(OutboxEntity(transactionId = transactionId, kind = kind.name, payload = cipher.encrypt(payload), attempts = 0, createdAt = now))

    override suspend fun pending(): List<OutboxItem> = dao.all().map {
        OutboxItem(it.id, it.transactionId, OutboxKind.valueOf(it.kind), cipher.decrypt(it.payload), it.attempts, it.createdAt)
    }

    override suspend fun has(transactionId: String, kind: OutboxKind) = dao.count(transactionId, kind.name) > 0

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun recordAttempt(id: Long) = dao.incrementAttempts(id)

    override fun observeCount(): Flow<Int> = dao.observeCount()
}

/** STAN persistente: 1 a 999999 y vuelve a empezar. No es un dato sensible. */
class PrefsStanSource(context: Context) : StanSource {
    private val prefs = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    @Synchronized
    override fun next(): Int {
        val next = prefs.getInt(KEY, 0) % 999_999 + 1
        prefs.edit().putInt(KEY, next).commit()
        return next
    }

    private companion object {
        const val KEY = "stan"
    }
}
