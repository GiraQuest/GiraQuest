package es.girarkas.giraquest.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore

/**
 * Notificación de sistema al completarse TODA la cadena de instalación
 * (v0.1.27, requisito de Edu): descarga → extracción → APK → OBB.
 * También cuando la completa el temporizador en segundo plano, para
 * que se entere aunque esté fuera de la app. Canal propio, solo
 * informativa: sin sonido ni vibración. Si las notificaciones están
 * desactivadas para la app, no se hace nada (queda en el log).
 *
 * POST_NOTIFICATIONS ya está declarado en el manifiesto; en Quest 3
 * (API 34, targetSdk 32) el permiso se concede al instalar por ser
 * target < 33.
 */
object InstallNotifier {

    private const val CHANNEL_ID = "giraquest_install"
    private const val NOTIF_ID_BASE = 10_000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_install_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_install_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        } catch (e: Throwable) {
            StartupLog.mark(context, "notif: no se pudo crear el canal (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    fun notifyComplete(context: Context, packageName: String, versionCode: Long, gameName: String, detail: String) {
        val settings = SettingsStore(context)
        if (settings.wasInstallNotified(packageName, versionCode, "complete")) {
            StartupLog.mark(context, "notif: ya avisado «$gameName instalado por completo» ($packageName v$versionCode): no se repite")
            return
        }
        settings.markInstallNotified(packageName, versionCode, "complete")
        post(
            context,
            "install_complete:$gameName",
            context.getString(R.string.notif_install_complete_title, gameName),
            detail
        )
    }

    /** La cadena terminó con el OBB fallido o pendiente sin remedio.
     *  Igual que notifyComplete: una sola vez por (paquete, versión). */
    fun notifyObbFailed(context: Context, packageName: String, versionCode: Long, gameName: String, reason: String) {
        val settings = SettingsStore(context)
        if (settings.wasInstallNotified(packageName, versionCode, "failed")) {
            StartupLog.mark(context, "notif: ya avisado «$gameName: OBB sin copiar» ($packageName v$versionCode): no se repite")
            return
        }
        settings.markInstallNotified(packageName, versionCode, "failed")
        post(
            context,
            "install_obb_failed:$gameName",
            context.getString(R.string.notif_obb_missing_title, gameName),
            context.getString(R.string.notif_obb_missing_body, reason)
        )
    }

    private fun post(context: Context, tag: String, title: String, text: String) {
        try {
            ensureChannel(context)
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                StartupLog.mark(context, "notif: desactivadas para la app, no se avisa «$title»")
                return
            }
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_install)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSound(null)
                .setVibrate(null)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context)
                .notify(tag, NOTIF_ID_BASE + (tag.hashCode() and 0x7FFF), notif)
            StartupLog.mark(context, "notif: «$title» — $text")
        } catch (e: Throwable) {
            StartupLog.mark(context, "notif: no se pudo avisar (${e.javaClass.simpleName}: ${e.message})")
        }
    }
}
