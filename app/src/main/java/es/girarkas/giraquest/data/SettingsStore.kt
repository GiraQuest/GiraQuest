package es.girarkas.giraquest.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Ajustes persistentes de GiraQuest en SharedPreferences.
 */
class SettingsStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("giraquest", Context.MODE_PRIVATE)

    /** URLs de repos añadidos por el usuario. */
    var repoUrls: List<String>
        get() = prefs.getString("repo_urls", null)?.let { raw ->
            (0 until JSONArray(raw).length()).map { JSONArray(raw).getString(it) }
        } ?: emptyList()
        set(v) = prefs.edit().putString("repo_urls", JSONArray(v).toString()).apply()

    /** Repositorios VRP personalizados añadidos por el usuario ({baseUri, password, name, apiKey}). */
    var customVrpRepos: List<CustomVrpRepo>
        get() {
            val raw = prefs.getString("custom_vrp_repos", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val baseUri = o.optString("baseUri").trim().trimEnd('/')
                    if (baseUri.isBlank()) return@mapNotNull null
                    CustomVrpRepo(
                        baseUri = baseUri,
                        password = o.optString("password", ""),
                        name = o.optString("name", ""),
                        apiKey = o.optString("apiKey", ""),
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { r ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("baseUri", r.normalizedBaseUri)
                        put("password", r.password)
                        put("name", r.name)
                        if (r.apiKey.isNotBlank()) put("apiKey", r.apiKey)
                    }
                )
            }
            prefs.edit().putString("custom_vrp_repos", arr.toString()).apply()
        }

    fun addCustomVrpRepo(repo: CustomVrpRepo) {
        val norm = repo.normalizedBaseUri
        val list = customVrpRepos.filterNot { it.normalizedBaseUri.equals(norm, ignoreCase = true) }.toMutableList()
        list.add(repo.copy(baseUri = norm))
        customVrpRepos = list
    }

    fun removeCustomVrpRepo(baseUri: String) {
        val norm = baseUri.trim().trimEnd('/')
        customVrpRepos = customVrpRepos.filterNot { it.normalizedBaseUri.equals(norm, ignoreCase = true) }
    }

    var favorites: Set<String>
        get() = favoritesCache ?: (prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()).also {
            favoritesCache = it
        }
        set(v) {
            val copy = v.toSet()
            favoritesCache = copy
            prefs.edit().putStringSet("favorites", copy).apply()
        }

    fun isFavorite(packageName: String): Boolean = favorites.contains(packageName)

    fun toggleFavorite(packageName: String) {
        val set = favorites.toMutableSet()
        if (!set.add(packageName)) set.remove(packageName)
        favorites = set
    }

    /** Carpeta de descargas (por defecto, Download/GiraQuest). */
    var downloadFolder: String
        get() = prefs.getString("download_folder", DEFAULT_DOWNLOAD_FOLDER) ?: DEFAULT_DOWNLOAD_FOLDER
        set(v) = prefs.edit().putString("download_folder", v).apply()

    /** Límite de ancho de banda en KB/s. 0 = sin límite. */
    var bandwidthLimitKbps: Int
        get() = prefs.getInt("bandwidth_limit_kbps", 0)
        set(v) = prefs.edit().putInt("bandwidth_limit_kbps", v).apply()

    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("auto_update_check", true)
        set(v) = prefs.edit().putBoolean("auto_update_check", v).apply()

    /** Onboarding de permisos de primer arranque. */
    var permissionsOnboardingDone: Boolean
        get() = prefs.getBoolean("permissions_onboarding_done", false)
        set(v) = prefs.edit().putBoolean("permissions_onboarding_done", v).apply()

    /** Versión síncrona (commit) usada justo antes de reiniciar el proceso. */
    fun setPermissionsOnboardingDoneSync(done: Boolean) {
        prefs.edit().putBoolean("permissions_onboarding_done", done).commit()
    }

    /** Marca temporal del último reinicio automático para aplicar
     *  MOUNT_EXTERNAL_INSTALLER tras conceder REQUEST_INSTALL_PACKAGES. */
    val lastMountRestartMs: Long
        get() = prefs.getLong("last_mount_restart_ms", 0L)

    fun markMountRestartNow() {
        prefs.edit().putLong("last_mount_restart_ms", System.currentTimeMillis()).commit()
    }

    /** Tráilers de YouTube activados (como settings.TrailersEnabled del PC). */
    var trailersEnabled: Boolean
        get() = prefs.getBoolean("trailers_enabled", true)
        set(v) = prefs.edit().putBoolean("trailers_enabled", v).apply()

    /** Edición sin repositorio incrustado: siempre desactivado. */
    var publicRepoEnabled: Boolean
        get() = false
        set(_) {}

    /** Pase de Cloudflare: cookie `cf_clearance` del mirror. */
    var cfClearanceCookie: String
        get() = prefs.getString("cf_clearance_cookie", "") ?: ""
        set(v) = prefs.edit().putString("cf_clearance_cookie", v).apply()

    /** User-Agent del WebView que consiguió la cookie. */
    var cfUserAgent: String
        get() = prefs.getString("cf_user_agent", "") ?: ""
        set(v) = prefs.edit().putString("cf_user_agent", v).apply()

    /** Repo de GitHub del propio gestor para auto-update (editable). */
    var updateRepo: String
        get() = prefs.getString("update_repo", DEFAULT_UPDATE_REPO) ?: DEFAULT_UPDATE_REPO
        set(v) = prefs.edit().putString("update_repo", v).apply()

    /** Versiones instaladas por nosotros (paquete -> versionCode), para saber
     *  qué es "nuestro" en la vista de instaladas y para el estado de lista. */
    fun setInstalledVersion(packageName: String, versionCode: Long) {
        prefs.edit().putLong("installed_$packageName", versionCode).apply()
    }

    fun getInstalledVersion(packageName: String): Long =
        prefs.getLong("installed_$packageName", -1)

    /** Paquetes con versión registrada por nosotros (v0.1.28): para
     *  listar "Instaladas" sin depender solo de la enumeración del
     *  PackageManager, filtrada en API 30+ sin QUERY_ALL_PACKAGES.
     *  Cada paquete se verifica después con getPackageInfo(). */
    fun installedPackageNames(): Set<String> =
        prefs.all.keys
            .filter { it.startsWith("installed_") }
            .map { it.removePrefix("installed_") }
            .filter { it.isNotBlank() }
            .toSet()

    fun clearInstalledVersion(packageName: String) {
        prefs.edit().remove("installed_$packageName").apply()
    }

    /** OBBs pendientes de copiar (v0.1.19): APK instalado por fallback
     *  (sin ADB) o copia del OBB fallida. Se guardan como JSON
     *  (paquete, carpeta de staging en Descargas, versionCode, nombre).
     *  La app los completa sola en cuanto el ADB vuelve a estar vivo.
     *  v0.1.27: también la lista completa de .obb (nombre, ruta
     *  relativa, tamaño) y el motivo del último fallo, para que el
     *  reintento cubra todos y la UI enseñe el motivo. Las entradas
     *  viejas (sin esos campos) se leen con lista vacía y sin motivo. */
    var pendingObbs: List<PendingObb>
        get() {
            pendingObbsCache?.let { return it }
            val raw = prefs.getString("pending_obbs", null) ?: return emptyList<PendingObb>().also {
                pendingObbsCache = it
            }
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val pkg = o.optString("packageName").ifBlank { return@mapNotNull null }
                    val dir = o.optString("stagingDir").ifBlank { return@mapNotNull null }
                    val files = mutableListOf<ObbFileInfo>()
                    val farr = o.optJSONArray("obbFiles")
                    if (farr != null) {
                        for (j in 0 until farr.length()) {
                            val fo = farr.optJSONObject(j) ?: continue
                            val n = fo.optString("name")
                            if (n.isBlank()) continue
                            files.add(
                                ObbFileInfo(
                                    name = n,
                                    relativePath = fo.optString("relativePath", n),
                                    size = fo.optLong("size", 0),
                                )
                            )
                        }
                    }
                    PendingObb(
                        packageName = pkg,
                        stagingDir = dir,
                        versionCode = o.optLong("versionCode", 0),
                        gameName = o.optString("gameName", pkg),
                        obbFiles = files,
                        lastError = o.optString("lastError", ""),
                        failedAttempts = o.optInt("failedAttempts", 0),
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }.also { pendingObbsCache = it }
        }
        set(v) {
            val copy = v.toList()
            pendingObbsCache = copy
            val arr = JSONArray()
            copy.forEach { p ->
                arr.put(
                    org.json.JSONObject().apply {
                        put("packageName", p.packageName)
                        put("stagingDir", p.stagingDir)
                        put("versionCode", p.versionCode)
                        put("gameName", p.gameName)
                        put("lastError", p.lastError)
                        put("failedAttempts", p.failedAttempts)
                        val farr = JSONArray()
                        p.obbFiles.forEach { f ->
                            farr.put(
                                org.json.JSONObject().apply {
                                    put("name", f.name)
                                    put("relativePath", f.relativePath)
                                    put("size", f.size)
                                }
                            )
                        }
                        put("obbFiles", farr)
                    }
                )
            }
            prefs.edit().putString("pending_obbs", arr.toString()).apply()
        }

    /** Añade (o actualiza) un OBB pendiente para un paquete. */
    fun addPendingObb(p: PendingObb) {
        val list = pendingObbs.filter { it.packageName != p.packageName }.toMutableList()
        list.add(p)
        pendingObbs = list
    }

    fun removePendingObb(packageName: String) {
        pendingObbs = pendingObbs.filter { it.packageName != packageName }
    }

    fun pendingObbFor(packageName: String): PendingObb? =
        pendingObbs.firstOrNull { it.packageName == packageName }

    // ---- Registros de instalación (v0.1.27) ----
    // Un solo estado final claro y persistente por juego (descarga →
    // extracción → APK → OBB). Se guarda como JSON en preferencias.
    private fun installRecordToJson(r: InstallRecord): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("packageName", r.packageName)
            put("gameName", r.gameName)
            put("versionCode", r.versionCode)
            put("downloadBytes", r.downloadBytes)
            put("downloadParts", r.downloadParts)
            put("downloadDone", r.downloadDone)
            put("extractDone", r.extractDone)
            put("extractSpeedMBps", r.extractSpeedMBps)
            put("apkInstalled", r.apkInstalled)
            put("apkInstalledAtMs", r.apkInstalledAtMs)
            put("obbState", r.obbState.name)
            put("obbCopiedBytes", r.obbCopiedBytes)
            put("obbError", r.obbError)
            put("obbCompletedAtMs", r.obbCompletedAtMs)
            put("obbUnverified", r.obbUnverified)
            put("state", r.state.name)
            put("error", r.error)
            put("signatureMismatch", r.signatureMismatch)
            put("updatedAtMs", r.updatedAtMs)
            put("dismissed", r.dismissed)
            val farr = JSONArray()
            r.obbFiles.forEach { f ->
                farr.put(
                    org.json.JSONObject().apply {
                        put("name", f.name)
                        put("relativePath", f.relativePath)
                        put("size", f.size)
                    }
                )
            }
            put("obbFiles", farr)
        }

    private fun installRecordFromJson(o: org.json.JSONObject): InstallRecord? {
        val pkg = o.optString("packageName").ifBlank { return null }
        val files = mutableListOf<ObbFileInfo>()
        val farr = o.optJSONArray("obbFiles")
        if (farr != null) {
            for (j in 0 until farr.length()) {
                val fo = farr.optJSONObject(j) ?: continue
                val n = fo.optString("name")
                if (n.isBlank()) continue
                files.add(
                    ObbFileInfo(
                        name = n,
                        relativePath = fo.optString("relativePath", n),
                        size = fo.optLong("size", 0),
                    )
                )
            }
        }
        return InstallRecord(
            packageName = pkg,
            gameName = o.optString("gameName", pkg),
            versionCode = o.optLong("versionCode", 0),
            downloadBytes = o.optLong("downloadBytes", 0),
            downloadParts = o.optInt("downloadParts", 0),
            downloadDone = o.optBoolean("downloadDone", false),
            extractDone = o.optBoolean("extractDone", false),
            extractSpeedMBps = o.optDouble("extractSpeedMBps", 0.0),
            apkInstalled = o.optBoolean("apkInstalled", false),
            apkInstalledAtMs = o.optLong("apkInstalledAtMs", 0),
            obbState = try {
                ObbRecordState.valueOf(o.optString("obbState", "NO_OBB"))
            } catch (_: Exception) {
                ObbRecordState.NO_OBB
            },
            obbFiles = files,
            obbCopiedBytes = o.optLong("obbCopiedBytes", 0),
            obbError = o.optString("obbError", ""),
            obbCompletedAtMs = o.optLong("obbCompletedAtMs", 0),
            obbUnverified = o.optBoolean("obbUnverified", false),
            state = try {
                InstallRecordState.valueOf(o.optString("state", "IN_PROGRESS"))
            } catch (_: Exception) {
                InstallRecordState.IN_PROGRESS
            },
            error = o.optString("error", ""),
            signatureMismatch = o.optBoolean("signatureMismatch", false),
            updatedAtMs = o.optLong("updatedAtMs", 0),
            dismissed = o.optBoolean("dismissed", false),
        )
    }

    var installRecords: List<InstallRecord>
        get() {
            installRecordsCache?.let { return it }
            val raw = prefs.getString("install_records", null) ?: return emptyList<InstallRecord>().also {
                installRecordsCache = it
            }
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { installRecordFromJson(it) }
                }
            } catch (_: Exception) {
                emptyList()
            }.also { installRecordsCache = it }
        }
        set(v) {
            val copy = v.toList()
            installRecordsCache = copy
            val arr = JSONArray()
            copy.forEach { arr.put(installRecordToJson(it)) }
            prefs.edit().putString("install_records", arr.toString()).apply()
        }

    fun getInstallRecord(packageName: String): InstallRecord? =
        installRecords.firstOrNull { it.packageName == packageName }

    fun saveInstallRecord(r: InstallRecord) {
        val list = installRecords.filter { it.packageName != r.packageName }.toMutableList()
        list.add(r)
        installRecords = list
    }

    fun dismissInstallRecord(packageName: String) {
        getInstallRecord(packageName)?.let { saveInstallRecord(it.copy(dismissed = true)) }
    }

    /** v0.1.30: borra el registro de instalación de un juego
     *  (desinstalación: no queda ni versión ni estado viejo que
     *  confunda a la lista). */
    fun removeInstallRecord(packageName: String) {
        installRecords = installRecords.filter { it.packageName != packageName }
    }

    // ---- Tamaño de descarga por juego (v0.1.27) ----
    // El índice no trae tamaños; se consulta bajo demanda (listado +
    // HEADs) y se guarda aquí por paquete+versión, para que la segunda
    // vez sea instantáneo y la confirmación lo enseñe siempre. La clave
    // lleva la versión: si el juego se actualiza, el tamaño se vuelve a
    // consultar (el de la versión vieja ya no vale).
    fun cachedGameSize(packageName: String, versionCode: Long): Pair<Long, Int>? {
        val raw = prefs.getString("game_sizes", null) ?: return null
        return try {
            val o = org.json.JSONObject(raw)
            val e = o.optJSONObject("$packageName|$versionCode") ?: return null
            val bytes = e.optLong("bytes", 0)
            if (bytes <= 0) null else bytes to e.optInt("parts", 0)
        } catch (_: Exception) {
            null
        }
    }

    fun cacheGameSize(packageName: String, versionCode: Long, bytes: Long, parts: Int) {
        if (bytes <= 0) return
        try {
            val raw = prefs.getString("game_sizes", null)
            val o = if (raw.isNullOrBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
            o.put(
                "$packageName|$versionCode",
                org.json.JSONObject().apply {
                    put("bytes", bytes)
                    put("parts", parts)
                }
            )
            prefs.edit().putString("game_sizes", o.toString()).apply()
        } catch (_: Exception) {
        }
    }

    // ---- Orden de la lista (v0.1.27) ----
    /** Orden elegido en "Ordenar por": INDEX, POPULAR, AZ, NEW, SIZE. */
    var sortOrder: String
        get() = prefs.getString("sort_order", "INDEX") ?: "INDEX"
        set(v) = prefs.edit().putString("sort_order", v).apply()

    /** Dirección del orden por tamaño (v0.1.40): true = menor a mayor (↑), false = mayor a menor (↓). */
    var sortSizeAsc: Boolean
        get() = prefs.getBoolean("sort_size_asc", true)
        set(v) = prefs.edit().putBoolean("sort_size_asc", v).apply()

    /** Mapa completo de tamaños ya consultados por "$packageName|$versionCode" (v0.1.40). */
    fun allCachedGameSizes(): Map<String, Long> {
        val raw = prefs.getString("game_sizes", null) ?: return emptyMap()
        return try {
            val o = org.json.JSONObject(raw)
            val out = HashMap<String, Long>(o.length())
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val bytes = o.optJSONObject(k)?.optLong("bytes", 0L) ?: 0L
                if (bytes > 0L) out[k] = bytes
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---- Snapshot del índice para "Nuevos" (v0.1.27) ----
    // El índice no trae fechas: "nuevo" = paquete que no estaba en la
    // última carga. El snapshot vive en un fichero (3.018 paquetes, ~
    // 100 KB: demasiado para SharedPreferences). La primera carga no
    // tiene base: guarda el snapshot en silencio y no marca nada nuevo.
    private val indexSnapshotFile: java.io.File
        get() = java.io.File(context.filesDir, "index_snapshot.json")

    fun loadIndexSnapshot(): Set<String> {
        return try {
            val f = indexSnapshotFile
            if (!f.exists()) return emptySet()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun saveIndexSnapshot(packages: Set<String>) {
        try {
            indexSnapshotFile.writeText(JSONArray(packages.toList()).toString())
        } catch (_: Exception) {
        }
    }

    /** Compara con el snapshot anterior y lo actualiza. Devuelve los
     *  paquetes nuevos (vacío en la primera carga: sin base no hay
     *  "nuevos"). */
    fun diffIndexSnapshot(current: Set<String>): Set<String> {
        val prev = loadIndexSnapshot()
        saveIndexSnapshot(current)
        if (prev.isEmpty()) return emptySet()
        return current - prev
    }

    /** Último intento de completar OBBs pendientes (para no reintentar
     *  en cada onResume: el intento con ADB muerto es caro — probe +
     *  mDNS — y no tiene sentido repetirlo cada pocos segundos). */
    var lastObbRetryMs: Long
        get() = prefs.getLong("last_obb_retry_ms", 0)
        set(v) = prefs.edit().putLong("last_obb_retry_ms", v).apply()

    // ---- Notificaciones de "instalado por completo" ya enviadas (v0.1.33) ----
    // La v0.1.32 avisaba ~30 veces por juego: el temporizador
    // re-descubría cada minuto un juego ya completo (la reparación
    // encontraba su staging en disco) y markObbCompleted notificaba de
    // nuevo. Ahora cada aviso se anota UNA VEZ por (paquete, versión,
    // tipo) y no se repite: la clave es "$paquete@$versionCode:$tipo"
    // ("complete" o "failed"). Al desinstalar el juego se borran sus
    // claves, así que una reinstalación de la misma versión vuelve a
    // avisar. Lista ordenada (JSONArray) con tope de 200 para que no
    // crezca sin límite.
    private fun notifiedInstallKeys(): MutableList<String> {
        val raw = prefs.getString("notified_installs", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapTo(mutableListOf()) { arr.getString(it) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun wasInstallNotified(packageName: String, versionCode: Long, kind: String): Boolean =
        notifiedInstallKeys().contains("$packageName@$versionCode:$kind")

    fun markInstallNotified(packageName: String, versionCode: Long, kind: String) {
        val keys = notifiedInstallKeys()
        val key = "$packageName@$versionCode:$kind"
        if (key in keys) return
        keys.add(key)
        while (keys.size > 200) keys.removeAt(0)
        prefs.edit().putString("notified_installs", JSONArray(keys).toString()).apply()
    }

    fun clearNotifiedInstalls(packageName: String) {
        val keys = notifiedInstallKeys().filterNot { it.startsWith("$packageName@") }
        prefs.edit().putString("notified_installs", JSONArray(keys).toString()).apply()
    }

    /** v0.1.29: URI del árbol SAF de /Android/obb concedido por el
     *  usuario (ACTION_OPEN_DOCUMENT_TREE, permiso persistido). Es la
     *  vía de escape cuando la I/O directa de archivos falla con EACCES
     *  en /Android/obb/<paquete> aunque MANAGE_EXTERNAL_STORAGE esté
     *  concedido (el FUSE de Horizon no deja ver ni escribir esos
     *  directorios con File API tras una instalación limpia). Vacío =
     *  no concedido. */
    var obbSafTreeUri: String
        get() = prefs.getString("obb_saf_tree_uri", "") ?: ""
        set(v) = prefs.edit().putString("obb_saf_tree_uri", v).apply()

    /** v0.1.30: QUÉ árbol SAF se concedió (lo detecta ObbSaf.detectKind
     *  al concederlo; "obb" = la carpeta Android/obb, "root" = la raíz
     *  del almacenamiento, "other" = otra carpeta, "" = sin conceder).
     *  El selector de Horizon puede NO dejar elegir Android/obb
     *  (Android 11+ lo bloquea): con la raíz concedida se llega desde
     *  ahí a Android/obb con DocumentFile. Sin este dato, pkgDir/listDir
     *  no sabrían desde dónde navegar. */
    var obbSafTreeKind: String
        get() = prefs.getString("obb_saf_tree_kind", "") ?: ""
        set(v) = prefs.edit().putString("obb_saf_tree_kind", v).apply()

    /** Emparejamiento ADB persistido: puerto de conexión y host. */
    var adbPaired: Boolean
        get() = prefs.getBoolean("adb_paired", false)
        set(v) = prefs.edit().putBoolean("adb_paired", v).apply()

    var adbConnectHost: String
        get() = prefs.getString("adb_connect_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = prefs.edit().putString("adb_connect_host", v).apply()

    var adbConnectPort: Int
        get() = prefs.getInt("adb_connect_port", -1)
        set(v) = prefs.edit().putInt("adb_connect_port", v).apply()

    var adbPairingHost: String
        get() = prefs.getString("adb_pairing_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = prefs.edit().putString("adb_pairing_host", v).apply()

    var adbPairingPort: Int
        get() = prefs.getInt("adb_pairing_port", -1)
        set(v) = prefs.edit().putInt("adb_pairing_port", v).apply()

    var writeSecureSettingsGranted: Boolean
        get() = prefs.getBoolean("write_secure_settings", false)
        set(v) = prefs.edit().putBoolean("write_secure_settings", v).apply()

    /**
     * Modo ADB (v0.1.25, Quest): "tls" = depuración inalámbrica con
     * emparejamiento por código (puerto _adb-tls-connect, el de siempre);
     * "tcp" = "Wireless ADB" clásico en el puerto 5555 (sin código, con
     * aviso de autorización en el visor la primera vez). En Quest el
     * modo TLS vive en las Opciones de desarrollador de Android
     * (botón del paso 2 del asistente, que intenta abrir los
     * Ajustes AOSP directamente) y el modo TCP en el interruptor
     * "Wireless ADB" del mismo menú.
     */
    var adbMode: String
        get() = prefs.getString("adb_mode", "tls") ?: "tls"
        set(v) = prefs.edit().putString("adb_mode", v).apply()

    /** Endpoint del modo TCP 5555 (v0.1.25). Por defecto el propio visor. */
    var adbTcpHost: String
        get() = prefs.getString("adb_tcp_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = prefs.edit().putString("adb_tcp_host", v).apply()

    var adbTcpPort: Int
        get() = prefs.getInt("adb_tcp_port", 5555)
        set(v) = prefs.edit().putInt("adb_tcp_port", v).apply()

    /**
     * ¿Aceptó el visor la clave en el modo TCP? (v0.1.25). La primera
     * conexión TCP muestra el aviso de autorización en el visor y hay
     * que aceptarlo; después la clave queda en /data/misc/adb/adb_keys
     * y las conexiones siguientes ya no piden nada.
     */
    var adbTcpAuthorized: Boolean
        get() = prefs.getBoolean("adb_tcp_authorized", false)
        set(v) = prefs.edit().putBoolean("adb_tcp_authorized", v).apply()

    companion object {
        const val DEFAULT_DOWNLOAD_FOLDER = "Download/GiraQuest"
        const val DEFAULT_UPDATE_REPO = "girarkas/giraquest"
        @Volatile
        private var favoritesCache: Set<String>? = null
        @Volatile
        private var pendingObbsCache: List<PendingObb>? = null
        @Volatile
        private var installRecordsCache: List<InstallRecord>? = null
    }
}
