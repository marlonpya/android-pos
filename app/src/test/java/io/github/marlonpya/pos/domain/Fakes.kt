package io.github.marlonpya.pos.domain

import io.github.marlonpya.dukpt.Dukpt
import io.github.marlonpya.dukpt.Ksn
import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.IsoCodec
import io.github.marlonpya.iso8583.IsoMessage
import io.github.marlonpya.iso8583.MessageMac
import io.github.marlonpya.iso8583.Mti
import io.github.marlonpya.pinpad.PinpadCommand
import io.github.marlonpya.pinpad.PinpadException
import io.github.marlonpya.pinpad.PinpadResponse
import io.github.marlonpya.pinpad.simulator.InitialKey
import io.github.marlonpya.pinpad.simulator.PinpadSimulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** El simulador real, llamado directo, sin enlace de por medio. */
class SimulatorPinpad(val simulator: PinpadSimulator = PinpadSimulator()) : PinpadPort {
    var unavailable = false

    private fun call(command: PinpadCommand): PinpadResponse {
        if (unavailable) throw PinpadException.NotDelivered(3)
        return simulator.handle(command)
    }

    override fun readCard(amountCents: Long, currency: String, stan: Int): PinpadResponse.CardRead =
        when (val r = call(PinpadCommand.Sale(amountCents, currency, stan))) {
            is PinpadResponse.CardRead -> r
            is PinpadResponse.Failure -> throw PinpadRejected(r.reason)
            else -> error("Respuesta inesperada $r")
        }

    override fun nextKsn(): String = (call(PinpadCommand.NextKsn) as PinpadResponse.KsnInfo).ksn

    override fun mac(data: ByteArray): Pair<String, ByteArray> {
        val r = call(PinpadCommand.Mac(data)) as PinpadResponse.MacResult
        return r.ksn to r.mac.hexToBytes()
    }

    override fun injectFault(name: String, value: String) {
        call(PinpadCommand.InjectFault(name, value))
    }
}

/**
 * Host de mentira que sí verifica el MAC con la BDK de prueba, para demostrar
 * que lo que firma la app a través del PINpad es verificable del otro lado.
 */
class ScriptedGateway : GatewayPort {
    enum class Mode { RESPOND, NOT_CONNECTED, NO_RESPONSE }

    var mode = Mode.RESPOND
    var responseCode = "00"
    val received = mutableListOf<IsoMessage>()
    private val codec = IsoCodec()
    private val mac = MessageMac(codec)

    override fun exchange(raw: ByteArray): ByteArray {
        when (mode) {
            Mode.NOT_CONNECTED -> throw GatewayException.NotConnected()
            Mode.NO_RESPONSE -> {
                received += codec.unpack(raw)
                throw GatewayException.NoResponse()
            }
            Mode.RESPOND -> Unit
        }
        val request = codec.unpack(raw)
        received += request
        val key = Dukpt.macRequestKey(Dukpt.transactionKey(InitialKey.TEST_BDK, Ksn.parse(request.text(Fields.KSN))))
        check(mac.verify(raw, key)) { "MAC inválido en ${request.mti}" }
        val code = if (request.mti == Mti.FINANCIAL_REQUEST) responseCode else "00"
        var response = request.toResponse(code).with(Fields.RRN, "430315000001")
        if (code == "00" && request.mti == Mti.FINANCIAL_REQUEST) response = response.with(Fields.AUTH_CODE, "A00001")
        return codec.pack(response)
    }
}

class InMemoryTransactions : TransactionRepository {
    private val state = MutableStateFlow<Map<String, Transaction>>(emptyMap())

    override suspend fun save(tx: Transaction) {
        state.value = state.value + (tx.id to tx)
    }

    override suspend fun find(id: String) = state.value[id]

    override suspend fun withStatus(vararg status: TxStatus) = state.value.values.filter { it.status in status }

    override fun observeAll(): Flow<List<Transaction>> = state.map { it.values.sortedBy { tx -> tx.createdAt } }

    fun all() = state.value.values.toList()
}

class InMemoryOutbox : OutboxRepository {
    private val items = MutableStateFlow<List<OutboxItem>>(emptyList())
    private var nextId = 1L

    override suspend fun enqueue(transactionId: String, kind: OutboxKind, payload: ByteArray, now: Long): Long {
        val id = nextId++
        items.value = items.value + OutboxItem(id, transactionId, kind, payload, 0, now)
        return id
    }

    override suspend fun pending() = items.value

    override suspend fun has(transactionId: String, kind: OutboxKind) =
        items.value.any { it.transactionId == transactionId && it.kind == kind }

    override suspend fun remove(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun recordAttempt(id: Long) {
        items.value = items.value.map { if (it.id == id) OutboxItem(it.id, it.transactionId, it.kind, it.payload, it.attempts + 1, it.createdAt) else it }
    }

    override fun observeCount(): Flow<Int> = items.map { it.size }

    fun all() = items.value
}

class CountingStan : StanSource {
    private var n = 0
    override fun next() = ++n
}
