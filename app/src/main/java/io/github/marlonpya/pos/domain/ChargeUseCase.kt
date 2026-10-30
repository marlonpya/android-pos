package io.github.marlonpya.pos.domain

import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.pinpad.PinpadException
import io.github.marlonpya.pinpad.PinpadResponse.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

/**
 * Un cobro de punta a punta: PINpad, bitácora, host y, si hace falta, reversa
 * o aviso offline. Emite cada estado de [TransactionMachine] para la UI.
 *
 * Las tres salidas de la llamada al host deciden todo:
 *  - hay respuesta → aprobada o rechazada, es definitivo;
 *  - no se pudo conectar → nada salió del terminal; bajo el límite se aprueba
 *    offline y se encola el aviso, sobre el límite se rechaza;
 *  - se mandó y no hubo respuesta → estado incierto: se encola la reversa y se
 *    intenta mandarla en el acto. Nunca se reintenta el cobro.
 */
class ChargeUseCase(
    private val config: TerminalConfig,
    private val pinpad: PinpadPort,
    private val gateway: GatewayPort,
    private val transactions: TransactionRepository,
    private val outbox: OutboxRepository,
    private val stans: StanSource,
    private val messages: MessageFactory,
    private val forwarder: Forwarder,
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun charge(amountCents: Long): Flow<ChargeState> = flow {
        var state: ChargeState = ChargeState.Idle
        suspend fun move(event: ChargeEvent) {
            state = TransactionMachine.next(state, event)
            emit(state)
        }

        move(ChargeEvent.Start(amountCents))
        val now = clock.instant()
        val stan = MessageFactory.stanText(stans.next())
        val f7 = MessageFactory.transmissionDateTime(now)

        val card = try {
            pinpad.readCard(amountCents, config.currency, stan.toInt())
        } catch (e: PinpadRejected) {
            return@flow move(ChargeEvent.Fail(describe(e.reason)))
        } catch (e: PinpadException) {
            return@flow move(ChargeEvent.Fail("PINpad: ${e.message}"))
        }
        move(ChargeEvent.CardRead(card.maskedPan))

        // Write-ahead: la transacción existe en disco antes de que salga un solo byte al host.
        var tx = Transaction(
            id = UUID.randomUUID().toString(),
            stan = stan,
            transmissionDateTime = f7,
            amountCents = amountCents,
            currency = config.currency,
            maskedPan = card.maskedPan,
            status = TxStatus.AUTHORIZING,
            ksn = card.ksn,
            createdAt = now.toEpochMilli(),
        )
        transactions.save(tx)

        val request = try {
            messages.sale(card, amountCents, stan, f7, now)
        } catch (e: Exception) {
            tx = tx.update(TxStatus.FAILED, "No se pudo firmar la venta: ${e.message}")
            transactions.save(tx)
            return@flow move(ChargeEvent.Fail(tx.message))
        }

        val response = try {
            messages.responseOf(gateway.exchange(request))
        } catch (_: GatewayException.NotConnected) {
            val advice = if (config.offlineEnabled && amountCents <= config.floorLimitCents) {
                runCatching { messages.advice(card, amountCents, stan, f7, now) }.getOrNull()
            } else {
                null
            }
            if (advice != null) {
                outbox.enqueue(tx.id, OutboxKind.ADVICE, advice, clock.millis())
                tx = tx.update(TxStatus.OFFLINE_APPROVED, "Aprobada sin conexión; el aviso saldrá al volver la red")
                transactions.save(tx)
                return@flow move(ChargeEvent.ApprovedOffline(tx))
            }
            tx = tx.update(TxStatus.FAILED, "Sin conexión con el host y el monto supera el límite offline")
            transactions.save(tx)
            return@flow move(ChargeEvent.Fail(tx.message))
        } catch (e: Exception) {
            // Sin respuesta, conexión cortada o respuesta ilegible: el host pudo haber aprobado.
            if (e is CancellationException) throw e
            tx = tx.update(TxStatus.REVERSAL_PENDING, "El host no respondió: se reversa")
            transactions.save(tx)
            move(ChargeEvent.Uncertain(tx))
            queueReversal(tx)
            forwarder.drain()
            tx = transactions.find(tx.id) ?: tx
            return@flow move(if (tx.status == TxStatus.REVERSED) ChargeEvent.ReversalConfirmed(tx) else ChargeEvent.ReversalQueued(tx))
        }

        val code = response.text(Fields.RESPONSE_CODE)
        tx = tx.copy(
            responseCode = code,
            authCode = response.textOrNull(Fields.AUTH_CODE),
            rrn = response.textOrNull(Fields.RRN),
        )
        if (code == "00") {
            tx = tx.update(TxStatus.APPROVED, "Aprobada")
            transactions.save(tx)
            move(ChargeEvent.HostApproved(tx))
        } else {
            tx = tx.update(TxStatus.DECLINED, "Rechazada: ${ResponseCodes.describe(code)}")
            transactions.save(tx)
            move(ChargeEvent.HostDeclined(tx))
        }
    }.flowOn(io)

    /**
     * Encola la reversa de [tx] si todavía no está en la cola. Si el PINpad no
     * puede firmarla ahora, la transacción queda en REVERSAL_PENDING y la
     * recuperación lo vuelve a intentar.
     */
    private suspend fun queueReversal(tx: Transaction) {
        if (outbox.has(tx.id, OutboxKind.REVERSAL)) return
        runCatching {
            val stan = MessageFactory.stanText(stans.next())
            messages.reversal(tx, stan, clock.instant())
        }.onSuccess { outbox.enqueue(tx.id, OutboxKind.REVERSAL, it, clock.millis()) }
    }

    /**
     * Al arrancar la app: lo que quedó a medias se reversa. Una transacción en
     * AUTHORIZING significa que la app murió después de mandar (o mientras
     * mandaba) la venta, así que su estado es incierto.
     */
    suspend fun recover(): Unit = withContext(io) {
        for (tx in transactions.withStatus(TxStatus.AUTHORIZING)) {
            transactions.save(tx.update(TxStatus.REVERSAL_PENDING, "La app se cerró durante el cobro: se reversa"))
        }
        for (tx in transactions.withStatus(TxStatus.REVERSAL_PENDING)) queueReversal(tx)
        forwarder.drain()
    }

    private fun Transaction.update(status: TxStatus, message: String) =
        copy(status = status, message = message, updatedAt = clock.millis())

    private fun describe(reason: Reason) = when (reason) {
        Reason.CARD_REMOVED -> "Tarjeta retirada antes de tiempo"
        Reason.USER_CANCEL -> "El cliente canceló en el PINpad"
        Reason.CARD_TIMEOUT -> "No se presentó la tarjeta a tiempo"
        Reason.KEY_EXHAUSTED -> "El PINpad agotó su llave: requiere nueva inyección"
        Reason.UNSUPPORTED -> "Operación no soportada por el PINpad"
    }
}
