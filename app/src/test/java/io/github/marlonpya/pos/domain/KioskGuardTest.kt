package io.github.marlonpya.pos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskGuardTest {
    private var clock = 1_000L
    private val store = FakeSupervisorCodeStore("4321")
    private val guard = KioskGuard(store, { clock }, maxAttempts = 3, lockMillis = 1_000)

    @Test
    fun `arranca bloqueado`() {
        assertEquals(KioskState.LOCKED, guard.state.value)
    }

    @Test
    fun `el código correcto desbloquea`() {
        assertEquals(UnlockResult.Granted, guard.unlock("4321"))
        assertEquals(KioskState.UNLOCKED, guard.state.value)
    }

    @Test
    fun `un código equivocado no desbloquea y cuenta los intentos que quedan`() {
        assertEquals(UnlockResult.Denied(remaining = 2), guard.unlock("0000"))
        assertEquals(KioskState.LOCKED, guard.state.value)
    }

    @Test
    fun `tras los fallos permitidos se bloquea aunque llegue el código correcto`() {
        repeat(2) { guard.unlock("0000") }
        assertTrue(guard.unlock("0000") is UnlockResult.Blocked)
        assertTrue(guard.unlock("4321") is UnlockResult.Blocked)
        assertEquals(KioskState.LOCKED, guard.state.value)
    }

    @Test
    fun `el bloqueo expira`() {
        repeat(3) { guard.unlock("0000") }
        clock += 1_000
        assertEquals(UnlockResult.Granted, guard.unlock("4321"))
    }

    @Test
    fun `un acierto reinicia la cuenta de fallos`() {
        guard.unlock("0000")
        guard.unlock("4321")
        guard.lock()
        assertEquals(UnlockResult.Denied(remaining = 2), guard.unlock("0000"))
    }

    @Test
    fun `sin código configurado no se puede salir`() {
        val sinCodigo = KioskGuard(FakeSupervisorCodeStore(), { clock })
        assertEquals(UnlockResult.NotConfigured, sinCodigo.unlock("4321"))
        assertEquals(KioskState.LOCKED, sinCodigo.state.value)
    }

    @Test
    fun `el resultado de un fallo no contiene el código intentado`() {
        assertFalse(guard.unlock("9876").toString().contains("9876"))
    }
}
