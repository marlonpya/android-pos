package io.github.marlonpya.pos.domain

import io.github.marlonpya.pinpad.PinpadResponse
import kotlinx.coroutines.flow.Flow

/**
 * El PINpad visto desde el dominio. Las llamadas son bloqueantes, como el
 * enlace serial que representan; los casos de uso las corren en IO.
 *
 * Lanza [io.github.marlonpya.pinpad.PinpadException] si falla el enlace y
 * [PinpadRejected] si el PINpad responde que no pudo (tarjeta retirada,
 * cliente canceló...).
 */
interface PinpadPort {
    fun readCard(amountCents: Long, currency: String, stan: Int): PinpadResponse.CardRead

    /** Avanza el KSN para firmar una operación que no viene de una lectura de tarjeta. */
    fun nextKsn(): String

    /** MAC de [data] con la llave del KSN actual. Devuelve el KSN usado y el MAC. */
    fun mac(data: ByteArray): Pair<String, ByteArray>

    fun injectFault(name: String, value: String)
}

class PinpadRejected(val reason: PinpadResponse.Reason) : Exception("El PINpad respondió $reason")

/** El host de autorización, por ISO 8583 sobre TCP. Bloqueante. */
interface GatewayPort {
    /** Manda un mensaje empaquetado y devuelve la respuesta empaquetada. */
    fun exchange(raw: ByteArray): ByteArray
}

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No se llegó a mandar nada: es seguro decidir sin reversa. */
    class NotConnected(cause: Throwable? = null) : GatewayException("Sin conexión con el host", cause)

    /** Se mandó y no hubo respuesta: el host pudo haber aprobado. */
    class NoResponse(cause: Throwable? = null) : GatewayException("El host no respondió", cause)
}

interface TransactionRepository {
    suspend fun save(tx: Transaction)

    suspend fun find(id: String): Transaction?

    suspend fun withStatus(vararg status: TxStatus): List<Transaction>

    fun observeAll(): Flow<List<Transaction>>
}

interface OutboxRepository {
    suspend fun enqueue(transactionId: String, kind: OutboxKind, payload: ByteArray, now: Long): Long

    /** En orden de llegada: el host debe ver los avisos y reversas en el orden en que ocurrieron. */
    suspend fun pending(): List<OutboxItem>

    suspend fun has(transactionId: String, kind: OutboxKind): Boolean

    suspend fun remove(id: Long)

    suspend fun recordAttempt(id: Long)

    fun observeCount(): Flow<Int>
}

/** System Trace Audit Number: correlativo de 6 dígitos del terminal. */
interface StanSource {
    fun next(): Int
}
