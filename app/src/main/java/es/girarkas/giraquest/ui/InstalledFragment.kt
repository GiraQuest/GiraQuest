package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.InstalledApp
import es.girarkas.giraquest.data.ObbUiState
import es.girarkas.giraquest.install.GameUninstaller
import es.girarkas.giraquest.install.Installer
import es.girarkas.giraquest.install.ObbEvents
import es.girarkas.giraquest.library.LibraryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestaña Instaladas: apps instaladas por GiraQuest (y, si se activa,
 * todas las de usuario) con desinstalación APK+OBB+datos.
 */
class InstalledFragment : Fragment() {

    private lateinit var library: LibraryManager
    private lateinit var installer: Installer
    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnToggle: Button
    private lateinit var adapter: InstalledAdapter
    private var showAll = false
    private var refreshJob: Job? = null

    data class InstalledRow(
        val app: InstalledApp,
        val obbBytes: Long,
        val obbState: ObbUiState,
    )

    private var pendingUninstallPackage: String? = null
    private var pendingUninstallLabel: String? = null
    private var pendingUninstallDeleteDownloads: Boolean = false
    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val pkg = pendingUninstallPackage ?: return@registerForActivityResult
            val label = pendingUninstallLabel ?: pkg
            pendingUninstallPackage = null
            if (!isAdded) return@registerForActivityResult
            val ctx = requireContext()
            val actuallyGone = !GameUninstaller.isPackageInstalled(ctx, pkg)
            if (result.resultCode == Activity.RESULT_OK || actuallyGone) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        GameUninstaller.cleanupAfterUninstall(
                            ctx, pkg, pendingUninstallDeleteDownloads
                        )
                    }
                    if (!isAdded) return@launch
                    Toast.makeText(
                        ctx,
                        getString(
                            R.string.uninstall_done,
                            if (pendingUninstallDeleteDownloads && freed > 0) {
                                "$label (${RepoAdapter.formatSize(freed)} de descargas borrados)"
                            } else {
                                label
                            }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            } else {
                StartupLog.mark(
                    ctx,
                    "desinstalar: $pkg cancelado o no completado (resultCode=${result.resultCode})"
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_installed, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        library = LibraryManager(requireContext())
        installer = Installer(requireContext())
        recycler = view.findViewById(R.id.recycler_installed)
        tvEmpty = view.findViewById(R.id.tv_empty_installed)
        btnToggle = view.findViewById(R.id.btn_toggle_all)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(true)
        adapter = InstalledAdapter(
            emptyList(),
            onUninstall = { row -> askUninstall(row.app) },
            onRetryObb = { row -> retryObb(row.app) }
        )
        recycler.adapter = adapter

        btnToggle.setOnClickListener {
            showAll = !showAll
            refresh()
        }
        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            ObbEvents.flow.collectLatest { refresh() }
        }
    }

    /** Recarga la lista en Dispatchers.IO para no bloquear la UI al cambiar de pestaña. */
    private fun refresh() {
        if (!::library.isInitialized || !isAdded) return
        btnToggle.text = if (showAll) getString(R.string.show_ours_only) else getString(R.string.show_all)
        val wantAll = showAll
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val apps = if (wantAll) library.allUserApps() else library.ourInstalledApps()
                apps.map { app ->
                    InstalledRow(
                        app = app,
                        obbBytes = library.obbSizeBytes(app.packageName),
                        obbState = installer.obbUiState(app.packageName),
                    )
                }
            }
            if (!isAdded || view == null) return@launch
            tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            adapter.update(rows)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun retryObb(app: InstalledApp) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { installer.retryObb(app.packageName) }
            if (!isAdded) return@launch
            val state = installer.obbUiState(app.packageName)
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
            refresh()
        }
    }

    private fun askUninstall(app: InstalledApp) {
        viewLifecycleOwner.lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) {
                GameUninstaller.downloadedSizeBytes(requireContext(), app.packageName)
            }
            if (!isAdded) return@launch
            val ctx = requireContext()
            val check = CheckBox(ctx).apply {
                text = ctx.getString(
                    R.string.uninstall_delete_downloads,
                    RepoAdapter.formatSize(size)
                )
                isChecked = false
                visibility = if (size > 0) View.VISIBLE else View.GONE
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(check)
            }
            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.uninstall_title, app.label))
                .setMessage(ctx.getString(R.string.uninstall_message, app.packageName))
                .setView(container)
                .setPositiveButton(R.string.uninstall) { _, _ ->
                    pendingUninstallPackage = app.packageName
                    pendingUninstallLabel = app.label
                    pendingUninstallDeleteDownloads = check.isChecked
                    StartupLog.mark(
                        ctx,
                        "desinstalar: ${app.packageName} solicitado (borrar descargas=$pendingUninstallDeleteDownloads)"
                    )
                    val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                        data = Uri.parse("package:${app.packageName}")
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    }
                    try {
                        uninstallLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        uninstallLauncher.launch(
                            Intent(Intent.ACTION_DELETE).apply {
                                data = Uri.parse("package:${app.packageName}")
                            }
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}

class InstalledAdapter(
    private var items: List<InstalledFragment.InstalledRow>,
    private val onUninstall: (InstalledFragment.InstalledRow) -> Unit,
    private val onRetryObb: (InstalledFragment.InstalledRow) -> Unit,
) : RecyclerView.Adapter<InstalledAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.item_name)
        val sub: TextView = view.findViewById(R.id.item_sub)
        val obb: TextView = view.findViewById(R.id.item_obb)
        val btnRetry: Button = view.findViewById(R.id.item_retry)
        val btn: Button = view.findViewById(R.id.item_action)
    }

    fun update(newItems: List<InstalledFragment.InstalledRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_installed, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val app = row.app
        holder.name.text = app.label
        val obb = row.obbBytes
        holder.sub.text = holder.itemView.context.getString(
            R.string.installed_sub_format,
            app.versionName ?: "?",
            app.versionCode,
            app.packageName,
            if (obb > 0) " · OBB " + RepoAdapter.formatSize(obb) else ""
        )
        val ctx = holder.itemView.context
        when (val state = row.obbState) {
            is ObbUiState.Copied -> {
                holder.obb.visibility = View.VISIBLE
                holder.obb.text = ctx.getString(
                    R.string.obb_state_copied,
                    state.files.size, RepoAdapter.formatSize(state.bytes)
                )
                holder.btnRetry.visibility = View.GONE
            }
            is ObbUiState.Pending -> {
                holder.obb.visibility = View.VISIBLE
                holder.obb.text = ctx.getString(R.string.obb_state_pending, state.reason)
                holder.btnRetry.visibility = View.VISIBLE
            }
            is ObbUiState.Failed -> {
                holder.obb.visibility = View.VISIBLE
                holder.obb.text = ctx.getString(R.string.obb_state_failed, state.reason)
                holder.btnRetry.visibility = View.VISIBLE
            }
            is ObbUiState.NotGranted -> {
                holder.obb.visibility = View.VISIBLE
                holder.obb.text = ctx.getString(R.string.obb_state_not_granted)
                holder.btnRetry.visibility = View.GONE
            }
            is ObbUiState.NoObb, is ObbUiState.Unknown -> {
                holder.obb.visibility = View.GONE
                holder.btnRetry.visibility = View.GONE
            }
        }
        holder.btnRetry.text = ctx.getString(R.string.retry_obb)
        holder.btnRetry.setOnClickListener { onRetryObb(row) }
        holder.btn.setText(R.string.uninstall)
        holder.btn.setOnClickListener { onUninstall(row) }
    }
}
