package es.girarkas.giraquest.update

import android.content.Context
import es.girarkas.giraquest.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Auto-update del propio gestor comprobando GitHub Releases.
 *
 * El repo por defecto es girarkas/giraquest (editable en Ajustes).
 * Si está vacío, no se comprueba nada.
 */
class Updater(private val context: Context) {

    private val settings = SettingsStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val body: String,
        val apkUrl: String?,
        val htmlUrl: String,
    )

    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val repo = settings.updateRepo.trim()
        if (repo.isEmpty() || !repo.contains("/")) return@withContext null
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("User-Agent", "GiraQuest/0.1")
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", true)) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                ReleaseInfo(
                    tag = json.optString("tag_name"),
                    name = json.optString("name", json.optString("tag_name")),
                    body = json.optString("body", ""),
                    apkUrl = apkUrl,
                    htmlUrl = json.optString("html_url"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** true si la release es más nueva que la versión instalada. */
    fun isNewer(release: ReleaseInfo, currentVersion: String): Boolean {
        val rel = release.tag.trimStart('v', 'V')
        val cur = currentVersion.trimStart('v', 'V')
        return compareVersions(rel, cur) > 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".", "-").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
