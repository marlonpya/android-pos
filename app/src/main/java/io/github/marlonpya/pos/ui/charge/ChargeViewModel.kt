package io.github.marlonpya.pos.ui.charge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.marlonpya.pos.domain.ChargeEvent
import io.github.marlonpya.pos.domain.ChargeState
import io.github.marlonpya.pos.domain.ChargeUseCase
import io.github.marlonpya.pos.domain.TransactionMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChargeUiState(
    /** Monto en centavos, armado dígito a dígito como en una caja. */
    val amountCents: Long = 0,
    val charge: ChargeState = ChargeState.Idle,
) {
    val busy: Boolean get() = charge !is ChargeState.Idle && !charge.isFinal
    val canCharge: Boolean get() = amountCents > 0 && !busy
}

class ChargeViewModel(
    private val useCase: ChargeUseCase,
    private val onFinished: () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(ChargeUiState())
    val state: StateFlow<ChargeUiState> = _state.asStateFlow()

    fun digit(d: Int) = _state.update {
        if (it.busy || it.amountCents >= MAX_AMOUNT / 10) it else it.copy(amountCents = it.amountCents * 10 + d)
    }

    fun backspace() = _state.update { if (it.busy) it else it.copy(amountCents = it.amountCents / 10) }

    fun clear() = _state.update { if (it.busy) it else ChargeUiState() }

    /** Montos que provocan cada respuesta en gateway-mock. */
    fun preset(cents: Long) = _state.update { if (it.busy) it else it.copy(amountCents = cents) }

    fun charge() {
        val amount = _state.value.amountCents
        if (!_state.value.canCharge) return
        viewModelScope.launch {
            useCase.charge(amount)
                .catch { e -> emit(ChargeState.Failed("Error inesperado: ${e.message}")) }
                .collect { s -> _state.update { it.copy(charge = s) } }
            onFinished()
        }
    }

    fun newSale() = _state.update {
        if (it.busy) it else ChargeUiState(charge = TransactionMachine.next(it.charge, ChargeEvent.Reset))
    }

    private companion object {
        const val MAX_AMOUNT = 99_999_999L
    }
}
