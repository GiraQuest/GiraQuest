package es.girarkas.giraquest.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ejecutor de install.txt SIN ADB (v0.1.25).
 *
 * En el PC, install.txt es una lista de comandos `adb ...` que se ejecutan
 * tal cual por ADB (Sideloader.RunADBCommandsFromFile: solo líneas que
 * empiezan por "adb", extrae los .7z antes, no aborta en la primera que
 * falle). En Quest, cuando no hay ADB, el flujo con ADB no corría y el
 * install.txt quedaba sin ejecutar (limitación conocida desde la v0.1.23:
 * "sin ADB no hay pm install, ni install.txt, ni shell").
 *
 * Este ejecutor traduce cada línea a operaciones directas en Java cuando
 * es posible, con el mismo permiso MANAGE_EXTERNAL_STORAGE que ya usa la
 * copia directa del OBB (probada en el móvil de Edu):
 *
 * | Línea install.txt                | Sin ADB                              |
 * | adb install [-r] juego.apk       | instalador del sistema (confirmación)|
 * | adb push <src> <dst>             | copia File (archivo o carpeta)       |
 * | adb shell mkdir [-p] <dir>       | File.mkdirs()                        |
 * | adb shell cp [-r] <src> <dst>    | copia File                           |
 * | adb shell mv <src> <dst>         | renameTo (o copia+borrado)           |
 * | adb shell rm [-rf] <path>        | borrado File (recursivo si -r)        |
 * | adb shell pm/am/settings/chmod…  | OMITIDO (necesita shell)             |
 * | adb uninstall / resto            | OMITIDO (necesita shell)             |
 *
 * Lo omitido NUNCA tumba la instalación: queda en el log con su línea y
 * se reporta en la UI ("install.txt: N pasos ejecutados, M omitidos por
 * necesitar ADB: ..."). El APK+OBB siempre se completan: la APK principal
 * ya la instaló el fallback del sistema antes de correr esto.
 *
 * Sin imports de Android a propósito: [sdcardRoot] (en el móvil es
 * Environment.getExternalStorageDirectory()), [apkInstaller] (en el móvil
 * es PackageInstallerFallback.installWithConfirmation) y [log] se inyectan,
 * así la clase se prueba en JVM con directorios temporales y un instalador
 * falso (ver /tmp/noadbtest/TestNoAdb.kt).
 */
class InstallTxtNoAdb(
    private val workDir: File,
    private val mainApk: File? = null,
    private val sdcardRoot: File,
    private val apkInstaller: suspend (File) -> Boolean,
    private val log: (String) -> Unit,
) {

    data class StepOutcome(val line: String, val ran: Boolean, val detail: String)
    data class Report(val done: List<StepOutcome>, val skipped: List<StepOutcome>)

    /** Ejecuta todas las líneas. No se aborta en la primera que falle u se omita, como el PC. */
    suspend fun run(installTxt: File, onStep: (StepOutcome) -> Unit = {}): Report =
        withContext(Dispatchers.IO) {
            val done = mutableListOf<StepOutcome>()
            val skipped = mutableListOf<StepOutcome>()
            for (raw in installTxt.readLines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("REM", ignoreCase = true)) continue
                val outcome = runLine(line)
                log("install.txt sin ADB: ${if (outcome.ran) "OK" else "OMITIDO"}: $line — ${outcome.detail}")
                if (outcome.ran) done.add(outcome) else skipped.add(outcome)
                onStep(outcome)
            }
            Report(done, skipped)
        }

    private suspend fun runLine(line: String): StepOutcome {
        // Como el PC (RunADBCommandsFromFile): solo líneas que empiezan por "adb".
        if (!line.startsWith("adb")) return StepOutcome(line, false, "línea sin prefijo adb")
        // Quitar el prefijo "adb" y el "-s <dispositivo>" del PC (igual que el traductor con ADB).
        var cmd = line.removePrefix("adb").trim()
        cmd = cmd.replace(Regex("^-s\\s+\\S+\\s+"), "").trim()
        if (cmd.isEmpty()) return StepOutcome(line, false, "línea vacía tras quitar el prefijo adb")
        return when {
            cmd == "install" || cmd.startsWith("install ") -> runInstall(line, cmd)
            cmd.startsWith("push ") -> runPush(line, cmd)
            cmd.startsWith("shell ") -> runShell(line, cmd.removePrefix("shell").trim())
            cmd.startsWith("uninstall ") -> StepOutcome(line, false, "necesita ADB (pm uninstall)")
            else -> StepOutcome(line, false, "comando no traducible sin ADB")
        }
    }

    private suspend fun runInstall(line: String, cmd: String): StepOutcome {
        // adb install [-r] juego.apk -> instalador del sistema.
        val apkToken = splitArgs(cmd.removePrefix("install").trim())
            .lastOrNull { it.endsWith(".apk", true) }
            ?: return StepOutcome(line, false, "install sin APK")
        val apkFile = resolveSource(apkToken)
            ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: $apkToken")
        if (!apkFile.exists()) return StepOutcome(line, false, "APK no encontrada: $apkToken")
        if (mainApk != null && sameFile(apkFile, mainApk)) {
            return StepOutcome(line, true, "APK ya instalada por el instalador del sistema")
        }
        return try {
            if (apkInstaller(apkFile)) {
                StepOutcome(line, true, "APK instalada con confirmación: ${apkFile.name}")
            } else {
                StepOutcome(line, false, "la instalación de ${apkFile.name} no se completó (cancelada o fallida)")
            }
        } catch (e: Exception) {
            StepOutcome(line, false, "instalador del sistema falló: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun runPush(line: String, cmd: String): StepOutcome {
        // adb push <origen> <destino> -> copia File.
        val parts = splitArgs(cmd.removePrefix("push").trim())
        if (parts.size < 2) return StepOutcome(line, false, "push sin origen o destino")
        return copyStep(line, parts[0], parts[1], copyContents = false)
    }

    private fun runShell(line: String, inner: String): StepOutcome {
        if (inner.isBlank()) return StepOutcome(line, false, "shell vacío")
        val args = splitArgs(inner)
        val verb = args.firstOrNull() ?: return StepOutcome(line, false, "shell vacío")
        return when (verb) {
            "mkdir" -> {
                // adb shell mkdir [-p] <dir>
                val path = args.drop(1).lastOrNull { !it.startsWith("-") }
                    ?: return StepOutcome(line, false, "mkdir sin ruta")
                val dir = deviceFile(path)
                    ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: $path")
                try {
                    if (dir.exists() || dir.mkdirs()) {
                        StepOutcome(line, true, "directorio creado (o ya existía): $path")
                    } else {
                        StepOutcome(line, false, "no se pudo crear el directorio: $path")
                    }
                } catch (e: Exception) {
                    StepOutcome(line, false, "mkdir falló: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            "cp" -> {
                // adb shell cp [-r] <origen> <destino>
                val paths = args.drop(1).filter { !it.startsWith("-") }
                if (paths.size < 2) return StepOutcome(line, false, "cp sin origen o destino")
                copyStep(line, paths[paths.size - 2], paths[paths.size - 1], copyContents = false)
            }
            "mv" -> {
                // adb shell mv <origen> <destino>
                val paths = args.drop(1).filter { !it.startsWith("-") }
                if (paths.size < 2) return StepOutcome(line, false, "mv sin origen o destino")
                val src = resolveSource(paths[paths.size - 2])
                    ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: ${paths[paths.size - 2]}")
                val dst = deviceFile(paths[paths.size - 1])
                    ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: ${paths[paths.size - 1]}")
                try {
                    if (!src.exists()) return StepOutcome(line, false, "origen no encontrado: ${paths[paths.size - 2]}")
                    val destFile = if (dst.isDirectory || paths[paths.size - 1].endsWith("/")) File(dst, src.name) else dst
                    destFile.parentFile?.mkdirs()
                    if (src.renameTo(destFile)) {
                        StepOutcome(line, true, "movido ${src.name} → ${destFile.absolutePath}")
                    } else {
                        // renameTo falla entre volúmenes: copia + borrado.
                        when (val copied = copyFileOrDir(src, destFile, copyContents = false, dstIsDir = false)) {
                            is CopyOutcome.Ok -> {
                                src.deleteRecursively()
                                StepOutcome(line, true, "movido ${src.name} → ${destFile.absolutePath} (copia+borrado)")
                            }
                            is CopyOutcome.Failed -> StepOutcome(line, false, copied.reason)
                        }
                    }
                } catch (e: Exception) {
                    StepOutcome(line, false, "mv falló: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            "rm" -> {
                // adb shell rm [-r|-f|-rf] <ruta>
                val flags = args.drop(1).filter { it.startsWith("-") }.joinToString("")
                val path = args.drop(1).lastOrNull { !it.startsWith("-") }
                    ?: return StepOutcome(line, false, "rm sin ruta")
                val target = deviceFile(path)
                    ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: $path")
                try {
                    if (!target.exists()) return StepOutcome(line, true, "no existía, nada que borrar: $path")
                    if (target.isDirectory && !flags.contains("r")) {
                        return StepOutcome(line, false, "rm de directorio sin -r: $path")
                    }
                    val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
                    if (ok) StepOutcome(line, true, "borrado: $path")
                    else StepOutcome(line, false, "no se pudo borrar: $path")
                } catch (e: Exception) {
                    StepOutcome(line, false, "rm falló: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            // Todo lo que de verdad necesita shell se omite sin tumbar la instalación.
            "pm", "am", "settings", "chmod", "chown", "setprop", "getprop",
            "input", "dumpsys", "svc", "cmd", "appops" ->
                StepOutcome(line, false, "necesita ADB (shell: $verb)")
            else -> StepOutcome(line, false, "comando shell no traducible sin ADB: $verb")
        }
    }

    private fun copyStep(line: String, srcArg: String, dstArg: String, copyContents: Boolean): StepOutcome {
        val copyContentsArg = copyContents || srcArg.endsWith("/.")
        val src = resolveSource(srcArg.removeSuffix("/."))
            ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: $srcArg")
        val dst = deviceFile(dstArg)
            ?: return StepOutcome(line, false, "ruta fuera de /sdcard, necesita ADB: $dstArg")
        return try {
            if (!src.exists()) return StepOutcome(line, false, "origen no encontrado: $srcArg")
            // File normaliza la barra final ("/sdcard/x/" pierde la /): la
            // intención de "copiar DENTRO" se decide por el argumento crudo.
            val dstIsDir = dst.isDirectory || dstArg.endsWith("/")
            when (val copied = copyFileOrDir(src, dst, copyContentsArg, dstIsDir)) {
                is CopyOutcome.Ok -> StepOutcome(
                    line, true,
                    "copiado ${src.name} → ${dst.absolutePath} (${copied.files} archivos, ${copied.bytes} bytes)"
                )
                is CopyOutcome.Failed -> StepOutcome(line, false, copied.reason)
            }
        } catch (e: Exception) {
            StepOutcome(line, false, "copia falló: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private sealed class CopyOutcome {
        data class Ok(val files: Int, val bytes: Long) : CopyOutcome()
        data class Failed(val reason: String) : CopyOutcome()
    }

    /**
     * Copia un archivo o una carpeta (recursiva). Semántica cp -r:
     * si el destino existe como directorio o el argumento terminaba en /
     * ([dstIsDir]), la fuente se copia DENTRO de él con su nombre; si no,
     * el destino ES la copia. Con [copyContents] se copia el CONTENIDO de
     * la carpeta fuente dentro del destino (el `cp -r "src"/. "dst"/` del
     * traductor con ADB).
     */
    private fun copyFileOrDir(src: File, dst: File, copyContents: Boolean, dstIsDir: Boolean): CopyOutcome {
        if (src.isFile) {
            val destFile = if (dstIsDir) File(dst, src.name) else dst
            destFile.parentFile?.mkdirs()
            return try {
                copyBytes(src, destFile)
                if (destFile.length() != src.length()) {
                    CopyOutcome.Failed("${src.name} quedó con ${destFile.length()} bytes, esperados ${src.length()}")
                } else {
                    CopyOutcome.Ok(1, src.length())
                }
            } catch (e: Exception) {
                CopyOutcome.Failed("copia de ${src.name} falló: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // Directorio.
        var files = 0
        var bytes = 0L
        try {
            if (copyContents) {
                if (!dst.exists() && !dst.mkdirs()) {
                    return CopyOutcome.Failed("no se pudo crear el directorio destino: ${dst.absolutePath}")
                }
                for (child in src.listFiles() ?: emptyArray()) {
                    when (val r = copyFileOrDir(child, File(dst, child.name), copyContents = false, dstIsDir = false)) {
                        is CopyOutcome.Ok -> { files += r.files; bytes += r.bytes }
                        is CopyOutcome.Failed -> return r
                    }
                }
            } else {
                val destDir = if (dstIsDir) File(dst, src.name) else dst
                if (!destDir.exists() && !destDir.mkdirs()) {
                    return CopyOutcome.Failed("no se pudo crear el directorio destino: ${destDir.absolutePath}")
                }
                for (child in src.listFiles() ?: emptyArray()) {
                    when (val r = copyFileOrDir(child, File(destDir, child.name), copyContents = false, dstIsDir = false)) {
                        is CopyOutcome.Ok -> { files += r.files; bytes += r.bytes }
                        is CopyOutcome.Failed -> return r
                    }
                }
            }
        } catch (e: Exception) {
            return CopyOutcome.Failed("copia de ${src.name} falló: ${e.javaClass.simpleName}: ${e.message}")
        }
        return CopyOutcome.Ok(files, bytes)
    }

    private fun copyBytes(src: File, dest: File) {
        src.inputStream().use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(1024 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                }
            }
        }
    }

    /** Origen de una copia/install: relativo al workDir, o ruta de dispositivo /sdcard. */
    private fun resolveSource(path: String): File? {
        val p = path.replace('\\', '/')
        return when {
            p.startsWith("/sdcard/") || p == "/sdcard" -> deviceFile(p)
            p.startsWith("/storage/emulated/0/") -> deviceFile(p)
            p.startsWith("/") -> null // ruta absoluta fuera de /sdcard: necesita ADB
            else -> File(workDir, p)
        }
    }

    /**
     * Ruta de dispositivo a File bajo [sdcardRoot]. Null si es una ruta
     * absoluta fuera de /sdcard (p. ej. /data/…): sin ADB no se puede tocar.
     */
    private fun deviceFile(path: String): File? {
        val p = path.replace('\\', '/')
        return when {
            p == "/sdcard" -> sdcardRoot
            p.startsWith("/sdcard/") -> File(sdcardRoot, p.removePrefix("/sdcard/"))
            p.startsWith("/storage/emulated/0/") -> File(sdcardRoot, p.removePrefix("/storage/emulated/0/"))
            p.startsWith("/") -> null
            else -> File(sdcardRoot, p)
        }
    }

    private fun sameFile(a: File, b: File): Boolean {
        val ca = runCatching { a.canonicalFile }.getOrNull() ?: a.absoluteFile
        val cb = runCatching { b.canonicalFile }.getOrNull() ?: b.absoluteFile
        return ca == cb
    }

    /** Parte una línea respetando rutas entre comillas dobles ("mi carpeta/archivo"). */
    private fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (c in s) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c.isWhitespace() && !inQuotes -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.clear()
                    }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
