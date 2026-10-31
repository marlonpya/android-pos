package io.github.marlonpya.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import io.github.marlonpya.pos.data.kiosk.AndroidKioskPolicy
import io.github.marlonpya.pos.domain.KioskState
import io.github.marlonpya.pos.ui.KioskBottomBar
import io.github.marlonpya.pos.ui.KioskTab
import io.github.marlonpya.pos.ui.admin.SupervisorDialog
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    private lateinit var kioskPolicy: AndroidKioskPolicy

    override fun onResume() {
        super.onResume()
        // Si el sistema sacó la app de lock task (p. ej. tras un reinicio), se vuelve a fijar.
        if ((application as PosApp).container.kiosk.state.value == KioskState.LOCKED) kioskPolicy.enter()
    }

    private fun applyKiosk(state: KioskState) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (state == KioskState.LOCKED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            kioskPolicy.enter()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bars.show(WindowInsetsCompat.Type.systemBars())
            kioskPolicy.exit()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PosApp
        val c = app.container
        kioskPolicy = AndroidKioskPolicy(this)
        val factory = viewModelFactory {
            initializer { ChargeViewModel(c.charge) { ForwardWorker.schedule(app) } }
            initializer { JournalViewModel(c.transactions, c.outbox, c.forwarder) }
            initializer { ChaosViewModel(c.network, c.pinpad, c.gatewayAdmin) { ForwardWorker.schedule(app) } }
        }
        setContent {
            PosTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var showSupervisor by remember { mutableStateOf(false) }
                val kiosk by c.kiosk.state.collectAsState()
                val locked = kiosk == KioskState.LOCKED
                LaunchedEffect(kiosk) { applyKiosk(kiosk) }
                BackHandler(enabled = locked) {}
                if (showSupervisor) {
                    SupervisorDialog(c.kiosk, c.supervisorCodes, kiosk) { showSupervisor = false }
                }
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = { KioskBottomBar(TABS, tab, onSelect = { tab = it }) },
                    ) { padding ->
                        val modifier = Modifier.padding(padding)
                        when (tab) {
                            0 -> ChargeScreen(vm<ChargeViewModel>(factory), modifier)
                            1 -> JournalScreen(vm<JournalViewModel>(factory), modifier)
                            else -> ChaosScreen(vm<ChaosViewModel>(factory), modifier)
                        }
                    }
                    // Zona oculta: pulsación larga en la esquina superior para el supervisor.
                    Box(
                        Modifier
                            .size(56.dp)
                            .combinedClickable(onClick = {}, onLongClick = { showSupervisor = true }),
                    )
                }
            }
        }
    }

    private companion object {
        val TABS = listOf(
            KioskTab("Cobrar", R.drawable.ic_point_of_sale),
            KioskTab("Bitácora", R.drawable.ic_receipt),
            KioskTab("Caos", R.drawable.ic_bolt),
        )
    }
}

@androidx.compose.runtime.Composable
private inline fun <reified T : ViewModel> vm(factory: androidx.lifecycle.ViewModelProvider.Factory): T =
    viewModel(factory = factory)
