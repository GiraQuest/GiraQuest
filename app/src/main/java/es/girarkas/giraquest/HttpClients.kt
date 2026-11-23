package es.girarkas.giraquest

import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Clientes HTTP con la TLS de la PLATAFORMA (v0.1.22).
 *
 * Motivo (log de Edu 05:22): al descargar un juego desde cero, OkHttp
 * fallaba con `NoClassDefFoundError:
 * okhttp3.internal.platform.ConscryptPlatform`. La app registraba el
 * Conscrypt empaquetado como proveedor TLS global nº 1 (en
 * GiraAdbManager.ensureAdbReady), así que `SSLContext.getInstance(
 * "TLS")` — el que usa OkHttp por defecto — devolvía contextos
 * Conscrypt y sus sockets iban por `ConscryptSocketAdapter`, que toca
 * `ConscryptPlatform`. fetchMeta a las 05:10 (antes de emparejar, sin
 * Conscrypt registrado) descargó 65 MB bien: la correlación es exacta.
 *
 * La v0.1.22 quita el registro global (libadb usa su propio Conscrypt
 * explícito para el TLS del ADB, no necesita el global) Y, como
 * defensa en profundidad, estos clientes usan EXPLÍCITAMENTE el
 * SSLContext de la plataforma (AndroidOpenSSL) con el trust manager
 * de CAs del sistema: aunque algo volviera a registrar Conscrypt
 * globalmente en el futuro, las descargas no se romperían. Si el
 * proveedor "AndroidOpenSSL" no existiera (ROM rara), se cae al
 * SSLContext por defecto; si ni eso se puede construir, se devuelve
 * null y el cliente se queda como antes (sin forzar nada).
 */
object HttpClients {

    /** (SSLSocketFactory, X509TrustManager) de la plataforma, o null. */
    fun platformSsl(): Pair<SSLSocketFactory, X509TrustManager>? {
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
                ?: return null
            val ctx = try {
                SSLContext.getInstance("TLS", "AndroidOpenSSL")
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            // El trust manager que usa el contexto es EXACTAMENTE el que
            // se le pasa a OkHttp: si no coinciden, OkHttp rechaza el
            // cliente al construirlo.
            ctx.init(null, arrayOf(tm), null)
            ctx.socketFactory to tm
        } catch (_: Exception) {
            null
        }
    }

    /** Aplica la SSL de plataforma a un builder, si se pudo construir. */
    fun applyPlatformSsl(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val (factory, tm) = platformSsl() ?: return builder
        return builder.sslSocketFactory(factory, tm)
    }
}
