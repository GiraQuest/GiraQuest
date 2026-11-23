package es.girarkas.giraquest.download

import es.girarkas.giraquest.HttpClients
import es.girarkas.giraquest.adb.AdbHealth
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.data.VrpPublicRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Progreso de descarga, como ModernQueuePanel / ModernProgessBar del PC. */
data class DownloadProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val percent: Int,
    val speedBytesPerSec: Long = 0L,
) {
    val etaSeconds: Long? = null
}

sealed class DownloadResult {
    data class Ok(val file: File) : DownloadResult()
    data class Failed(val reason: String) : DownloadResult()
}

/**
 * Downloader (equivale a la cola de descargas del PC: progreso en tiempo
 * real, reanudar con Range, limitar ancho de banda).
 *
 * - Reanuda si existe un .part parcial y el servidor acepta Range.
 * - Verifica SHA-256 si se pasa (el repo.json lo trae).
 * - Límite de ancho de banda en KB/s desde Ajustes (0 = sin límite).
 */
class Downloader(private val settings: SettingsStore) {

    // v0.1.22: TLS de la plataforma (AndroidOpenSSL), no el Conscrypt
    // empaquetado — ver HttpClients. Sin esto, tras emparejar, OkHttp
    // usaba el Conscrypt global y la descarga fallaba con
    // NoClassDefFoundError: okhttp3.internal.platform.ConscryptPlatform.
    private val client = HttpClients.applyPlatformSsl(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // descargas grandes, sin timeout de llamada
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
    ).build()

    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        expectedSize: Long = 0,
        extraHeaders: Map<String, String> = emptyMap(),
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): DownloadResult = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        // v0.1.40: si el archivo destino ya existe completo en disco, reutilizarlo sin re-descargar.
        if (dest.exists() && expectedSize > 0 && dest.length() == expectedSize) {
            val shaOk = expectedSha256 == null || sha256(dest).equals(expectedSha256, ignoreCase = true)
            if (shaOk) {
                File(dest.parentFile, dest.name + ".part").delete()
                onProgress?.invoke(DownloadProgress(expectedSize, expectedSize, 100))
                return@withContext DownloadResult.Ok(dest)
            }
        }
        val part = File(dest.parentFile, dest.name + ".part")
        var done = if (part.exists()) part.length() else 0L
        // Si el .part ya es el archivo completo, verificar y terminar.
        if (done > 0 && expectedSize > 0 && done >= expectedSize) {
            return@withContext finishPart(part, dest, expectedSha256)
        }
        // v0.1.11: UA de rclone como el PC (sin Accept: Go no lo envía).
        // Accept-Encoding: gzip lo pone solo el transporte (OkHttp y Go).
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", VrpPublicRepo.RCLONE_UA)
        for ((k, v) in extraHeaders) reqBuilder.header(k, v)
        if (done > 0) reqBuilder.header("Range", "bytes=$done-")
        try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    return@withContext DownloadResult.Failed("HTTP ${resp.code} en $url")
                }
                // El servidor ignoró el Range: empezar de cero.
                if (resp.code == 200 && done > 0) {
                    part.delete()
                    done = 0
                }
                val total = if (expectedSize > 0) expectedSize
                else (resp.header("Content-Length")?.toLongOrNull() ?: 0L) + done
                val body = resp.body ?: return@withContext DownloadResult.Failed("Sin cuerpo: $url")
                val limitBps = settings.bandwidthLimitKbps * 1024L
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(done)
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    var lastReport = System.currentTimeMillis()
                    var lastReportBytes = done
                    var emaSpeedBps = 0.0
                    val stream = body.byteStream()
                    val chunkStart = System.currentTimeMillis()
                    var chunkBytes = 0L
                    var chunkTimeStart = chunkStart
                    while (stream.read(buf).also { read = it } != -1) {
                        raf.write(buf, 0, read)
                        done += read
                        chunkBytes += read
                        // Límite de ancho de banda: dormir lo que sobre.
                        if (limitBps > 0) {
                            val elapsedMs = System.currentTimeMillis() - chunkTimeStart
                            val expectedMs = chunkBytes * 1000L / limitBps
                            if (expectedMs > elapsedMs) {
                                Thread.sleep(expectedMs - elapsedMs)
                                chunkTimeStart = System.currentTimeMillis()
                                chunkBytes = 0
                            }
                        }
                        val now = System.currentTimeMillis()
                        val dtMs = now - lastReport
                        if (dtMs >= 250) {
                            val dBytes = done - lastReportBytes
                            val instBps = (dBytes * 1000.0) / dtMs
                            emaSpeedBps = if (emaSpeedBps <= 0.0) instBps else emaSpeedBps * 0.65 + instBps * 0.35
                            lastReport = now
                            lastReportBytes = done
                            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                            onProgress?.invoke(DownloadProgress(done, total, pct, emaSpeedBps.toLong()))
                        }
                    }
                }
                onProgress?.invoke(DownloadProgress(done, total, if (total > 0) 100 else 0, 0L))
                return@withContext finishPart(part, dest, expectedSha256)
            }
        } catch (e: Throwable) {
            // Queda el .part para reanudar en el próximo intento.
            // v0.1.22: se atrapa Throwable (un NoClassDefFoundError es
            // un Error, no una Exception) y se guarda la cadena de
            // causas COMPLETA: el error de Edu (05:22) llegaba truncado
            // a solo el nombre de la clase y no se podía diagnosticar.
            if (e is CancellationException) throw e
            return@withContext DownloadResult.Failed(
                AdbHealth.fullChainReason(e) ?: e.toString()
            )
        }
    }

    private fun finishPart(part: File, dest: File, expectedSha256: String?): DownloadResult {
        if (expectedSha256 != null) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                part.delete()
                return DownloadResult.Failed(
                    "SHA-256 no coincide (esperado ${expectedSha256.take(12)}…, obtenido ${actual.take(12)}…). Archivo borrado."
                )
            }
        }
        if (dest.exists()) dest.delete()
        part.renameTo(dest)
        return DownloadResult.Ok(dest)
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
