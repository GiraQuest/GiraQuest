package es.girarkas.giraquest.download

import android.content.Context
import es.girarkas.giraquest.crash.StartupLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Extracción de 7z con el binario OFICIAL de 7-Zip (7zzs, Linux ARM64,
 * estáticamente enlazado, v26.06 de https://www.7-zip.org — mismo motor
 * que el 7z del PC).
 *
 * Por qué hace falta (v0.1.12): commons-compress NO abre el meta.7z real
 * del servidor con ninguna de las 4 contraseñas candidatas — en el móvil
 * de Edu falla con "Compressed data is corrupt" al leer los datos. El
 * binario real sí abre esos archivos (verificado en JVM con 7zzs x86-64
 * contra fixtures p7zip) y además DISTINGUE contraseña incorrecta de
 * archivo incompleto, cosa que commons-compress no hace.
 *
 * v0.1.27 — DESACTIVADO (ver findBinary): el binario no se ejecuta
 * en Quest 3 ni en el móvil de Edu (exit 159 / EACCES en todas las
 * vías). La extracción es solo commons-compress; este objeto queda
 * como código muerto documentado por si un dispositivo futuro lo
 * ejecuta (entonces bastará con reactivar findBinary).
 * El binario se ejecuta SIN shell (ProcessBuilder directo) y con
 * LANG=C.UTF-8 / LC_ALL=C.UTF-8, para que 7zzs convierta la contraseña
 * de argv como UTF-8 (igual que p7zip en Linux): el candidato 0 lleva
 * U+FFFD y debe llegar intacto como bytes EF BF BD. La contraseña
 * NUNCA se escribe en el log ni en la salida.
 */
object SevenZipBinary {

    private const val LIB_NAME = "lib7zzs.so"
    private const val ASSET_NAME = "7zzs"

    /** Resultado de una extracción con el binario. */
    sealed class Result {
        /** Extracción correcta (exit 0 y destino con archivos). */
        data object Ok : Result()
        /** El archivo no es un 7z válido o la cabecera está dañada. */
        data class BadArchive(val detail: String) : Result()
        /** Contraseña incorrecta (el binario lo dice explícitamente). */
        data class WrongPassword(val detail: String) : Result()
        /** Archivo incompleto/truncado ("Unexpected end of archive"). */
        data class Truncated(val detail: String) : Result()
        /** Otro error del binario (mensaje de su salida). */
        data class Failed(val detail: String) : Result()
    }

    /** ¿Está el archivo 7z completo? (listado sin contraseña, v0.1.13). */
    sealed class ArchiveCheck {
        /** `7zzs l` lista el contenido: completo (y cabecera legible). */
        data object Complete : ArchiveCheck()
        /** `7zzs l` dice "Unexpected end": descarga truncada. */
        data class Truncated(val detail: String) : ArchiveCheck()
        /** No es un 7z válido. */
        data class BadArchive(val detail: String) : ArchiveCheck()
        /** No se pudo comprobar (sin binario, o cabecera cifrada). */
        data object Unknown : ArchiveCheck()
    }

    @Volatile
    private var cachedBinary: File? = null
    @Volatile
    private var binaryChecked = false

    /** Resultado de arrancar un proceso (exit -2 = ni siquiera arrancó). */
    private data class ProcResult(val exit: Int, val out: String, val startError: String? = null)

    /**
     * Ejecuta el binario sin argumentos y captura salida + código.
     * 7zzs sin args imprime su ayuda y sale 0: si arranca, el binario
     * SIRVE en este dispositivo. Devuelve el resultado o null si no
     * arranca (con el motivo exacto en el log).
     */
    private fun probeBinary(context: Context, binary: File, label: String): ProcResult? {
        val r = runProcess(binary.absolutePath, timeoutSeconds = 15)
        return if (r.startError != null) {
            StartupLog.mark(context, "7zzs prueba [$label]: no arranca (${r.startError})")
            null
        } else {
            StartupLog.mark(
                context,
                "7zzs prueba [$label]: exit=${r.exit}, salida=${r.out.take(120)}"
            )
            if (r.exit == 0 && "7-Zip" in r.out) r else null
        }
    }

    /**
     * Arranca un proceso, consume su salida (stdout+stderr juntos) y
     * espera hasta [timeoutSeconds]. Con entorno UTF-8. Si no arranca,
     * exit=-2 y startError con el motivo (p. ej. "Permission denied"
     * por SELinux, o "Exec format error").
     */
    private fun runProcess(
        vararg args: String,
        timeoutSeconds: Long,
    ): ProcResult {
        val proc = try {
            val pb = ProcessBuilder(*args).redirectErrorStream(true)
            pb.environment()["LANG"] = "C.UTF-8"
            pb.environment()["LC_ALL"] = "C.UTF-8"
            pb.start()
        } catch (e: Throwable) {
            return ProcResult(-2, "", "${e.javaClass.simpleName}: ${e.message}")
        }
        // Cerrar la stdin del hijo: si 7zzs pide la contraseña de forma
        // interactiva (p. ej. `7zzs l` sobre un archivo con cabecera
        // cifrada y sin -p), recibe EOF y falla rápido en vez de quedarse
        // esperando para siempre. Todas las llamadas pasan la contraseña
        // por argv (-p...), nunca por stdin.
        try {
            proc.outputStream.close()
        } catch (_: Exception) {
        }
        val out = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) {
                        if (out.length < 4000) out.append(line).append('\n')
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        val finished = try {
            proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
        if (!finished) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            reader.join(2000)
            return ProcResult(-1, "sin respuesta en ${timeoutSeconds}s")
        }
        reader.join(5000)
        return ProcResult(proc.exitValue(), out.toString())
    }

    /** Prepara una copia ejecutable del asset en [dir]/7zzs. Null si falla. */
    private fun prepareAssetCopy(context: Context, dir: File, label: String): File? {
        return try {
            val binDir = File(dir, "bin").apply { mkdirs() }
            val copy = File(binDir, ASSET_NAME)
            val needsCopy = !copy.isFile || copy.length() <= 0
            if (needsCopy) {
                context.assets.open(ASSET_NAME).use { inp ->
                    copy.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            copy.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "700", copy.absolutePath)).waitFor()
            } catch (_: Exception) {
            }
            StartupLog.mark(
                context,
                "7zzs [$label]: existe=${copy.exists()} bytes=${copy.length()} " +
                    "ejecutable=${copy.canExecute()} legible=${copy.canRead()}"
            )
            if (copy.isFile && copy.canExecute()) copy else null
        } catch (e: Throwable) {
            StartupLog.mark(
                context,
                "7zzs [$label]: no se pudo preparar la copia (${e.javaClass.simpleName}: ${e.message})"
            )
            null
        }
    }

    /**
     * Localiza un 7zzs ejecutable, PROBÁNDOLO de verdad (v0.1.13: la
     * v0.1.12 solo miraba canExecute(), que puede ser true aunque
     * execve falle por SELinux). Devuelve null si ninguna vía arranca;
     * el StartupLog explica por qué falló cada una. Solo registra la
     * vía usada, nunca la contraseña.
     *
     * v0.1.27 — DESACTIVADO: el binario NO se ejecuta en ningún
     * dispositivo Android probado (móvil de Edu: SIGSYS/permiso
     * denegado; Quest 3, log 2026-11-19 15:23:54: exit=159 en lib,
     * error=13 Permission denied en filesDir/bin y cacheDir/bin —
     * "NINGUNA vía ejecuta el binario en este dispositivo"). Intentarlo
     * en cada extracción solo añade líneas al log y ~0,5 s por intento.
     * La extracción es solo commons-compress desde esta versión; si
     * algún día un dispositivo lo ejecuta, bastará con devolver aquí
     * la lógica de abajo (se conserva el código de prueba).
     */
    @Synchronized
    fun findBinary(context: Context): File? {
        if (binaryChecked) return cachedBinary
        binaryChecked = true
        cachedBinary = null
        StartupLog.mark(
            context,
            "7zzs desactivado en v0.1.27: el binario no se ejecuta en " +
                "Quest/Android 14 (exit 159 / EACCES en el log de Edu " +
                "2026-11-19) — extracción solo con commons-compress"
        )
        return null
    }

    @Suppress("UNUSED")
    @Synchronized
    private fun findBinaryLegacy(context: Context): File? {
        if (binaryChecked) return cachedBinary
        binaryChecked = true
        // 0. Diagnóstico del directorio de librerías nativas.
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        try {
            val listing = nativeDir.listFiles()
                ?.joinToString(", ") {
                    "${it.name}(${it.length()}b${if (it.canExecute()) ",+x" else ""})"
                } ?: "(vacío o ilegible)"
            StartupLog.mark(context, "7zzs: nativeLibraryDir=$nativeDir: $listing")
        } catch (e: Throwable) {
            StartupLog.mark(context, "7zzs: no se pudo listar nativeLibraryDir (${e.message})")
        }
        // 1. Librería nativa extraída por el instalador.
        val fromLib = File(nativeDir, LIB_NAME)
        StartupLog.mark(
            context,
            "7zzs [lib]: existe=${fromLib.exists()} bytes=${if (fromLib.exists()) fromLib.length() else -1} " +
                "ejecutable=${fromLib.canExecute()} legible=${fromLib.canRead()}"
        )
        if (fromLib.isFile) {
            if (!fromLib.canExecute()) {
                // El instalador debería dejarlo con +x; intentarlo de todos
                // modos (puede que la app sí pueda cambiarlo).
                try {
                    fromLib.setExecutable(true, false)
                    StartupLog.mark(
                        context,
                        "7zzs [lib]: chmod intentado, ejecutable=${fromLib.canExecute()}"
                    )
                } catch (e: Throwable) {
                    StartupLog.mark(context, "7zzs [lib]: chmod falló (${e.message})")
                }
            }
            if (fromLib.canExecute() && probeBinary(context, fromLib, "lib") != null) {
                StartupLog.mark(context, "7zzs: binario en nativeLibraryDir (verificado ejecutándose)")
                cachedBinary = fromLib
                return fromLib
            }
            StartupLog.mark(context, "7zzs [lib]: no arranca, se prueba la copia del asset")
        }
        // 2. Copia del asset a filesDir/bin.
        prepareAssetCopy(context, context.filesDir, "filesDir/bin")?.let { copy ->
            if (probeBinary(context, copy, "filesDir/bin") != null) {
                StartupLog.mark(context, "7zzs: binario copiado del asset a filesDir/bin (verificado)")
                cachedBinary = copy
                return copy
            }
            StartupLog.mark(context, "7zzs [filesDir/bin]: la copia no arranca (posible SELinux)")
        }
        // 3. Copia del asset a cacheDir/bin.
        prepareAssetCopy(context, context.cacheDir, "cacheDir/bin")?.let { copy ->
            if (probeBinary(context, copy, "cacheDir/bin") != null) {
                StartupLog.mark(context, "7zzs: binario copiado del asset a cacheDir/bin (verificado)")
                cachedBinary = copy
                return copy
            }
            StartupLog.mark(context, "7zzs [cacheDir/bin]: la copia no arranca (posible SELinux)")
        }
        StartupLog.mark(context, "7zzs: NINGUNA vía ejecuta el binario en este dispositivo")
        cachedBinary = null
        return null
    }

    /** Diagnóstico completo en el StartupLog (para el error en pantalla). */
    fun diagnose(context: Context) {
        findBinary(context)
    }

    /**
     * ¿Está el archivo 7z completo? `7zzs l` sin contraseña lista el
     * contenido si la cabecera es legible (meta.7z la tiene legible).
     * Distingue descarga truncada de contraseña incorrecta ANTES de
     * probar contraseñas: un truncado no se arregla con otra contraseña.
     */
    fun checkArchive(context: Context, archive: File): ArchiveCheck {
        val binary = findBinary(context) ?: return ArchiveCheck.Unknown
        val result = runProcess(binary.absolutePath, "l", archive.absolutePath, timeoutSeconds = 60)
        if (result.startError != null) return ArchiveCheck.Unknown
        val (exit, out) = result.exit to result.out
        StartupLog.mark(
            context,
            "7zzs l ${archive.name}: exit=$exit, salida=${out.take(200)}"
        )
        return when {
            exit == 0 -> ArchiveCheck.Complete
            "Unexpected end of archive" in out || "Unexpected end of data" in out ->
                ArchiveCheck.Truncated(out.take(200))
            "Can not open the file as archive" in out || "is not supported archive" in out ->
                ArchiveCheck.BadArchive(out.take(200))
            else -> ArchiveCheck.Unknown
        }
    }

    /**
     * Extrae [archive] en [destDir] con el binario 7zzs y [password].
     * Devuelve el resultado clasificado. Nunca lanza por el proceso en
     * sí: los fallos de ejecución se devuelven como Failed.
     */
    fun extract(
        context: Context,
        archive: File,
        destDir: File,
        password: String,
        timeoutMinutes: Long = 30,
    ): Result {
        val binary = findBinary(context)
            ?: return Result.Failed("binario 7zzs no disponible en este dispositivo")
        destDir.mkdirs()
        val lastLines = ArrayList<String>()
        return try {
            val proc = ProcessBuilder(
                binary.absolutePath,
                "x",
                "-p$password",
                "-y",
                archive.absolutePath,
                "-o" + destDir.absolutePath,
            ).redirectErrorStream(true).apply {
                // argv UTF-8: el candidato 0 lleva U+FFFD y debe llegar
                // intacto (bytes EF BF BD) como a p7zip en Linux.
                environment()["LANG"] = "C.UTF-8"
                environment()["LC_ALL"] = "C.UTF-8"
            }.start()
            // Cerrar stdin: 7zzs no debe pedir nada interactivo (la
            // contraseña va por argv); si pidiera, EOF y falla rápido.
            try {
                proc.outputStream.close()
            } catch (_: Exception) {
            }
            // Consumir la salida en otro hilo (7z escribe progreso; si el
            // buffer se llena y nadie lee, el proceso se bloquea).
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(lastLines) {
                            lastLines += line
                            if (lastLines.size > 40) lastLines.removeAt(0)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
            val finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                return Result.Failed("7zzs sin respuesta en $timeoutMinutes min (archivo grande o bloqueado)")
            }
            reader.join(5000)
            val exit = proc.exitValue()
            val tail = synchronized(lastLines) { lastLines.joinToString(" | ") }
            val detail = tail.ifBlank { "exit=$exit" }.take(300)
            val hasFiles = destDir.walkTopDown().any { it.isFile }
            when {
                exit == 0 && hasFiles -> Result.Ok
                exit == 0 -> Result.Failed("7zzs terminó sin error pero no dejó archivos (exit=0)")
                "Wrong password" in tail || "Headers Error" in tail ->
                    Result.WrongPassword(detail)
                "Unexpected end of archive" in tail || "Unexpected end of data" in tail ->
                    Result.Truncated(detail)
                "Can not open the file as archive" in tail || "is not supported archive" in tail ->
                    Result.BadArchive(detail)
                else -> Result.Failed(detail)
            }
        } catch (e: Throwable) {
            // Ejecución imposible (permiso denegado, ABI...): el binario
            // no sirve en este dispositivo; no reintentarlo más.
            synchronized(this) {
                cachedBinary = null
                binaryChecked = true // no volver a intentarlo en esta sesión
            }
            StartupLog.mark(context, "7zzs: no se pudo ejecutar (${e.javaClass.simpleName}: ${e.message})")
            Result.Failed("no se pudo ejecutar 7zzs (${e.javaClass.simpleName})")
        }
    }
}
