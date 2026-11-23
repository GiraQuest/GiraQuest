package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import coil.load
import es.girarkas.giraquest.GiraQuestApp
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.InstallOrigin
import es.girarkas.giraquest.data.InstallRecordState
import es.girarkas.giraquest.data.InstallState
import es.girarkas.giraquest.data.ObbStatus
import es.girarkas.giraquest.data.ObbUiState
import es.girarkas.giraquest.data.RepoEntry
import es.girarkas.giraquest.data.RepoManager
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.download.ThumbnailCache
import es.girarkas.giraquest.install.GameUninstaller
import es.girarkas.giraquest.install.Installer
import es.girarkas.giraquest.install.ObbEvents
import es.girarkas.giraquest.install.ProcessRebirth
import es.girarkas.giraquest.trailer.TrailerActivity
import es.girarkas.giraquest.trailer.TrailerSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Ficha de detalle del juego.
 * Constructor vacío obligatorio para Android FragmentManager (evita crash
 * InstantiationException al recrear la Activity tras suspensión/rotación).
 */
class GameDetailDialog : DialogFragment() {

    companion object {
        const val RESULT_UNINSTALLED = "giraquest_game_uninstalled"
        private const val ARG_ENTRY_JSON = "entry_json"
        private const val ARG_BASE_ENTRY_JSON = "base_entry_json"
        private const val ARG_OBB_STATUS = "obb_status"
        private const val ARG_DOWNLOADED_SIZE = "downloaded_size"
        private const val ARG_INSTALL_STATE = "install_state"
        private const val ARG_POPULARITY_RANK = "popularity_rank"

        fun newInstance(
            entry: RepoEntry,
            baseEntry: RepoEntry?,
            thumbnailCache: ThumbnailCache? = null,
            trailerSearch: TrailerSearch? = null,
            settings: SettingsStore? = null,
            installer: Installer? = null,
            onInstall: ((RepoEntry) -> Unit)? = null,
            obbStatus: ObbStatus = if (entry.hasObb) ObbStatus.YES else ObbStatus.UNKNOWN,
            downloadedApkSize: Long? = null,
            installState: InstallState = InstallState.NOT_INSTALLED,
            popularityRank: Int = 0,
            onDeleteDownload: ((RepoEntry) -> Unit)? = null,
            onUninstalled: (() -> Unit)? = null,
        ): GameDetailDialog = GameDetailDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_ENTRY_JSON, entry.toJson().toString())
                baseEntry?.let { putString(ARG_BASE_ENTRY_JSON, it.toJson().toString()) }
                putString(ARG_OBB_STATUS, obbStatus.name)
                if (downloadedApkSize != null) putLong(ARG_DOWNLOADED_SIZE, downloadedApkSize)
                putString(ARG_INSTALL_STATE, installState.name)
                putInt(ARG_POPULARITY_RANK, popularityRank)
            }
            this.thumbnailCacheRef = thumbnailCache
            this.trailerSearchRef = trailerSearch
            this.settingsRef = settings
            this.installerRef = installer
            this.onInstallRef = onInstall
            this.onDeleteDownloadRef = onDeleteDownload
            this.onUninstalledRef = onUninstalled
        }
    }

    private var thumbnailCacheRef: ThumbnailCache? = null
    private var trailerSearchRef: TrailerSearch? = null
    private var settingsRef: SettingsStore? = null
    private var installerRef: Installer? = null
    private var onInstallRef: ((RepoEntry) -> Unit)? = null
    private var onDeleteDownloadRef: ((RepoEntry) -> Unit)? = null
    private var onUninstalledRef: (() -> Unit)? = null

    private lateinit var entry: RepoEntry
    private var baseEntry: RepoEntry? = null
    private lateinit var thumbnailCache: ThumbnailCache
    private lateinit var trailerSearch: TrailerSearch
    private lateinit var settings: SettingsStore
    private lateinit var installer: Installer
    private var obbStatus: ObbStatus = ObbStatus.UNKNOWN
    private var downloadedApkSize: Long? = null
    private var installState: InstallState = InstallState.NOT_INSTALLED
    private var popularityRank: Int = 0

    private var uninstallDeleteDownloads = false
    private var refreshChecklistRef: (() -> Unit)? = null
    private var refreshLaunchAndUninstallRef: (() -> Unit)? = null

    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!isAdded || !::entry.isInitialized) return@registerForActivityResult
            val ctx = requireContext()
            val actuallyGone = !GameUninstaller.isPackageInstalled(ctx, entry.packageName)
            if (result.resultCode == Activity.RESULT_OK || actuallyGone) {
                lifecycleScope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        GameUninstaller.cleanupAfterUninstall(
                            ctx, entry.packageName, uninstallDeleteDownloads
                        )
                    }
                    if (!isAdded) return@launch
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.uninstall_done,
                            if (uninstallDeleteDownloads && freed > 0) {
                                "${entry.displayName} (${RepoAdapter.formatSize(freed)} de descargas borrados)"
                            } else {
                                entry.displayName
                            }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    onUninstalledRef?.invoke()
                    parentFragmentManager.setFragmentResult(RESULT_UNINSTALLED, Bundle())
                    dismissAllowingStateLoss()
                }
            } else {
                StartupLog.mark(
                    ctx,
                    "desinstalar: ${entry.packageName} cancelado o no completado (resultCode=${result.resultCode})"
                )
                Toast.makeText(
                    ctx,
                    if (result.resultCode == Activity.RESULT_CANCELED) {
                        getString(R.string.uninstall_cancelled)
                    } else {
                        getString(R.string.uninstall_failed, "código ${result.resultCode}")
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun launchSystemUninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            uninstallLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            uninstallLauncher.launch(
                Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    /** Confirmación de desinstalación con opción de borrar también
     *  los archivos descargados (solo se ofrece si los hay). */
    private fun askUninstall() {
        if (!isAdded) return
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                GameUninstaller.downloadedSizeBytes(requireContext(), entry.packageName)
            }
            if (!isAdded) return@launch
            val ctx = requireContext()
            val check = android.widget.CheckBox(ctx).apply {
                text = ctx.getString(
                    R.string.uninstall_delete_downloads,
                    RepoAdapter.formatSize(size)
                )
                isChecked = false
                visibility = if (size > 0) View.VISIBLE else View.GONE
            }
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(check)
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.uninstall_title, entry.displayName))
                .setMessage(ctx.getString(R.string.uninstall_message, entry.packageName))
                .setView(container)
                .setPositiveButton(R.string.uninstall) { _, _ ->
                    uninstallDeleteDownloads = check.isChecked
                    StartupLog.mark(
                        ctx,
                        "desinstalar: ${entry.packageName} solicitado (borrar descargas=$uninstallDeleteDownloads)"
                    )
                    launchSystemUninstall(entry.packageName)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChecklistRef?.invoke()
        refreshLaunchAndUninstallRef?.invoke()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = arguments
        val parsedEntry = args?.getString(ARG_ENTRY_JSON)?.let { raw ->
            try {
                RepoEntry.fromJson(JSONObject(raw), "", "")
            } catch (_: Exception) {
                null
            }
        }
        if (parsedEntry == null) {
            dismissAllowingStateLoss()
            return android.app.AlertDialog.Builder(requireContext()).create()
        }
        entry = parsedEntry
        baseEntry = args.getString(ARG_BASE_ENTRY_JSON)?.let { raw ->
            try {
                RepoEntry.fromJson(JSONObject(raw), "", "")
            } catch (_: Exception) {
                null
            }
        }
        obbStatus = runCatching {
            ObbStatus.valueOf(args.getString(ARG_OBB_STATUS) ?: ObbStatus.UNKNOWN.name)
        }.getOrDefault(ObbStatus.UNKNOWN)
        downloadedApkSize = if (args.containsKey(ARG_DOWNLOADED_SIZE)) {
            args.getLong(ARG_DOWNLOADED_SIZE)
        } else {
            null
        }
        installState = runCatching {
            InstallState.valueOf(args.getString(ARG_INSTALL_STATE) ?: InstallState.NOT_INSTALLED.name)
        }.getOrDefault(InstallState.NOT_INSTALLED)
        popularityRank = args.getInt(ARG_POPULARITY_RANK, 0)

        val ctx = requireContext()
        settings = settingsRef ?: SettingsStore(ctx)
        thumbnailCache = thumbnailCacheRef ?: ThumbnailCache(ctx)
        trailerSearch = trailerSearchRef ?: TrailerSearch(settings)
        installer = installerRef ?: Installer(ctx)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_game_detail, null)
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setView(view)
            .create()

        val cover = view.findViewById<ImageView>(R.id.detail_cover)
        val coverFile = thumbnailCache.cachedFile(entry.packageName)
        val coverSource: Any? = coverFile ?: RepoAdapter.coverUrl(entry)
        cover.load(coverSource) {
            placeholder(R.drawable.ic_cover_placeholder)
            error(R.drawable.ic_cover_placeholder)
            crossfade(true)
        }
        cover.contentDescription = entry.displayName

        view.findViewById<TextView>(R.id.detail_name).text = entry.displayName
        view.findViewById<TextView>(R.id.detail_version).text = getString(
            R.string.detail_version, entry.versionName, entry.versionCode
        )
        view.findViewById<TextView>(R.id.detail_package).text =
            getString(R.string.detail_package, entry.packageName)
        view.findViewById<TextView>(R.id.detail_size).text =
            getString(
                R.string.detail_size,
                downloadedApkSize?.takeIf { it > 0 }?.let { RepoAdapter.formatSize(it) }
                    ?: RepoAdapter.formatSize(entry.apkSize)
            )
        val downloads = view.findViewById<TextView>(R.id.detail_downloads)
        downloads.text = if (popularityRank > 0) {
            getString(R.string.popularity_rank, "#$popularityRank")
        } else {
            getString(R.string.detail_downloads_unknown)
        }
        val tvSizeDownload = view.findViewById<TextView>(R.id.detail_size_download)
        val tvFreeSpace = view.findViewById<TextView>(R.id.detail_free_space)
        if (downloadedApkSize != null && downloadedApkSize!! > 0) {
            tvSizeDownload.text = getString(
                R.string.detail_size_download, RepoAdapter.formatSize(downloadedApkSize!!)
            )
        } else if (entry.vrpHash != null) {
            tvSizeDownload.text = getString(R.string.detail_size_querying)
            lifecycleScope.launch {
                val size = withContext(Dispatchers.IO) { installer.gameDownloadSize(entry) }
                if (!isAdded) return@launch
                tvSizeDownload.text = if (size != null && size.totalBytes > 0) {
                    getString(
                        R.string.detail_size_download,
                        "${RepoAdapter.formatSize(size.totalBytes)} (${size.parts} partes)"
                    )
                } else {
                    getString(R.string.detail_size_unavailable)
                }
            }
        } else {
            tvSizeDownload.text = getString(
                R.string.detail_size_download, RepoAdapter.formatSize(entry.apkSize)
            )
        }
        lifecycleScope.launch {
            val free = withContext(Dispatchers.IO) { installer.freeSpaceBytes() }
            if (isAdded && free > 0) {
                tvFreeSpace.text = getString(
                    R.string.detail_free_space, RepoAdapter.formatSize(free)
                )
            }
        }
        val descText = entry.description?.takeIf { it.isNotBlank() }
            ?: baseEntry?.description?.takeIf { it.isNotBlank() }
        val tvDesc = view.findViewById<TextView>(R.id.detail_description)
        tvDesc.text = if (descText != null) {
            getString(R.string.detail_notes_fmt, descText)
        } else {
            getString(R.string.detail_no_description)
        }
        view.findViewById<TextView>(R.id.detail_repo).text =
            getString(R.string.detail_repo, entry.repoName)
        val tvUpdated = view.findViewById<TextView>(R.id.detail_updated)
        val updated = entry.lastUpdated?.takeIf { it.isNotBlank() }
        if (updated != null) {
            tvUpdated.text = getString(R.string.detail_updated, updated)
            tvUpdated.visibility = View.VISIBLE
        } else {
            tvUpdated.visibility = View.GONE
        }
        val tvState = view.findViewById<TextView>(R.id.detail_state)
        val tvOrigin = view.findViewById<TextView>(R.id.detail_origin)
        val repoMgr = RepoManager(ctx)
        fun refreshStateLabel() {
            val installedNow = GameUninstaller.isPackageInstalled(requireContext(), entry.packageName)
            val effectiveState = if (installedNow && installState == InstallState.NOT_INSTALLED) {
                InstallState.INSTALLED
            } else {
                installState
            }
            val stateText = when (effectiveState) {
                InstallState.INSTALLED -> getString(R.string.state_installed)
                InstallState.UPDATE_AVAILABLE -> getString(R.string.state_update)
                InstallState.DOWNLOADED -> getString(R.string.state_downloaded)
                InstallState.AHEAD_OF_LIST -> getString(R.string.state_ahead)
                InstallState.NOT_INSTALLED -> getString(R.string.state_not_installed)
            }
            tvState.text = getString(R.string.detail_state, stateText)

            val origin = if (installedNow) repoMgr.installOriginOf(entry.packageName) else InstallOrigin.NONE
            when (origin) {
                InstallOrigin.META_STORE -> {
                    tvOrigin.visibility = View.VISIBLE
                    tvOrigin.text = getString(R.string.detail_origin_fmt, getString(R.string.origin_detail_meta))
                    tvOrigin.setBackgroundResource(R.drawable.bg_origin_meta)
                }
                InstallOrigin.GIRAQUEST -> {
                    tvOrigin.visibility = View.VISIBLE
                    tvOrigin.text = getString(R.string.detail_origin_fmt, getString(R.string.origin_detail_giraquest))
                    tvOrigin.setBackgroundResource(R.drawable.bg_origin_giraquest)
                }
                InstallOrigin.OTHER -> {
                    tvOrigin.visibility = View.VISIBLE
                    tvOrigin.text = getString(R.string.detail_origin_fmt, getString(R.string.origin_detail_other))
                    tvOrigin.setBackgroundResource(R.drawable.bg_origin_other)
                }
                InstallOrigin.NONE -> {
                    tvOrigin.visibility = View.GONE
                }
            }
        }
        refreshStateLabel()

        val obb = view.findViewById<TextView>(R.id.detail_obb)
        obb.text = when (obbStatus) {
            ObbStatus.YES -> getString(R.string.detail_has_obb)
            ObbStatus.NO -> getString(R.string.confirm_no_obb)
            ObbStatus.UNKNOWN -> getString(R.string.detail_obb_unknown)
        }
        val owned = view.findViewById<TextView>(R.id.detail_owned)
        owned.visibility = if (entry.requiresOwnedFiles) View.VISIBLE else View.GONE
        owned.text = getString(R.string.detail_owned_files)

        val tvCheckDownload = view.findViewById<TextView>(R.id.check_download)
        val tvCheckExtract = view.findViewById<TextView>(R.id.check_extract)
        val tvCheckApk = view.findViewById<TextView>(R.id.check_apk)
        val tvCheckObb = view.findViewById<TextView>(R.id.check_obb)
        val btnRetryObb = view.findViewById<Button>(R.id.btn_retry_obb)
        val btnObbSettings = view.findViewById<Button>(R.id.btn_obb_settings)
        fun refreshChecklist() {
            if (!isAdded) return
            val record = installer.installRecord(entry.packageName)
            tvCheckDownload.text = if (record?.downloadDone == true) {
                getString(R.string.check_download, RepoAdapter.formatSize(record.downloadBytes))
            } else {
                getString(R.string.check_download_pending)
            }
            tvCheckExtract.text = if (record?.extractDone == true) {
                getString(
                    R.string.check_extract,
                    if (record.extractSpeedMBps > 0) "%.0f MB/s".format(record.extractSpeedMBps) else "—"
                )
            } else {
                getString(R.string.check_extract_pending)
            }
            tvCheckApk.text = if (record?.apkInstalled == true) {
                getString(R.string.check_apk)
            } else {
                getString(R.string.check_apk_pending)
            }
            val obbState = installer.obbUiState(entry.packageName)
            when (obbState) {
                is ObbUiState.Copied -> {
                    tvCheckObb.text = getString(
                        if (obbState.unverified) R.string.check_obb_done_unverified
                        else R.string.check_obb_done,
                        obbState.files.size, RepoAdapter.formatSize(obbState.bytes),
                        entry.packageName
                    )
                    btnRetryObb.visibility = View.GONE
                    btnObbSettings.visibility = View.GONE
                }
                is ObbUiState.Pending -> {
                    val done = obbState.files.count { f ->
                        val dest = File(
                            Environment.getExternalStorageDirectory(),
                            "Android/obb/${entry.packageName}/${f.name}"
                        )
                        try {
                            java.io.RandomAccessFile(dest, "r").use { it.length() } == f.size
                        } catch (_: Exception) {
                            false
                        }
                    }
                    tvCheckObb.text = getString(
                        R.string.check_obb_pending, done, obbState.files.size, obbState.reason
                    )
                    btnRetryObb.visibility = View.VISIBLE
                    btnObbSettings.visibility = View.GONE
                }
                is ObbUiState.Failed -> {
                    tvCheckObb.text = getString(
                        R.string.check_obb_pending, 0, obbState.files.size, obbState.reason
                    )
                    btnRetryObb.visibility = View.VISIBLE
                    btnObbSettings.visibility = View.GONE
                }
                is ObbUiState.NotGranted -> {
                    tvCheckObb.text = getString(R.string.obb_state_not_granted)
                    btnRetryObb.visibility = View.GONE
                    btnObbSettings.visibility = View.VISIBLE
                }
                is ObbUiState.NoObb -> {
                    tvCheckObb.text = getString(R.string.check_obb_none)
                    btnRetryObb.visibility = View.GONE
                    btnObbSettings.visibility = View.GONE
                }
                is ObbUiState.Unknown -> {
                    tvCheckObb.text = getString(R.string.check_obb_unknown)
                    btnRetryObb.visibility = View.GONE
                    btnObbSettings.visibility = View.GONE
                }
            }
            if (record?.state == InstallRecordState.FAILED && record.error.isNotBlank()) {
                tvCheckObb.append("\n" + getString(R.string.check_failed, record.error))
            }
            when {
                record?.signatureMismatch == true -> {
                    btnObbSettings.visibility = View.VISIBLE
                    btnObbSettings.text = getString(R.string.uninstall_current_version)
                    btnObbSettings.setOnClickListener {
                        uninstallDeleteDownloads = false
                        launchSystemUninstall(entry.packageName)
                    }
                }
                obbState is ObbUiState.Failed &&
                    (GiraQuestApp.needsMountRestart(requireContext()) ||
                        "EACCES" in obbState.reason || "sin permiso" in obbState.reason ||
                        "reiniciar" in obbState.reason) -> {
                    btnObbSettings.visibility = View.VISIBLE
                    btnObbSettings.text = getString(R.string.settings_obb_activate_btn)
                    btnObbSettings.setOnClickListener {
                        val c = requireContext()
                        when {
                            !Environment.isExternalStorageManager() -> {
                                PermissionsHelper.openAppAllFilesSettings(requireActivity())
                            }
                            !c.packageManager.canRequestPackageInstalls() -> {
                                PermissionsHelper.openSettings(
                                    requireActivity(),
                                    PermissionsHelper.Kind.INSTALL_UNKNOWN
                                )
                            }
                            GiraQuestApp.needsMountRestart(c) -> {
                                Toast.makeText(c, R.string.restarting_to_activate_obb, Toast.LENGTH_SHORT).show()
                                ProcessRebirth.triggerRebirth(c)
                            }
                            else -> {
                                lifecycleScope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        installer.retryObb(entry.packageName)
                                    }
                                    if (isAdded) {
                                        refreshChecklistRef?.invoke()
                                        Toast.makeText(
                                            requireContext(),
                                            if (ok) R.string.obb_retried_ok else R.string.saf_tree_failed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }
                obbState is ObbUiState.NotGranted -> {
                    btnObbSettings.visibility = View.VISIBLE
                    btnObbSettings.text = getString(R.string.open_settings)
                    btnObbSettings.setOnClickListener {
                        PermissionsHelper.openSettings(
                            requireActivity(),
                            PermissionsHelper.Kind.ALL_FILES
                        )
                    }
                }
                else -> {
                    btnObbSettings.visibility = View.GONE
                }
            }
        }
        refreshChecklistRef = ::refreshChecklist
        refreshChecklist()
        btnRetryObb.setOnClickListener {
            btnRetryObb.isEnabled = false
            btnRetryObb.text = getString(R.string.obb_retrying)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { installer.retryObb(entry.packageName) }
                if (!isAdded) return@launch
                btnRetryObb.isEnabled = true
                btnRetryObb.text = getString(R.string.retry_obb)
                refreshChecklist()
                val state = installer.obbUiState(entry.packageName)
                Toast.makeText(
                    requireContext(),
                    if (ok) getString(R.string.obb_retried_ok)
                    else getString(
                        R.string.obb_retried_fail,
                        when (state) {
                            is ObbUiState.Pending -> state.reason
                            is ObbUiState.Failed -> state.reason
                            is ObbUiState.NotGranted -> "falta el permiso de acceso a archivos"
                            else -> "?"
                        }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val tvTrailerStatus = view.findViewById<TextView>(R.id.detail_trailer_status)
        val btnTrailer = view.findViewById<Button>(R.id.btn_trailer)
        btnTrailer.setOnClickListener {
            if (!settings.trailersEnabled) {
                tvTrailerStatus.text = getString(R.string.trailer_disabled)
                return@setOnClickListener
            }
            btnTrailer.isEnabled = false
            tvTrailerStatus.text = getString(R.string.trailer_searching)
            lifecycleScope.launch {
                val explicitId = entry.trailerYoutubeId
                val searchName = (baseEntry ?: entry).displayName
                val videoId = explicitId ?: trailerSearch.resolveVideoId(searchName)
                btnTrailer.isEnabled = true
                StartupLog.mark(
                    requireContext(),
                    if (videoId.isNullOrBlank()) "trailer: sin vídeo para \"$searchName\" (explícito=${explicitId != null})"
                    else "trailer: vídeo $videoId para \"$searchName\" (explícito=${explicitId != null})"
                )
                if (videoId.isNullOrBlank()) {
                    tvTrailerStatus.text = getString(R.string.no_trailer_found)
                } else {
                    tvTrailerStatus.text = ""
                    startActivity(
                        Intent(requireContext(), TrailerActivity::class.java).apply {
                            putExtra(TrailerActivity.EXTRA_VIDEO_ID, videoId)
                            putExtra(TrailerActivity.EXTRA_GAME_NAME, entry.displayName)
                        }
                    )
                }
            }
        }

        val btnLaunch = view.findViewById<Button>(R.id.btn_detail_launch)
        val btnInstall = view.findViewById<Button>(R.id.btn_detail_install)
        val btnDeleteDownload = view.findViewById<Button>(R.id.btn_detail_delete_download)
        val btnUninstall = view.findViewById<Button>(R.id.btn_detail_uninstall)

        fun refreshLaunchAndInstall() {
            if (!isAdded) return
            val c = requireContext()
            val installedNow = GameUninstaller.isPackageInstalled(c, entry.packageName)
            val installing = Installer.isInstalling(entry.packageName)
            btnLaunch.visibility = if (installedNow && !installing) View.VISIBLE else View.GONE
            btnUninstall.visibility = if (installedNow && !installing) View.VISIBLE else View.GONE

            btnInstall.isEnabled = !installing
            btnInstall.text = if (installing) {
                getString(R.string.installing)
            } else {
                when {
                    installState == InstallState.UPDATE_AVAILABLE -> getString(R.string.update_install)
                    installedNow || installState == InstallState.INSTALLED -> getString(R.string.reinstall)
                    else -> getString(R.string.install)
                }
            }
            refreshStateLabel()
        }
        refreshLaunchAndUninstallRef = ::refreshLaunchAndInstall
        refreshLaunchAndInstall()

        btnLaunch.setOnClickListener {
            if (!GameUninstaller.launchPackage(requireContext(), entry.packageName)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.launch_game_failed, entry.displayName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        lifecycleScope.launch {
            installer.progress.collectLatest {
                refreshChecklist()
                refreshLaunchAndInstall()
            }
        }
        lifecycleScope.launch {
            ObbEvents.flow.collectLatest { refreshChecklist() }
        }

        btnInstall.setOnClickListener {
            dismissAllowingStateLoss()
            val cb = onInstallRef
            if (cb != null) cb(entry) else installer.install(entry)
        }

        if (installState == InstallState.DOWNLOADED && onDeleteDownloadRef != null) {
            btnDeleteDownload.visibility = View.VISIBLE
            btnDeleteDownload.isEnabled = !Installer.isInstalling(entry.packageName)
            btnDeleteDownload.setOnClickListener {
                dismissAllowingStateLoss()
                onDeleteDownloadRef?.invoke(entry)
            }
        } else {
            btnDeleteDownload.visibility = View.GONE
        }
        btnUninstall.setOnClickListener { askUninstall() }

        val btnFav = view.findViewById<Button>(R.id.btn_detail_fav)
        fun refreshFav() {
            btnFav.text = if (settings.isFavorite(entry.packageName)) getString(R.string.unfav)
            else getString(R.string.fav)
        }
        refreshFav()
        btnFav.setOnClickListener {
            settings.toggleFavorite(entry.packageName)
            refreshFav()
        }
        view.findViewById<Button>(R.id.btn_detail_close).setOnClickListener { dismissAllowingStateLoss() }
        return dialog
    }
}
