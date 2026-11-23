package es.girarkas.giraquest.download

import java.io.File

/**
 * Motor nativo C/C++ de 7-Zip (libgira7z.so, basado en 7-Zip 24.09 compilado
 * para arm64-v8a con Android NDK).
 *
 * Corre en proceso vía JNI (sin execve, por lo que no le afecta SELinux/seccomp),
 * usa descompresión LZMA2 multihilo + AES-256 acelerado por hardware ARMv8
 * y lectura streaming multi-volumen (.7z.001/.002/…) con buffer de 4 MB.
 */
object NativeSevenZip {

    fun interface ProgressCallback {
        fun onProgress(writtenBytes: Long, totalBytes: Long, entryName: String)
    }

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    val isAvailable: Boolean
        get() {
            if (!loadAttempted) {
                synchronized(this) {
                    if (!loadAttempted) {
                        loadAttempted = true
                        try {
                            System.loadLibrary("gira7z")
                            loaded = true
                        } catch (t: Throwable) {
                            loaded = false
                            loadError = "${t.javaClass.simpleName}: ${t.message}"
                        }
                    }
                }
            }
            return loaded
        }

    @JvmStatic
    private external fun nativeExtract7z(
        parts: Array<String>,
        destDir: String,
        password: String?,
        resume: Boolean,
        threads: Int,
        callback: ProgressCallback?,
    ): LongArray

    fun extract7z(
        archives: List<File>,
        destDir: File,
        password: String?,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ): ArchiveExtractor.ExtractStats {
        check(isAvailable) { "libgira7z no disponible: ${loadError ?: "desconocido"}" }
        val partPaths = archives.map { it.absolutePath }.toTypedArray()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
        val cb = if (onProgress != null) {
            ProgressCallback { written, total, entryName ->
                onProgress(written, total, entryName)
            }
        } else {
            null
        }
        val res = nativeExtract7z(
            partPaths,
            destDir.absolutePath,
            password,
            resume,
            threads,
            cb,
        )
        return ArchiveExtractor.ExtractStats(
            writtenBytes = res.getOrElse(0) { 0L },
            skippedEntries = res.getOrElse(1) { 0L }.toInt(),
            skippedBytes = res.getOrElse(2) { 0L },
        )
    }
}
