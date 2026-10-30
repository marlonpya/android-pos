package io.github.marlonpya.pos.ui.chaos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.marlonpya.pos.data.gateway.GatewayAdminClient
import io.github.marlonpya.pos.data.gateway.NetworkSwitch
import io.github.marlonpya.pos.data.pinpad.LinkedPinpad
import io.github.marlonpya.pos.data.pinpad.PinpadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChaosViewModel(
    private val network: NetworkSwitch,
    private val pinpad: LinkedPinpad,
    private val admin: GatewayAdminClient,
    private val onNetworkBack: () -> Unit,
) : ViewModel() {
    val online = network.online
    val gatewayMode = MutableStateFlow<String?>(null)
    val pinpadSource = MutableStateFlow(pinpad.source)
    val log = MutableStateFlow("")

    fun setOnline(value: Boolean) {
        network.online.value = value
        if (value) onNetworkBack()
        say(if (value) "Red del terminal activa: la cola sale sola" else "Red del terminal apagada (modo avión simulado)")
    }

    fun setPinpadSource(source: PinpadSource) {
        pinpad.source = source
        pinpadSource.value = source
        say("PINpad: ${if (source == PinpadSource.IN_APP) "simulador interno" else "simulador por TCP"}")
    }

    fun refreshGateway() = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { admin.chaos() } }
        result.onSuccess { gatewayMode.value = it.mode }
            .onFailure { gatewayMode.value = null; say("gateway-mock no responde en su puerto de administración") }
    }

    fun setGatewayMode(mode: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { admin.setChaos(mode, 5_000) } }
        result.onSuccess { gatewayMode.value = it.mode; say("Gateway en modo ${it.mode}") }
            .onFailure { say("No se pudo cambiar el modo del gateway: ${it.message}") }
    }

    fun pinpadFault(name: String, value: String, label: String) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { pinpad.injectFault(name, value) } }
        say(if (result.isSuccess) "PINpad: $label" else "PINpad no disponible: ${result.exceptionOrNull()?.message}")
    }

    private fun say(text: String) {
        log.value = text
    }
}

private data class Fault(val name: String, val value: String, val label: String)

private val PINPAD_FAULTS = listOf(
    Fault("NAK", "1", "NAK a la próxima orden"),
    Fault("DROP_ACK", "1", "ACK perdido"),
    Fault("CORRUPT", "1", "LRC dañado en la respuesta"),
    Fault("DELAY", "35000", "Demora más que el timeout"),
    Fault("DISCONNECT", "1", "Cable desconectado"),
    Fault("CARD", "CARD_REMOVED", "Tarjeta retirada"),
    Fault("CARD", "USER_CANCEL", "Cliente cancela"),
    Fault("PIN", "9999", "PIN equivocado"),
    Fault("CARD_BRAND", "MASTERCARD", "Tarjeta Mastercard"),
    Fault("RESET", "", "Todo normal"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChaosScreen(viewModel: ChaosViewModel, modifier: Modifier = Modifier) {
    val online by viewModel.online.collectAsStateWithLifecycle()
    val mode by viewModel.gatewayMode.collectAsStateWithLifecycle()
    val source by viewModel.pinpadSource.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshGateway() }

    Column(
        modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Panel de caos", style = MaterialTheme.typography.headlineSmall)
        Text(log.ifEmpty { "Provoca fallos y mira cómo reacciona el cobro." }, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Red del terminal", style = MaterialTheme.typography.titleMedium)
                Text("Apágala a mitad de un cobro bajo S/ 50.00 para ver store and forward.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = online, onCheckedChange = viewModel::setOnline)
        }

        HorizontalDivider()
        Text("Gateway (gateway-mock)", style = MaterialTheme.typography.titleMedium)
        Text(
            if (mode == null) "Sin conexión con la administración del gateway" else "Modo actual: $mode",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GatewayAdminClient.MODES.forEach { m ->
                FilterChip(selected = mode == m, onClick = { viewModel.setGatewayMode(m) }, label = { Text(m) })
            }
        }

        HorizontalDivider()
        Text("PINpad", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == PinpadSource.IN_APP,
                onClick = { viewModel.setPinpadSource(PinpadSource.IN_APP) },
                label = { Text("Simulador interno") },
            )
            FilterChip(
                selected = source == PinpadSource.TCP,
                onClick = { viewModel.setPinpadSource(PinpadSource.TCP) },
                label = { Text("Simulador TCP :9100") },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PINPAD_FAULTS.forEach { f ->
                OutlinedButton(onClick = { viewModel.pinpadFault(f.name, f.value, f.label) }) { Text(f.label) }
            }
        }
        Text(
            "Los fallos de PINpad aplican a la próxima orden. \"Demora\" supera el timeout de respuesta (30 s).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
