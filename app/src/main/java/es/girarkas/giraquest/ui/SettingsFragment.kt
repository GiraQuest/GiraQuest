package es.girarkas.giraquest.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import es.girarkas.giraquest.BuildConfig
import es.girarkas.giraquest.GiraQuestApp
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.RepoManager
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.install.ObbSaf
import es.girarkas.giraquest.install.ProcessRebirth
import es.girarkas.giraquest.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestaña Ajustes (equivale a SettingsForm.cs del PC):
 * carpeta de descargas, límite de ancho de banda, auto-update del gestor,
 * gestión de repos, acerca de / créditos.
 */
class SettingsFragment : Fragment() {

    private lateinit var settings: SettingsStore

    private var tvObbStatus: TextView? = null
    private var tvObbProbeResult: TextView? = null

    private fun refreshObbStatus() {
        val tv = tvObbStatus ?: return
        if (!isAdded) return
        val ctx = requireContext()
        tv.text = when {
            !Environment.isExternalStorageManager() ->
                getString(R.string.settings_obb_missing_files)
            !ctx.packageManager.canRequestPackageInstalls() ->
                getString(R.string.settings_obb_missing_install)
            GiraQuestApp.needsMountRestart(ctx) ->
                getString(R.string.settings_obb_restart_needed)
            else ->
                getString(R.string.settings_obb_ready)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshObbStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = SettingsStore(requireContext())
        val repoManager = RepoManager(requireContext())
        val updater = Updater(requireContext())

        val etFolder = view.findViewById<EditText>(R.id.et_download_folder)
        val etBandwidth = view.findViewById<EditText>(R.id.et_bandwidth)
        val etUpdateRepo = view.findViewById<EditText>(R.id.et_update_repo)
        val swAutoUpdate = view.findViewById<Switch>(R.id.sw_auto_update)
        val swTrailers = view.findViewById<Switch>(R.id.sw_trailers)
        val swPublicRepo = view.findViewById<Switch>(R.id.sw_public_repo)
        val tvRepos = view.findViewById<TextView>(R.id.tv_repos)
        val tvAbout = view.findViewById<TextView>(R.id.tv_about)

        etFolder.setText(settings.downloadFolder)
        etBandwidth.setText(settings.bandwidthLimitKbps.toString())
        etUpdateRepo.setText(settings.updateRepo)
        swAutoUpdate.isChecked = settings.autoUpdateCheck
        swTrailers.isChecked = settings.trailersEnabled
        swPublicRepo.visibility = View.GONE

        fun refreshRepos() {
            val lines = mutableListOf<String>()
            settings.customVrpRepos.forEach { r ->
                lines += "• ${r.displayName} — ${r.normalizedBaseUri} (VRP)"
            }
            settings.repoUrls.forEach { u ->
                lines += "• $u"
            }
            tvRepos.text = if (lines.isEmpty()) {
                getString(R.string.settings_no_repos)
            } else {
                lines.joinToString("\n")
            }
        }
        refreshRepos()

        tvAbout.text = getString(
            R.string.about_text,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )

        view.findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            settings.downloadFolder = etFolder.text.toString().trim().ifBlank { SettingsStore.DEFAULT_DOWNLOAD_FOLDER }
            settings.bandwidthLimitKbps = etBandwidth.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            settings.updateRepo = etUpdateRepo.text.toString().trim()
            settings.autoUpdateCheck = swAutoUpdate.isChecked
            settings.trailersEnabled = swTrailers.isChecked
            Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btn_add_repo_settings).setOnClickListener {
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
            android.app.AlertDialog.Builder(ctx)
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
                            refreshRepos()
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.repo_added, repo.name, repo.entries.size),
                                Toast.LENGTH_LONG
                            ).show()
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

        view.findViewById<Button>(R.id.btn_remove_repo).setOnClickListener {
            val customList = settings.customVrpRepos
            val urlList = settings.repoUrls
            if (customList.isEmpty() && urlList.isEmpty()) {
                Toast.makeText(requireContext(), R.string.settings_no_repos, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            customList.forEach { r ->
                labels += "${r.displayName} (${r.normalizedBaseUri})"
                actions += { repoManager.removeCustomVrpRepo(r.normalizedBaseUri) }
            }
            urlList.forEach { u ->
                labels += u
                actions += { repoManager.removeRepo(u) }
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.remove_repo_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    actions[which].invoke()
                    refreshRepos()
                }
                .show()
        }

        view.findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val release = updater.checkLatest()
                if (release == null) {
                    Toast.makeText(requireContext(), R.string.update_none, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (updater.isNewer(release, BuildConfig.VERSION_NAME)) {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.update_available, release.tag))
                        .setMessage(release.body.ifBlank { release.name })
                        .setPositiveButton(R.string.update_open) { _, _ ->
                            startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.htmlUrl))
                            )
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    Toast.makeText(requireContext(), R.string.update_latest, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // v0.1.26: sin botón de asistente (eliminado: la app abre directo
        // en la lista y los permisos se piden en contexto).

        // v0.1.37: activación real de /Android/obb mediante
        // MANAGE_EXTERNAL_STORAGE + REQUEST_INSTALL_PACKAGES + reinicio
        // limpio del proceso para obtener MOUNT_EXTERNAL_INSTALLER.
        tvObbStatus = view.findViewById(R.id.tv_obb_access_status)
        tvObbProbeResult = view.findViewById(R.id.tv_obb_probe_result)
        refreshObbStatus()
        val btnGrantObb = view.findViewById<Button>(R.id.btn_grant_obb)
        btnGrantObb.setText(R.string.settings_obb_activate_btn)
        btnGrantObb.setOnClickListener {
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
                    viewLifecycleOwner.lifecycleScope.launch {
                        val direct = withContext(Dispatchers.IO) { ObbSaf.probeDirectWrite() }
                        if (!isAdded) return@launch
                        if (direct.ok) {
                            tvObbProbeResult?.text = getString(R.string.settings_obb_probe_ok, direct.detail)
                            Toast.makeText(ctx, R.string.onboarding_obb_probe_ok, Toast.LENGTH_SHORT).show()
                        } else {
                            // Si por cualquier razón el montaje aún no está activo, reiniciar.
                            Toast.makeText(ctx, R.string.restarting_to_activate_obb, Toast.LENGTH_SHORT).show()
                            ProcessRebirth.triggerRebirth(ctx)
                        }
                    }
                }
            }
        }
        view.findViewById<Button>(R.id.btn_check_obb).setOnClickListener {
            val ctx = requireContext()
            if (GiraQuestApp.needsMountRestart(ctx)) {
                val msg = getString(R.string.onboarding_obb_probe_restart)
                StartupLog.mark(ctx, "ajustes: comprobación Android/obb: $msg")
                tvObbProbeResult?.text = msg
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                refreshObbStatus()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val direct = withContext(Dispatchers.IO) { ObbSaf.probeDirectWrite() }
                if (!isAdded) return@launch
                StartupLog.mark(ctx, "ajustes: sonda directa Android/obb: ${direct.detail}")
                tvObbProbeResult?.text = if (direct.ok) {
                    getString(R.string.settings_obb_probe_ok, direct.detail)
                } else {
                    getString(R.string.settings_obb_probe_fail, direct.detail)
                }
                Toast.makeText(
                    ctx,
                    if (direct.ok) getString(R.string.settings_obb_check_ok, direct.detail)
                    else getString(R.string.settings_obb_check_fail, direct.detail),
                    Toast.LENGTH_LONG
                ).show()
                refreshObbStatus()
            }
        }

        // v0.1.17: el log que se comparte es el ARCHIVO VIVO del log
        // interno (filesDir/startup.log) vía FileProvider, no la copia
        // de Descargas. Edu compartía el archivo de Descargas desde el
        // explorador y ese archivo llevaba congelado desde las 00:07:14
        // (solo se reescribía al pulsar este botón): tres veces llegó
        // el mismo texto viejo. El archivo compartido lleva cabecera
        // con versión y fecha para comprobar de un vistazo que es
        // fresco. La copia de Descargas se refresca además en cada
        // arranque (GiraQuestApp), por si la comparte directamente.
        // Compartir el log.
    //
    // v0.1.28: la v0.1.27 mandaba EXTRA_TEXT con EL LOG ENTERO + el
    // archivo (EXTRA_STREAM) sin ClipData. En Android 14 ese texto
    // gigante desborda la transacción del destino y el envío se pierde
    // (el selector se abre, se elige destino y no llega nada). Ahora se
    // comparte SOLO el archivo fresco con cabecera de versión/fecha,
    // ClipData con la URI + FLAG_GRANT_READ_URI_PERMISSION, y una sola
    // línea de resumen en EXTRA_TEXT. El resultado queda en el log.
    view.findViewById<Button>(R.id.btn_share_log).setOnClickListener {
        val ctx = requireContext()
        val snapshot = StartupLog.shareSnapshotFile(
            ctx, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        if (snapshot == null) {
            Toast.makeText(ctx, R.string.log_export_failed, Toast.LENGTH_LONG).show()
            StartupLog.mark(ctx, "log: compartir FALLÓ (no se pudo escribir el snapshot)")
            return@setOnClickListener
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", snapshot)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GiraQuest — log de arranque")
            putExtra(
                Intent.EXTRA_TEXT,
                "GiraQuest v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — log completo en el archivo adjunto."
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(ctx.contentResolver, "GiraQuest — log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        StartupLog.mark(ctx, "log: compartiendo ${snapshot.name} (${snapshot.length()} bytes) vía FileProvider")
        try {
            startActivity(Intent.createChooser(share, getString(R.string.share_log)))
        } catch (e: Exception) {
            StartupLog.mark(ctx, "log: compartir FALLÓ al abrir el selector (${e.message})")
            Toast.makeText(ctx, R.string.log_export_failed, Toast.LENGTH_LONG).show()
        }
    }

        view.findViewById<Button>(R.id.btn_view_log).setOnClickListener {
            val ctx = requireContext()
            val text = StartupLog.readStartupLog(ctx)
            if (text.isNullOrBlank()) {
                Toast.makeText(ctx, R.string.log_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // v0.1.17: antes solo se enseñaban los últimos 6.000
            // caracteres y no se podían copiar; ahora el diálogo
            // muestra la cola completa útil y tiene botón Copiar con
            // el texto íntegro al portapapeles.
            android.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.log_title)
                .setMessage(text.takeLast(20000))
                .setPositiveButton(R.string.cancel, null)
                .setNeutralButton(R.string.log_copy) { _, _ ->
                    val clip = android.content.ClipData.newPlainText(
                        "GiraQuest — log", text
                    )
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager)
                        .setPrimaryClip(clip)
                    Toast.makeText(ctx, R.string.log_copied, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }
}
