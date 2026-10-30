package io.github.marlonpya.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.marlonpya.pos.data.work.ForwardWorker
import io.github.marlonpya.pos.ui.chaos.ChaosScreen
import io.github.marlonpya.pos.ui.chaos.ChaosViewModel
import io.github.marlonpya.pos.ui.charge.ChargeScreen
import io.github.marlonpya.pos.ui.charge.ChargeViewModel
import io.github.marlonpya.pos.ui.journal.JournalScreen
import io.github.marlonpya.pos.ui.journal.JournalViewModel
import io.github.marlonpya.pos.ui.theme.PosTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PosApp
        val c = app.container
        val factory = viewModelFactory {
            initializer { ChargeViewModel(c.charge) { ForwardWorker.schedule(app) } }
            initializer { JournalViewModel(c.transactions, c.outbox, c.forwarder) }
            initializer { ChaosViewModel(c.network, c.pinpad, c.gatewayAdmin) { ForwardWorker.schedule(app) } }
        }
        setContent {
            PosTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { index, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { Icon(painterResource(icon), contentDescription = null) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (tab) {
                        0 -> ChargeScreen(vm<ChargeViewModel>(factory), modifier)
                        1 -> JournalScreen(vm<JournalViewModel>(factory), modifier)
                        else -> ChaosScreen(vm<ChaosViewModel>(factory), modifier)
                    }
                }
            }
        }
    }

    private companion object {
        val TABS = listOf(
            "Cobrar" to R.drawable.ic_point_of_sale,
            "Bitácora" to R.drawable.ic_receipt,
            "Caos" to R.drawable.ic_bolt,
        )
    }
}

@androidx.compose.runtime.Composable
private inline fun <reified T : ViewModel> vm(factory: androidx.lifecycle.ViewModelProvider.Factory): T =
    viewModel(factory = factory)
