package es.girarkas.giraquest.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore

/**
 * Reinicio limpio del proceso principal (patrón ProcessPhoenix).
 *
 * Motivo técnico (AOSP StorageManagerService.java, Android 12–14 / API 31–34):
 * Cuando una app instala desde cero y el usuario concede por primera vez
 * "Instalar aplicaciones desconocidas" (OP_REQUEST_INSTALL_PACKAGES), Android 12+
 * NO mata ni reinicia el proceso (a diferencia de Android 11):
 *   "starting from S, only kill the app when it transitions away from MODE_ALLOWED"
 *   ("for those that [handle OBBs], they can restart themselves.")
 * Como Zygote asigna el espacio de montaje (/mnt/installer/0 vs /mnt/user/0)
 * y el GID ext_obb_rw únicamente al hacer fork() del proceso, el proceso actual
 * sigue bloqueado en MOUNT_EXTERNAL_DEFAULT hasta que se reinicia por completo.
 *
 * PhoenixActivity corre en el proceso auxiliar `:phoenix`, mata el PID del
 * proceso principal y relanza MainActivity en un proceso nuevo nacido de Zygote
 * ya con MOUNT_EXTERNAL_INSTALLER activo.
 */
object ProcessRebirth {

    private const val KEY_RESTART_INTENT = "giraquest_restart_intent"
    private const val KEY_MAIN_PROCESS_PID = "giraquest_main_process_pid"

    /** Ventana mínima entre reinicios automáticos para evitar bucles si un
     *  dispositivo exótico negara igualmente el montaje tras reiniciar. */
    private const val RESTART_COOLDOWN_MS = 30_000L

    fun canTriggerAutoRestart(context: Context): Boolean {
        val last = SettingsStore(context).lastMountRestartMs
        return System.currentTimeMillis() - last > RESTART_COOLDOWN_MS
    }

    fun triggerRebirth(context: Context) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(context.packageName)
            ?: return
        val component = launchIntent.component ?: return
        val nextIntent = Intent.makeRestartActivityTask(component).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        SettingsStore(context).markMountRestartNow()
        StartupLog.mark(
            context,
            "reiniciando proceso (:phoenix) para activar MOUNT_EXTERNAL_INSTALLER en Android/obb"
        )
        StartupLog.markCleanShutdown(context)
        val phoenixIntent = Intent(context, PhoenixActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(KEY_RESTART_INTENT, nextIntent)
            putExtra(KEY_MAIN_PROCESS_PID, Process.myPid())
        }
        context.startActivity(phoenixIntent)
    }

    class PhoenixActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val mainPid = intent.getIntExtra(KEY_MAIN_PROCESS_PID, -1)
            if (mainPid > 0 && mainPid != Process.myPid()) {
                Process.killProcess(mainPid)
            }
            @Suppress("DEPRECATION")
            val restartIntent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(KEY_RESTART_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(KEY_RESTART_INTENT)
            }
            val target = restartIntent ?: packageManager.getLaunchIntentForPackage(packageName)
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(target)
            }
            finish()
            Runtime.getRuntime().exit(0)
        }
    }
}
