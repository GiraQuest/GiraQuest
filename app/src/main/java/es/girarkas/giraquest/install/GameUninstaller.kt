package es.girarkas.giraquest.install

import android.content.Context
import android.content.Intent
import android.os.Environment
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import java.io.File

/**
 * v0.1.30: limpieza tras desinstalar un juego + borrado de descargas.
 *
 * La desinstalación la hace el SISTEMA (intent ACTION_UNINSTALL_PACKAGE):
 * la app solo la pide y, cuando el sistema confirma (RESULT_OK), limpia
 * sus propios registros. Si el usuario cancela en el diálogo del
 * sistema, NO se toca nada. El sistema borra solo la app, sus datos y
 * sus OBB de /Android/obb/<paquete>/; la app solo limpia su registro de
 * versión instalada, sus pendientes de OBB y su registro de
 * instalación, y —si el usuario lo marcó— sus archivos descargados.
 *
 * También centraliza el borrado de descargas (papelera de Descargados):
 * SOLO Download/GiraQuest/<paquete>/ (partes .7z.001…, .7z unido,
 * game_extracted/, app.apk y temporales). El juego instalado y sus OBB
 * en /Android/obb NO se tocan nunca desde aquí.
 */
object GameUninstaller {

    /** Carpeta de descargas de un paquete:
     *  /sdcard/<downloadFolder>/<paquete>/ (por defecto
     *  Download/GiraQuest/<paquete>/). */
    fun downloadDir(context: Context, packageName: String): File {
        val settings = SettingsStore(context)
        return File(
            Environment.getExternalStorageDirectory(),
            settings.downloadFolder
        ).let { File(it, packageName) }
    }

    /** Tamaño en disco de todo lo descargado de un juego (0 si no hay
     *  nada). Recorre el árbol: es solo stat, no lee contenidos. */
    fun downloadedSizeBytes(context: Context, packageName: String): Long {
        val dir = downloadDir(context, packageName)
        if (!dir.isDirectory) return 0L
        var total = 0L
        try {
            dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        } catch (_: Exception) {
            return 0L
        }
        return total
    }

    /** Borra TODO lo descargado de un juego (su carpeta completa bajo
     *  Download/GiraQuest/). Devuelve los bytes liberados (0 si no
     *  había nada o si falló). Nunca toca /Android/obb ni el juego
     *  instalado. */
    fun deleteDownloads(context: Context, packageName: String): Long {
        val dir = downloadDir(context, packageName)
        val freed = downloadedSizeBytes(context, packageName)
        if (freed <= 0L) {
            StartupLog.mark(context, "descargas: borrar $packageName: nada que borrar")
            return 0L
        }
        val ok = try {
            dir.deleteRecursively()
        } catch (e: Exception) {
            StartupLog.mark(
                context,
                "descargas: borrar $packageName FALLÓ (${e.javaClass.simpleName}: ${e.message})"
            )
            false
        }
        StartupLog.mark(
            context,
            "descargas: borrados los archivos descargados de $packageName " +
                "($freed bytes liberados, borrado=${if (ok) "OK" else "FALLÓ"})"
        )
        return if (ok) freed else 0L
    }

    /** Limpieza tras una desinstalación CONFIRMADA por el sistema:
     *  registro de versión instalada, pendientes de OBB y registro de
     *  instalación. Si deleteDownloads, también sus archivos
     *  descargados. Devuelve los bytes liberados por las descargas (0
     *  si no se pidieron o no había). */
    fun cleanupAfterUninstall(
        context: Context,
        packageName: String,
        deleteDownloads: Boolean,
    ): Long {
        val settings = SettingsStore(context)
        settings.clearInstalledVersion(packageName)
        settings.removePendingObb(packageName)
        settings.removeInstallRecord(packageName)
        // v0.1.33: al desinstalar se borran los avisos ya enviados de
        // este paquete: una reinstalación de la misma versión vuelve a
        // avisar una vez (si no, nunca volvería a avisar).
        settings.clearNotifiedInstalls(packageName)
        var freed = 0L
        if (deleteDownloads) freed = deleteDownloads(context, packageName)
        StartupLog.mark(
            context,
            "desinstalar: $packageName limpio (versión instalada + pendientes de OBB + " +
                "registro borrados${if (deleteDownloads) "; descargas borradas: $freed bytes liberados" else "; descargas conservadas"})"
        )
        return freed
    }

    /** Comprueba si el paquete sigue instalado en el sistema. */
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Lanza un juego instalado (incluye fallback para apps VR sin CATEGORY_LAUNCHER estándar). */
    fun launchPackage(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: run {
            val main = Intent(Intent.ACTION_MAIN).setPackage(packageName)
            val resolved = pm.queryIntentActivities(main, 0).firstOrNull() ?: return false
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
