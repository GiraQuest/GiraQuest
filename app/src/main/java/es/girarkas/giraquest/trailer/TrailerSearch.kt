package es.girarkas.giraquest.trailer

import es.girarkas.giraquest.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Búsqueda de tráilers en YouTube — port literal del PC
 * (MainForm.cs ResolveVideoIdAsync / CleanGameNameForSearch /
 * ExtractBestVideoId / ExtractVideoId, 6300-6760).
 *
 * Los tráilers NO vienen guardados por juego en el repo: se buscan en vivo.
 *  - Término: "{nombre limpio} VR trailer"
 *  - URL: https://www.youtube.com/results?search_query=...
 *  - Se extrae el primer ID de vídeo de 11 caracteres tras /watch?v=,
 *    puntuando +25 los títulos con "trailer"/"teaser".
 *  - Ajuste TrailersEnabled (como settings.TrailersEnabled del PC).
 *  - Si no hay tráiler: "No Trailer found", sin romper la ficha.
 *  - Caché por nombre de juego (incluye el resultado vacío, para no
 *    re-buscar lo que no existe).
 *
 * Reproducción: WebView con la IFrame API de YouTube en un player.html
 * local (como WebView2 + player.html del PC) — ver TrailerActivity.
 */
class TrailerSearch(private val settings: SettingsStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, String?>()

    /** Resuelve el ID de YouTube (11 caracteres) del tráiler de un juego, o null. */
    suspend fun resolveVideoId(gameName: String): String? = withContext(Dispatchers.IO) {
        if (!settings.trailersEnabled) return@withContext null
        if (gameName.isBlank()) return@withContext null
        cache[gameName]?.let { return@withContext it }
        val result = try {
            val cleaned = cleanGameNameForSearch(gameName)
            val searchTerm = "$cleaned VR trailer"
            val query = URLEncoder.encode(searchTerm, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$query"
            val req = Request.Builder().url(searchUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; rv:109.0) Gecko/20100101 Firefox/119.0"
                )
                .build()
            val html = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null.also { cache[gameName] = null }
                resp.body?.string() ?: ""
            }
            extractBestVideoId(html, cleaned).ifBlank { null }
        } catch (_: Exception) {
            null // como el PC: swallow
        }
        cache[gameName] = result
        result
    }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    /**
     * CleanGameNameForSearch del PC: quita (paréntesis), [corchetes],
     * números de versión (v1.0, 1.35.0…), puntuación final y espacios dobles.
     */
    fun cleanGameNameForSearch(gameName: String): String {
        if (gameName.isBlank()) return gameName
        var cleaned = gameName
        val patternsToRemove = listOf(
            Regex("""\s*\([^)]+\)"""), // (anything in parentheses)
            Regex("""\s*\[[^\]]*\]"""), // [anything in brackets]
            Regex("""\s*v?\d+\.\d+[\d.]*\b""", RegexOption.IGNORE_CASE), // versiones
        )
        for (pattern in patternsToRemove) cleaned = pattern.replace(cleaned, "")
        cleaned = Regex("""[-:,]+$""").replace(cleaned, "")
        cleaned = Regex("""\s+""").replace(cleaned, " ").trim()
        return cleaned.ifBlank { gameName.trim() }
    }

    /**
     * ExtractBestVideoId del PC, con su regex VideoDataRegex y su scoring:
     *  - Solo cuentan vídeos cuyo título contiene TODAS las palabras del juego.
     *  - Solo los 10 primeros.
     *  - Posición: 1º +30, 2º +20, 3º +10.
     *  - trailer/teaser +25, official +20, launch/release +15,
     *    announce/reveal +12, gameplay/vr +5.
     *  - −3 por cada palabra extra del título.
     *  - review/tutorial/how to/install/guide/setup/reaction −30.
     *  - Fallback sin matches: primer /watch?v= de 11 caracteres.
     */
    fun extractBestVideoId(html: String, cleanedGameName: String): String {
        if (html.isEmpty()) return ""
        val videoMatches = videoDataRegex.findAll(html).toList()
        if (videoMatches.isEmpty()) {
            // ExtractVideoId del PC.
            val simple = Regex("""/watch\?v=([A-Za-z0-9_\-]{11})""").find(html)
            return simple?.groupValues?.get(1) ?: ""
        }
        val lowerGameName = cleanedGameName.lowercase().replace("'", "")
        val gameWords = lowerGameName.split(' ', '-', ':', '&').filter { it.isNotEmpty() }
        var bestVideoId: String? = null
        var bestScore = 0
        var position = 0
        for (match in videoMatches) {
            val videoId = match.groupValues[1]
            var title = match.groupValues[2].lowercase()
            title = unicodeEscapeRegex.replace(title) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
            val normalizedTitle = title.replace("'", "")
            if (!gameWords.all { normalizedTitle.contains(it) }) continue
            position++
            if (position > 10) break
            var score = 0
            if (position == 1) score += 30 else if (position == 2) score += 20 else if (position == 3) score += 10
            if (title.contains("trailer") || title.contains("teaser")) score += 25
            if (title.contains("official")) score += 20
            if (title.contains("launch") || title.contains("release")) score += 15
            if (title.contains("announce") || title.contains("reveal")) score += 12
            if (title.contains("gameplay") || title.contains("vr")) score += 5
            val totalWords = title.split(' ', '-', '|', ':', '–').filter { it.isNotEmpty() }.size
            val extraWords = totalWords - gameWords.size
            score += extraWords * -3
            if (title.contains("review") || title.contains("tutorial") ||
                title.contains("how to") || title.contains("install") ||
                title.contains("guide") || title.contains("setup") ||
                title.contains("reaction")
            ) score -= 30
            if (score > bestScore) {
                bestScore = score
                bestVideoId = videoId
            }
        }
        return bestVideoId ?: ""
    }

    companion object {
        // VideoDataRegex del PC (videoRenderer con videoId + título).
        private val videoDataRegex = Regex(
            """"videoRenderer"\s*:\s*\{\s*[^}]*?"videoId"\s*:\s*"([A-Za-z0-9_\-]{11})"[\s\S]*?"title"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"([^"]+)""""
        )
        private val unicodeEscapeRegex = Regex("""\\u([0-9A-Fa-f]{4})""")
    }
}
