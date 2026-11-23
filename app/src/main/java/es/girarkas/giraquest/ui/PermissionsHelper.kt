package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import es.girarkas.giraquest.R

/**
 * Permisos que GiraQuest necesita para instalar, pedidos EN CONTEXTO
 * (v0.1.26): ya no hay asistente inicial; la tarjeta de la pantalla
 * principal y el diálogo previo a instalar abren la pantalla real de
 * Ajustes de cada permiso. Cada botón hace algo: abre Ajustes.
 */
object PermissionsHelper {

    enum class Kind { INSTALL_UNKNOWN, ALL_FILES }

    /** Permisos que faltan ahora mismo. Vacío = todo listo. */
    fun missing(context: Context): List<Kind> {
        val out = mutableListOf<Kind>()
        if (!context.packageManager.canRequestPackageInstalls()) out.add(Kind.INSTALL_UNKNOWN)
        if (!Environment.isExternalStorageManager()) out.add(Kind.ALL_FILES)
        return out
    }

    fun label(context: Context, kind: Kind): String = when (kind) {
        Kind.INSTALL_UNKNOWN -> context.getString(R.string.perm_btn_install)
        Kind.ALL_FILES -> context.getString(R.string.perm_btn_files)
    }

    /** Abre la pantalla de Ajustes de ese permiso. Si el sistema no la
     *  tiene (Horizon OS recorta Ajustes), cae a la ficha de la app. */
    fun openSettings(activity: Activity, kind: Kind) {
        val pkg = activity.packageName
        val intent = when (kind) {
            Kind.INSTALL_UNKNOWN -> Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$pkg")
            )
            Kind.ALL_FILES -> Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$pkg")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(
                    activity,
                    R.string.perms_settings_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** v0.1.28 (onboarding de primer arranque): pantalla de "Acceso a
     *  todos los archivos" DIRECTA de GiraQuest
     *  (ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, API 30+, con la
     *  URI del paquete): el usuario cae en el interruptor de la app, no
     *  en la lista general. Con caídas a la lista general y a la ficha
     *  de la app si Horizon no tiene esa pantalla. */
    fun openAppAllFilesSettings(activity: Activity) {
        val pkg = activity.packageName
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$pkg")
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")
            ),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(activity, R.string.perms_settings_failed, Toast.LENGTH_LONG).show()
    }

    /** Notificaciones (POST_NOTIFICATIONS, permiso runtime desde API 33):
     *  true si concedido (o si la API no lo exige). */
    fun notificationsGranted(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Diálogo previo a instalar cuando falta un permiso: explica cuál
     *  falta y ofrece abrir Ajustes. Devuelve true si se mostró. */
    fun showMissingDialog(activity: Activity, missing: List<Kind>): Boolean {
        if (missing.isEmpty()) return false
        val names = missing.joinToString("\n• ") { "• " + label(activity, it) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.perms_missing_title)
            .setMessage(activity.getString(R.string.perms_missing_msg, names))
            .setPositiveButton(R.string.perms_open_settings) { _, _ ->
                openSettings(activity, missing.first())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }
}
