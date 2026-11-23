package es.girarkas.giraquest.crash

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log de arranque paso a paso (v0.1.2).
 *
 * La v0.1 y la v0.1.1 crasheaban al abrir en el móvil de Edu sin dejar
 * rastro útil: el informe de crash solo existía si el CrashReporter
 * llegaba a instalarse y el diálogo que lo enseñaba vivía en
 * WizardActivity, que es posterior al punto donde la app moría.
 *
 * Este log se escribe desde la primera línea de Application.onCreate,
 * una línea por hito, con flush inmediato tras cada línea. Aunque el
 * crash mate al proceso (incluso con un fallo nativo que el handler
 * de Java no ve), el archivo dice hasta dónde llegó el arranque.
 *
 * Dos copias, con reglas distintas (v0.1.13: antes cada marca
 * reescribía la copia de Descargas borrando por DISPLAY_NAME e
 * insertando de nuevo en MediaStore; ese borrado fallaba en silencio
 * muchas veces y MediaStore creaba `GiraQuest-startup (1).log`,
 * `(2).log`… — Edu acabó con mogollón de archivos de log sin sentido
 * en Descargas):
 *  - filesDir/startup.log: la ÚNICA copia que escribe mark(), con
 *    append. Es la copia fiable; se lee con el diálogo de crash, con
 *    "Ver log" o compartiendo. Rota a ~200 KB (conserva la cola).
 *  - Descargas/GiraQuest-startup.log: SOLO bajo demanda — cuando el
 *    usuario pulsa "Compartir log" en Ajustes, o al producirse un
 *    crash (una sola escritura). writeDownloadsFile ACTUALIZA el mismo
 *    archivo si ya existe (openOutputStream "wt") y solo inserta si no
 *    existe, así que nunca crea duplicados.
 */
object StartupLog {

    const val STARTUP_FILE_NAME = "startup.log"
    const val DOWNLOAD_STARTUP_FILE_NAME = "GiraQuest-startup.log"

    /** Marcador de cierre limpio (v0.1.33): MainActivity lo escribe en
     *  onDestroy. Si al arrancar hay log de una sesión anterior pero NO
     *  está este marcador, la sesión anterior terminó sin cierre limpio
     *  (posible crash o cierre del sistema): queda en el log. */
    const val CLEAN_SHUTDOWN_FILE_NAME = "clean_shutdown"

    /** Escribe el marcador de cierre limpio (llamado desde
     *  MainActivity.onDestroy; best-effort, nunca lanza). */
    fun markCleanShutdown(context: Context) {
        try {
            File(context.filesDir, CLEAN_SHUTDOWN_FILE_NAME).writeText("ok")
        } catch (_: Throwable) {
        }
    }

    /** Comprueba cómo terminó la sesión anterior (v0.1.33) y lo deja en
     *  el log ANTES de la marca "app start": si había log previo pero
     *  ningún marcador de cierre limpio, la sesión anterior terminó
     *  mal (posible crash). El marcador se borra al leerlo. En el
     *  primer arranque no hay log previo y no se dice nada. */
    fun checkPreviousSession(context: Context) {
        try {
            val marker = File(context.filesDir, CLEAN_SHUTDOWN_FILE_NAME)
            val hadPrevious = File(context.filesDir, STARTUP_FILE_NAME).exists()
            if (marker.exists()) {
                mark(context, "sesión anterior: cierre limpio")
                try { marker.delete() } catch (_: Throwable) {}
            } else if (hadPrevious) {
                mark(context, "sesión anterior: terminó sin cierre limpio (posible crash o cierre del sistema)")
            }
        } catch (_: Throwable) {
        }
    }

    /** Tamaño máximo del log interno antes de rotar. */
    private const val MAX_INTERNAL_BYTES = 200 * 1024L
    /** Bytes que se conservan al rotar (la cola del log). */
    private const val KEEP_TAIL_BYTES = 100 * 1024L

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Una línea de log: marca de tiempo + hito. Con flush inmediato.
     *  SOLO escribe la copia interna (v0.1.13): la copia de Descargas
     *  ya no se reescribe en cada marca. */
    @Synchronized
    fun mark(context: Context, tag: String) {
        val line = "${dateFormat.format(Date())} $tag\n"
        try {
            val f = File(context.filesDir, STARTUP_FILE_NAME)
            FileOutputStream(f, true).use {
                it.write(line.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            rotateIfNeeded(f)
        } catch (_: Throwable) {
            // Sin filesDir no hay nada que hacer; no crashear por el log.
        }
    }

    /** Si el log interno supera ~200 KB, conserva solo la cola (~100 KB). */
    private fun rotateIfNeeded(f: File) {
        try {
            if (f.length() <= MAX_INTERNAL_BYTES) return
            val bytes = f.readBytes()
            if (bytes.size <= MAX_INTERNAL_BYTES) return
            var start = bytes.size - KEEP_TAIL_BYTES.toInt()
            // Cortar en un salto de línea para no dejar media línea.
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            if (start < bytes.size) start++
            val tail = bytes.copyOfRange(start.coerceIn(0, bytes.size), bytes.size)
            FileOutputStream(f, false).use { it.write(tail) }
        } catch (_: Throwable) {
        }
    }

    fun readStartupLog(context: Context): String? {
        return try {
            val f = File(context.filesDir, STARTUP_FILE_NAME)
            if (f.exists()) f.readText() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clearStartupLog(context: Context) {
        try {
            File(context.filesDir, STARTUP_FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * Escribe la copia actual del log interno en Descargas. SOLO bajo
     * demanda (botón "Compartir log" o crash): mark() ya no llama aquí.
     */
    fun exportToDownloads(context: Context): Boolean {
        val text = readStartupLog(context) ?: return false
        return writeDownloadsFile(context, DOWNLOAD_STARTUP_FILE_NAME, text)
    }

    /**
     * Refresca la copia de Descargas desde el log interno vivo y deja
     * constancia en el propio log interno de si la escritura funcionó
     * (v0.1.17). Edu compartía el archivo de Descargas directamente
     * desde el explorador de archivos en vez del botón de la app, y ese
     * archivo llevaba congelado desde las 00:07:14 (última vez que se
     * pulsó "Compartir log"): cada log que mandaba era el mismo texto
     * viejo. Refrescarlo en cada arranque lo deja, como mucho, una
     * sesión desactualizado; y si la escritura en MediaStore falla en
     * su móvil (HyperOS), el siguiente log lo dirá en vez de fallar en
     * silencio como hasta ahora.
     */
    fun refreshDownloadsCopy(context: Context): Boolean {
        val ok = exportToDownloads(context)
        try {
            val f = File(context.filesDir, STARTUP_FILE_NAME)
            FileOutputStream(f, true).use {
                it.write(
                    "${dateFormat.format(Date())} log: copia Descargas actualizada: $ok\n"
                        .toByteArray(Charsets.UTF_8)
                )
            }
        } catch (_: Throwable) {
        }
        return ok
    }

    /**
     * Copia del log interno lista para compartir (v0.1.17): el archivo
     * de cacheDir con una cabecera (versión, fecha/hora, dispositivo)
     * seguida del contenido vivo del log. Compartir ESTE archivo (vía
     * FileProvider) en vez del de Descargas garantiza que lo que sale
     * del teléfono es el log actual, no una copia vieja.
     */
    fun shareSnapshotFile(context: Context, versionName: String, versionCode: Int): File? {
        val text = readStartupLog(context) ?: return null
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val header = "GiraQuest v$versionName ($versionCode) — log compartido $date\n" +
                "Marca/modelo: ${Build.MANUFACTURER} ${Build.MODEL} — " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "---\n"
            val dir = File(context.cacheDir, "log-share").apply { mkdirs() }
            val f = File(dir, DOWNLOAD_STARTUP_FILE_NAME)
            f.writeText(header + text, Charsets.UTF_8)
            f
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Escribe (o ACTUALIZA) un archivo de texto en Descargas vía
     * MediaStore. No necesita permiso de almacenamiento: el archivo es
     * de la app.
     *
     * v0.1.13: si ya existe un archivo con ese nombre, se abre su URI
     * con openOutputStream(uri, "wt") (trunca y reescribe EN EL MISMO
     * archivo) en vez de borrarlo e insertar uno nuevo. El borrado +
     * inserción anterior fallaba en silencio muchas veces y MediaStore
     * creaba `GiraQuest-startup (1).log`, `(2).log`… Solo se inserta
     * si no existe ninguno. Si hay varias entradas con el mismo nombre
     * (restos de antes), se actualiza la más reciente y se borran las
     * demás.
     */
    fun writeDownloadsFile(context: Context, displayName: String, text: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection =
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val existing = findDownloadsUris(context, displayName)
                if (existing.isNotEmpty()) {
                    // Actualizar la más reciente en el mismo archivo.
                    val keep = existing.first()
                    resolver.openOutputStream(keep, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        it.flush()
                    } ?: return false
                    // Borrar duplicados con el mismo nombre, si los hay.
                    existing.drop(1).forEach { uri ->
                        try {
                            resolver.delete(uri, null, null)
                        } catch (_: Throwable) {
                        }
                    }
                    true
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                    }
                    val uri = resolver.insert(collection, values) ?: return false
                    resolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        it.flush()
                    } ?: return false
                    true
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                File(downloads, displayName).writeText(text)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * URIs en Descargas con ese DISPLAY_NAME exacto, de más reciente a
     * más antigua (por DATE_MODIFIED). Vacía si no hay ninguna.
     */
    private fun findDownloadsUris(context: Context, displayName: String): List<Uri> {
        val out = ArrayList<Pair<Long, Uri>>()
        try {
            val resolver = context.contentResolver
            val collection =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cursor = resolver.query(
                collection,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DATE_MODIFIED
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null
            )
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val date = it.getLong(dateCol)
                    out += date to collection.buildUpon().appendPath(id.toString()).build()
                }
            }
        } catch (_: Throwable) {
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * Limpieza al arrancar (v0.1.13): borra de Descargas los duplicados
     * que sembró la v0.1.12 y anteriores — `GiraQuest-startup (N).log`
     * y `GiraQuest-crash (N).txt` — dejando como mucho el archivo
     * actual sin número. Solo toca archivos propios de la app con ese
     * patrón exacto.
     */
    fun cleanupDownloadDuplicates(context: Context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val resolver = context.contentResolver
            val collection =
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cursor = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR " +
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("GiraQuest-startup (%.log", "GiraQuest-crash (%.txt"),
                null
            )
            var deleted = 0
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    try {
                        val uri = collection.buildUpon().appendPath(id.toString()).build()
                        if (resolver.delete(uri, null, null) > 0) deleted++
                    } catch (_: Throwable) {
                    }
                }
            }
            if (deleted > 0) {
                // No usar mark() aquí (escribiría en el log que se está
                // limpiando y no hay contexto útil); dejar constancia
                // mínima en el propio log interno sin recursión.
                try {
                    FileOutputStream(File(context.filesDir, STARTUP_FILE_NAME), true).use {
                        it.write(
                            "${dateFormat.format(Date())} log: borrados $deleted duplicados de Descargas\n"
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Borra solo la copia de Descargas de un archivo (la interna se conserva). */
    fun deleteDownloadsFile(context: Context, displayName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                findDownloadsUris(context, displayName).forEach { uri ->
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Throwable) {
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                File(downloads, displayName).delete()
            }
        } catch (_: Throwable) {
        }
    }
}
