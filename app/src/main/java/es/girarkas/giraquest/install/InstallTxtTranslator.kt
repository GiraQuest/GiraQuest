package es.girarkas.giraquest.install

import es.girarkas.giraquest.adb.GiraAdbManager
import java.io.File

/**
 * Traducción de install.txt.
 */
class InstallTxtTranslator(
    private val adb: GiraAdbManager,
    private val workDir: File, // carpeta donde está install.txt (rutas relativas parten de aquí)
) {

    data class StepResult(val line: String, val ok: Boolean, val output: String)

    /** Ejecuta todas las líneas. Devuelve el resultado por línea (no se aborta en la primera que falle, como el PC). */
    suspend fun run(installTxt: File): List<StepResult> {
        val results = mutableListOf<StepResult>()
        val lines = installTxt.readLines()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("REM", ignoreCase = true)) continue
            val translated = translate(line)
            if (translated == null) {
                results.add(StepResult(line, false, "Línea ignorada (no traducible): $line"))
                continue
            }
            val res = adb.exec(translated, timeoutMs = 300_000)
            results.add(StepResult(line, !res.isError, res.combined.ifBlank { "OK" }))
        }
        return results
    }

    /**
     * Traduce una línea `adb ...` a comando shell. Null si no se puede traducir.
     * Las rutas relativas se resuelven contra [workDir] y se suben a /sdcard/
     * antes (el shell no ve el almacenamiento privado de la app).
     */
    fun translate(line: String): String? {
        // Quitar el prefijo "adb" y el "-s <dispositivo>" del PC.
        var cmd = line.removePrefix("adb").trim()
        cmd = cmd.replace(Regex("^-s\\s+\\S+\\s+"), "").trim()
        if (cmd.isEmpty()) return null
        return when {
            cmd.startsWith("install ") -> {
                // adb install [-r] juego.apk  -> pm install -r -g <ruta en /sdcard>
                val apk = cmd.removePrefix("install").trim()
                    .split(" ").lastOrNull { it.endsWith(".apk", true) } ?: return null
                val devicePath = devicePathFor(apk)
                "pm install -r -g \"$devicePath\""
            }
            cmd.startsWith("push ") -> {
                // adb push <origen> <destino>
                val parts = cmd.removePrefix("push").trim().split(Regex("\\s+"))
                if (parts.size < 2) return null
                val src = parts[0]
                val dst = parts[1]
                val deviceSrc = devicePathFor(src)
                if (dst.contains("/Android/obb/")) {
                    "mkdir -p \"${dst.removeSuffix("/")}\" && cp -r \"$deviceSrc\"/. \"${dst.removeSuffix("/")}/\""
                } else {
                    "cp -r \"$deviceSrc\" \"$dst\""
                }
            }
            cmd.startsWith("shell ") -> {
                // adb shell <comando> -> el comando tal cual por el shell embebido.
                // El PC ya traduce "adb shell pm ..." a "pm ..." aquí.
                cmd.removePrefix("shell").trim().ifBlank { null }
            }
            cmd.startsWith("uninstall ") -> {
                "pm uninstall ${cmd.removePrefix("uninstall").trim()}"
            }
            else -> null
        }
    }

    /**
     * Ruta en /sdcard/ para un archivo local: los archivos descargados ya
     * viven en /sdcard/Download/GiraQuest, así que si la ruta ya es
     * absoluta en /sdcard se usa tal cual; si es relativa al workDir, se
     * devuelve su ruta en /sdcard (el Installer copia antes los archivos a
     * la carpeta de descargas, que es visible para el shell).
     */
    private fun devicePathFor(path: String): String {
        if (path.startsWith("/sdcard/") || path.startsWith("/storage/")) return path
        val f = File(workDir, path)
        return f.absolutePath
    }
}
