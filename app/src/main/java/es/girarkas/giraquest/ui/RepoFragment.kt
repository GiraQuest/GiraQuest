package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.InstallOrigin
import es.girarkas.giraquest.data.InstallRecord
import es.girarkas.giraquest.data.InstallRecordState
import es.girarkas.giraquest.data.InstallState
import es.girarkas.giraquest.data.ObbStatus
import es.girarkas.giraquest.data.ObbUiState
import es.girarkas.giraquest.data.RepoEntry
import es.girarkas.giraquest.data.RepoManager
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.data.VrpPublicRepo
import es.girarkas.giraquest.download.ThumbnailCache
import es.girarkas.giraquest.install.InstallProgress
import es.girarkas.giraquest.install.Installer
import es.girarkas.giraquest.install.ObbEvents
import es.girarkas.giraquest.trailer.TrailerSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pestaña principal: explorar repos (lista/galería, búsqueda, filtros,
 * favoritos) e instalar con doble toque, como en el PC.
 */
class RepoFragment : Fragment() {

    companion object {
        /** v0.1.35: el cartel compacto enseña como mucho 2 líneas
         *  (una por juego); el resto se resume con "(+N más)". */
        private const val MAX_BANNER_ROWS = 2
    }

    private lateinit var repoManager: RepoManager
    private lateinit var settings: SettingsStore
    private lateinit var installer: Installer
    private lateinit var thumbnailCache: ThumbnailCache
    private lateinit var trailerSearch: TrailerSearch
    private lateinit var adapter: RepoAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    // v0.1.26: cabecera GiraQuest (logo), tarjeta de permisos y progreso
    // real de carátulas.
    private lateinit var cardPermissions: View
    private lateinit var btnPermInstall: Button
    private lateinit var btnPermFiles: Button
    private lateinit var coversRow: View
    private lateinit var progressCovers: ProgressBar
    private lateinit var tvCoversProgress: TextView

    private var entries: List<RepoEntry> = emptyList()
    private var filter = RepoManager.Filter.ALL
    private var query = ""
    // v0.1.27: galería por defecto (portadas protagonistas) y orden
    // recordado entre arranques (se lee de SettingsStore en onViewCreated).
    private var galleryMode = true
    private var sortOrder: RepoManager.SortOrder = RepoManager.SortOrder.INDEX
    private var sortSizeAsc: Boolean = true
    private var newPackages: Set<String> = emptySet()
    /** v0.1.29: ranking de popularidad por paquete (puesto #N, como el
     *  PC). Se calcula en loadRepos y se pasa al adapter y a la ficha. */
    private var popularityRank: Map<String, Int> = emptyMap()
    private var sizeByPackageCache: Map<String, Long>? = null
    // v0.1.27: banner de instalación persistente. v0.1.35: compacto
    // (una línea por juego, máx. 2; las filas se construyen por
    // código, cada una con su ✕ para descartarla).
    private lateinit var cardInstallStatus: View
    private lateinit var layoutBannerRows: LinearLayout
    // v0.1.27: tamaño perezoso — solo filas visibles, 3 a la vez.
    private val sizeSemaphore = Semaphore(3)
    private val sizeFetching = mutableSetOf<String>()
    private var filterJob: Job? = null
    private var pendingScrollToTop: Boolean = false
    private var latestDownloadedPkgs: Set<String> = emptySet()
    private var lastShowDeleteDownload: Boolean = false
    private var lastRepoSignature: String = ""

    private fun activeRepoSignature(): String =
        settings.repoUrls.joinToString("|") + "||" +
            settings.customVrpRepos.joinToString("|") { "${it.normalizedBaseUri}#${it.password.hashCode()}#${it.apiKey.hashCode()}" }

    /**
     * Pase de Cloudflare (v0.1.10): si la carga del repo público falla con
     * 403, se abre el WebView UNA vez por carga para pasar el challenge y
     * reintentar con la cookie. `cloudflareFailed` = el WebView tampoco
     * pasó: la biblioteca enseña entonces el mensaje de bloqueo.
     */
    private var cloudflareAttempted = false
    private var cloudflareFailed = false

    private val cloudflareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            cloudflareFailed = false
            StartupLog.mark(requireContext(), "cloudflare: pase obtenido, reintentando repo público")
            Toast.makeText(requireContext(), getString(R.string.cf_retrying), Toast.LENGTH_SHORT).show()
            loadRepos(forcePublicRefresh = true)
        } else {
            val reason = result.data?.getStringExtra(CloudflareActivity.EXTRA_REASON) ?: "?"
            cloudflareFailed = true
            StartupLog.mark(requireContext(), "cloudflare: pase fallido ($reason)")
            applyFilter()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_repo, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repoManager = RepoManager(requireContext())
        settings = SettingsStore(requireContext())
        installer = Installer(requireContext())
        thumbnailCache = ThumbnailCache(requireContext())
        trailerSearch = TrailerSearch(settings)
        recycler = view.findViewById(R.id.recycler_repo)
        tvEmpty = view.findViewById(R.id.tv_empty)
        progressBar = view.findViewById(R.id.progress_install)
        tvProgress = view.findViewById(R.id.tv_progress)
        cardPermissions = view.findViewById(R.id.card_permissions)
        btnPermInstall = view.findViewById(R.id.btn_perm_install)
        btnPermFiles = view.findViewById(R.id.btn_perm_files)
        coversRow = view.findViewById(R.id.covers_progress_row)
        progressCovers = view.findViewById(R.id.progress_covers)
        tvCoversProgress = view.findViewById(R.id.tv_covers_progress)
        cardInstallStatus = view.findViewById(R.id.card_install_status)
        layoutBannerRows = view.findViewById(R.id.layout_install_banner_rows)

        // v0.1.27: orden recordado entre arranques.
        sortOrder = runCatching { RepoManager.SortOrder.valueOf(settings.sortOrder) }
            .getOrDefault(RepoManager.SortOrder.INDEX)
        sortSizeAsc = settings.sortSizeAsc
        refreshFilterChips()
        refreshSortChips()

        // v0.1.26: cada botón de la tarjeta de permisos abre su pantalla
        // real de Ajustes (nada de botones muertos).
        btnPermInstall.setOnClickListener {
            PermissionsHelper.openSettings(requireActivity(), PermissionsHelper.Kind.INSTALL_UNKNOWN)
        }
        btnPermFiles.setOnClickListener {
            PermissionsHelper.openSettings(requireActivity(), PermissionsHelper.Kind.ALL_FILES)
        }

        // v0.1.28: "Instalar APK" bien visible: abre el explorador de
        // archivos en Descargas (tocar un APK lo instala directo, sin
        // pasar por el selector del sistema).
        view.findViewById<Button>(R.id.btn_open_install_apk).setOnClickListener {
            val activity = activity as? es.girarkas.giraquest.MainActivity
            activity?.openFileExplorer(
                java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "Download"
                ).absolutePath
            )
        }

        val openPayPal = View.OnClickListener {
            val url = getString(R.string.paypal_url)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.paypal_open_failed, url),
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.paypal_open_failed, url),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        view.findViewById<View>(R.id.layout_paypal)?.setOnClickListener(openPayPal)
        view.findViewById<Button>(R.id.btn_paypal)?.setOnClickListener(openPayPal)

        parentFragmentManager.setFragmentResultListener(
            GameDetailDialog.RESULT_UNINSTALLED,
            viewLifecycleOwner
        ) { _, _ ->
            applyFilter()
        }

        // v0.1.27: progreso HONESTO de carátulas (ThumbnailCache emite
        // CoverProgress con el total del catálogo real; aquí se pasa al
        // hilo principal).
        thumbnailCache.progressListener = { p ->
            activity?.runOnUiThread { renderCoversProgress(p) }
        }

        // v0.1.27: cuando el temporizador o un reintento completan un
        // OBB fuera de este flujo, el banner y la lista cambian EN SU
        // SITIO (no con un Toast que se pierde).
        viewLifecycleOwner.lifecycleScope.launch {
            ObbEvents.flow.collectLatest {
                refreshInstallBanner()
                applyFilter()
            }
        }

        adapter = RepoAdapter(
            emptyList(),
            isFavorite = { settings.isFavorite(it) },
            isGallery = { galleryMode },
            thumbnailCache = thumbnailCache,
            onTap = { showDetail(it) },
            onDoubleTap = { askInstall(it) },
            onLongPress = {
                settings.toggleFavorite(it.packageName)
                applyFilter()
                Toast.makeText(
                    requireContext(),
                    if (settings.isFavorite(it.packageName)) getString(R.string.fav_added)
                    else getString(R.string.fav_removed),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onSizeNeeded = { requestSize(it) },
            onDeleteDownload = { askDeleteDownload(it) },
            // v0.1.36: el estado de la fila es "APK ✓/✗ · OBB ✓/✗"
            // (Edu): el adaptador pregunta el estado del OBB por
            // paquete (registro + pendiente + disco, barato).
            obbState = { installer.obbUiState(it) },
            isEntryDownloaded = { repoManager.isDownloaded(it) },
        )
        adapter.newPackages = newPackages
        // v0.1.27: galería por defecto (portadas protagonistas).
        // v0.1.33: la app arranca en VERTICAL: 2 columnas en portrait
        // (con 3 las portadas quedaban apretadas) y 3 en landscape.
        recycler.adapter = adapter
        recycler.layoutManager = GridLayoutManager(requireContext(), gallerySpanCount())

        val search = view.findViewById<EditText>(R.id.et_search)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter(debounceMs = 120L, scrollToTop = true)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        view.findViewById<Button>(R.id.btn_filter_all).setOnClickListener { setFilter(RepoManager.Filter.ALL) }
        view.findViewById<Button>(R.id.btn_filter_installed).setOnClickListener { setFilter(RepoManager.Filter.INSTALLED) }
        view.findViewById<Button>(R.id.btn_filter_updates).setOnClickListener { setFilter(RepoManager.Filter.UPDATE_AVAILABLE) }
        view.findViewById<Button>(R.id.btn_filter_favs).setOnClickListener { setFilter(RepoManager.Filter.FAVORITES) }
        view.findViewById<Button>(R.id.btn_filter_downloaded).setOnClickListener { setFilter(RepoManager.Filter.DOWNLOADED) }
        view.findViewById<Button>(R.id.btn_toggle_view).setOnClickListener { toggleView() }
        view.findViewById<Button>(R.id.btn_add_repo).setOnClickListener { showAddRepo() }
        // v0.1.27: selector "Ordenar por" junto a los filtros.
        view.findViewById<Button>(R.id.btn_sort_index).setOnClickListener { setSort(RepoManager.SortOrder.INDEX) }
        view.findViewById<Button>(R.id.btn_sort_popular).setOnClickListener { setSort(RepoManager.SortOrder.POPULAR) }
        view.findViewById<Button>(R.id.btn_sort_az).setOnClickListener { setSort(RepoManager.SortOrder.AZ) }
        view.findViewById<Button>(R.id.btn_sort_new).setOnClickListener { setSort(RepoManager.SortOrder.NEW) }
        view.findViewById<Button>(R.id.btn_sort_size).setOnClickListener { setSort(RepoManager.SortOrder.SIZE) }
        // v0.1.35: al arrancar, borrar sin ruido los pendientes
        // heredados de versiones anteriores (sin staging en disco ni
        // paquete instalado): no vuelven a tapar la lista.
        installer.pruneStalePendingRecords()
        view.findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            // Actualizar a mano permite otro intento del pase de Cloudflare.
            cloudflareAttempted = false
            cloudflareFailed = false
            loadRepos(forcePublicRefresh = true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            installer.progress.collectLatest { p -> renderProgress(p) }
        }
        loadRepos()
    }

    private fun setFilter(f: RepoManager.Filter) {
        val prev = filter
        filter = f
        sizeByPackageCache = null
        if (prev == RepoManager.Filter.DOWNLOADED || f == RepoManager.Filter.DOWNLOADED) {
            adapter.clearSizes()
        }
        // v0.1.30: la papelera de la descarga solo se enseña en la
        // lista de Descargados.
        adapter.showDeleteDownload = (f == RepoManager.Filter.DOWNLOADED)
        refreshFilterChips()
        applyFilter(scrollToTop = true)
    }

    /** v0.1.41: resalta el chip de filtro activo (negrita + opacidad 100%
     *  + fondo turquesa) para que se vea al instante qué filtro está puesto. */
    private fun refreshFilterChips() {
        val v = view ?: return
        val ctx = context ?: return
        val activeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.gira_turquoise_dark))
        val inactiveColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.gira_purple))
        val ids = mapOf(
            R.id.btn_filter_all to RepoManager.Filter.ALL,
            R.id.btn_filter_installed to RepoManager.Filter.INSTALLED,
            R.id.btn_filter_updates to RepoManager.Filter.UPDATE_AVAILABLE,
            R.id.btn_filter_favs to RepoManager.Filter.FAVORITES,
            R.id.btn_filter_downloaded to RepoManager.Filter.DOWNLOADED,
        )
        for ((id, flt) in ids) {
            val btn = v.findViewById<Button>(id) ?: continue
            val active = flt == filter
            btn.isSelected = active
            btn.alpha = if (active) 1.0f else 0.65f
            btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            btn.backgroundTintList = if (active) activeColor else inactiveColor
        }
    }

    /** v0.1.27 + v0.1.40: el orden se aplica DENTRO del filtro actual
     *  y se recuerda entre arranques. Al pulsar "Tamaño" por primera vez
     *  ordena de menor a mayor (↑); al volver a pulsarlo alterna con
     *  mayor a menor (↓). */
    private fun setSort(s: RepoManager.SortOrder) {
        if (s == RepoManager.SortOrder.SIZE) {
            if (sortOrder == RepoManager.SortOrder.SIZE) {
                sortSizeAsc = !sortSizeAsc
            } else {
                sortOrder = RepoManager.SortOrder.SIZE
                sortSizeAsc = true
            }
            settings.sortSizeAsc = sortSizeAsc
        } else {
            sortOrder = s
        }
        settings.sortOrder = sortOrder.name
        refreshSortChips()
        applyFilter(scrollToTop = true)
    }

    /** v0.1.29 + v0.1.40: solo se enseñan los órdenes que tienen datos.
     *  Tamaño usa el tamaño del índice (MB), tamaños cacheados o lo
     *  descargado en disco, así que funciona en todo el catálogo. */
    private fun refreshSortChipsVisibility(downloadedPkgs: Set<String> = latestDownloadedPkgs) {
        val v = view ?: return
        val hasPopular = entries.any { it.downloads > 0 }
        val hasNew = newPackages.isNotEmpty()
        val hasAnySize = entries.any {
            it.apkSize > 0L || it.packageName in downloadedPkgs
        } || settings.allCachedGameSizes().isNotEmpty()
        v.findViewById<Button>(R.id.btn_sort_popular)?.visibility =
            if (hasPopular) View.VISIBLE else View.GONE
        v.findViewById<Button>(R.id.btn_sort_new)?.visibility =
            if (hasNew) View.VISIBLE else View.GONE
        v.findViewById<Button>(R.id.btn_sort_size)?.visibility =
            if (hasAnySize) View.VISIBLE else View.GONE
        val hiddenActive = (sortOrder == RepoManager.SortOrder.POPULAR && !hasPopular) ||
            (sortOrder == RepoManager.SortOrder.NEW && !hasNew) ||
            (sortOrder == RepoManager.SortOrder.SIZE && !hasAnySize)
        if (hiddenActive) {
            sortOrder = RepoManager.SortOrder.INDEX
            settings.sortOrder = sortOrder.name
            refreshSortChips()
            applyFilter(scrollToTop = true)
        }
    }

    /** El chip del orden activo se marca en negrita (los chips son
     *  Button con estilo Chip; no hay estado seleccionado nativo). */
    private fun refreshSortChips() {
        val v = view ?: return
        val ids = mapOf(
            R.id.btn_sort_index to RepoManager.SortOrder.INDEX,
            R.id.btn_sort_popular to RepoManager.SortOrder.POPULAR,
            R.id.btn_sort_az to RepoManager.SortOrder.AZ,
            R.id.btn_sort_new to RepoManager.SortOrder.NEW,
            R.id.btn_sort_size to RepoManager.SortOrder.SIZE,
        )
        for ((id, order) in ids) {
            val btn = v.findViewById<Button>(id) ?: continue
            val active = order == sortOrder
            btn.isSelected = active
            btn.alpha = if (active) 1.0f else 0.6f
            // v0.1.28: en la v0.1.27 el comentario decía "negrita" pero el
            // código no la ponía: el chip activo no se distinguía. Ahora
            // sí: negrita + opacidad completa.
            btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (id == R.id.btn_sort_size) {
                btn.text = if (active) {
                    getString(if (sortSizeAsc) R.string.sort_size_asc else R.string.sort_size_desc)
                } else {
                    getString(R.string.sort_size)
                }
            }
        }
    }

    /** Columnas de la galería según la orientación (v0.1.33). */
    private fun gallerySpanCount(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    private fun toggleView() {
        galleryMode = !galleryMode
        recycler.layoutManager = if (galleryMode) {
            GridLayoutManager(requireContext(), gallerySpanCount())
        } else {
            LinearLayoutManager(requireContext())
        }
        // Cambia el tipo de vista (lista con miniatura / galería con carátula grande).
        adapter.notifyDataSetChanged()
    }

    private fun loadRepos(forcePublicRefresh: Boolean = false) {
        val v = view ?: return
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded || view == null) return@launch
            tvEmpty.text = getString(R.string.loading_repos)
            // v0.1.26: mientras se carga el índice, la barra de carátulas
            // enseña "Cargando juegos…" (indeterminada); al sincronizar
            // carátulas pasa a determinada con "N de M" reales.
            coversRow.visibility = View.VISIBLE
            progressCovers.isIndeterminate = true
            tvCoversProgress.text = getString(R.string.covers_loading_index)
            // Una sola descarga de repos; de ahí salen las entradas y la
            // sincronización de carátulas.
            lastRepoSignature = activeRepoSignature()
            val repos = repoManager.allRepos(forcePublicRefresh)
            if (!isAdded || view == null) return@launch
            entries = repos.flatMap { it.entries }
            // v0.1.27: "Nuevos" = paquetes que no estaban en la carga
            // anterior del índice (el índice no trae fechas). La primera
            // carga guarda el snapshot en silencio, sin marcar nada.
            newPackages = withContext(Dispatchers.IO) {
                settings.diffIndexSnapshot(entries.map { it.packageName }.toSet())
            }
            if (!isAdded || view == null) return@launch
            adapter.newPackages = newPackages
            sizeByPackageCache = null
            // v0.1.29: ranking de popularidad como el PC (MainForm.cs:
            // 2752-2759): por paquete, la MAYOR puntuación si hay varias
            // versiones; solo puntuaciones > 0 clasifican; el puesto es
            // la posición + 1 en orden descendente (estable ante empates).
            popularityRank = entries
                .groupBy { it.packageName }
                .mapValues { (_, es) -> es.maxOf { it.downloads } }
                .filter { it.value > 0 }
                .entries.sortedByDescending { it.value }
                .mapIndexed { i, e -> e.key to (i + 1) }.toMap()
            adapter.popularityRank = popularityRank
            val dlSnap = withContext(Dispatchers.IO) { repoManager.downloadedPackageDirsSnapshot() }
            if (!isAdded || view == null) return@launch
            latestDownloadedPkgs = dlSnap
            refreshSortChipsVisibility(dlSnap)
            val ctx = context ?: return@launch
            StartupLog.mark(
                ctx,
                "orden: ${entries.size} juegos, ${entries.count { it.downloads > 0 }} con descargas>0, " +
                    "máx=${entries.maxOfOrNull { it.downloads } ?: 0.0}, nuevos=${newPackages.size}, " +
                    "con ranking=${popularityRank.size}"
            )
            applyFilter()
            refreshInstallBanner()
            // v0.1.16: limpiar las carpetas vacías por juego que las
            // versiones anteriores creaban en Descargas solo por listar
            // la biblioteca (una carpeta com.* por juego del índice).
            val cleaned = withContext(Dispatchers.IO) {
                repoManager.cleanupEmptyDownloadDirs()
            }
            if (!isAdded || view == null) return@launch
            if (cleaned > 0) {
                context?.let {
                    StartupLog.mark(
                        it,
                        "descargas: limpiadas $cleaned carpetas vacías por juego"
                    )
                }
            }
            thumbnailCache.syncAll(repos)
            if (!isAdded || view == null) return@launch
            applyFilter() // refresca las carátulas ya descargadas
            // v0.1.10: 403 de Cloudflare en el repo público → abrir el
            // WebView para pasar el challenge con el motor real de Chrome
            // y reintentar con la cookie cf_clearance. Una sola vez por
            // carga (si el WebView tampoco pasa, no se entra en bucle).
            if (entries.isEmpty() && settings.customVrpRepos.isNotEmpty() &&
                VrpPublicRepo.isCloudflare403(repoManager.lastPublicError) &&
                !cloudflareAttempted
            ) {
                cloudflareAttempted = true
                cloudflareFailed = false
                try {
                    val c = context ?: return@launch
                    val firstCustom = settings.customVrpRepos.first()
                    val vrp = VrpPublicRepo(c, firstCustom)
                    val baseUri = vrp.loadConfig().baseUri
                    vrp.clearClearance() // la cookie vieja puede estar caducada
                    StartupLog.mark(
                        c,
                        "cloudflare: 403 en el repo, abriendo WebView a $baseUri"
                    )
                    val intent = Intent(c, CloudflareActivity::class.java)
                        .putExtra(CloudflareActivity.EXTRA_URL, baseUri)
                    cloudflareLauncher.launch(intent)
                } catch (e: Exception) {
                    context?.let {
                        StartupLog.mark(
                            it,
                            "cloudflare: no se pudo abrir el WebView: ${e.message}"
                        )
                    }
                }
            }
            // v0.1.27 + v0.1.41: al terminar, la línea final queda visible ~4 s
            // y se oculta en la misma corrutina (sin volver a pedir viewLifecycleOwner).
            delay(4_000)
            if (isAdded && view != null) coversRow.visibility = View.GONE
        }
    }

    /** Tarjeta de permisos (v0.1.26): visible solo si falta algo; cada
     *  botón solo se enseña si SU permiso falta. */
    private fun refreshPermissionsCard() {
        if (!::cardPermissions.isInitialized || !isAdded || view == null) return
        val missing = PermissionsHelper.missing(requireContext())
        cardPermissions.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        btnPermInstall.visibility =
            if (missing.contains(PermissionsHelper.Kind.INSTALL_UNKNOWN)) View.VISIBLE else View.GONE
        btnPermFiles.visibility =
            if (missing.contains(PermissionsHelper.Kind.ALL_FILES)) View.VISIBLE else View.GONE
    }

    /** Progreso HONESTO de carátulas (v0.1.27): el total es el catálogo
     *  real (3.018), no las nuevas de esta pasada. Mientras sincroniza:
     *  "Cargando carátulas… N de M nuevas (X de 3.018 en caché)"; al
     *  terminar: "Carátulas listas: X de 3.018 juegos", donde X son las
     *  carátulas realmente en caché. Sin catálogo, se oculta. */
    private fun renderCoversProgress(p: ThumbnailCache.CoverProgress) {
        if (!::coversRow.isInitialized || !isAdded || view == null) return
        if (p.catalogTotal <= 0) {
            coversRow.visibility = View.GONE
            return
        }
        coversRow.visibility = View.VISIBLE
        progressCovers.isIndeterminate = false
        progressCovers.max = p.catalogTotal
        progressCovers.progress = p.cached.coerceIn(0, p.catalogTotal)
        tvCoversProgress.text = if (p.finished) {
            getString(R.string.covers_done, p.cached, p.catalogTotal)
        } else {
            getString(R.string.covers_loading, p.doneNew, p.totalNew, p.cached, p.catalogTotal)
        }
    }

    /** Cartel compacto de instalación (v0.1.35, Edu, captura
     *  58628): UNA línea por juego (máx. 2), sin el log de errores
     *  pegado dentro — el error completo vive solo en el log de
     *  arranque y en la ficha del juego (ObbUiState.Failed.reason).
     *  Cada fila se toca para abrir la ficha y lleva su ✕ para
     *  descartarla (persistido: no vuelve al reiniciar). El cartel
     *  nunca tapa la lista ni bloquea su scroll: la lista es un
     *  RecyclerView aparte y el cartel solo ocupa sus 2 líneas. */
    private fun refreshInstallBanner() {
        if (!::layoutBannerRows.isInitialized || !isAdded || view == null) return
        val visible = settings.installRecords
            .filter { !it.dismissed }
            .filter {
                it.state == InstallRecordState.COMPLETE ||
                    it.state == InstallRecordState.PENDING_OBB ||
                    it.state == InstallRecordState.FAILED
            }
            .sortedByDescending { it.updatedAtMs }
        val rows = visible.take(MAX_BANNER_ROWS)
        layoutBannerRows.removeAllViews()
        if (rows.isEmpty()) {
            cardInstallStatus.visibility = View.GONE
            return
        }
        cardInstallStatus.visibility = View.VISIBLE
        val extra = visible.size - rows.size
        rows.forEachIndexed { index, record ->
            val isLast = index == rows.lastIndex
            val line = bannerLine(record, if (isLast) extra else 0)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tv = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 14f
                text = line
            }
            val dismiss = TextView(requireContext()).apply {
                text = "\u2715"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 4, 8, 4)
                setOnClickListener {
                    installer.dismissInstallRecord(record.packageName)
                    refreshInstallBanner()
                }
            }
            row.addView(tv)
            row.addView(dismiss)
            row.setOnClickListener { openDetailForPackage(record.packageName) }
            layoutBannerRows.addView(row)
        }
    }

    /** Línea compacta del cartel (v0.1.35): una línea por juego, sin
     *  el log de errores. `extra` = registros visibles más que no
     *  caben en las 2 líneas (solo se añade a la última). */
    private fun bannerLine(record: InstallRecord, extra: Int): String {
        val base = when (record.state) {
            InstallRecordState.COMPLETE ->
                getString(R.string.banner_compact_complete, record.gameName)
            InstallRecordState.PENDING_OBB ->
                getString(R.string.banner_compact_pending, record.gameName)
            InstallRecordState.FAILED ->
                getString(R.string.banner_compact_failed, record.gameName)
            InstallRecordState.IN_PROGRESS -> record.gameName
        }
        return if (extra > 0) base + getString(R.string.banner_compact_more, extra) else base
    }

    /** Tocar una fila del cartel abre la ficha del juego (donde vive
     *  el detalle completo y el motivo del error). */
    private fun openDetailForPackage(packageName: String) {
        val entry = entries.firstOrNull { it.packageName == packageName } ?: return
        showDetail(entry)
    }

    /** Tamaño de un juego bajo demanda (v0.1.27): descargados = tamaño
     *  exacto en disco, sin red; repo público = listado + HEADs con
     *  caché (2–3 a la vez, solo filas visibles). Nunca bloquea el
     *  scroll ni la carga de la lista. */
    private fun requestSize(entry: RepoEntry) {
        if (entry.packageName in sizeFetching) return
        if (entry.packageName in latestDownloadedPkgs && repoManager.isDownloaded(entry)) {
            // v0.1.28: esta rama corre SÍNCRONA dentro de onBindViewHolder
            // (cada fila descargada la dispara): si downloadedSizeBytes
            // lanzara algo, tumbaría el RecyclerView. A prueba de fallos.
            try {
                val bytes = installer.downloadedSizeBytes(entry)
                if (bytes > 0) {
                    adapter.setSize(entry.packageName, RepoAdapter.formatSize(bytes))
                    return
                }
            } catch (_: Exception) {
            }
        }
        if (entry.vrpHash == null) return
        settings.cachedGameSize(entry.packageName, entry.versionCode)?.let { (bytes, _) ->
            if (bytes > 0) {
                adapter.setSize(entry.packageName, RepoAdapter.formatSize(bytes))
                return
            }
        }
        sizeFetching.add(entry.packageName)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                sizeSemaphore.withPermit {
                    val size = withContext(Dispatchers.IO) {
                        installer.gameDownloadSize(entry)
                    }
                    if (size != null && size.totalBytes > 0 && isAdded) {
                        sizeByPackageCache = null
                        adapter.setSize(entry.packageName, RepoAdapter.formatSize(size.totalBytes))
                    }
                }
            } catch (_: Exception) {
            } finally {
                sizeFetching.remove(entry.packageName)
            }
        }
    }

    /** Tamaños para el orden por tamaño (v0.1.40): combina tamaño real en
     *  disco (en la pestaña Descargados), tamaños cacheados por HEAD y
     *  el tamaño del índice (entry.apkSize). Con caché para no recorrer
     *  disco ni JSON en cada tecla de búsqueda. */
    private fun sizeByPackageForSort(downloadedPkgs: Set<String>): Map<String, Long> {
        if (sortOrder != RepoManager.SortOrder.SIZE) return emptyMap()
        sizeByPackageCache?.let { return it }
        val cached = settings.allCachedGameSizes()
        val map = HashMap<String, Long>(cached.size + 16)
        map.putAll(cached)
        if (filter == RepoManager.Filter.DOWNLOADED) {
            for (e in entries) {
                if (e.packageName in downloadedPkgs && repoManager.isDownloaded(e)) {
                    val disk = installer.downloadedSizeBytes(e)
                    if (disk > 0L) map["${e.packageName}|${e.versionCode}"] = disk
                }
            }
        }
        sizeByPackageCache = map
        return map
    }

    private fun scrollToTopImmediate() {
        if (!isAdded || view == null || !::recycler.isInitialized) return
        recycler.stopScroll()
        val lm = recycler.layoutManager
        if (lm is LinearLayoutManager) {
            lm.scrollToPositionWithOffset(0, 0)
        } else {
            recycler.scrollToPosition(0)
        }
    }

    /** Subida ultrarrápida hasta arriba del todo al pulsar la pestaña Principal. */
    fun fastScrollToTop() {
        if (!isAdded || view == null || !::recycler.isInitialized) return
        recycler.stopScroll()
        val lm = recycler.layoutManager as? LinearLayoutManager ?: run {
            recycler.scrollToPosition(0)
            return
        }
        val firstPos = lm.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return
        if (!recycler.canScrollVertically(-1)) return

        fun startFastSmoothScroll() {
            if (!isAdded || view == null) return
            val scroller = object : LinearSmoothScroller(requireContext()) {
                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                    8f / displayMetrics.densityDpi

                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            }
            scroller.targetPosition = 0
            lm.startSmoothScroll(scroller)
        }

        if (firstPos > 30) {
            lm.scrollToPositionWithOffset(24, 0)
            recycler.post { startFastSmoothScroll() }
        } else {
            startFastSmoothScroll()
        }
    }

    private fun applyFilter(debounceMs: Long = 0L, scrollToTop: Boolean = false) {
        if (!isAdded || view == null) return
        if (scrollToTop) pendingScrollToTop = true
        val entriesSnap = entries
        val qSnap = query
        val fSnap = filter
        val sSnap = sortOrder
        val newSnap = newPackages
        val ascSnap = sortSizeAsc
        val oldItems = adapter.currentItems()
        val oldOrigins = adapter.currentOrigins()
        val showTrashNow = adapter.showDeleteDownload
        val showTrashChanged = showTrashNow != lastShowDeleteDownload
        val cfFailedSnap = cloudflareFailed

        filterJob?.cancel()
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val result = withContext(Dispatchers.Default) {
                thumbnailCache.ensureIndex()
                val installed = repoManager.installedVersionsSnapshot()
                val origins = repoManager.installedOriginsSnapshot(installed.keys)
                val downloadedPkgs = repoManager.downloadedPackageDirsSnapshot()
                val sizesMap = sizeByPackageForSort(downloadedPkgs)
                val filtered = repoManager.filter(
                    entriesSnap, qSnap, fSnap, sSnap, newSnap, sizesMap, ascSnap, installed, downloadedPkgs
                )
                val newItems = filtered.map { it to repoManager.stateOf(it, installed, downloadedPkgs) }
                val interestingPkgs = HashSet<String>(installed.size + 32).apply {
                    addAll(installed.keys)
                    settings.installRecords.forEach { add(it.packageName) }
                    settings.pendingObbs.forEach { add(it.packageName) }
                }
                val obbStates = HashMap<String, ObbUiState>(interestingPkgs.size * 2 + 4)
                for (pkg in interestingPkgs) {
                    obbStates[pkg] = installer.obbUiState(pkg)
                }
                val defaultObb = ObbUiState.Unknown
                val diff = if (showTrashChanged) {
                    null
                } else {
                    DiffUtil.calculateDiff(RepoAdapter.DiffCallback(oldItems, newItems, oldOrigins, origins))
                }
                FilterResult(newItems, diff, obbStates, defaultObb, downloadedPkgs, origins)
            }
            if (!isAdded || view == null) return@launch
            val shouldScrollTop = pendingScrollToTop
            pendingScrollToTop = false
            latestDownloadedPkgs = result.downloadedPkgs
            lastShowDeleteDownload = showTrashNow
            if (result.diff != null) {
                adapter.updateWithDiff(
                    result.items, result.diff, result.obbStates, result.defaultObb, result.downloadedPkgs, result.origins
                )
            } else {
                adapter.updateFull(
                    result.items, result.obbStates, result.defaultObb, result.downloadedPkgs, result.origins
                )
            }
            if (shouldScrollTop) {
                scrollToTopImmediate()
            }
            tvEmpty.visibility = if (result.items.isEmpty()) View.VISIBLE else View.GONE
            if (result.items.isEmpty()) {
                tvEmpty.text = when {
                    entriesSnap.isNotEmpty() -> getString(R.string.empty_no_results)
                    settings.repoUrls.isEmpty() &&
                        settings.customVrpRepos.isEmpty() ->
                        getString(R.string.empty_no_repo)
                    cfFailedSnap ->
                        getString(R.string.cf_blocked_message)
                    repoManager.lastPublicError != null ->
                        "${repoManager.lastPublicError}\n\n${getString(R.string.public_error_hint)}"
                    else ->
                        getString(R.string.empty_public_error)
                }
            }
        }
    }

    private class FilterResult(
        val items: List<Pair<RepoEntry, InstallState>>,
        val diff: DiffUtil.DiffResult?,
        val obbStates: Map<String, ObbUiState>,
        val defaultObb: ObbUiState,
        val downloadedPkgs: Set<String>,
        val origins: Map<String, InstallOrigin>,
    )

    override fun onResume() {
        super.onResume()
        // v0.1.26: al volver (p. ej. de Ajustes tras conceder un
        // permiso), la tarjeta se actualiza: desaparece si ya está todo.
        refreshPermissionsCard()
        // v0.1.27: el banner de instalación también se actualiza al
        // volver (el temporizador puede haber completado un OBB fuera).
        refreshInstallBanner()
        val repoChanged = ::settings.isInitialized && activeRepoSignature() != lastRepoSignature
        if (repoChanged) {
            loadRepos(forcePublicRefresh = false)
        }
        // v0.1.19: completar OBBs pendientes automáticamente, sin que el
        // usuario haga nada. Al volver a la biblioteca con el ADB vivo,
        // los OBB que quedaron pendientes tras instalar por fallback (o
        // tras fallar la copia) se copian solos a su carpeta. Silencioso
        // salvo que se complete alguno (con reintento limitado a 5 min).
        viewLifecycleOwner.lifecycleScope.launch {
            val completed = installer.completePendingObbs()
            if (completed.isNotEmpty() && isAdded && view != null) {
                Toast.makeText(
                    requireContext(),
                    "OBB completado: ${completed.joinToString(", ")}.",
                    Toast.LENGTH_LONG
                ).show()
                loadRepos()
            }
        }
    }

    /** Ficha de detalle (carátula grande, descripción, versión, descargas,
     *  instalar, tráiler). Versión base para el tráiler: nombre más corto. */
    private fun showDetail(entry: RepoEntry) {
        val base = repoManager.baseEntry(entries, entry.packageName) ?: entry
        // v0.1.19: el OBB del repo público no se sabe por el índice
        // (ameList.txt no tiene columna de OBB): si el juego está
        // descargado y extraído, se mira lo extraído en disco; si no,
        // UNKNOWN — la ficha ya no afirma "Solo APK, sin OBB" para
        // juegos que sí lo tienen (The Walking Dead, Pistol Whip).
        val dir = repoManager.downloadDirFor(entry)
        val extracted = File(dir, "game_extracted")
        // v0.1.28: "NO" solo cuando la extracción TERMINÓ bien. El
        // extractor crea game_extracted/ con mkdirs() AL EMPEZAR, así
        // que una extracción interrumpida o fallida deja el directorio
        // vacío o incompleto y la v0.1.27 lo leía como "sin OBB" para
        // siempre. Señales de extracción completa: el marcador
        // .extracted_ok (v0.1.27) o app.apk copiado (solo se copia tras
        // extraer OK). Cualquier otro caso es UNKNOWN.
        val extractionDone = File(extracted, ".extracted_ok").exists() ||
            File(dir, "app.apk").let { it.exists() && it.length() > 0 }
        val obbStatus = when {
            entry.vrpHash == null ->
                if (entry.hasObb) ObbStatus.YES else ObbStatus.NO
            extractionDone ->
                if (VrpPublicRepo(requireContext()).hasGameDataFiles(extracted)) ObbStatus.YES
                // v0.1.32: la carpeta del paquete puede haberse MOVIDO a
                // /Android/obb/<paquete> (renameTo): el staging ya no
                // trae archivos de datos pero el juego SÍ los tiene. No
                // decir "sin OBB".
                else if (installer.obbUiState(entry.packageName) is ObbUiState.Copied) ObbStatus.YES
                else ObbStatus.NO
            else -> ObbStatus.UNKNOWN
        }
        // v0.1.19: el índice VRP no trae tamaños; si el APK ya está
        // descargado, la ficha enseña su tamaño real en vez de "?".
        val downloadedApkSize =
            File(dir, "app.apk").takeIf { it.exists() && it.length() > 0 }?.length()
        // v0.1.24: estado de instalación para la ficha (la lista ya lo
        // enseña; la ficha no, y Edu reinstaló sin querer un juego ya
        // instalado porque el botón siempre decía "Instalar").
        val installState = repoManager.stateOf(entry)
        GameDetailDialog.newInstance(
            entry = entry,
            baseEntry = base,
            thumbnailCache = thumbnailCache,
            trailerSearch = trailerSearch,
            settings = settings,
            installer = installer,
            onInstall = { askInstall(it, installState) },
            obbStatus = obbStatus,
            downloadedApkSize = downloadedApkSize,
            installState = installState,
            popularityRank = popularityRank[entry.packageName] ?: 0,
            onDeleteDownload = { askDeleteDownload(it) },
            onUninstalled = { applyFilter() },
        ).show(parentFragmentManager, "game_detail")
    }

    /** Papelera de la descarga: borra toda la carpeta Download/GiraQuest/<paquete>/. */
    private fun askDeleteDownload(entry: RepoEntry) {
        if (!isAdded) return
        if (Installer.isInstalling(entry.packageName)) {
            Toast.makeText(
                requireContext(),
                R.string.delete_download_busy,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                es.girarkas.giraquest.install.GameUninstaller.downloadedSizeBytes(
                    requireContext(), entry.packageName
                )
            }
            if (!isAdded) return@launch
            if (size <= 0L) {
                // Ya no hay nada que borrar (se borró desde otra
                // pantalla): la lista se actualiza sola.
                applyFilter()
                return@launch
            }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_download_title, entry.displayName))
                .setMessage(
                    getString(
                        R.string.delete_download_message,
                        entry.displayName,
                        RepoAdapter.formatSize(size)
                    )
                )
                .setPositiveButton(R.string.delete_download_confirm) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val freed = withContext(Dispatchers.IO) {
                            es.girarkas.giraquest.install.GameUninstaller.deleteDownloads(
                                requireContext(), entry.packageName
                            )
                        }
                        if (!isAdded) return@launch
                        if (freed > 0L) {
                            Toast.makeText(
                                requireContext(),
                                getString(
                                    R.string.delete_download_done,
                                    RepoAdapter.formatSize(freed)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.delete_download_failed, "?"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // El juego sale de Descargados; el espacio libre
                        // de la próxima confirmación ya lo refleja.
                        applyFilter()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Confirmación en pantalla antes de instalar (la que Edu aceptó). */
    private fun askInstall(entry: RepoEntry, installState: InstallState = InstallState.NOT_INSTALLED) {
        // v0.1.26: permisos en contexto, antes de instalar. Si falta
        // alguno, se abre el diálogo que lleva a Ajustes y NO se instala
        // (antes el instalador fallaba a mitad sin explicar por qué).
        if (PermissionsHelper.showMissingDialog(
                requireActivity(),
                PermissionsHelper.missing(requireContext())
            )
        ) return
        // v0.1.24: si ya está instalado con esta versión, la confirmación
        // lo dice explícito ("¿Reinstalar …?") para que reinstalar sea
        // una decisión visible, no un toque accidental (Edu, 10:23:40).
        if (installState == InstallState.INSTALLED) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.confirm_reinstall_title, entry.displayName))
                .setMessage(getString(R.string.confirm_reinstall_message, entry.versionName))
                .setPositiveButton(R.string.reinstall) { _, _ -> installer.install(entry) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        // Del repo público no se sabe el tamaño ni si trae OBB hasta
        // descargar el archivo: el OBB viene dentro del .7z del juego.
        val obbNote = when {
            entry.vrpHash != null -> getString(R.string.confirm_vrp)
            entry.hasObb -> getString(R.string.confirm_obb)
            else -> getString(R.string.confirm_no_obb)
        }
        // v0.1.27: la confirmación muestra SIEMPRE las tres cifras
        // (tamaño, libres, quedarán). El tamaño del repo público se
        // consulta bajo demanda (listado + HEADs, con caché); mientras
        // tanto el diálogo ya está abierto con "Consultando tamaño…".
        val initialSize = if (entry.vrpHash != null) {
            getString(R.string.detail_size_querying)
                .removePrefix("Tamaño de descarga: ")
        } else {
            RepoAdapter.formatSize(entry.apkSize)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_title, entry.displayName))
            .setMessage(
                getString(
                    R.string.confirm_message,
                    entry.versionName,
                    initialSize,
                    obbNote
                )
            )
            .setPositiveButton(R.string.install) { _, _ -> installer.install(entry) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val free = withContext(Dispatchers.IO) { installer.freeSpaceBytes() }
            val sizeBytes: Long? = if (entry.vrpHash != null) {
                withContext(Dispatchers.IO) { installer.gameDownloadSize(entry) }?.totalBytes
            } else if (entry.apkSize > 0) {
                entry.apkSize
            } else {
                null
            }
            if (!isAdded) return@launch
            val sizeText = sizeBytes?.let { RepoAdapter.formatSize(it) } ?: "?"
            val freeText = if (free > 0) RepoAdapter.formatSize(free) else "?"
            // Quedarán: tras descarga+extracción queda el .7z unido y lo
            // extraído (el doble temporalmente, y ambos se quedan en
            // Descargas). Estimación conservadora: libre − 2×tamaño.
            val remaining = if (sizeBytes != null && free > 0) (free - 2 * sizeBytes).coerceAtLeast(0) else -1L
            val remainingText = if (remaining >= 0) RepoAdapter.formatSize(remaining) else "?"
            val noFit = sizeBytes != null && free in 0 until sizeBytes
            val tight = sizeBytes != null && !noFit && remaining in 0 until (500L * 1_048_576L)
            val msg = buildString {
                append(
                    getString(R.string.confirm_message, entry.versionName, sizeText, obbNote)
                )
                append("\n")
                append(getString(R.string.confirm_space, sizeText, freeText, remainingText))
                if (noFit) {
                    append("\n")
                    append(getString(R.string.confirm_nofit, entry.displayName, sizeText, freeText))
                } else if (tight) {
                    append("\n")
                    append(getString(R.string.confirm_tight, RepoAdapter.formatSize(remaining)))
                }
            }
            dialog.setMessage(msg)
            // Sin espacio: NO se descarga (el botón queda desactivado).
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !noFit
        }
    }

    private fun showAddRepo() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.add_repo_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
        }
        val container = FrameLayout(ctx).apply {
            val padH = (20 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.add_repo_title)
            .setMessage(R.string.add_repo_message)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(ctx, R.string.repo_invalid_input, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(ctx, R.string.repo_adding, Toast.LENGTH_SHORT).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val repo = repoManager.addRawRepo(raw)
                        if (!isAdded) return@launch
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.repo_added, repo.name, repo.entries.size),
                            Toast.LENGTH_LONG
                        ).show()
                        loadRepos(forcePublicRefresh = false)
                    } catch (e: Exception) {
                        if (!isAdded) return@launch
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.repo_failed, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** v0.1.36: la lista NUNCA muestra texto largo. El progreso es
     *  UNA línea corta: % mientras trabaja, "OBB pendiente" cuando
     *  queda pendiente. El detalle completo (rutas, excepciones,
     *  cadena de errores por archivo) va SOLO al log y a la ficha del
     *  juego. shortLine() es el cortafuegos: primera línea, máx 90
     *  caracteres (captura 58637: el texto gigante era el mensaje de
     *  ObbPending con la cadena de errores de los 7 archivos). */
    private fun shortLine(text: String): String {
        val first = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (first.length > 90) first.take(87) + "…" else first
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val kb = bytesPerSec / 1024.0
        if (kb < 1024.0) return "%.0f KB/s".format(kb)
        return "%.1f MB/s".format(kb / 1024.0)
    }

    private fun renderProgress(p: InstallProgress) {
        if (!isAdded || view == null) return
        when (p) {
            is InstallProgress.DownloadingApk -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = p.percent
                tvProgress.visibility = View.VISIBLE
                val spd = formatSpeed(p.speedBytesPerSec)
                tvProgress.text = if (spd.isNotEmpty()) {
                    getString(R.string.progress_apk_speed, p.percent, spd)
                } else {
                    getString(R.string.progress_apk, p.percent)
                }
            }
            is InstallProgress.DownloadingGame -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = p.percent
                tvProgress.visibility = View.VISIBLE
                val spd = formatSpeed(p.speedBytesPerSec)
                tvProgress.text = if (spd.isNotEmpty()) {
                    getString(R.string.progress_game_speed, p.percent, spd)
                } else {
                    getString(R.string.progress_game, p.percent)
                }
            }
            is InstallProgress.DownloadingObb -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = p.percent
                tvProgress.visibility = View.VISIBLE
                val spd = formatSpeed(p.speedBytesPerSec)
                tvProgress.text = if (spd.isNotEmpty()) {
                    getString(R.string.progress_obb_speed, p.percent, spd)
                } else {
                    getString(R.string.progress_obb, p.percent)
                }
            }
            is InstallProgress.ExtractingObb -> {
                progressBar.visibility = View.VISIBLE
                if (p.percent >= 0) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = p.percent
                } else {
                    progressBar.isIndeterminate = true
                }
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = shortLine(p.note)
            }
            is InstallProgress.InstallingApk,
            is InstallProgress.CopyingObb -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = when (p) {
                    is InstallProgress.InstallingApk -> shortLine(p.note)
                    is InstallProgress.CopyingObb -> shortLine(p.note)
                    else -> ""
                }
            }
            is InstallProgress.RunningInstallTxt -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = getString(R.string.progress_installtxt, shortLine(p.line))
            }
            is InstallProgress.ObbPending -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                tvProgress.visibility = View.VISIBLE
                tvProgress.text = getString(R.string.progress_obb_pending, p.gameName)
                refreshInstallBanner()
            }
            is InstallProgress.Done -> {
                progressBar.visibility = View.GONE
                val short = shortLine(p.message)
                if (short.isNotBlank()) {
                    tvProgress.visibility = View.VISIBLE
                    tvProgress.text = short
                    Toast.makeText(requireContext(), short, Toast.LENGTH_LONG).show()
                    applyFilter()
                } else {
                    tvProgress.text = ""
                    tvProgress.visibility = View.GONE
                }
                sizeByPackageCache = null
                refreshInstallBanner()
            }
        }
    }
}
