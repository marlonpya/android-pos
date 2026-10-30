package io.github.marlonpya.pos.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.marlonpya.pos.PosApp
import java.util.concurrent.TimeUnit

/**
 * Vacía la cola de store and forward en segundo plano. WorkManager lo corre
 * cuando hay red, sobrevive a que la app se cierre y reintenta con backoff
 * exponencial mientras queden mensajes.
 */
class ForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PosApp).container
        val result = container.forwarder.drain()
        return if (result.remaining == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "store-and-forward"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
