package es.girarkas.giraquest.install

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import es.girarkas.giraquest.crash.StartupLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * Fallback sin ADB: PackageInstaller del sistema.
 *
 * Instala con la confirmación en pantalla de Android (a Edu le da igual
 * confirmar). Limitación honesta: en este modo el OBB NO se puede copiar
 * automáticamente (Android 11+ bloquea /Android/obb a apps normales y
 * MANAGE_EXTERNAL_STORAGE no lo permite). El Installer avisa de ello.
 *
 * v0.1.17: el resultado ya no es un Boolean mudo. Antes, "Instalación
 * cancelada o fallida." cubría TODO (usuario que cancela, falta el
 * permiso de "Instalar apps desconocidas", bloqueo del sistema, APK
 * inválido, sin espacio…) y no había forma de distinguirlo ni en
 * pantalla ni en el log. Ahora cada motivo llega con su nombre, y el
 * permiso se comprueba ANTES de crear la sesión: sin él, el commit
 * está condenado y el usuario no recibe ni el aviso para concederlo,
 * así que se le lleva directamente a Ajustes en vez de intentarlo a
 * ciegas. (El asistente pide ese permiso en su paso 4, pero Edu nunca
 * terminó el asistente — el emparejamiento quedó pendiente — así que
 * es muy probable que no lo tenga concedido.)
 */
object PackageInstallerFallback {

    private const val ACTION_INSTALL_RESULT = "es.girarkas.giraquest.INSTALL_RESULT"

    /** Motivo preciso del resultado del instalador del sistema. */
    sealed class FallbackResult {
        /** Instalado con confirmación. */
        object Success : FallbackResult()

        /** El usuario canceló en la pantalla de confirmación. */
        object Cancelled : FallbackResult()

        /** El sistema bloqueó la instalación (política o permiso). */
        object Blocked : FallbackResult()

        /** Fallo con el detalle que dio el instalador del sistema. */
        data class Failed(val detail: String) : FallbackResult()

        /** Falta el permiso "Instalar apps desconocidas" para esta app. */
        object PermissionMissing : FallbackResult()
    }

    suspend fun installWithConfirmation(context: Context, apkFile: File): FallbackResult {
        // Sin este permiso el commit de la sesión está condenado: el
        // instalador del sistema lo rechaza sin avisar ni ofrecer
        // concederlo. No crear una sesión a ciegas.
        if (!context.packageManager.canRequestPackageInstalls()) {
            return FallbackResult.PermissionMissing
        }
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                try {
                    val installer = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    val sessionId = installer.createSession(params)
                    val intent = Intent(context, InstallResultActivity::class.java).apply {
                        action = ACTION_INSTALL_RESULT
                    }
                    val pi = PendingIntent.getActivity(
                        context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    // v0.1.18: registrar el callback ANTES del commit. El
                    // PendingIntent puede dispararse muy rápido (el estado
                    // PENDING_USER_ACTION llega casi al instante) y si la
                    // Activity se crea antes de registrarlo, el resultado
                    // se pierde y esta corrutina cuelga para siempre.
                    InstallResultActivity.awaitResult(sessionId) { status, message ->
                        StartupLog.mark(
                            context,
                            "fallback: resultado instalador status=$status msg=${message ?: ""}"
                        )
                        if (cont.isActive) cont.resume(mapStatus(status, message))
                    }
                    installer.openSession(sessionId).use { session ->
                        session.openWrite("app", 0, apkFile.length()).use { out ->
                            FileInputStream(apkFile).use { input -> input.copyTo(out) }
                            session.fsync(out)
                        }
                        // commit es de la sesion, no del instalador, y hay que
                        // llamarlo con la sesion abierta.
                        session.commit(pi.intentSender)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(
                            FallbackResult.Failed(
                                e.message?.takeIf { it.isNotBlank() }
                                    ?: e.javaClass.simpleName
                            )
                        )
                    }
                }
            }
        }
    }

    /** Traduce el estado del PackageInstaller a un motivo legible. */
    private fun mapStatus(status: Int, message: String?): FallbackResult {
        val msg = message?.takeIf { it.isNotBlank() }
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> FallbackResult.Success
            // API 34+: cancelado por el usuario / bloqueado por el sistema.
            PackageInstaller.STATUS_FAILURE_ABORTED -> FallbackResult.Cancelled
            PackageInstaller.STATUS_FAILURE_BLOCKED -> FallbackResult.Blocked
            // -1: lo intercepta InstallResultActivity (abre la UI de
            // confirmación y espera el resultado final). Si llega aquí,
            // la UI no se abrió.
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                FallbackResult.Failed("el instalador pidió confirmación pero no se abrió su pantalla")
            else -> {
                // En API <34 todo es STATUS_FAILURE: el mensaje del
                // instalador dice si el usuario canceló.
                if (msg != null && "cancel" in msg.lowercase()) {
                    FallbackResult.Cancelled
                } else {
                    FallbackResult.Failed(msg ?: "fallo sin detalle (código $status)")
                }
            }
        }
    }

    /** Abre Ajustes en "Instalar apps desconocidas" para esta app. */
    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun uninstallWithConfirmation(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Activity transparente que recibe el resultado del PackageInstaller. */
    class InstallResultActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            // v0.1.18: el "-1 sin detalle" del log 02:56 ERA ESTO:
            // STATUS_PENDING_USER_ACTION (-1). El sistema NO ha fallado:
            // pide que el usuario confirme, y la UI de confirmación viene
            // en Intent.EXTRA_INTENT. Hay que lanzarla; tras confirmar/cancelar,
            // el sistema vuelve a disparar este PendingIntent con el
            // resultado FINAL. La v0.1.17 trataba el primer disparo como
            // final ("fallo sin detalle (código -1)"), quitaba el callback
            // y la confirmación no aparecía nunca: la instalación no
            // avanzaba y el OBB quedaba sin colocar.
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    StartupLog.mark(this, "fallback: instalador pide confirmación, abriendo su UI")
                    startActivity(confirmIntent)
                    // NO quitar el callback: el resultado final llega
                    // en un segundo disparo de este mismo PendingIntent.
                } else {
                    callbacks.remove(sessionId)
                        ?.invoke(status, "el sistema pidió confirmación pero no dio el intent")
                }
                finish()
                return
            }
            callbacks.remove(sessionId)?.invoke(status, message)
            finish()
        }

        companion object {
            private val callbacks = mutableMapOf<Int, (Int, String?) -> Unit>()
            fun awaitResult(sessionId: Int, cb: (Int, String?) -> Unit) {
                callbacks[sessionId] = cb
            }
        }
    }

    /** Uri para compartir un APK con el instalador del sistema (si hace falta). */
    fun apkUri(context: Context, apkFile: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
}
