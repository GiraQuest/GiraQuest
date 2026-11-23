package es.girarkas.giraquest.data

import android.content.Context
import es.girarkas.giraquest.BuildConfig
import es.girarkas.giraquest.HttpClients
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.download.ArchiveExtractor
import es.girarkas.giraquest.download.DownloadResult
import es.girarkas.giraquest.download.Downloader
import es.girarkas.giraquest.download.NativeSevenZip
import es.girarkas.giraquest.download.SevenZipBinary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Cliente del repositorio público de VRP de PC (el de vrp-public.json).
 *
 * Protocolo exacto del PC (verificado en pc-vrp/, cliente PC VRP 3.4):
 *
 *  - Config: `vrp-public.json` = {baseUri, password}. En el PC la contraseña
 *    va en Base64 y el getter la decodifica; aquí se aceptan las dos formas
 *    (ver decodePassword).
 *  - Índice: GET `<baseUri>/meta.7z` por HTTP directo, SIN autenticación
 *    HTTP (el PC lo baja con rclone `:http:` + `--http-url <baseUri>`; la
 *    contraseña NO es de HTTP). Se extrae con la contraseña (7z AES) y
 *    dentro hay `*ameList.txt` en la raíz y `.meta/` con `thumbnails`,
 *    `notes` y `nouns` (RCLONE.cs: UpdateMetadataFromPublic,
 *    ProcessMetadataFromPublic).
 *  - Lista de juegos: texto con `;`, cabecera en la primera línea, columnas
 *    0 nombre, 1 release, 2 paquete, 3 versionCode, 4 ruta APK (no se usa
 *    en el mirror público), 5 versionName, 6 descargas, 7 versión instalada
 *    (RCLONE.cs: GameNameIndex..InstalledVersion).
 *  - Cada juego se descarga de `<baseUri>/<hash>/`, donde hash = MD5 hex
 *    del **NOMBRE DE RELEASE** (columna 1, NO el nombre de la columna 0)
 *    + "\n" en UTF-8 (MainForm.cs: la selección usa ReleaseNameIndex
 *    (4136), la cola guarda el releaseName (4355) y el hash se calcula
 *    sobre ese gameName (4553-4562). La v0.1.8–v0.1.14 usaban cols[0] y
 *    el servidor devolvía 404 en `<baseUri>/<hash>/` para TODOS los
 *    juegos (v0.1.15 lo corrige).
 *  - Carátulas: `.meta/thumbnails/<PackageName>.jpg|.png` (PC: UpdateGamePhotos
 *    + GetCachedImage). Notas: `.meta/notes/<ReleaseName>.txt` (PC: la ficha
 *    carga notes/<release>.txt como descripción). Blacklist: `.meta/nouns/
 *    blacklist.txt` (PC: los juegos de esa lista no se muestran).
 *
 * En Quest no hay rclone: todo es HTTP directo con OkHttp. La
 * extracción de los 7z cifrados la hace el binario oficial 7zzs
 * (SevenZipBinary, v0.1.12) porque commons-compress (SevenZFile con
 * contraseña; LZMA vía org.tukaani:xz) NO abre el meta.7z real del
 * servidor con ningún candidato: en el móvil de Edu falla con
 * "Compressed data is corrupt" al leer los datos. commons-compress
 * queda como fallback si el binario no se puede ejecutar.
 *
 * v0.1.11 — imitación del cliente del PC al milímetro: el WAF del
 * mirror bloquea en DURO a Chrome, OkHttp y WebView (captura de Edu en
 * Chrome real: "Sorry, you have been blocked", sin challenge ni casilla)
 * y solo deja pasar al rclone del PC en el mismo WiFi. Las peticiones
 * al mirror envían `User-Agent: rclone/v1.72.1` (versión fijada en
 * GetDependencies.cs:93), sin Accept ni Accept-Language (Go no los
 * envía), con X-API-Key y la cookie cf_clearance si la hay. El UA del
 * WebView ya no se envía nunca. Si ni así pasa, el siguiente paso es
 * la huella TLS (JA3): Go vs BoringSSL, con un ConnectionSpec de
 * OkHttp que imite el orden de cifrados de Go (ver checkpoint-013).
 *
 * v0.1.9 — lo aprendido del fallo real de Edu (biblioteca vacía, mensaje
 * genérico) y de la investigación posterior:
 *  - La contraseña del JSON se decodifica de Base64 EXACTAMENTE como el PC
 *    (PublicConfig.cs: Encoding.UTF8.GetString(Convert.FromBase64String)).
 *    La v0.1.8 usaba el valor en claro porque el Base64 decodificado "no
 *    era imprimible" — pero el PC usa justo ese valor decodificado (con el
 *    carácter de reemplazo U+FFFD que .NET pone al decodificar UTF-8
 *    inválido). Con la contraseña en claro la extracción del 7z falla
 *    siempre (checksum). Se prueba una lista de candidatos en orden.
 *  - El PC envía además la cabecera X-API-Key en TODAS las peticiones al
 *    mirror (rclone --header, MainForm.cs GetPublicMirrorArgs; la clave va
 *    incrustada en el binario oficial, no está en el código fuente). La app
 *    la envía igual (BuildConfig.VRP_API_KEY, inyectada en el build manual
 *    desde .api-key; en Gradle va vacía). Sin ella, el servidor puede
 *    devolver 403 aunque la IP sea válida.
 *  - Cloudflare bloquea esta VM (403) con CUALQUIER cliente y cabecera,
 *    incluso con la clave real: el bloqueo aquí es por IP de datacenter
 *    (y/o huella TLS). En el móvil de Edu (misma red que su PC, donde el
 *    VRP de PC sí llega) la descarga debería funcionar; si no, el error
 *    exacto queda ahora en pantalla y en el StartupLog.
 */
class VrpPublicRepo(
    private val context: Context,
    private val customRepo: CustomVrpRepo? = null,
) {

    private val effectiveRepoUrl: String get() = customRepo?.repoUrl ?: PUBLIC_URL
    private val effectiveRepoName: String get() = customRepo?.displayName ?: PUBLIC_NAME

    companion object {
        /** URL ficticia que identifica este repo en RepoEntry.repoUrl. */
        const val PUBLIC_URL = "vrp://public"
        const val PUBLIC_NAME = "Repositorio público"
        const val META_FILE = "meta.7z"
        const val META_CACHE = "meta.json"

        /** User-Agent de navegador: algunos WAF bloquean UAs de librería. */
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * User-Agent EXACTO de rclone, el cliente del PC (v0.1.11).
         * Fuente: `Sideloader/GetDependencies.cs:93` de VRP 3.4:
         * `wantedRcloneVersion = "1.72.1"` (lo descarga de
         * downloads.rclone.org y lo impone en cada arranque salvo
         * --no-rclone-update). VRP no pasa --user-agent, así que
         * rclone usa su UA por defecto, formato `rclone/v<versión>`,
         * verificado en el binario de dep/ (cadena de formato
         * "User-Agent: %s"). El WAF del mirror bloquea en DURO a Chrome,
         * OkHttp y WebView (captura de Edu: "Sorry, you have been
         * blocked", sin challenge ni casilla) y solo deja pasar al
         * rclone del PC en el mismo WiFi: la hipótesis es que el WAF
         * solo permite al cliente rclone (por UA y/o huella TLS de Go).
         * Por eso TODAS las peticiones al mirror imitan al PC.
         */
        const val RCLONE_UA = "rclone/v1.72.1"

        /** La caché del índice vale 60 min; pasado ese tiempo se re-descarga. */
        const val META_CACHE_MAX_AGE_MS = 60 * 60 * 1000L

        /**
         * v0.1.18: serializa TODAS las fetchMeta. VrpPublicRepo se
         * instancia nuevo en cada llamada (RepoManager, Installer…), así
         * que el Mutex es de companion (estático): sin él, dos fetchMeta
         * concurrentes escriben el MISMO meta.7z a la vez y lo corrompen
         * ("nextHeaderOffset is out of bounds" — 3ª vez: 02:56:37, con
         * fetchMeta solapadas a las 02:56:16/37/38/50). La llamada que
         * espera re-comprueba la caché al entrar: si la primera ya
         * refrescó, usa su resultado sin volver a descargar.
         */
        private val fetchMutex = Mutex()

        /** ¿Es este error un 403 de Cloudflare en el mirror? */
        fun isCloudflare403(error: String?): Boolean =
            error != null && "HTTP 403" in error

        /**
         * Una fila de la lista de juegos (sin Android, testeable en JVM).
         * Columnas del *ameList.txt del PC (RCLONE.cs: GameNameIndex=0,
         * ReleaseNameIndex=1, PackageNameIndex=2, VersionCodeIndex=3,
         * ReleaseAPKPathIndex=4, VersionNameIndex=5, DownloadsIndex=6).
         *
         * v0.1.29: la columna 6 es una PUNTUACIÓN DECIMAL, no un contador
         * entero: el PC la lee con StringUtilities.TryParseDouble
         * (MainForm.cs:2737, NumberStyles.Any, InvariantCulture) y solo
         * clasifica puntuaciones > 0 ("0.00 popularity or not found" no
         * clasifica). La v0.1.14–v0.1.28 la leía con toLongOrNull: un
         * valor como "12.50" o "0.00" devolvía null → 0 en LOS 3.018
         * juegos, y el orden por popularidad no podía reordenar nada.
         * Aquí es Double, como el PC.
         */
        private const val META_CACHE_VERSION = 3
        private val RELEASE_VER_RE = Regex("""\bv\d+\+([^\s]+)""")

        data class VrpGame(
            val name: String,
            val releaseName: String,
            val packageName: String,
            val versionCode: Long,
            val versionName: String,
            val sizeBytes: Long,
            val downloads: Double,
            val lastUpdated: String? = null,
        )

        /** Parse de la lista de juegos (primera línea = cabecera, se salta).
         *  Columnas reales de *ameList.txt:
         *  0=GameName, 1=ReleaseName, 2=PackageName, 3=VersionCode,
         *  4=LastUpdated, 5=Size (MB), 6=Downloads, 7=Rating, 8=RatingCount. */
        fun parseGameList(text: String, blacklist: Set<String> = emptySet()): List<VrpGame> {
            val blocked = blacklist.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            val out = mutableListOf<VrpGame>()
            val lines = text.lines()
            for ((index, raw) in lines.withIndex()) {
                if (index == 0) continue // cabecera
                val line = raw.trim()
                if (line.isEmpty()) continue
                val cols = line.split(';')
                if (cols.size < 6) continue
                val name = cols[0].trim()
                val releaseName = cols[1].trim().ifBlank { name }
                val pkg = cols[2].trim()
                if (name.isBlank() || pkg.isBlank()) continue
                if (name.lowercase() in blocked) continue
                val versionCode = cols[3].trim().toLongOrNull() ?: 0L
                val versionName = RELEASE_VER_RE.find(releaseName)?.groupValues?.get(1)?.trim()
                    ?.ifBlank { null } ?: cols[3].trim()
                val sizeMb = cols[5].trim().replace(",", ".").toDoubleOrNull()
                val sizeBytes = when {
                    sizeMb == null -> 0L
                    sizeMb > 0.0 -> (sizeMb * 1024.0 * 1024.0).toLong()
                    else -> 1L // "0" MB en el índice = conocido (< 1 MB)
                }
                out.add(
                    VrpGame(
                        name = name,
                        releaseName = releaseName,
                        packageName = pkg,
                        versionCode = versionCode,
                        versionName = versionName,
                        sizeBytes = sizeBytes,
                        // v0.1.29: columna 6 = puntuación decimal del PC
                        // (TryParseDouble, InvariantCulture): "12.50" y
                        // "0.00" son válidas; toLongOrNull las mataba a 0.
                        // Se quitan espacios y separadores de miles (',')
                        // como hace NumberStyles.Any con InvariantCulture.
                        downloads = cols.getOrNull(6)?.trim()
                            ?.replace(" ", "")?.replace(",", "")
                            ?.toDoubleOrNull() ?: 0.0,
                        lastUpdated = cols.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() },
                    )
                )
            }
            return out
        }

        /**
         * Hash de carpeta de un juego (MainForm.cs:4553-4562): MD5 hex en
         * minúsculas de `"<releaseName>\n"` en UTF-8 (ojo: con el salto
         * de línea). El nombre que entra en el hash es el NOMBRE DE
         * RELEASE (columna 1 del GameList, ReleaseNameIndex), NO el
         * nombre de la columna 0: la selección del usuario mete el
         * releaseName en gamesToDownload (MainForm.cs:4136), la cola lo
         * guarda tal cual (4355) y el bucle de descarga lo usa como
         * gameName para el hash (4407-4410, 4553-4562). La v0.1.8–v0.1.14
         * usaban el nombre (columna 0): el hash apuntaba a un directorio
         * que no existe y el servidor devolvía 404 en
         * `<baseUri>/<hash>/` para TODOS los juegos.
         */
        fun gameHash(releaseName: String): String {
            val digest = MessageDigest.getInstance("MD5")
                .digest("$releaseName\n".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** Un archivo de un listado de directorio HTTP (nombre + URL). */
        data class RemoteFile(val name: String, val url: String)

        /**
         * Hrefs de un listado de directorio (mismas reglas que
         * ChunkedDownloader.ListRemoteFilesAsync del PC): se saltan "../",
         * "?", "#" y entradas que terminan en "/" (directorios).
         */
        fun parseDirectoryListing(html: String, baseUrl: String): List<RemoteFile> {
            val base = baseUrl.trimEnd('/')
            val origin = try {
                java.net.URI(base).let { "${it.scheme}://${it.authority}" }
            } catch (_: Exception) {
                base
            }
            val hrefRe = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
            val out = mutableListOf<RemoteFile>()
            for (m in hrefRe.findAll(html)) {
                val href = m.groupValues[1]
                if (href == "../" || href == ".." ||
                    href.startsWith("?") || href.startsWith("#") ||
                    href.endsWith("/")
                ) continue
                val (name, url) = when {
                    href.startsWith("http://") || href.startsWith("https://") -> {
                        java.net.URLDecoder.decode(href.substringAfterLast('/'), "UTF-8") to href
                    }
                    href.startsWith("/") -> {
                        java.net.URLDecoder.decode(href.substringAfterLast('/'), "UTF-8") to origin + href
                    }
                    else -> {
                        java.net.URLDecoder.decode(href, "UTF-8") to "$base/$href"
                    }
                }
                if (name.isBlank() || name == "/") continue
                out.add(RemoteFile(name, url))
            }
            return out
        }

        /**
         * La contraseña del PC va en Base64 en el JSON y el getter la
         * decodifica SIEMPRE (PublicConfig.cs: el setter hace
         * Encoding.UTF8.GetString(Convert.FromBase64String(value))).
         * Replica eso exactamente: decodificar Base64 y luego UTF-8 con
         * reemplazo (Java pone U+FFFD ante bytes inválidos, igual que
         * .NET por defecto). La v0.1.8 hacía lo contrario — usaba el valor
         * en claro si el decodificado "no era imprimible" — y con ese
         * valor la extracción del 7z falla siempre (checksum).
         * Si el valor no es Base64 válido, se usa tal cual.
         */
        fun decodePassword(stored: String): String {
            val t = stored.trim()
            if (t.isEmpty()) return t
            return try {
                val decoded = Base64.getDecoder().decode(t)
                // String(byte[], UTF_8) reemplaza secuencias inválidas por
                // U+FFFD, igual que Encoding.UTF8.GetString de .NET.
                String(decoded, Charsets.UTF_8)
            } catch (_: Exception) {
                t
            }
        }

        /**
         * Candidatos de contraseña para extraer los 7z del repo, en orden.
         */
        fun passwordCandidates(stored: String): List<String> {
            val t = stored.trim()
            if (t.isEmpty()) return listOf("")
            val out = ArrayList<String>()
            fun add(s: String) {
                if (s !in out) out += s
            }
            val decodedBytes = try {
                Base64.getDecoder().decode(t)
            } catch (_: Exception) {
                null
            }
            if (decodedBytes != null) {
                val pc = String(decodedBytes, Charsets.UTF_8)
                add(pc)
                add(String(decodedBytes, Charsets.ISO_8859_1))
                add(String(pc.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
            }
            add(t)
            return out
        }
    }

    data class Config(
        val baseUri: String,
        /** Contraseña principal (la del PC). */
        val password: String,
        /** Candidatos para extraer los 7z, en orden (el primero es password). */
        val passwordCandidates: List<String>,
    )

    // v0.1.22: TLS de la plataforma (ver HttpClients): con el Conscrypt
    // global registrado, OkHttp fallaba al descargar (05:22).
    private val client = HttpClients.applyPlatformSsl(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
    ).build()

    /**
     * Cabeceras de cada petición al mirror, IMITANDO AL PC AL MILÍMETRO
     * (v0.1.11). Lo que envía rclone (cliente Go) por defecto, verificado
     * en pc-vrp/:
     *  - `User-Agent: rclone/v1.72.1` (GetDependencies.cs:93 fija esa
     *    versión; sin --user-agent, rclone usa su UA por defecto).
     *  - `Accept-Encoding: gzip`: lo añade SOLO el transporte HTTP (Go
     *    lo pone automáticamente si no hay Range; OkHttp hace lo mismo
     *    vía BridgeInterceptor y además descomprime transparente). NO
     *    se pone explícito: si se pusiera a mano, OkHttp dejaría de
     *    descomprimir y el HTML del listado llegaría en gzip.
     *  - NADA de `Accept` ni `Accept-Language`: Go no los envía.
     *  - `X-API-Key` (MainForm.cs GetPublicMirrorArgs, --header).
     *  - `Cookie: cf_clearance=...` solo si el pase WebView la consiguió
     *    alguna vez (v0.1.10). OJO: el UA del WebView ya NO se envía: el
     *    UA de rclone manda siempre, porque el de Chrome está bloqueado
     *    en duro; la cookie se envía por si Cloudflare la acepta con
     *    cualquier UA.
     *  - `--tpslimit 1.0` del PC no es una cabecera (límite de ritmo) y
     *    no se imita.
     */
    private fun requestBuilder(url: String): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", RCLONE_UA)
        val apiKey = customRepo?.apiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.VRP_API_KEY
        if (apiKey.isNotBlank()) b.header("X-API-Key", apiKey)
        val cfCookie = settings.cfClearanceCookie
        if (cfCookie.isNotBlank()) b.header("Cookie", cfCookie)
        return b
    }

    /** Borra el pase de Cloudflare guardado (p. ej. antes de resolverlo
     *  de nuevo: la cookie vieja puede estar caducada). */
    fun clearClearance() {
        settings.cfClearanceCookie = ""
        settings.cfUserAgent = ""
    }

    /**
     * Extrae un 7z probando las contraseñas candidatas en orden.
     * Devuelve el índice del candidato que funcionó (0 = la del PC).
     * Limpia el destino entre intentos.
     *
     * v0.1.27: SOLO commons-compress. El binario 7zzs está desactivado
     * (no se ejecuta en Quest/Android 14: exit 159 / EACCES, log
     * 2026-11-19) y ya no se intenta por candidato: antes cada intento
     * dejaba dos líneas engañosas ("probando con 7zzs… / no
     * disponible") y el checkArchive previo. La contraseña nunca se
     * escribe en el log, solo el índice y el motor.
     *
     * v0.1.27 REANUDACIÓN (`resume = true`): si la extracción anterior
     * del mismo archivo se interrumpió (proceso matado, reinicio de la
     * app), NO se borra el destino y la candidata 0 salta las entradas
     * ya completas en disco (ver ArchiveExtractor.extract7z). Si la
     * reanudación falla, se borra todo y se prueban las candidatas
     * desde cero, como antes.
     */
    private fun extractWithCandidates(
        archive: File,
        destDir: File,
        candidates: List<String>,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ): Int = extractWithCandidates(listOf(archive), destDir, candidates, onProgress, resume)

    private fun extractWithCandidates(
        archives: List<File>,
        destDir: File,
        candidates: List<String>,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ): Int {
        val label = if (archives.size == 1) archives[0].name else "${archives[0].name}..(${archives.size} partes)"
        val totalBytes = archives.sumOf { it.length() }
        val engineName = if (NativeSevenZip.isAvailable) "native C++ 7z (libgira7z)" else "commons-compress buffered"
        StartupLog.mark(
            context,
            "vrp 7z: extrayendo $label ($totalBytes bytes), " +
                "${candidates.size} contraseñas a probar ($engineName" +
                (if (archives.size > 1) ", sin unión previa" else "") + ")" +
                if (resume) " — reanudando lo ya extraído" else ""
        )
        // Fase 1 (solo con resume): candidata 0 sin borrar el destino.
        if (resume && candidates.isNotEmpty()) {
            try {
                val stats = ArchiveExtractor.extract7z(archives, destDir, candidates[0], onProgress, resume = true)
                StartupLog.mark(
                    context,
                    "vrp 7z extraído con $engineName, candidata 0 (reanudado: " +
                        "${stats.skippedEntries} entradas ya completas, " +
                        "${stats.skippedBytes} bytes saltados)"
                )
                return 0
            } catch (e: Throwable) {
                StartupLog.mark(
                    context,
                    "vrp 7z: la reanudación con candidata 0 falló " +
                        "(${e.javaClass.simpleName}): se borra lo extraído y se empieza de cero"
                )
                destDir.deleteRecursively()
                destDir.mkdirs()
            }
        }
        // Fase 2: candidatas en orden, destino limpio entre intentos.
        var lastError: Throwable? = null
        var lastKind = ""
        for ((index, pw) in candidates.withIndex()) {
            if (index > 0 || !resume) {
                destDir.deleteRecursively()
                destDir.mkdirs()
            }
            // v0.1.17: registrar la fase ANTES de cada intento (antes solo se
            // registraba el fallo): si la extracción se atasca, el log dice
            // en qué candidata y en qué motor se quedó.
            StartupLog.mark(context, "vrp 7z: probando candidata $index con $engineName…")
            try {
                val stats = ArchiveExtractor.extract7z(archives, destDir, pw, onProgress)
                StartupLog.mark(
                    context,
                    "vrp 7z extraído con $engineName, candidata $index" +
                        if (stats.skippedEntries > 0) " (${stats.skippedEntries} entradas saltadas)" else ""
                )
                return index
            } catch (e: Throwable) {
                lastError = e
                lastKind = "$engineName (${e.javaClass.simpleName})"
                StartupLog.mark(
                    context,
                    "vrp 7z: candidata $index falló con $engineName " +
                        "(${e.javaClass.simpleName}: ${e.message?.take(120)})"
                )
            }
        }
        throw IllegalStateException(
            "No se pudo extraer $label con ninguna contraseña " +
                "(${candidates.size} probadas, último intento: $lastKind): " +
                (lastError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "?"),
            lastError
        )
    }

    private val vrpDir: File
        get() = if (customRepo == null) {
            File(context.filesDir, "vrp").apply { mkdirs() }
        } else {
            val id = customRepo.normalizedBaseUri.lowercase().hashCode().toUInt().toString(16)
            File(context.filesDir, "vrp/custom_$id").apply { mkdirs() }
        }
    private val settings = SettingsStore(context)

    fun clearCustomCache() {
        if (customRepo != null) vrpDir.deleteRecursively()
    }

    /** Lee la configuración del repositorio añadido por el usuario. */
    fun loadConfig(): Config {
        val repo = customRepo
            ?: throw IllegalStateException("No hay ningún repositorio configurado")
        val baseUri = repo.normalizedBaseUri
        if (baseUri.isBlank()) throw IllegalStateException("Repositorio sin dirección baseUri")
        val candidates = passwordCandidates(repo.password)
        return Config(baseUri, candidates.firstOrNull() ?: "", candidates)
    }

    /**
     * Descarga y parsea el índice (o usa la caché si tiene menos de 60 min).
     * Serializada (fetchMutex): una sola descarga a la vez en todo el proceso.
     */
    suspend fun fetchMeta(force: Boolean = false): Repo = withContext(Dispatchers.IO) {
        val cacheFile = File(vrpDir, META_CACHE)
        if (!force && cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < META_CACHE_MAX_AGE_MS
        ) {
            cachedMeta()?.let { return@withContext it }
        }
        // v0.1.18: single-flight (ver fetchMutex). La llamada que espera
        // re-comprueba la caché: si la primera ya refrescó, no re-descarga.
        fetchMutex.withLock {
            if (!force && cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < META_CACHE_MAX_AGE_MS
            ) {
                cachedMeta()?.let { return@withLock it }
            }
            val metaFile = File(vrpDir, META_FILE)
            if (!force && metaFile.exists() &&
                System.currentTimeMillis() - metaFile.lastModified() < META_CACHE_MAX_AGE_MS
            ) {
                try {
                    return@withLock parseMetaArchive(metaFile, loadConfig())
                } catch (_: Exception) {
                }
            }
            fetchMetaDownload()
        }
    }

    /**
     * Descarga y parsea el índice (o usa la caché si tiene menos de 60 min).
     * Lanza excepción con el motivo EXACTO si no hay red ni caché: URL,
     * código HTTP y cadena de causas (v0.1.9: la v0.1.8 tragaba el motivo
     * en RepoManager y la biblioteca solo decía "comprueba la conexión").
     * Todo queda también en el StartupLog (Descargas/GiraQuest-startup.log).
     *
     * v0.1.18: se descarga a un TEMPORAL y solo se sustituye el meta.7z
     * visible con renombrado atómico cuando la descarga está verificada
     * (magic + tamaño): un lector concurrente o un fallo a mitad nunca ve
     * un archivo a medias.
     */
    private suspend fun fetchMetaDownload(): Repo {
        val cfg = loadConfig()
        val url = "${cfg.baseUri}/$META_FILE"
        StartupLog.mark(context, "vrp fetchMeta: $url")
        val metaFile = File(vrpDir, META_FILE)
        // v0.1.18: la descarga va al temporal; el visible solo se sustituye
        // cuando está verificada (ver renombrado atómico abajo).
        val tmpFile = File(vrpDir, "$META_FILE.tmp")
        try {
            var lastError: Throwable? = null
            // Un reintento automático (solo errores de red / 5xx, no 4xx).
            for (attempt in 0..1) {
                try {
                    val req = requestBuilder(url).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            StartupLog.mark(
                                context,
                                "vrp fetchMeta: HTTP ${resp.code} en $url" +
                                    if (settings.cfClearanceCookie.isNotBlank()) " (con cookie cf_clearance)"
                                    else " (sin cookie cf_clearance)"
                            )
                            if (resp.code in 400..499) {
                                throw IllegalStateException(
                                    httpErrorText(resp.code, url)
                                )
                            }
                            throw IllegalStateException(
                                "HTTP ${resp.code} en $url (error del servidor)"
                            )
                        }
                        val body = resp.body ?: throw IllegalStateException("meta.7z vacío: $url")
                        val contentType = resp.header("Content-Type") ?: ""
                        if (contentType.startsWith("text/html")) {
                            throw IllegalStateException(
                                "El servidor devolvió una página HTML en vez de meta.7z " +
                                    "(HTTP ${resp.code}, posible página de bloqueo): $url"
                            )
                        }
                        val expectedSize = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                        val contentEncoding = resp.header("Content-Encoding") ?: ""
                        tmpFile.delete()
                        tmpFile.outputStream().use { out -> body.byteStream().copyTo(out) }
                        // v0.1.12: verificar la descarga ANTES de extraer.
                        // La cabecera de un 7z es siempre 37 7A BC AF 27 1C;
                        // si no está, el archivo no es un 7z (p. ej. una
                        // página de bloqueo guardada como .7z). Y si el
                        // tamaño es menor que Content-Length, la descarga
                        // quedó truncada: reintentarlo (IOException, que el
                        // bucle de reintentos sí vuelve a intentar) en vez
                        // de extraer un archivo cortado, que es justo lo
                        // que da "Compressed data is corrupt" al descomprimir.
                        val downloaded = tmpFile.length()
                        val magic = ByteArray(6)
                        var magicRead = 0
                        try {
                            FileInputStream(tmpFile).use { inp ->
                                while (magicRead < 6) {
                                    val n = inp.read(magic, magicRead, 6 - magicRead)
                                    if (n == -1) break
                                    magicRead += n
                                }
                            }
                        } catch (_: Exception) {
                            magicRead = -1
                        }
                        val magicHex = if (magicRead == 6) {
                            magic.joinToString(" ") { "%02X".format(it) }
                        } else {
                            "(menos de 6 bytes)"
                        }
                        StartupLog.mark(
                            context,
                            "vrp fetchMeta: descargados $downloaded bytes " +
                                "(Content-Length $expectedSize, Content-Encoding " +
                                "'$contentEncoding'), magic $magicHex"
                        )
                        if (magicRead != 6 || magicHex != "37 7A BC AF 27 1C") {
                            throw IllegalStateException(
                                "El archivo descargado no es un 7z (magic $magicHex, " +
                                    "$downloaded bytes): posible página de bloqueo: $url"
                            )
                        }
                        // Con Content-Encoding el Content-Length es el tamaño
                        // comprimido en red, no el del archivo: solo se
                        // compara si no hay codificación.
                        if (expectedSize > 0 && contentEncoding.isBlank() &&
                            downloaded < expectedSize
                        ) {
                            throw java.io.IOException(
                                "Descarga incompleta de meta.7z: $downloaded de " +
                                    "$expectedSize bytes"
                            )
                        }
                    }
                    lastError = null
                    break
                } catch (e: IllegalStateException) {
                    // 4xx y HTML: no tiene sentido reintentar.
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    StartupLog.mark(
                        context,
                        "vrp fetchMeta intento $attempt falló: " +
                            (es.girarkas.giraquest.adb.AdbHealth.fullChainReason(e) ?: e.toString())
                    )
                    if (attempt == 0) delay(2000)
                }
            }
            lastError?.let { throw it }
            // v0.1.18: renombrado atómico. Hasta aquí solo se tocó el
            // temporal; el meta.7z visible se sustituye ahora que la
            // descarga está verificada (magic + tamaño). Un lector
            // concurrente o un fallo a mitad nunca ve un archivo a medias.
            if (!tmpFile.renameTo(metaFile)) {
                metaFile.delete()
                if (!tmpFile.renameTo(metaFile)) {
                    tmpFile.copyTo(metaFile, overwrite = true)
                    tmpFile.delete()
                }
            }
            StartupLog.mark(context, "vrp fetchMeta: descargados ${metaFile.length()} bytes")
            val repo = parseMetaArchive(metaFile, cfg)
            StartupLog.mark(context, "vrp fetchMeta: ${repo.entries.size} juegos en el índice")
            return repo
        } catch (e: Throwable) {
            // Sin red o bloqueado: intentar con la caché aunque sea vieja.
            cachedMeta()?.let {
                StartupLog.mark(context, "vrp fetchMeta falló, usando caché: ${e.message}")
                return it
            }
            StartupLog.mark(
                context,
                "vrp fetchMeta FALLÓ: " +
                    (es.girarkas.giraquest.adb.AdbHealth.fullChainReason(e) ?: e.toString())
            )
            throw e
        }
    }

    /** Texto claro para un código HTTP al pedir algo al mirror. */
    private fun httpErrorText(code: Int, url: String): String = when (code) {
        403 -> "El servidor bloqueó la petición (HTTP 403) en $url — " +
            "Cloudflare rechaza este cliente. La app ya imita al cliente " +
            "del PC (rclone) y ni así pasa; el Chrome del móvil también " +
            "está bloqueado en duro (sin challenge). Queda por probar la " +
            "huella TLS del cliente."
        404 -> "No existe $url (HTTP 404)"
        401 -> "El servidor pide autenticación (HTTP 401) en $url"
        else -> "HTTP $code en $url"
    }

    /** El índice guardado (offline). Null si nunca se descargó o es versión vieja sin tamaños. */
    fun cachedMeta(): Repo? {
        val cacheFile = File(vrpDir, META_CACHE)
        if (!cacheFile.exists()) return null
        return try {
            val repo = RepoManager(context).parseRepo(effectiveRepoUrl, cacheFile.readText())
            if (repo.version < META_CACHE_VERSION) {
                val metaFile = File(vrpDir, META_FILE)
                if (metaFile.exists()) {
                    try {
                        return parseMetaArchive(metaFile, loadConfig())
                    } catch (_: Exception) {
                    }
                }
                return null
            }
            repo
        } catch (_: Exception) {
            null
        }
    }

    /** Extrae meta.7z, parsea la lista y deja carátulas/notas en su sitio. */
    private fun parseMetaArchive(metaFile: File, cfg: Config): Repo {
        val extractDir = File(vrpDir, "meta-extracted").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            // La contraseña que cifra meta.7z es la del PC; se prueban los
            // candidatos en orden (v0.1.9: la v0.1.8 usaba el valor en
            // claro y la extracción fallaba siempre con checksum).
            extractWithCandidates(metaFile, extractDir, cfg.passwordCandidates)
            val listFile = extractDir.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith("ameList.txt", ignoreCase = true) }
                ?: throw IllegalStateException("meta.7z sin lista de juegos (*ameList.txt)")
            val blacklist = File(extractDir, ".meta/nouns/blacklist.txt")
                .takeIf { it.exists() }
                ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
            val listText = listFile.readText()
            // v0.1.29: líneas crudas del índice tal cual (las 3 primeras
            // de datos): si la popularidad vuelve a salir a 0, aquí se ve
            // el formato real (nº de columnas ';' y el valor de la col 6).
            val rawDataLines = listText.lines()
                .drop(1).map { it.trim() }.filter { it.isNotEmpty() }.take(3)
            rawDataLines.forEachIndexed { i, l ->
                val c = l.split(';')
                StartupLog.mark(
                    context,
                    "vrp meta línea ${i + 1}: ${c.size} cols, col6='${c.getOrNull(6) ?: ""}': ${l.take(200)}"
                )
            }
            val games = parseGameList(listText, blacklist)
            // Carátulas (PC: UpdateGamePhotos): <paquete>.<ext> a filesDir/thumbnails/.
            // v0.1.41: mover con renameTo dentro de filesDir (instantáneo frente a copiar miles de ficheros).
            val thumbsSrc = File(extractDir, ".meta/thumbnails")
            val thumbsDst = File(context.filesDir, "thumbnails").apply { mkdirs() }
            var thumbsCopied = 0
            thumbsSrc.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val target = File(thumbsDst, f.name)
                    if (target.exists()) target.delete()
                    if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
                    thumbsCopied++
                }
            }
            if (thumbsCopied > 0) {
                es.girarkas.giraquest.download.ThumbnailCache.invalidateIndex()
            }
            // Notas (PC: notes/<ReleaseName>.txt = descripción de la ficha).
            val notesSrc = File(extractDir, ".meta/notes")
            val notesDst = File(vrpDir, "notes").apply { mkdirs() }
            var notesCopied = 0
            notesSrc.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val target = File(notesDst, f.name)
                    if (target.exists()) target.delete()
                    if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
                    notesCopied++
                }
            }
            // v0.1.19: dejar constancia de cuántas notas/caratulas trae el
            // índice. Si un juego muestra "Sin descripción", aquí se ve si
            // el repo trae notas en general (0 = el .meta/notes no viene o
            // no está en .meta/, y hay que revisar la ruta) o si solo ese
            // juego no tiene nota (el repo no la trae: correcto).
            StartupLog.mark(
                context,
                "vrp meta: $thumbsCopied carátulas, $notesCopied notas en .meta/ " +
                    "(${games.size} juegos)"
            )
            val notesByLowerKey = HashMap<String, String>()
            notesDst.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.equals("txt", ignoreCase = true)) {
                    val cleaned = f.readText()
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                        .lines()
                        .map { it.trimEnd() }
                        .joinToString("\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim()
                        .take(2000)
                    if (cleaned.isNotEmpty()) {
                        notesByLowerKey[f.nameWithoutExtension.trim().lowercase()] = cleaned
                    }
                }
            }
            val entries = games.map { g ->
                // v0.1.15: el hash es del NOMBRE DE RELEASE (columna 1),
                // como el PC (MainForm.cs:4136/4355/4553). Con el nombre
                // (columna 0) el servidor devolvía 404 para todos.
                val hash = gameHash(g.releaseName)
                val note = notesByLowerKey[g.releaseName.trim().lowercase()]
                    ?: File(notesDst, "${g.releaseName}.txt")
                        .takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.take(2000)
                RepoEntry(
                    name = g.name,
                    releaseName = g.releaseName,
                    packageName = g.packageName,
                    versionCode = g.versionCode,
                    versionName = g.versionName,
                    apkUrl = "${cfg.baseUri}/$hash/",
                    apkSha256 = null,
                    apkSize = g.sizeBytes,
                    obbUrl = null,
                    obbFile = null,
                    iconUrl = null,
                    description = note,
                    installTxtUrl = null,
                    requiresOwnedFiles = false,
                    trailerUrl = null,
                    youtubeId = null,
                    downloads = g.downloads,
                    repoUrl = effectiveRepoUrl,
                    repoName = effectiveRepoName,
                    vrpHash = hash,
                    lastUpdated = g.lastUpdated,
                )
            }
            val repo = Repo(effectiveRepoUrl, effectiveRepoName, META_CACHE_VERSION, entries, thumbnailsUrl = null)
            // Caché como repo.json normal (la lee RepoManager.parseRepo).
            val root = JSONObject().apply {
                put("name", effectiveRepoName)
                put("version", META_CACHE_VERSION)
                val apps = JSONArray()
                entries.forEach { apps.put(it.toJson()) }
                put("apps", apps)
            }
            File(vrpDir, META_CACHE).writeText(root.toString())
            return repo
        } finally {
            extractDir.deleteRecursively()
        }
    }

    /** Trozos del juego en `<baseUri>/<hash>/` (ordenados por nombre). */
    suspend fun listGameParts(hash: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val cfg = loadConfig()
        val url = "${cfg.baseUri}/$hash/"
        StartupLog.mark(context, "vrp listGameParts: $url")
        val req = requestBuilder(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // v0.1.15: el 404 aquí es el error que veía Edu ("No existe
                // https://go.src..."): el hash apuntaba a un directorio
                // que no existe. Queda la URL COMPLETA en el log.
                StartupLog.mark(context, "vrp listGameParts FALLÓ: HTTP ${resp.code} en $url")
                throw IllegalStateException(httpErrorText(resp.code, url))
            }
            val html = resp.body?.string() ?: throw IllegalStateException("Listado vacío: $url")
            parseDirectoryListing(html, url)
                .filter { it.name.startsWith("$hash.7z.", ignoreCase = true) }
                .sortedBy { it.name }
                .also { parts ->
                    StartupLog.mark(
                        context,
                        "vrp listGameParts: ${parts.size} partes en $url: " +
                            parts.joinToString(", ") { it.name }.take(300)
                    )
                }
                .ifEmpty { throw IllegalStateException("El servidor no lista archivos para este juego ($url)") }
        }
    }

    /** Tamaño de un archivo remoto (HEAD). 0 si no se puede saber. */
    private fun remoteSize(url: String): Long {
        return try {
            val req = requestBuilder(url).head().build()
            client.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** Tamaño de descarga de un juego del repo público (v0.1.27):
     *  1 GET al listado + 1 HEAD por parte; el total es la suma. El
     *  índice NO trae tamaños, así que esto es bajo demanda (ficha,
     *  confirmación, carga perezosa en la lista), nunca para los 3.018
     *  juegos de golpe. Lanza si no se puede saber (sin red, 404). */
    data class GameDownloadSize(val totalBytes: Long, val parts: Int)

    suspend fun downloadSize(entry: RepoEntry): GameDownloadSize = withContext(Dispatchers.IO) {
        val hash = entry.vrpHash
            ?: throw IllegalStateException("La entrada no es del repositorio público")
        val (parts, sizes) = probeParts(hash)
        val total = sizes.values.sum()
        StartupLog.mark(
            context,
            "vrp downloadSize: ${entry.displayName} ${parts.size} partes, total $total bytes"
        )
        GameDownloadSize(total, parts.size)
    }

    /** Listado de partes + tamaño HEAD de cada una (lo que downloadGame
     *  ya hacía en línea para calcular el total antes de descargar). */
    private suspend fun probeParts(hash: String): Pair<List<RemoteFile>, Map<RemoteFile, Long>> {
        val parts = listGameParts(hash)
        val sizes = parts.associateWith { remoteSize(it.url) }
        return parts to sizes
    }

    /**
     * Descarga los trozos del juego (.7z.001, .7z.002…) dentro de [dir].
     * v0.1.41: devuelve la lista ordenada de partes (o el .7z unido si ya
     * existía completo en disco) sin copiar gigabytes para unirlos:
     * ArchiveExtractor los lee directamente en streaming multi-volumen.
     */
    suspend fun downloadGame(
        entry: RepoEntry,
        dir: File,
        onProgress: (percent: Int, speedBytesPerSec: Long) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        val hash = entry.vrpHash
            ?: throw IllegalStateException("La entrada no es del repositorio público")
        StartupLog.mark(
            context,
            "vrp downloadGame: ${entry.displayName} release=${entry.releaseName} " +
                "hash=$hash dir=${entry.apkUrl}"
        )
        val (parts, sizes) = probeParts(hash)
        val downloader = Downloader(settings)
        dir.mkdirs()
        val total = sizes.values.sum()
        val joined = File(dir, "$hash.7z")
        if (total > 0 && joined.exists() && joined.length() == total) {
            StartupLog.mark(
                context,
                "vrp downloadGame: ${joined.name} ya existe completo en disco ($total bytes), reutilizando sin re-descargar"
            )
            onProgress(100, 0L)
            return@withContext listOf(joined)
        }
        StartupLog.mark(
            context,
            "vrp downloadGame: ${parts.size} partes, total ${total} bytes " +
                "(tamaños por HEAD: ${sizes.values.joinToString(",")})"
        )
        var done = parts.sumOf { File(dir, it.name).takeIf { f -> f.exists() }?.length() ?: 0L }
        for ((index, part) in parts.withIndex()) {
            val dest = File(dir, part.name)
            val before = if (dest.exists()) dest.length() else 0L
            when (val r = downloader.download(
                part.url, dest,
                expectedSize = sizes[part] ?: 0L,
                extraHeaders = apiHeaders(),
                onProgress = { p ->
                    val overall = if (total > 0) {
                        ((done + p.bytesDone) * 100 / total).toInt()
                    } else {
                        (index * 100 + p.percent) / parts.size
                    }
                    onProgress(overall.coerceIn(0, 100), p.speedBytesPerSec)
                }
            )) {
                is DownloadResult.Failed -> {
                    StartupLog.mark(
                        context,
                        "vrp downloadGame FALLÓ parte ${part.name}: ${r.reason}"
                    )
                    throw IllegalStateException(
                        "No se pudo descargar ${part.name}: ${r.reason}"
                    )
                }
                is DownloadResult.Ok -> {
                    StartupLog.mark(
                        context,
                        "vrp downloadGame: parte ${part.name} OK " +
                            "(${(if (dest.exists()) dest.length() else 0L)} bytes)"
                    )
                }
            }
            done += (if (dest.exists()) dest.length() else 0L) - before
        }
        val sortedFiles = parts
            .sortedBy { it.name.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE }
            .map { File(dir, it.name) }
        StartupLog.mark(
            context,
            "vrp downloadGame: ${sortedFiles.size} partes listas para extracción directa (${sortedFiles.sumOf { it.length() }} bytes, esperados $total)"
        )
        sortedFiles
    }

    /** Extrae el .7z (o partes .7z.001…) del juego en [destDir]. */
    fun extractGame(
        archive: File,
        destDir: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ) = extractGame(listOf(archive), destDir, onProgress, resume)

    fun extractGame(
        archives: List<File>,
        destDir: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ) {
        val cfg = loadConfig()
        val label = if (archives.size == 1) archives[0].name else "${archives[0].name}..(${archives.size} partes)"
        StartupLog.mark(
            context,
            "vrp extractGame: $label (${archives.sumOf { it.length() }} bytes) → ${destDir.absolutePath}" +
                if (resume) " (reanudando)" else ""
        )
        extractWithCandidates(archives, destDir, cfg.passwordCandidates, onProgress, resume)
    }

    /** Cabecera X-API-Key (vacía si el build no la lleva) + pase de
     *  Cloudflare si lo hay (v0.1.10) + UA de rclone (v0.1.11).
     *  Downloader aplica estos headers después de su UA por defecto,
     *  así que el UA de rclone manda también en las descargas de juegos.
     *  El UA del WebView ya no se envía: está bloqueado en duro. */
    fun apiHeaders(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = RCLONE_UA
        val apiKey = customRepo?.apiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.VRP_API_KEY
        if (apiKey.isNotBlank()) out["X-API-Key"] = apiKey
        val cfCookie = settings.cfClearanceCookie
        if (cfCookie.isNotBlank()) out["Cookie"] = cfCookie
        return out
    }

    /** Primer APK dentro de la carpeta extraída (recursivo). */
    fun findGameApk(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }

    /** ¿Hay archivos .obb dentro de la carpeta extraída? */
    fun hasObbFiles(dir: File): Boolean =
        dir.walkTopDown().any { it.isFile && it.extension.equals("obb", ignoreCase = true) }

    /** ¿Hay archivos de DATOS reales del juego dentro de la carpeta extraída?
     *  (v0.1.38: usa ArchiveExtractor.isGameDataFile para ignorar .apk,
     *  .extracted_ok, .extracting, release.manifest, install.txt y 0 bytes). */
    fun hasGameDataFiles(dir: File): Boolean {
        if (!dir.exists()) return false
        return try {
            dir.walkTopDown().any { f ->
                if (!f.isFile) return@any false
                val inPkg = generateSequence(f.parentFile) { it.parentFile }
                    .takeWhile { it != dir }
                    .any { '.' in it.name && !it.name.startsWith('.') }
                ArchiveExtractor.isGameDataFile(f, insidePkgFolder = inPkg)
            }
        } catch (_: Exception) {
            false
        }
    }
}
