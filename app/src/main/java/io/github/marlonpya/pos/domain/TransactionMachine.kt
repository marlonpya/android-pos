package io.github.marlonpya.pos.domain

/** Lo que ve el cajero durante un cobro. */
sealed interface ChargeState {
    data object Idle : ChargeState

    data class ReadingCard(val amountCents: Long) : ChargeState

    data class Authorizing(val amountCents: Long, val maskedPan: String) : ChargeState

    data class Approved(val tx: Transaction) : ChargeState

    data class Declined(val tx: Transaction) : ChargeState

    data class OfflineApproved(val tx: Transaction) : ChargeState

    /** Hubo timeout: se está mandando la reversa. Nunca se muestra como aprobado ni rechazado. */
    data class Reversing(val tx: Transaction) : ChargeState

    data class Reversed(val tx: Transaction) : ChargeState

    /** La reversa no salió todavía; queda en la cola y saldrá sola. */
    data class ReversalPending(val tx: Transaction) : ChargeState

    data class Failed(val message: String) : ChargeState

    val isFinal: Boolean
        get() = this is Approved || this is Declined || this is OfflineApproved ||
            this is Reversed || this is ReversalPending || this is Failed
}

sealed interface ChargeEvent {
    data class Start(val amountCents: Long) : ChargeEvent

    data class CardRead(val maskedPan: String) : ChargeEvent

    data class HostApproved(val tx: Transaction) : ChargeEvent

    data class HostDeclined(val tx: Transaction) : ChargeEvent

    data class ApprovedOffline(val tx: Transaction) : ChargeEvent

    /** Se mandó la solicitud y no llegó respuesta. */
    data class Uncertain(val tx: Transaction) : ChargeEvent

    data class ReversalConfirmed(val tx: Transaction) : ChargeEvent

    data class ReversalQueued(val tx: Transaction) : ChargeEvent

    data class Fail(val message: String) : ChargeEvent

    data object Reset : ChargeEvent
}

class IllegalTransition(state: ChargeState, event: ChargeEvent) :
    IllegalStateException("No hay transición desde ${state::class.simpleName} con ${event::class.simpleName}")

/**
 * Las transiciones permitidas de un cobro, en un solo lugar.
 *
 * La regla que esta tabla protege: desde [ChargeState.Authorizing] un timeout
 * solo lleva a [ChargeState.Reversing], y desde ahí solo se sale a reversado
 * o a reversa pendiente. Un timeout nunca es un rechazo ni una aprobación, y
 * nunca se reintenta el cobro: se reintenta la reversa.
 */
object TransactionMachine {

    fun next(state: ChargeState, event: ChargeEvent): ChargeState = when {
        event is ChargeEvent.Start && (state is ChargeState.Idle || state.isFinal) ->
            ChargeState.ReadingCard(event.amountCents)

        event is ChargeEvent.Reset && (state is ChargeState.Idle || state.isFinal) -> ChargeState.Idle

        state is ChargeState.ReadingCard -> when (event) {
            is ChargeEvent.CardRead -> ChargeState.Authorizing(state.amountCents, event.maskedPan)
            is ChargeEvent.Fail -> ChargeState.Failed(event.message)
            else -> throw IllegalTransition(state, event)
        }

        state is ChargeState.Authorizing -> when (event) {
            is ChargeEvent.HostApproved -> ChargeState.Approved(event.tx)
            is ChargeEvent.HostDeclined -> ChargeState.Declined(event.tx)
            is ChargeEvent.ApprovedOffline -> ChargeState.OfflineApproved(event.tx)
            is ChargeEvent.Uncertain -> ChargeState.Reversing(event.tx)
            is ChargeEvent.Fail -> ChargeState.Failed(event.message)
            else -> throw IllegalTransition(state, event)
        }

        state is ChargeState.Reversing -> when (event) {
            is ChargeEvent.ReversalConfirmed -> ChargeState.Reversed(event.tx)
            is ChargeEvent.ReversalQueued -> ChargeState.ReversalPending(event.tx)
            else -> throw IllegalTransition(state, event)
        }

        else -> throw IllegalTransition(state, event)
    }
}
