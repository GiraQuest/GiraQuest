package es.girarkas.giraquest.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Descubrimiento mDNS de los servicios de depuración inalámbrica, como
 * hacen Shizuku (AdbMdns) y LADB (NsdManager):
 *
 *  - `_adb-tls-pairing._tcp` — solo existe mientras el usuario tiene abierto
 *    el diálogo "Emparejar dispositivo con código" en Ajustes. Da el puerto
 *    de emparejamiento (cambia cada vez que se abre el diálogo).
 *  - `_adb-tls-connect._tcp` — existe mientras la depuración inalámbrica
 *    está activa. Da el puerto de conexión (estable hasta que se apague).
 *
 * El puerto se descubre solo; el código de 6 dígitos NO (lo escribe el
 * usuario una vez, vía notificación).
 */
object AdbPortDiscovery {

    const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
    const val CONNECT_SERVICE = "_adb-tls-connect._tcp."

    /**
     * v0.1.25: "Wireless ADB" clásico (Quest). Cuando el interruptor
     * "Wireless ADB" de las Opciones de desarrollador está activo, el
     * adbd escucha en el puerto 5555 y anuncia `_adb._tcp` (sin TLS,
     * con aviso de autorización en el visor la primera vez).
     */
    const val TCP_SERVICE = "_adb._tcp."

    data class Endpoint(val host: String, val port: Int)

    /**
     * Espera hasta [timeoutMs] a que aparezca el servicio y devuelve su
     * endpoint, o null si no aparece (p. ej. depuración inalámbrica apagada
     * o diálogo de emparejamiento cerrado).
     */
    suspend fun discover(
        context: Context,
        serviceType: String,
        timeoutMs: Long = 30_000,
    ): Endpoint? = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val mainHandler = Handler(Looper.getMainLooper())
        var done = false
        var listener: NsdManager.DiscoveryListener? = null
        val finish: (Endpoint?) -> Unit = { ep ->
            if (!done) {
                done = true
                try {
                    listener?.let { nsd.stopServiceDiscovery(it) }
                } catch (_: Exception) {
                }
                if (cont.isActive) cont.resume(ep)
            }
        }
        val timeoutRunnable = Runnable { finish(null) }
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: "127.0.0.1"
                        mainHandler.removeCallbacks(timeoutRunnable)
                        finish(Endpoint(host, serviceInfo.port))
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                mainHandler.removeCallbacks(timeoutRunnable)
                finish(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        cont.invokeOnCancellation {
            mainHandler.removeCallbacks(timeoutRunnable)
            try {
                listener?.let { nsd.stopServiceDiscovery(it) }
            } catch (_: Exception) {
            }
        }
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener!!)
        } catch (_: Exception) {
            finish(null)
        }
    }

    suspend fun discoverPairing(context: Context, timeoutMs: Long = 60_000): Endpoint? =
        discover(context, PAIRING_SERVICE, timeoutMs)

    suspend fun discoverConnect(context: Context, timeoutMs: Long = 15_000): Endpoint? =
        discover(context, CONNECT_SERVICE, timeoutMs)

    /** v0.1.25: servicio clásico `_adb._tcp` (puerto 5555, modo TCP). */
    suspend fun discoverTcp(context: Context, timeoutMs: Long = 10_000): Endpoint? =
        discover(context, TCP_SERVICE, timeoutMs)
}
