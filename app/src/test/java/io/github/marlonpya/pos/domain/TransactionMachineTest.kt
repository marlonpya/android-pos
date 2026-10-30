package io.github.marlonpya.pos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMachineTest {

    private val tx = Transaction(
        id = "t1", stan = "000001", transmissionDateTime = "1030153000", amountCents = 2550,
        currency = "604", maskedPan = "411111******1111", status = TxStatus.AUTHORIZING, createdAt = 0,
    )

    private fun authorizing(): ChargeState {
        val reading = TransactionMachine.next(ChargeState.Idle, ChargeEvent.Start(2550))
        return TransactionMachine.next(reading, ChargeEvent.CardRead("411111******1111"))
    }

    @Test
    fun `el camino feliz va de leer tarjeta a autorizar a aprobada`() {
        val approved = TransactionMachine.next(authorizing(), ChargeEvent.HostApproved(tx))
        assertTrue(approved is ChargeState.Approved)
        assertTrue(approved.isFinal)
    }

    @Test
    fun `un timeout lleva a reversar, nunca a rechazada ni aprobada`() {
        val reversing = TransactionMachine.next(authorizing(), ChargeEvent.Uncertain(tx))
        assertTrue(reversing is ChargeState.Reversing)
        assertTrue(!reversing.isFinal)
        assertThrows(IllegalTransition::class.java) { TransactionMachine.next(reversing, ChargeEvent.HostApproved(tx)) }
        assertThrows(IllegalTransition::class.java) { TransactionMachine.next(reversing, ChargeEvent.HostDeclined(tx)) }
        assertThrows(IllegalTransition::class.java) { TransactionMachine.next(reversing, ChargeEvent.Start(2550)) }
    }

    @Test
    fun `desde reversar solo se sale a reversada o a reversa pendiente`() {
        val reversing = TransactionMachine.next(authorizing(), ChargeEvent.Uncertain(tx))
        assertTrue(TransactionMachine.next(reversing, ChargeEvent.ReversalConfirmed(tx)) is ChargeState.Reversed)
        assertTrue(TransactionMachine.next(reversing, ChargeEvent.ReversalQueued(tx)) is ChargeState.ReversalPending)
    }

    @Test
    fun `no se puede empezar otro cobro mientras uno está en curso`() {
        assertThrows(IllegalTransition::class.java) { TransactionMachine.next(authorizing(), ChargeEvent.Start(100)) }
    }

    @Test
    fun `desde un estado final se puede empezar otro cobro o volver a inicio`() {
        val failed = ChargeState.Failed("x")
        assertEquals(ChargeState.ReadingCard(100), TransactionMachine.next(failed, ChargeEvent.Start(100)))
        assertEquals(ChargeState.Idle, TransactionMachine.next(failed, ChargeEvent.Reset))
    }
}
