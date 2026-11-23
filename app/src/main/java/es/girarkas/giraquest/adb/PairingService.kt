package es.girarkas.giraquest.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ACTION_PAIRING_CODE = "es.girarkas.giraquest.ACTION_PAIRING_CODE"
const val EXTRA_PAIRING_CODE = "pairing_code"
const val ACTION_PAIRING_RESULT = "es.girarkas.giraquest.ACTION_PAIRING_RESULT"
const val EXTRA_PAIRING_OK = "pairing_ok"
const val EXTRA_PAIRING_MESSAGE = "pairing_message"
const val CHANNEL_PAIRING = "pairing"
const val NOTIF_PAIRING_ID = 1001

/**
 * Crea el canal de emparejamiento si no existe (v0.1.7: lo usan también
 * el receiver y la ventana flotante, no solo el servicio).
 */
fun ensurePairingChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PAIRING,
                context.getString(R.string.notif_pairing_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.notif_pairing_channel_desc) }
        )
    }
}

/**
 * Notificación con RemoteInput para escribir el código de 6 dígitos SIN
 * salir de Ajustes (v0.1.7, vía PRINCIPAL).
 *
 * Bajar la barra de notificaciones NO cierra el diálogo "Emparejar
 * dispositivo con código" de Ajustes (la cortina es un overlay del
 * sistema); cambiar de app SÍ lo cierra, y al cerrarse el código y el
 * puerto se regeneran. Por eso el código se escribe aquí, en la
 * notificación, no en la app. El texto lleva el puerto descubierto para
 * que Edu compruebe que coincide con el de Ajustes.
 */
fun postPairingCodeNotification(context: Context, endpoint: AdbPortDiscovery.Endpoint?) {
    val text = if (endpoint != null) {
        context.getString(R.string.notif_pairing_found_port, endpoint.port)
    } else {
        context.getString(R.string.notif_pairing_found_text)
    }
    postPairingCodeNotificationText(context, text)
}

/** Variante con texto libre (p. ej. "el diálogo de Ajustes se cerró"). */
fun postPairingCodeNotificationText(context: Context, text: String) {
    ensurePairingChannel(context)
    val remoteInput = RemoteInput.Builder(EXTRA_PAIRING_CODE)
        .setLabel(context.getString(R.string.notif_pairing_code_hint))
        .build()
    val replyIntent = Intent(context, PairingCodeReceiver::class.java).apply {
        action = ACTION_PAIRING_CODE
    }
    val replyPending = PendingIntent.getBroadcast(
        context, 0, replyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    val action = NotificationCompat.Action.Builder(
        android.R.drawable.ic_input_add,
        context.getString(R.string.notif_pairing_code_action),
        replyPending
    ).addRemoteInput(remoteInput).build()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(
        NOTIF_PAIRING_ID,
        NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_pairing_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(action)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    )
}

/** Notificación de resultado (éxito o fallo con motivo). */
fun postPairingResultNotification(context: Context, ok: Boolean, message: String) {
    ensurePairingChannel(context)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(
        NOTIF_PAIRING_ID,
        NotificationCompat.Builder(context, CHANNEL_PAIRING)
            .setSmallIcon(
                if (ok) android.R.drawable.ic_dialog_info
                else android.R.drawable.ic_dialog_alert
            )
            .setContentTitle(
                if (ok) context.getString(R.string.notif_pairing_ok)
                else context.getString(R.string.notif_pairing_failed)
            )
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    )
}

/**
 * Servicio en primer plano para el emparejamiento ADB por código, con la
 * misma UX que Shizuku (AdbPairingService) y LADB:
 *
 *  1. Descubre el servicio `_adb-tls-pairing._tcp` por mDNS (puerto solo).
 *  2. Publica una notificación con RemoteInput: el usuario escribe el
 *     código de 6 dígitos que muestra Ajustes SIN salir de Ajustes y sin
 *     cerrar el diálogo (cerrarlo invalida el código).
 *  3. Al recibir el código, empareja con libadb, conecta, se concede
 *     WRITE_SECURE_SETTINGS y avisa con un broadcast.
 *
 * El código NO se detecta solo: Android no ofrece ninguna API para leerlo.
 */
class PairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var discoveryJob: Job? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensurePairingChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.1.7: solo la primera vez. Si se vuelve a llamar a
        // startForeground con la notificación de "buscando", TAPARÍA la
        // notificación con el código que ya está puesta (mismo ID).
        if (!foregroundStarted) {
            foregroundStarted = true
            startForeground(NOTIF_PAIRING_ID, buildSearchingNotification())
        }
        // Un solo bucle de descubrimiento aunque se arranque el servicio
        // varias veces (p. ej. desde el paso 2 y desde el diálogo).
        if (discoveryJob?.isActive != true) {
            discoveryJob = scope.launch(Dispatchers.IO) { discoveryLoop() }
        }
        return START_NOT_STICKY
    }

    /**
     * Bucle de descubrimiento (v0.1.7): la v0.1.6 descubría UNA vez con
     * timeout de 120 s y luego se rendía; si Edu tardaba más (o el diálogo
     * de Ajustes se cerraba y se volvía a abrir con puerto nuevo), la
     * notificación con el código nunca aparecía o quedaba con el puerto
     * viejo. Ahora el servicio vigila hasta 10 minutos: en cuanto aparece
     * el servicio de emparejamiento publica la notificación con el puerto
     * actual, la actualiza si cambia (nuevo diálogo = nuevo puerto) y
     * avisa si el diálogo de Ajustes se cierra. Al emparejar, el receiver
     * para el servicio.
     */
    private suspend fun discoveryLoop() {
        val deadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000L
        var shownEp: AdbPortDiscovery.Endpoint? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val ep = try {
                AdbPortDiscovery.discoverPairing(this@PairingService, 15_000)
            } catch (_: Exception) {
                null
            }
            if (ep != null) {
                SettingsStore(this@PairingService).apply {
                    adbPairingHost = ep.host
                    adbPairingPort = ep.port
                }
                if (ep != shownEp) {
                    shownEp = ep
                    postPairingCodeNotification(this@PairingService, ep)
                }
            } else if (shownEp != null) {
                // El diálogo de Ajustes se cerró: el puerto ya no vale.
                shownEp = null
                postPairingCodeNotificationText(
                    this@PairingService,
                    getString(R.string.notif_pairing_gone_text)
                )
            }
            // Si no hay servicio, discoverPairing ya bloqueó 15 s; si lo
            // hay, vuelve a mirar enseguida por si cambia el puerto.
            if (ep == null) delay(2_000) else delay(3_000)
        }
        // Timeout: avisar y parar (el usuario puede volver a pulsar
        // Emparejar para arrancar el servicio de nuevo).
        postPairingResultNotification(
            this@PairingService,
            false,
            getString(R.string.notif_pairing_searching_timeout)
        )
        stopSelf()
    }

    private fun buildSearchingNotification() =
        NotificationCompat.Builder(this, CHANNEL_PAIRING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_pairing_searching))
            .setContentText(getString(R.string.notif_pairing_searching_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Recibe el código escrito en la notificación y ejecuta el pairing. */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(EXTRA_PAIRING_CODE)?.toString()?.trim() ?: return
        if (!Regex("\\d{6}").matches(code)) return // el código siempre son 6 dígitos
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adb = GiraAdbManager.getInstance(context)
                // Descubrimiento fresco antes de emparejar.
                val freshEp = try {
                    AdbPortDiscovery.discoverPairing(context, 5_000)
                } catch (_: Exception) {
                    null
                }
                var paired = false
                var connected = false
                var msg: String
                if (freshEp == null) {
                    msg = context.getString(R.string.notif_pairing_no_service)
                } else {
                    val outcome = adb.pairDeviceDetailed(freshEp.host, freshEp.port, code)
                    paired = outcome.ok
                    if (!paired) {
                        msg = if (outcome.failure == GiraAdbManager.PairFailure.CONNECTION) {
                            context.getString(R.string.notif_pairing_conn_fail)
                        } else {
                            context.getString(R.string.notif_pairing_code_wrong)
                        }
                    } else {
                        msg = ""
                    }
                }
                if (paired) {
                    // Buscar el puerto de conexión y conectar sin código.
                    val connectEp = AdbPortDiscovery.discoverConnect(context, 15_000)
                    connected = if (connectEp != null) {
                        adb.connectTo(connectEp.host, connectEp.port)
                    } else {
                        adb.connectSaved()
                    }
                    msg = if (connected) {
                        // Auto-concederse WRITE_SECURE_SETTINGS para poder
                        // re-activar la depuración tras reinicios.
                        adb.grantWriteSecureSettings()
                        context.getString(R.string.notif_pairing_ok_text)
                    } else {
                        context.getString(R.string.notif_pairing_connected_fail)
                    }
                }
                postPairingResultNotification(context, paired && connected, msg)
                context.sendBroadcast(Intent(ACTION_PAIRING_RESULT).apply {
                    putExtra(EXTRA_PAIRING_OK, paired && connected)
                    // v0.1.4: motivo visible en el asistente.
                    putExtra(EXTRA_PAIRING_MESSAGE, msg)
                    setPackage(context.packageName)
                })
                if (paired && connected) {
                    context.stopService(Intent(context, PairingService::class.java))
                } else {
                    // v0.1.7: NO parar el servicio al fallar. Antes se
                    // paraba siempre y la notificación con el código
                    // desaparecía: Edu no podía reintentar sin salir de
                    // Ajustes (y salir cierra su diálogo). Ahora el
                    // servicio sigue vigilando y, pasados unos segundos
                    // para que el aviso del fallo se lea, se vuelve a
                    // publicar la notificación con el código si el
                    // diálogo de Ajustes sigue abierto.
                    StartupLog.mark(
                        context,
                        "pair notif FAILED: $msg"
                    )
                    delay(4_000)
                    val epNow = try {
                        AdbPortDiscovery.discoverPairing(context, 5_000)
                    } catch (_: Exception) {
                        null
                    }
                    if (epNow != null) postPairingCodeNotification(context, epNow)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
