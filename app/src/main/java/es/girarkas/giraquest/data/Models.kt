package es.girarkas.giraquest.data

import org.json.JSONObject

/**
 * Una entrada de juego/app en un repo. Formato propio (spec §3), NO el
 * meta.7z/rclone del PC: un repo.json HTTP simple.
 *
 * Campos espejo de los índices del PC (RCLONE.cs):
 *   0 GameName -> name, 1 ReleaseName -> releaseName, 2 PackageName -> package,
 *   3 VersionCode -> versionCode, 4 ReleaseAPKPath -> apkUrl,
 *   5 VersionName -> versionName, 6 Downloads (no se usa), 7 InstalledVersion
 *   -> se calcula en local contra PackageManager.
 */
data class RepoEntry(
    val name: String,
    val releaseName: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String?,
    val apkSize: Long,
    val obbUrl: String?,
    val obbFile: String?,
    val iconUrl: String?,
    val description: String?,
    val installTxtUrl: String?,
    val requiresOwnedFiles: Boolean,
    /** Tráiler de YouTube del juego (como los trailers del PC). Uno de los dos basta. */
    val trailerUrl: String?,
    val youtubeId: String?,
    /** Nº de descargas según el repo (índice 6 del PC). 0 = el repo no lo da. */
    val downloads: Double,
    val repoUrl: String = "",
    val repoName: String = "",
    /** URL base de la carpeta thumbnails del repo (equivale al
     *  `.meta/thumbnails` remoto del PC). La rellena RepoManager al parsear. */
    val thumbnailsBaseUrl: String = "",
    /**
     * Hash MD5 de la carpeta del juego en el repositorio público VRP
     * (MD5 de "<nombre>\n"). No null = entrada del repo público: su
     * descarga no es un APK directo sino los trozos .7z.001 de
     * `<baseUri>/<hash>/` (ver VrpPublicRepo).
     */
    val vrpHash: String? = null,
    /** Fecha de última actualización en el índice (columna 4 de GameList.txt). */
    val lastUpdated: String? = null,
) {
    val hasObb: Boolean get() = !obbUrl.isNullOrBlank()
    val displayName: String get() = name.ifBlank { releaseName }

    /** Id de YouTube (11 caracteres) sacado de youtubeId o de trailerUrl. */
    val trailerYoutubeId: String?
        get() {
            youtubeId?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            val url = trailerUrl?.trim().orEmpty()
            if (url.isBlank()) return null
            // https://www.youtube.com/watch?v=XXXX / https://youtu.be/XXXX / /embed/XXXX
            val patterns = listOf(
                Regex("[?&]v=([A-Za-z0-9_-]{11})"),
                Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
                Regex("/embed/([A-Za-z0-9_-]{11})"),
                Regex("/shorts/([A-Za-z0-9_-]{11})"),
            )
            for (p in patterns) {
                p.find(url)?.let { return it.groupValues[1] }
            }
            return null
        }

    val hasTrailer: Boolean get() = trailerYoutubeId != null

    /** Miniatura oficial de YouTube para el tráiler (hqdefault). */
    val trailerThumbnailUrl: String?
        get() = trailerYoutubeId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("releaseName", releaseName)
        put("package", packageName)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("apkUrl", apkUrl)
        put("apkSha256", apkSha256)
        put("apkSize", apkSize)
        put("obbUrl", obbUrl)
        put("obbFile", obbFile)
        put("iconUrl", iconUrl)
        put("description", description)
        put("installTxtUrl", installTxtUrl)
        put("requiresOwnedFiles", requiresOwnedFiles)
        put("trailerUrl", trailerUrl)
        put("youtubeId", youtubeId)
        put("downloads", downloads)
        put("repoUrl", repoUrl)
        put("repoName", repoName)
        put("thumbnailsBaseUrl", thumbnailsBaseUrl)
        put("vrpHash", vrpHash)
        put("lastUpdated", lastUpdated)
    }

    companion object {
        fun fromJson(obj: JSONObject, repoUrl: String, repoName: String): RepoEntry? {
            val apkUrl = obj.optString("apkUrl").ifBlank { return null }
            val pkg = obj.optString("package").ifBlank { return null }
            return RepoEntry(
                name = obj.optString("name", obj.optString("releaseName", pkg)),
                releaseName = obj.optString("releaseName", obj.optString("name", pkg)),
                packageName = pkg,
                versionCode = obj.optLong("versionCode", 0),
                versionName = obj.optString("versionName", ""),
                apkUrl = apkUrl,
                apkSha256 = obj.optString("apkSha256").ifBlank { null },
                apkSize = obj.optLong("apkSize", 0),
                obbUrl = obj.optString("obbUrl").ifBlank { null },
                obbFile = obj.optString("obbFile").ifBlank { null },
                iconUrl = obj.optString("iconUrl").ifBlank { null },
                description = obj.optString("description").ifBlank { null },
                installTxtUrl = obj.optString("installTxtUrl").ifBlank { null },
                requiresOwnedFiles = obj.optBoolean("requiresOwnedFiles", false),
                trailerUrl = obj.optString("trailerUrl").ifBlank { null },
                youtubeId = obj.optString("youtubeId").ifBlank { null },
                downloads = obj.optDouble("downloads", 0.0),
                repoUrl = obj.optString("repoUrl").ifBlank { repoUrl },
                repoName = obj.optString("repoName").ifBlank { repoName },
                thumbnailsBaseUrl = obj.optString("thumbnailsBaseUrl").ifBlank { "" },
                vrpHash = obj.optString("vrpHash").ifBlank { null },
                lastUpdated = obj.optString("lastUpdated").ifBlank { null },
            )
        }
    }
}

/** Repositorio VRP personalizado añadido por el usuario (baseUri + password + apiKey opcional). */
data class CustomVrpRepo(
    val baseUri: String,
    val password: String,
    val name: String = "",
    val apiKey: String = "",
) {
    val normalizedBaseUri: String get() = baseUri.trim().trimEnd('/')
    val repoUrl: String get() = "vrp://$normalizedBaseUri"
    val displayName: String
        get() = name.takeIf { it.isNotBlank() } ?: run {
            try {
                java.net.URI(normalizedBaseUri).host ?: normalizedBaseUri
            } catch (_: Exception) {
                normalizedBaseUri
            }
        }
}

data class Repo(
    val url: String,
    val name: String,
    val version: Int,
    val entries: List<RepoEntry>,
    /** URL base de la carpeta de carátulas del repo (`.meta/thumbnails` del PC). */
    val thumbnailsUrl: String? = null,
)

/** Estado de instalación de una entrada, como en el PC (Instalado /
 * Actualización disponible / Descargado / Por delante de la lista). */
enum class InstallState {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    DOWNLOADED,
    AHEAD_OF_LIST, // instalada una versión mayor que la del repo
}

/**
 * ¿Trae OBB el juego? Para el repo público VRP el índice (ameList.txt)
 * NO tiene columna de OBB y la app lo pone siempre a false (obbUrl =
 * null): la ficha decía "Solo APK, sin OBB" para TODOS los juegos del
 * repo, incluso para los que sí tienen OBB (The Walking Dead, Pistol
 * Whip). El PC tampoco lo sabe por el índice: lo comprueba en disco
 * tras extraer (carpeta <paquete>/). Por eso el estado es tri-estado:
 * UNKNOWN = aún no descargado, no se puede saber; YES/NO = sabido (por
 * el índice en repos normales, por lo extraído en VRP).
 */
enum class ObbStatus { UNKNOWN, YES, NO }

/**
 * OBB pendiente de copiar (v0.1.19): el APK se instaló por el
 * instalador del sistema (fallback, sin ADB) o la copia del OBB falló,
 * y el OBB quedó en disco sin colocar. Se guarda en preferencias y se
 * completa SOLO en cuanto el ADB vuelve a estar vivo (sin que el
 * usuario tenga que hacer nada). stagingDir es la carpeta en Descargas
 * con el contenido OBB ya extraído (o la carpeta extraída entera).
 *
 * v0.1.27: stagingDir es la RAÍZ extraída (game_extracted u
 * obb_extracted), no una sola subcarpeta: los .obb de un juego pueden
 * estar repartidos en varias carpetas y la copia debe cubrirlos todos
 * (ver ArchiveExtractor.findAllObbFiles). obbFiles guarda la lista
 * completa encontrada al registrar el pendiente, para que el reintento
 * y la UI sepan qué falta sin re-escanear; lastError guarda el motivo
 * del último fallo para enseñarlo en vez de callarlo.
 */
data class PendingObb(
    val packageName: String,
    val stagingDir: String,
    val versionCode: Long,
    val gameName: String,
    val obbFiles: List<ObbFileInfo> = emptyList(),
    val lastError: String = "",
    /** v0.1.29: fallos seguidos de copia directa con el mismo motivo.
     *  A partir de 2, el temporizador deja de reintentarlo solo (queda
     *  en fallido visible con Reintentar manual; el reintento manual
     *  pone el contador a 0). */
    val failedAttempts: Int = 0,
)

/** Un .obb encontrado en lo extraído: nombre, ruta relativa a la raíz
 *  extraída y tamaño esperado (para verificar la copia por archivo). */
data class ObbFileInfo(
    val name: String,
    val relativePath: String,
    val size: Long,
)

/** Estado del OBB de un juego, para la UI (ficha, Instalados, banner).
 *  Se calcula desde el registro persistente + el disco: nunca se
 *  afirma "copiado" sin comprobar tamaños. */
sealed class ObbUiState {
    /** El juego no trae OBB (solo lo dice quien lo ha comprobado:
     *  extracción terminada bien sin .obb, o registro NO_OBB). */
    object NoObb : ObbUiState()

    /** v0.1.28: SIN información de OBB (juego nunca descargado ni
     *  instalado, o extracción sin terminar). NO se puede decir "sin
     *  OBB": se sabrá al descargar. */
    object Unknown : ObbUiState()

    /** Todos los .obb están en /Android/obb/<paquete>/ con su tamaño.
     *  v0.1.30: unverified = la copia escribió sin error pero el tamaño
     *  del destino no se pudo comprobar (lectura denegada en Horizon):
     *  "copiado, sin verificar", no un falso "completo verificado". */
    data class Copied(
        val files: List<ObbFileInfo>,
        val bytes: Long,
        val completedAtMs: Long,
        val unverified: Boolean = false,
    ) : ObbUiState()

    /** APK instalada, OBB sin copiar todavía (se está copiando o
     *  esperando al temporizador/reintento). */
    data class Pending(val files: List<ObbFileInfo>, val reason: String) : ObbUiState()

    /** Falta MANAGE_EXTERNAL_STORAGE: sin él no se puede copiar. */
    data class NotGranted(val files: List<ObbFileInfo>) : ObbUiState()

    /** La copia falló con un motivo concreto (no silencioso). */
    data class Failed(val files: List<ObbFileInfo>, val reason: String) : ObbUiState()
}

/** Estado global de una instalación (v0.1.27): UN solo estado final
 *  claro y persistente por juego, para que siempre se sepa cuándo está
 *  TODO terminado (descarga → extracción → APK → OBB). La instalación
 *  NO es COMPLETE hasta que el OBB tiene resultado conocido. */
enum class InstallRecordState { IN_PROGRESS, COMPLETE, PENDING_OBB, FAILED }

/** Resultado del OBB dentro del registro. */
enum class ObbRecordState { NO_OBB, PENDING, COMPLETE, FAILED, NOT_GRANTED }

/**
 * Registro persistente de una instalación (v0.1.27): los pasos con su
 * resultado, para enseñarlo en la ficha, en la lista y en Instalados
 * aunque la app se cierre o el temporizador complete el OBB en segundo
 * plano. `dismissed` = el usuario ya descartó el banner de "Instalación
 * completa" (el registro se conserva para la ficha).
 */
data class InstallRecord(
    val packageName: String,
    val gameName: String,
    val versionCode: Long,
    /** Tamaño descargado en bytes (0 = desconocido). */
    val downloadBytes: Long = 0,
    val downloadParts: Int = 0,
    val downloadDone: Boolean = false,
    val extractDone: Boolean = false,
    /** Velocidad media de la última extracción, MB/s (0 = sin medir). */
    val extractSpeedMBps: Double = 0.0,
    val apkInstalled: Boolean = false,
    val apkInstalledAtMs: Long = 0,
    val obbState: ObbRecordState = ObbRecordState.NO_OBB,
    val obbFiles: List<ObbFileInfo> = emptyList(),
    val obbCopiedBytes: Long = 0,
    val obbError: String = "",
    val obbCompletedAtMs: Long = 0,
    /** v0.1.30: la copia escribió sin error pero el tamaño del destino
     *  no se pudo comprobar por lectura (stat/lectura denegados en
     *  Horizon): "copiado, sin verificar". */
    val obbUnverified: Boolean = false,
    val state: InstallRecordState = InstallRecordState.IN_PROGRESS,
    val error: String = "",
    val updatedAtMs: Long = 0,
    val dismissed: Boolean = false,
    /** v0.1.29: el instalador del sistema rechazó el APK con
     *  INSTALL_FAILED_UPDATE_INCOMPATIBLE (ya hay una versión instalada
     *  con otra firma). La UI ofrece desinstalar la versión actual. */
    val signatureMismatch: Boolean = false,
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
)

/** Origen de instalación de un paquete en el visor (Meta Store oficial vs GiraQuest vs externo). */
enum class InstallOrigin {
    NONE,
    GIRAQUEST,
    META_STORE,
    OTHER,
}
