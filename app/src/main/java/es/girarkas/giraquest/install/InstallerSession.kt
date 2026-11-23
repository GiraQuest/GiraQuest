package es.girarkas.giraquest.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Environment
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.ObbFileInfo
import es.girarkas.giraquest.download.ArchiveExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.resume

/**
 * Sesión del instalador con OBB (v0.1.34, EXPERIMENTAL).
 *
 * Por qué existe: la copia directa a /Android/obb está muerta en este
 * visor (mkdirs=false, renameTo=false, EACCES/ENOENT en las 3 vías) y
 * la concesión SAF de la raíz también está muerta (Edu 2026-11-20
 * 14:00, firme: el selector no deja elegir la raíz ni Android/obb).
 * La vía que queda por probar es que el SISTEMA coloque el OBB: una
 * sesión del PackageInstaller entrega APK + OBB juntos y es installd
 * (privilegiado) quien escribe en /Android/obb/<paquete>/ — GiraQuest
 * no escribe nunca ahí.
 *
 * API oculta: el android-34 público NO tiene Session.addFile ni
 * LOCATION_MEDIA_OBB (son @SystemApi; verificado con javap sobre
 * platforms/android-34/android.jar: solo openWrite es público). Se
 * llega a ellos por reflexión; en Horizon pueden estar bloqueados
 * (hidden API blacklist) — solo la prueba en el visor lo dice. Por
 * eso todo esto es una sonda + un camino alternativo con registro
 * abundante, NUNCA un sustituto de los caminos viejos: si la sonda
 * dice que no es invocable, o la sesión falla en cualquier punto,
 * se sigue con el flujo de siempre (instalador del sistema +
 * copia directa/renameTo/SAF).
 *
 * Regla OBB (Edu, firme): se coloca UNA SOLA carpeta — la que
 * devuelve findObbFolder (carpeta llamada exactamente <pkg> a
 * cualquier profundidad, o ancestro común más profundo) con TODO
 * su contenido (cualquier extensión salvo .apk). Esta ruta opera
 * sobre esa única carpeta y no la divide: cada archivo de datos
 * se entrega con addFile(LOCATION_MEDIA_OBB, <ruta relativa>,
 * <tamaño>, null, null). Los .pak de Maestro y los .hdog de Hot
 * Dogs se intentan igual que los .obb: si installd los rechaza
 * (p. ej. por no llamarse main./patch.), el error queda en el log
 * y se abandona la sesión — NUNCA se omiten en silencio.
 *
 * Confirmación: el commit muestra la UI del instalador del sistema,
 * como el fallback de siempre; Edu la acepta. El resultado llega
 * por InstallResultActivity (mismo mecanismo que el fallback).
 *
 * Etiquetas del log (buscar en Ajustes → "Compartir log"):
 *  "obb sesión: addFile disponible=SÍ/NO, ..." (sonda, al arrancar)
 *  "obb sesión: sesión creada id=N ..."
 *  "obb sesión: addFile OK <nombre> (<bytes> bytes)" (por archivo)
 *  "obb sesión: commit enviado (id=N) ..."
 *  "obb sesión: resultado instalador status=N ..."
 *  "obb sesión: OBB colocado por el sistema: ..." (verificación)
 */
object InstallerSession {

    /** Resultado de la sonda de reflexión (se cachea: no cambia en caliente). */
    data class ProbeResult(
        val addFileFound: Boolean,
        val addFileReturnType: String?,
        val locationMediaObb: Int?,
        val accessible: Boolean,
        val error: String?,
    ) {
        /** "Invocable" según la sonda: método + constante presentes y
         *  accesibles. NO es una garantía (Horizon puede bloquear la
         *  invocación real); la prueba es el intento de instalar. */
        val callable: Boolean get() = addFileFound && locationMediaObb != null && accessible
    }

    @Volatile
    var lastProbe: ProbeResult? = null
        private set

    @Volatile
    private var addFileMethod: Method? = null

    /** Resultado de un intento de instalar por sesión. */
    sealed class SessionResult {
        /** APK + OBB instalados por el sistema. files/bytes: lo entregado;
         *  unverified: el instalador dijo éxito pero el tamaño en el
         *  destino no se pudo comprobar (lectura denegada en Horizon);
         *  verifyDetail: el detalle de la comprobación. */
        data class Success(
            val files: List<ObbFileInfo>,
            val bytes: Long,
            val unverified: Boolean,
            val verifyDetail: String,
        ) : SessionResult()

        /** El usuario canceló en la UI de confirmación. */
        object Cancelled : SessionResult()

        /** El sistema bloqueó la sesión (política o permiso). */
        data class Blocked(val detail: String) : SessionResult()

        /** Fallo técnico (antes o durante el commit). */
        data class Failed(val detail: String) : SessionResult()

        /** No se puede ni intentar (sonda negativa o sin carpeta de datos). */
        data class NotAvailable(val detail: String) : SessionResult()
    }

    /** Sonda por reflexión. Nunca lanza: si algo falla, queda en el
     *  ProbeResult y en el log, y se sigue con los caminos viejos. */
    fun probe(context: Context): ProbeResult {
        lastProbe?.let { return it }
        var method: Method? = null
        var methodError: String? = null
        try {
            method = PackageInstaller.Session::class.java.getMethod(
                "addFile",
                Int::class.javaPrimitiveType,
                String::class.java,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java,
            )
        } catch (e: Exception) {
            methodError = "addFile: ${e.javaClass.simpleName}: ${e.message}"
        }
        var location: Int? = null
        var fieldError: String? = null
        try {
            val field = PackageInstaller.Session::class.java.getField("LOCATION_MEDIA_OBB")
            location = field.getInt(null)
        } catch (e: Exception) {
            fieldError = "LOCATION_MEDIA_OBB: ${e.javaClass.simpleName}: ${e.message}"
        }
        // trySetAccessible() no existe con el target JVM de este build
        // (kotlinc 1.9.22, JVM 1.8): se usa setAccessible(true), que
        // existe desde Java 1.2. Si el método está bloqueado, lanza y
        // queda como no invocable; la prueba real es el intento de
        // instalar en el visor.
        val accessible = try {
            method?.let {
                it.isAccessible = true
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
        val error = listOfNotNull(methodError, fieldError)
            .takeIf { it.isNotEmpty() }?.joinToString("; ")
            ?: if (!accessible && method != null) "addFile no accesible (setAccessible=false)" else null
        val result = ProbeResult(
            addFileFound = method != null,
            addFileReturnType = method?.returnType?.name,
            locationMediaObb = location,
            accessible = accessible,
            error = error,
        )
        addFileMethod = method
        lastProbe = result
        StartupLog.mark(
            context,
            "obb sesión: addFile disponible=${if (result.addFileFound) "SÍ" else "NO"}" +
                ", retorno=${result.addFileReturnType ?: "—"}" +
                ", LOCATION_MEDIA_OBB=${result.locationMediaObb?.toString() ?: "—"}" +
                ", invocable=${if (result.callable) "SÍ" else "NO"}" +
                (result.error?.let { " (error: $it)" } ?: "")
        )
        return result
    }

    /** Sonda al arrancar la app (GiraQuestApp.onCreate). Nunca tumba
     *  el arranque: cualquier excepción queda en el log. */
    fun probeAtStartup(context: Context) {
        try {
            probe(context)
        } catch (t: Throwable) {
            try {
                StartupLog.mark(
                    context,
                    "obb sesión: sonda FALLÓ con excepción: ${t.javaClass.simpleName}: ${t.message}"
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Instala APK + OBB en una sola sesión del instalador.
     *
     * Solo se intenta si la sonda dice que addFile es invocable y el
     * staging trae una carpeta con archivos de datos (findObbFolder).
     * En cualquier fallo técnico ANTES del commit se abandona la sesión
     * y se devuelve Failed (el llamador sigue con el flujo de siempre).
     * Si el usuario cancela la confirmación, se devuelve Cancelled y el
     * llamador NO re-pregunta. Tras el commit, el resultado lo trae
     * InstallResultActivity (misma mecánica que el fallback).
     */
    suspend fun installWithObb(
        context: Context,
        apkFile: File,
        packageName: String,
        stagingRoot: File,
    ): SessionResult {
        val probe = lastProbe ?: probe(context)
        if (!probe.callable) {
            val detail = "sesión OBB no disponible: ${probe.error ?: "addFile/LOCATION_MEDIA_OBB no accesibles"}"
            StartupLog.mark(context, "obb sesión: $detail")
            return SessionResult.NotAvailable(detail)
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            val detail = "sin permiso «Instalar apps desconocidas» (el commit de la sesión está condenado)"
            StartupLog.mark(context, "obb sesión: $detail")
            return SessionResult.Failed(detail)
        }
        val folder = ArchiveExtractor.findObbFolder(stagingRoot, packageName)
            ?: return SessionResult.NotAvailable(
                "sin carpeta con archivos de datos en el staging (ni carpeta $packageName ni ancestro común)"
            )
        val files = ArchiveExtractor.listDataFiles(folder.dir, insidePkgFolder = !folder.viaCommonAncestor)
        if (files.isEmpty()) {
            return SessionResult.NotAvailable(
                "la carpeta ${folder.dir.name} no trae archivos de datos (solo el instalador .apk)"
            )
        }
        val method = addFileMethod ?: probe(context).let { addFileMethod }
            ?: return SessionResult.NotAvailable("addFile no disponible (método perdido tras la sonda)")
        val location = probe.locationMediaObb
            ?: return SessionResult.NotAvailable("LOCATION_MEDIA_OBB no disponible")
        val obbCount = files.count { it.name.endsWith(".obb", ignoreCase = true) }
        StartupLog.mark(
            context,
            "obb sesión: carpeta a colocar: ${folder.dir.absolutePath} " +
                "(relativa: ${folder.relativePath}, profundidad ${folder.depth}, " +
                "${files.size} archivos ($obbCount .obb), ${files.sumOf { it.size }} bytes) — se intentará por sesión del instalador"
        )
        return withContext(Dispatchers.IO) {
            val (status, message) = suspendCancellableCoroutine<Pair<Int, String?>> { cont ->
                var sessionId = -1
                var committed = false
                try {
                    val installer = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    sessionId = installer.createSession(params)
                    StartupLog.mark(
                        context,
                        "obb sesión: sesión creada id=$sessionId (APK + ${files.size} archivos de datos)"
                    )
                    val intent = Intent(
                        context,
                        PackageInstallerFallback.InstallResultActivity::class.java
                    ).apply {
                        action = "es.girarkas.giraquest.INSTALL_RESULT"
                    }
                    val pi = PendingIntent.getActivity(
                        context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    // Registrar el callback ANTES del commit (como el
                    // fallback): el PENDING_USER_ACTION llega casi al
                    // instante y si la Activity se crea antes, el
                    // resultado se pierde y esta corrutina cuelga.
                    PackageInstallerFallback.InstallResultActivity.awaitResult(sessionId) { s, m ->
                        StartupLog.mark(
                            context,
                            "obb sesión: resultado instalador status=$s msg=${m ?: ""}"
                        )
                        if (cont.isActive) cont.resume(s to m)
                    }
                    installer.openSession(sessionId).use { session ->
                        session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                            FileInputStream(apkFile).use { input -> input.copyTo(out) }
                            session.fsync(out)
                        }
                        StartupLog.mark(
                            context,
                            "obb sesión: APK escrita (base.apk, ${apkFile.length()} bytes)"
                        )
                        for (info in files) {
                            val dataFile = File(folder.dir, info.relativePath)
                            if (!dataFile.isFile) {
                                throw IllegalStateException(
                                    "el archivo de datos ${info.relativePath} desapareció del staging"
                                )
                            }
                            val outObj: Any? = try {
                                method.invoke(
                                    session, location, info.relativePath, info.size, null, null
                                )
                            } catch (e: Exception) {
                                val cause = (e as? InvocationTargetException)?.targetException ?: e
                                StartupLog.mark(
                                    context,
                                    "obb sesión: addFile FALLÓ para ${info.relativePath}: " +
                                        "${cause.javaClass.simpleName}: ${cause.message}"
                                )
                                throw cause
                            }
                            if (outObj !is OutputStream) {
                                StartupLog.mark(
                                    context,
                                    "obb sesión: addFile devolvió " +
                                        "${outObj?.javaClass?.name ?: "void/null"} para " +
                                        "${info.relativePath} (sin flujo para escribir los bytes: " +
                                        "no se puede entregar el archivo)"
                                )
                                throw IllegalStateException(
                                    "addFile sin flujo de salida para ${info.relativePath}"
                                )
                            }
                            try {
                                FileInputStream(dataFile).use { input -> input.copyTo(outObj) }
                                try {
                                    session.fsync(outObj)
                                } catch (e: Exception) {
                                    StartupLog.mark(
                                        context,
                                        "obb sesión: fsync tras ${info.relativePath} falló (no fatal): ${e.message}"
                                    )
                                }
                            } finally {
                                try {
                                    outObj.close()
                                } catch (_: Exception) {
                                }
                            }
                            StartupLog.mark(
                                context,
                                "obb sesión: addFile OK ${info.relativePath} (${info.size} bytes)"
                            )
                        }
                        session.commit(pi.intentSender)
                        committed = true
                        StartupLog.mark(
                            context,
                            "obb sesión: commit enviado (id=$sessionId) — esperando confirmación/resultado del instalador"
                        )
                    }
                } catch (e: Exception) {
                    if (!committed) {
                        if (sessionId >= 0) {
                            try {
                                context.packageManager.packageInstaller.abandonSession(sessionId)
                            } catch (_: Exception) {
                            }
                            StartupLog.mark(context, "obb sesión: sesión id=$sessionId abandonada")
                        }
                        val cause = (e as? InvocationTargetException)?.targetException ?: e
                        StartupLog.mark(
                            context,
                            "obb sesión: FALLÓ antes del commit: ${cause.javaClass.simpleName}: ${cause.message}"
                        )
                        if (cont.isActive) {
                            cont.resume(
                                PackageInstaller.STATUS_FAILURE to
                                    (cause.message?.takeIf { it.isNotBlank() }
                                        ?: cause.javaClass.simpleName)
                            )
                        }
                    } else {
                        // Excepción tras el commit (p. ej. al cerrar la
                        // sesión): el instalador ya tiene la sesión y
                        // traerá su resultado por el callback; no se
                        // resume aquí para no duplicar el resultado.
                        StartupLog.mark(
                            context,
                            "obb sesión: excepción tras el commit (se espera el resultado del instalador): " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }
            mapStatus(context, packageName, files, status, message)
        }
    }

    /** Traduce el estado del instalador al resultado de la sesión. En
     *  SUCCESS se comprueba en disco qué quedó en /Android/obb/<pkg>/:
     *  verificado (todos con su tamaño), o "sin verificar" si el destino
     *  no se puede leer (Horizon) — el instalador dijo éxito y se le
     *  cree; el lanzamiento del juego es la prueba final. */
    private fun mapStatus(
        context: Context,
        packageName: String,
        files: List<ObbFileInfo>,
        status: Int,
        message: String?,
    ): SessionResult {
        val msg = message?.takeIf { it.isNotBlank() }
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                val (unverified, detail) = verifyPlaced(packageName, files)
                StartupLog.mark(
                    context,
                    "obb sesión: OBB colocado por el sistema: ${files.size} archivos, " +
                        "${files.sumOf { it.size }} bytes → /Android/obb/$packageName/ ($detail)"
                )
                SessionResult.Success(files, files.sumOf { it.size }, unverified, detail)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> SessionResult.Cancelled
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                SessionResult.Blocked(msg ?: "bloqueado por el sistema")
            else -> {
                if (msg != null && "cancel" in msg.lowercase()) {
                    SessionResult.Cancelled
                } else {
                    SessionResult.Failed(msg ?: "fallo sin detalle (código $status)")
                }
            }
        }
    }

    /** Comprobación en disco tras STATUS_SUCCESS: cada archivo esperado
     *  por ruta relativa + tamaño (sonda de lectura). Si alguno se puede
     *  leer pero trae otro tamaño, queda en el detalle (el instalador
     *  dijo éxito; no se contradice, se registra). Si no se puede leer
     *  nada del destino (Horizon), "sin verificar". */
    private fun verifyPlaced(
        packageName: String,
        files: List<ObbFileInfo>,
    ): Pair<Boolean, String> {
        val dest = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        var unreadable = 0
        val mismatched = mutableListOf<String>()
        for (info in files) {
            val f = File(dest, info.relativePath)
            val size = try {
                RandomAccessFile(f, "r").use { it.length() }
            } catch (_: Exception) {
                null
            }
            when {
                size == info.size -> { /* verificado */ }
                size == null -> unreadable++
                else -> mismatched += "${info.relativePath} (esperado ${info.size}, leído $size)"
            }
        }
        return when {
            mismatched.isEmpty() && unreadable == 0 ->
                false to "verificación OK"
            mismatched.isEmpty() ->
                true to "sin verificar: $unreadable/${files.size} tamaños ilegibles en el destino"
            else ->
                true to "sin verificar: tamaño distinto en ${mismatched.joinToString("; ")}" +
                    if (unreadable > 0) " ($unreadable tamaños ilegibles)" else ""
        }
    }
}
