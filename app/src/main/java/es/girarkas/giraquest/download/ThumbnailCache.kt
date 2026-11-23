package es.girarkas.giraquest.download

import android.content.Context
import es.girarkas.giraquest.HttpClients
import es.girarkas.giraquest.data.Repo
import es.girarkas.giraquest.data.RepoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Caché local de carátulas — espejo literal del PC:
 *
 *  PC (Sideloader/RCLONE.cs:41, UpdateGamePhotos):
 *    ThumbnailsFolder = <CurrentDirectory>/thumbnails
 *    sync "<remote>:/.meta/thumbnails" -> ThumbnailsFolder
 *  PC (GalleryView.cs GetCachedImage, 1606-1620):
 *    busca <ThumbnailsFolder>/<PackageName>.jpg y luego .png,
 *    con caché en memoria (LRU, MAX_CACHE_SIZE).
 *  PC (MainForm.cs:6707): la ficha también carga
 *    <ThumbnailsFolder>/<PackageName>.jpg/.png.
 *
 * En Quest no hay rclone: la sincronización es HTTP directa contra la
 * carpeta `thumbnails` del repo (repo.json: "thumbnailsUrl", equivalente
 * al `.meta/thumbnails` remoto). Cada carátula se guarda como
 * `<PackageName>.<ext>` en filesDir/thumbnails/.
 *
 * Fallback por juego (no existe en el PC, que solo usa la carpeta):
 * iconUrl del repo.json, guardado también como <PackageName>.<ext>.
 */
class ThumbnailCache(private val context: Context) {

    /** Equivale a SideloaderRCLONE.ThumbnailsFolder del PC. */
    val thumbnailsDir: File = File(context.filesDir, "thumbnails").apply { mkdirs() }

    // v0.1.22: TLS de la plataforma (ver HttpClients).
    private val client = HttpClients.applyPlatformSsl(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
    ).build()

    /**
     * Progreso HONESTO de la sincronización (v0.1.27): el total es el
     * CATÁLOGO REAL, no las nuevas de esta pasada. La v0.1.26 decía
     * "Carátulas listas: 73 de 73" con 3.018 juegos en la lista porque
     * el total eran solo las que faltaban en caché (73 nuevas). Ahora:
     * doneNew/totalNew = carátulas nuevas de esta pasada; cached =
     * carátulas realmente en caché (contadas de filesDir/thumbnails);
     * catalogTotal = juegos del catálogo. Una carátula que no existe en
     * el repo (sin .jpg/.png ni iconUrl) NUNCA cuenta como cargada.
     */
    data class CoverProgress(
        val doneNew: Int,
        val totalNew: Int,
        val cached: Int,
        val catalogTotal: Int,
        val finished: Boolean,
    )

    var progressListener: ((CoverProgress) -> Unit)? = null
    private var progressDoneNew = 0
    private var progressTotalNew = 0
    private var progressCatalogTotal = 0
    private var progressCached = 0

    private val extensions = listOf(".jpg", ".png", ".webp")

    /** GetCachedImage del PC: <paquete>.jpg, luego .png (.webp extra).
     *  v0.1.43: usa índice en memoria para que el scroll / cambio de filtro
     *  no haga miles de File.exists() en el hilo principal. */
    fun cachedFile(packageName: String): File? {
        if (packageName.isBlank()) return null
        ensureIndex()
        val f = indexMap[packageName]
        if (f != null && f.exists() && f.length() > 0) return f
        if (f != null) indexMap.remove(packageName)
        return null
    }

    fun ensureIndex() {
        if (indexLoaded) return
        synchronized(indexLock) {
            if (indexLoaded) return
            val map = HashMap<String, File>()
            try {
                thumbnailsDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        val name = file.name
                        val dot = name.lastIndexOf('.')
                        if (dot > 0) {
                            val ext = name.substring(dot).lowercase()
                            if (ext in extensions) {
                                val pkg = name.substring(0, dot)
                                val prev = map[pkg]
                                if (prev == null || extensions.indexOf(ext) < extensions.indexOf("." + prev.extension.lowercase())) {
                                    map[pkg] = file
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            indexMap.clear()
            indexMap.putAll(map)
            indexLoaded = true
        }
    }

    /** Carátulas realmente disponibles en caché para estos repos. */
    fun cachedCountFor(repos: List<Repo>): Int =
        repos.sumOf { repo -> repo.entries.count { cachedFile(it.packageName) != null } }

    /**
     * Sincroniza las carátulas de un repo (UpdateGamePhotos del PC):
     * para cada juego sin carátula en caché, intenta
     *   <thumbnailsUrl>/<PackageName>.jpg, luego .png
     * y si no hay, el iconUrl del juego.
     * No borra la caché (el PC hace sync con borrado; aquí prima no
     * re-descargar en cada arranque).
     * Devuelve cuántas carátulas NUEVAS se descargaron de verdad en
     * esta pasada (solo las que existen en el repo: si no hay .jpg/.png
     * ni iconUrl, no se cuenta como cargada).
     */
    suspend fun syncRepo(repo: Repo): Int = withContext(Dispatchers.IO) {
        var downloadedNew = 0
        for (entry in repo.entries) {
            if (cachedFile(entry.packageName) != null) continue
            // 1) Carpeta thumbnails del repo.
            val base = repo.thumbnailsUrl?.trim()?.trimEnd('/')
            var done = false
            if (!base.isNullOrBlank()) {
                for (ext in listOf(".jpg", ".png")) {
                    if (downloadTo("$base/${entry.packageName}$ext", File(thumbnailsDir, entry.packageName + ext))) {
                        done = true
                        break
                    }
                }
            }
            // 2) Fallback: iconUrl del juego.
            if (!done) {
                val icon = entry.iconUrl?.takeIf { it.isNotBlank() }
                if (icon != null) {
                    val ext = when {
                        icon.lowercase().contains(".png") -> ".png"
                        icon.lowercase().contains(".webp") -> ".webp"
                        else -> ".jpg"
                    }
                    done = downloadTo(icon, File(thumbnailsDir, entry.packageName + ext))
                }
            }
            // v0.1.27: solo cuenta si se descargó de verdad; una
            // carátula que no existe en el repo no es "cargada".
            if (done) {
                downloadedNew++
                progressDoneNew++
                progressCached++
            }
            progressListener?.invoke(
                CoverProgress(
                    progressDoneNew, progressTotalNew,
                    progressCached, progressCatalogTotal, finished = false
                )
            )
        }
        downloadedNew
    }

    suspend fun syncAll(repos: List<Repo>) {
        // Total REAL antes de empezar: el catálogo entero (no solo las
        // nuevas). Las nuevas = juegos sin carátula en caché.
        progressCatalogTotal = repos.sumOf { it.entries.size }
        progressTotalNew = repos.sumOf { repo ->
            repo.entries.count { cachedFile(it.packageName) == null }
        }
        progressDoneNew = 0
        progressCached = cachedCountFor(repos)
        progressListener?.invoke(
            CoverProgress(0, progressTotalNew, progressCached, progressCatalogTotal, finished = false)
        )
        for (repo in repos) {
            try {
                syncRepo(repo)
            } catch (_: Exception) {
                // Sin red o repo sin thumbnails: se sigue con iconUrl/placeholder.
            }
        }
        // Recuento final de verdad (por si alguna descarga falló
        // a mitad o el repo cambió durante la sincronización).
        progressCached = cachedCountFor(repos)
        progressListener?.invoke(
            CoverProgress(
                progressDoneNew, progressTotalNew,
                progressCached, progressCatalogTotal, finished = true
            )
        )
    }

    private fun downloadTo(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "GiraQuest/0.1").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                // Rechazar respuestas que no son imagen (p. ej. 404 HTML).
                val type = resp.header("Content-Type") ?: ""
                if (!type.startsWith("image/") && body.contentLength() < 1024) return false
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
                val ok = dest.exists() && dest.length() > 1024
                if (ok) {
                    val dot = dest.name.lastIndexOf('.')
                    if (dot > 0) indexMap[dest.name.substring(0, dot)] = dest
                }
                ok
            }
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    companion object {
        /** Extensiones que prueba GetCachedImage (PC: .jpg, .png). */
        val PC_EXTENSIONS = listOf(".jpg", ".png")

        private val indexLock = Any()
        private val indexMap = ConcurrentHashMap<String, File>()
        @Volatile
        private var indexLoaded = false

        fun invalidateIndex() {
            indexLoaded = false
        }
    }
}
