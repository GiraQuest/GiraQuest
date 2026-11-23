package es.girarkas.giraquest.ui

import android.app.AlertDialog
import android.os.Environment
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import es.girarkas.giraquest.R
import es.girarkas.giraquest.install.Installer
import kotlinx.coroutines.launch
import java.io.File

/**
 * Oferta de OBB tras instalar un APK local (v0.1.28): si el APK trae
 * .obb en su misma carpeta o en la carpeta <nombre-apk>/, ofrece
 * copiarlos a /Android/obb/<paquete>/. Si no trae ninguno, no pregunta
 * nada: instala solo el APK. Los .obb que ya están copiados (mismo
 * tamaño en destino) no se ofrecen otra vez (el camino ADB ya los
 * copia de la carpeta <nombre-apk>/). onDone se llama siempre, una
 * sola vez, al terminar (copiados, saltados o sin OBB).
 */
object LocalObbOffer {

    fun maybeOffer(
        fragment: Fragment,
        installer: Installer,
        apkFile: File,
        onDone: () -> Unit,
    ) {
        var done = false
        fun once() {
            if (!done) {
                done = true
                onDone()
            }
        }
        if (!fragment.isAdded) {
            once()
            return
        }
        val ctx = fragment.requireContext()
        val pkg = installer.packageNameOfApk(apkFile)
        val obbs = installer.findLocalObbFiles(apkFile)
        if (pkg == null || obbs.isEmpty()) {
            once()
            return
        }
        val pending = obbs.filter { obb ->
            val dest = File(
                Environment.getExternalStorageDirectory(),
                "Android/obb/$pkg/${obb.name}"
            )
            !dest.exists() || dest.length() != obb.length()
        }
        if (pending.isEmpty()) {
            once()
            return
        }
        val total = pending.sumOf { it.length() }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.local_obb_title, pending.size))
            .setMessage(
                ctx.getString(R.string.local_obb_msg, RepoAdapter.formatSize(total), pkg) +
                    "\n\n" + pending.joinToString("\n") { "• ${it.name}" }
            )
            .setPositiveButton(R.string.local_obb_copy) { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    val res = installer.copyLocalObbFiles(pkg, pending)
                    if (!fragment.isAdded) {
                        once()
                        return@launch
                    }
                    val msg = when (res) {
                        is Installer.DirectObbResult.Ok ->
                            fragment.getString(
                                R.string.local_obb_done,
                                res.files.size,
                                RepoAdapter.formatSize(res.bytes),
                                pkg
                            )
                        is Installer.DirectObbResult.NotGranted ->
                            fragment.getString(R.string.local_obb_not_granted)
                        is Installer.DirectObbResult.Failed ->
                            fragment.getString(R.string.local_obb_failed, res.reason)
                    }
                    Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_LONG).show()
                    once()
                }
            }
            .setNegativeButton(R.string.local_obb_skip) { _, _ -> once() }
            .setOnCancelListener { once() }
            .show()
    }
}
