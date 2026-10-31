package io.github.marlonpya.pos.data.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.marlonpya.pos.MainActivity

/** Al encender el equipo abre la caja; sin esto el terminal quedaría en el launcher. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
