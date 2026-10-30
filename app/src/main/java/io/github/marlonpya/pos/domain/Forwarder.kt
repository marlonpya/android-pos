package io.github.marlonpya.pos.domain

import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.IsoCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Vacía la cola de store and forward: avisos 0220 y reversas 0400.
 *
 * Reglas:
 *  - en orden de llegada, y al primer fallo de red se detiene para no
 *    adelantar mensajes;
 *  - cada mensaje sale exactamente con los mismos bytes cada vez, así que
 *    reintentarlo es seguro: el host lo reconoce y responde lo mismo;
 *  - un mensaje solo sale de la cola cuando el host respondió algo.
 */
class Forwarder(
    private val outbox: OutboxRepository,
    private val gateway: GatewayPort,
    private val transactions: TransactionRepository,
    private val io: CoroutineDispatcher,
    private val codec: IsoCodec = IsoCodec(),
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Result(val sent: Int, val remaining: Int)

    private val mutex = Mutex()

    suspend fun drain(): Result = mutex.withLock {
        var sent = 0
        val pending = outbox.pending()
        for ((index, item) in pending.withIndex()) {
            val response = try {
                withContext(io) { codec.unpack(gateway.exchange(item.payload)) }
            } catch (_: GatewayException) {
                outbox.recordAttempt(item.id)
                return Result(sent, pending.size - index)
            }
            settle(item, response.text(Fields.RESPONSE_CODE), response.textOrNull(Fields.RRN))
            outbox.remove(item.id)
            sent++
        }
        Result(sent, 0)
    }

    private suspend fun settle(item: OutboxItem, code: String, rrn: String?) {
        val tx = transactions.find(item.transactionId) ?: return
        val now = clock.millis()
        val updated = when (item.kind) {
            OutboxKind.REVERSAL -> if (code == "00") {
                tx.copy(status = TxStatus.REVERSED, message = "Reversada en el host", updatedAt = now)
            } else {
                tx.copy(status = TxStatus.FAILED, message = "Reversa rechazada ($code): conciliar a mano", updatedAt = now)
            }
            OutboxKind.ADVICE -> if (code == "00") {
                tx.copy(message = "Aprobada sin conexión; aviso aceptado por el host", rrn = rrn ?: tx.rrn, updatedAt = now)
            } else {
                tx.copy(status = TxStatus.FAILED, message = "Aviso rechazado ($code): conciliar a mano", updatedAt = now)
            }
        }
        transactions.save(updated)
    }
}

/** Texto para el cajero de los códigos de respuesta que usa el laboratorio. */
object ResponseCodes {
    fun describe(code: String): String = when (code) {
        "00" -> "aprobada"
        "05" -> "no honrar (05)"
        "12" -> "transacción inválida (12)"
        "13" -> "monto inválido (13)"
        "14" -> "tarjeta inválida (14)"
        "30" -> "error de formato (30)"
        "51" -> "fondos insuficientes (51)"
        "54" -> "tarjeta vencida (54)"
        "55" -> "PIN incorrecto (55)"
        "61" -> "excede el límite (61)"
        "63" -> "violación de seguridad, MAC inválido (63)"
        "94" -> "transmisión duplicada (94)"
        "96" -> "falla del sistema (96)"
        else -> "código $code"
    }
}
