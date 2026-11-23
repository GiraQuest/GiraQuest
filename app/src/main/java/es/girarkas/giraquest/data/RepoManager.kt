package es.girarkas.giraquest.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import es.girarkas.giraquest.HttpClients
import es.girarkas.giraquest.adb.AdbHealth
import es.girarkas.giraquest.crash.StartupLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * RepoManager (equivale a SideloaderRCLONE.cs del PC, pero con repo.json
 * HTTP propio en vez de meta.7z + rclone + API key).
 *
 * - Varios repos por URL, añadidos/quitaos por el usuario.
 * - Caché local del último repo.json descargado (funciona offline con la
 *   última lista conocida).
 * - Estado de instalación por entrada, como el PC: Instalado, Actualización
 *   disponible, Descargado, Por delante de la lista.
 */
class RepoManager(private val context: Context) {

    private val settings = SettingsStore(context)
    // v0.1.22: TLS de la plataforma (ver HttpClients).
    private val client = HttpClients.applyPlatformSsl(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
    ).build()

    /**
     * Último error al cargar el repositorio público (v0.1.9). Antes el
     * fallo se tragaba aquí y la biblioteca solo decía "comprueba la
     * conexión": ahora el motivo exacto (URL, HTTP, causa) queda aquí y
     * la UI lo enseña. Null si la última carga fue bien.
     */
    @Volatile
    var lastPublicError: String? = null
        private set

    private val cacheDir: File get() = File(context.cacheDir, "repos").apply { mkdirs() }

    /** Descarga y parsea un repo.json. Lanza excepción si la URL no es un repo válido. */
    suspend fun fetchRepo(url: String): Repo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", VrpPublicRepo.BROWSER_UA)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} en $url")
            val body = resp.body?.string() ?: throw IllegalStateException("Repo vacío: $url")
            val repo = parseRepo(url, body)
            // Guardar caché para uso offline.
            File(cacheDir, url.hashCode().toString() + ".json").writeText(body)
            repo
        }
    }

    /** Lee el repo desde caché (offline). Null si nunca se descargó. */
    fun cachedRepo(url: String): Repo? {
        val f = File(cacheDir, url.hashCode().toString() + ".json")
        if (!f.exists()) return null
        return try {
            parseRepo(url, f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun parseRepo(url: String, json: String): Repo {
        val root = JSONObject(json)
        val name = root.optString("name", url)
        val version = root.optInt("version", 1)
        // Carpeta de carátulas del repo (`.meta/thumbnails` del PC).
        val thumbnailsUrl = root.optString("thumbnailsUrl").ifBlank { null }
        val apps = root.optJSONArray("apps") ?: throw IllegalStateException("repo.json sin \"apps\"")
        val entries = mutableListOf<RepoEntry>()
        for (i in 0 until apps.length()) {
            RepoEntry.fromJson(apps.getJSONObject(i), url, name)?.let { entry ->
                // La URL base de thumbnails del repo vale para todos sus juegos.
                entries.add(entry.copy(thumbnailsBaseUrl = thumbnailsUrl ?: entry.thumbnailsBaseUrl))
            }
        }
        return Repo(url, name, version, entries, thumbnailsUrl)
    }

    /** Sin repositorio incrustado: el usuario añade su propio repositorio. */
    private suspend fun loadPublicRepo(@Suppress("UNUSED_PARAMETER") forcePublicRefresh: Boolean): Repo? = null

    /** Todos los repos añadidos por el usuario (red primero, caché si falla). */
    suspend fun allRepos(forcePublicRefresh: Boolean = false): List<Repo> {
        val out = mutableListOf<Repo>()
        lastPublicError = null
        for (url in settings.repoUrls) {
            val repo = try {
                fetchRepo(url)
            } catch (e: Exception) {
                lastPublicError = "${url}: ${AdbHealth.fullChainReason(e) ?: e.message}"
                cachedRepo(url)
            }
            repo?.let { out.add(it) }
        }
        for (custom in settings.customVrpRepos) {
            val vrp = VrpPublicRepo(context, custom)
            val repo = try {
                vrp.fetchMeta(forcePublicRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = AdbHealth.fullChainReason(e) ?: e.toString()
                lastPublicError = "${custom.displayName}: $reason"
                StartupLog.mark(context, "custom vrp (${custom.baseUri}) FALLÓ: $reason")
                vrp.cachedMeta()
            }
            repo?.let { out.add(it) }
        }
        return out
    }

    /** Cliente VrpPublicRepo correcto para una entrada (público o personalizado). */
    fun vrpClientFor(entry: RepoEntry): VrpPublicRepo {
        val custom = settings.customVrpRepos.firstOrNull {
            it.repoUrl.equals(entry.repoUrl, ignoreCase = true) ||
                entry.apkUrl.startsWith(it.normalizedBaseUri + "/", ignoreCase = true)
        }
        return VrpPublicRepo(context, custom)
    }

    /** Parsea entrada en bruto de repositorio (como vrp-public.json, URL+pass en
     *  líneas o separados por espacio/|, o URL de repo.json) y lo valida + guarda. */
    suspend fun addRawRepo(rawInput: String): Repo = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("Entrada vacía")

        // 1) Bloque JSON en bruto: {"baseUri":"...","password":"...","apiKey":"..."}
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val baseUri = listOf("baseUri", "baseUrl", "url", "uri", "address", "direccion")
                .firstNotNullOfOrNull { k -> obj.optString(k).trim().takeIf { it.isNotBlank() } }
                ?: throw IllegalArgumentException("El JSON no contiene baseUri / url")
            val password = listOf("password", "pass", "pw", "pwd")
                .firstNotNullOfOrNull { k -> if (obj.has(k)) obj.optString(k, "") else null }
                ?: ""
            val apiKey = listOf("apiKey", "api_key", "x-api-key", "key")
                .firstNotNullOfOrNull { k -> obj.optString(k).trim().takeIf { it.isNotBlank() } }
                ?: ""
            val name = obj.optString("name", "").trim()
            val custom = CustomVrpRepo(baseUri = baseUri, password = password, name = name, apiKey = apiKey)
            val vrp = VrpPublicRepo(context, custom)
            val repo = vrp.fetchMeta(force = true)
            settings.addCustomVrpRepo(custom)
            return@withContext repo
        }

        // 2) Texto con URL + contraseña (en 2 líneas, o "url pass", o "url|pass", o "baseUri=... password=...")
        val urlMatch = Regex("""https?://[^\s"'<>|;]+""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: throw IllegalArgumentException("No se encontró una dirección http:// o https:// válida")
        val foundUrl = urlMatch.value.trimEnd(',', '"', '\'')
        val remainder = (trimmed.substring(0, urlMatch.range.first) + " " +
            trimmed.substring(urlMatch.range.last + 1))
            .replace(Regex("""(?i)\b(baseuri|baseurl|url|uri|password|pass|pw|pwd)\s*[:=]\s*"""), " ")
            .replace("|", " ")
            .replace(";", " ")
            .trim()
            .lines()
            .map { it.trim().trim('"', '\'') }
            .firstOrNull { it.isNotBlank() }
            ?: ""

        if (remainder.isNotBlank()) {
            val custom = CustomVrpRepo(baseUri = foundUrl, password = remainder)
            val vrp = VrpPublicRepo(context, custom)
            val repo = vrp.fetchMeta(force = true)
            settings.addCustomVrpRepo(custom)
            return@withContext repo
        }

        // 3) Solo URL: comprobar si es un repo.json, un vrp-public.json remoto o una raíz VRP sin contraseña
        if (foundUrl.endsWith(".json", ignoreCase = true)) {
            val req = Request.Builder().url(foundUrl)
                .header("User-Agent", VrpPublicRepo.BROWSER_UA)
                .header("Accept", "*/*")
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} en $foundUrl")
                resp.body?.string() ?: throw IllegalStateException("Repo vacío: $foundUrl")
            }
            val root = JSONObject(body)
            if (root.has("apps")) {
                val repo = parseRepo(foundUrl, body)
                File(cacheDir, foundUrl.hashCode().toString() + ".json").writeText(body)
                addRepo(foundUrl)
                return@withContext repo
            }
            val baseUri = listOf("baseUri", "baseUrl", "url", "uri")
                .firstNotNullOfOrNull { k -> root.optString(k).trim().takeIf { it.isNotBlank() } }
            if (baseUri != null) {
                val pw = root.optString("password", "")
                val name = root.optString("name", "").trim()
                val apiKey = listOf("apiKey", "api_key", "x-api-key", "key")
                    .firstNotNullOfOrNull { k -> root.optString(k).trim().takeIf { it.isNotBlank() } }
                    ?: ""
                val custom = CustomVrpRepo(baseUri = baseUri, password = pw, name = name, apiKey = apiKey)
                val repo = VrpPublicRepo(context, custom).fetchMeta(force = true)
                settings.addCustomVrpRepo(custom)
                return@withContext repo
            }
            throw IllegalStateException("El JSON no contiene \"apps\" ni \"baseUri\"")
        }

        // URL base VRP sin contraseña explícita (o probar repo.json si falla)
        val custom = CustomVrpRepo(baseUri = foundUrl, password = "")
        return@withContext try {
            val repo = VrpPublicRepo(context, custom).fetchMeta(force = true)
            settings.addCustomVrpRepo(custom)
            repo
        } catch (vrpErr: Exception) {
            try {
                val repo = fetchRepo(foundUrl)
                addRepo(foundUrl)
                repo
            } catch (_: Exception) {
                throw vrpErr
            }
        }
    }

    fun removeCustomVrpRepo(baseUri: String) {
        val custom = settings.customVrpRepos.firstOrNull {
            it.normalizedBaseUri.equals(baseUri.trim().trimEnd('/'), ignoreCase = true)
        }
        if (custom != null) {
            VrpPublicRepo(context, custom).clearCustomCache()
            settings.removeCustomVrpRepo(custom.normalizedBaseUri)
        }
    }

    /**
     * Versión base de un juego (GalleryView.cs:1781 del PC): cuando un
     * paquete tiene varias versiones, se pre-selecciona la del nombre más
     * corto (el juego base) para mostrar su carátula/tráiler.
     */
    fun baseEntry(entries: List<RepoEntry>, packageName: String): RepoEntry? =
        entries.filter { it.packageName == packageName }
            .minByOrNull { it.displayName.length }

    /** Todas las entradas de todos los repos (primero intenta red, si falla usa caché). */
    suspend fun allEntries(forcePublicRefresh: Boolean = false): List<RepoEntry> =
        allRepos(forcePublicRefresh).flatMap { it.entries }

    fun addRepo(url: String) {
        val list = settings.repoUrls.toMutableList()
        val clean = url.trim()
        if (clean.isNotBlank() && clean !in list) {
            list.add(clean)
            settings.repoUrls = list
        }
    }

    fun removeRepo(url: String) {
        settings.repoUrls = settings.repoUrls - url
        File(cacheDir, url.hashCode().toString() + ".json").delete()
    }

    /** Mapa instantáneo de paquetes instalados -> versionCode (1 llamada IPC
     *  para toda la biblioteca en lugar de miles en cada tecla de búsqueda). */
    fun installedVersionsSnapshot(): Map<String, Long> {
        return try {
            @Suppress("DEPRECATION")
            val list = context.packageManager.getInstalledPackages(0)
            val map = HashMap<String, Long>(list.size)
            for (p in list) {
                map[p.packageName] = p.longVersionCode
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** versionCode instalado según PackageManager, -1 si no está instalado.
     *  v0.1.28: se captura Exception (no solo NameNotFoundException):
     *  getPackageInfo puede lanzar otras (p. ej. paquetes no visibles
     *  sin QUERY_ALL_PACKAGES) y un solo juego no puede tumbar la lista. */
    fun installedVersionCode(packageName: String): Long {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(packageName, 0)
            info.longVersionCode
        } catch (_: Exception) {
            -1
        }
    }

    fun isInstalled(packageName: String): Boolean = installedVersionCode(packageName) >= 0

    private fun isMetaInstallerPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg.startsWith("com.oculus.", ignoreCase = true) ||
            pkg.startsWith("com.meta.", ignoreCase = true)
    }

    /** Determina si un paquete instalado procede de la tienda oficial de Meta,
     *  de GiraQuest o de otro instalador/sideload externo. */
    fun installOriginOf(packageName: String, preverifiedInfo: PackageInfo? = null): InstallOrigin {
        val pm = context.packageManager
        val pkgInfo = preverifiedInfo ?: try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            return InstallOrigin.NONE
        }
        val source = try {
            pm.getInstallSourceInfo(packageName)
        } catch (_: Exception) {
            null
        }
        val installing = source?.installingPackageName
        val initiating = source?.initiatingPackageName
        val originating = source?.originatingPackageName

        // 1) Tienda oficial de Meta (Horizon Store / OCMS) siempre gana sobre registros antiguos.
        if (isMetaInstallerPackage(installing) ||
            isMetaInstallerPackage(initiating) ||
            isMetaInstallerPackage(originating)
        ) {
            return InstallOrigin.META_STORE
        }

        // 2) Instalado desde GiraQuest (instalador propio o registro ADB/installRecords).
        val selfPkg = context.packageName
        if (installing == selfPkg || initiating == selfPkg || originating == selfPkg) {
            return InstallOrigin.GIRAQUEST
        }
        if (settings.getInstalledVersion(packageName) >= 0 ||
            settings.getInstallRecord(packageName)?.apkInstalled == true
        ) {
            return InstallOrigin.GIRAQUEST
        }

        // 3) Apps preinstaladas de sistema (Horizon OS / Meta).
        val flags = pkgInfo.applicationInfo?.flags ?: 0
        if ((flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        ) {
            return InstallOrigin.META_STORE
        }

        return InstallOrigin.OTHER
    }

    /** Mapa paquete -> origen de instalación, solo para los paquetes instalados. */
    fun installedOriginsSnapshot(installedPackages: Set<String>): Map<String, InstallOrigin> {
        if (installedPackages.isEmpty()) return emptyMap()
        val pm = context.packageManager
        val out = HashMap<String, InstallOrigin>(installedPackages.size * 2)
        for (pkg in installedPackages) {
            val info = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            } catch (_: Exception) {
                null
            }
            if (info != null) {
                out[pkg] = installOriginOf(pkg, info)
            }
        }
        return out
    }

    /** Conjunto de nombres de carpeta existentes en Download/GiraQuest/.
     *  Una sola llamada al FS en vez de 12.000 stats en cada filtro. */
    fun downloadedPackageDirsSnapshot(): Set<String> {
        val base = File(
            android.os.Environment.getExternalStorageDirectory(),
            settings.downloadFolder
        )
        val children = try {
            base.listFiles()
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        val set = HashSet<String>(children.size)
        for (c in children) {
            if (c.isDirectory) set.add(c.name)
        }
        return set
    }

    fun stateOf(
        entry: RepoEntry,
        installedVersions: Map<String, Long>? = null,
        downloadedDirs: Set<String>? = null,
    ): InstallState {
        val installed = installedVersions?.get(entry.packageName)
            ?: if (installedVersions != null) -1L else installedVersionCode(entry.packageName)
        if (installed < 0) {
            val dl = if (downloadedDirs != null && entry.packageName !in downloadedDirs) false
            else isDownloaded(entry)
            return if (dl) InstallState.DOWNLOADED else InstallState.NOT_INSTALLED
        }
        return when {
            installed == entry.versionCode -> InstallState.INSTALLED
            installed < entry.versionCode -> InstallState.UPDATE_AVAILABLE
            else -> InstallState.AHEAD_OF_LIST
        }
    }

    fun isDownloaded(entry: RepoEntry): Boolean {
        val dir = downloadDirFor(entry)
        val apk = File(dir, "app.apk")
        if (apk.exists() && apk.length() > 0) return true
        val extractedOk = File(dir, "game_extracted/.extracted_ok")
        if (extractedOk.exists()) return true
        val hash = entry.vrpHash
        if (!hash.isNullOrBlank()) {
            val joined = File(dir, "$hash.7z")
            if (joined.exists() && joined.length() > 0 && !File(dir, "$hash.7z.part").exists()) {
                return true
            }
            val p1 = File(dir, "$hash.7z.001")
            if (p1.exists() && p1.length() > 0 && dir.listFiles()?.none { it.name.endsWith(".part") } == true) {
                return true
            }
        }
        return false
    }

    /**
     * Carpeta de descargas de una entrada:
     * /sdcard/Download/GiraQuest/<paquete>/.
     *
     * v0.1.16: cálculo PURO, sin crear nada. La v0.1.8–v0.1.15 hacía
     * mkdirs() AQUÍ, y como isDownloaded()/stateOf() se llaman para
     * CADA juego al listar la biblioteca (RepoFragment.applyFilter
     * calcula el estado de los 3019 juegos del índice), abrir la
     * biblioteca creaba una carpeta por juego (3019 carpetas com.* en
     * Descargas) sin descargar nada. Las carpetas salían vacías salvo
     * la del juego que sí se descargó.
     */
    fun downloadDirFor(entry: RepoEntry): File {
        val base = File(
            android.os.Environment.getExternalStorageDirectory(),
            settings.downloadFolder
        )
        return File(base, entry.packageName)
    }

    /** Como downloadDirFor, pero creando la carpeta (solo al descargar). */
    fun ensureDownloadDir(entry: RepoEntry): File = downloadDirFor(entry).apply { mkdirs() }

    /**
     * Borra las carpetas de descarga por juego que están VACÍAS (restos
     * de la v0.1.8–v0.1.15: carpetas creadas solo por listar la
     * biblioteca). Solo borra hijos directos de la carpeta de descargas
     * cuyo árbol esté vacío; un juego descargado (app.apk, trozos .7z,
     * game_extracted) nunca está vacío y se conserva. Devuelve cuántas
     * borró.
     */
    fun cleanupEmptyDownloadDirs(): Int {
        val base = File(
            android.os.Environment.getExternalStorageDirectory(),
            settings.downloadFolder
        )
        val children = try {
            base.listFiles() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        var deleted = 0
        for (child in children) {
            if (!child.isDirectory) continue
            try {
                if (isTreeEmpty(child) && child.deleteRecursively()) deleted++
            } catch (_: Exception) {
            }
        }
        return deleted
    }

    private fun isTreeEmpty(dir: File): Boolean {
        val kids = try {
            dir.listFiles() ?: return true
        } catch (_: Exception) {
            return false
        }
        return kids.all { it.isDirectory && isTreeEmpty(it) }
    }

    companion object {
        private val MN_REGEX = Regex("\\p{Mn}+")
        private val WS_REGEX = Regex("\\s+")
    }

    private fun normalizeSearch(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return MN_REGEX.replace(decomposed, "").lowercase()
    }

    /** Búsqueda + filtros, como MainForm.cs del PC. El orden se aplica
     *  DENTRO del filtro actual (y de la búsqueda). Por defecto el orden
     *  del índice (no A–Z: la v0.1.26 ordenaba siempre A–Z y el orden
     *  del índice se perdía). */
    fun filter(
        entries: List<RepoEntry>,
        query: String,
        filter: Filter,
        sort: SortOrder = SortOrder.INDEX,
        newPackages: Set<String> = emptySet(),
        sizeByKey: Map<String, Long> = emptyMap(),
        sortSizeAsc: Boolean = true,
        installedVersions: Map<String, Long>? = null,
        downloadedDirs: Set<String>? = null,
    ): List<RepoEntry> {
        val tokens = normalizeSearch(query.trim())
            .split(WS_REGEX)
            .filter { it.isNotEmpty() }
        val installed = installedVersions ?: installedVersionsSnapshot()
        val favs = if (filter == Filter.FAVORITES) settings.favorites else emptySet()
        val dirs = downloadedDirs ?: if (filter == Filter.DOWNLOADED) downloadedPackageDirsSnapshot() else null
        val filtered = entries.filter { e ->
            val matchesQuery = tokens.isEmpty() || run {
                val haystack = normalizeSearch("${e.name} ${e.releaseName} ${e.packageName}")
                tokens.all { haystack.contains(it) }
            }
            if (!matchesQuery) return@filter false
            when (filter) {
                Filter.ALL -> true
                Filter.INSTALLED -> installed.containsKey(e.packageName)
                Filter.UPDATE_AVAILABLE -> stateOf(e, installed, dirs) == InstallState.UPDATE_AVAILABLE
                Filter.DOWNLOADED -> (dirs == null || e.packageName in dirs) && isDownloaded(e)
                Filter.FAVORITES -> e.packageName in favs
                Filter.AHEAD -> stateOf(e, installed, dirs) == InstallState.AHEAD_OF_LIST
            }
        }
        // Posición en el índice (una sola vez: indexOf por comparación
        // sería O(n²) con 3.018 juegos).
        val indexByEntry = entries.withIndex().associate { it.value to it.index }
        val indexOf: (RepoEntry) -> Int = { indexByEntry[it] ?: Int.MAX_VALUE }
        return when (sort) {
            SortOrder.INDEX -> filtered
            SortOrder.POPULAR -> filtered.sortedByDescending { it.downloads }
            SortOrder.AZ -> filtered.sortedBy { it.displayName.lowercase() }
            SortOrder.NEW -> filtered.sortedWith(
                compareByDescending<RepoEntry> { it.packageName in newPackages }
                    .thenBy { indexOf(it) }
            )
            // Tamaño (v0.1.40): usa tamaño en disco (si está descargado y en Descargados),
            // tamaño exacto cacheado por HEAD o tamaño del índice (MB -> bytes).
            // Los de tamaño desconocido (<= 0) siempre quedan al final.
            SortOrder.SIZE -> {
                fun effectiveSize(e: RepoEntry): Long {
                    val override = sizeByKey["${e.packageName}|${e.versionCode}"]
                        ?: sizeByKey[e.packageName]
                    return if (override != null && override > 0L) override else e.apkSize
                }
                val (known, unknown) = filtered.partition { effectiveSize(it) > 0L }
                val sortedKnown = if (sortSizeAsc) {
                    known.sortedWith(compareBy<RepoEntry> { effectiveSize(it) }.thenBy { indexOf(it) })
                } else {
                    known.sortedWith(compareByDescending<RepoEntry> { effectiveSize(it) }.thenBy { indexOf(it) })
                }
                sortedKnown + unknown
            }
        }
    }

    enum class Filter { ALL, INSTALLED, UPDATE_AVAILABLE, DOWNLOADED, FAVORITES, AHEAD }

    /** Orden de la lista (v0.1.27, se recuerda entre arranques). */
    enum class SortOrder { INDEX, POPULAR, AZ, NEW, SIZE }
}
