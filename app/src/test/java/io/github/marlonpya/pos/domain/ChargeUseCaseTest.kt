package io.github.marlonpya.pos.domain

import io.github.marlonpya.iso8583.Fields
import io.github.marlonpya.iso8583.Mti
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeUseCaseTest {

    private val config = TerminalConfig()
    private val pinpad = SimulatorPinpad()
    private val gateway = ScriptedGateway()
    private val transactions = InMemoryTransactions()
    private val outbox = InMemoryOutbox()
    private val io = Dispatchers.Unconfined
    private val forwarder = Forwarder(outbox, gateway, transactions, io)
    private val useCase = ChargeUseCase(
        config, pinpad, gateway, transactions, outbox, CountingStan(), MessageFactory(config, pinpad), forwarder, io,
    )

    private fun charge(amount: Long) = runBlocking { useCase.charge(amount).toList() }

    @Test
    fun `cobro aprobado pasa por leer tarjeta y autorizar, con MAC que el host verifica`() {
        val states = charge(2550)
        assertTrue(states[0] is ChargeState.ReadingCard)
        assertTrue(states[1] is ChargeState.Authorizing)
        val approved = states.last() as ChargeState.Approved
        assertEquals("A00001", approved.tx.authCode)
        assertEquals(TxStatus.APPROVED, transactions.all().single().status)
        // El PIN block viaja cifrado y el KSN de la venta coincide con el de la lectura.
        val sent = gateway.received.single()
        assertTrue(Fields.PIN_BLOCK in sent)
        assertEquals(approved.tx.ksn, sent.text(Fields.KSN))
    }

    @Test
    fun `cobro rechazado guarda el código y el motivo`() {
        gateway.responseCode = "51"
        val declined = charge(2551).last() as ChargeState.Declined
        assertEquals("51", declined.tx.responseCode)
        assertTrue(declined.tx.message.contains("fondos insuficientes"))
    }

    @Test
    fun `sin conexión y bajo el límite se aprueba offline y el aviso queda en la cola`() {
        gateway.mode = ScriptedGateway.Mode.NOT_CONNECTED
        val offline = charge(3000).last() as ChargeState.OfflineApproved
        assertEquals(TxStatus.OFFLINE_APPROVED, offline.tx.status)
        val item = outbox.all().single()
        assertEquals(OutboxKind.ADVICE, item.kind)

        // Vuelve la red: el aviso sale, el host lo verifica y la cola queda vacía.
        gateway.mode = ScriptedGateway.Mode.RESPOND
        val result = runBlocking { forwarder.drain() }
        assertEquals(1, result.sent)
        assertTrue(outbox.all().isEmpty())
        val advice = gateway.received.last()
        assertEquals(Mti.FINANCIAL_ADVICE, advice.mti)
        assertFalse("Un aviso offline no lleva PIN block", Fields.PIN_BLOCK in advice)
    }

    @Test
    fun `sin conexión y sobre el límite se rechaza sin dejar nada en la cola`() {
        gateway.mode = ScriptedGateway.Mode.NOT_CONNECTED
        assertTrue(charge(config.floorLimitCents + 1).last() is ChargeState.Failed)
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `timeout del host encola la reversa y nunca reintenta el cobro`() {
        gateway.mode = ScriptedGateway.Mode.NO_RESPONSE
        val states = charge(2550)
        assertTrue(states.any { it is ChargeState.Reversing })
        assertTrue(states.last() is ChargeState.ReversalPending)
        // Una sola venta salió; lo que queda es la reversa, no otro cobro.
        assertEquals(1, gateway.received.count { it.mti == Mti.FINANCIAL_REQUEST })
        assertEquals(OutboxKind.REVERSAL, outbox.all().single().kind)

        gateway.mode = ScriptedGateway.Mode.RESPOND
        runBlocking { forwarder.drain() }
        val reversal = gateway.received.last()
        assertEquals(Mti.REVERSAL_REQUEST, reversal.mti)
        val original = (states.last() as ChargeState.ReversalPending).tx
        assertEquals("0200" + original.stan + original.transmissionDateTime, reversal.text(Fields.ORIGINAL_DATA).take(20))
        assertEquals(TxStatus.REVERSED, transactions.all().single().status)
        assertEquals(1, gateway.received.count { it.mti == Mti.FINANCIAL_REQUEST })
    }

    @Test
    fun `la reversa reenviada sale con exactamente los mismos bytes`() {
        gateway.mode = ScriptedGateway.Mode.NO_RESPONSE
        charge(2550)
        val queued = outbox.all().single().payload
        runBlocking { forwarder.drain() }
        assertTrue(queued.contentEquals(outbox.all().single().payload))
        assertEquals("Un intento durante el cobro y otro ahora", 2, outbox.all().single().attempts)
    }

    @Test
    fun `si la app murió autorizando, al volver se reversa`() = runBlocking {
        transactions.save(
            Transaction(
                id = "zombie", stan = "000099", transmissionDateTime = "1030150000", amountCents = 1200,
                currency = "604", maskedPan = "411111******1111", status = TxStatus.AUTHORIZING, createdAt = 0,
            ),
        )
        pinpad.readCard(100, "604", 1) // el PINpad ya tiene un KSN en uso
        useCase.recover()
        assertEquals(TxStatus.REVERSED, transactions.find("zombie")!!.status)
        assertEquals(Mti.REVERSAL_REQUEST, gateway.received.single().mti)
    }

    @Test
    fun `si el PINpad no responde, el cobro falla sin tocar al host`() {
        pinpad.unavailable = true
        assertTrue(charge(2550).last() is ChargeState.Failed)
        assertTrue(gateway.received.isEmpty())
        assertTrue(transactions.all().isEmpty())
    }

    @Test
    fun `si el cliente cancela en el PINpad, el cobro falla con el motivo`() {
        pinpad.injectFault("CARD", "USER_CANCEL")
        val failed = charge(2550).last() as ChargeState.Failed
        assertTrue(failed.message.contains("canceló"))
    }
}
