package es.girarkas.giraquest.library

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import es.girarkas.giraquest.data.InstalledApp
import es.girarkas.giraquest.data.SettingsStore

/**
 * LibraryManager (equivale a la vista de instaladas del PC: PackageManager
 * + versiones + desinstalar).
 *
 * Muestra las apps instaladas por GiraQuest y, opcionalmente, todas las
 * apps de usuario del visor. La desinstalación (APK+OBB+datos) vive en
 * Installer.uninstall() — aquí solo se lista y se consulta.
 *
 * v0.1.28: en API 30+ (Quest Android 14) getInstalledPackages() devuelve
 * una lista FILTRADA sin QUERY_ALL_PACKAGES, así que la pestaña
 * "Instaladas" salía vacía aunque hubiera juegos instalados. Ahora
 * ourInstalledApps() parte del REGISTRO PROPIO (paquetes que nosotros
 * instalamos) y verifica cada uno con getPackageInfo(), y la
 * enumeración queda como red de seguridad; el manifiesto declara
 * QUERY_ALL_PACKAGES.
 */
class LibraryManager(private val context: Context) {

    private val settings = SettingsStore(context)

    private fun toInstalledApp(pm: PackageManager, info: PackageInfo): InstalledApp? {
        val appInfo = info.applicationInfo ?: return null
        return InstalledApp(
            packageName = info.packageName,
            label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                info.packageName
            },
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    /** Apps instaladas por nosotros (según nuestro registro) con sus datos. */
    fun ourInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        val out = mutableMapOf<String, InstalledApp>()
        // 1) Registro propio: paquetes que nosotros instalamos, verificados
        // uno a uno con getPackageInfo (no depende de la enumeración).
        for (pkg in settings.installedPackageNames()) {
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, 0)
                toInstalledApp(pm, info)?.let { out[it.packageName] = it }
            } catch (_: Exception) {
                // Ya no instalado (o no visible): se salta, no tumba la lista.
            }
        }
        // 2) Enumeración como red de seguridad (con QUERY_ALL_PACKAGES
        // trae todo; sin él, solo lo visible para la app).
        try {
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledPackages(0)
            for (pkg in installed) {
                if (settings.getInstalledVersion(pkg.packageName) < 0) continue
                toInstalledApp(pm, pkg)?.let { out[it.packageName] = it }
            }
        } catch (_: Exception) {
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    /** Todas las apps de usuario (no sistema) del visor. */
    fun allUserApps(): List<InstalledApp> {
        val pm = context.packageManager
        val out = mutableListOf<InstalledApp>()
        try {
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledPackages(0)
            for (pkg in installed) {
                val appInfo = pkg.applicationInfo ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (isSystem) continue
                toInstalledApp(pm, pkg)?.let { out.add(it.copy(isSystem = false)) }
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun isInstalled(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun versionCode(packageName: String): Long {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (_: Exception) {
            -1
        }
    }

    /** Tamaño del OBB instalado, si existe (para mostrar "OBB: 1,2 GB"). */
    fun obbSizeBytes(packageName: String): Long {
        val dir = java.io.File("/sdcard/Android/obb/$packageName")
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
