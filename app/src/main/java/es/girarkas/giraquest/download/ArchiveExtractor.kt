package es.girarkas.giraquest.download

import es.girarkas.giraquest.data.ObbFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

/**
 * Descompresión de OBB empaquetados (.zip y .7z, como en el PC, que extrae
 * los *.7z de la carpeta del juego antes de instalar).
 *
 * Devuelve la carpeta donde quedó el contenido extraído.
 */
object ArchiveExtractor {

    suspend fun extract(
        archive: File,
        destDir: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        when (archive.extension.lowercase()) {
            "zip" -> extractZip(archive, destDir)
            "7z" -> extract7z(archive, destDir, onProgress)
            else -> {
                // No es un archivo: el OBB ya es una carpeta/archivo suelto.
                // Se copia tal cual a destDir.
                val target = File(destDir, archive.name)
                if (archive.isDirectory) {
                    archive.copyRecursively(target, overwrite = true)
                } else {
                    archive.copyTo(target, overwrite = true)
                }
            }
        }
        destDir
    }

    private fun extractZip(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath
        val buf = ByteArray(256 * 1024)
        ZipInputStream(FileInputStream(archive).buffered(256 * 1024)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name).canonicalFile
                require(out.path.startsWith(destCanonical)) { "Ruta fuera del destino: ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { o ->
                        var r: Int
                        while (zip.read(buf).also { r = it } != -1) {
                            o.write(buf, 0, r)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun extract7z(
        archive: File,
        destDir: File,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
    ) = extract7z(archive, destDir, null, onProgress)

    /**
     * Canal SeekableByteChannel con buffer de lectura anticipada (4 MB):
     * evita miles de syscalls pread pequeñas de commons-compress / LZMA2
     * sobre archivos locales o volúmenes partidos .7z.001/.002.
     */
    private class BufferedSeekableByteChannel(
        private val delegate: SeekableByteChannel,
        bufferSize: Int = 4 * 1024 * 1024,
    ) : SeekableByteChannel {
        private val buf = ByteArray(bufferSize)
        private var bufStart = -1L
        private var bufLen = 0
        private var pos = 0L
        private val totalSize = delegate.size()

        override fun isOpen(): Boolean = delegate.isOpen
        override fun close() = delegate.close()
        override fun position(): Long = pos
        override fun size(): Long = totalSize

        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0L) { "Negative position: $newPosition" }
            pos = newPosition
            return this
        }

        override fun read(dst: ByteBuffer): Int {
            if (!dst.hasRemaining()) return 0
            if (pos >= totalSize) return -1
            if (pos < bufStart || pos >= bufStart + bufLen) {
                delegate.position(pos)
                bufStart = pos
                bufLen = 0
                val bb = ByteBuffer.wrap(buf)
                while (bb.hasRemaining()) {
                    val n = delegate.read(bb)
                    if (n <= 0) break
                }
                bufLen = bb.position()
                if (bufLen <= 0) return -1
            }
            val off = (pos - bufStart).toInt()
            val avail = (bufLen - off).coerceAtMost(dst.remaining())
            dst.put(buf, off, avail)
            pos += avail
            return avail
        }

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    }

    data class ExtractStats(val writtenBytes: Long, val skippedEntries: Int, val skippedBytes: Long)

    fun extract7z(
        archive: File,
        destDir: File,
        password: String?,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ): ExtractStats = extract7z(listOf(archive), destDir, password, onProgress, resume)

    /**
     * Extrae un .7z simple o dividido en partes (.7z.001, .7z.002…)
     * directamente vía MultiReadOnlySeekableByteChannel + lectura bufferizada
     * de 4 MB, sin necesidad de concatenar gigabytes en disco antes.
     */
    fun extract7z(
        archives: List<File>,
        destDir: File,
        password: String?,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)? = null,
        resume: Boolean = false,
    ): ExtractStats {
        require(archives.isNotEmpty()) { "Sin archivos .7z que extraer" }
        var skippedEntries = 0
        var skippedBytes = 0L
        var writtenTotal = 0L
        sevenZRun(archives, destDir, password, onProgress, resume) { written, skippedE, skippedB ->
            writtenTotal = written
            skippedEntries = skippedE
            skippedBytes = skippedB
        }
        return ExtractStats(writtenTotal, skippedEntries, skippedBytes)
    }

    private fun openSevenZ(archives: List<File>, password: String?): SevenZFile {
        val rawChannel: SeekableByteChannel = if (archives.size == 1) {
            FileChannel.open(archives[0].toPath(), StandardOpenOption.READ)
        } else {
            MultiReadOnlySeekableByteChannel.forFiles(*archives.toTypedArray())
        }
        val buffered = BufferedSeekableByteChannel(rawChannel)
        val builder = SevenZFile.builder().setSeekableByteChannel(buffered)
        if (!password.isNullOrBlank()) {
            builder.setPassword(password.toCharArray())
        }
        return builder.get()
    }

    private fun sevenZRun(
        archives: List<File>,
        destDir: File,
        password: String?,
        onProgress: ((writtenBytes: Long, totalBytes: Long, entryName: String) -> Unit)?,
        resume: Boolean,
        onDone: (written: Long, skippedEntries: Int, skippedBytes: Long) -> Unit,
    ) {
        if (NativeSevenZip.isAvailable) {
            try {
                val stats = NativeSevenZip.extract7z(
                    archives = archives,
                    destDir = destDir,
                    password = password,
                    onProgress = onProgress,
                    resume = resume,
                )
                onDone(stats.writtenBytes, stats.skippedEntries, stats.skippedBytes)
                return
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                val canFallback = t is UnsatisfiedLinkError ||
                    "Unsupported compression method" in msg
                if (!canFallback) throw t
            }
        }

        val sevenZ = openSevenZ(archives, password)
        var skippedEntries = 0
        var skippedBytes = 0L
        var writtenTotal = 0L
        // v0.1.41: buffer único reutilizado fuera del bucle y canonicalPath precalculado
        // (evita miles de asignaciones de 1 MB y llamadas realpath() en archivos con muchos ficheros).
        val buf = ByteArray(1024 * 1024)
        val destCanonical = destDir.canonicalPath
        val destPrefix = if (destCanonical.endsWith(File.separator)) destCanonical else "$destCanonical${File.separator}"
        sevenZ.use {
            val total = try {
                it.entries
                    .filter { e -> !e.isDirectory && e.size >= 0 }
                    .sumOf { e -> e.size }
            } catch (_: Throwable) {
                0L
            }
            var written = 0L
            var lastPct = -1
            val report = { entryName: String, force: Boolean ->
                if (onProgress != null && total > 0) {
                    val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                    if (force || pct != lastPct) {
                        lastPct = pct
                        onProgress(written, total, entryName)
                    }
                }
            }
            var entry = it.nextEntry
            while (entry != null) {
                val rawOut = File(destDir, entry.name)
                val out = if (".." in entry.name) rawOut.canonicalFile else rawOut
                if (".." in entry.name) {
                    val p = out.path
                    require(p == destCanonical || p.startsWith(destPrefix)) { "Ruta fuera del destino: ${entry.name}" }
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    val alreadyDone = resume && out.isFile && entry.size >= 0 && out.length() == entry.size
                    if (alreadyDone) {
                        var read: Int
                        var skipped = 0L
                        while (it.read(buf).also { read = it } != -1) {
                            skipped += read
                            written += read
                            report(entry.name, false)
                        }
                        skippedEntries++
                        skippedBytes += skipped
                    } else {
                        out.parentFile?.mkdirs()
                        val stream: OutputStream = if (entry.size < 0 || entry.size > 65_536L) {
                            BufferedOutputStream(FileOutputStream(out), 512 * 1024)
                        } else {
                            FileOutputStream(out)
                        }
                        stream.use { o ->
                            var read: Int
                            while (it.read(buf).also { read = it } != -1) {
                                o.write(buf, 0, read)
                                written += read
                                report(entry.name, false)
                            }
                        }
                    }
                }
                entry = it.nextEntry
            }
            report("", true)
            writtenTotal = written
        }
        onDone(writtenTotal, skippedEntries, skippedBytes)
    }

    /**
     * TODOS los .obb del árbol extraído (v0.1.27, el arreglo del bug
     * OBB): recursivo, sin distinguir mayúsculas (.OBB vale), ordenado
     * por ruta relativa. La v0.1.26 solo miraba UNA carpeta
     * (findObbContent): si los .obb estaban repartidos en varias
     * carpetas (principal en <paquete>/, parche en la raíz u otra
     * subcarpeta), los de fuera se perdían en silencio.
     */
    fun findAllObbFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return try {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
                .sortedBy { it.relativeTo(root).path }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Lo mismo, con ruta relativa y tamaño (para el registro por
     *  archivo y la verificación tras copiar). */
    fun listObbFiles(root: File): List<ObbFileInfo> {
        return findAllObbFiles(root).map {
            ObbFileInfo(
                name = it.name,
                relativePath = try {
                    it.relativeTo(root).path
                } catch (_: Exception) {
                    it.name
                },
                size = it.length(),
            )
        }
    }

    private val ALWAYS_IGNORED_NAMES = setOf(
        ".extracted_ok",
        ".extracting",
        ".installed_ok",
        "release.manifest",
        "desktop.ini",
        ".ds_store",
        "thumbs.db",
    )

    private val OUTSIDE_PKG_IGNORED_NAMES = setOf(
        "install.txt",
        "hwid.txt",
        "readme.txt",
        "changelog.txt",
    )

    private val OUTSIDE_PKG_IGNORED_EXTS = setOf(
        "apk", "txt", "manifest", "nfo", "md", "url", "lnk",
        "bat", "cmd", "ps1", "sh", "exe", "dll", "log",
        "png", "jpg", "jpeg", "webp", "gif", "ico",
        "json", "xml", "ini", "cfg", "7z", "zip", "rar", "part",
    )

    /**
     * v0.1.38/v0.1.39: distingue un archivo REAL de datos del juego (.obb,
     * .pak, .hdog, .bin, etc.) frente a marcadores internos de GiraQuest
     * (`.extracted_ok`, `.extracting`), metadatos del repo VRP
     * (`release.manifest`, `install.txt`), el `.apk` o basura suelta.
     *
     * Cuando [insidePkgFolder] es true (el archivo está dentro de la
     * carpeta `<paquete>/` del propio juego), se conserva TODO su contenido
     * íntegro (salvo `.apk` y marcadores/basura del sistema) para que no se
     * quede ningún juego incompleto por traer archivos auxiliares o de 0 KB.
     * Cuando es false (archivos sueltos fuera de `<paquete>/`), se aplica el
     * filtro estricto para no copiar `.extracted_ok`, `release.manifest` ni
     * archivos vacíos a `/Android/obb/<paquete>/`.
     */
    fun isGameDataFile(file: File, insidePkgFolder: Boolean = false): Boolean {
        if (!file.isFile) return false
        val nameLower = file.name.lowercase()
        if (nameLower in ALWAYS_IGNORED_NAMES) return false
        val ext = file.extension.lowercase()
        if (ext == "apk") return false
        if (insidePkgFolder) return true
        if (file.length() <= 0L) return false
        if (nameLower in OUTSIDE_PKG_IGNORED_NAMES) return false
        if (ext in OUTSIDE_PKG_IGNORED_EXTS) return false
        return true
    }

    /** ¿Existe una carpeta llamada `<packageName>` dentro de [root]? (Sirve
     *  para detectar en `preparedFromDisk` que sus datos ya se movieron antes). */
    fun hasPkgDir(root: File, packageName: String): Boolean {
        if (!root.exists()) return false
        return try {
            root.walkTopDown().any { it.isDirectory && it.name == packageName }
        } catch (_: Exception) {
            false
        }
    }

    /** TODOS los archivos de datos reales del árbol extraído (v0.1.33,
     *  depurado en v0.1.38): excluye .apk, marcadores (.extracted_ok,
     *  .extracting), metadatos VRP (release.manifest, install.txt…) y
     *  archivos vacíos de 0 bytes. */
    fun findAllDataFiles(root: File, insidePkgFolder: Boolean = false): List<File> {
        if (!root.exists()) return emptyList()
        return try {
            root.walkTopDown()
                .filter { isGameDataFile(it, insidePkgFolder = insidePkgFolder) }
                .sortedBy { it.relativeTo(root).path }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Lo mismo, con ruta relativa y tamaño (para el registro por
     *  archivo y la verificación tras copiar). */
    fun listDataFiles(root: File, insidePkgFolder: Boolean = false): List<ObbFileInfo> {
        return findAllDataFiles(root, insidePkgFolder = insidePkgFolder).map {
            ObbFileInfo(
                name = it.name,
                relativePath = try {
                    it.relativeTo(root).path
                } catch (_: Exception) {
                    it.name
                },
                size = it.length(),
            )
        }
    }

    /**
     * Normaliza un staging viejo a la raíz extraída (v0.1.27): los
     * pendientes guardados por la v0.1.26 apuntan a la carpeta del
     * paquete dentro de game_extracted; el reintento debe cubrir TODO
     * el árbol, así que se sube hasta game_extracted/obb_extracted si
     * existe por encima (como mucho 4 niveles). Si no hay raíz, se
     * devuelve el staging tal cual.
     */
    fun normalizeStagingRoot(staging: File): File {
        var cur: File? = staging
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.name == "game_extracted" || cur.name == "obb_extracted") return cur
            cur = cur.parentFile
            hops++
        }
        return staging
    }

    /**
     * Busca dentro de una carpeta extraída la carpeta del OBB (la que se
     * llama como el paquete, p. ej. com.teambeef.doom3quest) o, si no hay
     * carpeta, los archivos .obb sueltos. Devuelve la carpeta a copiar a
     * /sdcard/Android/obb/<paquete>/.
     *
     * LEGADO (v0.1.27): ya NO se usa para el staging — la copia camina
     * desde la raíz extraída y cubre todos los .obb (findAllObbFiles).
     * Se conserva por compatibilidad; no usar para decidir qué se copia.
     *
     * v0.1.19: la versión anterior devolvía la carpeta del paquete aunque
     * estuviera VACÍA (copiarla dejaba /Android/obb/<paquete>/ vacío y
     * parecía un éxito: así quedó Pistol Whip) y devolvía "la única
     * carpeta" ANTES de mirar los .obb sueltos en la raíz (si el .obb
     * estaba suelto y había una carpeta, se copiaba la carpeta
     * equivocada). Ahora solo se devuelve una carpeta que contiene
     * archivos .obb DIRECTAMENTE (o la del paquete no vacía, como el
     * PC, si el OBB no lleva extensión .obb); una carpeta vacía ya no
     * vale como OBB.
     */
    fun findObbContent(extractedDir: File, packageName: String): File? {
        // Carpetas que contienen archivos .obb DIRECTAMENTE (hijos, no
        // en subcarpetas): si el .obb está en Android/obb/<paquete>/,
        // la carpeta buena es <paquete>/, no "Android/".
        val obbDirs = mutableListOf<File>()
        extractedDir.walkTopDown().forEach { d ->
            if (d.isDirectory &&
                d.listFiles()?.any { it.isFile && it.extension.equals("obb", true) } == true
            ) {
                obbDirs.add(d)
            }
        }
        // 1) La carpeta del paquete (el layout del PC).
        obbDirs.firstOrNull { it.name == packageName }?.let { return it }
        // 2) Cualquier otra carpeta con .obb directos (incluida la raíz
        // del extraído si el .obb está suelto ahí).
        obbDirs.firstOrNull()?.let { return it }
        // 3) Compatibilidad con el PC: carpeta del paquete no vacía
        // aunque el OBB no lleve extensión .obb. Nunca una carpeta
        // vacía: copiarla dejaría el OBB vacío y parecería un éxito.
        val byName = File(extractedDir, packageName)
        if (byName.isDirectory && (byName.listFiles()?.isNotEmpty() == true)) return byName
        return null
    }

    /**
     * Carpeta localizada para colocar entera en /Android/obb/<paquete>/
     * (v0.1.32, como el PC: remotePath=/sdcard/Android/obb/<folderName>).
     *
     * v0.1.33: "carpeta con datos" = cualquier archivo, NO solo .obb.
     * Maestro trae .pak además del .obb y Hot Dogs H3VR2 NO trae .obb
     * (sus datos son .hdog): con el criterio viejo la carpeta no se
     * encontraba y la copia por archivos dejaba fuera los .pak/.hdog.
     */
    data class ObbFolderResult(
        /** Carpeta a colocar en /Android/obb/<paquete>/. */
        val dir: File,
        /** true si NO había ninguna carpeta llamada <paquete> y se usó el
         *  ancestro común más profundo de los archivos de datos. */
        val viaCommonAncestor: Boolean,
        /** Nº de .obb dentro de la carpeta (directos o en subdirectorios). */
        val obbCount: Int,
        /** Nº TOTAL de archivos de datos dentro de la carpeta
         *  (cualquier extensión salvo .apk). */
        val fileCount: Int,
        /** Bytes totales de los archivos de datos de la carpeta. */
        val totalBytes: Long,
        /** Profundidad de la carpeta respecto a la raíz extraída (0 = la
         *  propia raíz). */
        val depth: Int,
        /** Ruta relativa de la carpeta desde la raíz extraída. */
        val relativePath: String,
    )

    /**
     * Localiza la carpeta de datos en el árbol extraído (v0.1.32, reglas
     * ampliadas en v0.1.33). Búsqueda RECURSIVA por todo el árbol, sin
     * asumir ninguna profundidad: la carpeta del paquete NO siempre está
     * a la misma profundidad (a veces está metida en una subcarpeta;
     * nada de game_extracted/<release>/<pkg> como ruta fija).
     *
     * 1) Un directorio llamado EXACTAMENTE <packageName> que contenga
     *    archivos de datos (cualquier extensión salvo .apk, directamente
     *    o en subdirectorios): esa es la carpeta, esté a la profundidad
     *    que esté. Si hay varias, la más superficial (la primera en el
     *    recorrido).
     * 2) Si no hay ninguna y hay archivos .obb, el ANCESTRO COMÚN MÁS
     *    PROFUNDO de todos los .obb.
     * 3) Si no hay .obb pero sí otros archivos de datos, el ANCESTRO
     *    COMÚN MÁS PROFUNDO de todos los archivos de datos.
     * 4) Si no hay ningún archivo de datos en el árbol: null.
     */
    fun findObbFolder(root: File, packageName: String): ObbFolderResult? {
        // 1) Carpeta llamada exactamente como el paquete, con archivos
        // de datos reales dentro (directos o en subdirectorios).
        val pkgDir = try {
            root.walkTopDown().firstOrNull { d ->
                d.isDirectory && d.name == packageName &&
                    d.walkTopDown().any { isGameDataFile(it, insidePkgFolder = true) }
            }
        } catch (_: Exception) {
            null
        }
        if (pkgDir != null) {
            return folderResult(root, pkgDir, viaCommonAncestor = false)
        }
        val dataFiles = try {
            findAllDataFiles(root, insidePkgFolder = false)
        } catch (_: Exception) {
            return null
        }
        if (dataFiles.isEmpty()) return null
        val obbFiles = dataFiles.filter { it.extension.equals("obb", true) }
        // 2) Ancestro común más profundo de todos los .obb.
        if (obbFiles.isNotEmpty()) {
            val ancestor = deepestCommonAncestor(obbFiles) ?: return null
            return folderResult(root, ancestor, viaCommonAncestor = true)
        }
        // 3) Ancestro común más profundo de todos los archivos de datos reales.
        val ancestor = deepestCommonAncestor(dataFiles) ?: return null
        return folderResult(root, ancestor, viaCommonAncestor = true)
    }

    /** ObbFolderResult de una carpeta: cuenta sus archivos de datos reales
     *  y sus .obb. */
    private fun folderResult(root: File, dir: File, viaCommonAncestor: Boolean): ObbFolderResult {
        val data = try {
            listDataFiles(dir, insidePkgFolder = !viaCommonAncestor)
        } catch (_: Exception) {
            emptyList()
        }
        return ObbFolderResult(
            dir = dir,
            viaCommonAncestor = viaCommonAncestor,
            obbCount = data.count { it.name.endsWith(".obb", ignoreCase = true) },
            fileCount = data.size,
            totalBytes = data.sumOf { it.size },
            depth = depthOf(root, dir),
            relativePath = relPathOf(root, dir),
        )
    }

    /** Ancestro común más profundo de una lista de archivos (por partes
     *  de ruta absoluta). null si no hay antepasado común. */
    private fun deepestCommonAncestor(files: List<File>): File? {
        if (files.isEmpty()) return null
        val parents = files.mapNotNull { it.parentFile }
        if (parents.isEmpty()) return null
        var common = parents.first().absolutePath.split(File.separator)
        for (p in parents.drop(1)) {
            val parts = p.absolutePath.split(File.separator)
            val n = minOf(common.size, parts.size)
            var i = 0
            while (i < n && common[i] == parts[i]) i++
            if (i == 0) return null
            common = common.subList(0, i)
        }
        return File(common.joinToString(File.separator))
    }

    /** Ruta de `dir` relativa a `root` (o la absoluta si no se puede). */
    private fun relPathOf(root: File, dir: File): String = try {
        dir.relativeTo(root).path
    } catch (_: Exception) {
        dir.absolutePath
    }

    /** Profundidad de `dir` respecto a `root` (0 = la propia raíz). */
    private fun depthOf(root: File, dir: File): Int {
        val rel = relPathOf(root, dir)
        if (rel.isBlank() || rel == ".") return 0
        return rel.split(File.separator).count { it.isNotEmpty() && it != "." }
    }
}
