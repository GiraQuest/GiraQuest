package es.girarkas.giraquest.install

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import es.girarkas.giraquest.GiraQuestApp
import es.girarkas.giraquest.R
import es.girarkas.giraquest.adb.AdbHealth
import es.girarkas.giraquest.adb.AdbPortDiscovery
import es.girarkas.giraquest.adb.GiraAdbManager
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.InstallRecord
import es.girarkas.giraquest.data.InstallRecordState
import es.girarkas.giraquest.data.ObbFileInfo
import es.girarkas.giraquest.data.ObbRecordState
import es.girarkas.giraquest.data.ObbUiState
import es.girarkas.giraquest.data.PendingObb
import es.girarkas.giraquest.data.RepoEntry
import es.girarkas.giraquest.data.RepoManager
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.data.VrpPublicRepo
import es.girarkas.giraquest.download.ArchiveExtractor
import es.girarkas.giraquest.download.DownloadResult
import es.girarkas.giraquest.download.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections

/** Fases visibles durante una instalación (como la cola del PC). */
sealed class InstallProgress {
    data class DownloadingApk(val percent: Int, val speedBytesPerSec: Long = 0L) : InstallProgress()
    data class DownloadingObb(val percent: Int, val speedBytesPerSec: Long = 0L) : InstallProgress()
    /** Descarga del juego completo del repo público (trozos .7z.001…). */
    data class DownloadingGame(val percent: Int, val speedBytesPerSec: Long = 0L) : InstallProgress()
    data class ExtractingObb(val note: String, val percent: Int = -1) : InstallProgress()
    data class InstallingApk(val note: String) : InstallProgress()
    data class CopyingObb(val note: String) : InstallProgress()
    data class RunningInstallTxt(val line: String) : InstallProgress()
    /**
     * APK instalada pero OBB pendiente (v0.1.27): estado EN CURSO, no
     * final. Antes esto era un Done(true) con "se copiará cuando la
     * depuración esté activa" (falso) y la única confirmación un Toast
     * que se perdía — así el usuario creyó que los 3 .obb de BulletStorm no se
     * habían copiado cuando sí se copiaron 30 s después. Ahora la UI
     * enseña "APK instalada — OBB pendiente, copiando…" y cuando el
     * temporizador lo completa, el mismo estado cambia a completado en
     * su sitio (sin Toast como única señal).
     */
    data class ObbPending(val packageName: String, val gameName: String, val message: String, val reason: String) : InstallProgress()
    data class Done(val ok: Boolean, val message: String, val silent: Boolean = false) : InstallProgress()
}

/**
 * Installer — el flujo central (doble clic en el PC):
 *   descargar APK (+OBB) -> instalar APK -> copiar OBB -> listo.
 *
 * Vía principal: ADB embebido (pm install + cp a /sdcard/Android/obb).
 * Si el ADB no está conectado, fallback a PackageInstaller con confirmación
 * del sistema (aceptable para el usuario) PERO el OBB no es fiable en ese modo y
 * se avisa claramente.
 *
 * La confirmación en pantalla la pone la UI antes de llamar a install()
 * (diálogo "Instalar X v1.0 — APK 500 MB + OBB 1,2 GB?").
 */
class Installer(private val context: Context) {

    private val settings = SettingsStore(context)
    private val repoManager = RepoManager(context)
    private val downloader = Downloader(settings)
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Gestor ADB bajo demanda (v0.1.2). Antes se creaba aquí, en el
     * constructor de Installer, que corre en onViewCreated del fragmento
     * que MainActivity abre al arrancar: si esa creación lanzaba, la app
     * crasheaba al abrir sin remedio. Ahora es null si no se puede crear,
     * y solo se toca durante una instalación/desinstalación.
     */
    private fun adbOrNull(): GiraAdbManager? {
        return try {
            GiraAdbManager.getInstance(context)
        } catch (_: Throwable) {
            null
        }
    }

    private val _progress = MutableStateFlow<InstallProgress>(InstallProgress.Done(true, ""))
    /** Progreso COMPARTIDO por todas las instancias (v0.1.27): cada
     *  RepoFragment crea su Installer, así que un progreso por
     *  instancia se perdía al recrear el fragmento. Ahora todos
     *  observan el mismo flujo y la instalación en curso se ve desde
     *  cualquier pestaña. */
    val progress: StateFlow<InstallProgress> get() = _progressShared

    companion object {
        /**
         * Watchdog de extracción (v0.1.27): SIN tope total. La v0.1.26
         * tenía un tope fijo de 10 min que disparaba a mitad de juegos
         * grandes legítimos (Arizona: 23,3 GB, ~70 min; el timeout
         * disparó a las 15:20:54 mientras la extracción seguía al 19%
         * y el hilo huérfano continuaba). Ahora la extracción solo se
         * declara atascada si no se escriben bytes durante N minutos
         * seguidos; mientras haya progreso, no hay tope.
         */
        private const val EXTRACT_STALL_MS = 5 * 60_000L

        /**
         * Tope TOTAL de adbReady() (v0.1.24): la suma de sus fases ya
         * tiene topes propios (connectSaved con watchdog 25 s, mDNS 15 s,
         * probe 10 s + reintento), pero sin tope total el flujo podía
         * tardar minutos reintentando contra un adbd degradado. Pasado
         * este tiempo se rinde y cae al instalador del sistema con su
         * línea en el log. El tope no es duro a mitad de fase: se
         * comprueba ANTES de cada fase (mDNS, probe).
         */
        private const val ADB_READY_TIMEOUT_MS = 60_000L

        // ---- Estado compartido entre TODAS las instancias (v0.1.27) ----
        // La v0.1.26 guardaba runningInstalls POR INSTANCIA: cada
        // RepoFragment crea su Installer y al recrearse el fragmento
        // (cambio de pestaña, recreación de la actividad) el guardado
        // nacía vacío. Dos toques en Arizona Sunshine (14:56:37 y
        // 14:59:28) lanzaron DOS descargas paralelas del mismo juego y
        // la primera extracción se corrompió (15:09:52, nextHeaderOffset
        // out of bounds). Ahora el guardado es estático (sobrevive a
        // recrear fragmento/actividad) + candado en disco (sobrevive a
        // matar el proceso) + un solo Mutex para toda la app: UNA sola
        // instalación a la vez (Arizona extrayendo mientras BulletStorm
        // descargaba competían por CPU/IO: 23 MB/s frente a 2-5 MB/s).
        private val runningInstalls = Collections.synchronizedSet(mutableSetOf<String>())
        private val installMutex = Mutex()
        /** Candado GLOBAL compartido entre TODAS las instancias de
         *  Installer (MainActivity timer, RepoFragment, GameDetailDialog,
         *  instalación inmediata) para que nunca corran a la vez dos copias
         *  o reparaciones de OBB sobre /Android/obb/. */
        private val obbWorkMutex = Mutex()
        @Volatile
        private var activeInstallPackage: String? = null
        @Volatile
        private var activeInstallName: String? = null
        private val _activeInstalls = MutableStateFlow<Set<String>>(emptySet())
        val activeInstalls: StateFlow<Set<String>> = _activeInstalls
        private val _progressShared = MutableStateFlow<InstallProgress>(InstallProgress.Done(true, ""))
        val sharedProgress: StateFlow<InstallProgress> = _progressShared
        private val verifiedCompleteLogged = Collections.synchronizedSet(HashSet<String>())

        /** ¿Se está instalando este paquete ahora mismo? (para
         *  desactivar su botón Instalar mientras tanto). */
        fun isInstalling(packageName: String): Boolean = packageName in runningInstalls

        /** ¿Hay alguna instalación en curso en la app? (v0.1.37). */
        fun isAnyInstalling(): Boolean = runningInstalls.isNotEmpty()

        /** Nombre de la instalación en curso en toda la app, si la hay. */
        fun activeInstallDisplayName(): String? = activeInstallName

        /** Antigüedad máxima de un candado en disco para considerarlo
         *  vigente (un proceso matado deja el suyo; pasado esto es
         *  rancio y se ignora). */
        private const val INSTALL_LOCK_MAX_AGE_MS = 4 * 60 * 60_000L
    }

    /**
     * Resultado de una extracción con watchdog (v0.1.27). Stalled NO
     * significa "cancelado": el hilo sigue extrayendo en segundo plano
     * (SevenZFile no coopera con la cancelación) y el próximo intento
     * reanudará desde lo extraído; la UI nunca debe decir "cancelado"
     * mientras el hilo sigue trabajando.
     */
    private sealed class ExtractOutcome {
        object Ok : ExtractOutcome()
        data class Failed(val error: Throwable) : ExtractOutcome()
        data class Stalled(val lastPct: Int, val written: Long, val total: Long, val speedMBps: Double) : ExtractOutcome()
    }

    /**
     * Ejecuta la extracción en un hilo y la vigila por PROGRESO
     * (v0.1.27): solo se declara atascada si no se escriben bytes
     * durante EXTRACT_STALL_MS seguidos. Sin tope total: un juego de
     * 23 GB puede tardar una hora si va escribiendo.
     */
    private fun runExtractionWithWatchdog(
        vrp: VrpPublicRepo,
        archives: List<File>,
        extracted: File,
        resume: Boolean,
        onProgress: (Long, Long, String) -> Unit,
    ): ExtractOutcome {
        // @Volatile no vale en variables locales: Atomic* (el hilo de
        // extracción escribe y el watchdog lee desde otro hilo).
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val lastWritten = java.util.concurrent.atomic.AtomicLong(0L)
        val lastTotal = java.util.concurrent.atomic.AtomicLong(0L)
        val extractError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val startedAt = System.currentTimeMillis()
        val t = Thread({
            try {
                vrp.extractGame(archives, extracted, { w, tot, name ->
                    lastWritten.set(w)
                    lastTotal.set(tot)
                    lastProgressAt.set(System.currentTimeMillis())
                    onProgress(w, tot, name)
                }, resume = resume)
            } catch (e: Throwable) {
                extractError.set(e)
            }
        }, "giraquest-extract-watchdog")
        t.isDaemon = true
        t.start()
        while (true) {
            t.join(5_000)
            if (!t.isAlive) break
            val idleMs = System.currentTimeMillis() - lastProgressAt.get()
            if (idleMs > EXTRACT_STALL_MS) {
                val written = lastWritten.get()
                val total = lastTotal.get()
                val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else -1
                val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(1)
                val speed = written / 1_048_576.0 / elapsedSec
                return ExtractOutcome.Stalled(pct, written, total, speed)
            }
        }
        return extractError.get()?.let { ExtractOutcome.Failed(it) } ?: ExtractOutcome.Ok
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds < 60 -> context.getString(R.string.eta_seconds, seconds)
            seconds < 3600 -> context.getString(R.string.eta_minutes, seconds / 60)
            else -> context.getString(R.string.eta_hours_minutes, seconds / 3600, (seconds % 3600) / 60)
        }
    }

    private fun formatMBps(mbps: Double): String = "%.1f MB/s".format(mbps)

    /** ¿Es este error "sin espacio"? (ENOSPC en la cadena de causas). */
    private fun isNoSpaceError(e: Throwable?): Boolean {
        var cur = e
        while (cur != null) {
            val msg = (cur.message ?: "") + " " + cur.javaClass.simpleName
            if ("No space left on device" in msg || "ENOSPC" in msg) return true
            cur = cur.cause
        }
        return false
    }

    private fun isNoSpaceText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return "No space left on device" in text || "ENOSPC" in text
    }

    private fun emit(p: InstallProgress) {
        _progressShared.value = p
        if (p is InstallProgress.Done && p.message.isNotBlank() && !p.silent) {
            if (p.ok) {
                InstallSuccessEvents.emit(
                    gameName = activeInstallName ?: context.getString(R.string.install_success_generic),
                    detail = p.message,
                    packageName = activeInstallPackage ?: "",
                )
            } else {
                InstallFailureEvents.emit(
                    gameName = activeInstallName ?: context.getString(R.string.install_failed_generic),
                    reason = p.message,
                    packageName = activeInstallPackage ?: "",
                )
            }
        }
    }

    /** Carpeta de descargas de una entrada: /sdcard/Download/GiraQuest/<paquete>/ */
    fun workDirFor(entry: RepoEntry): File = repoManager.downloadDirFor(entry)

    fun install(entry: RepoEntry) {
        StartupLog.mark(
            context,
            "install: ${entry.displayName} (${entry.packageName}) " +
                "vrpHash=${entry.vrpHash} dir=${workDirFor(entry).absolutePath}"
        )
        if (!runningInstalls.add(entry.packageName)) {
            StartupLog.mark(
                context,
                "install: ya hay una instalación en curso para ${entry.packageName}, ignorando toque duplicado"
            )
            emit(
                InstallProgress.Done(
                    false,
                    context.getString(R.string.install_already_running, entry.displayName),
                    silent = true
                )
            )
            return
        }
        // Emitir estado inicial síncronamente para limpiar cualquier progreso viejo (ej. 100% previo).
        emit(InstallProgress.ExtractingObb(context.getString(R.string.install_preparing, entry.displayName)))
        val lockFile = File(workDirFor(entry), ".installing")
        try {
            if (lockFile.exists()) {
                val ageMs = System.currentTimeMillis() - lockFile.lastModified()
                if (ageMs < INSTALL_LOCK_MAX_AGE_MS) {
                    StartupLog.mark(
                        context,
                        "install: candado en disco vigente para ${entry.packageName} " +
                            "(hace ${ageMs / 1000}s): otra instalación en curso, ignorando toque"
                    )
                    runningInstalls.remove(entry.packageName)
                    emit(
                        InstallProgress.Done(
                            false,
                            context.getString(R.string.install_already_running_lock, entry.displayName),
                            silent = true
                        )
                    )
                    return
                }
                StartupLog.mark(
                    context,
                    "install: candado en disco rancio para ${entry.packageName} (hace ${ageMs / 1000}s): se ignora, era de un proceso matado"
                )
                lockFile.delete()
            }
            lockFile.parentFile?.mkdirs()
            lockFile.writeText(System.currentTimeMillis().toString())
        } catch (e: Throwable) {
            StartupLog.mark(context, "install: no se pudo escribir el candado (${e.message}), se sigue sin él")
        }
        _activeInstalls.value = _activeInstalls.value + entry.packageName
        activeInstallPackage = entry.packageName
        activeInstallName = entry.displayName
        // Registro persistente: la instalación empieza (la UI enseña el
        // checklist desde aquí, no solo cuando termina).
        updateRecord(entry) { it.copy(state = InstallRecordState.IN_PROGRESS, updatedAtMs = System.currentTimeMillis()) }
        scope.launch {
            try {
                // v0.1.27: UNA sola instalación a la vez en toda la app.
                // Si otro juego se está instalando, se RECHAZA con el
                // motivo (no se encola detrás de una extracción de 70
                // min): la extracción concurrente robaba CPU/IO
                // (BulletStorm 23 MB/s mientras Arizona iba a 2-5 MB/s).
                if (!installMutex.tryLock()) {
                    val other = activeInstallName ?: context.getString(R.string.install_another_game)
                    StartupLog.mark(
                        context,
                        "install: ${entry.displayName} rechazado, ya se está instalando $other"
                    )
                    emit(
                        InstallProgress.Done(
                            false,
                            context.getString(R.string.install_another_running, other, entry.displayName),
                            silent = true
                        )
                    )
                    return@launch
                }
                try {
                    runInstall(entry)
                } finally {
                    installMutex.unlock()
                }
            } catch (e: Throwable) {
                // v0.1.17: capturar también Error (p. ej. StackOverflowError
                // u OutOfMemoryError): antes el `catch (e: Exception)` no
                // los atrapaba, la corrutina moría en silencio y la UI se
                // quedaba congelada en la última fase ("Extrayendo…").
                // CancellationException se re-lanza: es cancelación, no error.
                if (e is CancellationException) throw e
                StartupLog.mark(
                    context,
                    "install FALLÓ: ${AdbHealth.fullChainReason(e) ?: e.toString()}"
                )
                updateRecord(entry) {
                    it.copy(
                        state = InstallRecordState.FAILED,
                        error = (AdbHealth.fullChainReason(e) ?: e.toString()).take(300),
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                emit(InstallProgress.Done(false, "Error: ${(AdbHealth.fullChainReason(e) ?: e.javaClass.simpleName).take(300)}"))
            } finally {
                runningInstalls.remove(entry.packageName)
                _activeInstalls.value = _activeInstalls.value - entry.packageName
                if (activeInstallPackage == entry.packageName) {
                    activeInstallPackage = null
                    activeInstallName = null
                }
                try {
                    lockFile.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---- Registro persistente de instalación (v0.1.27) ----

    private fun updateRecord(entry: RepoEntry, mutate: (InstallRecord) -> InstallRecord) {
        updateRecordByPackage(entry.packageName, entry.displayName, entry.versionCode, mutate)
    }

    private fun updateRecordByPackage(
        packageName: String,
        gameName: String,
        versionCode: Long,
        mutate: (InstallRecord) -> InstallRecord,
    ) {
        val cur = settings.getInstallRecord(packageName)
            ?: InstallRecord(packageName = packageName, gameName = gameName, versionCode = versionCode)
        settings.saveInstallRecord(mutate(cur.copy(updatedAtMs = System.currentTimeMillis())))
    }

    fun installRecord(packageName: String): InstallRecord? =
        settings.getInstallRecord(packageName)

    fun dismissInstallRecord(packageName: String) {
        settings.dismissInstallRecord(packageName)
    }

    /** v0.1.35: al arrancar, borrar sin ruido los pendientes
     *  HEREDADOS de versiones anteriores: un OBB pendiente o un
     *  registro en estado PENDING_OBB cuyo paquete NO está instalado
     *  Y cuya carpeta de staging ya no existe en disco. Un pendiente
     *  VIVO (paquete instalado o staging presente) no se toca: sigue
     *  enseñándose (compacto) y el temporizador puede completarlo.
     *  Devuelve lo borrado, para el log. */
    fun pruneStalePendingRecords(): List<String> {
        val dropped = mutableListOf<String>()
        settings.pendingObbs.toList().forEach { p ->
            val stagingExists = File(p.stagingDir).exists()
            val installed = repoManager.isInstalled(p.packageName)
            if (!stagingExists && !installed) {
                settings.removePendingObb(p.packageName)
                dropped += "pendiente OBB ${p.packageName}"
            }
        }
        settings.installRecords.toList().forEach { r ->
            if (r.state == InstallRecordState.PENDING_OBB) {
                val staging = File(
                    Environment.getExternalStorageDirectory(),
                    "${settings.downloadFolder}/${r.packageName}"
                )
                if (!staging.exists() && !repoManager.isInstalled(r.packageName)) {
                    settings.removeInstallRecord(r.packageName)
                    dropped += "registro PENDING_OBB ${r.packageName}"
                }
            }
        }
        if (dropped.isNotEmpty()) {
            StartupLog.mark(
                context,
                "limpieza arranque: pendientes heredados borrados: ${dropped.joinToString(", ")}"
            )
        }
        return dropped
    }

    // ---- Espacio libre (v0.1.27) ----

    /** Margen mínimo que debe quedar libre tras descargar+extraer. */
    private val spaceMinMarginBytes = 500L * 1_048_576L

    /** Espacio libre real en /storage/emulated/0 (mismo volumen que
     *  Descargas/GiraQuest y que /Android/obb). */
    fun freeSpaceBytes(): Long {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    sealed class SpaceCheck {
        /** Cabe con margen. remaining = lo que quedará libre. */
        data class Fits(val freeBytes: Long, val remainingBytes: Long) : SpaceCheck()

        /** Cabe, pero queda menos del margen tras descargar+extraer. */
        data class Tight(val freeBytes: Long, val remainingBytes: Long) : SpaceCheck()

        /** No cabe: ni la descarga+extracción entran. NO descargar. */
        data class NoFit(val freeBytes: Long, val neededBytes: Long) : SpaceCheck()
    }

    /** ¿Cabe este juego? downloadBytes = lo que se descarga;
     *  extractEstimateBytes = lo que ocupará extraído (para el repo
     *  público se estima con el propio tamaño de descarga: el .7z
     *  unido + lo extraído conviven temporalmente). */
    fun checkSpace(downloadBytes: Long, extractEstimateBytes: Long): SpaceCheck {
        val free = freeSpaceBytes()
        val needed = downloadBytes + extractEstimateBytes
        val remaining = free - needed
        return when {
            free < needed -> SpaceCheck.NoFit(free, needed)
            remaining < spaceMinMarginBytes -> SpaceCheck.Tight(free, remaining)
            else -> SpaceCheck.Fits(free, remaining)
        }
    }

    /** Tamaño real en disco de lo descargado de un juego
     *  (Download/GiraQuest/<paquete>/, todo el árbol). */
    fun downloadedSizeBytes(entry: RepoEntry): Long {
        return try {
            val dir = workDirFor(entry)
            if (!dir.exists()) 0L
            else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    /** Tamaño de descarga de un juego del repo público (v0.1.27):
     *  primero la caché local, si no consulta bajo demanda (listado +
     *  HEADs) y la guarda. Null si no se puede saber (sin red, 404):
     *  la instalación sigue como antes (el total se calcula al
     *  descargar). */
    suspend fun gameDownloadSize(entry: RepoEntry): VrpPublicRepo.GameDownloadSize? {
        if (entry.vrpHash == null) return null
        settings.cachedGameSize(entry.packageName, entry.versionCode)?.let { (b, p) ->
            return VrpPublicRepo.GameDownloadSize(b, p)
        }
        return try {
            val size = RepoManager(context).vrpClientFor(entry).downloadSize(entry)
            settings.cacheGameSize(entry.packageName, entry.versionCode, size.totalBytes, size.parts)
            size
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            StartupLog.mark(
                context,
                "install: no se pudo consultar el tamaño de ${entry.displayName} (${e.message})"
            )
            null
        }
    }

    // ---- Estado del OBB para la UI (v0.1.27) ----

    /** Estado del OBB calculado desde el registro + el disco: nunca se
     *  afirma "copiado" sin comprobar que cada .obb está en
     *  /Android/obb/<paquete>/ con su tamaño. */
    fun obbUiState(packageName: String): ObbUiState {
        val record = settings.getInstallRecord(packageName)
        val pending = settings.pendingObbFor(packageName)
        val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        val expected = record?.obbFiles?.takeIf { it.isNotEmpty() }
            ?: pending?.obbFiles?.takeIf { it.isNotEmpty() }
            ?: emptyList()
        // v0.1.29: el listado del directorio puede fallar (listFiles
        // null) aunque los archivos estén; y exists()/length() mienten
        // cuando el stat está denegado. Se combinan el listado (si
        // funciona) con la sonda de lectura por nombre esperado.
        val landedByName = mutableMapOf<String, ObbFileInfo>()
        try {
            if (obbDir.exists()) {
                obbDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
                    .forEach { landedByName[it.name] = ObbFileInfo(it.name, it.name, it.length()) }
            }
        } catch (_: Exception) {
        }
        for (e in expected) {
            // v0.1.32: si el nombre no se lee, probar la ruta relativa
            // dentro de la carpeta (carpeta movida entera con
            // subcarpetas). Para infos por archivo, la ruta relativa es
            // del staging y no existe bajo obbDir: la sonda da null y no
            // cambia nada.
            val size = readProbeSize(File(obbDir, e.name))
                ?: if (e.relativePath.contains(File.separator)) {
                    readProbeSize(File(obbDir, e.relativePath))
                } else {
                    null
                }
            if (size != null) landedByName[e.name] = ObbFileInfo(e.name, e.name, size)
        }
        val landed = landedByName.values.toList()
        if (expected.isNotEmpty()) {
            val allThere = expected.all { e -> landedByName[e.name]?.size == e.size }
            if (allThere) {
                return ObbUiState.Copied(
                    expected,
                    expected.sumOf { it.size },
                    record?.obbCompletedAtMs ?: 0L
                )
            }
            // v0.1.30: el registro dice COMPLETE sin verificar y NINGUNA
            // sonda devolvió tamaño (lectura denegada en Horizon): la
            // copia escribió sin error, así que es "copiado, sin
            // verificar", no un fallo. Si alguna sonda devolvió un
            // tamaño DISTINTO al esperado (parcial), eso sí es fallo y
            // cae al when de abajo.
            val allIllegible = expected.all { landedByName[it.name] == null }
            if (allIllegible && record?.obbState == ObbRecordState.COMPLETE &&
                record.obbUnverified && pending == null
            ) {
                return ObbUiState.Copied(
                    expected,
                    expected.sumOf { it.size },
                    record.obbCompletedAtMs,
                    unverified = true
                )
            }
            if (pending != null) {
                val reason = pending.lastError.ifBlank { context.getString(R.string.obb_reason_pending) }
                return when {
                    !Environment.isExternalStorageManager() -> ObbUiState.NotGranted(expected)
                    record?.obbState == ObbRecordState.FAILED -> ObbUiState.Failed(
                        expected,
                        record.obbError.ifBlank { reason }
                    )
                    else -> ObbUiState.Pending(expected, reason)
                }
            }
            return when (record?.obbState) {
                ObbRecordState.COMPLETE -> ObbUiState.Failed(
                    expected,
                    context.getString(R.string.obb_reason_missing_after_copy, packageName)
                )
                ObbRecordState.NOT_GRANTED -> ObbUiState.NotGranted(expected)
                ObbRecordState.FAILED -> ObbUiState.Failed(expected, record.obbError.ifBlank { context.getString(R.string.obb_reason_copy_failed) })
                else -> ObbUiState.Pending(expected, context.getString(R.string.obb_reason_pending))
            }
        }
        if (landed.isNotEmpty()) {
            return ObbUiState.Copied(landed, landed.sumOf { it.size }, record?.obbCompletedAtMs ?: 0L)
        }
        if (pending != null) {
            return ObbUiState.Pending(emptyList(), pending.lastError.ifBlank { context.getString(R.string.obb_reason_pending) })
        }
        // v0.1.28: SIN información (ni esperados, ni copiados, ni
        // pendiente) NO se puede decir "sin OBB". Solo es NoObb cuando
        // el registro lo comprobó de verdad (instalación terminada sin
        // OBB). Un juego nunca instalado/descargado es Unknown: se sabrá
        // al descargar. Un registro PENDING/FAILED sin archivos esperados
        // se enseña como pendiente, no como "sin OBB".
        return when (record?.obbState) {
            ObbRecordState.NO_OBB -> ObbUiState.NoObb
            ObbRecordState.PENDING -> ObbUiState.Pending(
                emptyList(), record.obbError.ifBlank { context.getString(R.string.obb_reason_pending) }
            )
            ObbRecordState.FAILED -> ObbUiState.Failed(
                emptyList(), record.obbError.ifBlank { context.getString(R.string.obb_reason_obb_copy_failed) }
            )
            ObbRecordState.NOT_GRANTED -> ObbUiState.NotGranted(emptyList())
            else -> ObbUiState.Unknown
        }
    }

    /**
     * Reintenta la copia del OBB pendiente de un juego (v0.1.27, botón
     * Reintentar de la ficha y de Instalados): directa primero, luego
     * ADB. Devuelve true si el OBB quedó completo. Si el pendiente se
     * perdió pero el juego sigue en disco, se reconstruye desde lo
     * extraído.
     */
    suspend fun retryObb(packageName: String): Boolean {
        // v0.1.29: si el APK fue rechazado por firma distinta, reintentar
        // el OBB no tiene sentido (el APK nunca se instaló): la salida
        // es desinstalar la versión actual desde la ficha.
        val record0 = settings.getInstallRecord(packageName)
        if (record0?.signatureMismatch == true) {
            StartupLog.mark(
                context,
                "obb reintentar: $packageName no se reintenta (firma distinta: desinstala la versión actual)"
            )
            return false
        }
        if (settings.pendingObbFor(packageName) == null) {
            val staging = findStagingForRetry(packageName)
            if (staging == null) {
                // v0.1.32: el staging puede no traer .obb porque la
                // carpeta se MOVIÓ a /Android/obb/<paquete>: si el
                // destino los tiene, está completo.
                if (obbUiState(packageName) is ObbUiState.Copied) {
                    StartupLog.mark(
                        context,
                        "obb reintentar: $packageName ya tiene el OBB en su sitio (la carpeta se movió; el staging ya no trae los .obb)"
                    )
                    return true
                }
                StartupLog.mark(context, "obb reintentar: $packageName sin staging en disco, nada que copiar")
                return false
            }
            // v0.1.29: no reconstruir un pendiente para un juego
            // instalado con OTRA versión/firma (Maestro de la tienda):
            // el staging es del repo, el instalado no.
            val installedVc = repoManager.installedVersionCode(packageName)
            val stagedVc = stagedVersionCode(File(downloadsBase(), packageName), packageName)
            if (stagedVc != null && installedVc > 0 && stagedVc != installedVc) {
                StartupLog.mark(
                    context,
                    "obb reintentar: $packageName no se reconstruye (instalado v$installedVc, staging v$stagedVc: otra firma u otra versión)"
                )
                return false
            }
            val record = settings.getInstallRecord(packageName)
            val files = ArchiveExtractor.listDataFiles(staging)
            settings.addPendingObb(
                PendingObb(
                    packageName = packageName,
                    stagingDir = staging.absolutePath,
                    versionCode = record?.versionCode ?: 0,
                    gameName = record?.gameName ?: packageName,
                    obbFiles = files,
                )
            )
            StartupLog.mark(
                context,
                "obb reintentar: $packageName pendiente reconstruido desde ${staging.absolutePath} (${files.size} archivos de datos)"
            )
        } else {
            // v0.1.29: el reintento manual pone a 0 el contador de
            // fallos seguidos (el temporizador lo suspendió tras 2).
            settings.pendingObbFor(packageName)?.let {
                settings.addPendingObb(it.copy(failedAttempts = 0, lastError = ""))
            }
        }
        completePendingObbs(force = true)
        val done = obbUiState(packageName) is ObbUiState.Copied
        StartupLog.mark(context, "obb reintentar: $packageName ${if (done) "COMPLETO" else "sigue pendiente"}")
        if (done) {
            val rec = settings.getInstallRecord(packageName)
            InstallSuccessEvents.emit(
                gameName = rec?.gameName ?: packageName,
                detail = context.getString(R.string.obb_completed_detail, packageName),
                packageName = packageName,
            )
        }
        return done
    }

    /** Raíz extraída con .obb de un juego instalado, para reconstruir
     *  un pendiente perdido (game_extracted u obb_extracted). */
    private fun findStagingForRetry(packageName: String): File? {
        val base = File(downloadsBase(), packageName)
        val extracted = File(base, "game_extracted")
        if (extracted.isDirectory && VrpPublicRepo(context).hasGameDataFiles(extracted)) return extracted
        val obbExtracted = File(base, "obb_extracted")
        if (obbExtracted.isDirectory && VrpPublicRepo(context).hasGameDataFiles(obbExtracted)) return obbExtracted
        return null
    }

    private suspend fun runInstall(entry: RepoEntry) {
        val dir = workDirFor(entry).apply { mkdirs() }
        // Preparar: descargar (APK+OBB sueltos, o juego completo VRP).
        // v0.1.17: si el juego VRP ya está descargado Y extraído en disco
        // (app.apk + game_extracted con su APK), instalar desde disco sin
        // re-descargar ni re-extraer: el botón Instalar con estado
        // "Descargado" re-ejecutaba todo el flujo y volvía a atascarse en
        // la extracción.
        val prepared = try {
            if (entry.vrpHash != null) {
                preparedFromDisk(entry, dir) ?: prepareVrp(entry, dir)
            } else prepareNormal(entry, dir)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            StartupLog.mark(
                context,
                "install: preparación FALLÓ: ${AdbHealth.fullChainReason(e) ?: e.toString()}"
            )
            // v0.1.22: la UI enseña la cadena COMPLETA (truncada a 500):
            // el error del usuario (05:22) solo decía "Error:
            // okhttp3.internal.platform.ConscryptPlatform" y no se podía
            // diagnosticar sin la causa raíz.
            emit(InstallProgress.Done(false, "Error: ${(AdbHealth.fullChainReason(e) ?: e.toString()).take(500)}"))
            return
        } ?: return // prepare* ya emitió el fallo
        val apkFile = prepared.apkFile
        val obbStaging = prepared.obbStaging
        val installTxt = prepared.installTxt

        // v0.1.23: la fase cambia AQUÍ, antes de tocar el ADB. En la
        // v0.1.22 la UI se quedaba en "Extrayendo…" mientras adbReady()
        // se colgaba en el connect (log 06:00:22: última línea "adb
        // connect: huella …" y nada después): la extracción YA había
        // terminado pero ninguna fase posterior se emitía.
        emit(InstallProgress.InstallingApk(context.getString(R.string.install_extract_done_adb)))

        // 4. ¿ADB disponible? Si no, intentar conectar; si sigue sin haberlo, fallback.
        val adb = adbReady()
        if (adb == null) {
            StartupLog.mark(context, "install: ADB no disponible, fallback instalador del sistema")
            emit(InstallProgress.InstallingApk(context.getString(R.string.install_no_adb_fallback)))
            val fb = installViaSystemFallback(entry, apkFile, obbStaging)
            // v0.1.25: install.txt SIN ADB — si existe y la APK se instaló,
            // ejecutar lo traducible con I/O Java directo (push/cp/mv/mkdir/rm,
            // pm install por el instalador del sistema); lo que necesita shell
            // se omite con su línea en el log y se avisa en la UI. Nunca tumba
            // la instalación: el APK+OBB ya están completos por el fallback.
            if (fb.installed && installTxt != null) {
                if (prepared.extractArchivesFirst) {
                    // Extraer .7z/.zip de la carpeta antes, como hace el PC
                    // y como el paso 5 con ADB.
                    dir.listFiles()?.filter { it.extension.equals("7z", true) || it.extension.equals("zip", true) }
                        ?.forEach { ArchiveExtractor.extract(it, dir) }
                }
                runInstallTxtNoAdb(entry, installTxt, prepared.installTxtDir, apkFile, obbStaging, fb.obb)
            }
            return
        }

        // 5. install.txt traducido (si existe) — manda como en el PC.
        if (installTxt != null) {
            val translator = InstallTxtTranslator(adb, prepared.installTxtDir)
            if (prepared.extractArchivesFirst) {
                // Extraer .7z/.zip de la carpeta antes, como hace el PC.
                dir.listFiles()?.filter { it.extension.equals("7z", true) || it.extension.equals("zip", true) }
                    ?.forEach { ArchiveExtractor.extract(it, dir) }
            }
            for (step in translator.run(installTxt)) {
                emit(InstallProgress.RunningInstallTxt(step.line))
                if (!step.ok) {
                    updateRecord(entry) {
                        it.copy(
                            state = InstallRecordState.FAILED,
                            error = context.getString(R.string.install_txt_failed_short, step.line),
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                    emit(InstallProgress.Done(false, context.getString(R.string.install_txt_failed_detail, step.line, step.output)))
                    return
                }
            }
            settings.setInstalledVersion(entry.packageName, entry.versionCode)
            // install.txt manda también sobre el OBB (lo copia él mismo):
            // el estado real se comprueba en disco, no se supone.
            refreshObbRecordFromDisk(entry, obbStaging)
            updateRecord(entry) {
                it.copy(
                    apkInstalled = true,
                    apkInstalledAtMs = System.currentTimeMillis(),
                    state = if (it.obbState == ObbRecordState.PENDING || it.obbState == ObbRecordState.FAILED) InstallRecordState.PENDING_OBB else InstallRecordState.COMPLETE,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            emit(InstallProgress.Done(true, context.getString(R.string.install_txt_done, entry.displayName)))
            return
        }

        // 6. Instalar APK vía ADB shell.
        emit(InstallProgress.InstallingApk(context.getString(R.string.install_installing_game, entry.displayName)))
        val installRes = adb.installApk(apkFile.absolutePath)
        if (!adb.installSucceeded(installRes)) {
            StartupLog.mark(
                context,
                "install: pm install FALLÓ: ${pmFailureDetail(installRes)} (code=${installRes.code})"
            )
            if (GiraAdbManager.isTransportErrorText(installRes.error)) {
                StartupLog.mark(context, "install: error de transporte ADB, fallback instalador del sistema")
                emit(InstallProgress.InstallingApk(context.getString(R.string.install_adb_lost_fallback)))
                installViaSystemFallback(entry, apkFile, obbStaging)
                return
            }
            updateRecord(entry) {
                it.copy(
                    state = InstallRecordState.FAILED,
                    error = context.getString(R.string.install_pm_failed, pmFailureDetail(installRes)),
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            emit(InstallProgress.Done(false, context.getString(R.string.install_pm_failed, pmFailureDetail(installRes))))
            return
        }
        updateRecord(entry) {
            it.copy(
                apkInstalled = true,
                apkInstalledAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
        }

        // 7. Copiar OBB (esto es lo que una app normal NO puede hacer vía ADB).
        if (obbStaging != null) {
            emit(InstallProgress.CopyingObb(context.getString(R.string.install_copying_obb)))
            // v0.1.21: primero la copia DIRECTA (Java, sin ADB): si el
            // permiso está concedido y el FUSE lo permite, el OBB se
            // copia aunque el ADB esté muerto (y el ADB acaba de morir
            // en el pm install en casi todos los intentos del usuario).
            val direct = obbWorkMutex.withLock {
                copyObbDirect(entry.packageName, obbStaging)
            }
            if (direct is DirectObbResult.Ok) {
                StartupLog.mark(
                    context,
                    "install: OBB copiado por vía DIRECTA (${direct.files.size} .obb, sin ADB)" +
                        if (direct.unverified) " (copiado, sin verificar)" else ""
                )
                updateRecord(entry) {
                    it.copy(
                        obbState = ObbRecordState.COMPLETE,
                        obbFiles = direct.files,
                        obbCopiedBytes = direct.bytes,
                        obbError = "",
                        obbCompletedAtMs = System.currentTimeMillis(),
                        obbUnverified = direct.unverified,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                ObbEvents.emit(
                    ObbEvents.Event.Completed(
                        entry.packageName, entry.displayName, direct.files.size, direct.bytes
                    )
                )
            } else {
                val copyRes = adb.copyObb(entry.packageName, obbStaging.absolutePath)
                if (!copyRes.ok) {
                    // v0.1.19: la copia falló, pero el OBB NO se pierde: queda
                    // pendiente y se reintentará solo (nada de pedirle al
                    // usuario que lo haga a mano). v0.1.27: esto es un estado
                    // EN CURSO (ObbPending), no un Done(false): la APK SÍ
                    // está instalada y el OBB sigue intentándose.
                    val reason = copyRes.detail.take(300)
                    StartupLog.mark(
                        context,
                        "install: copiar OBB FALLÓ: $reason — queda pendiente"
                    )
                    recordPendingObb(entry, obbStaging, reason)
                    updateRecord(entry) {
                        it.copy(
                            obbState = ObbRecordState.FAILED,
                            obbError = reason,
                            state = InstallRecordState.PENDING_OBB,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                    ObbEvents.emit(
                        ObbEvents.Event.Pending(entry.packageName, entry.displayName, reason)
                    )
                    emit(
                        InstallProgress.ObbPending(
                            entry.packageName,
                            entry.displayName,
                            context.getString(R.string.install_apk_done_obb_pending),
                            reason
                        )
                    )
                    return
                }
                refreshObbRecordFromDisk(entry, obbStaging)
                val refreshed = settings.getInstallRecord(entry.packageName)
                if (refreshed != null && refreshed.obbState == ObbRecordState.COMPLETE) {
                    ObbEvents.emit(
                        ObbEvents.Event.Completed(
                            entry.packageName, entry.displayName,
                            refreshed.obbFiles.size, refreshed.obbCopiedBytes
                        )
                    )
                }
            }
        } else {
            // v0.1.32: reinstalación de la APK tras un movimiento de
            // carpeta: el staging ya no trae .obb pero el destino sí.
            val alreadyThere = recordObbAlreadyThere(entry.packageName)
            if (alreadyThere != null) {
                StartupLog.mark(
                    context,
                    "install: ${entry.displayName} reinstalado (APK); el OBB ya estaba en su sitio (${alreadyThere.size} .obb)"
                )
                updateRecord(entry) {
                    it.copy(
                        obbState = ObbRecordState.COMPLETE,
                        obbFiles = alreadyThere,
                        obbCopiedBytes = alreadyThere.sumOf { f -> f.size },
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            } else {
                updateRecord(entry) {
                    it.copy(obbState = ObbRecordState.NO_OBB, updatedAtMs = System.currentTimeMillis())
                }
            }
        }

        settings.setInstalledVersion(entry.packageName, entry.versionCode)
        updateRecord(entry) {
            it.copy(state = InstallRecordState.COMPLETE, updatedAtMs = System.currentTimeMillis())
        }
        val record = settings.getInstallRecord(entry.packageName)
        val obbDetail = if (record != null && record.obbState == ObbRecordState.COMPLETE && record.obbFiles.isNotEmpty()) {
            formatPlacedDetail(record.obbFiles, record.obbCopiedBytes)
        } else if (obbStaging != null) {
            "APK + OBB"
        } else {
            "APK"
        }
        emit(
            InstallProgress.Done(
                true,
                context.getString(R.string.install_complete_with_detail, entry.displayName, entry.versionName, obbDetail)
            )
        )
        InstallNotifier.notifyComplete(
            context,
            entry.packageName, entry.versionCode, entry.displayName,
            context.getString(R.string.install_complete_notif_detail, entry.displayName, obbDetail, entry.packageName)
        )
        val completedOthers = completePendingObbs(force = true)
        if (completedOthers.isNotEmpty()) {
            emit(
                InstallProgress.Done(
                    true,
                    context.getString(R.string.obb_completed_toast, completedOthers.joinToString(", ")),
                    silent = true
                )
            )
        }
    }

    /** Comprueba en disco si el OBB está ya en su sitio y actualiza el
     *  registro con el resultado real (COMPLETE con archivos y bytes, o
     *  PENDING con motivo). Nunca supone el resultado.
     *
     *  v0.1.33: "archivos esperados" = TODOS los archivos de datos
     *  (no solo .obb): un juego sin .obb (Hot Dogs, .hdog) también
     *  tiene datos que colocar. */
    private fun refreshObbRecordFromDisk(entry: RepoEntry, obbStaging: File?) {
        if (obbStaging == null) {
            updateRecord(entry) {
                it.copy(obbState = ObbRecordState.NO_OBB, updatedAtMs = System.currentTimeMillis())
            }
            return
        }
        val expected = ArchiveExtractor.listDataFiles(obbStaging)
        if (expected.isEmpty()) {
            updateRecord(entry) {
                it.copy(obbState = ObbRecordState.NO_OBB, updatedAtMs = System.currentTimeMillis())
            }
            return
        }
        val allThere = obbAlreadyThereByInfos(entry.packageName, expected)
        updateRecord(entry) {
            if (allThere) {
                it.copy(
                    obbState = ObbRecordState.COMPLETE,
                    obbFiles = expected,
                    obbCopiedBytes = expected.sumOf { f -> f.size },
                    obbError = "",
                    obbCompletedAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis()
                )
            } else {
                it.copy(
                    obbState = ObbRecordState.PENDING,
                    obbFiles = expected,
                    obbError = "no todos los archivos de datos están en /Android/obb/${entry.packageName}/",
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun formatSizeBytes(bytes: Long): String {
        if (bytes <= 0) return "?"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    /** Detalle "APK + …" de un juego ya colocado (v0.1.33): los
     *  archivos colocados NO son siempre .obb (Maestro trae .pak,
     *  Hot Dogs trae .hdog y ningún .obb). Nunca se dice "OBB" cuando
     *  no hay ninguno: se dice cuántos archivos de datos y cuántos
     *  bytes se colocaron. */
    private fun formatPlacedDetail(files: List<ObbFileInfo>, bytes: Long): String {
        val obbCount = files.count { it.name.endsWith(".obb", ignoreCase = true) }
        val size = formatSizeBytes(bytes)
        return when {
            files.isEmpty() -> "APK"
            obbCount == files.size -> context.getString(R.string.placed_detail_obb, obbCount, size)
            obbCount == 0 -> context.getString(R.string.placed_detail_data, files.size, size)
            else -> context.getString(R.string.placed_detail_mixed, obbCount, files.size - obbCount, size)
        }
    }

    /** Lo que deja lista la fase de descarga, sea cual sea el tipo de repo. */
    private data class PreparedInstall(
        val apkFile: File,
        val obbStaging: File?,
        val installTxt: File?,
        /** Carpeta contra la que se resuelven las rutas relativas de install.txt. */
        val installTxtDir: File,
        /** Extraer .7z/.zip de la carpeta antes de correr install.txt (flujo normal). */
        val extractArchivesFirst: Boolean,
    )

    /** Flujo normal (repo.json propio): APK suelto + OBB suelto. */
    private suspend fun prepareNormal(entry: RepoEntry, dir: File): PreparedInstall? {
        val apkFile = File(dir, "app.apk")

        // 1. Descargar APK (con reanudación y SHA-256 si el repo lo trae).
        emit(InstallProgress.DownloadingApk(0, 0L))
        when (val r = downloader.download(
            entry.apkUrl, apkFile, entry.apkSha256, entry.apkSize,
            onProgress = { p -> emit(InstallProgress.DownloadingApk(p.percent, p.speedBytesPerSec)) }
        )) {
            is DownloadResult.Failed -> {
                emit(InstallProgress.Done(false, context.getString(R.string.install_apk_download_failed, r.reason)))
                return null
            }
            is DownloadResult.Ok -> { /* seguir */ }
        }

        // 2. install.txt (si el repo lo trae): manda sobre el flujo normal, como en el PC.
        var installTxt: File? = null
        if (!entry.installTxtUrl.isNullOrBlank()) {
            val txtFile = File(dir, "install.txt")
            when (downloader.download(entry.installTxtUrl, txtFile)) {
                is DownloadResult.Ok -> installTxt = txtFile
                is DownloadResult.Failed -> { /* sin install.txt, flujo normal */ }
            }
        }

        // 3. Descargar + extraer OBB si lo hay.
        var obbStaging: File? = null
        if (entry.hasObb) {
            val obbName = entry.obbFile?.takeIf { it.isNotBlank() } ?: "obb.dat"
            val obbFile = File(dir, obbName)
            emit(InstallProgress.DownloadingObb(0, 0L))
            when (val r = downloader.download(
                entry.obbUrl!!, obbFile,
                onProgress = { p -> emit(InstallProgress.DownloadingObb(p.percent, p.speedBytesPerSec)) }
            )) {
                is DownloadResult.Failed -> {
                    emit(InstallProgress.Done(false, context.getString(R.string.install_obb_download_failed, r.reason)))
                    return null
                }
                is DownloadResult.Ok -> { /* seguir */ }
            }
            emit(InstallProgress.ExtractingObb(context.getString(R.string.install_extracting_obb)))
            val extracted = File(dir, "obb_extracted")
            ArchiveExtractor.extract(obbFile, extracted) { written, total, _ ->
                val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else -1
                if (pct >= 0) {
                    emit(
                        InstallProgress.ExtractingObb(
                            context.getString(R.string.install_extracting_obb_pct, pct, written / 1_048_576, total / 1_048_576),
                            pct,
                        )
                    )
                }
            }
            // v0.1.27: staging = la raíz extraída ENTERA (no una sola
            // subcarpeta): la copia camina todo el árbol y cubre todos
            // los .obb, estén donde estén.
            obbStaging = extracted
            logObbList("install normal", extracted)
        }
        return PreparedInstall(apkFile, obbStaging, installTxt, dir, extractArchivesFirst = true)
    }

    /** Deja en el log la lista COMPLETA de .obb encontrados (ruta
     *  relativa + tamaño) ANTES de copiarlos: si alguno falta después,
     *  aquí se ve cuál era. */
    private fun logObbList(tag: String, root: File) {
        val files = ArchiveExtractor.listDataFiles(root)
        StartupLog.mark(
            context,
            "$tag: archivos de datos (${files.size}): " +
                if (files.isEmpty()) "(ninguno)"
                else files.joinToString(", ") { "${it.relativePath} (${it.size} bytes)" }.take(1000)
        )
    }

    /**
     * v0.1.17: juego VRP ya descargado y extraído en disco — saltarse
     * descarga y extracción e instalar directamente. Condiciones:
     * app.apk existe y no está vacío, y game_extracted contiene un APK
     * del MISMO tamaño (si no coincide, es de otra versión y hay que
     * re-descargar). Devuelve null si no se cumple: entonces corre el
     * flujo completo (prepareVrp).
     */
    private fun preparedFromDisk(entry: RepoEntry, dir: File): PreparedInstall? {
        val apkFile = File(dir, "app.apk")
        val extracted = File(dir, "game_extracted")
        val vrp = RepoManager(context).vrpClientFor(entry)
        // v0.1.27: la extracción huérfana terminó en segundo plano. La
        // extracción corre en un hilo del proceso: si la app se reinicia
        // (log 16:33:56, Arizona al 59%), el hilo muere y app.apk nunca
        // se copió — pero si el marcador .extracted_ok existe y el APK
        // está en lo extraído, no se re-descarga ni se re-extrae: se
        // copia el APK y se instala desde disco.
        if ((!apkFile.exists() || apkFile.length() <= 0L) &&
            File(extracted, ".extracted_ok").exists()
        ) {
            val apkInExtracted = vrp.findGameApk(extracted)
            if (apkInExtracted != null) {
                apkInExtracted.copyTo(apkFile, overwrite = true)
                StartupLog.mark(
                    context,
                    "install: la extracción terminó en segundo plano (hilo huérfano): " +
                        "APK recuperado de lo extraído (${apkFile.length()} bytes), sin re-descargar ni re-extraer"
                )
            }
        }
        if (!apkFile.exists() || apkFile.length() <= 0L) return null
        val apkInExtracted = vrp.findGameApk(extracted) ?: return null
        if (apkInExtracted.length() != apkFile.length()) {
            StartupLog.mark(
                context,
                "install: app.apk en disco no coincide con lo extraído " +
                    "(${apkFile.length()} vs ${apkInExtracted.length()} bytes): re-descargando"
            )
            return null
        }
        // v0.1.27: staging = la raíz extraída ENTERA (no una sola
        // subcarpeta): la copia camina todo el árbol.
        // v0.1.33: hay datos si hay archivos de datos (no solo .obb).
        // v0.1.39: si el juego tenía OBB/carpeta de paquete pero se movió
        // de game_extracted antes y ya NO está en /Android/obb/<pkg>/
        // (p. ej. tras desinstalar), no instalar solo la APK: devolver null
        // para re-extraer / re-descargar los datos completos.
        val hasStagingData = vrp.hasGameDataFiles(extracted)
        if (!hasStagingData) {
            val rec = settings.getInstallRecord(entry.packageName)
            val expectedBefore = rec?.obbFiles?.isNotEmpty() == true ||
                ArchiveExtractor.hasPkgDir(extracted, entry.packageName)
            if (expectedBefore && recordObbAlreadyThere(entry.packageName) == null) {
                StartupLog.mark(
                    context,
                    "install: ${entry.packageName} tenía datos en disco que ya se movieron y ya no están en Android/obb; se re-extrae/re-descarga"
                )
                return null
            }
        }
        val obbStaging = if (hasStagingData) extracted else null
        if (obbStaging != null) logObbList("install desde disco", extracted)
        val installTxt = File(extracted, "install.txt").takeIf { it.exists() }
        StartupLog.mark(
            context,
            "install: ya descargado, instalando desde disco sin re-descargar " +
                "(APK ${apkFile.length()} bytes, OBB=${obbStaging != null}, install.txt=${installTxt != null})"
        )
        emit(InstallProgress.InstallingApk(context.getString(R.string.install_from_disk, entry.displayName)))
        return PreparedInstall(apkFile, obbStaging, installTxt, extracted, extractArchivesFirst = false)
    }

    /**
     * Flujo del repositorio público VRP (como el PC): descargar los trozos
     * `<hash>.7z.001…`, unirlos, extraer con la contraseña y de ahí sacar
     * el APK, el OBB y el install.txt si lo trae el archivo.
     */
    private suspend fun prepareVrp(entry: RepoEntry, dir: File): PreparedInstall? {
        val vrp = RepoManager(context).vrpClientFor(entry)
        // v0.1.27 COMPROBACIÓN DE ESPACIO (el caso es quedarse sin
        // espacio): ANTES de descargar nada, con el tamaño ya consultado
        // (caché de la ficha/confirmación, o consulta ahora), se compara
        // con el espacio libre real. La descarga+extracción necesita el
        // doble temporalmente (el .7z unido + lo extraído) más 500 MB de
        // margen. Si no cabe, NO se descarga y se dice claro.
        val knownSize = gameDownloadSize(entry)
        if (knownSize != null && knownSize.totalBytes > 0) {
            when (val space = checkSpace(knownSize.totalBytes, knownSize.totalBytes)) {
                is SpaceCheck.NoFit -> {
                    val msg = context.getString(
                        R.string.install_no_space_before,
                        entry.displayName,
                        formatSizeBytes(knownSize.totalBytes),
                        formatSizeBytes(space.freeBytes)
                    )
                    StartupLog.mark(context, "install vrp SIN ESPACIO: $msg")
                    updateRecord(entry) {
                        it.copy(
                            downloadBytes = knownSize.totalBytes,
                            downloadParts = knownSize.parts,
                            state = InstallRecordState.FAILED,
                            error = msg,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                    emit(InstallProgress.Done(false, msg))
                    return null
                }
                is SpaceCheck.Tight -> {
                    StartupLog.mark(
                        context,
                        "install vrp ESPACIO JUSTO: ${entry.displayName} ${formatSizeBytes(knownSize.totalBytes)}, " +
                            "libres ${formatSizeBytes(space.freeBytes)}; tras descargar+extraer quedarían " +
                            "${formatSizeBytes(space.remainingBytes)} — la extracción necesita espacio temporal"
                    )
                    emit(
                        InstallProgress.DownloadingGame(0)
                    )
                }
                is SpaceCheck.Fits -> { /* cabe con margen, se sigue */ }
            }
            updateRecord(entry) {
                it.copy(
                    downloadBytes = knownSize.totalBytes,
                    downloadParts = knownSize.parts,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
        emit(InstallProgress.DownloadingGame(0, 0L))
        val archives = try {
            vrp.downloadGame(
                entry,
                dir,
                onProgress = { pct, speed -> emit(InstallProgress.DownloadingGame(pct, speed)) },
            )
        } catch (e: Throwable) {
            // v0.1.22: Throwable, no Exception: un NoClassDefFoundError
            // (el error del usuario 05:22) es un Error y se escapaba de aquí.
            if (e is CancellationException) throw e
            val noSpace = isNoSpaceError(e)
            if (noSpace) {
                // Disco lleno a mitad de descarga: decirlo tal cual y
                // limpiar los trozos parciales (no dejar GB de basura).
                cleanupPartialDownload(dir, entry.vrpHash)
            }
            StartupLog.mark(
                context,
                "install vrp DESCARGA FALLÓ: ${AdbHealth.fullChainReason(e) ?: e.toString()}"
            )
            updateRecord(entry) {
                it.copy(
                    state = InstallRecordState.FAILED,
                    error = if (noSpace) "sin espacio en el almacenamiento" else (AdbHealth.fullChainReason(e) ?: e.toString()).take(300),
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            emit(
                InstallProgress.Done(
                    false,
                    if (noSpace) {
                        context.getString(R.string.install_no_space_during, entry.displayName)
                    } else {
                        context.getString(R.string.install_download_failed, (AdbHealth.fullChainReason(e) ?: e.toString()).take(500))
                    }
                )
            )
            return null
        }
        val archiveBytes = archives.sumOf { it.length() }
        val archiveLabel = if (archives.size == 1) archives[0].name else "${archives[0].name}..(${archives.size} partes)"
        updateRecord(entry) {
            it.copy(
                downloadBytes = archiveBytes,
                downloadDone = true,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        emit(InstallProgress.ExtractingObb(context.getString(R.string.install_extracting_game)))
        StartupLog.mark(
            context,
            "install vrp: extrayendo $archiveLabel ($archiveBytes bytes) a game_extracted"
        )
        val extracted = File(dir, "game_extracted")
        val markerFile = File(extracted, ".extracting")
        val markerText = "${archives[0].name}|$archiveBytes"
        val resume = try {
            extracted.isDirectory && markerFile.exists() &&
                markerFile.readText().trim() == markerText
        } catch (_: Exception) {
            false
        }
        if (extracted.exists() && extracted.listFiles()?.isNotEmpty() == true && !resume) {
            StartupLog.mark(
                context,
                "install vrp: la extracción anterior se interrumpió (sin marcador válido); " +
                    "se empieza de nuevo desde el principio (la descarga ya está guardada, no se re-descarga)"
            )
            emit(
                InstallProgress.ExtractingObb(
                    context.getString(R.string.install_extract_restarting),
                    0
                )
            )
        }
        if (!resume) {
            extracted.deleteRecursively()
            extracted.mkdirs()
        }
        try {
            markerFile.writeText(markerText)
        } catch (_: Exception) {
        }
        // v0.1.23: progreso REAL (lo pidel usuario): % de bytes escritos
        // sobre el total sin comprimir de la cabecera del 7z; el log
        // anota cada 5% (sin spam: el % entero cambia como mucho 101
        // veces). Se emite también el 100% al terminar, antes de pasar
        // a la fase siguiente — la pantalla nunca se queda en
        // "Extrayendo…" con la extracción ya terminada.
        // v0.1.27: velocidad real (MB/s) y tiempo estimado en la línea
        // ("Extrayendo… 42% (512 MB de 1093 MB, 38 MB/s, quedan ~15 min)").
        val extractStartedAt = System.currentTimeMillis()
        var lastLoggedPct = -1
        var lastSpeedMBps = 0.0
        val onExtractProgress: (Long, Long, String) -> Unit = { written, total, entryName ->
            val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else -1
            if (pct >= 0) {
                val elapsedSec = ((System.currentTimeMillis() - extractStartedAt) / 1000).coerceAtLeast(1)
                val speed = written / 1_048_576.0 / elapsedSec
                lastSpeedMBps = speed
                val etaSec = if (speed > 0.01 && total > written) {
                    ((total - written) / 1_048_576.0 / speed).toLong()
                } else {
                    -1L
                }
                val etaText = if (etaSec >= 0) context.getString(R.string.install_eta_suffix, formatEta(etaSec)) else ""
                emit(
                    InstallProgress.ExtractingObb(
                        context.getString(
                            R.string.install_extracting_game_pct,
                            pct,
                            written / 1_048_576,
                            total / 1_048_576,
                            "${formatMBps(speed)}$etaText"
                        ),
                        pct,
                    )
                )
                if (pct >= lastLoggedPct + 5) {
                    lastLoggedPct = pct
                    StartupLog.mark(
                        context,
                        "install vrp: extrayendo… $pct% ($written de $total bytes, ${formatMBps(speed)}$etaText, $entryName)"
                    )
                }
            }
        }
        // v0.1.27: WATCHDOG DE PROGRESO en vez de tope fijo de 10 min.
        // El tope de la v0.1.23 disparaba a mitad de juegos grandes
        // legítimos (Arizona: 600 s a las 15:20:54 con la extracción al
        // 19% y el hilo huérfano siguiendo). Ahora solo se declara
        // atascada si no se escriben bytes durante 5 min seguidos, sin
        // tope total; y nunca se dice "cancelado" mientras el hilo sigue
        // trabajando (el marcador queda para reanudar).
        when (val extractResult = runExtractionWithWatchdog(vrp, archives, extracted, resume, onExtractProgress)) {
            is ExtractOutcome.Stalled -> {
                StartupLog.mark(
                    context,
                    "install vrp EXTRACCIÓN ATASCADA: ${EXTRACT_STALL_MS / 60_000} min sin escribir " +
                        "(último ${extractResult.lastPct}%, ${extractResult.written} de ${extractResult.total} bytes, " +
                        "${formatMBps(extractResult.speedMBps)}; archivo $archiveLabel, $archiveBytes bytes)"
                )
                updateRecord(entry) {
                    it.copy(
                        state = InstallRecordState.FAILED,
                        error = "extracción atascada: ${EXTRACT_STALL_MS / 60_000} min sin avanzar",
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                emit(
                    InstallProgress.Done(
                        false,
                        context.getString(
                            R.string.install_extract_stalled,
                            EXTRACT_STALL_MS / 60_000,
                            extractResult.lastPct,
                            extractResult.written / 1_048_576,
                            extractResult.total / 1_048_576,
                            formatMBps(extractResult.speedMBps)
                        )
                    )
                )
                return null
            }
            is ExtractOutcome.Failed -> {
                val extractError = extractResult.error
                if (extractError is CancellationException) throw extractError
                StartupLog.mark(
                    context,
                    "install vrp EXTRACCIÓN FALLÓ: ${AdbHealth.fullChainReason(extractError) ?: extractError.toString()}"
                )
                updateRecord(entry) {
                    it.copy(
                        state = InstallRecordState.FAILED,
                        error = (AdbHealth.fullChainReason(extractError) ?: extractError.toString()).take(300),
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                emit(
                    InstallProgress.Done(
                        false,
                        context.getString(R.string.install_extract_failed, (AdbHealth.fullChainReason(extractError) ?: extractError.toString()).take(500))
                    )
                )
                return null
            }
            is ExtractOutcome.Ok -> {
                emit(InstallProgress.ExtractingObb(context.getString(R.string.install_extract_complete_100), 100))
                StartupLog.mark(context, "install vrp: extracción terminada OK (100%)")
                try {
                    markerFile.delete()
                    File(extracted, ".extracted_ok").writeText(markerText)
                } catch (_: Exception) {
                }
                updateRecord(entry) {
                    it.copy(
                        extractDone = true,
                        extractSpeedMBps = lastSpeedMBps,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            }
        }
        val apk = vrp.findGameApk(extracted) ?: run {
            emit(InstallProgress.Done(false, context.getString(R.string.install_no_apk_found)))
            return null
        }
        val apkFile = File(dir, "app.apk")
        apk.copyTo(apkFile, overwrite = true)
        // v0.1.19: dejar en el log QUÉ trae el archivo extraído (nombres
        // del nivel superior): si un juego dice no tener OBB, aquí se ve
        // si el archivo trae .obb, carpeta <paquete>/ u otra cosa.
        val topLevel = extracted.listFiles()
            ?.sortedBy { it.name }
            ?.joinToString(", ") { (if (it.isDirectory) it.name + "/" else it.name) }
            ?.take(500) ?: "(vacío)"
        StartupLog.mark(context, "install vrp: contenido extraído: $topLevel")
        // Datos solo si hay archivos de datos de verdad (v0.1.33: no
        // solo .obb — Maestro trae .pak y Hot Dogs trae .hdog sin ningún
        // .obb). Staging = la raíz extraída ENTERA (v0.1.27): la copia
        // camina todo el árbol y cubre todos los archivos de datos,
        // estén donde estén.
        val obbStaging = if (vrp.hasGameDataFiles(extracted)) {
            extracted
        } else {
            null
        }
        if (obbStaging != null) logObbList("install vrp", extracted)
        val installTxt = File(extracted, "install.txt").takeIf { it.exists() }
        StartupLog.mark(
            context,
            "install vrp: extraído OK, APK=${apkFile.absolutePath} " +
                "(${apkFile.length()} bytes) OBB=${obbStaging != null} install.txt=${installTxt != null}"
        )
        if (obbStaging != null) {
            updateRecord(entry) {
                it.copy(
                    obbState = ObbRecordState.PENDING,
                    obbFiles = ArchiveExtractor.listDataFiles(extracted),
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
        return PreparedInstall(apkFile, obbStaging, installTxt, extracted, extractArchivesFirst = false)
    }

    /** Limpieza tras quedarse sin espacio a mitad de descarga: borra
     *  los trozos .7z.00N (y sus .part) y el .7z unido parcial, para no
     *  dejar gigabytes de basura. No borra nada de otros juegos. */
    private fun cleanupPartialDownload(dir: File, vrpHash: String?) {
        try {
            var deleted = 0L
            dir.listFiles()?.forEach { f ->
                val isPart = vrpHash != null && f.name.startsWith("$vrpHash.7z.")
                val isJoined = vrpHash != null && f.name == "$vrpHash.7z"
                val isPartFile = f.name.endsWith(".part")
                if (isPart || isJoined || isPartFile) {
                    deleted += f.length()
                    f.delete()
                }
            }
            StartupLog.mark(
                context,
                "install vrp: sin espacio — limpiados trozos parciales en ${dir.absolutePath} ($deleted bytes)"
            )
        } catch (e: Throwable) {
            StartupLog.mark(context, "install vrp: no se pudieron limpiar los trozos parciales (${e.message})")
        }
    }

    /** Instalar un APK local suelto (sin repo), con su OBB si hay una carpeta con su nombre al lado. */
    fun installLocalApk(apkFile: File, onDone: (Boolean, String) -> Unit) {
        StartupLog.mark(
            context,
            "install local: ${apkFile.name} (${apkFile.length()} bytes)"
        )
        suspend fun finishOnMain(ok: Boolean, msg: String, silent: Boolean = false) {
            val pkg = readPackageFromApk(apkFile) ?: apkFile.name
            val label = runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                info?.applicationInfo?.let { ai ->
                    ai.sourceDir = apkFile.absolutePath
                    ai.publicSourceDir = apkFile.absolutePath
                    ai.loadLabel(context.packageManager)?.toString()
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: apkFile.nameWithoutExtension
            if (!silent) {
                if (ok) {
                    InstallSuccessEvents.emit(label, msg, pkg)
                } else if (msg.isNotBlank()) {
                    InstallFailureEvents.emit(label, msg, pkg)
                }
            }
            withContext(Dispatchers.Main) {
                onDone(ok, msg)
            }
        }
        scope.launch {
            try {
                if (!installMutex.tryLock()) {
                    val other = activeInstallName ?: context.getString(R.string.install_another_game)
                    StartupLog.mark(context, "install local: rechazado, ya se está instalando $other")
                    finishOnMain(false, context.getString(R.string.install_local_busy, other), silent = true)
                    return@launch
                }
                try {
                    installLocalApkLocked(apkFile) { ok, msg ->
                        finishOnMain(ok, msg, silent = (!ok && msg == context.getString(R.string.fallback_cancelled)))
                    }
                } finally {
                    installMutex.unlock()
                }
            } catch (e: Exception) {
                finishOnMain(false, "Error: ${e.message}")
            }
        }
    }

    private suspend fun installLocalApkLocked(
        apkFile: File,
        onDone: suspend (Boolean, String) -> Unit,
    ) {
        try {
                val adb = adbReady()
                if (adb == null) {
                    val result = PackageInstallerFallback.installWithConfirmation(context, apkFile)
                    val ok = result is PackageInstallerFallback.FallbackResult.Success
                    if (ok) recordLocalInstalled(apkFile)
                    onDone(ok, fallbackMessage(result))
                    return
                }
                val res = adb.installApk(apkFile.absolutePath)
                if (!adb.installSucceeded(res)) {
                    StartupLog.mark(
                        context,
                        "install local: pm install FALLÓ: ${pmFailureDetail(res)} (code=${res.code})"
                    )
                    if (GiraAdbManager.isTransportErrorText(res.error)) {
                        val result = PackageInstallerFallback.installWithConfirmation(context, apkFile)
                        val ok = result is PackageInstallerFallback.FallbackResult.Success
                        if (ok) recordLocalInstalled(apkFile)
                        onDone(
                            ok,
                            if (ok) {
                                context.getString(R.string.install_local_fallback_ok)
                            } else {
                                context.getString(R.string.install_local_fallback_fail, fallbackMessage(result))
                            }
                        )
                        return
                    }
                    onDone(false, context.getString(R.string.install_pm_failed, pmFailureDetail(res)))
                    return
                }
                // OBB local: carpeta <nombre-apk>/ junto al APK, o <paquete>/.
                val sibling = File(apkFile.parentFile, apkFile.nameWithoutExtension)
                if (sibling.isDirectory) {
                    // Averiguar el paquete del APK instalado para el destino OBB.
                    val pkg = readPackageFromApk(apkFile)
                    if (pkg != null) {
                        // v0.1.21: directa primero (sin ADB), luego ADB.
                        val direct = obbWorkMutex.withLock { copyObbDirect(pkg, sibling) }
                        if (direct !is DirectObbResult.Ok) {
                            val copyRes = adb.copyObb(pkg, sibling.absolutePath)
                            StartupLog.mark(
                                context,
                                "install local: copia OBB de $pkg: " +
                                    if (copyRes.ok) "completada por ADB" else "FALLÓ: ${copyRes.detail.take(200)}"
                            )
                        }
                    }
                }
                // v0.1.28: registrar la versión instalada (pestaña
                // "Instaladas"); la oferta de OBB de la misma carpeta la
                // hace el llamador (LocalObbOffer) tras onDone.
                recordLocalInstalled(apkFile)
                onDone(true, context.getString(R.string.install_local_done, apkFile.name))
            } catch (e: Exception) {
                onDone(false, "Error: ${e.message}")
            }
    }

    private fun readPackageFromApk(apkFile: File): String? {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
        } catch (_: Exception) {
            null
        }
    }

    /** Paquete de un APK suelto (para la oferta de OBB y el registro). */
    fun packageNameOfApk(apkFile: File): String? = readPackageFromApk(apkFile)

    /** Registra la versión instalada de un APK local (v0.1.28): sin
     *  registro, la pestaña "Instaladas" filtra el paquete en la
     *  enumeración y no aparece aunque esté instalado. */
    private fun recordLocalInstalled(apkFile: File) {
        try {
            val pkg = readPackageFromApk(apkFile) ?: return
            @Suppress("DEPRECATION")
            val vc = try {
                context.packageManager.getPackageInfo(pkg, 0).longVersionCode
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    ?.longVersionCode ?: 0L
            }
            settings.setInstalledVersion(pkg, vc)
            StartupLog.mark(context, "install local: registrado $pkg v$vc (pestaña Instaladas)")
        } catch (_: Exception) {
        }
    }

    /** .obb que trae un APK local (v0.1.28): *.obb en su MISMA carpeta
     *  (solo nivel superior) + *.obb dentro de la carpeta <nombre-apk>/
     *  (recursivo). NO se camina toda la carpeta padre: en Descargas
     *  eso barrería todos los juegos descargados. */
    fun findLocalObbFiles(apkFile: File): List<File> {
        val out = mutableListOf<File>()
        try {
            apkFile.parentFile?.listFiles()
                ?.filter { it.isFile && it.extension.equals("obb", true) }
                ?.let { out += it }
            val sibling = File(apkFile.parentFile, apkFile.nameWithoutExtension)
            if (sibling.isDirectory) {
                sibling.walkTopDown()
                    .filter { it.isFile && it.extension.equals("obb", true) }
                    .forEach { out += it }
            }
        } catch (_: Exception) {
        }
        return out.distinctBy { it.absolutePath }.sortedBy { it.name.lowercase() }
    }

    /** Copia una lista explícita de .obb a /Android/obb/<paquete>/ con
     *  las mismas comprobaciones que copyObbDirect (permiso, espacio,
     *  mkdirs, verificación por tamaño). Para APKs locales y para el
     *  explorador de archivos.
     *
     *  v0.1.29: mismo arreglo que copyObbDirect — mkdirs no aborta,
     *  cada .obb se comprueba antes por nombre+tamaño y se copia con
     *  las mismas estrategias (directa, tras borrar, temporal+rename,
     *  SAF), y los mains viejos del mismo rol se borran. */
    suspend fun copyLocalObbFiles(packageName: String, files: List<File>): DirectObbResult =
        withContext(Dispatchers.IO) {
            obbWorkMutex.withLock {
            val granted = Environment.isExternalStorageManager()
            StartupLog.mark(
                context,
                "obb directo: MANAGE_EXTERNAL_STORAGE concedido=$granted (Android ${Build.VERSION.SDK_INT})"
            )
            if (!granted) return@withContext DirectObbResult.NotGranted
            if (files.isEmpty()) {
                return@withContext DirectObbResult.Failed("sin archivos .obb")
            }
            val infos = files.map { ObbFileInfo(it.name, it.name, it.length()) }
            StartupLog.mark(
                context,
                "obb directo: .obb locales (${infos.size}): " +
                    infos.joinToString(", ") { "${it.name} (${it.size} bytes)" }.take(1000)
            )
            val totalBytes = infos.sumOf { it.size }
            val freeBytes = freeSpaceBytes()
            if (freeBytes in 0 until totalBytes) {
                return@withContext DirectObbResult.Failed(
                    "sin espacio: los OBB ocupan ${formatSizeBytes(totalBytes)} y solo quedan " +
                        "${formatSizeBytes(freeBytes)} libres"
                )
            }
            val target = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
            val mkdirsOk = try { target.mkdirs() } catch (_: Exception) { false }
            val targetIsDir = try { target.isDirectory } catch (_: Exception) { false }
            StartupLog.mark(
                context,
                "obb directo: destino ${target.absolutePath} mkdirs=$mkdirsOk isDirectory=$targetIsDir"
            )
            var bytes = 0L
            var anyUnverified = false
            val failures = mutableListOf<String>()
            for (obb in files) {
                val dest = File(target, obb.name)
                when (val r = copyOneObb(obb, dest, packageName)) {
                    is CopyOneResult.Copied -> {
                        bytes += r.bytes
                        StartupLog.mark(
                            context,
                            "obb directo: ${obb.name} copiado OK (${r.bytes} bytes, vía ${r.via})"
                        )
                    }
                    is CopyOneResult.CopiedUnverified -> {
                        // v0.1.30: escrito sin error, sin comprobar.
                        bytes += r.bytes
                        anyUnverified = true
                        StartupLog.mark(
                            context,
                            "obb directo: ${obb.name} copiado SIN VERIFICAR (${r.bytes} bytes, vía ${r.via})"
                        )
                    }
                    is CopyOneResult.AlreadyThere -> {
                        bytes += r.bytes
                        StartupLog.mark(
                            context,
                            "obb directo: ${obb.name} ya estaba completo (${r.bytes} bytes): no se re-copia"
                        )
                    }
                    is CopyOneResult.Failed -> failures += "${obb.name}: ${r.reason}"
                }
            }
            if (failures.isNotEmpty()) {
                return@withContext DirectObbResult.Failed(failures.joinToString(" | "))
            }
            cleanupStaleObbMains(target, files.map { it.name }.toSet())
            StartupLog.mark(
                context,
                "obb directo: ${files.size} .obb en ${target.absolutePath} ($bytes bytes) SIN ADB"
            )
            DirectObbResult.Ok(infos, bytes, anyUnverified)
            }
        }

    /** Desinstalar APK+OBB+datos (como UninstallGame del PC). */
    fun uninstall(packageName: String, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val adb = adbReady()
                if (adb != null) {
                    val res = adb.uninstallGame(packageName)
                    settings.clearInstalledVersion(packageName)
                    onDone(!res.isError, res.combined.ifBlank { context.getString(R.string.uninstall_adb_done) })
                } else {
                    PackageInstallerFallback.uninstallWithConfirmation(context, packageName)
                    settings.clearInstalledVersion(packageName)
                    onDone(true, context.getString(R.string.uninstall_system_requested))
                }
            } catch (e: Exception) {
                onDone(false, "Error: ${e.message}")
            }
        }
    }

    /**
     * Instala el APK con el instalador del sistema (confirmación en
     * pantalla). El OBB NO se copia en este modo: Android 11+ no deja
     * escribir en /Android/obb/ de otra app sin ADB. Devuelve true si
     * se instaló. Usado cuando no hay ADB y cuando el transporte ADB
     * muere durante el pm install (v0.1.16).
     *
     * v0.1.19: si el juego trae OBB (obbStaging != null), el OBB ya NO
     * se pierde: queda PENDIENTE y la app lo copiará sola en cuanto el
     * ADB vuelva a estar vivo (completePendingObbs). Antes el mensaje
     * pedía al usuario re-activar la depuración y reinstalar a mano.
     *
     * v0.1.17: el motivo del fallo llega preciso (permiso, cancelado,
     * bloqueado, detalle del instalador) en vez del genérico
     * "Instalación cancelada o fallida." que lo cubría todo. Si falta
     * el permiso de "Instalar apps desconocidas", se abre Ajustes para
     * concederlo: sin él la sesión está condenada y el sistema ni
     * siquiera avisa.
     */
    /** Qué pasó con el OBB tras instalar la APK (v0.1.27). */
    sealed class ObbOutcome {
        /** El juego no trae OBB: la cadena está completa con la APK. */
        object NoObb : ObbOutcome()

        /** Copiado: lista de archivos y bytes totales. v0.1.30:
         *  unverified = la copia escribió sin error pero el tamaño
         *  del destino no se pudo comprobar (lectura denegada):
         *  "copiado, sin verificar". */
        data class Copied(
            val files: List<ObbFileInfo>,
            val bytes: Long,
            val unverified: Boolean = false,
        ) : ObbOutcome()

        /** Queda pendiente (motivo real): la UI lo muestra como EN
         *  CURSO, nunca como terminado, con botón Reintentar. */
        data class Pending(val reason: String) : ObbOutcome()

        /** Falta MANAGE_EXTERNAL_STORAGE: sin él no se puede escribir
         *  en /Android/obb. La UI debe decirlo y abrir Ajustes. */
        data class NotGranted(val files: List<ObbFileInfo>) : ObbOutcome()
    }

    /** Resultado del fallback del instalador del sistema. */
    data class FallbackOutcome(val installed: Boolean, val obb: ObbOutcome)

    /**
     * Instala el APK con el instalador del sistema (confirmación en
     * pantalla) y, si el juego trae OBB, lo copia EN EL MOMENTO con
     * I/O Java directo (v0.1.27) — no solo ADB, no solo el
     * temporizador. La copia directa tarda ~28 s para 9,68 GB en el
     * Quest (~345 MB/s, log 15:32:32) y NO necesita ADB: esperar aquí
     * es lo que hace que el estado final sea verdad en el momento.
     * Antes la app decía "instalado" y el OBB llegaba 30 s después
     * sin que nadie lo viera (BulletStorm: el usuario creyó que no se había
     * copiado nada).
     *
     * La instalación NO se da por terminada hasta que el OBB tiene un
     * resultado conocido: copiado (N archivos + bytes) o pendiente
     * con motivo. Si la APK se instaló pero el OBB queda pendiente,
     * el estado visible es "APK instalada — OBB pendiente,
     * copiando…" como EN CURSO (ObbPending, no Done), y cuando el
     * temporizador lo complete, ese mismo estado cambia a completado
     * en su sitio (tarjeta + ficha) vía ObbEvents, no con un Toast.
     *
     * v0.1.17: el motivo del fallo llega preciso (permiso, cancelado,
     * bloqueado, detalle del instalador) en vez del genérico
     * "Instalación cancelada o fallida." que lo cubría todo. Si falta
     * el permiso de "Instalar apps desconocidas", se abre Ajustes para
     * concederlo: sin él la sesión está condenada y el sistema ni
     * siquiera avisa.
     */
    private suspend fun installViaSystemFallback(
        entry: RepoEntry,
        apkFile: File,
        obbStaging: File?,
    ): FallbackOutcome {
        // v0.1.34 (EXPERIMENTAL): sesión del instalador con OBB, ANTES
        // del fallback de siempre — el sistema (installd, privilegiado)
        // coloca el OBB en /Android/obb/<paquete>/ y la app no escribe
        // nunca ahí. Solo si el juego trae datos que colocar y la sonda
        // de arranque dijo que addFile es invocable. Si la sesión no se
        // puede intentar o falla técnicamente, se sigue con el fallback
        // de siempre (instalador del sistema + copia directa). Si el
        // usuario cancela la confirmación, NO se re-pregunta: la
        // instalación termina como cancelada.
        if (obbStaging != null) {
            when (val s = InstallerSession.installWithObb(context, apkFile, entry.packageName, obbStaging)) {
                is InstallerSession.SessionResult.Success -> {
                    settings.setInstalledVersion(entry.packageName, entry.versionCode)
                    val installedAt = System.currentTimeMillis()
                    updateRecord(entry) {
                        it.copy(
                            apkInstalled = true,
                            apkInstalledAtMs = installedAt,
                            obbState = ObbRecordState.COMPLETE,
                            obbFiles = s.files,
                            obbCopiedBytes = s.bytes,
                            obbCompletedAtMs = installedAt,
                            obbUnverified = s.unverified,
                            state = InstallRecordState.COMPLETE,
                            updatedAtMs = installedAt
                        )
                    }
                    ObbEvents.emit(
                        ObbEvents.Event.Completed(
                            entry.packageName, entry.displayName, s.files.size, s.bytes
                        )
                    )
                    val unverifiedNote = if (s.unverified) context.getString(R.string.obb_system_unverified_note) else ""
                    InstallNotifier.notifyComplete(
                        context, entry.packageName, entry.versionCode, entry.displayName,
                        context.getString(
                            R.string.install_session_notif_detail,
                            formatPlacedDetail(s.files, s.bytes),
                            entry.packageName,
                            unverifiedNote
                        )
                    )
                    emit(
                        InstallProgress.Done(
                            true,
                            context.getString(R.string.install_session_complete, entry.displayName, unverifiedNote)
                        )
                    )
                    return FallbackOutcome(installed = true, obb = ObbOutcome.Copied(s.files, s.bytes, s.unverified))
                }
                is InstallerSession.SessionResult.Cancelled -> {
                    StartupLog.mark(context, "install: sesión OBB cancelada por el usuario (no se re-pregunta)")
                    updateRecord(entry) {
                        it.copy(
                            state = InstallRecordState.FAILED,
                            error = context.getString(R.string.fallback_cancelled),
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                    emit(InstallProgress.Done(false, context.getString(R.string.fallback_cancelled), silent = true))
                    return FallbackOutcome(installed = false, obb = ObbOutcome.NoObb)
                }
                is InstallerSession.SessionResult.NotAvailable -> {
                    StartupLog.mark(context, "install: sesión OBB no disponible (${s.detail}): fallback de siempre")
                }
                is InstallerSession.SessionResult.Blocked -> {
                    StartupLog.mark(context, "install: sesión OBB bloqueada por el sistema (${s.detail}): fallback de siempre")
                }
                is InstallerSession.SessionResult.Failed -> {
                    StartupLog.mark(context, "install: sesión OBB FALLÓ (${s.detail}): fallback de siempre")
                }
            }
        }
        val result = PackageInstallerFallback.installWithConfirmation(context, apkFile)
        if (result is PackageInstallerFallback.FallbackResult.PermissionMissing) {
            StartupLog.mark(context, "install: fallback sin permiso «Instalar apps desconocidas», abriendo Ajustes")
            PackageInstallerFallback.openUnknownSourcesSettings(context)
        }
        if (result !is PackageInstallerFallback.FallbackResult.Success) {
            StartupLog.mark(context, "install: fallback FALLÓ: ${fallbackMessage(result)}")
            updateRecord(entry) {
                it.copy(
                    state = InstallRecordState.FAILED,
                    error = fallbackMessage(result),
                    signatureMismatch = isSignatureMismatch(result),
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            emit(
                InstallProgress.Done(
                    false,
                    fallbackMessage(result),
                    silent = (result is PackageInstallerFallback.FallbackResult.Cancelled)
                )
            )
            return FallbackOutcome(installed = false, obb = ObbOutcome.NoObb)
        }
        settings.setInstalledVersion(entry.packageName, entry.versionCode)
        val installedAt = System.currentTimeMillis()
        updateRecord(entry) {
            it.copy(
                apkInstalled = true,
                apkInstalledAtMs = installedAt,
                updatedAtMs = installedAt
            )
        }
        if (obbStaging == null) {
            // v0.1.32: reinstalación de la APK de un juego cuyo OBB ya
            // se movió a /Android/obb/<paquete> (el staging ya no trae
            // .obb): no borrar el estado COMPLETE.
            val alreadyThere = recordObbAlreadyThere(entry.packageName)
            if (alreadyThere != null) {
                StartupLog.mark(
                    context,
                    "install: ${entry.displayName} reinstalado (APK); el OBB ya estaba en su sitio (${alreadyThere.size} .obb)"
                )
                updateRecord(entry) {
                    it.copy(
                        obbState = ObbRecordState.COMPLETE,
                        obbCopiedBytes = alreadyThere.sumOf { f -> f.size },
                        state = InstallRecordState.COMPLETE,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                InstallNotifier.notifyComplete(
                    context, entry.packageName, entry.versionCode, entry.displayName,
                    context.getString(R.string.install_notif_apk_reinstalled_obb_present)
                )
                emit(
                    InstallProgress.Done(
                        true,
                        context.getString(R.string.install_complete_reinstalled_obb_present, entry.displayName)
                    )
                )
                return FallbackOutcome(
                    installed = true,
                    obb = ObbOutcome.Copied(alreadyThere, alreadyThere.sumOf { f -> f.size })
                )
            }
            // Sin OBB: la cadena está completa con la APK.
            updateRecord(entry) {
                it.copy(
                    obbState = ObbRecordState.NO_OBB,
                    state = InstallRecordState.COMPLETE,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
            InstallNotifier.notifyComplete(
                context, entry.packageName, entry.versionCode, entry.displayName,
                context.getString(R.string.install_notif_apk_only)
            )
            emit(
                InstallProgress.Done(
                    true,
                    context.getString(R.string.install_complete_apk_only, entry.displayName)
                )
            )
            return FallbackOutcome(installed = true, obb = ObbOutcome.NoObb)
        }
        emit(InstallProgress.CopyingObb(context.getString(R.string.install_copying_obb)))
        val obbOutcome = copyObbDirectNow(entry, obbStaging)
        return when (obbOutcome) {
            is ObbOutcome.Copied -> {
                updateRecord(entry) {
                    it.copy(
                        obbState = ObbRecordState.COMPLETE,
                        obbCopiedBytes = obbOutcome.bytes,
                        obbCompletedAtMs = System.currentTimeMillis(),
                        // v0.1.30: "copiado, sin verificar" queda en el
                        // registro para que la ficha lo diga así.
                        obbUnverified = obbOutcome.unverified,
                        state = InstallRecordState.COMPLETE,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                ObbEvents.emit(
                    ObbEvents.Event.Completed(
                        entry.packageName, entry.displayName, obbOutcome.files.size, obbOutcome.bytes
                    )
                )
                val unverifiedNote =
                    if (obbOutcome.unverified) context.getString(R.string.obb_copied_unverified_note) else ""
                InstallNotifier.notifyComplete(
                    context, entry.packageName, entry.versionCode, entry.displayName,
                    "${formatPlacedDetail(obbOutcome.files, obbOutcome.bytes)} " +
                        "→ /Android/obb/${entry.packageName}/$unverifiedNote"
                )
                emit(
                    InstallProgress.Done(
                        true,
                        context.getString(
                            R.string.install_complete_obb_placed,
                            entry.displayName,
                            formatPlacedDetail(obbOutcome.files, obbOutcome.bytes),
                            entry.packageName,
                            unverifiedNote
                        )
                    )
                )
                FallbackOutcome(installed = true, obb = obbOutcome)
            }
            is ObbOutcome.NotGranted -> {
                val noPermReason = context.getString(R.string.obb_reason_no_perm)
                recordPendingObb(
                    entry, obbStaging,
                    lastError = noPermReason
                )
                updateRecord(entry) {
                    it.copy(
                        obbState = ObbRecordState.NOT_GRANTED,
                        obbError = context.getString(R.string.obb_missing_files_reason),
                        state = InstallRecordState.PENDING_OBB,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                ObbEvents.emit(
                    ObbEvents.Event.Pending(
                        entry.packageName, entry.displayName,
                        noPermReason
                    )
                )
                emit(
                    InstallProgress.ObbPending(
                        entry.packageName, entry.displayName,
                        context.getString(R.string.install_apk_done_obb_pending),
                        noPermReason
                    )
                )
                FallbackOutcome(installed = true, obb = obbOutcome)
            }
            is ObbOutcome.Pending -> {
                recordPendingObb(entry, obbStaging, lastError = obbOutcome.reason)
                updateRecord(entry) {
                    it.copy(
                        obbState = ObbRecordState.PENDING,
                        obbError = obbOutcome.reason,
                        state = InstallRecordState.PENDING_OBB,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                ObbEvents.emit(
                    ObbEvents.Event.Pending(
                        entry.packageName, entry.displayName, obbOutcome.reason
                    )
                )
                emit(
                    InstallProgress.ObbPending(
                        entry.packageName, entry.displayName,
                        context.getString(R.string.install_apk_done_obb_pending),
                        obbOutcome.reason
                    )
                )
                // Red de seguridad: el temporizador de MainActivity y
                // completePendingObbs reintentan (directo primero, luego
                // ADB). Cuando lo completen, el mismo estado cambia a
                // completado en su sitio vía ObbEvents + notificación.
                scope.launch {
                    try {
                        completePendingObbs(force = true)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        StartupLog.mark(
                            context,
                            "install: reintento de OBB en segundo plano FALLÓ: " +
                                (AdbHealth.fullChainReason(e) ?: e.toString())
                        )
                    }
                }
                FallbackOutcome(installed = true, obb = obbOutcome)
            }
            // Inalcanzable aquí (obbStaging != null), pero el when
            // debe ser exhaustivo.
            is ObbOutcome.NoObb -> FallbackOutcome(installed = true, obb = obbOutcome)
        }
    }

    /** Copia directa de todos los archivos de datos del staging y
     *  traduce el resultado a ObbOutcome.
     *
     *  v0.1.30: la copia inmediata tras instalar el APK es LA VENTANA
     *  que funcionó 2 veces (BulletStorm 15:32, Band Space 17:36) y se
     *  endurece: si falla, se reintenta 3 veces con pausas de ~3 s
     *  (por si la propiedad del directorio /Android/obb/<paquete> se
     *  asienta tras instalar) y luego pasa a SAF dentro de cada
     *  intento (copyOneObb). Cada intento queda en el log con su
     *  resultado. Sin reintento si falta espacio (no se arregla
     *  esperando) ni si falta el permiso. */
    private suspend fun copyObbDirectNow(entry: RepoEntry, obbStaging: File): ObbOutcome {
        val files = ArchiveExtractor.listDataFiles(obbStaging)
        var lastReason = ""
        repeat(3) { attempt ->
            val n = attempt + 1
            StartupLog.mark(
                context,
                "obb inmediato: intento $n/3 de copia para ${entry.packageName}"
            )
            val r = obbWorkMutex.withLock {
                copyObbDirect(entry.packageName, obbStaging)
            }
            when (r) {
                is DirectObbResult.Ok -> {
                    if (r.unverified) {
                        StartupLog.mark(
                            context,
                            "obb inmediato: intento $n/3 copiado SIN VERIFICAR"
                        )
                    }
                    return ObbOutcome.Copied(r.files, r.bytes, r.unverified)
                }
                is DirectObbResult.NotGranted -> return ObbOutcome.NotGranted(files)
                is DirectObbResult.Failed -> {
                    lastReason = r.reason
                    StartupLog.mark(
                        context,
                        "obb inmediato: intento $n/3 FALLÓ: ${r.reason}"
                    )
                    // Sin espacio no se arregla esperando: no reintentar.
                    if ("sin espacio" in r.reason) return ObbOutcome.Pending(r.reason)
                    if (n < 3) delay(3_000)
                }
            }
        }
        return ObbOutcome.Pending(lastReason)
    }

    /**
     * install.txt sin ADB (v0.1.25): traduce cada línea a I/O Java directo
     * cuando se puede (push/cp/mv/mkdir/rm, pm install por el instalador del
     * sistema); lo que necesita shell (pm/am/settings/chmod…) se omite con
     * su línea en el log y se avisa en la UI. El APK+OBB ya están instalados
     * por el fallback; esto solo añade los pasos del install.txt. El flujo
     * con ADB (paso 5) no se toca: sigue usando InstallTxtTranslator.
     */
    private suspend fun runInstallTxtNoAdb(
        entry: RepoEntry,
        installTxt: File,
        installTxtDir: File,
        apkFile: File,
        obbStaging: File?,
        obb: ObbOutcome,
    ) {
        emit(InstallProgress.InstallingApk(context.getString(R.string.install_txt_no_adb_running)))
        val executor = InstallTxtNoAdb(
            workDir = installTxtDir,
            mainApk = apkFile,
            sdcardRoot = File(Environment.getExternalStorageDirectory().absolutePath),
            apkInstaller = { apk ->
                PackageInstallerFallback.installWithConfirmation(context, apk) is
                    PackageInstallerFallback.FallbackResult.Success
            },
            log = { StartupLog.mark(context, it) },
        )
        val report = executor.run(installTxt) { step ->
            emit(InstallProgress.RunningInstallTxt(step.line))
        }
        val skippedDetail = report.skipped.joinToString("; ") { "${it.line} (${it.detail})" }
        val obbText = when (obb) {
            is ObbOutcome.NoObb -> ""
            is ObbOutcome.Copied -> {
                val unver = if (obb.unverified) context.getString(R.string.obb_copied_unverified_long_note) else ""
                context.getString(R.string.install_txt_obb_copied, obb.files.size, formatSizeBytes(obb.bytes), unver)
            }
            is ObbOutcome.NotGranted -> context.getString(R.string.install_txt_obb_not_granted)
            is ObbOutcome.Pending -> context.getString(R.string.install_txt_obb_pending, obb.reason)
        }
        val skippedNote = if (report.skipped.isNotEmpty()) {
            context.getString(R.string.install_txt_skipped_note, report.skipped.size, skippedDetail)
        } else {
            ""
        }
        val msg = context.getString(
            R.string.install_txt_no_adb_done,
            entry.displayName,
            report.done.size,
            skippedNote,
            obbText
        )
        StartupLog.mark(context, "install: install.txt sin ADB — ${report.done.size} ejecutados, ${report.skipped.size} omitidos")
        when (obb) {
            is ObbOutcome.Copied, ObbOutcome.NoObb -> emit(InstallProgress.Done(true, msg))
            is ObbOutcome.NotGranted, is ObbOutcome.Pending -> {
                val reason = when (obb) {
                    is ObbOutcome.NotGranted -> context.getString(R.string.obb_reason_no_perm)
                    is ObbOutcome.Pending -> obb.reason
                    else -> ""
                }
                emit(
                    InstallProgress.ObbPending(
                        entry.packageName, entry.displayName,
                        context.getString(R.string.install_apk_done_obb_pending),
                        reason
                    )
                )
            }
        }
    }

    /** Guarda un OBB como pendiente (se completará solo: copia directa
     *  en el temporizador, luego ADB). El staging que se guarda es la
     *  RAÍZ extraída (no una subcarpeta) y la lista completa de .obb,
     *  para que los reintentos cubran todos. */
    private fun recordPendingObb(entry: RepoEntry, obbStaging: File, lastError: String? = null) {
        val root = ArchiveExtractor.normalizeStagingRoot(obbStaging)
        settings.addPendingObb(
            PendingObb(
                packageName = entry.packageName,
                stagingDir = root.absolutePath,
                versionCode = entry.versionCode,
                gameName = entry.displayName,
                obbFiles = ArchiveExtractor.listDataFiles(root),
                lastError = lastError ?: "",
            )
        )
        StartupLog.mark(
            context,
            "install: OBB pendiente para ${entry.packageName} " +
                "(staging ${root.absolutePath}, motivo: ${lastError ?: "sin motivo"})"
        )
    }

    /** Sin reintentos de OBB pendientes más a menudo que esto (el intento
     *  con ADB muerto es caro: probe + mDNS). */
    private val obbRetryMinIntervalMs = 5 * 60_000L

    /** Resultado de la copia directa de OBB con I/O Java (sin ADB, v0.1.21). */
    /** v0.1.28: público para que la oferta de OBB local (LocalObbOffer)
     *  y el explorador lean el resultado de copyLocalObbFiles. */
    sealed class DirectObbResult {
        object NotGranted : DirectObbResult()
        data class Ok(val files: List<ObbFileInfo>, val bytes: Long, val unverified: Boolean = false) : DirectObbResult()
        data class Failed(val reason: String) : DirectObbResult()
    }

    /**
     * Copia el OBB con I/O Java directo, SIN ADB (v0.1.21). En Android
     * 11+ los gestores con MANAGE_EXTERNAL_STORAGE escriben en
     * /Android/obb, pero un audit anterior decía que en este móvil NO
     * (y el Wizard lo pide en el paso 4, así que el usuario casi seguro lo
     * tiene concedido): NO se da por hecho — se intenta, el resultado
     * queda en el log y si falla se cae a la vía ADB. Si funciona, el
     * OBB se copia aunque el ADB esté muerto (que es el estado habitual
     * de este Xiaomi: el transporte muere en casi cada comando).
     *
     * v0.1.29: la v0.1.28 abortaba si `mkdirs()` devolvía false — pero
     * mkdirs() devuelve false CUANDO EL DIRECTORIO YA EXISTE (lo
     * crea el instalador del sistema al instalar el APK) y también
     * cuando el stat está denegado aunque el directorio exista. La
     * v0.1.28 mató así la copia de VRBartender en el mismo segundo en
     * que se instaló su APK, y re-copiaba OBBs ya completos (Band
     * Space, completo desde la v0.1.27) sin comprobar el destino.
     * Ahora: mkdirs solo se LOGUEA (nunca aborta), cada .obb se
     * comprueba ANTES por nombre exacto + tamaño (sonda de lectura, no
     * stat), y si la escritura falla se prueban varias vías (directa,
     * directa tras borrar, temporal+rename, SAF) con el resultado de
     * CADA intento en el log.
     */
    private suspend fun copyObbDirect(packageName: String, stagingDir: File): DirectObbResult =
        withContext(Dispatchers.IO) {
            val granted = Environment.isExternalStorageManager()
            val installGranted = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                false
            }
            val needsRestart = GiraQuestApp.needsMountRestart(context)
            StartupLog.mark(
                context,
                "obb directo: MANAGE_EXTERNAL_STORAGE=$granted, REQUEST_INSTALL_PACKAGES=$installGranted " +
                    "(al_arrancar=${GiraQuestApp.startedWithInstallPermission}, necesita_reinicio=$needsRestart, Android ${Build.VERSION.SDK_INT})"
            )
            if (!granted) return@withContext DirectObbResult.NotGranted
            if (needsRestart) {
                StartupLog.mark(
                    context,
                    "obb directo: AVISO — el proceso arrancó sin REQUEST_INSTALL_PACKAGES (mount default, sin MOUNT_EXTERNAL_INSTALLER); hace falta reiniciar GiraQuest una vez"
                )
            }
            // v0.1.27: el staging se normaliza a la raíz extraída
            // (por si el pendiente guardado apunta a una subcarpeta de
            // una versión vieja): la copia cubre TODO el árbol.
            val stagingRoot = ArchiveExtractor.normalizeStagingRoot(stagingDir)
            // v0.1.32/v0.1.38: localizar primero la carpeta real de datos
            // (<paquete>/ o ancestro común de archivos reales de datos,
            // excluyendo .apk, .extracted_ok, release.manifest y 0 bytes).
            val folder = ArchiveExtractor.findObbFolder(stagingRoot, packageName)
            if (folder == null) {
                return@withContext DirectObbResult.Failed(
                    "el staging no trae archivos de datos reales (solo APK o metadatos)"
                )
            }
            val folderFiles = ArchiveExtractor.listDataFiles(
                folder.dir,
                insidePkgFolder = !folder.viaCommonAncestor
            )
            if (folderFiles.isEmpty()) {
                return@withContext DirectObbResult.Failed(
                    "la carpeta ${folder.dir.name} no trae archivos de datos reales"
                )
            }
            StartupLog.mark(
                context,
                "obb directo: archivos de datos (${folderFiles.size}): " +
                    folderFiles.joinToString(", ") { "${it.relativePath} (${it.size} bytes)" }.take(1000)
            )
            val folderObbCount = folderFiles.count { it.name.endsWith(".obb", ignoreCase = true) }
            StartupLog.mark(
                context,
                "obb carpeta: carpeta a colocar: ${folder.dir.absolutePath} " +
                    "(relativa: ${folder.relativePath}, profundidad ${folder.depth}, " +
                    "${folderFiles.size} archivos ($folderObbCount .obb), ${folderFiles.sumOf { it.size }} bytes)" +
                    if (folder.viaCommonAncestor) " — SIN carpeta llamada $packageName: se usa el ancestro común más profundo de los archivos de datos" else ""
            )
            when (val moved = placeObbFolderByMove(packageName, folder, folderFiles)) {
                is FolderPlaceResult.Placed -> {
                    return@withContext DirectObbResult.Ok(folderFiles, moved.bytes, moved.unverified)
                }
                is FolderPlaceResult.Failed -> StartupLog.mark(
                    context,
                    "obb carpeta: mover (renameTo) FALLÓ: ${moved.reason}"
                )
            }
            if (ObbSaf.hasTree(context)) {
                when (val saf = placeObbFolderBySaf(packageName, folder, folderFiles)) {
                    is FolderPlaceResult.Placed -> {
                        return@withContext DirectObbResult.Ok(folderFiles, saf.bytes, saf.unverified)
                    }
                    is FolderPlaceResult.Failed -> StartupLog.mark(
                        context,
                        "obb carpeta: SAF FALLÓ: ${saf.reason}"
                    )
                }
            } else {
                StartupLog.mark(
                    context,
                    "obb carpeta: SAF sin árbol concedido (concede Android/obb desde la ficha del juego)"
                )
            }
            StartupLog.mark(
                context,
                "obb carpeta: ni el movimiento ni SAF colocaron la carpeta; copia recursiva por archivos"
            )
            // v0.1.27: sin espacio, no se empieza a copiar (un archivo a
            // medias se confundiría con uno completo).
            val totalBytes = folderFiles.sumOf { it.size }
            val freeBytes = freeSpaceBytes()
            if (freeBytes in 0 until totalBytes) {
                return@withContext DirectObbResult.Failed(
                    "sin espacio: los datos ocupan ${formatSizeBytes(totalBytes)} y solo quedan " +
                        "${formatSizeBytes(freeBytes)} libres"
                )
            }
            val target = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
            // v0.1.29: mkdirs() NO decide nada. Devuelve false si el
            // directorio YA EXISTE (caso normal: lo creó el instalador
            // del sistema al instalar el APK) y también si el stat está
            // denegado. Se loguea el estado real (isDirectory/canWrite
            // + sonda de escritura) y se intenta la copia igualmente:
            // la última vía (SAF) ni siquiera necesita el directorio.
            val mkdirsOk = try { target.mkdirs() } catch (_: Exception) { false }
            val targetIsDir = try { target.isDirectory } catch (_: Exception) { false }
            val targetCanWrite = try { target.canWrite() } catch (_: Exception) { false }
            StartupLog.mark(
                context,
                "obb directo: destino ${target.absolutePath} mkdirs=$mkdirsOk isDirectory=$targetIsDir canWrite=$targetCanWrite"
            )
            if (!targetIsDir) {
                val probe = File(target, ".giraquest-probe")
                val probeOk = try {
                    probe.createNewFile() && probe.delete()
                } catch (_: Exception) {
                    false
                }
                StartupLog.mark(
                    context,
                    "obb directo: sonda de escritura en destino (crear+borrar .giraquest-probe): $probeOk"
                )
            }
            var bytes = 0L
            var anyUnverified = false
            val failures = mutableListOf<String>()
            // v0.1.33: copia RECURSIVA de TODA la carpeta (no solo
            // .obb): cada archivo a su ruta relativa dentro de
            // /Android/obb/<paquete>/, creando las subcarpetas que
            // hagan falta. Maestro: .pak incluidos. Hot Dogs: .hdog.
            for (info in folderFiles) {
                val src = File(folder.dir, info.relativePath)
                val dest = File(target, info.relativePath)
                try {
                    dest.parentFile?.mkdirs()
                } catch (_: Exception) {
                }
                val verbosePerFile = folderFiles.size <= 15
                when (val r = copyOneObb(src, dest, packageName)) {
                    is CopyOneResult.Copied -> {
                        bytes += r.bytes
                        if (verbosePerFile) {
                            StartupLog.mark(
                                context,
                                "obb directo: ${info.relativePath} copiado OK (${r.bytes} bytes, vía ${r.via})"
                            )
                        }
                    }
                    is CopyOneResult.CopiedUnverified -> {
                        bytes += r.bytes
                        anyUnverified = true
                        if (verbosePerFile) {
                            StartupLog.mark(
                                context,
                                "obb directo: ${info.relativePath} copiado SIN VERIFICAR (${r.bytes} bytes, vía ${r.via})"
                            )
                        }
                    }
                    is CopyOneResult.AlreadyThere -> {
                        bytes += r.bytes
                        if (verbosePerFile) {
                            StartupLog.mark(
                                context,
                                "obb directo: ${info.relativePath} ya estaba completo (${r.bytes} bytes): no se re-copia"
                            )
                        }
                    }
                    is CopyOneResult.Failed -> failures += "${info.relativePath}: ${r.reason}"
                }
            }
            if (failures.isNotEmpty()) {
                val joined = failures.joinToString(" | ")
                val reason = if (needsRestart) {
                    "Falta reiniciar GiraQuest 1 vez tras conceder «Instalar apps desconocidas» para activar Android/obb ($joined)"
                } else {
                    joined
                }
                return@withContext DirectObbResult.Failed(reason)
            }
            // v0.1.29: actualización — si el OBB nuevo trae otro número
            // de versión (main.398... en vez de main.397...), el viejo
            // del mismo rol se borra: nunca dos mains en la carpeta.
            cleanupStaleObbMains(target, folderFiles.map { it.name }.toSet())
            StartupLog.mark(
                context,
                "obb directo: ${folderFiles.size} archivos ($folderObbCount .obb) en ${target.absolutePath} ($bytes bytes) SIN ADB"
            )
            StartupLog.mark(
                context,
                "obb carpeta colocada vía directa: ${folderFiles.size} archivos ($folderObbCount .obb), $bytes bytes, verificación " +
                    if (anyUnverified) "SIN VERIFICAR (la escritura no dio error pero el destino no se puede leer)" else "OK"
            )
            DirectObbResult.Ok(folderFiles, bytes, anyUnverified)
        }

    /** Resultado de colocar la carpeta entera del OBB (v0.1.32). */
    private sealed class FolderPlaceResult {
        data class Placed(val bytes: Long, val unverified: Boolean) : FolderPlaceResult()
        data class Failed(val reason: String) : FolderPlaceResult()
    }

    /**
     * v0.1.32 (a): MOVER la carpeta entera a /Android/obb/<paquete>/
     * con renameTo. Mismo FS (emulado), así que es instantáneo: no se
     * copian los bytes y la carpeta destino se crea con el propio
     * movimiento (nada de mkdirs). Si el destino ya existe: vacío se
     * borra y se mueve encima; con archivos, SOLO se borra si NO trae
     * el OBB completo esperado (archivos viejos o parciales, como el
     * PC: rm -rf + mkdir -p) y se reintenta el movimiento; si trae el
     * conjunto completo esperado, o si no se puede listar, NO se toca.
     * La verificación es por ruta relativa + tamaño (sonda de lectura)
     * de cada archivo de datos de la carpeta, en su ruta relativa
     * dentro de ella (por si trae subcarpetas, como el PC). v0.1.33:
     * TODOS los archivos de datos (.obb, .pak, .hdog…), no solo los
     * .obb.
     */
    private fun placeObbFolderByMove(
        packageName: String,
        folder: ArchiveExtractor.ObbFolderResult,
        files: List<ObbFileInfo>,
    ): FolderPlaceResult {
        val dest = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        val src = folder.dir
        if (files.isEmpty()) {
            return FolderPlaceResult.Failed("la carpeta ${src.name} no trae archivos de datos")
        }
        // Regla del proyecto: nunca colocar .apk, marcadores (.extracted_ok) ni
        // metadatos/archivos vacíos (release.manifest, 0 bytes) en /Android/obb/<paquete>/.
        val containsNonData = try {
            src.walkTopDown().any {
                it.isFile && !ArchiveExtractor.isGameDataFile(it, insidePkgFolder = !folder.viaCommonAncestor)
            }
        } catch (_: Exception) {
            false
        }
        if (containsNonData) {
            return FolderPlaceResult.Failed(
                "la carpeta ${src.name} contiene .apk, marcadores o metadatos no-OBB (se colocan solo los archivos de datos reales)"
            )
        }
        val obbCount = files.count { it.name.endsWith(".obb", ignoreCase = true) }
        if (dest.exists()) {
            val existing = try { dest.listFiles() } catch (_: Exception) { null }
            when {
                existing != null && existing.isEmpty() -> {
                    val deleted = try { dest.delete() } catch (_: Exception) { false }
                    StartupLog.mark(
                        context,
                        "obb carpeta: destino ${dest.absolutePath} ya existía pero VACÍO: borrar para mover encima = $deleted"
                    )
                    if (!deleted) {
                        return FolderPlaceResult.Failed("el destino ya existe (vacío pero no se pudo borrar)")
                    }
                }
                existing == null -> return FolderPlaceResult.Failed(
                    "el destino ya existe (no se puede listar: no se toca)"
                )
                else -> {
                    // v0.1.34: si el destino existe con archivos, hay
                    // que distinguir un OBB COMPLETO (no se toca jamás)
                    // de archivos VIEJOS o PARCIALES (se borran, como el
                    // PC: rm -rf + mkdir -p). "Completo" se juzga contra
                    // el conjunto ESPERADO (los archivos de esta
                    // carpeta): todos presentes por ruta relativa y con
                    // su tamaño. Si el tamaño no se puede leer en
                    // Horizon, que el nombre esté listado cuenta como
                    // presente (conservador: mejor no borrar un OBB
                    // bueno que borrarlo por error). Si NO está el
                    // conjunto completo, el destino solo trae archivos
                    // viejos/parciales: se borra la carpeta entera y se
                    // reintenta el movimiento. NUNCA se borra un
                    // destino que no se puede listar (existing == null,
                    // arriba): ahí puede haber un OBB completo
                    // funcionando (p. ej. el de Band Space).
                    val destPaths = try {
                        dest.walkTopDown().filter { it.isFile }
                            .map { it.relativeTo(dest).path }.toSet()
                    } catch (_: Exception) {
                        null
                    }
                    val complete = destPaths != null && files.all { info ->
                        info.relativePath in destPaths &&
                            (readProbeSize(File(dest, info.relativePath)) ?: info.size) == info.size
                    }
                    if (complete) {
                        return FolderPlaceResult.Failed(
                            "el destino ya existe con ${existing.size} elementos y trae el OBB completo esperado (no se toca)"
                        )
                    }
                    val deleted = try { dest.deleteRecursively() } catch (_: Exception) { false }
                    StartupLog.mark(
                        context,
                        "obb carpeta: destino ${dest.absolutePath} existe con ${existing.size} elementos pero SIN el OBB completo esperado (solo archivos viejos/parciales): borrar para mover encima = $deleted"
                    )
                    if (!deleted) {
                        return FolderPlaceResult.Failed(
                            "el destino ya existe con archivos viejos/parciales pero no se pudo borrar"
                        )
                    }
                    // Borrado: se cae al renameTo de abajo.
                }
            }
        }
        val moved = try {
            src.renameTo(dest)
        } catch (e: Exception) {
            return FolderPlaceResult.Failed("renameTo lanzó ${e.javaClass.simpleName}: ${e.message}")
        }
        StartupLog.mark(
            context,
            "obb carpeta: renameTo ${src.absolutePath} → ${dest.absolutePath} = $moved"
        )
        if (!moved) {
            return FolderPlaceResult.Failed("renameTo devolvió false (el sistema no permite mover la carpeta a Android/obb)")
        }
        val bytes = files.sumOf { it.size }
        // Verificación: cada archivo de datos por ruta relativa +
        // tamaño (sonda de lectura), en su ruta relativa dentro de la
        // carpeta.
        val verified = files.all { info ->
            readProbeSize(File(dest, info.relativePath)) == info.size
        }
        if (verified) {
            StartupLog.mark(
                context,
                "obb carpeta colocada vía renameTo: ${files.size} archivos ($obbCount .obb), $bytes bytes, verificación OK"
            )
            return FolderPlaceResult.Placed(bytes, false)
        }
        // renameTo es atómico en el mismo FS (no hay copia a medias):
        // si el directorio se movió y los archivos están listados en
        // sus rutas relativas, la carpeta está colocada aunque la
        // lectura de tamaños esté denegada en Horizon.
        val pathsThere = try {
            val listed = dest.walkTopDown().filter { it.isFile }.map { it.relativeTo(dest).path }.toSet()
            files.all { it.relativePath in listed }
        } catch (_: Exception) {
            false
        }
        if (pathsThere) {
            StartupLog.mark(
                context,
                "obb carpeta colocada vía renameTo: ${files.size} archivos ($obbCount .obb), $bytes bytes, verificación OK (rutas listadas; tamaños ilegibles, el movimiento es atómico)"
            )
            return FolderPlaceResult.Placed(bytes, false)
        }
        StartupLog.mark(
            context,
            "obb carpeta colocada vía renameTo: ${files.size} archivos ($obbCount .obb), $bytes bytes, verificación SIN VERIFICAR (el movimiento no dio error pero el destino no se puede leer)"
        )
        return FolderPlaceResult.Placed(bytes, true)
    }

    /**
     * v0.1.32 (b): colocar la carpeta entera por SAF: crea
     * Android/obb/<paquete>/ (o la abre si ya existe) y copia dentro
     * todos los archivos de datos de la carpeta, con sus subcarpetas.
     * La verificación es la de ObbSaf (tamaño final por documento al
     * escribir). v0.1.33: TODOS los archivos de datos (.obb, .pak,
     * .hdog…); el .apk se omite en ObbSaf.copyFolderObb.
     */
    private fun placeObbFolderBySaf(
        packageName: String,
        folder: ArchiveExtractor.ObbFolderResult,
        files: List<ObbFileInfo>,
    ): FolderPlaceResult {
        val saf = ObbSaf.copyFolderObb(context, packageName, folder.dir)
        StartupLog.mark(
            context,
            "obb carpeta: SAF ${folder.dir.name} → Android/obb/$packageName: ${saf.reason} (${saf.bytes} bytes)"
        )
        if (!saf.ok) return FolderPlaceResult.Failed(saf.reason)
        val bytes = files.sumOf { it.size }
        val obbCount = files.count { it.name.endsWith(".obb", ignoreCase = true) }
        StartupLog.mark(
            context,
            "obb carpeta colocada vía SAF: ${files.size} archivos ($obbCount .obb), $bytes bytes, verificación " +
                if (saf.verified) "OK" else "SIN VERIFICAR (SAF escribió sin error pero el tamaño no se pudo comprobar)"
        )
        return FolderPlaceResult.Placed(bytes, !saf.verified)
    }

    /** Resultado de copiar UN .obb al destino. */
    private sealed class CopyOneResult {
        data class Copied(val bytes: Long, val via: String) : CopyOneResult()
        /** v0.1.30: se escribió sin error pero el tamaño del destino
         *  no se pudo comprobar por lectura (Horizon deniega la
         *  lectura): "copiado, sin verificar", no un fallo. */
        data class CopiedUnverified(val bytes: Long, val via: String) : CopyOneResult()
        data class AlreadyThere(val bytes: Long) : CopyOneResult()
        data class Failed(val reason: String) : CopyOneResult()
    }

    /** Tamaño de un archivo por lectura directa (RandomAccessFile): en
     *  Horizon, dest.exists()/length() mienten cuando el stat está
     *  denegado; abrir para leer es la única comprobación fiable.
     *  null = no se puede leer (no existe o sin permiso de lectura). */
    private fun readProbeSize(file: File): Long? = try {
        java.io.RandomAccessFile(file, "r").use { it.length() }
    } catch (_: Exception) {
        null
    }

    /** Copia UN .obb al destino con varias estrategias, cada intento con
     *  su motivo en el log. Antes de nada: si el destino YA existe con
     *  el MISMO NOMBRE y tamaño, no se re-copia (la "reparación" de la
     *  v0.1.28 re-copiaba OBBs completos, como el de Band Space). */
    private fun copyOneObb(src: File, dest: File, packageName: String): CopyOneResult {
        if (!src.isFile) {
            val present = readProbeSize(dest)
            if (present != null) {
                StartupLog.mark(
                    context,
                    "obb copia: ${src.name} ya no está en staging pero el destino tiene $present bytes: se respeta"
                )
                return CopyOneResult.AlreadyThere(present)
            }
            return CopyOneResult.Failed("archivo origen no existe (${src.name})")
        }
        val expectedSize = src.length()
        val present = readProbeSize(dest)
        if (present != null && present == expectedSize) {
            return CopyOneResult.AlreadyThere(present)
        }
        if (present != null) {
            StartupLog.mark(
                context,
                "obb copia: ${src.name} existe en destino con $present bytes (esperados $expectedSize): se sobrescribe"
            )
        }
        val attempts = mutableListOf<String>()
        // 0) v0.1.37: mover el archivo directamente con renameTo (como krxzyinstall):
        // instantáneo en el mismo almacenamiento cuando /Android/obb/<pkg> ya existe
        // y el movimiento de carpeta entera no pudo reemplazarlo.
        val movedDirect = try {
            if (!dest.exists()) src.renameTo(dest) else false
        } catch (_: Exception) {
            false
        }
        if (movedDirect) {
            val done = readProbeSize(dest)
            if (done != null && done == expectedSize) {
                return CopyOneResult.Copied(done, "renameTo archivo")
            }
            if (done == null && dest.isFile && dest.length() == expectedSize) {
                return CopyOneResult.Copied(expectedSize, "renameTo archivo")
            }
            if (done == null) {
                return CopyOneResult.CopiedUnverified(expectedSize, "renameTo archivo")
            }
            attempts += "renameTo archivo: tamaño inesperado ($done/$expectedSize)"
        }
        // 1) Directa con truncate (FileOutputStream).
        val err1 = tryCopyStream(src, dest)
        if (err1 == null) {
            val done = readProbeSize(dest)
            if (done != null && done == src.length()) {
                return CopyOneResult.Copied(done, "directa")
            }
            // v0.1.30: sin error de escritura y destino ILEGIBLE (no
            // se puede leer para comprobar) = copiado, sin verificar.
            // No es un fallo: las demás vías tampoco podrían verificarlo.
            if (done == null) {
                return CopyOneResult.CopiedUnverified(src.length(), "directa")
            }
            attempts += "directa: copia incompleta (destino=$done/${src.length()})"
        } else {
            attempts += "directa: $err1"
        }
        // 2) Borrar el destino y crear de nuevo (un destino corrupto o
        // con permisos raros puede bloquear el truncate). NUNCA borrar
        // si el origen ya no existe (otro hilo lo movió) o si el
        // destino ya tiene el tamaño completo.
        if (!src.isFile) {
            val already = readProbeSize(dest)
            if (already != null && already == expectedSize) {
                return CopyOneResult.AlreadyThere(already)
            }
            return CopyOneResult.Failed(attempts.joinToString(" | "))
        }
        if (readProbeSize(dest) == expectedSize) {
            return CopyOneResult.AlreadyThere(expectedSize)
        }
        try {
            if (dest.exists()) dest.delete()
        } catch (_: Exception) {
        }
        val err2 = tryCopyStream(src, dest)
        if (err2 == null) {
            val done = readProbeSize(dest)
            if (done != null && done == src.length()) {
                return CopyOneResult.Copied(done, "directa tras borrar")
            }
            if (done == null) {
                return CopyOneResult.CopiedUnverified(src.length(), "directa tras borrar")
            }
            attempts += "directa tras borrar: copia incompleta (destino=$done/${src.length()})"
        } else {
            attempts += "directa tras borrar: $err2"
        }
        // 3) Temporal en el mismo directorio + rename (atómico en el
        // mismo FS; a veces pasa donde el truncate no).
        val tmp = File(dest.parentFile, ".giraquest-tmp-${dest.name}")
        try {
            tmp.delete()
        } catch (_: Exception) {
        }
        val err3 = tryCopyStream(src, tmp)
        if (err3 == null) {
            val tmpSize = readProbeSize(tmp)
            if (tmpSize != null && tmpSize == src.length()) {
                val renamed = try { tmp.renameTo(dest) } catch (_: Exception) { false }
                val done = readProbeSize(dest)
                if (renamed && done != null && done == src.length()) {
                    return CopyOneResult.Copied(done, "temporal+rename")
                }
                // v0.1.30: renombrado sin error pero destino ilegible
                // para comprobar = copiado, sin verificar.
                if (renamed && done == null) {
                    return CopyOneResult.CopiedUnverified(src.length(), "temporal+rename")
                }
                attempts += "temporal+rename: rename=$renamed, destino=${done ?: "ilegible"}"
            } else {
                attempts += "temporal: copia incompleta (${tmpSize ?: "ilegible"}/${src.length()})"
            }
        } else {
            attempts += "temporal: $err3"
        }
        try {
            tmp.delete()
        } catch (_: Exception) {
        }
        // 4) SAF (árbol Android/obb concedido): última vía.
        if (ObbSaf.hasTree(context)) {
            val saf = ObbSaf.copyObb(context, packageName, src)
            StartupLog.mark(context, "obb copia: ${src.name} vía SAF: ${saf.reason}")
            if (saf.ok) {
                return if (saf.verified) CopyOneResult.Copied(saf.bytes, "SAF")
                else CopyOneResult.CopiedUnverified(saf.bytes, "SAF")
            }
            attempts += "SAF: ${saf.reason}"
        } else {
            attempts += "SAF: sin árbol concedido (concede Android/obb desde la ficha del juego)"
        }
        StartupLog.mark(context, "obb copia: ${src.name} FALLÓ — ${attempts.joinToString(" | ")}")
        return CopyOneResult.Failed(attempts.joinToString(" | "))
    }

    /** Copia por streams; null si no hubo error (la verificación de
     *  tamaño la hace quien llama con readProbeSize). */
    private fun tryCopyStream(src: File, dest: File): String? {
        return try {
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(1024 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            }
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Tras una actualización, borra los .obb viejos del MISMO rol
     *  (main/patch/overflow) con otro número de versión: nunca dos
     *  mains en la carpeta. Solo borra si el OBB nuevo trae ese rol;
     *  si la actualización no trae main, el viejo se queda. */
    private fun cleanupStaleObbMains(target: File, keepNames: Set<String>) {
        val obbNameRegex =
            Regex("^(main|patch|overflow)\\.(\\d+)\\.(.+)\\.obb$", RegexOption.IGNORE_CASE)
        val keepRoles = keepNames.mapNotNull {
            obbNameRegex.matchEntire(it)?.groupValues?.getOrNull(1)?.lowercase()
        }.toSet()
        if (keepRoles.isEmpty()) return
        val existing = try {
            target.listFiles()?.filter { it.isFile } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (f in existing) {
            if (f.name in keepNames) continue
            val role = obbNameRegex.matchEntire(f.name)?.groupValues?.getOrNull(1)?.lowercase()
                ?: continue
            if (role in keepRoles) {
                val deleted = try { f.delete() } catch (_: Exception) { false }
                StartupLog.mark(
                    context,
                    "obb directo: OBB viejo ${f.name} (${if (deleted) "borrado" else "NO se pudo borrar"}) — sustituido por la nueva versión"
                )
            }
        }
    }

    /**
     * ¿El OBB ya está en su sitio, comprobado por File API con TAMAÑOS?
     * Un .obb parcial (copia muerta a mitad) NO cuenta: la v0.1.20 solo
     * miraba si había algún .obb y un parcial hacía creer que estaba
     * completo y lo quitaba de pendientes.
     *
     * v0.1.29: la comprobación usa la sonda de lectura (RandomAccessFile),
     * no exists()/length(): en Horizon el stat puede estar denegado
     * aunque el archivo esté completo (Band Space estaba copiado desde
     * la v0.1.27 y la v0.1.28 no lo veía).
     */
    private fun obbAlreadyThereDirect(packageName: String, stagingDir: File): Boolean {
        if (!Environment.isExternalStorageManager()) return false
        val infos = try {
            ArchiveExtractor.listDataFiles(stagingDir)
        } catch (_: Exception) {
            return false
        }
        return infos.isNotEmpty() && obbAlreadyThereByInfos(packageName, infos)
    }

    /** v0.1.32: ¿los .obb ESPERADOS (lista guardada en el pendiente o en
     *  el registro, no el staging) están en /Android/obb/<paquete>/ con
     *  el tamaño exacto? Sonda de lectura por archivo (no stat): en
     *  Horizon el stat puede mentir. Se comprueba por nombre y, si no
     *  se puede leer, por ruta relativa dentro de la carpeta (carpeta
     *  movida entera con subcarpetas). Es el check del destino cuando
     *  el staging ya no trae los .obb (la carpeta se movió). */
    private fun obbAlreadyThereByInfos(packageName: String, infos: List<ObbFileInfo>): Boolean {
        if (infos.isEmpty()) return false
        val target = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        return infos.all { info ->
            val size = readProbeSize(File(target, info.name))
                ?: if (info.relativePath.contains(File.separator)) {
                    readProbeSize(File(target, info.relativePath))
                } else {
                    null
                }
            size == info.size
        }
    }

    /** v0.1.32: ¿el OBB esperado según el REGISTRO sigue en su sitio?
     *  Para reinstalaciones de la APK tras un movimiento de carpeta: el
     *  staging ya no trae .obb pero el destino sí. null si no hay nada
     *  que comprobar o el destino no está completo. */
    private fun recordObbAlreadyThere(packageName: String): List<ObbFileInfo>? {
        val expected = settings.getInstallRecord(packageName)?.obbFiles.orEmpty()
        if (expected.isEmpty()) return null
        return if (obbAlreadyThereByInfos(packageName, expected)) expected else null
    }

    /** Parsea la salida de `stat -c '%n %s' ...` a nombre -> tamaño. */
    private fun parseStatSizes(output: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for (raw in output.lines()) {
            val line = raw.trim()
            val idx = line.lastIndexOf(' ')
            if (idx <= 0) continue
            val size = line.substring(idx + 1).toLongOrNull() ?: continue
            out[line.substring(0, idx).substringAfterLast('/')] = size
        }
        return out
    }

    /**
     * Completa los OBBs pendientes (v0.1.19): los registrados al instalar
     * por fallback o al fallar la copia, MÁS los juegos ya instalados
     * cuya carpeta de descargas aún tiene el OBB extraído (reparación:
     * The Walking Dead y Pistol Whip quedaron instalados solo con la
     * APK en la v0.1.18, con el OBB en disco y /Android/obb/<paquete>/
     * vacío). Todo automático, sin que el usuario haga nada.
     *
     * v0.1.21: orden de vías — 1) copia DIRECTA con I/O Java (sin ADB,
     * sin throttle: si el permiso está concedido, el OBB se copia aunque
     * el ADB esté muerto); 2) vía ADB (desacoplada + un solo comando),
     * con throttle de 5 min. La ventana de 5 min solo cuenta si hubo un
     * intento REAL de copia: en la v0.1.20 se marcaba al tener ADB vivo
     * y el intento de las 04:34:13 la consumió aunque murió en el mkdir
     * por transporte. Devuelve los nombres de los juegos cuyo OBB se
     * completó ahora.
     */
    /** Marca un OBB pendiente como completado en el registro
     *  persistente, emite el evento para que la UI (tarjeta, ficha,
     *  Instalados) cambie EN SU SITIO a completado, y avisa con una
     *  notificación de sistema (v0.1.27). Es el camino que faltaba:
     *  antes el temporizador solo dejaba un Toast que el usuario no veía. */
    private fun markObbCompleted(
        pkg: String,
        gameName: String,
        versionCode: Long,
        files: List<ObbFileInfo>,
        bytes: Long,
        unverified: Boolean = false,
    ) {
        updateRecordByPackage(pkg, gameName, versionCode) {
            it.copy(
                obbState = ObbRecordState.COMPLETE,
                obbFiles = files,
                obbCopiedBytes = bytes,
                obbError = "",
                obbCompletedAtMs = System.currentTimeMillis(),
                obbUnverified = unverified,
                state = InstallRecordState.COMPLETE,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        ObbEvents.emit(ObbEvents.Event.Completed(pkg, gameName, files.size, bytes))
        // v0.1.33: nombre para mostrar = el del registro si lo hay (el
        // de la tienda, no el paquete: el escaneo por disco solo conoce
        // el paquete). La notificación sale UNA VEZ por (paquete,
        // versión): InstallNotifier anota la clave y no repite.
        val displayName = settings.getInstallRecord(pkg)?.gameName
            ?.takeIf { it.isNotBlank() && it != pkg }
            ?: gameName
        InstallNotifier.notifyComplete(
            context, pkg, versionCode, displayName,
            "${formatPlacedDetail(files, bytes)} → /Android/obb/$pkg/"
        )
    }

    /** Actualiza el estado del OBB en el registro persistente de un
     *  juego (pendiente / sin permiso / fallido) con su motivo, para
     *  que la ficha y la pestaña Instalados lo enseñen con Reintentar. */
    private fun markObbPending(pkg: String, gameName: String, versionCode: Long, state: ObbRecordState, reason: String) {
        updateRecordByPackage(pkg, gameName, versionCode) {
            it.copy(
                obbState = state,
                obbError = reason,
                state = InstallRecordState.PENDING_OBB,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        ObbEvents.emit(ObbEvents.Event.Pending(pkg, gameName, reason))
    }

    suspend fun completePendingObbs(force: Boolean = false): List<String> {
        if (force) {
            obbWorkMutex.lock()
        } else if (!obbWorkMutex.tryLock()) {
            StartupLog.mark(context, "obb pendientes: ya hay una copia/completación en curso")
            return emptyList()
        }
        try {
            // Candidatos: pendientes guardados + reparación por disco.
            val candidates = linkedMapOf<String, PendingObb>()
            settings.pendingObbs.forEach { candidates[it.packageName] = it }
            for (p in scanInstalledGamesWithStagingObb()) {
                candidates.putIfAbsent(p.packageName, p)
            }
            if (candidates.isEmpty()) return emptyList()
            val completed = mutableListOf<String>()

            // 1) Copia DIRECTA (Java, sin ADB): no necesita throttle ni
            // ADB. Si el permiso está concedido y el FUSE lo permite, el
            // OBB se copia aunque el ADB esté muerto.
            val remaining = mutableListOf<Pair<String, PendingObb>>()
            for ((pkg, pending) in candidates) {
                // v0.1.39: nunca interferir con una instalación activa del
                // mismo paquete (evita la carrera entre onResume/timer e
                // installViaSystemFallback).
                if (isInstalling(pkg)) {
                    StartupLog.mark(context, "obb pendientes: $pkg está instalándose ahora mismo, se omite")
                    continue
                }
                // v0.1.29: tras 2 fallos seguidos con el mismo motivo, el
                // temporizador NO reintenta solo (la v0.1.28 reintentaba
                // un EACCES cada 60 s eternamente y llenaba el log):
                // queda en fallido visible con Reintentar manual. El
                // reintento manual (force=true) pone el contador a 0.
                if (!force && pending.failedAttempts >= 2) {
                    StartupLog.mark(
                        context,
                        "obb pendientes: $pkg suspendido tras ${pending.failedAttempts} fallos seguidos (${pending.lastError.take(120)}): no se reintenta solo; Reintentar manual o conceder Android/obb por SAF"
                    )
                    continue
                }
                // v0.1.27: el staging se normaliza a la raíz extraída
                // (un pendiente viejo puede apuntar a una subcarpeta).
                // v0.1.33: hay datos si hay archivos de datos (no solo
                // .obb): Hot Dogs trae .hdog sin ningún .obb.
                val staging = ArchiveExtractor.normalizeStagingRoot(File(pending.stagingDir))
                val stagingOk = staging.exists() && VrpPublicRepo(context).hasGameDataFiles(staging)
                if (!stagingOk) {
                    // v0.1.32: el staging puede estar VACÍO de archivos
                    // de datos porque la carpeta se MOVIÓ a
                    // /Android/obb/<paquete> (renameTo): comprobar el
                    // DESTINO primero con la lista guardada en el
                    // pendiente — no darlo por perdido ni intentar
                    // copiar de un staging vacío.
                    if (pending.obbFiles.isNotEmpty() &&
                        obbAlreadyThereByInfos(pkg, pending.obbFiles)
                    ) {
                        StartupLog.mark(
                            context,
                            "obb pendientes: $pkg ya tiene los datos en su sitio (la carpeta se movió; el staging ya no trae los archivos)"
                        )
                        settings.removePendingObb(pkg)
                        markObbCompleted(
                            pkg, pending.gameName, pending.versionCode,
                            pending.obbFiles, pending.obbFiles.sumOf { it.size }
                        )
                        completed.add(pending.gameName)
                    } else {
                        StartupLog.mark(
                            context,
                            "obb pendientes: $pkg sin staging en disco (destino comprobado: OBB no completo), se quita de pendientes"
                        )
                        settings.removePendingObb(pkg)
                    }
                    continue
                }
                if (obbAlreadyThereDirect(pkg, staging)) {
                    StartupLog.mark(context, "obb pendientes: $pkg ya tiene los datos en su sitio (tamaños correctos)")
                    settings.removePendingObb(pkg)
                    val files = ArchiveExtractor.listDataFiles(staging)
                    markObbCompleted(pkg, pending.gameName, pending.versionCode, files, files.sumOf { it.size })
                    completed.add(pending.gameName)
                    continue
                }
                when (val direct = copyObbDirect(pkg, staging)) {
                    is DirectObbResult.Ok -> {
                        StartupLog.mark(
                            context,
                            "obb pendientes: datos de $pkg completados por copia DIRECTA " +
                                "(${direct.files.size} archivos (${direct.files.count { it.name.endsWith(".obb", true) }} .obb), ${direct.bytes} bytes, sin ADB)" +
                                if (direct.unverified) " (copiado, sin verificar)" else ""
                        )
                        settings.removePendingObb(pkg)
                        settings.setInstalledVersion(pkg, pending.versionCode)
                        markObbCompleted(pkg, pending.gameName, pending.versionCode, direct.files, direct.bytes, direct.unverified)
                        completed.add(pending.gameName)
                    }
                    is DirectObbResult.NotGranted -> {
                        // v0.1.29: sin ADB en este dispositivo no hay
                        // otra vía automática: se dice así, no "se
                        // intentará por ADB" (falso en el Quest).
                        StartupLog.mark(
                            context,
                            "obb pendientes: $pkg sin copia directa (MANAGE_EXTERNAL_STORAGE no concedido) — sin ADB no hay otra vía automática; concede el permiso o pulsa Reintentar"
                        )
                        markObbPending(
                            pkg, pending.gameName, pending.versionCode, ObbRecordState.NOT_GRANTED,
                            "falta el permiso de acceso a todos los archivos"
                        )
                        remaining.add(pkg to pending)
                    }
                    is DirectObbResult.Failed -> {
                        val attempts = pending.failedAttempts + 1
                        val suspended = attempts >= 2
                        StartupLog.mark(
                            context,
                            "obb pendientes: copia directa de $pkg FALLÓ (${direct.reason.take(200)}) — fallo $attempts/2" +
                                if (suspended) " (suspendido: no se reintentará solo)" else " (sin ADB no hay otra vía automática)"
                        )
                        markObbPending(
                            pkg, pending.gameName, pending.versionCode,
                            if (suspended) ObbRecordState.FAILED else ObbRecordState.PENDING,
                            direct.reason
                        )
                        settings.addPendingObb(
                            pending.copy(lastError = direct.reason, failedAttempts = attempts)
                        )
                        if (!suspended) remaining.add(pkg to pending)
                    }
                }
            }
            if (remaining.isEmpty()) return completed

            // 2) Throttle (solo para la vía ADB, que es cara).
            if (!force &&
                System.currentTimeMillis() - settings.lastObbRetryMs < obbRetryMinIntervalMs
            ) {
                StartupLog.mark(
                    context,
                    "obb pendientes: ${remaining.size} pendientes, reintento ADB hace poco, esperando (la copia directa ya se intentó)"
                )
                return completed
            }
            val adb = adbReady()
            if (adb == null) {
                StartupLog.mark(
                    context,
                    "obb pendientes: ${remaining.size} pendientes (${remaining.joinToString(", ") { it.first }}), sin ADB — quedan para la próxima (sin consumir el reintento)"
                )
                return completed
            }
            // 3) Vía ADB. La ventana de 5 min solo cuenta si hubo un
            // intento REAL de copia (v0.1.21): en la v0.1.20 se marcaba
            // al tener ADB vivo y el intento de las 04:34:13 la consumió
            // aunque murió en el mkdir por transporte.
            var anyAttempted = false
            for ((pkg, pending) in remaining) {
                val staging = File(pending.stagingDir)
                val stagingObbs = try {
                    staging.walkTopDown()
                        .filter { it.isFile && it.extension.equals("obb", true) }
                        .associate { it.name to it.length() }
                } catch (_: Exception) {
                    emptyMap()
                }
                // ¿El OBB ya está en su sitio? Con TAMAÑOS (v0.1.21): un
                // .obb parcial de una copia muerta a mitad NO cuenta.
                val statRes = adb.execQuick("stat -c '%n %s' \"/sdcard/Android/obb/$pkg\"/*.obb 2>/dev/null")
                val landed = if (statRes.isError) emptyMap() else parseStatSizes(statRes.output)
                if (stagingObbs.isNotEmpty() && stagingObbs.all { (n, s) -> landed[n] == s }) {
                    StartupLog.mark(context, "obb pendientes: $pkg ya tiene el OBB en su sitio (tamaños correctos)")
                    settings.removePendingObb(pkg)
                    continue
                }
                StartupLog.mark(
                    context,
                    "obb pendientes: copiando OBB de $pkg desde ${staging.absolutePath}"
                )
                val copyRes = adb.copyObb(pkg, staging.absolutePath)
                if (copyRes.attempted) anyAttempted = true
                if (copyRes.ok) {
                    StartupLog.mark(context, "obb pendientes: OBB de $pkg completado por ADB")
                    settings.removePendingObb(pkg)
                    settings.setInstalledVersion(pkg, pending.versionCode)
                    val files = ArchiveExtractor.listDataFiles(staging)
                    markObbCompleted(pkg, pending.gameName, pending.versionCode, files, files.sumOf { it.size })
                    completed.add(pending.gameName)
                } else {
                    val reason = copyRes.detail.take(300)
                    val attempts = pending.failedAttempts + 1
                    StartupLog.mark(
                        context,
                        "obb pendientes: copiar OBB de $pkg FALLÓ: ${reason.take(200)} — fallo $attempts/2, sigue pendiente"
                    )
                    markObbPending(
                        pkg, pending.gameName, pending.versionCode,
                        if (attempts >= 2) ObbRecordState.FAILED else ObbRecordState.PENDING,
                        reason
                    )
                    settings.addPendingObb(
                        pending.copy(lastError = reason, failedAttempts = attempts)
                    )
                }
            }
            if (anyAttempted) {
                settings.lastObbRetryMs = System.currentTimeMillis()
            } else {
                StartupLog.mark(
                    context,
                    "obb pendientes: ningún intento real de copia (el transporte murió antes de arrancar) — sin consumir el reintento"
                )
            }
            return completed
        } finally {
            obbWorkMutex.unlock()
        }
    }


    /**
     * Reparación (v0.1.19): juegos INSTALADOS cuya carpeta de descargas
     * aún tiene el OBB extraído (game_extracted con .obb). La carpeta de
     * descargas se llama como el paquete, así que no hace falta la lista
     * del repo: basta con escanear Descargas/GiraQuest.
     *
     * v0.1.29: SOLO se repara si la versión instalada coincide con la
     * del staging. Sin esta guarda, un juego instalado con OTRA firma
     * (Maestro, versión de la tienda) entraba como candidato aunque su
     * APK del repo nunca se instaló, y su OBB se reintentaba en bucle.
     */
    private fun scanInstalledGamesWithStagingObb(): List<PendingObb> {
        val out = mutableListOf<PendingObb>()
        val base = downloadsBase()
        val children = try {
            base.listFiles() ?: return out
        } catch (_: Exception) {
            return out
        }
        for (child in children) {
            if (!child.isDirectory) continue
            val pkg = child.name
            if (isInstalling(pkg)) continue
            if (!repoManager.isInstalled(pkg)) continue
            val installedVc = repoManager.installedVersionCode(pkg)
            val stagedVc = stagedVersionCode(child, pkg)
            if (stagedVc != null && stagedVc != installedVc) {
                StartupLog.mark(
                    context,
                    "obb pendientes: reparación omite $pkg (instalado v$installedVc, el staging es v$stagedVc: otra firma u otra versión)"
                )
                continue
            }
            val extracted = File(child, "game_extracted")
            if (!extracted.isDirectory) continue
            val vrp = VrpPublicRepo(context)
            if (!vrp.hasGameDataFiles(extracted)) continue
            // v0.1.33: un juego YA COMPLETO (misma versión, archivos
            // verificados en su sitio) NO entra como candidato: si no,
            // cada minuto el temporizador lo "re-completaba" y
            // notificaba de nuevo (la v0.1.32 avisó ~30 veces por
            // juego). Si el OBB se borró después, la verificación falla
            // y la reparación sigue entrando.
            val rec = settings.getInstallRecord(pkg)
            if (rec?.obbState == ObbRecordState.COMPLETE && rec.versionCode == installedVc &&
                rec.obbFiles.isNotEmpty() && obbAlreadyThereByInfos(pkg, rec.obbFiles)
            ) {
                if (verifiedCompleteLogged.add(pkg)) {
                    StartupLog.mark(
                        context,
                        "obb pendientes: reparación omite $pkg (datos ya completos y verificados en su sitio)"
                    )
                }
                continue
            }
            // v0.1.27: staging = la raíz extraída ENTERA (no una sola
            // subcarpeta), con la lista completa de archivos de datos.
            out.add(
                PendingObb(
                    packageName = pkg,
                    stagingDir = extracted.absolutePath,
                    versionCode = installedVc,
                    gameName = pkg,
                    obbFiles = ArchiveExtractor.listDataFiles(extracted),
                )
            )
        }
        if (out.isNotEmpty()) {
            StartupLog.mark(
                context,
                "obb pendientes: reparación encontró ${out.size} instalados con datos en disco: " +
                    out.joinToString(", ") { it.packageName }
            )
        }
        return out
    }

    /** versionCode del APK descargado (app.apk) o, si no está, del
     *  nombre del .obb principal (main.<vc>.<paquete>.obb). null si no
     *  se puede determinar: entonces no se puede verificar y la
     *  reparación lo añade igual (la copia verifica por nombre+tamaño
     *  y el tope de 2 fallos evita el bucle infinito). */
    private fun stagedVersionCode(downloadDir: File, packageName: String): Long? {
        val apk = File(downloadDir, "app.apk")
        if (apk.isFile && apk.length() > 0) {
            @Suppress("DEPRECATION")
            val info = try {
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            } catch (_: Exception) {
                null
            }
            if (info != null) return info.versionCode.toLong()
        }
        val extracted = File(downloadDir, "game_extracted")
        val obbVc = try {
            extracted.walkTopDown()
                .filter { it.isFile && it.extension.equals("obb", true) }
                .mapNotNull {
                    Regex("^main\\.(\\d+)\\.", RegexOption.IGNORE_CASE)
                        .find(it.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
                }.firstOrNull()
        } catch (_: Exception) {
            null
        }
        return obbVc
    }

    /** ¿El instalador rechazó el APK porque ya hay una versión
     *  instalada con OTRA FIRMA (INSTALL_FAILED_UPDATE_INCOMPATIBLE)?
     *  Es el caso de Maestro (log 18:51:30): la versión de la tienda
     *  (u otra fuente) bloquea la del repo. La salida es desinstalar
     *  la versión actual, nunca reintentar el OBB en bucle. */
    private fun isSignatureMismatch(result: PackageInstallerFallback.FallbackResult): Boolean =
        result is PackageInstallerFallback.FallbackResult.Failed &&
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in result.detail

    /** Mensaje preciso en pantalla para cada motivo del fallback. */
    private fun fallbackMessage(
        result: PackageInstallerFallback.FallbackResult,
    ): String = when (result) {
        is PackageInstallerFallback.FallbackResult.Success ->
            context.getString(R.string.fallback_success)
        is PackageInstallerFallback.FallbackResult.PermissionMissing ->
            context.getString(R.string.fallback_perm_missing)
        is PackageInstallerFallback.FallbackResult.Cancelled ->
            context.getString(R.string.fallback_cancelled)
        is PackageInstallerFallback.FallbackResult.Blocked ->
            context.getString(R.string.fallback_blocked)
        is PackageInstallerFallback.FallbackResult.Failed ->
            if ("INSTALL_FAILED_UPDATE_INCOMPATIBLE" in result.detail) {
                context.getString(R.string.uninstall_signature)
            } else {
                context.getString(R.string.fallback_failed, result.detail)
            }
    }

    /**
     * Detalle de un pm install fallido: la salida de pm si la hay; si
     * el transporte murió sin dejar salida, decirlo en vez de enseñar
     * un "pm install falló: " vacío (v0.1.17).
     */
    private fun pmFailureDetail(res: GiraAdbManager.ShellResult): String {
        val combined = res.combined.take(300).trim()
        if (combined.isNotBlank()) return combined
        val err = res.error.trim()
        if (err.isNotBlank()) return err
        return "sin salida (el transporte ADB se cerró durante la instalación)"
    }

    /**
     * Devuelve el gestor ADB conectado, o null si no hay ADB. Solo se
     * llama durante una instalación/desinstalación (acción del usuario),
     * nunca al arrancar.
     *
     * v0.1.16: verificar vida REAL antes de instalar. El flag `connected`
     * queda obsoleto si el socket muere durante la descarga+extracción
     * del juego (minutos, GBs, móvil dormido, cambio de WiFi, adbd
     * reiniciado, HyperOS matando el socket): ensureLive() hace un probe
     * `echo ok` y reconecta si hace falta. La vía tomada y el endpoint
     * quedan en el StartupLog.
     *
     * v0.1.24: ACOTADO. En la reinstalación del usuario (log 10:23:40) el
     * flujo hizo 3 intentos de conexión en ~150 ms (connectSaved falla
     * en 0 ms → mDNS → connectTo OK → echo muere → reintento → connect)
     * y luego SILENCIO 107 s: el reintento del echo se quedó colgado en
     * la lectura bloqueante de readShellStream (arreglado en v0.1.24
     * con hilo+join) y adbReady() nunca devolvió ni cayó al fallback.
     * Ahora: como mucho 2 intentos de conexión (ensureLive + un SOLO
     * redescubrimiento mDNS — nunca uno por ciclo), tope total de
     * 60 s comprobado antes de cada fase, y línea explícita de rendición
     * con intentos y tiempo ("…tras N intentos (Xms), fallback
     * instalador del sistema"). La reinstalación desde disco se comporta
     * igual que la instalación fresca: pm install si hay ADB, fallback
     * si no, nunca bucle silencioso.
     */
    private suspend fun adbReady(): GiraAdbManager? {
        val started = System.currentTimeMillis()
        var attempts = 0
        fun elapsedMs() = System.currentTimeMillis() - started
        fun giveUp(reason: String): GiraAdbManager? {
            StartupLog.mark(
                context,
                "install: ADB no disponible tras $attempts intentos de conexión (${elapsedMs()}ms): $reason — fallback instalador del sistema"
            )
            return null
        }
        val adb = adbOrNull() ?: run {
            StartupLog.mark(context, "install: adbReady sin gestor (GiraAdbManager null)")
            return null
        }
        if (adb.authRejected) {
            StartupLog.mark(
                context,
                "install: adbReady con clave rechazada (${adb.authRejectedReason ?: "?"}); sin intentos"
            )
            return null
        }
        // Salida rápida (<5 ms): si no hay emparejamiento guardado ni modo TCP
        // autorizado y la depuración inalámbrica está apagada en Settings.Global,
        // no gastar 20 s en connectTls/mDNS antes de abrir el instalador del sistema.
        if (!adb.isTcpMode &&
            !settings.adbPaired &&
            settings.adbConnectPort <= 0 &&
            !adb.isWirelessDebuggingOnDirect()
        ) {
            StartupLog.mark(
                context,
                "install: depuración inalámbrica apagada y sin emparejar — fallback directo (${elapsedMs()}ms)"
            )
            return null
        }
        // Intento 1: endpoint guardado (con probe real).
        attempts++
        if (adb.ensureLive()) {
            StartupLog.mark(context, "install: ADB vivo (${adb.describeEndpoint()}) [intento $attempts, ${elapsedMs()}ms]")
            return adb
        }
        // v0.1.25: re-activación DIRECTA (solo modo TLS y solo si el
        // permiso WRITE_SECURE_SETTINGS está concedido). Si la
        // depuración se apagó (sueño o reinicio del visor), el connect
        // falla en 0 ms y el mDNS no encuentra nada: escribir
        // adb_wifi_enabled=1 en el propio proceso (sin ADB — es la
        // salida del círculo vicioso del enable por shell) y reintentar
        // el connect una vez tras 2 s (el adbd tarda un poco en
        // levantarse). Sin el permiso concedido no se intenta. En modo
        // TCP no aplica (el interruptor "Wireless ADB" es otro).
        if (!adb.isTcpMode && settings.writeSecureSettingsGranted) {
            attempts++
            StartupLog.mark(context, "install: ADB no vivo, intentando re-activar la depuración directa (adb_wifi_enabled=1)… [intento $attempts, ${elapsedMs()}ms]")
            val reactivated = try {
                adb.enableWirelessDebuggingDirect()
            } catch (_: Throwable) {
                false
            }
            if (reactivated) {
                delay(2_000)
                if (adb.ensureLive()) {
                    StartupLog.mark(context, "install: ADB vivo tras re-activar la depuración directa (${adb.describeEndpoint()}) [intento $attempts, ${elapsedMs()}ms]")
                    return adb
                }
                StartupLog.mark(context, "install: depuración re-activada pero el connect sigue sin responder [${elapsedMs()}ms]")
            }
        }
        // Tope total antes de redescubrir por mDNS: un solo
        // redescubrimiento por llamada a adbReady(), nunca en bucle.
        if (elapsedMs() > ADB_READY_TIMEOUT_MS) {
            return giveUp("tope total de ${ADB_READY_TIMEOUT_MS / 1000}s superado antes de redescubrir por mDNS")
        }
        // Intento 2: descubrir el servicio de conexión por mDNS y conectar.
        // v0.1.18: con desenlace SIEMPRE en el log y tope duro de 30 s.
        // En el log del usuario (02:27) "buscando por mDNS…" fue la última
        // línea: la búsqueda no dejaba rastro de su resultado y el
        // connect() a un endpoint obsoleto (libadb: timeout infinito por
        // defecto) podía colgar la instalación sin más salida. Ahora el
        // connect lleva su propio timeout (GiraAdbManager) y aquí queda
        // registrado si no hay servicio, si lo hay y si conecta o no.
        // v0.1.25: en modo TCP 5555 el servicio es `_adb._tcp` y la
        // conexión es connectTcp (sin pairing TLS); en modo TLS sigue
        // siendo `_adb-tls-connect` + connectTo.
        StartupLog.mark(context, "install: ADB no vivo, buscando por mDNS… [intento $attempts, ${elapsedMs()}ms]")
        val tcpMode = adb.isTcpMode
        val ep = try {
            withTimeout(15_000) {
                if (tcpMode) AdbPortDiscovery.discoverTcp(context, 10_000)
                else AdbPortDiscovery.discoverConnect(context, 10_000)
            }
        } catch (e: TimeoutCancellationException) {
            StartupLog.mark(context, "install: mDNS TIMEOUT (15s) sin responder")
            null
        }
        if (ep == null) {
            StartupLog.mark(
                context,
                "install: mDNS sin servicio " +
                    (if (tcpMode) "_adb._tcp" else "_adb-tls-connect") + " en 10s " +
                    "(depuración inalámbrica apagada, sin WiFi, o el visor no anuncia el servicio)"
            )
            return giveUp("mDNS sin servicio de conexión")
        }
        attempts++
        StartupLog.mark(context, "install: mDNS encontró ${ep.host}:${ep.port}, conectando… [intento $attempts, ${elapsedMs()}ms]")
        adb.disconnect()
        val connected = if (tcpMode) adb.connectTcp(ep.host, ep.port) else adb.connectTo(ep.host, ep.port)
        if (connected && adb.probe()) {
            StartupLog.mark(context, "install: ADB conectado por mDNS ${ep.host}:${ep.port} [intento $attempts, ${elapsedMs()}ms]")
            return adb
        }
        StartupLog.mark(context, "install: mDNS conectó pero el probe falla (${ep.host}:${ep.port}) [${elapsedMs()}ms]")
        return giveUp("el probe falló tras conectar por mDNS")
    }

    /** Ruta de descargas base, para la vista local. */
    fun downloadsBase(): File =
        File(Environment.getExternalStorageDirectory(), settings.downloadFolder)
}
