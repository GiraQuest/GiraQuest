package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un elemento del explorador: archivo/carpeta por File API, o por
 *  SAF (DocumentFile) cuando File.listFiles() devuelve null en
 *  directorios del sistema (Android/obb, Android/data). `file` es null
 *  en los elementos SAF: no hay ruta File utilizable. */
data class ExplorerItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val file: File? = null,
    val docUri: Uri? = null,
) {
    val viaSaf: Boolean get() = docUri != null
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
                    refreshList()
                }
            }
        )
        view.findViewById<Button>(R.id.btn_sort_name).setOnClickListener { toggleSort(SortMode.NAME) }
        view.findViewById<Button>(R.id.btn_sort_size).setOnClickListener { toggleSort(SortMode.SIZE) }
        view.findViewById<Button>(R.id.btn_sort_date).setOnClickListener { toggleSort(SortMode.DATE) }
        // v0.1.29: mostrar/ocultar archivos que empiezan por '.'.
        view.findViewById<Button>(R.id.btn_files_hidden).setOnClickListener {
            showHidden = !showHidden
            refreshList()
            updateSortButtons()
        }
        // v0.1.37: activar acceso directo a /Android/obb mediante los permisos
        // del sistema y reinicio limpio para obtener MOUNT_EXTERNAL_INSTALLER.
        val btnGrantFolder = view.findViewById<Button>(R.id.btn_files_grant_folder)
        btnGrantFolder.setText(R.string.settings_obb_activate_btn)
        btnGrantFolder.setOnClickListener {
            val ctx = requireContext()
            when {
                !Environment.isExternalStorageManager() -> {
                    PermissionsHelper.openAppAllFilesSettings(requireActivity())
                }
                !ctx.packageManager.canRequestPackageInstalls() -> {
                    PermissionsHelper.openSettings(requireActivity(), PermissionsHelper.Kind.INSTALL_UNKNOWN)
                }
                GiraQuestApp.needsMountRestart(ctx) -> {
                    Toast.makeText(ctx, R.string.restarting_to_activate_obb, Toast.LENGTH_SHORT).show()
                    ProcessRebirth.triggerRebirth(ctx)
                }
                else -> {
                    Toast.makeText(ctx, R.string.files_android_data_restricted, Toast.LENGTH_LONG).show()
                }
            }
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
                        navigateTo(currentDir.parentFile ?: rootDir)
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

    private fun navigateTo(dir: File) {
        val target = if (dir.isDirectory) dir else rootDir
        // No salir por encima de la raíz del almacenamiento.
        currentDir = if (target.absolutePath.startsWith(rootDir.absolutePath)) target else rootDir
        // v0.1.29: en directorios del sistema (Android/obb, Android/data)
        // los ocultos se enseñan por defecto.
        val rel = try { currentDir.relativeTo(rootDir).path } catch (_: Exception) { "" }
        if (rel.startsWith("Android/obb") || rel.startsWith("Android/data")) {
            showHidden = true
        }
        selection.clear()
        pickMode = null
        refresh()
    }

    private fun toggleSort(mode: SortMode) {
        if (sortMode == mode) sortAsc = !sortAsc else {
            sortMode = mode
            sortAsc = true
        }
        refreshList()
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
                    setOnClickListener { navigateTo(seg.second) }
                }
                setTypeface(typeface, if (isLast) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            })
        }
    }

    private fun refreshList() {
        if (!isAdded) return
        val v = view ?: return
        // v0.1.29: el listado se intenta por File, luego por SAF y luego
        // por MediaStore, con el resultado de CADA intento en el log.
        // listFiles() devuelve null cuando el FUSE no deja listar la
        // carpeta (Android/obb/<paquete> en Horizon): antes eso se
        // enseñaba como "vacío" sin más. Ahora se distingue "vacío de
        // verdad" de "no se pudo listar" y se enseña el motivo.
        listFailedReason = null
        var raw: List<ExplorerItem>? = null
        var fileListNull = false
        try {
            val files = currentDir.listFiles()
            if (files != null) {
                raw = files.map {
                    ExplorerItem(
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = try { it.length() } catch (_: Exception) { 0L },
                        lastModified = try { it.lastModified() } catch (_: Exception) { 0L },
                        file = it,
                    )
                }
                StartupLog.mark(
                    requireContext(),
                    "explorador: listar ${currentDir.absolutePath} vía File: ${raw.size} elementos"
                )
            } else {
                fileListNull = true
                StartupLog.mark(
                    requireContext(),
                    "explorador: listar ${currentDir.absolutePath} vía File: null (sin permiso para listar o no legible)"
                )
            }
        } catch (e: Exception) {
            fileListNull = true
            StartupLog.mark(
                requireContext(),
                "explorador: listar ${currentDir.absolutePath} vía File: excepción ${e.javaClass.simpleName}: ${e.message}"
            )
        }
        if (raw == null) {
            val saf = try {
                ObbSaf.listDir(requireContext(), currentDir)
            } catch (_: Exception) {
                null
            }
            StartupLog.mark(
                requireContext(),
                "explorador: listar ${currentDir.absolutePath} vía SAF: " +
                    if (saf == null) "sin árbol concedido o fuera del árbol"
                    else "${saf.size} elementos"
            )
            if (saf != null) {
                raw = saf.map {
                    ExplorerItem(
                        name = it.name ?: "?",
                        isDirectory = it.isDirectory,
                        size = it.length(),
                        lastModified = it.lastModified(),
                        docUri = it.uri,
                    )
                }
            }
        }
        if (raw == null) {
            val media = listViaMediaStore(currentDir)
            StartupLog.mark(
                requireContext(),
                "explorador: listar ${currentDir.absolutePath} vía MediaStore: " +
                    if (media == null) "falló la consulta" else "${media.size} elementos"
            )
            raw = media ?: emptyList()
        }
        if (raw.isEmpty() && fileListNull) {
            listFailedReason = "sin permiso para listar (File.listFiles() devolvió null y las vías alternativas no devolvieron nada). Concede la carpeta para ver su contenido."
        }
        val filtered = raw
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
        // Carpetas primero, luego archivos; dentro de cada grupo, el orden
        // elegido (asc/desc).
        val sorted = filtered.sortedWith(
            compareByDescending<ExplorerItem> { it.isDirectory }
                .then(if (sortAsc) cmp else cmp.reversed())
        )
        items = sorted
        adapter.update(items)
        val tvEmpty = v.findViewById<TextView>(R.id.tv_files_empty)
        if (items.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = listFailedReason?.let {
                getString(R.string.files_list_failed, it)
            } ?: getString(R.string.files_empty_real)
        } else {
            tvEmpty.visibility = View.GONE
        }
        v.findViewById<Button>(R.id.btn_files_grant_folder).visibility =
            if (listFailedReason != null) View.VISIBLE else View.GONE
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
        if (selection.isNotEmpty()) {
            if (!selection.remove(item)) selection.add(item)
            refresh()
            return
        }
        if (pickMode != null) return // en modo pegar, los toques no abren nada
        if (item.viaSaf) {
            onSafTap(item)
            return
        }
        val file = item.file ?: return
        when {
            file.isDirectory -> navigateTo(file)
            file.extension.equals("apk", true) -> askInstallApk(file)
            file.extension.equals("obb", true) -> askCopyObb(file)
            file.extension.equals("log", true) || file.extension.equals("txt", true) -> showTextFile(file)
            file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") -> showImagePreview(file)
            file.extension.equals("7z", true) -> showArchiveInfo(file)
            else -> showFileInfo(file)
        }
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
            navigateTo(File(currentDir, item.name))
            return
        }
        if (item.extension.equals("apk", true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                val cached = withContext(Dispatchers.IO) {
                    try {
                        val tmp = File(requireContext().cacheDir, item.name)
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
                        "explorador: APK por SAF ${item.name} copiado a caché (${cached.length()} bytes)"
                    )
                    askInstallApk(cached)
                }
            }
            return
        }
        showSafFileInfo(item)
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
            String(shown, Charsets.UTF_8) + if (cut) "\n\n…(truncado, el archivo es mayor)" else ""
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
                        if (count > 60) "\n…y ${count - 60} más" else ""
                }
            }
        } catch (e: Exception) {
            header + getString(R.string.files_7z_unreadable, e.javaClass.simpleName)
        }
    }

    private fun showFileInfo(file: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setMessage(
                "${RepoAdapter.formatSize(file.length())} · ${dateFmt.format(Date(file.lastModified()))}\n\n${file.absolutePath}"
            )
            .setPositiveButton(R.string.share) { _, _ -> shareFile(file) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareFile(file: File) {
        val ctx = requireContext()
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
        // v0.1.29: copiar/mover solo vale para elementos con File; los
        // vistos por SAF se quedan fuera (se avisa).
        val targets = selection.mapNotNull { it.file }
        val skippedSaf = selection.count { it.viaSaf }
        if (targets.isEmpty()) {
            pickMode = null
            if (skippedSaf > 0) {
                Toast.makeText(
                    requireContext(),
                    R.string.files_saf_only,
                    Toast.LENGTH_LONG
                ).show()
            }
            refresh()
            return
        }
        val destDir = currentDir
        pickMode = null
        viewLifecycleOwner.lifecycleScope.launch {
            var done = 0
            var errors = 0
            withContext(Dispatchers.IO) {
                for (src in targets) {
                    try {
                        val dest = uniqueDest(destDir, src.name)
                        if (mode == PickMode.COPY) {
                            copyItem(src, dest)
                        } else {
                            moveItem(src, dest)
                        }
                        done++
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            if (!isAdded) return@launch
            StartupLog.mark(
                requireContext(),
                "explorador: ${if (mode == PickMode.COPY) "copiar" else "mover"} ${targets.size} elementos → ${destDir.absolutePath}: $done ok, $errors errores"
            )
            Toast.makeText(
                requireContext(),
                getString(R.string.files_paste_done, done, errors),
                Toast.LENGTH_LONG
            ).show()
            selection.clear()
            refresh()
        }
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
                    val ok = withContext(Dispatchers.IO) {
                        // v0.1.29: los elementos SAF se renombran por
                        // DocumentFile (no hay File utilizable).
                        if (item.viaSaf && item.docUri != null) {
                            try {
                                DocumentFile.fromSingleUri(requireContext(), item.docUri)
                                    ?.renameTo(newName) != null
                            } catch (_: Exception) {
                                false
                            }
                        } else {
                            item.file?.renameTo(File(item.file.parentFile, newName)) == true
                        }
                    }
                    if (!isAdded) return@launch
                    StartupLog.mark(
                        requireContext(),
                        "explorador: renombrar ${item.name} → $newName: ${if (ok) "ok" else "falló"}"
                    )
                    Toast.makeText(
                        requireContext(),
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
        val extra = if (selection.size > 5) "\n…y ${selection.size - 5} más" else ""
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.files_delete_title, selection.size))
            .setMessage(names + extra)
            .setPositiveButton(R.string.files_delete) { _, _ ->
                val targets = selection.toList()
                selection.clear()
                viewLifecycleOwner.lifecycleScope.launch {
                    var done = 0
                    withContext(Dispatchers.IO) {
                        for (item in targets) {
                            try {
                                // v0.1.29: los elementos SAF se borran por
                                // DocumentFile (no hay File utilizable).
                                val ok = if (item.viaSaf && item.docUri != null) {
                                    DocumentFile.fromSingleUri(requireContext(), item.docUri)
                                        ?.delete() == true
                                } else {
                                    val f = item.file
                                    if (f == null) false
                                    else if (f.isDirectory) f.deleteRecursively() else f.delete()
                                }
                                if (ok) done++
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (!isAdded) return@launch
                    StartupLog.mark(
                        requireContext(),
                        "explorador: borrados $done de ${targets.size} (${targets.joinToString(", ") { it.name }.take(300)})"
                    )
                    Toast.makeText(
                        requireContext(),
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
                    val ok = withContext(Dispatchers.IO) {
                        File(currentDir, name).mkdir()
                    }
                    if (!isAdded) return@launch
                    Toast.makeText(
                        requireContext(),
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
                val count = try {
                    // v0.1.29: los directorios SAF se cuentan por
                    // DocumentFile (File.listFiles() devuelve null).
                    if (item.viaSaf && item.docUri != null) {
                        DocumentFile.fromSingleUri(holder.itemView.context, item.docUri)
                            ?.listFiles()?.size ?: 0
                    } else {
                        item.file?.listFiles()?.size ?: 0
                    }
                } catch (_: Exception) {
                    0
                }
                holder.itemView.context.getString(
                    R.string.files_dir_meta,
                    count,
                    dateFmt.format(Date(item.lastModified))
                )
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
