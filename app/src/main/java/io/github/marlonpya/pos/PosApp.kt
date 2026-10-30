package io.github.marlonpya.pos

import android.app.Application
import io.github.marlonpya.pos.data.work.ForwardWorker
import io.github.marlonpya.pos.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PosApp : Application() {

    lateinit var container: AppContainer
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Lo que quedó a medias en la sesión anterior se reversa antes del primer cobro.
        scope.launch {
            runCatching { container.charge.recover() }
            ForwardWorker.schedule(this@PosApp)
        }
    }
}
