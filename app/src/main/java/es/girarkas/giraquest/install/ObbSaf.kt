package es.girarkas.giraquest.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import es.girarkas.giraquest.data.SettingsStore
import java.io.File

/**
 * v0.1.29: acceso a /Android/obb por SAF (Storage Access Framework).
 *
 * Motivo: en la v0.1.28 (instalada LIMPIA, nuevo UID) la I/O directa
 * de archivos a /Android/obb/<paquete> falla con EACCES aunque
 * MANAGE_EXTERNAL_STORAGE esté concedido — el FUSE de Horizon no deja
 * ni crear el directorio ni abrir el destino para escribir (y a veces
 * ni listar). En v0.1.26/v0.1.27 (instaladas encima, UID antiguo) la
 * misma copia directa funcionaba. El árbol SAF concedido por el
 * usuario es la vía de escape que sí pasa por el proveedor de
 * documentos del sistema en vez de por la FUSE directa.
 *
 * v0.1.30: el árbol concedido puede ser Android/obb O la RAÍZ del
 * almacenamiento (el selector de Horizon/Android 11+ puede NO dejar
 * elegir Android/obb). Por eso se guarda QUÉ se concedió
 * (SettingsStore.obbSafTreeKind: "obb" | "root" | "other") y TODAS las
 * funciones navegan desde la base real del árbol. Sin este dato,
 * pkgDir() buscaba el paquete en la raíz (y hasta podía CREAR la
 * carpeta del juego en la raíz del almacenamiento) y listDir()
 * navegaba "Android/obb/..." desde un árbol que YA era Android/obb.
 * Además, verifyTree() comprueba de verdad (listar + escribir y
 * borrar una sonda) que el árbol concedido sirve, y lo registra en el
 * log: conceder no basta, hay que demostrar que escribe.
 */
object ObbSaf {

    private const val OBB_TREE_DOC_ID = "primary:Android/obb"
    private const val AUTHORITY = "com.android.externalstorage.documents"

    /** Carpeta de paquete de prueba para la sonda de verificación
     *  (v0.1.31): se crea y se borra en cada verifyTree; no es un
     *  juego real. */
    private const val PROBE_PKG = "com.giraquest.probe"

    /** URI inicial del selector: abre directamente en Android/obb. */
    fun initialTreeUri(): Uri = DocumentsContract.buildDocumentUri(
        AUTHORITY, OBB_TREE_DOC_ID
    )

    /** URI inicial del selector en la RAÍZ del almacenamiento (vía
     *  alternativa cuando el selector no deja elegir Android/obb:
     *  desde la raíz se llega a Android/obb con DocumentFile). */
    fun initialRootTreeUri(): Uri = DocumentsContract.buildDocumentUri(
        AUTHORITY, "primary:"
    )

    /** URI inicial del selector para una carpeta concreta del
     *  almacenamiento (p. ej. Android/obb/<paquete>). null si la
     *  carpeta no está bajo el almacenamiento externo: el selector
     *  abre entonces en su raíz por defecto. */
    fun initialTreeUriFor(dir: File): Uri? {
        val rel = try {
            dir.relativeTo(Environment.getExternalStorageDirectory()).path
                .replace(File.separator, "/")
        } catch (_: Exception) {
            return null
        }
        if (rel.isBlank() || rel == ".") return null
        return DocumentsContract.buildDocumentUri(
            AUTHORITY, "primary:$rel"
        )
    }

    /** Qué árbol se concedió: "obb" (Android/obb), "root" (la raíz del
     *  almacenamiento), "obb-sub" (una subcarpeta de Android/obb, p.
     *  ej. Android/obb/<paquete>), "other" (otra carpeta) o "" (nada).
     *  Se detecta del document ID del árbol concedido. */
    fun detectKind(uri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return "other"
        }
        return when {
            docId == OBB_TREE_DOC_ID -> "obb"
            docId == "primary:" || docId == "primary" -> "root"
            docId.startsWith("primary:Android/obb/") -> "obb-sub"
            else -> "other"
        }
    }

    fun treeUri(context: Context): Uri? =
        SettingsStore(context).obbSafTreeUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    fun hasTree(context: Context): Boolean = treeUri(context) != null

    /** Vía concedida ("obb" | "root" | "obb-sub" | "other" | ""): la
     *  guardada al conceder, o detectada de la URI si falta. */
    fun treeKind(context: Context): String {
        val settings = SettingsStore(context)
        val saved = settings.obbSafTreeKind
        if (saved.isNotBlank()) return saved
        val uri = treeUri(context) ?: return ""
        return detectKind(uri).also { settings.obbSafTreeKind = it }
    }

    /** Ruta relativa (desde la raíz del almacenamiento) de la base del
     *  árbol concedido: "" = raíz, "Android/obb", "Android/obb/<pkg>",
     *  "Download", ... "" también si no hay árbol. */
    fun treeBasePath(context: Context): String {
        val uri = treeUri(context) ?: return ""
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return ""
        }
        return docId.removePrefix("primary:").trim('/').replace("/", File.separator)
    }

    /** DocumentFile de la raíz del árbol concedido. */
    private fun treeRoot(context: Context): DocumentFile? =
        treeUri(context)?.let {
            try {
                DocumentFile.fromTreeUri(context, it)
            } catch (_: Exception) {
                null
            }
        }

    /** Navega desde un nodo por las partes de una ruta relativa. null
     *  si alguna parte no existe (sin crear nada). */
    private fun navigate(node: DocumentFile, relativePath: String): DocumentFile? {
        var current = node
        for (part in relativePath.split(File.separator)) {
            if (part.isBlank() || part == ".") continue
            current = try {
                current.findFile(part) ?: return null
            } catch (_: Exception) {
                return null
            }
        }
        return current
    }

    /** La carpeta Android/obb vista desde el árbol concedido. null si
     *  el árbol no la contiene (otra carpeta que no es antepasada de
     *  Android/obb, o una subcarpeta suya: desde ahí no se llega a los
     *  hermanos). */
    fun obbRoot(context: Context): DocumentFile? {
        val root = treeRoot(context) ?: return null
        val base = treeBasePath(context)
        return when {
            base.isEmpty() -> {
                // Árbol = raíz del almacenamiento: bajar a Android/obb.
                val android = try {
                    navigate(root, "Android") ?: root.createDirectory("Android")
                } catch (_: Exception) {
                    return null
                } ?: return null
                try {
                    navigate(android, "obb") ?: android.createDirectory("obb")
                } catch (_: Exception) {
                    null
                }
            }
            base == "Android${File.separator}obb" -> root
            else -> null
        }
    }

    /** Carpeta del paquete dentro del árbol concedido (la crea si no
     *  existe). null si no hay árbol o no se puede abrir/crear. */
    fun pkgDir(context: Context, packageName: String): DocumentFile? {
        val root = treeRoot(context) ?: return null
        val base = treeBasePath(context)
        // Subcarpeta de Android/obb concedida directamente: solo vale
        // si ES la carpeta de este paquete.
        if (base.startsWith("Android${File.separator}obb${File.separator}")) {
            val leaf = base.substringAfterLast(File.separator)
            if (leaf == packageName) return root
            return null
        }
        val obb = obbRoot(context) ?: return null
        return try {
            obb.findFile(packageName) ?: obb.createDirectory(packageName)
        } catch (_: Exception) {
            null
        }
    }

    /** Lista el contenido de una ruta que esté bajo el árbol concedido
     *  (para el explorador cuando File.listFiles() devuelve null).
     *  null si la ruta no está bajo el árbol o no se puede listar. */
    fun listDir(context: Context, dir: File): List<DocumentFile>? {
        val root = treeRoot(context) ?: return null
        val base = treeBasePath(context)
        val rel = try {
            dir.relativeTo(Environment.getExternalStorageDirectory()).path
        } catch (_: Exception) {
            return null
        }
        // Ruta de la carpeta respecto a la base del árbol: debe ser
        // descendiente de la base (o la base misma).
        val relFromBase = when {
            base.isEmpty() -> rel
            rel == base -> ""
            rel.startsWith(base + File.separator) -> rel.removePrefix(base + File.separator)
            else -> return null
        }
        val node = if (relFromBase.isBlank() || relFromBase == ".") root else navigate(root, relFromBase)
            ?: return null
        return try {
            node.listFiles().toList()
        } catch (_: Exception) {
            null
        }
    }

    data class SafCopyResult(val ok: Boolean, val bytes: Long, val reason: String, val verified: Boolean = true)

    /** Copia un .obb a la carpeta del paquete por SAF. Si el destino
     *  ya existe con el mismo tamaño, se da por hecho sin re-copiar.
     *  verified=false si se escribió sin error pero el tamaño del
     *  documento no se pudo comprobar (length() = -1). */
    fun copyObb(context: Context, packageName: String, src: File): SafCopyResult {
        val dir = pkgDir(context, packageName)
            ?: return SafCopyResult(false, -1, "no se pudo abrir/crear la carpeta $packageName en el árbol SAF")
        return copyFileToDir(context, dir, src)
    }

    /** Copia TODA una carpeta por SAF a Android/obb/<paquete>/ (v0.1.32,
     *  vía (b) de la colocación de carpeta entera): la carpeta del
     *  paquete se crea si no existe y dentro se copian todos sus
     *  archivos, conservando las subcarpetas (como el PC). verified=
     *  false si algún archivo se escribió sin poder comprobar su
     *  tamaño. ok=false si algún archivo falló (el resto queda copiado;
     *  el llamador reintenta por archivos). */
    fun copyFolderObb(context: Context, packageName: String, srcDir: File): SafCopyResult {
        val dir = pkgDir(context, packageName)
            ?: return SafCopyResult(false, -1, "no se pudo abrir/crear la carpeta $packageName en el árbol SAF")
        val files = try {
            srcDir.walkTopDown().filter { it.isFile }.toList()
        } catch (e: Exception) {
            return SafCopyResult(false, -1, "no se puede listar la carpeta ${srcDir.name}: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (files.isEmpty()) {
            return SafCopyResult(false, -1, "la carpeta ${srcDir.name} no trae archivos")
        }
        // v0.1.33/v0.1.38: nunca copiar .apk, marcadores (.extracted_ok),
        // metadatos (release.manifest) ni archivos vacíos a /Android/obb/.
        val insidePkg = srcDir.name == packageName
        val skippedApk = files.count { it.extension.equals("apk", true) }
        val toCopy = files.filter {
            es.girarkas.giraquest.download.ArchiveExtractor.isGameDataFile(it, insidePkgFolder = insidePkg)
        }
        if (toCopy.isEmpty()) {
            return SafCopyResult(false, -1, "la carpeta ${srcDir.name} no trae archivos de datos reales")
        }
        var bytes = 0L
        var unverified = false
        var obbCount = 0
        val failures = mutableListOf<String>()
        for (f in toCopy) {
            val rel = try {
                f.relativeTo(srcDir).path
            } catch (_: Exception) {
                f.name
            }
            val parentRel = rel.substringBeforeLast(File.separator, "")
            val targetDir = if (parentRel.isEmpty()) dir else navigateOrCreate(dir, parentRel)
            if (targetDir == null) {
                failures += "${f.name}: no se pudo crear la subcarpeta $parentRel en SAF"
                continue
            }
            val r = copyFileToDir(context, targetDir, f)
            if (r.ok) {
                bytes += r.bytes
                if (!r.verified) unverified = true
                if (f.extension.equals("obb", true)) obbCount++
            } else {
                failures += "${f.name}: ${r.reason}"
            }
        }
        if (failures.isNotEmpty()) {
            return SafCopyResult(
                false, bytes,
                "SAF carpeta: ${failures.joinToString("; ")}", unverified
            )
        }
        return SafCopyResult(
            true, bytes,
            "copiado por SAF (${toCopy.size} archivos, $obbCount .obb)" +
                (if (skippedApk > 0) " ($skippedApk .apk omitidos: son el instalador)" else "") +
                if (unverified) " (sin verificar tamaño)" else "",
            unverified
        )
    }

    /** Navega por las partes de una ruta relativa creando las carpetas
     *  que falten. null si no se puede. */
    private fun navigateOrCreate(node: DocumentFile, relativePath: String): DocumentFile? {
        var current = node
        for (part in relativePath.split(File.separator)) {
            if (part.isBlank() || part == ".") continue
            current = try {
                current.findFile(part) ?: current.createDirectory(part)
            } catch (_: Exception) {
                return null
            } ?: return null
        }
        return current
    }

    /** Copia UN archivo a una carpeta DocumentFile (ya resuelta). Si el
     *  destino ya existe con el mismo tamaño, se da por hecho sin
     *  re-copiar. verified=false si se escribió sin error pero el
     *  tamaño del documento no se pudo comprobar (length() = -1). */
    private fun copyFileToDir(context: Context, dir: DocumentFile, src: File): SafCopyResult {
        return try {
            val existing = dir.findFile(src.name)
            if (existing != null && existing.isFile) {
                val len = try { existing.length() } catch (_: Exception) { -1L }
                if (len == src.length()) {
                    return SafCopyResult(true, src.length(), "ya estaba (mismo tamaño)")
                }
                existing.delete()
            }
            val doc = dir.createFile("application/octet-stream", src.name)
                ?: return SafCopyResult(false, -1, "createFile devolvió null")
            var written = 0L
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                src.inputStream().use { input -> written = input.copyTo(out) }
            } ?: return SafCopyResult(false, -1, "openOutputStream devolvió null")
            if (written != src.length()) {
                doc.delete()
                return SafCopyResult(false, written, "incompleto: $written/${src.length()} bytes")
            }
            val checkLen = try { doc.length() } catch (_: Exception) { -1L }
            if (checkLen == -1L) {
                SafCopyResult(true, written, "copiado por SAF (sin verificar tamaño)", verified = false)
            } else if (checkLen != src.length()) {
                doc.delete()
                SafCopyResult(false, written, "SAF: tamaño final $checkLen != ${src.length()}")
            } else {
                SafCopyResult(true, written, "copiado por SAF")
            }
        } catch (e: Exception) {
            SafCopyResult(false, -1, "SAF: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    data class SafVerifyResult(
        val kind: String,
        val treeUri: String,
        val obbReachable: Boolean,
        val listedCount: Int?,
        val writeProbeOk: Boolean,
        val detail: String,
    ) {
        /** true si el árbol sirve para copiar OBBs: se llega a
         *  Android/obb, se lista y se escribe+borra una sonda. */
        val usable: Boolean get() = obbReachable && writeProbeOk
    }

    /** Comprueba DE VERDAD que el árbol SAF concedido sirve para los
     *  OBBs: resuelve Android/obb, lo lista y crea+borra un archivo
     *  sonda ".giraquest-probe" dentro (sin dejar basura). Y segundo
     *  nivel (v0.1.31): la copia real escribe dentro de
     *  Android/obb/<paquete>/, así que también crea una carpeta de
     *  paquete de prueba ("com.giraquest.probe"), escribe y borra una
     *  sonda dentro y borra la carpeta. No crea carpetas de juegos ni
     *  toca .obb existentes. */
    fun verifyTree(context: Context): SafVerifyResult {
        val uri = treeUri(context)
            ?: return SafVerifyResult("", "", false, null, false, "sin árbol concedido")
        val kind = treeKind(context)
        val obb = obbRoot(context)
            ?: return SafVerifyResult(
                kind, uri.toString(), false, null, false,
                "el árbol concedido (vía $kind) no contiene Android/obb"
            )
        val listed = try {
            obb.listFiles().size
        } catch (e: Exception) {
            return SafVerifyResult(
                kind, uri.toString(), true, null, false,
                "Android/obb accesible pero no se puede listar: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
        val probe = try {
            obb.createFile("application/octet-stream", ".giraquest-probe")
        } catch (e: Exception) {
            return SafVerifyResult(
                kind, uri.toString(), true, listed, false,
                "Android/obb lista ($listed elementos) pero no se puede escribir: ${e.javaClass.simpleName}: ${e.message}"
            )
        } ?: return SafVerifyResult(
            kind, uri.toString(), true, listed, false,
            "Android/obb lista ($listed elementos) pero createFile devolvió null"
        )
        val writeOk = try {
            context.contentResolver.openOutputStream(probe.uri)?.use { it.write(1) }
            true
        } catch (_: Exception) {
            false
        }
        try {
            probe.delete()
        } catch (_: Exception) {
        }
        if (!writeOk) {
            return SafVerifyResult(
                kind, uri.toString(), true, listed, false,
                "Android/obb lista ($listed elementos) pero no se pudo escribir en la sonda"
            )
        }
        // Segundo nivel: la copia real escribe en Android/obb/<paquete>/
        // — si no se puede crear una carpeta de paquete ni escribir
        // dentro, la vía SAF no sirve aunque la raíz escriba.
        val probeDir = try {
            obb.findFile(PROBE_PKG) ?: obb.createDirectory(PROBE_PKG)
        } catch (e: Exception) {
            return SafVerifyResult(
                kind, uri.toString(), true, listed, false,
                "Android/obb lista ($listed elementos), sonda en raíz OK, pero no se puede crear una carpeta de paquete dentro: ${e.javaClass.simpleName}: ${e.message}"
            )
        } ?: return SafVerifyResult(
            kind, uri.toString(), true, listed, false,
            "Android/obb lista ($listed elementos), sonda en raíz OK, pero createDirectory devolvió null"
        )
        val pkgProbe = try {
            probeDir.createFile("application/octet-stream", ".giraquest-probe")
        } catch (e: Exception) {
            try {
                probeDir.delete()
            } catch (_: Exception) {
            }
            return SafVerifyResult(
                kind, uri.toString(), true, listed, false,
                "Android/obb lista ($listed elementos), sonda en raíz OK, pero no se puede escribir dentro de una carpeta de paquete: ${e.javaClass.simpleName}: ${e.message}"
            )
        } ?: run {
            try {
                probeDir.delete()
            } catch (_: Exception) {
            }
            return SafVerifyResult(
                kind, uri.toString(), true, listed, false,
                "Android/obb lista ($listed elementos), sonda en raíz OK, pero createFile en la carpeta de paquete devolvió null"
            )
        }
        val pkgWriteOk = try {
            context.contentResolver.openOutputStream(pkgProbe.uri)?.use { it.write(1) }
            true
        } catch (_: Exception) {
            false
        }
        try {
            pkgProbe.delete()
        } catch (_: Exception) {
        }
        try {
            probeDir.delete()
        } catch (_: Exception) {
        }
        return if (pkgWriteOk) {
            SafVerifyResult(
                kind, uri.toString(), true, listed, true,
                "Android/obb accesible (vía $kind): $listed elementos, escritura de sonda OK (raíz y carpeta de paquete)"
            )
        } else {
            SafVerifyResult(
                kind, uri.toString(), true, listed, false,
                "Android/obb lista ($listed elementos), sonda en raíz OK, pero no se pudo escribir en la sonda de la carpeta de paquete"
            )
        }
    }

    /** Resultado de la sonda de escritura DIRECTA (File API). */
    data class DirectProbeResult(val ok: Boolean, val detail: String)

    /** v0.1.35: sonda de escritura DIRECTA por la API de archivos
     *  (sin SAF, sin selector): crea la carpeta de paquete de prueba
     *  "com.giraquest.probe" en Android/obb, escribe un archivo de
     *  prueba dentro, lo borra y borra la carpeta. Es exactamente lo
     *  que necesitaría la copia directa de un OBB: si esto falla, la
     *  copia directa también fallará; si funciona, la copia directa
     *  puede funcionar. No toca ninguna carpeta de juegos ni deja
     *  basura (la carpeta de prueba solo se borra si quedó vacía tras
     *  borrar el archivo). Sin MANAGE_EXTERNAL_STORAGE no se intenta:
     *  el resultado lo dice así. */
    fun probeDirectWrite(): DirectProbeResult {
        if (!Environment.isExternalStorageManager()) {
            return DirectProbeResult(
                false,
                "sin permiso de acceso a todos los archivos"
            )
        }
        val probeDir = File(
            Environment.getExternalStorageDirectory(),
            "Android/obb/$PROBE_PKG"
        )
        val created = !probeDir.exists()
        return try {
            if (created && !probeDir.mkdirs()) {
                return DirectProbeResult(
                    false,
                    "no se puede crear una carpeta en Android/obb (mkdirs=false)"
                )
            }
            val probeFile = File(probeDir, ".giraquest-probe")
            try {
                probeFile.outputStream().use { it.write(1) }
            } catch (e: Exception) {
                runCatching { if (created) probeDir.delete() }
                return DirectProbeResult(
                    false,
                    "no se puede escribir dentro de Android/obb: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            val ok = probeFile.exists() && probeFile.length() == 1L
            runCatching { probeFile.delete() }
            if (created) runCatching { probeDir.delete() }
            if (ok) {
                DirectProbeResult(
                    true,
                    "se puede crear una carpeta y escribir en Android/obb"
                )
            } else {
                DirectProbeResult(
                    false,
                    "el archivo de prueba no quedó escrito"
                )
            }
        } catch (e: Exception) {
            runCatching { File(probeDir, ".giraquest-probe").delete() }
            if (created) runCatching { probeDir.delete() }
            DirectProbeResult(
                false,
                "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    // v0.1.36: acceso al VOLUMEN (StorageVolume.createAccessIntent).
    // La v0.1.35 probó en el visor que la sesión del instalador está
    // MUERTA (LOCATION_MEDIA_OBB no existe en Horizon) y el selector
    // de carpetas (ACTION_OPEN_DOCUMENT_TREE) también: no deja elegir
    // la raíz, ni Android, ni ver obb (SAF RAÍZ MUERTA, confirmado
    // por Edu). La ÚNICA vía que ha funcionado en este dispositivo es
    // la de los exploradores de archivos como X-plore, que crean
    // carpetas dentro de Android/obb: consiguen el acceso con
    // StorageVolume.createAccessIntent(null), un diálogo del sistema
    // que concede TODO el volumen con un toque, SIN navegar por
    // carpetas (el selector no puede esconder obb porque no hay
    // navegación). El árbol que devuelve es la raíz del volumen, así
    // que copyFolderObb/obbRoot lo usan tal cual (vía "root").
    fun volumeAccessIntent(context: Context): Intent? {
        return try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE)
                as? StorageManager ?: return null
            val primary = sm.storageVolumes.firstOrNull { it.isPrimary }
                ?: return null
            primary.createAccessIntent(null)
        } catch (_: Exception) {
            null
        }
    }

    /** Persiste el árbol concedido por el diálogo de volumen (URI +
     *  vía "root") con permiso persistente de lectura+escritura.
     *  false si no se pudo persistir (el diálogo no concedió nada
     *  usable). */
    fun saveVolumeTree(context: Context, uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val settings = SettingsStore(context)
            settings.obbSafTreeUri = uri.toString()
            settings.obbSafTreeKind = detectKind(uri)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Línea de log uniforme para el resultado del acceso al volumen
     *  (la que Edu debe buscar en el log): diálogo, árbol concedido,
     *  sonda de crear carpeta y sonda de escribir. La sonda
     *  (verifyTree) crea la carpeta de paquete y escribe dentro, así
     *  que writeProbeOk=true significa que crear Y escribir
     *  funcionan; si falla, el detalle dice en qué nivel. */
    fun volumeResultLogLine(
        dialogOk: Boolean,
        saved: Boolean,
        verify: SafVerifyResult?
    ): String {
        val probe = if (verify?.writeProbeOk == true) "SÍ" else "NO"
        val detail = verify?.detail ?: "sin verificación"
        return "obb volumen: diálogo=${if (dialogOk) "SÍ" else "NO"}, " +
            "árbol concedido=${if (saved) "SÍ" else "NO"}, " +
            "sonda crear carpeta=$probe, sonda escribir=$probe — $detail"
    }

    // ---------- v0.1.49 / v0.1.53: soporte SAF general / Android/data ----------

    private val ZERO_WIDTH_CHARS = charArrayOf('\u200D', '\u200B', '\u200C', '\uFEFF')

    fun initialDataTreeUri(): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath("tree")
        .appendPath("primary:Android/data")
        .appendPath("document")
        .appendPath("primary:Android/data")
        .build()

    fun initialPackageDataTreeUri(packageName: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath("tree")
        .appendPath("primary:Android/data/$packageName")
        .appendPath("document")
        .appendPath("primary:Android/data/$packageName")
        .build()

    /** URI con carácter de ancho cero (\u200B) en "A\u200Bndroid/data" para
     *  esquivar el filtro PATTERN_RESTRICTED_INITIAL_PATH de DocumentsUI en
     *  Android 13/14. */
    fun initialDataBypassTreeUri(subRel: String? = null): Uri {
        val cleanSub = subRel?.trim('/')?.filterNot { it in ZERO_WIDTH_CHARS }.orEmpty()
        val docRel = if (cleanSub.isEmpty()) "primary:A\u200Bndroid/data"
        else "primary:A\u200Bndroid/data/$cleanSub"
        return Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("tree")
            .appendPath("primary:A\u200Bndroid/data")
            .appendPath("document")
            .appendPath(docRel)
            .build()
    }

    fun initialObbBypassTreeUri(subRel: String? = null): Uri {
        val cleanSub = subRel?.trim('/')?.filterNot { it in ZERO_WIDTH_CHARS }.orEmpty()
        val docRel = if (cleanSub.isEmpty()) "primary:A\u200Bndroid/obb"
        else "primary:A\u200Bndroid/obb/$cleanSub"
        return Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath("tree")
            .appendPath("primary:A\u200Bndroid/obb")
            .appendPath("document")
            .appendPath(docRel)
            .build()
    }

    fun openDocumentTreeIntent(initialUri: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }

    /** Guarda un árbol SAF concedido desde el explorador (p. ej. Android/data
     *  o Android/data/<paquete>) sin tocar un obbSafTreeUri ya existente. */
    fun saveExplorerSafTree(context: Context, uri: Uri): Boolean {
        val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = try {
            context.contentResolver.takePersistableUriPermission(uri, rwFlags)
            true
        } catch (_: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!persisted) return false
        val settings = SettingsStore(context)
        settings.addSafTreeUri(uri.toString())
        if (settings.obbSafTreeUri.isBlank()) {
            val kind = detectKind(uri)
            if (kind == "obb" || kind == "root") {
                settings.obbSafTreeUri = uri.toString()
                settings.obbSafTreeKind = kind
            }
        }
        return true
    }

    private fun allExplorerTreeUris(context: Context): List<Uri> {
        val settings = SettingsStore(context)
        val ordered = LinkedHashSet<String>()
        for (u in settings.safTreeUris) {
            if (u.isNotBlank()) ordered.add(u)
        }
        val obb = settings.obbSafTreeUri
        if (obb.isNotBlank()) ordered.add(obb)
        return ordered.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun treeBaseRelPath(uri: Uri): String? {
        val rawDocId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val docId = rawDocId.filterNot { it in ZERO_WIDTH_CHARS }
        return when {
            docId == "primary" || docId == "primary:" -> ""
            docId.startsWith("primary:") -> docId.removePrefix("primary:").trim('/')
            else -> null
        }
    }

    private fun dirRelPath(dir: File): String? {
        val raw = try {
            dir.relativeTo(Environment.getExternalStorageDirectory()).path
        } catch (_: Exception) {
            return null
        }
        val norm = raw.replace(File.separator, "/").trim('/').filterNot { it in ZERO_WIDTH_CHARS }
        return if (norm == ".") "" else norm
    }

    /** Devuelve true si existe un árbol SAF concedido que cubra `dir`
     *  directamente (él mismo o un ancestro suyo, p. ej. raíz o Android/data). */
    fun hasCoveringTree(context: Context, dir: File): Boolean {
        val rel = dirRelPath(dir) ?: return false
        for (uri in allExplorerTreeUris(context)) {
            val base = treeBaseRelPath(uri) ?: continue
            if (base.isEmpty() || rel == base || rel.startsWith("$base/")) {
                return true
            }
        }
        return false
    }

    /** Localiza el DocumentFile de `dir` buscando en todos los árboles SAF
     *  concedidos (prioriza el árbol más específico). */
    fun findDirNode(context: Context, dir: File): DocumentFile? {
        val rel = dirRelPath(dir) ?: return null
        val candidates = allExplorerTreeUris(context)
            .mapNotNull { uri -> treeBaseRelPath(uri)?.let { base -> uri to base } }
            .filter { (_, base) -> base.isEmpty() || rel == base || rel.startsWith("$base/") }
            .sortedByDescending { (_, base) -> base.length }

        for ((uri, base) in candidates) {
            val root = try {
                DocumentFile.fromTreeUri(context, uri)
            } catch (_: Exception) {
                null
            } ?: continue
            val subRel = when {
                base.isEmpty() -> rel
                rel == base -> ""
                else -> rel.removePrefix("$base/")
            }
            val node = if (subRel.isEmpty()) root
            else navigate(root, subRel.replace("/", File.separator))
            if (node != null && node.isDirectory) return node
        }
        return null
    }

    /** Lista `dir` usando cualquier árbol SAF concedido. Si no hay árbol
     *  padre que cubra `dir`, pero sí árboles hijos directos concedidos
     *  (p. ej. Android/data/<paquete>), devuelve esas carpetas para poder
     *  entrar en ellas. */
    fun listDirAny(context: Context, dir: File): List<DocumentFile>? {
        val node = findDirNode(context, dir)
        if (node != null) {
            return try {
                node.listFiles().toList()
            } catch (_: Exception) {
                null
            }
        }
        val rel = dirRelPath(dir) ?: return null
        val prefix = if (rel.isEmpty()) "" else "$rel/"
        val children = mutableListOf<DocumentFile>()
        val seenNames = HashSet<String>()
        for (uri in allExplorerTreeUris(context)) {
            val base = treeBaseRelPath(uri) ?: continue
            if (prefix.isEmpty() && base.isNotEmpty() && !base.contains('/')) {
                val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                val name = base
                if (doc != null && seenNames.add(name)) children += doc
            } else if (prefix.isNotEmpty() && base.startsWith(prefix)) {
                val rest = base.removePrefix(prefix)
                if (rest.isNotEmpty() && !rest.contains('/')) {
                    val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                    if (doc != null && seenNames.add(rest)) children += doc
                }
            }
        }
        return if (children.isNotEmpty()) children else null
    }

    fun findChildNode(context: Context, dir: File, name: String): DocumentFile? =
        try {
            findDirNode(context, dir)?.findFile(name)
        } catch (_: Exception) {
            null
        }

    fun copyFileToSafDir(context: Context, destDir: File, src: File): SafCopyResult {
        val node = findDirNode(context, destDir)
            ?: return SafCopyResult(false, -1, "sin permiso SAF en ${destDir.name}")
        return copyFileToDir(context, node, src)
    }

    fun copyDirToSafDir(context: Context, destParentDir: File, srcDir: File): SafCopyResult {
        val parentNode = findDirNode(context, destParentDir)
            ?: return SafCopyResult(false, -1, "sin permiso SAF en ${destParentDir.name}")
        val targetRoot = try {
            parentNode.findFile(srcDir.name) ?: parentNode.createDirectory(srcDir.name)
        } catch (_: Exception) {
            null
        } ?: return SafCopyResult(false, -1, "no se pudo crear ${srcDir.name} en SAF")
        val files = try {
            srcDir.walkTopDown().filter { it.isFile }.toList()
        } catch (e: Exception) {
            return SafCopyResult(false, -1, e.message ?: e.javaClass.simpleName)
        }
        var total = 0L
        for (f in files) {
            val rel = runCatching { f.relativeTo(srcDir).path }.getOrDefault(f.name)
            val parentRel = rel.substringBeforeLast(File.separator, "")
            val subNode = if (parentRel.isEmpty()) targetRoot else navigateOrCreate(targetRoot, parentRel)
            if (subNode == null) {
                return SafCopyResult(false, total, "no se pudo crear subcarpeta $parentRel")
            }
            val r = copyFileToDir(context, subNode, f)
            if (!r.ok) return r
            total += r.bytes
        }
        return SafCopyResult(true, total, "copiado por SAF")
    }
}
