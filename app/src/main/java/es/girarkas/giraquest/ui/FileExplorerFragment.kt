package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import es.girarkas.giraquest.GiraQuestApp
import es.girarkas.giraquest.R
import es.girarkas.giraquest.adb.ACTION_PAIRING_RESULT
import es.girarkas.giraquest.adb.AdbPortDiscovery
import es.girarkas.giraquest.adb.EXTRA_PAIRING_OK
import es.girarkas.giraquest.adb.GiraAdbManager
import es.girarkas.giraquest.adb.PairOverlay
import es.girarkas.giraquest.adb.PairingService
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.install.Installer
import es.girarkas.giraquest.install.ObbSaf
import es.girarkas.giraquest.install.ProcessRebirth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un elemento del explorador: archivo/carpeta por File API (directo o
 *  alias FUSE sin restricciones), por ADB shell o por SAF. */
data class ExplorerItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val file: File? = null,
    val docUri: Uri? = null,
    val viaAdb: Boolean = false,
    val adbPath: String? = null,
    val viaAlias: Boolean = false,
) {
    val viaSaf: Boolean get() = docUri != null
    val effectivePath: String? get() = file?.absolutePath ?: adbPath
    val extension: String get() = name.substringAfterLast('.', "")
}

/**
 * Explorador de archivos PROPIO de GiraQuest (v0.1.28, a petición de
 * Edu): para no depender del selector del sistema ni de gestores
 * externos. Navegación real por el almacenamiento, con accesos rápidos,
 * miga de pan, orden, buscador, espacio libre, acciones por tipo de
 * archivo (.apk instalar, .obb copiar, .log/.txt ver+compartir, imágenes
 * vista previa, .7z listar contenido) y selección múltiple (copiar,
 * mover, renombrar, borrar con confirmación, crear carpeta).
 *
 * Todo con MANAGE_EXTERNAL_STORAGE (pedido en el primer arranque): sin
 * ACTION_OPEN_DOCUMENT ni apps de terceros. Si falta el permiso, el
 * explorador lo dice y lleva a concederlo.
 *
 * v0.1.29: en Horizon, File.listFiles() devuelve null en
 * /Android/obb/<paquete> aunque MANAGE_EXTERNAL_STORAGE esté concedido
 * (el explorador enseñaba "vacío" sin motivo). Ahora: el listado se
 * intenta por File, luego por SAF (árbol concedido) y luego por
 * MediaStore, con el resultado de cada intento en el log; si todo
 * falla, se enseña el MOTIVO en vez de una lista vacía engañosa, con
 * botón para conceder la carpeta por SAF. Y hay opción "Mostrar
 * archivos ocultos" (activa por defecto en directorios del sistema).
 */
class FileExplorerFragment : Fragment() {

    companion object {
        /** Ruta con la que abrir (la pone MainActivity.openFileExplorer);
         *  se consume una sola vez. */
        var pendingStartPath: String? = null

        private val DATA_ALIAS_PREFIXES = listOf(
            "Android/\u200Ddata",
            "Android/\u200Bdata",
            "Android/\u200Cdata",
            "Android/\uFEFFdata",
            "A\u200Dndroid/data",
            "A\u200Bndroid/data",
            "Andr\u200Boid/data",
            "Andr\u200Doid/data",
            "Android/d\u200Data",
            "Android/d\u200Bata",
            "Android/data\u200B",
            "Android/data\u200D",
        )

        private val OBB_ALIAS_PREFIXES = listOf(
            "Android/\u200Dobb",
            "Android/\u200Bobb",
            "A\u200Dndroid/obb",
            "A\u200Bndroid/obb",
        )

        private val ZERO_WIDTH_CHARS = charArrayOf('\u200D', '\u200B', '\u200C', '\uFEFF')
    }

    private enum class SortMode { NAME, SIZE, DATE }
    private enum class PickMode { COPY, MOVE }

    private lateinit var installer: Installer
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FileAdapter

    private val rootDir: File get() = Environment.getExternalStorageDirectory()
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var sortMode = SortMode.NAME
    private var sortAsc = true
    private var query = ""
    private val selection = mutableSetOf<ExplorerItem>()
    private var pickMode: PickMode? = null
    private var items: List<ExplorerItem> = emptyList()
    private var rawItems: List<ExplorerItem> = emptyList()
    private var currentDirViaAdb = false
    private var workingDataAliasPrefix: String? = null
    private var workingObbAliasPrefix: String? = null
    private var listRequestSeq = 0
    /** v0.1.29: mostrar archivos que empiezan por '.' (activa por
     *  defecto al entrar en directorios del sistema). */
    private var showHidden = false
    private var backCallback: OnBackPressedCallback? = null

    private fun updateBackEnabled() {
        backCallback?.isEnabled = isResumed && !isHidden
    }
    /** v0.1.29: motivo por el que el listado falló (null = listado OK
     *  o carpeta realmente vacía). */
    private var listFailedReason: String? = null

    private val safTreeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        val ctx = context ?: return@registerForActivityResult
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            val saved = ObbSaf.saveExplorerSafTree(ctx, uri)
            StartupLog.mark(ctx, "explorador: SAF árbol concedido=$saved uri=$uri")
            Toast.makeText(
                ctx,
                if (saved) R.string.files_data_granted else R.string.files_data_grant_failed,
                Toast.LENGTH_LONG
            ).show()
            refresh()
        } else {
            StartupLog.mark(ctx, "explorador: SAF selector cancelado (result=${res.resultCode})")
        }
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PAIRING_RESULT) return
            val ok = intent.getBooleanExtra(EXTRA_PAIRING_OK, false)
            if (ok && isAdded && view != null) {
                refresh()
            }
        }
    }
    private var pairingReceiverRegistered = false

    private fun currentRelPath(dir: File = currentDir): String {
        val raw = try {
            dir.relativeTo(rootDir).path
        } catch (_: Exception) {
            return ""
        }
        val norm = raw.replace(File.separator, "/").trim('/').filterNot { it in ZERO_WIDTH_CHARS }
        return if (norm == ".") "" else norm
    }

    private fun isAndroidDataPath(dir: File = currentDir): Boolean {
        val rel = currentRelPath(dir)
        return rel == "Android/data" || rel.startsWith("Android/data/")
    }

    private fun isAndroidObbPath(dir: File = currentDir): Boolean {
        val rel = currentRelPath(dir)
        return rel == "Android/obb" || rel.startsWith("Android/obb/")
    }

    private fun mapDirViaAlias(dir: File, aliasPrefix: String, normalPrefix: String): File {
        val rel = currentRelPath(dir)
        val suffix = rel.removePrefix(normalPrefix)
        return File(rootDir, aliasPrefix + suffix)
    }

    private fun effectiveIoDir(dir: File): File {
        if (isAndroidDataPath(dir)) {
            workingDataAliasPrefix?.let { return mapDirViaAlias(dir, it, "Android/data") }
        } else if (isAndroidObbPath(dir)) {
            workingObbAliasPrefix?.let { return mapDirViaAlias(dir, it, "Android/obb") }
        }
        return dir
    }

    private fun listDirRaw(mapped: File): Array<File>? {
        val direct = try { mapped.listFiles() } catch (_: Exception) { null }
        if (direct != null) return direct
        val names = try { mapped.list() } catch (_: Exception) { null } ?: return null
        return names.map { File(mapped, it) }.toTypedArray()
    }

    private fun resolveAliasListing(ctx: android.content.Context, dir: File, isData: Boolean): Array<File>? {
        val rel = currentRelPath(dir)
        val normalPrefix = if (isData) "Android/data" else "Android/obb"
        val suffix = rel.removePrefix(normalPrefix)
        val candidates = if (isData) DATA_ALIAS_PREFIXES else OBB_ALIAS_PREFIXES
        val cached = if (isData) workingDataAliasPrefix else workingObbAliasPrefix
        val needMoreThanOne = isData && rel == "Android/data"
        if (cached != null) {
            val mapped = mapDirViaAlias(dir, cached, normalPrefix)
            val list = listDirRaw(mapped)
            if (list != null && (if (needMoreThanOne) list.size > 1 else list.isNotEmpty())) {
                return list
            }
        }
        var bestPrefix: String? = null
        var bestList: Array<File>? = null
        for (prefix in candidates) {
            val mapped = mapDirViaAlias(dir, prefix, normalPrefix)
            val list = listDirRaw(mapped) ?: continue
            if (bestList == null || list.size > bestList.size) {
                bestPrefix = prefix
                bestList = list
            }
            if (if (needMoreThanOne) list.size > 1 else list.isNotEmpty()) break
        }
        if ((bestList == null || bestList.isEmpty()) && suffix.startsWith("/")) {
            val sub = suffix.removePrefix("/")
            val extraDirs = if (isData) {
                listOf(
                    "Android/\u200Ddata/\u200D$sub",
                    "Android/\u200Bdata/\u200B$sub",
                    "Android/data/\u200D$sub",
                    "Android/data/\u200B$sub",
                )
            } else {
                listOf(
                    "Android/\u200Dobb/\u200D$sub",
                    "Android/\u200Bobb/\u200B$sub",
                    "Android/obb/\u200D$sub",
                    "Android/obb/\u200B$sub",
                )
            }
            for (relCandidate in extraDirs) {
                val list = listDirRaw(File(rootDir, relCandidate)) ?: continue
                if (bestList == null || list.size > bestList.size) {
                    bestList = list
                }
                if (list.isNotEmpty()) break
            }
        }
        if (bestPrefix != null && bestList != null) {
            if (isData) workingDataAliasPrefix = bestPrefix else workingObbAliasPrefix = bestPrefix
            val safeLabel = bestPrefix
                .replace("\u200D", "\\u200D")
                .replace("\u200B", "\\u200B")
                .replace("\u200C", "\\u200C")
                .replace("\uFEFF", "\\uFEFF")
            StartupLog.mark(
                ctx,
                "explorador: alias FUSE directo OK ($safeLabel) en ${dir.absolutePath}: ${bestList.size} elementos"
            )
        }
        return bestList
    }

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private val obbNameRegex =
        Regex("^(main|patch|overflow)\\.(\\d+)\\.(.+)\\.obb$", RegexOption.IGNORE_CASE)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_file_explorer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        installer = Installer(requireContext())
        recycler = view.findViewById(R.id.recycler_files)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = FileAdapter(
            items,
            selection,
            dateFmt,
            onTap = { onFileTap(it) },
            onLongPress = { file ->
                if (selection.add(file)) refresh()
            },
        )
        recycler.adapter = adapter

        // Ruta pedida al abrir desde "Instalar APK" (Descargas).
        pendingStartPath?.let { path ->
            pendingStartPath = null
            val dir = File(path)
            if (dir.isDirectory) currentDir = dir
        }

        view.findViewById<Button>(R.id.btn_files_grant).setOnClickListener {
            PermissionsHelper.openAppAllFilesSettings(requireActivity())
        }
        view.findViewById<Button>(R.id.btn_files_new_folder).setOnClickListener {
            askNewFolder()
        }
        view.findViewById<Button>(R.id.btn_quick_downloads).setOnClickListener {
            navigateTo(File(rootDir, "Download"))
        }
        view.findViewById<Button>(R.id.btn_quick_giraquest).setOnClickListener {
            navigateTo(File(rootDir, SettingsStore(requireContext()).downloadFolder))
        }
        view.findViewById<Button>(R.id.btn_quick_obb).setOnClickListener {
            navigateTo(File(rootDir, "Android/obb"))
        }
        view.findViewById<Button>(R.id.btn_quick_data).setOnClickListener {
            navigateTo(File(rootDir, "Android/data"))
        }
        view.findViewById<EditText>(R.id.et_files_search).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim()
                    applyFilterAndSort()
                }
            }
        )
        view.findViewById<Button>(R.id.btn_sort_name).setOnClickListener { toggleSort(SortMode.NAME) }
        view.findViewById<Button>(R.id.btn_sort_size).setOnClickListener { toggleSort(SortMode.SIZE) }
        view.findViewById<Button>(R.id.btn_sort_date).setOnClickListener { toggleSort(SortMode.DATE) }
        // v0.1.29: mostrar/ocultar archivos que empiezan por '.'.
        view.findViewById<Button>(R.id.btn_files_hidden).setOnClickListener {
            showHidden = !showHidden
            applyFilterAndSort()
            updateSortButtons()
        }
        // v0.1.53: en Android/data y Android/obb restringidos ofrece un menú de
        // desbloqueo real (SAF con bypass \u200B, SAF por paquete, ADB local o
        // app Archivos del sistema).
        val btnGrantFolder = view.findViewById<Button>(R.id.btn_files_grant_folder)
        btnGrantFolder.setText(R.string.files_unlock_btn)
        btnGrantFolder.setOnClickListener {
            showUnlockFolderDialog()
        }

        view.findViewById<Button>(R.id.btn_files_paste).setOnClickListener { doPaste() }
        view.findViewById<Button>(R.id.btn_files_pick_cancel).setOnClickListener {
            pickMode = null
            refresh()
        }
        view.findViewById<Button>(R.id.btn_files_copy).setOnClickListener { startPick(PickMode.COPY) }
        view.findViewById<Button>(R.id.btn_files_move).setOnClickListener { startPick(PickMode.MOVE) }
        view.findViewById<Button>(R.id.btn_files_rename).setOnClickListener { askRename() }
        view.findViewById<Button>(R.id.btn_files_delete).setOnClickListener { askDelete() }

        // Atrás: primero cancela pegar/selección, luego sube de carpeta.
        val cb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    pickMode != null -> {
                        pickMode = null
                        refresh()
                    }
                    selection.isNotEmpty() -> {
                        selection.clear()
                        refresh()
                    }
                    currentDir != rootDir -> {
                        navigateTo(currentDir.parentFile ?: rootDir, trustIsDir = true)
                    }
                    else -> {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        backCallback = cb
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
        updateBackEnabled()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        if (!pairingReceiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                pairingReceiver,
                IntentFilter(ACTION_PAIRING_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pairingReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (pairingReceiverRegistered) {
            runCatching { requireContext().unregisterReceiver(pairingReceiver) }
            pairingReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        pendingStartPath?.let { path ->
            pendingStartPath = null
            val dir = File(path)
            if (dir.isDirectory && dir.absolutePath.startsWith(rootDir.absolutePath)) {
                currentDir = dir
            }
        }
        updateBackEnabled()
        // Al volver de conceder el permiso, re-comprobar.
        refresh()
    }

    override fun onPause() {
        super.onPause()
        updateBackEnabled()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateBackEnabled()
    }

    // ---------- Listado ----------

    private fun hasFilesPermission(): Boolean = Environment.isExternalStorageManager()

    private fun navigateTo(dir: File, trustIsDir: Boolean = false) {
        val cleanPath = dir.absolutePath.filterNot { it in ZERO_WIDTH_CHARS }
        val cleanDir = File(cleanPath)
        val target = if (trustIsDir || cleanDir.isDirectory || isAndroidDataPath(cleanDir) || isAndroidObbPath(cleanDir)) cleanDir else rootDir
        // No salir por encima de la raíz del almacenamiento.
        currentDir = if (target.absolutePath.startsWith(rootDir.absolutePath)) target else rootDir
        // v0.1.29: en directorios del sistema (Android/obb, Android/data)
        // los ocultos se enseñan por defecto.
        val rel = currentRelPath(currentDir)
        if (rel.startsWith("Android/obb") || rel.startsWith("Android/data")) {
            showHidden = true
        }
        if (pickMode == null) selection.clear()
        refresh()
    }

    private fun toggleSort(mode: SortMode) {
        if (sortMode == mode) sortAsc = !sortAsc else {
            sortMode = mode
            sortAsc = true
        }
        applyFilterAndSort()
    }

    private fun refresh() {
        if (!isAdded) return
        val v = view ?: return
        val granted = hasFilesPermission()
        v.findViewById<View>(R.id.card_files_perm).visibility =
            if (granted) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.recycler_files).visibility =
            if (granted) View.VISIBLE else View.GONE
        if (!granted) return
        refreshFreeSpace()
        refreshBreadcrumb()
        refreshList()
        refreshBars()
    }

    private fun refreshFreeSpace() {
        val v = view ?: return
        val free = try {
            StatFs(rootDir.path).availableBytes
        } catch (_: Exception) {
            -1L
        }
        v.findViewById<TextView>(R.id.tv_files_free).text =
            if (free >= 0) getString(R.string.files_free, RepoAdapter.formatSize(free))
            else ""
    }

    private fun refreshBreadcrumb() {
        val v = view ?: return
        val row = v.findViewById<LinearLayout>(R.id.breadcrumb_row)
        row.removeAllViews()
        val rel = try {
            currentDir.relativeTo(rootDir)
        } catch (_: Exception) {
            null
        }
        val segments = mutableListOf<Pair<String, File>>()
        segments += getString(R.string.files_root) to rootDir
        if (rel != null && rel.path.isNotBlank()) {
            var acc = rootDir
            for (part in rel.path.split(File.separator)) {
                acc = File(acc, part)
                segments += part to acc
            }
        }
        for ((i, seg) in segments.withIndex()) {
            if (i > 0) {
                row.addView(TextView(requireContext()).apply {
                    text = " › "
                    textSize = 15f
                })
            }
            val isLast = i == segments.lastIndex
            row.addView(TextView(requireContext()).apply {
                text = seg.first
                textSize = 15f
                setTextColor(
                    if (isLast) android.graphics.Color.WHITE
                    else ContextCompat.getColor(requireContext(), R.color.gira_purple_dark)
                )
                if (!isLast) {
                    setOnClickListener { navigateTo(seg.second, trustIsDir = true) }
                }
                setTypeface(typeface, if (isLast) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            })
        }
    }

    private fun isOwnPackageRestrictedDir(ctx: Context, rel: String): Boolean {
        val pkg = ctx.packageName
        return rel == "Android/data/$pkg" || rel.startsWith("Android/data/$pkg/") ||
            rel == "Android/obb/$pkg" || rel.startsWith("Android/obb/$pkg/")
    }

    private fun refreshList() {
        if (!isAdded) return
        val v = view ?: return
        val dir = currentDir
        val rel = currentRelPath(dir)
        val inData = isAndroidDataPath(dir)
        val inObb = isAndroidObbPath(dir)
        val inDataSub = inData && rel != "Android/data"
        val inObbSub = inObb && rel != "Android/obb"
        val seq = ++listRequestSeq
        val ctx = requireContext().applicationContext
        val btnGrant = v.findViewById<Button>(R.id.btn_files_grant_folder)

        listFailedReason = null
        val adb = GiraAdbManager.getInstance(ctx)

        viewLifecycleOwner.lifecycleScope.launch {
            data class LoadOutcome(
                val entries: List<ExplorerItem>,
                val viaAdb: Boolean,
                val failedReason: String?,
                val showUnlockBtn: Boolean,
            )

            val outcome = withContext(Dispatchers.IO) {
                var fileListNull = false
                var fileEntries: List<ExplorerItem>? = null
                try {
                    val files = dir.listFiles()
                    if (files != null) {
                        fileEntries = files.map {
                            ExplorerItem(
                                name = it.name,
                                isDirectory = it.isDirectory,
                                size = try { it.length() } catch (_: Exception) { 0L },
                                lastModified = try { it.lastModified() } catch (_: Exception) { 0L },
                                file = it,
                            )
                        }
                        StartupLog.mark(
                            ctx,
                            "explorador: listar ${dir.absolutePath} vía File: ${fileEntries.size} elementos"
                        )
                    } else {
                        fileListNull = true
                        StartupLog.mark(
                            ctx,
                            "explorador: listar ${dir.absolutePath} vía File: null (sin permiso directo)"
                        )
                    }
                } catch (e: Exception) {
                    fileListNull = true
                    StartupLog.mark(
                        ctx,
                        "explorador: listar ${dir.absolutePath} vía File: excepción ${e.javaClass.simpleName}: ${e.message}"
                    )
                }

                // 1) En Android/data (o Android/obb restringido), probar alias FUSE/casefold
                //    directo (sin ADB, sin depuración inalámbrica, sin selector externo).
                //    Importante: en subcarpetas Android/data/<paquete>, File.listFiles()
                //    suele devolver [] (no null), por eso se prueba también cuando está vacío.
                val shouldTryAlias = (inData && (fileListNull || fileEntries == null || fileEntries.isEmpty() || (rel == "Android/data" && fileEntries.size <= 1))) ||
                    (inObb && (fileListNull || fileEntries == null || fileEntries.isEmpty()))
                if (shouldTryAlias) {
                    val aliasFiles = resolveAliasListing(ctx, dir, isData = inData)
                    if (aliasFiles != null && aliasFiles.isNotEmpty() && !(inData && rel == "Android/data" && aliasFiles.size <= 1 && fileEntries != null && aliasFiles.size <= fileEntries.size)) {
                        val mapped = aliasFiles.map {
                            ExplorerItem(
                                name = it.name,
                                isDirectory = it.isDirectory,
                                size = try { it.length() } catch (_: Exception) { 0L },
                                lastModified = try { it.lastModified() } catch (_: Exception) { 0L },
                                file = it,
                                viaAlias = true,
                            )
                        }
                        return@withContext LoadOutcome(mapped, viaAdb = false, failedReason = null, showUnlockBtn = false)
                    }
                }

                if (fileEntries != null && fileEntries.isNotEmpty() && !(inData && rel == "Android/data" && fileEntries.size <= 1)) {
                    val partialSubUnlock = inDataSub && !ObbSaf.hasCoveringTree(ctx, dir) && !adb.connected && !isOwnPackageRestrictedDir(ctx, rel)
                    return@withContext LoadOutcome(fileEntries, viaAdb = false, failedReason = null, showUnlockBtn = partialSubUnlock)
                }

                // 2) SAF si ya hay un árbol concedido que cubra esta carpeta (incluido bypass \u200B).
                val safCovering = ObbSaf.hasCoveringTree(ctx, dir)
                if (safCovering) {
                    val saf = try {
                        ObbSaf.listDirAny(ctx, dir)
                    } catch (_: Exception) {
                        null
                    }
                    if (saf != null) {
                        StartupLog.mark(ctx, "explorador: listar ${dir.absolutePath} vía SAF: ${saf.size} elementos")
                        return@withContext LoadOutcome(
                            entries = saf.map {
                                ExplorerItem(
                                    name = it.name?.filterNot { c -> c in ZERO_WIDTH_CHARS } ?: "?",
                                    isDirectory = it.isDirectory,
                                    size = it.length(),
                                    lastModified = it.lastModified(),
                                    docUri = it.uri,
                                )
                            },
                            viaAdb = false,
                            failedReason = null,
                            showUnlockBtn = false,
                        )
                    }
                }

                // 3) Si ADB está conectado o emparejado previamente, reconectar y listar con UID 2000.
                if (!adb.authRejected) {
                    if (!adb.connected && (inData || inObb || fileListNull)) {
                        adb.ensureConnectedForExplorer(allowMdnsDiscovery = true)
                    }
                    if (adb.connected) {
                        val adbEntries = try {
                            adb.listDirShell(dir.absolutePath)
                        } catch (_: Exception) {
                            null
                        }
                        if (adbEntries != null) {
                            StartupLog.mark(ctx, "explorador: listar ${dir.absolutePath} vía ADB activo: ${adbEntries.size} elementos")
                            return@withContext LoadOutcome(
                                entries = adbEntries.map {
                                    ExplorerItem(
                                        name = it.name,
                                        isDirectory = it.isDirectory,
                                        size = it.size,
                                        lastModified = it.lastModified,
                                        viaAdb = true,
                                        adbPath = it.fullPath,
                                    )
                                },
                                viaAdb = true,
                                failedReason = null,
                                showUnlockBtn = false,
                            )
                        }
                    }
                }

                // 4) Fallback SAF para carpetas padre que muestran subárboles concedidos.
                if (!safCovering) {
                    val saf = try {
                        ObbSaf.listDirAny(ctx, dir)
                    } catch (_: Exception) {
                        null
                    }
                    if (saf != null) {
                        StartupLog.mark(ctx, "explorador: listar ${dir.absolutePath} vía SAF hijo: ${saf.size} elementos")
                        return@withContext LoadOutcome(
                            entries = saf.map {
                                ExplorerItem(
                                    name = it.name?.filterNot { c -> c in ZERO_WIDTH_CHARS } ?: "?",
                                    isDirectory = it.isDirectory,
                                    size = it.length(),
                                    lastModified = it.lastModified(),
                                    docUri = it.uri,
                                )
                            },
                            viaAdb = false,
                            failedReason = null,
                            showUnlockBtn = inDataSub || inObbSub,
                        )
                    }
                }

                // 5) En Android/data/<pkg> (o Android/obb/<pkg>), File.listFiles() suele devolver []
                //    (array NO nulo pero vacío) aunque la carpeta tenga archivos reales ocultos por
                //    Scoped Storage / fuse-bpf. Si no tenemos SAF ni ADB ni es nuestro paquete, NO
                //    la tratamos como "Carpeta vacía" silenciosa: enseñamos aviso + botón Desbloquear.
                if ((inDataSub || inObbSub) && !isOwnPackageRestrictedDir(ctx, rel)) {
                    val reason = if (inData) ctx.getString(R.string.files_data_restricted_hint)
                    else ctx.getString(R.string.files_list_null_reason)
                    return@withContext LoadOutcome(emptyList(), viaAdb = false, failedReason = reason, showUnlockBtn = true)
                }

                if (fileEntries != null) {
                    return@withContext LoadOutcome(fileEntries, viaAdb = false, failedReason = null, showUnlockBtn = false)
                }

                val media = listViaMediaStore(dir)
                StartupLog.mark(
                    ctx,
                    "explorador: listar ${dir.absolutePath} vía MediaStore: " +
                        if (media == null) "falló la consulta" else "${media.size} elementos"
                )
                val list = media ?: emptyList()
                val reason = if (list.isEmpty() && fileListNull) {
                    if (inData) ctx.getString(R.string.files_data_restricted_hint)
                    else ctx.getString(R.string.files_list_null_reason)
                } else null
                LoadOutcome(list, viaAdb = false, failedReason = reason, showUnlockBtn = reason != null)
            }

            if (!isAdded || seq != listRequestSeq || view == null) return@launch
            currentDirViaAdb = outcome.viaAdb
            rawItems = outcome.entries
            listFailedReason = outcome.failedReason
            applyFilterAndSort()

            if (outcome.showUnlockBtn) {
                btnGrant.visibility = View.VISIBLE
                btnGrant.setText(R.string.files_unlock_btn)
            } else {
                btnGrant.visibility = View.GONE
            }
        }
    }

    private fun applyFilterAndSort() {
        if (!isAdded) return
        val v = view ?: return
        val filtered = rawItems
            .filter { showHidden || !it.name.startsWith(".") }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val byName = compareBy<ExplorerItem> { it.name.lowercase() }
        val bySize = compareBy<ExplorerItem> { if (it.isDirectory) -1L else it.size }
        val byDate = compareBy<ExplorerItem> { it.lastModified }
        val cmp = when (sortMode) {
            SortMode.NAME -> byName
            SortMode.SIZE -> bySize
            SortMode.DATE -> byDate
        }
        val sorted = filtered.sortedWith(
            compareByDescending<ExplorerItem> { it.isDirectory }
                .then(if (sortAsc) cmp else cmp.reversed())
        )
        items = sorted
        adapter.update(items)
        val tvEmpty = v.findViewById<TextView>(R.id.tv_files_empty)
        if (items.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = listFailedReason ?: getString(R.string.files_empty_real)
        } else {
            tvEmpty.visibility = View.GONE
        }
        updateSortButtons()
    }

    /** Listado alternativo por MediaStore (por ruta relativa). En
     *  Android 11+ MediaStore NO indexa Android/obb ni Android/data,
     *  así que aquí casi siempre salen 0: se intenta igual y queda en
     *  el log, como vía alternativa documentada. null si la consulta
     *  falló; lista (posiblemente vacía) si fue bien. */
    private fun listViaMediaStore(dir: File): List<ExplorerItem>? {
        return try {
            val rel = try {
                dir.relativeTo(rootDir).path.replace(File.separator, "/") + "/"
            } catch (_: Exception) {
                return null
            }
            val uri = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val cursor = requireContext().contentResolver.query(
                uri, proj,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(rel), null
            ) ?: return null
            cursor.use {
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val out = mutableListOf<ExplorerItem>()
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx) ?: continue
                    out += ExplorerItem(
                        name = name,
                        isDirectory = false,
                        size = it.getLong(sizeIdx),
                        lastModified = it.getLong(dateIdx) * 1000,
                        file = File(dir, name),
                    )
                }
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateSortButtons() {
        val v = view ?: return
        val arrow = if (sortAsc) "↑" else "↓"
        v.findViewById<Button>(R.id.btn_sort_name).text =
            getString(R.string.files_sort_name) + if (sortMode == SortMode.NAME) " $arrow" else ""
        v.findViewById<Button>(R.id.btn_sort_size).text =
            getString(R.string.files_sort_size) + if (sortMode == SortMode.SIZE) " $arrow" else ""
        v.findViewById<Button>(R.id.btn_sort_date).text =
            getString(R.string.files_sort_date) + if (sortMode == SortMode.DATE) " $arrow" else ""
        v.findViewById<Button>(R.id.btn_files_hidden).text =
            getString(if (showHidden) R.string.files_hide_hidden else R.string.files_show_hidden)
    }

    private fun refreshBars() {
        val v = view ?: return
        val pick = pickMode
        v.findViewById<View>(R.id.bar_files_pick).visibility =
            if (pick != null) View.VISIBLE else View.GONE
        if (pick != null) {
            v.findViewById<TextView>(R.id.tv_files_pick_info).text =
                getString(
                    if (pick == PickMode.COPY) R.string.files_pick_copy else R.string.files_pick_move,
                    selection.size
                )
        }
        v.findViewById<View>(R.id.bar_files_select).visibility =
            if (selection.isNotEmpty() && pick == null) View.VISIBLE else View.GONE
        if (selection.isNotEmpty()) {
            v.findViewById<TextView>(R.id.tv_files_sel_count).text =
                getString(R.string.files_selected, selection.size)
            // Renombrar solo tiene sentido con un elemento.
            v.findViewById<Button>(R.id.btn_files_rename).isEnabled = selection.size == 1
        }
    }

    // ---------- Acciones al tocar ----------

    private fun onFileTap(item: ExplorerItem) {
        if (pickMode != null) {
            if (item.isDirectory) navigateTo(File(currentDir, item.name), trustIsDir = true)
            return
        }
        if (selection.isNotEmpty()) {
            if (!selection.remove(item)) selection.add(item)
            refresh()
            return
        }
        if (item.isDirectory) {
            navigateTo(File(currentDir, item.name), trustIsDir = true)
            return
        }
        if (item.viaAdb) {
            onAdbTap(item)
            return
        }
        if (item.viaSaf) {
            onSafTap(item)
            return
        }
        val file = item.file ?: return
        when {
            file.extension.equals("apk", true) -> askInstallApk(file)
            file.extension.equals("obb", true) -> askCopyObb(file)
            file.extension.lowercase() in setOf("log", "txt", "json", "xml", "cfg", "ini", "yaml", "yml", "properties") -> showTextFile(file)
            file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") -> showImagePreview(file)
            file.extension.equals("7z", true) -> showArchiveInfo(file)
            else -> showFileInfo(file)
        }
    }

    /** Toque en un archivo dentro de una carpeta listada vía ADB shell
     *  (p. ej. /Android/data): lo copia al caché accesible de la app
     *  para abrirlo, previsualizarlo, instalarlo o compartirlo sin límites. */
    private fun onAdbTap(item: ExplorerItem) {
        val srcPath = item.adbPath ?: return
        val ctx = requireContext()
        val cacheBase = ctx.externalCacheDir ?: ctx.cacheDir
        val tmpDir = File(cacheBase, "explorer_tmp")
        val tmpFile = File(tmpDir, item.name)
        val ext = item.extension.lowercase()
        val adb = GiraAdbManager.getInstance(ctx)

        Toast.makeText(ctx, getString(R.string.files_adb_loading), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = adb.stageRestrictedFileToCache(srcPath, tmpFile)
            if (!isAdded) return@launch
            if (!ok) {
                Toast.makeText(ctx, getString(R.string.files_read_failed, item.name), Toast.LENGTH_LONG).show()
                return@launch
            }
            when {
                ext == "apk" -> askInstallApk(tmpFile)
                ext == "obb" -> askCopyObb(tmpFile)
                ext in setOf("txt", "log", "json", "xml", "cfg", "ini", "yaml", "yml", "properties") -> showTextFile(tmpFile)
                ext in setOf("jpg", "jpeg", "png", "webp", "gif") -> showImagePreview(tmpFile)
                ext == "7z" -> showArchiveInfo(tmpFile)
                else -> showAdbFileInfo(item, tmpFile)
            }
        }
    }

    private fun showAdbFileInfo(item: ExplorerItem, cachedFile: File) {
        val ctx = requireContext()
        val path = item.adbPath ?: File(currentDir, item.name).absolutePath
        AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setMessage("${RepoAdapter.formatSize(item.size)} · ${dateFmt.format(Date(item.lastModified))}\n\n$path")
            .setPositiveButton(R.string.share) { _, _ -> shareFile(cachedFile) }
            .setNeutralButton(R.string.files_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = GiraAdbManager.getInstance(ctx).deleteShell(path)
                    if (!isAdded) return@launch
                    Toast.makeText(
                        ctx,
                        if (ok) getString(R.string.files_deleted, 1) else getString(R.string.files_delete_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Toque en un elemento listado por SAF (File.listFiles() devolvió
     *  null en esa carpeta). Las carpetas se navegan por ruta (el
     *  listado SAF de la subcarpeta se resuelve igual); los archivos
     *  enseñan info + compartir + borrar por DocumentFile. Un .apk por
     *  SAF se instala copiándolo primero a caché (el instalador del
     *  sistema necesita un File). */
    private fun onSafTap(item: ExplorerItem) {
        val uri = item.docUri ?: return
        if (item.isDirectory) {
            navigateTo(File(currentDir, item.name), trustIsDir = true)
            return
        }
        val ext = item.extension.lowercase()
        if (ext == "apk" || ext == "obb" || ext == "7z") {
            viewLifecycleOwner.lifecycleScope.launch {
                val cached = withContext(Dispatchers.IO) {
                    try {
                        val tmpDir = File(requireContext().cacheDir, "saf_tmp").apply { mkdirs() }
                        val tmp = File(tmpDir, item.name)
                        requireContext().contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        tmp.takeIf { it.exists() && it.length() > 0 }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!isAdded) return@launch
                if (cached == null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.files_read_failed, item.name),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    StartupLog.mark(
                        requireContext(),
                        "explorador: archivo SAF ${item.name} copiado a caché (${cached.length()} bytes)"
                    )
                    when (ext) {
                        "apk" -> askInstallApk(cached)
                        "obb" -> askCopyObb(cached)
                        "7z" -> showArchiveInfo(cached)
                    }
                }
            }
            return
        }
        if (ext in setOf("txt", "log", "json", "xml", "cfg", "ini", "yaml", "yml", "properties")) {
            showSafTextFile(item)
            return
        }
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) {
            showSafImagePreview(item)
            return
        }
        showSafFileInfo(item)
    }

    private fun showSafTextFile(item: ExplorerItem) {
        val uri = item.docUri ?: return
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(200_000)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                val hasMore = input.read() != -1
                String(buf, 0, total, Charsets.UTF_8) +
                    if (hasMore) getString(R.string.files_text_truncated) else ""
            } ?: getString(R.string.files_read_failed, item.name)
        } catch (e: Exception) {
            getString(R.string.files_read_failed, e.message ?: e.javaClass.simpleName)
        }
        val tv = TextView(requireContext()).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setView(scroll)
            .setPositiveButton(R.string.share) { _, _ -> shareUri(item.name, uri) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showSafImagePreview(item: ExplorerItem) {
        val uri = item.docUri ?: return
        val iv = ImageView(requireContext()).apply {
            adjustViewBounds = true
            setPadding(8, 8, 8, 8)
        }
        iv.load(uri)
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setView(iv)
            .setPositiveButton(R.string.share) { _, _ -> shareUri(item.name, uri) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareUri(name: String, uri: Uri) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(requireContext().contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(share, getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.files_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Ficha de un archivo listado por SAF: tamaño/fecha/ruta, compartir
     *  por su URI de contenido y borrar por DocumentFile. */
    private fun showSafFileInfo(item: ExplorerItem) {
        val uri = item.docUri ?: return
        val path = File(currentDir, item.name).absolutePath
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setMessage(
                "${RepoAdapter.formatSize(item.size)} · ${dateFmt.format(Date(item.lastModified))}\n\n$path\n\n(visto por SAF: File no puede leer esta carpeta)"
            )
            .setPositiveButton(R.string.share) { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_SUBJECT, item.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(requireContext().contentResolver, item.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(share, getString(R.string.share)))
                } catch (_: Exception) {
                    Toast.makeText(
                        requireContext(),
                        R.string.files_share_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNeutralButton(R.string.files_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            DocumentFile.fromSingleUri(requireContext(), uri)?.delete() == true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (!isAdded) return@launch
                    StartupLog.mark(
                        requireContext(),
                        "explorador: borrar por SAF ${item.name}: ${if (ok) "ok" else "falló"}"
                    )
                    Toast.makeText(
                        requireContext(),
                        if (ok) getString(R.string.files_deleted, 1)
                        else getString(R.string.files_delete_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun askInstallApk(apkFile: File) {
        if (PermissionsHelper.showMissingDialog(
                requireActivity(),
                PermissionsHelper.missing(requireContext())
            )
        ) return
        val pkg = installer.packageNameOfApk(apkFile)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_title, apkFile.name))
            .setMessage(
                getString(R.string.files_apk_confirm, RepoAdapter.formatSize(apkFile.length())) +
                    if (pkg != null) "\n\n$pkg" else ""
            )
            .setPositiveButton(R.string.install) { _, _ ->
                val appCtx = context?.applicationContext
                StartupLog.mark(
                    requireContext(),
                    "explorador: instalar APK ${apkFile.absolutePath}"
                )
                installer.installLocalApk(apkFile) { ok, msg ->
                    val ctx = context
                    if (!isAdded || ctx == null) {
                        appCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
                        return@installLocalApk
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    if (ok) {
                        LocalObbOffer.maybeOffer(this, installer, apkFile) {
                            if (isAdded && view != null) refresh()
                        }
                    } else {
                        if (isAdded && view != null) refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askCopyObb(obbFile: File) {
        val match = obbNameRegex.find(obbFile.name)
        val deduced = match?.groupValues?.getOrNull(3)
        if (deduced.isNullOrBlank()) {
            // No se deduce el paquete del nombre: preguntarlo.
            val input = EditText(requireContext()).apply {
                hint = "com.ejemplo.juego"
                setSingleLine()
            }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.files_obb_title, obbFile.name))
                .setMessage(getString(R.string.files_obb_ask_package))
                .setView(input)
                .setPositiveButton(R.string.files_obb_copy) { _, _ ->
                    val pkg = input.text.toString().trim()
                    if (pkg.isBlank() || !pkg.contains(".")) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.files_obb_bad_package),
                            Toast.LENGTH_LONG
                        ).show()
                        return@setPositiveButton
                    }
                    doCopyObb(pkg, listOf(obbFile))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.files_obb_title, obbFile.name))
            .setMessage(
                getString(
                    R.string.files_obb_confirm,
                    RepoAdapter.formatSize(obbFile.length()),
                    deduced
                )
            )
            .setPositiveButton(R.string.files_obb_copy) { _, _ ->
                doCopyObb(deduced, listOf(obbFile))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doCopyObb(packageName: String, files: List<File>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val res = installer.copyLocalObbFiles(packageName, files)
            if (!isAdded) return@launch
            val msg = when (res) {
                is Installer.DirectObbResult.Ok ->
                    getString(
                        R.string.local_obb_done,
                        res.files.size,
                        RepoAdapter.formatSize(res.bytes),
                        packageName
                    )
                is Installer.DirectObbResult.NotGranted ->
                    getString(R.string.local_obb_not_granted)
                is Installer.DirectObbResult.Failed ->
                    getString(R.string.local_obb_failed, res.reason)
            }
            StartupLog.mark(
                requireContext(),
                "explorador: OBB ${files.joinToString(", ") { it.name }} → $packageName: $msg"
            )
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun showTextFile(file: File) {
        val text = try {
            val bytes = file.readBytes()
            val cut = bytes.size > 200_000
            val shown = if (cut) bytes.copyOf(200_000) else bytes
            String(shown, Charsets.UTF_8) + if (cut) getString(R.string.files_text_truncated) else ""
        } catch (e: Exception) {
            getString(R.string.files_read_failed, e.message ?: e.javaClass.simpleName)
        }
        val tv = TextView(requireContext()).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(requireContext()).apply { addView(tv) }
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.share) { _, _ -> shareFile(file) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showImagePreview(file: File) {
        val iv = ImageView(requireContext()).apply {
            adjustViewBounds = true
            setPadding(8, 8, 8, 8)
        }
        iv.load(file)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(iv)
            .setPositiveButton(R.string.share) { _, _ -> shareFile(file) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showArchiveInfo(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { list7z(file) }
            if (!isAdded) return@launch
            AlertDialog.Builder(requireContext())
                .setTitle(file.name)
                .setMessage(info)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** Lista el contenido de un .7z sin extraer (sin contraseña: solo
     *  los nombres de las entradas, que no suelen estar cifrados). */
    private fun list7z(file: File): String {
        val header = "${file.name}\n${RepoAdapter.formatSize(file.length())}\n\n"
        return try {
            SevenZFile(file).use { sevenZ ->
                var count = 0
                var total = 0L
                val lines = mutableListOf<String>()
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    count++
                    total += entry.size
                    if (lines.size < 60) {
                        lines += "• ${entry.name} (${RepoAdapter.formatSize(entry.size)})"
                    }
                }
                if (count == 0) {
                    header + getString(R.string.files_7z_empty)
                } else {
                    header + getString(R.string.files_7z_summary, count, RepoAdapter.formatSize(total)) +
                        "\n\n" + lines.joinToString("\n") +
                        if (count > 60) getString(R.string.files_more_items, count - 60) else ""
                }
            }
        } catch (e: Exception) {
            header + getString(R.string.files_7z_unreadable, e.javaClass.simpleName)
        }
    }

    private fun showFileInfo(file: File) {
        val displayPath = file.absolutePath.filterNot { it in ZERO_WIDTH_CHARS }
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setMessage(
                "${RepoAdapter.formatSize(file.length())} · ${dateFmt.format(Date(file.lastModified()))}\n\n$displayPath"
            )
            .setPositiveButton(R.string.share) { _, _ -> shareFile(file) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareFile(file: File) {
        val ctx = requireContext()
        val needsStaging = file.absolutePath.any { it in ZERO_WIDTH_CHARS }
        if (!needsStaging) {
            launchShareUri(ctx, file)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                try {
                    val dir = File(ctx.cacheDir, "share_tmp").apply { mkdirs() }
                    val dst = File(dir, file.name)
                    file.copyTo(dst, overwrite = true)
                    dst
                } catch (_: Exception) {
                    null
                }
            }
            if (!isAdded) return@launch
            if (staged == null) {
                Toast.makeText(ctx, R.string.files_share_failed, Toast.LENGTH_LONG).show()
            } else {
                launchShareUri(ctx, staged)
            }
        }
    }

    private fun launchShareUri(ctx: android.content.Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(ctx.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(share, getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.files_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Selección múltiple ----------

    private fun startPick(mode: PickMode) {
        pickMode = mode
        refresh()
    }

    private fun doPaste() {
        val mode = pickMode ?: return
        val destDir = currentDir
        val ioDestDir = effectiveIoDir(destDir)
        val destSafNode = ObbSaf.findDirNode(requireContext(), destDir)
        val targets = selection.toList()
        if (targets.isEmpty()) {
            pickMode = null
            refresh()
            return
        }
        pickMode = null
        viewLifecycleOwner.lifecycleScope.launch {
            var done = 0
            var errors = 0
            val ctx = requireContext()
            val adb = GiraAdbManager.getInstance(ctx)
            withContext(Dispatchers.IO) {
                for (item in targets) {
                    try {
                        val srcPath = item.effectivePath
                        if (item.viaAdb && srcPath != null) {
                            val uniqueName = adb.uniqueDestShell(destDir.absolutePath, item.name)
                            val ok = if (mode == PickMode.COPY) {
                                adb.copyShell(srcPath, destDir.absolutePath, uniqueName)
                            } else {
                                adb.moveShell(srcPath, destDir.absolutePath, uniqueName)
                            }
                            if (ok) done++ else errors++
                            continue
                        }
                        val src = item.file
                        if (src != null) {
                            val normalOk = try {
                                val dest = uniqueDest(ioDestDir, src.name)
                                if (mode == PickMode.COPY) copyItem(src, dest) else moveItem(src, dest)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            if (normalOk) {
                                done++
                            } else if (destSafNode != null) {
                                val r = if (src.isDirectory) {
                                    ObbSaf.copyDirToSafDir(ctx, destDir, src)
                                } else {
                                    ObbSaf.copyFileToSafDir(ctx, destDir, src)
                                }
                                if (r.ok) {
                                    if (mode == PickMode.MOVE) {
                                        if (src.isDirectory) src.deleteRecursively() else src.delete()
                                    }
                                    done++
                                } else {
                                    errors++
                                }
                            } else if (adb.connected) {
                                val uniqueName = adb.uniqueDestShell(destDir.absolutePath, src.name)
                                val adbOk = if (mode == PickMode.COPY) {
                                    adb.copyShell(src.absolutePath, destDir.absolutePath, uniqueName)
                                } else {
                                    adb.moveShell(src.absolutePath, destDir.absolutePath, uniqueName)
                                }
                                if (adbOk) done++ else errors++
                            } else {
                                errors++
                            }
                        } else if (item.viaSaf && item.docUri != null) {
                            val ok = if (item.isDirectory) {
                                copySafDirToDest(ctx, File(currentDir, item.name), item.docUri, item.name, ioDestDir, destSafNode)
                            } else {
                                copySafFileToDest(ctx, item.docUri, item.name, ioDestDir, destSafNode)
                            }
                            if (ok) {
                                if (mode == PickMode.MOVE) {
                                    runCatching { DocumentFile.fromSingleUri(ctx, item.docUri)?.delete() }
                                }
                                done++
                            } else {
                                errors++
                            }
                        } else {
                            errors++
                        }
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            if (!isAdded) return@launch
            StartupLog.mark(
                ctx,
                "explorador: ${if (mode == PickMode.COPY) "copiar" else "mover"} ${targets.size} elementos → ${destDir.absolutePath}: $done ok, $errors errores"
            )
            Toast.makeText(
                ctx,
                getString(R.string.files_paste_done, done, errors),
                Toast.LENGTH_LONG
            ).show()
            selection.clear()
            refresh()
        }
    }

    private fun copySafFileToDest(
        ctx: android.content.Context,
        srcUri: Uri,
        name: String,
        destDir: File,
        destSafNode: DocumentFile?,
    ): Boolean {
        val directOk = try {
            val dest = uniqueDest(destDir, name)
            ctx.contentResolver.openInputStream(srcUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            dest.exists()
        } catch (_: Exception) {
            false
        }
        if (directOk) return true
        val node = destSafNode ?: return false
        return try {
            val existing = node.findFile(name)
            existing?.delete()
            val doc = node.createFile("application/octet-stream", name) ?: return false
            ctx.contentResolver.openInputStream(srcUri)?.use { input ->
                ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    input.copyTo(out)
                } ?: return false
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun copySafDirToDest(
        ctx: Context,
        srcDirFile: File,
        srcDirUri: Uri,
        dirName: String,
        destParentDir: File,
        destSafParentNode: DocumentFile?,
    ): Boolean {
        val srcDoc = ObbSaf.findDirNode(ctx, srcDirFile)
            ?: DocumentFile.fromTreeUri(ctx, srcDirUri)
            ?: return false
        val localTarget = uniqueDest(destParentDir, dirName)
        val localOk = try {
            localTarget.mkdirs() || localTarget.isDirectory
        } catch (_: Exception) {
            false
        }
        val targetSafNode = if (!localOk && destSafParentNode != null) {
            try {
                destSafParentNode.findFile(dirName) ?: destSafParentNode.createDirectory(dirName)
            } catch (_: Exception) {
                null
            }
        } else null
        if (!localOk && targetSafNode == null) return false

        val children = try {
            srcDoc.listFiles()
        } catch (_: Exception) {
            return false
        }
        var allOk = true
        for (child in children) {
            val childName = child.name ?: continue
            if (child.isDirectory) {
                if (!copySafDirToDest(ctx, File(srcDirFile, childName), child.uri, childName, localTarget, targetSafNode)) {
                    allOk = false
                }
            } else {
                if (!copySafFileToDest(ctx, child.uri, childName, localTarget, targetSafNode)) {
                    allOk = false
                }
            }
        }
        return allOk
    }

    // ---------- Desbloqueo de carpetas restringidas (v0.1.53) ----------

    private fun showUnlockFolderDialog() {
        if (!isAdded) return
        val ctx = requireContext()
        val rel = currentRelPath(currentDir)
        val inData = isAndroidDataPath(currentDir)
        val inObb = isAndroidObbPath(currentDir)
        val pkgName = when {
            rel.startsWith("Android/data/") -> rel.removePrefix("Android/data/").substringBefore("/")
            rel.startsWith("Android/obb/") -> rel.removePrefix("Android/obb/").substringBefore("/")
            else -> ""
        }
        val subRel = when {
            rel.startsWith("Android/data/") -> rel.removePrefix("Android/data/")
            rel.startsWith("Android/obb/") -> rel.removePrefix("Android/obb/")
            else -> ""
        }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (inObb && GiraQuestApp.needsMountRestart(ctx)) {
            labels += getString(R.string.settings_obb_activate_btn)
            actions += {
                when {
                    !Environment.isExternalStorageManager() ->
                        PermissionsHelper.openAppAllFilesSettings(requireActivity())
                    !ctx.packageManager.canRequestPackageInstalls() ->
                        PermissionsHelper.openSettings(requireActivity(), PermissionsHelper.Kind.INSTALL_UNKNOWN)
                    else -> {
                        Toast.makeText(ctx, R.string.restarting_to_activate_obb, Toast.LENGTH_SHORT).show()
                        ProcessRebirth.triggerRebirth(ctx)
                    }
                }
            }
        }

        if (inData) {
            labels += getString(R.string.files_unlock_saf_bypass)
            actions += {
                launchSafGrant(ObbSaf.initialDataBypassTreeUri(subRel.ifEmpty { null }))
            }
            if (pkgName.isNotEmpty()) {
                labels += getString(R.string.files_unlock_saf_pkg, pkgName)
                actions += {
                    launchSafGrant(ObbSaf.initialPackageDataTreeUri(pkgName))
                }
            }
        } else if (inObb) {
            labels += getString(R.string.files_unlock_saf_bypass).replace("Android/data", "Android/obb")
            actions += {
                launchSafGrant(ObbSaf.initialObbBypassTreeUri(subRel.ifEmpty { null }))
            }
            ObbSaf.initialTreeUriFor(currentDir)?.let { uri ->
                if (pkgName.isNotEmpty()) {
                    labels += getString(R.string.files_unlock_saf_pkg, pkgName)
                    actions += { launchSafGrant(uri) }
                }
            }
        } else {
            ObbSaf.initialTreeUriFor(currentDir)?.let { uri ->
                labels += getString(R.string.files_unlock_saf_pkg, currentDir.name)
                actions += { launchSafGrant(uri) }
            }
        }

        labels += getString(R.string.files_unlock_adb)
        actions += { startLocalAdbUnlock() }

        labels += getString(R.string.files_unlock_system_files)
        actions += { openSystemFilesApp() }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.files_unlock_btn)
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchSafGrant(initialUri: Uri) {
        val ctx = requireContext()
        try {
            StartupLog.mark(ctx, "explorador: lanzando selector SAF → $initialUri")
            safTreeLauncher.launch(ObbSaf.openDocumentTreeIntent(initialUri))
        } catch (e: Exception) {
            StartupLog.mark(ctx, "explorador: SAF falló ($initialUri): ${e.message}")
            Toast.makeText(ctx, R.string.files_data_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun startLocalAdbUnlock() {
        val ctx = requireContext()
        val adb = GiraAdbManager.getInstance(ctx)
        adb.clearAuthRejection()
        Toast.makeText(ctx, R.string.files_adb_connecting, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            // 1) Reintento si ya estaba emparejado o con Wireless ADB TCP 5555 activo.
            if (adb.ensureConnectedForExplorer(allowMdnsDiscovery = true)) {
                if (!isAdded) return@launch
                Toast.makeText(ctx, R.string.files_adb_connected_toast, Toast.LENGTH_SHORT).show()
                refresh()
                return@launch
            }

            // 2) Comprobar si TCP 5555 local está escuchando (Wireless ADB clásico en Quest).
            val tcpEp = withContext(Dispatchers.IO) {
                val discovered = AdbPortDiscovery.discoverTcp(ctx, 2_000)
                if (discovered != null) discovered
                else {
                    val localOpen = try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress("127.0.0.1", 5555), 800)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (localOpen) AdbPortDiscovery.Endpoint("127.0.0.1", 5555) else null
                }
            }
            if (tcpEp != null) {
                val res = adb.connectTcpFirstTime(tcpEp.host, tcpEp.port)
                if (!isAdded) return@launch
                if (res is GiraAdbManager.TcpFirstConnectOutcome.Ok) {
                    Toast.makeText(ctx, R.string.files_adb_connected_toast, Toast.LENGTH_SHORT).show()
                    refresh()
                    return@launch
                }
            }

            if (!isAdded) return@launch
            // 3) Lanzar servicio de emparejamiento inalámbrico (notificación + ventana flotante)
            //    y abrir Opciones de desarrollador / Depuración inalámbrica.
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PairingService::class.java))
            } catch (_: Exception) {
            }
            if (AndroidSettings.canDrawOverlays(ctx)) {
                PairOverlay.show(ctx)
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.files_unlock_adb)
                .setMessage(R.string.files_adb_pair_guide)
                .setPositiveButton(R.string.perms_open_settings) { _, _ ->
                    val intents = listOf(
                        Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                        Intent(AndroidSettings.ACTION_SETTINGS),
                    )
                    for (it in intents) {
                        try {
                            startActivity(it)
                            break
                        } catch (_: Exception) {
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun openSystemFilesApp() {
        val ctx = requireContext()
        val rel = currentRelPath(currentDir)
        val docId = if (rel.isEmpty()) "primary:" else "primary:$rel"
        val docUri = Uri.Builder()
            .scheme("content")
            .authority("com.android.externalstorage.documents")
            .appendPath("document")
            .appendPath(docId)
            .build()

        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(
                    "com.android.documentsui",
                    "com.android.documentsui.files.FilesActivity"
                )
                setDataAndType(docUri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(
                    "com.google.android.documentsui",
                    "com.android.documentsui.files.FilesActivity"
                )
                setDataAndType(docUri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(
                    "com.android.documentsui",
                    "com.android.documentsui.files.FilesActivity"
                )
            },
            Intent(Intent.ACTION_VIEW).apply {
                component = ComponentName(
                    "com.google.android.documentsui",
                    "com.android.documentsui.files.FilesActivity"
                )
            },
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(ctx, R.string.files_system_files_missing, Toast.LENGTH_LONG).show()
    }

    private fun uniqueDest(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun copyItem(src: File, dest: File) {
        if (src.isDirectory) {
            src.copyRecursively(dest, overwrite = false)
        } else {
            src.copyTo(dest, overwrite = false)
        }
    }

    private fun moveItem(src: File, dest: File) {
        if (src.renameTo(dest)) return
        copyItem(src, dest)
        if (src.isDirectory) src.deleteRecursively() else src.delete()
    }

    private fun askRename() {
        val item = selection.firstOrNull() ?: return
        val input = EditText(requireContext()).apply {
            setText(item.name)
            setSingleLine()
            setSelection(0, item.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.files_rename_title, item.name))
            .setView(input)
            .setPositiveButton(R.string.files_rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || newName.contains("/") || newName == item.name) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val ctx = requireContext()
                    val adb = GiraAdbManager.getInstance(ctx)
                    val ok = withContext(Dispatchers.IO) {
                        when {
                            item.viaAdb && item.adbPath != null -> {
                                val newPath = "${currentDir.absolutePath.trimEnd('/')}/$newName"
                                adb.renameShell(item.adbPath, newPath)
                            }
                            item.viaSaf && item.docUri != null -> {
                                try {
                                    val doc = ObbSaf.findChildNode(ctx, currentDir, item.name)
                                        ?: DocumentFile.fromSingleUri(ctx, item.docUri)
                                    doc?.renameTo(newName) == true
                                } catch (_: Exception) {
                                    false
                                }
                            }
                            else -> {
                                val f = item.file
                                (f != null && f.renameTo(File(f.parentFile, newName))) ||
                                    (adb.connected && f != null && adb.renameShell(
                                        f.absolutePath.filterNot { it in ZERO_WIDTH_CHARS },
                                        "${currentDir.absolutePath.trimEnd('/')}/$newName"
                                    ))
                            }
                        }
                    }
                    if (!isAdded) return@launch
                    StartupLog.mark(
                        ctx,
                        "explorador: renombrar ${item.name} → $newName: ${if (ok) "ok" else "falló"}"
                    )
                    Toast.makeText(
                        ctx,
                        if (ok) getString(R.string.files_renamed, newName)
                        else getString(R.string.files_rename_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    selection.clear()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askDelete() {
        if (selection.isEmpty()) return
        val names = selection.take(5).joinToString("\n") { "• ${it.name}" }
        val extra = if (selection.size > 5) getString(R.string.files_more_items, selection.size - 5) else ""
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.files_delete_title, selection.size))
            .setMessage(names + extra)
            .setPositiveButton(R.string.files_delete) { _, _ ->
                val targets = selection.toList()
                selection.clear()
                viewLifecycleOwner.lifecycleScope.launch {
                    val ctx = requireContext()
                    val adb = GiraAdbManager.getInstance(ctx)
                    var done = 0
                    withContext(Dispatchers.IO) {
                        for (item in targets) {
                            try {
                                val ok = when {
                                    item.viaAdb && item.adbPath != null ->
                                        adb.deleteShell(item.adbPath)
                                    item.viaSaf && item.docUri != null -> {
                                        val doc = ObbSaf.findChildNode(ctx, currentDir, item.name)
                                            ?: DocumentFile.fromSingleUri(ctx, item.docUri)
                                        doc?.delete() == true
                                    }
                                    else -> {
                                        val f = item.file
                                        if (f == null) false
                                        else (if (f.isDirectory) f.deleteRecursively() else f.delete()) ||
                                            (adb.connected && adb.deleteShell(f.absolutePath.filterNot { it in ZERO_WIDTH_CHARS }))
                                    }
                                }
                                if (ok) done++
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (!isAdded) return@launch
                    StartupLog.mark(
                        ctx,
                        "explorador: borrados $done de ${targets.size} (${targets.joinToString(", ") { it.name }.take(300)})"
                    )
                    Toast.makeText(
                        ctx,
                        getString(R.string.files_deleted, done),
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askNewFolder() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.files_new_folder_hint)
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.files_new_folder))
            .setView(input)
            .setPositiveButton(R.string.files_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name.contains("/")) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val ctx = requireContext()
                    val adb = GiraAdbManager.getInstance(ctx)
                    val ioDir = effectiveIoDir(currentDir)
                    val ok = withContext(Dispatchers.IO) {
                        val target = File(ioDir, name)
                        target.mkdir() ||
                            (adb.connected && adb.mkdirShell(File(currentDir, name).absolutePath)) ||
                            (ObbSaf.findDirNode(ctx, currentDir)?.createDirectory(name) != null)
                    }
                    if (!isAdded) return@launch
                    Toast.makeText(
                        ctx,
                        if (ok) getString(R.string.files_folder_created, name)
                        else getString(R.string.files_folder_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Adaptador ----------

    // Adaptador no-inner (una clase inner no puede anidar la clase VH):
    // la selección y el formato de fecha llegan por constructor.
    private class FileAdapter(
        private var items: List<ExplorerItem>,
        private val selection: Set<ExplorerItem>,
        private val dateFmt: SimpleDateFormat,
        private val onTap: (ExplorerItem) -> Unit,
        private val onLongPress: (ExplorerItem) -> Unit,
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val badge: TextView = view.findViewById(R.id.item_file_badge)
            val name: TextView = view.findViewById(R.id.item_file_name)
            val meta: TextView = view.findViewById(R.id.item_file_meta)
            val check: TextView = view.findViewById(R.id.item_file_check)
        }

        fun update(newItems: List<ExplorerItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val (tag, bg, fg) = typeTag(item)
            holder.badge.text = tag
            holder.badge.setBackgroundColor(bg)
            holder.badge.setTextColor(fg)
            holder.name.text = item.name
            holder.meta.text = if (item.isDirectory) {
                when {
                    item.viaAdb || item.viaAlias -> holder.itemView.context.getString(
                        R.string.files_adb_dir_meta,
                        dateFmt.format(Date(item.lastModified))
                    )
                    item.viaSaf -> holder.itemView.context.getString(
                        R.string.files_saf_dir_meta,
                        dateFmt.format(Date(item.lastModified))
                    )
                    else -> {
                        val count = try {
                            item.file?.listFiles()?.size ?: 0
                        } catch (_: Exception) {
                            0
                        }
                        holder.itemView.context.getString(
                            R.string.files_dir_meta,
                            count,
                            dateFmt.format(Date(item.lastModified))
                        )
                    }
                }
            } else {
                "${RepoAdapter.formatSize(item.size)} · ${dateFmt.format(Date(item.lastModified))}"
            }
            val selected = item in selection
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE
            holder.itemView.setBackgroundResource(
                if (selected) R.drawable.bg_perm_card else android.R.color.transparent
            )
            holder.itemView.setOnClickListener { onTap(item) }
            holder.itemView.setOnLongClickListener {
                onLongPress(item)
                true
            }
        }

        /** Etiqueta de tipo + colores (sin iconos nuevos). */
        private fun typeTag(item: ExplorerItem): Triple<String, Int, Int> {
            val white = Color.WHITE
            val ink = Color.parseColor("#1B1437")
            val ext = item.extension
            return when {
                item.isDirectory -> Triple("DIR", Color.parseColor("#6C4CF1"), white)
                ext.equals("apk", true) -> Triple("APK", Color.parseColor("#2E9E5B"), white)
                ext.equals("obb", true) -> Triple("OBB", Color.parseColor("#E69A00"), ink)
                ext.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") ->
                    Triple("IMG", Color.parseColor("#22D3EE"), ink)
                ext.equals("7z", true) -> Triple("7Z", Color.parseColor("#7C3AED"), white)
                ext.equals("log", true) || ext.equals("txt", true) ->
                    Triple("TXT", Color.parseColor("#607D8B"), white)
                else -> Triple("···", Color.parseColor("#9E9E9E"), white)
            }
        }
    }
}
