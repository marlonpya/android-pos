package io.github.marlonpya.pos.ui.charge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.marlonpya.pos.domain.ChargeState
import io.github.marlonpya.pos.domain.Transaction
import io.github.marlonpya.pos.ui.money
import io.github.marlonpya.pos.ui.theme.ResultColors

private val PRESETS = listOf(
    2550L to "Aprueba",
    2551L to "51 fondos",
    2505L to "05 no honrar",
    3000L to "Bajo límite offline",
    1_000_100L to "61 límite",
)

@Composable
fun ChargeScreen(viewModel: ChargeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = money(state.amountCents),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (cents, label) ->
                AssistChip(onClick = { viewModel.preset(cents) }, label = { Text("${money(cents)} · $label") })
            }
        }
        Keypad(onDigit = viewModel::digit, onBackspace = viewModel::backspace, onClear = viewModel::clear, enabled = !state.busy)
        if (state.charge.isFinal) {
            OutlinedButton(onClick = viewModel::newSale, modifier = Modifier.fillMaxWidth()) { Text("Nueva venta") }
        } else {
            Button(
                onClick = viewModel::charge,
                enabled = state.canCharge,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Cobrar ${money(state.amountCents)}") }
        }
        StatusCard(state.charge)
    }
}

@Composable
private fun Keypad(onDigit: (Int) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit, enabled: Boolean) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d -> KeyButton(d.toString(), enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("C", enabled, onClear)
            KeyButton("0", enabled) { onDigit(0) }
            KeyButton("⌫", enabled, onBackspace)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun StatusCard(charge: ChargeState) {
    val (title, color, detail) = when (charge) {
        ChargeState.Idle -> Triple("Listo para cobrar", ResultColors.neutral, "Ingresa el monto y presiona Cobrar.")
        is ChargeState.ReadingCard -> Triple("Esperando tarjeta y PIN", ResultColors.pending, "El PINpad lee la tarjeta y cifra el PIN.")
        is ChargeState.Authorizing -> Triple("Autorizando ${charge.maskedPan}", ResultColors.pending, "Venta 0200 firmada con MAC enviada al host.")
        is ChargeState.Approved -> Triple("Aprobada", ResultColors.approved, details(charge.tx))
        is ChargeState.Declined -> Triple("Rechazada", ResultColors.declined, details(charge.tx))
        is ChargeState.OfflineApproved -> Triple("Aprobada sin conexión", ResultColors.approved, details(charge.tx))
        is ChargeState.Reversing -> Triple("Sin respuesta: reversando", ResultColors.pending, "Un timeout no es un rechazo. Se manda la reversa 0400.")
        is ChargeState.Reversed -> Triple("Reversada", ResultColors.neutral, details(charge.tx))
        is ChargeState.ReversalPending -> Triple("Reversa en cola", ResultColors.pending, details(charge.tx))
        is ChargeState.Failed -> Triple("No se completó", ResultColors.declined, charge.message)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!charge.isFinal && charge !is ChargeState.Idle) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
                    Spacer(Modifier.size(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = Color.Unspecified)
        }
    }
}

private fun details(tx: Transaction) = buildString {
    appendLine(tx.message)
    appendLine("Tarjeta  ${tx.maskedPan ?: "-"}")
    appendLine("STAN     ${tx.stan}")
    tx.responseCode?.let { appendLine("Código   $it") }
    tx.authCode?.let { appendLine("Autoriz. $it") }
    tx.rrn?.let { appendLine("RRN      $it") }
    tx.ksn?.let { append("KSN      $it") }
}.trimEnd()
