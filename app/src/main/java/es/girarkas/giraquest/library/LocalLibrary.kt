package es.girarkas.giraquest.library

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Biblioteca local / modo offline del PC: escanea una carpeta en busca de
 * APKs, lee su package y versionCode con PackageManager (como hacía el PC
 * con su carpeta local) y permite instalarlos sin repo.
 */
class LocalLibrary(private val context: Context) {

    data class LocalApk(
        val file: File,
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long,
        val label: String?,
    )

    fun scan(folder: File): List<LocalApk> {
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        val pm = context.packageManager
        return folder.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", true) }
            .map { file ->
                @Suppress("DEPRECATION")
                val info = try {
                    pm.getPackageArchiveInfo(file.absolutePath, 0)
                } catch (_: Exception) {
                    null
                }
                LocalApk(
                    file = file,
                    packageName = info?.packageName,
                    versionName = info?.versionName,
                    versionCode = info?.longVersionCode ?: 0,
                    label = info?.applicationInfo?.let {
                        it.sourceDir = file.absolutePath
                        it.publicSourceDir = file.absolutePath
                        try {
                            pm.getApplicationLabel(it).toString()
                        } catch (_: Exception) {
                            null
                        }
                    },
                )
            }
            .sortedBy { it.file.name.lowercase() }
            .toList()
    }

    /** ¿Hay una carpeta OBB junto a este APK (mismo nombre que el APK)? */
    fun siblingObbDir(apkFile: File): File? {
        val sibling = File(apkFile.parentFile, apkFile.nameWithoutExtension)
        return if (sibling.isDirectory) sibling else null
    }

    fun isInstalled(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
