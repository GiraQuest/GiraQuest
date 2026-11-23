package es.girarkas.giraquest.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cazador de crashes (v0.1.1, endurecido en v0.1.2).
 *
 * La v0.1 crasheaba al abrir en el móvil de Edu sin dejar rastro visible.
 * La v0.1.1 añadió este cazador, pero tenía dos agujeros que la v0.1.2
 * cierra:
 *
 *  1. El diálogo que enseña el crash vivía en WizardActivity, que es
 *     posterior al punto donde la app moría (MainActivity / RepoFragment).
 *     Si el crash era determinista ahí, el diálogo no salía nunca.
 *     Ahora MainActivity lo enseña LO PRIMERO, antes que el asistente.
 *  2. El botón "Borrar y cerrar" borraba filesDir/crash.txt: Edu borró
 *     el log sin querer y nos quedamos sin el error. Ahora borrar SOLO
 *     borra la copia de Descargas; la copia interna (filesDir/crash.txt)
 *     se conserva siempre y solo la sobrescribe el siguiente crash.
 *     El botón principal es "Compartir error".
 *
 * Se escribe en DOS sitios:
 *  - filesDir/crash.txt: siempre escribible, no necesita permisos. Es la
 *    copia que se conserva; el diálogo la lee de aquí.
 *  - Descargas/GiraQuest-crash.txt: legible por Edu desde el explorador
 *    de archivos, vía MediaStore (sin permiso de almacenamiento). Se
 *    escribe UNA sola vez al producirse el crash (v0.1.13: antes cada
 *    marca del StartupLog reescribía también su copia de Descargas y
 *    MediaStore sembraba duplicados "(1)", "(2)"…; ahora la copia de
 *    Descargas solo se escribe bajo demanda o al crash, y
 *    writeDownloadsFile actualiza el mismo archivo en vez de borrar e
 *    insertar).
 */
object CrashReporter {

    const val CRASH_FILE_NAME = "crash.txt"
    const val DOWNLOAD_CRASH_FILE_NAME = "GiraQuest-crash.txt"

    private var installed = false

    /** Evita enseñar el diálogo dos veces en el mismo proceso. */
    private var dialogShownThisProcess = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // v0.1.33: el crash queda TAMBIÉN en el log de arranque (no
            // solo en crash.txt): la v0.1.32 crasheó en la pestaña
            // Archivos sin dejar ni FATAL ni crash.txt en el log
            // compartido. Resumen corto (clase + mensaje + 8 primeros
            // marcos) con flush inmediato, ANTES de escribir crash.txt
            // (MediaStore puede ser lento y el proceso muere enseguida).
            try {
                val frames = throwable.stackTrace.take(8)
                    .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                StartupLog.mark(
                    appContext,
                    "CRASH en ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message} [$frames]"
                )
            } catch (_: Throwable) {
                // Si ni el log de arranque funciona, no hay nada más que hacer.
            }
            try {
                writeCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Si ni escribir el crash funciona, no hay nada más que hacer.
            }
            // Dejar que el sistema haga lo suyo (cerrar el proceso).
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Escribe el crash a mano (para errores no fatales que queremos registrar). */
    fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val text = formatCrash(thread, throwable)
        try {
            File(context.filesDir, CRASH_FILE_NAME).writeText(text)
        } catch (_: Exception) {
        }
        try {
            StartupLog.writeDownloadsFile(context, DOWNLOAD_CRASH_FILE_NAME, text)
        } catch (_: Exception) {
            // Sin acceso a Descargas todavía: el de filesDir basta.
        }
    }

    fun readLastCrash(context: Context): String? {
        return try {
            val f = File(context.filesDir, CRASH_FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Borra SOLO la copia de Descargas. La copia interna
     * (filesDir/crash.txt) se conserva siempre: es la única prueba del
     * crash y solo la sobrescribe el siguiente crash. Esto es lo que
     * hace el botón de borrar del diálogo desde la v0.1.2 (antes borraba
     * las dos y Edu perdió el log sin querer).
     */
    fun clearDownloadsCopyOnly(context: Context) {
        StartupLog.deleteDownloadsFile(context, DOWNLOAD_CRASH_FILE_NAME)
    }

    /** Borra también la copia interna. Solo para uso interno controlado
     *  (p. ej. tras un arranque completo y correcto, si hace falta). */
    fun clearInternalCrash(context: Context) {
        try {
            File(context.filesDir, CRASH_FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    private fun formatCrash(thread: Thread, throwable: Throwable): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("GiraQuest — informe de crash")
            appendLine("Fecha: $date")
            appendLine("Marca/modelo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Hilo: ${thread.name}")
            appendLine()
            append(sw.toString())
        }
    }

    /**
     * Si hay un crash guardado del arranque anterior, muestra un diálogo
     * con el error y un botón para compartirlo. Devuelve true si lo mostró.
     *
     * El diálogo se enseña UNA vez por cada contenido de crash (se guarda
     * el hash en preferencias): así no sale en cada arranque, pero la
     * copia interna nunca se borra desde el diálogo. Si llega un crash
     * nuevo, el hash cambia y vuelve a salir.
     */
    fun showLastCrashIfAny(activity: AppCompatActivity): Boolean {
        if (dialogShownThisProcess) return false
        val text = readLastCrash(activity) ?: return false
        val prefs = activity.getSharedPreferences("giraquest", Context.MODE_PRIVATE)
        val hash = text.hashCode().toString()
        if (prefs.getString("crash_dialog_shown_for", null) == hash) return false
        dialogShownThisProcess = true
        prefs.edit().putString("crash_dialog_shown_for", hash).apply()

        val startup = StartupLog.readStartupLog(activity)
        val message = buildString {
            append("GiraQuest se cerró inesperadamente en el arranque anterior. Esto es lo que pasó:\n\n")
            append(text.take(2000))
            if (!startup.isNullOrBlank()) {
                append("\n\n--- Arranque (hasta dónde llegó) ---\n")
                append(startup.takeLast(800))
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("La app se cerró antes")
            .setMessage(message)
            .setPositiveButton("Compartir error") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "GiraQuest — informe de crash")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        text + "\n\n--- Arranque ---\n" + (startup ?: "")
                    )
                }
                activity.startActivity(
                    Intent.createChooser(share, "Compartir informe de crash")
                )
            }
            .setNeutralButton("Cerrar", null)
            .setNegativeButton("Borrar copia de Descargas") { _, _ ->
                // Solo la copia de Descargas. La interna se conserva:
                // es la única prueba del crash y solo la sobrescribe
                // el siguiente crash.
                clearDownloadsCopyOnly(activity)
                Toast.makeText(
                    activity,
                    "Borrada solo la copia de Descargas. La copia interna se conserva.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
        return true
    }
}
