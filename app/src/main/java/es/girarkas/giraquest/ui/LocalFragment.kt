package es.girarkas.giraquest.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import es.girarkas.giraquest.R
import es.girarkas.giraquest.install.Installer
import es.girarkas.giraquest.library.LocalLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pestaña Local / modo offline del PC: escanear una carpeta de APKs e
 * instalar APKs sueltos sin repo. El selector de archivos sustituye al
 * drag & drop del PC (no hay escritorio en el visor).
 */
class LocalFragment : Fragment() {

    private lateinit var localLibrary: LocalLibrary
    private lateinit var installer: Installer
    private var apks: List<LocalLibrary.LocalApk> = emptyList()
    private var refreshJob: Job? = null

    private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val file = copyToCache(uri) ?: run {
            Toast.makeText(requireContext(), getString(R.string.local_copy_failed), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        askInstallLocal(file)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val doc = DocumentFile.fromTreeUri(requireContext(), uri) ?: return@registerForActivityResult
        // Copiar los APK de la carpeta elegida a la carpeta local y escanear.
        val base = installer.downloadsBase().apply { mkdirs() }
        var count = 0
        for (child in doc.listFiles()) {
            if (child.name?.endsWith(".apk", true) == true) {
                requireContext().contentResolver.openInputStream(child.uri)?.use { input ->
                    File(base, child.name!!).outputStream().use { out -> input.copyTo(out) }
                    count++
                }
            }
        }
        Toast.makeText(requireContext(), getString(R.string.local_copied, count), Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_local, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        localLibrary = LocalLibrary(requireContext())
        installer = Installer(requireContext())
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_local)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        view.findViewById<Button>(R.id.btn_pick_apk).setOnClickListener {
            pickApk.launch("application/vnd.android.package-archive")
        }
        view.findViewById<Button>(R.id.btn_pick_folder).setOnClickListener {
            pickFolder.launch(null)
        }
        view.findViewById<Button>(R.id.btn_refresh_local).setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        if (!isAdded || view == null) return
        val downloadRoot = File(
            android.os.Environment.getExternalStorageDirectory(), "Download"
        )
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                localLibrary.scan(downloadRoot)
            }
            if (!isAdded || view == null) return@launch
            apks = scanned
            val recycler = requireView().findViewById<RecyclerView>(R.id.recycler_local)
            val tvEmpty = requireView().findViewById<TextView>(R.id.tv_empty_local)
            tvEmpty.visibility = if (apks.isEmpty()) View.VISIBLE else View.GONE
            tvEmpty.text = getString(R.string.local_empty, downloadRoot.absolutePath)
            recycler.adapter = LocalAdapter(apks, localLibrary) { askInstallLocal(it.file) }
        }
    }

    private fun copyToCache(uri: Uri): File? {
        return try {
            val name = DocumentFile.fromSingleUri(requireContext(), uri)?.name ?: "app.apk"
            val out = File(requireContext().cacheDir, name)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun askInstallLocal(apkFile: File) {
        // v0.1.26: permisos en contexto, igual que en la lista de repos.
        if (PermissionsHelper.showMissingDialog(
                requireActivity(),
                PermissionsHelper.missing(requireContext())
            )
        ) return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_title, apkFile.name))
            .setMessage(getString(R.string.local_confirm))
            .setPositiveButton(R.string.install) { _, _ ->
                val appCtx = context?.applicationContext
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
}

class LocalAdapter(
    private val items: List<LocalLibrary.LocalApk>,
    private val library: LocalLibrary,
    private val onInstall: (LocalLibrary.LocalApk) -> Unit,
) : RecyclerView.Adapter<LocalAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.item_name)
        val sub: TextView = view.findViewById(R.id.item_sub)
        val btn: Button = view.findViewById(R.id.item_action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_installed, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val apk = items[position]
        holder.name.text = apk.label ?: apk.file.name
        holder.sub.text = holder.itemView.context.getString(
            R.string.local_sub_format,
            apk.packageName ?: "?",
            apk.versionName ?: "?",
            apk.versionCode,
            RepoAdapter.formatSize(apk.file.length()),
            if (library.isInstalled(apk.packageName)) " · " + holder.itemView.context.getString(R.string.state_installed) else ""
        )
        holder.btn.setText(R.string.install)
        holder.btn.setOnClickListener { onInstall(apk) }
    }
}
