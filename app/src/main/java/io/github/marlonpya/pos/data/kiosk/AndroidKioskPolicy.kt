package io.github.marlonpya.pos.data.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.github.marlonpya.pos.MainActivity
import io.github.marlonpya.pos.domain.KioskPolicy

/**
 * Lock task sobre [DevicePolicyManager]. Si la app es device owner se autoriza a
 * sí misma y se apagan barra de estado, teclado de sistema y pantalla de bloqueo;
 * si no, [Activity.startLockTask] cae en pantalla fija, que el usuario puede
 * abandonar con la combinación de botones del sistema.
 */
class AndroidKioskPolicy(private val activity: Activity) : KioskPolicy {
    private val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(activity, PosDeviceAdminReceiver::class.java)

    override fun isDeviceOwner() = dpm.isDeviceOwnerApp(activity.packageName)

    override fun enter() {
        if (isDeviceOwner()) configure(lock = true)
        runCatching { activity.startLockTask() }
    }

    override fun exit() {
        runCatching { activity.stopLockTask() }
        if (isDeviceOwner()) configure(lock = false)
    }

    private fun configure(lock: Boolean) {
        dpm.setLockTaskPackages(admin, if (lock) arrayOf(activity.packageName) else emptyArray())
        dpm.setStatusBarDisabled(admin, lock)
        dpm.setKeyguardDisabled(admin, lock)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, if (lock) DevicePolicyManager.LOCK_TASK_FEATURE_NONE else ALL_FEATURES)
        }
        if (lock) {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, MainActivity::class.java))
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
        }
    }

    private companion object {
        @Suppress("InlinedApi")
        const val ALL_FEATURES = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
    }
}
