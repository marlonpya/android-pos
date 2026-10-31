package io.github.marlonpya.pos.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class KioskState { LOCKED, UNLOCKED }

sealed interface UnlockResult {
    data object Granted : UnlockResult

    /** Código equivocado; quedan [remaining] intentos antes del bloqueo. */
    data class Denied(val remaining: Int) : UnlockResult

    /** Demasiados fallos: no se acepta ningún código hasta [until] (ms de [KioskGuard.now]). */
    data class Blocked(val until: Long) : UnlockResult

    /** Aún no hay código de supervisor: no se puede salir hasta configurarlo. */
    data object NotConfigured : UnlockResult
}

/**
 * Reglas de salida del modo kiosko. Arranca bloqueado y solo el código de
 * supervisor lo abre. Tras [maxAttempts] fallos seguidos se bloquea [lockMillis];
 * ni el código ni su huella aparecen en resultados ni mensajes.
 */
class KioskGuard(
    private val store: SupervisorCodeStore,
    private val now: () -> Long,
    private val maxAttempts: Int = 5,
    private val lockMillis: Long = 60_000,
) {
    private val _state = MutableStateFlow(KioskState.LOCKED)
    val state: StateFlow<KioskState> = _state

    private var failures = 0
    private var blockedUntil = 0L

    fun unlock(supervisorCode: String): UnlockResult {
        if (!store.isSet()) return UnlockResult.NotConfigured
        val t = now()
        if (t < blockedUntil) return UnlockResult.Blocked(blockedUntil)
        if (store.matches(supervisorCode)) {
            failures = 0
            _state.value = KioskState.UNLOCKED
            return UnlockResult.Granted
        }
        failures++
        if (failures >= maxAttempts) {
            failures = 0
            blockedUntil = t + lockMillis
            return UnlockResult.Blocked(blockedUntil)
        }
        return UnlockResult.Denied(maxAttempts - failures)
    }

    fun lock() {
        _state.value = KioskState.LOCKED
    }
}
