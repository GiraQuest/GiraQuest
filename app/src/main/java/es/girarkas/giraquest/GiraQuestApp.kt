package es.girarkas.giraquest

import android.app.Application
import android.content.Context
import android.os.Environment
import es.girarkas.giraquest.crash.CrashReporter
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.install.InstallerSession

/**
 * Application de GiraQuest.
 *
 * Orden de arranque (modo seguro):
 *  1. Instalar el cazador de crashes antes que nada, para que cualquier
 *     fallo posterior deje su stack trace en filesDir/crash.txt y en
 *     Descargas/GiraQuest-crash.txt.
 *  2. Escribir la primera marca del log de arranque (StartupLog), con
 *     flush inmediato.
 *  3. Sin registro global de Conscrypt ni claves/ADB al inicio.
 */
class GiraQuestApp : Application() {

    companion object {
        /** Estado de los permisos en el instante del fork() de este proceso.
         *  En Android 12–14, si REQUEST_INSTALL_PACKAGES se concede después
         *  de arrancar, Zygote NO remonta /storage como MOUNT_EXTERNAL_INSTALLER
         *  ni añade el GID ext_obb_rw hasta reiniciar el proceso. */
        @Volatile
        var startedWithInstallPermission: Boolean = false
            private set

        @Volatile
        var startedWithAllFilesPermission: Boolean = false
            private set

        fun needsMountRestart(context: Context): Boolean {
            val hasFiles = Environment.isExternalStorageManager()
            val hasInstall = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                false
            }
            return hasFiles && hasInstall && !startedWithInstallPermission
        }
    }

    override fun onCreate() {
        super.onCreate()
        // El proceso auxiliar :phoenix solo mata el PID antiguo y relanza MainActivity:
        // no debe tocar StartupLog ni lanzar hilos de limpieza.
        if (getProcessName().endsWith(":phoenix")) return
        startedWithAllFilesPermission = try {
            Environment.isExternalStorageManager()
        } catch (_: Throwable) {
            false
        }
        startedWithInstallPermission = try {
            packageManager.canRequestPackageInstalls()
        } catch (_: Throwable) {
            false
        }
        CrashReporter.install(this)
        Thread {
            StartupLog.cleanupDownloadDuplicates(this)
            StartupLog.refreshDownloadsCopy(this)
        }.apply {
            isDaemon = true
            start()
        }
        StartupLog.checkPreviousSession(this)
        StartupLog.mark(this, "app start")
        StartupLog.mark(
            this,
            "permisos al arrancar proceso: archivos=$startedWithAllFilesPermission, " +
                "instalar=$startedWithInstallPermission"
        )
        StartupLog.mark(this, "app created")
        InstallerSession.probeAtStartup(this)
    }
}
