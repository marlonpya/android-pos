package io.github.marlonpya.pos.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.marlonpya.pos.domain.Forwarder
import io.github.marlonpya.pos.domain.OutboxRepository
import io.github.marlonpya.pos.domain.Transaction
import io.github.marlonpya.pos.domain.TransactionRepository
import io.github.marlonpya.pos.ui.money
import io.github.marlonpya.pos.ui.statusColor
import io.github.marlonpya.pos.ui.statusLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalViewModel(
    transactions: TransactionRepository,
    outbox: OutboxRepository,
    private val forwarder: Forwarder,
) : ViewModel() {
    val items: StateFlow<List<Transaction>> =
        transactions.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pending: StateFlow<Int> = outbox.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val lastResult = MutableStateFlow<String?>(null)

    fun forwardNow() = viewModelScope.launch {
        val r = forwarder.drain()
        lastResult.value = "Enviados ${r.sent}, quedan ${r.remaining}"
    }
}

@Composable
fun JournalScreen(viewModel: JournalViewModel, modifier: Modifier = Modifier) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Cola store and forward: $pending", style = MaterialTheme.typography.titleMedium)
                Text(
                    lastResult ?: "Avisos y reversas cifrados con Keystore, esperando al host.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { viewModel.forwardNow() }, enabled = pending > 0) { Text("Enviar ahora") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { tx -> TransactionRow(tx) }
        }
    }
}

@Composable
private fun TransactionRow(tx: Transaction) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(money(tx.amountCents), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(statusLabel(tx.status), color = statusColor(tx.status), fontWeight = FontWeight.Bold)
            }
            Text(
                "STAN ${tx.stan} · ${tx.maskedPan ?: "-"} · ${tx.responseCode ?: "--"}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(tx.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
