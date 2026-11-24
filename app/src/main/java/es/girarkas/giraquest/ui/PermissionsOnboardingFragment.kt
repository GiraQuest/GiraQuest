package es.girarkas.giraquest.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import es.girarkas.giraquest.GiraQuestApp
import es.girarkas.giraquest.MainActivity
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.install.ObbSaf
import es.girarkas.giraquest.install.ProcessRebirth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding de permisos de primer arranque (v0.1.28, a petición de Edu):
 * al arrancar por primera vez, la app pide TODOS los permisos que necesita
 * para funcionar, no solo en contexto antes de instalar.
 *
 * Orden: 1) acceso a todos los archivos (imprescindible: OBB a
 * Android/obb + leer descargas), 2) instalar apps desconocidas
 * (imprescindible: instalar los juegos), 3) acceso a la carpeta
 * Android/obb por VOLUMEN (v0.1.36: StorageVolume.createAccessIntent
 * — el sistema concede TODO el almacenamiento con un toque, SIN
 * navegar por carpetas: el selector de carpetas está MUERTO en este
 * visor (Edu 2026-11-20 14:00, firme: SAF RAÍZ MUERTA, no volver a
 * proponerlo) y la sesión del instalador también (v0.1.35: la
 * constante LOCATION_MEDIA_OBB no existe en Horizon). El botón
 * "Conceder" abre el diálogo de volumen; al volver, el árbol se
 * guarda y se VERIFICA (listar + sonda de escritura en Android/obb
 * raíz y carpeta de paquete). Queda el "Saltar este paso" y, desde
 * la v0.1.35, el botón "Comprobar acceso"
 * de la propia sección (sonda de escritura por la API de archivos,
 * sin selector), que dice la verdad. La vía nueva para colocar
 * los OBB es la sesión del instalador (InstallerSession, v0.1.34,
 * experimental): el sistema coloca el OBB), 4) notificaciones
 * (necesario: aviso de "instalado del todo"). NO se piden
 * WRITE_SECURE_SETTINGS (solo por ADB, muerto en Quest) ni
 * SYSTEM_ALERT_WINDOW (solo para el emparejamiento, también muerto).
 *
 * Flujo: cada "Conceder" abre su pantalla (Ajustes para 1 y 2,
 * diálogo del sistema para 4); al volver se re-comprueba y se pasa
 * al siguiente paso. Cuando los 4 pasos están resueltos (concedidos
 * o visitados y vueltos sin conceder), aparece el resumen
 * (verde/ámbar) + "Empezar". "Continuar sin conceder" salta el
 * onboarding sin atrapar al usuario (queda claro que sin los dos
 * primeros los juegos no se instalan).
 *
 * El paso OBB ya NO abre el selector (v0.1.34): el lanzador SAF y su
 * botón quedan en el código pero sin vía desde el onboarding — la
 * concesión, si algún visor la permitiera, sigue viviendo en
 * Ajustes ("Conceder acceso a Android/obb") con su verificación de
 * dos niveles. El diálogo contextual previo a instalar
 * (PermissionsHelper) sigue como red de seguridad por si alguien
 * saltó el onboarding.
 */
class PermissionsOnboardingFragment : Fragment() {

    private enum class Step { FILES, INSTALL, OBB, NOTIFY }

    private lateinit var settings: SettingsStore

    /** Pasos en los que el usuario ya abrió la pantalla/diálogo y volvió
     *  (concediera o no): un paso visitado cuenta como resuelto. */
    private val visited = mutableSetOf<Step>()

    private lateinit var rowFiles: LinearLayout
    private lateinit var rowInstall: LinearLayout
    private lateinit var rowObb: LinearLayout
    private lateinit var rowNotify: LinearLayout
    private lateinit var tvFilesStatus: TextView
    private lateinit var tvInstallStatus: TextView
    private lateinit var tvObbStatus: TextView
    private lateinit var tvNotifyStatus: TextView
    // v0.1.35: título/descripción de cada fila (para cambiar su color
    // según la tarjeta sea la actual — crema — o una normal).
    private lateinit var tvFilesTitle: TextView
    private lateinit var tvFilesDesc: TextView
    private lateinit var tvInstallTitle: TextView
    private lateinit var tvInstallDesc: TextView
    private lateinit var tvObbTitle: TextView
    private lateinit var tvObbDesc: TextView
    private lateinit var tvObbSessionNote: TextView
    private lateinit var btnObbProbe: Button
    private lateinit var tvObbProbeResult: TextView
    private lateinit var tvNotifyTitle: TextView
    private lateinit var tvNotifyDesc: TextView
    private lateinit var btnFiles: Button
    private lateinit var btnInstall: Button
    private lateinit var btnObb: Button
    private lateinit var btnObbSkip: Button
    private lateinit var tvObbSkipNote: TextView
    private lateinit var btnNotify: Button
    private lateinit var cardSummary: LinearLayout
    private lateinit var tvSumFiles: TextView
    private lateinit var tvSumInstall: TextView
    private lateinit var tvSumObb: TextView
    private lateinit var tvSumNotify: TextView

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            visited += Step.NOTIFY
            StartupLog.mark(
                requireContext(),
                "onboarding: notificaciones " +
                    if (granted) "concedidas (diálogo del sistema)" else "denegadas (diálogo del sistema)"
            )
            refresh()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_permissions_onboarding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = SettingsStore(requireContext())
        savedInstanceState?.getStringArrayList(KEY_VISITED)
            ?.mapNotNull { runCatching { Step.valueOf(it) }.getOrNull() }
            ?.let { visited += it }

        val langSpinner = view.findViewById<Spinner>(R.id.spinner_onboarding_language)
        LocaleHelper.bindSpinner(langSpinner, requireActivity())

        rowFiles = view.findViewById(R.id.row_onboarding_files)
        rowInstall = view.findViewById(R.id.row_onboarding_install)
        rowObb = view.findViewById(R.id.row_onboarding_obb)
        rowNotify = view.findViewById(R.id.row_onboarding_notify)
        tvFilesStatus = view.findViewById(R.id.tv_onboarding_files_status)
        tvInstallStatus = view.findViewById(R.id.tv_onboarding_install_status)
        tvObbStatus = view.findViewById(R.id.tv_onboarding_obb_status)
        tvNotifyStatus = view.findViewById(R.id.tv_onboarding_notify_status)
        tvFilesTitle = view.findViewById(R.id.tv_onboarding_files_title)
        tvFilesDesc = view.findViewById(R.id.tv_onboarding_files_desc)
        tvInstallTitle = view.findViewById(R.id.tv_onboarding_install_title)
        tvInstallDesc = view.findViewById(R.id.tv_onboarding_install_desc)
        tvObbTitle = view.findViewById(R.id.tv_onboarding_obb_title)
        tvObbDesc = view.findViewById(R.id.tv_onboarding_obb_desc)
        tvObbSessionNote = view.findViewById(R.id.tv_onboarding_obb_session_note)
        btnObbProbe = view.findViewById(R.id.btn_onboarding_obb_probe)
        tvObbProbeResult = view.findViewById(R.id.tv_onboarding_obb_probe_result)
        tvNotifyTitle = view.findViewById(R.id.tv_onboarding_notify_title)
        tvNotifyDesc = view.findViewById(R.id.tv_onboarding_notify_desc)
        btnFiles = view.findViewById(R.id.btn_onboarding_files)
        btnInstall = view.findViewById(R.id.btn_onboarding_install)
        btnObb = view.findViewById(R.id.btn_onboarding_obb)
        btnObbSkip = view.findViewById(R.id.btn_onboarding_obb_skip)
        tvObbSkipNote = view.findViewById(R.id.tv_onboarding_obb_skip_note)
        btnNotify = view.findViewById(R.id.btn_onboarding_notify)
        cardSummary = view.findViewById(R.id.card_onboarding_summary)
        tvSumFiles = view.findViewById(R.id.tv_onboarding_sum_files)
        tvSumInstall = view.findViewById(R.id.tv_onboarding_sum_install)
        tvSumObb = view.findViewById(R.id.tv_onboarding_sum_obb)
        tvSumNotify = view.findViewById(R.id.tv_onboarding_sum_notify)

        btnFiles.setOnClickListener {
            visited += Step.FILES
            PermissionsHelper.openAppAllFilesSettings(requireActivity())
        }
        btnInstall.setOnClickListener {
            visited += Step.INSTALL
            PermissionsHelper.openSettings(requireActivity(), PermissionsHelper.Kind.INSTALL_UNKNOWN)
        }
        // v0.1.37: el paso OBB ya NO abre StorageVolume.createAccessIntent
        // (deprecado en API 29, devuelve RESULT_CANCELED en Android 10+).
        // En Android 11–14, el acceso directo a /Android/obb se desbloquea
        // mediante REQUEST_INSTALL_PACKAGES + MANAGE_EXTERNAL_STORAGE en
        // cuanto el proceso arranca con ambos concedidos (MOUNT_EXTERNAL_INSTALLER).
        btnObb.setOnClickListener {
            visited += Step.OBB
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
                else -> refresh()
            }
        }
        btnObbSkip.setOnClickListener {
            visited += Step.OBB
            StartupLog.mark(
                requireContext(),
                "onboarding: paso Android/obb saltado por el usuario (se aplicará al pulsar Empezar si pasos 1 y 2 están concedidos)"
            )
            refresh()
        }
        btnObbProbe.setOnClickListener {
            val ctx = requireContext()
            if (GiraQuestApp.needsMountRestart(ctx)) {
                StartupLog.mark(
                    ctx,
                    "onboarding: sonda OBB pendiente de reinicio (proceso arrancó sin REQUEST_INSTALL_PACKAGES)"
                )
                tvObbProbeResult.text = getString(R.string.onboarding_obb_probe_restart)
                tvObbProbeResult.visibility = View.VISIBLE
                refresh()
                return@setOnClickListener
            }
            btnObbProbe.isEnabled = false
            btnObbProbe.text = getString(R.string.onboarding_obb_probe_checking)
            tvObbProbeResult.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { ObbSaf.probeDirectWrite() }
                if (!isAdded) return@launch
                StartupLog.mark(
                    requireContext(),
                    "onboarding: sonda de escritura Android/obb: ${result.detail}"
                )
                tvObbProbeResult.text = if (result.ok) {
                    getString(R.string.onboarding_obb_probe_ok)
                } else {
                    getString(R.string.onboarding_obb_probe_fail, result.detail)
                }
                tvObbProbeResult.visibility = View.VISIBLE
                btnObbProbe.isEnabled = true
                btnObbProbe.text = getString(R.string.onboarding_obb_probe)
                refresh()
            }
        }
        btnNotify.setOnClickListener {
            visited += Step.NOTIFY
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                refresh()
            }
        }
        view.findViewById<Button>(R.id.btn_onboarding_start).setOnClickListener {
            finish(skipped = false)
        }
        view.findViewById<TextView>(R.id.btn_onboarding_skip).setOnClickListener {
            finish(skipped = true)
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_VISITED, ArrayList(visited.map { it.name }))
    }

    override fun onResume() {
        super.onResume()
        // Al volver de una pantalla de Ajustes, re-comprobar si quedó
        // concedido y pasar al siguiente paso.
        refresh()
    }

    private fun isGranted(step: Step): Boolean {
        val ctx = requireContext()
        return when (step) {
            Step.FILES -> Environment.isExternalStorageManager()
            Step.INSTALL -> ctx.packageManager.canRequestPackageInstalls()
            Step.OBB -> ObbSaf.hasTree(ctx) ||
                (Environment.isExternalStorageManager() &&
                    ctx.packageManager.canRequestPackageInstalls() &&
                    GiraQuestApp.startedWithInstallPermission)
            Step.NOTIFY -> Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun titleOf(step: Step): String = when (step) {
        Step.FILES -> getString(R.string.onboarding_files_title)
        Step.INSTALL -> getString(R.string.onboarding_install_title)
        Step.OBB -> getString(R.string.onboarding_obb_title)
        Step.NOTIFY -> getString(R.string.onboarding_notif_title)
    }

    private fun refresh() {
        if (!isAdded || !::btnFiles.isInitialized) return
        updateRow(Step.FILES, rowFiles, tvFilesStatus, btnFiles)
        updateRow(Step.INSTALL, rowInstall, tvInstallStatus, btnInstall)
        updateRow(Step.OBB, rowObb, tvObbStatus, btnObb)
        updateRow(Step.NOTIFY, rowNotify, tvNotifyStatus, btnNotify)

        val ctx = requireContext()
        if (isGranted(Step.OBB)) {
            btnObb.visibility = View.GONE
        } else if (GiraQuestApp.needsMountRestart(ctx)) {
            tvObbStatus.text = getString(R.string.onboarding_obb_needs_restart)
            btnObb.visibility = View.VISIBLE
            btnObb.isEnabled = true
            btnObb.text = getString(R.string.onboarding_obb_activate_now)
        } else {
            btnObb.visibility = View.GONE
        }

        // v0.1.31: el salto del paso OBB solo se enseña mientras no
        // está concedido (concedido, sobra). Es el botón grande y
        // obvio de la tarjeta, no el "Continuar sin conceder" de
        // abajo.
        val obbSkipVisibility = if (isGranted(Step.OBB)) View.GONE else View.VISIBLE
        btnObbSkip.visibility = obbSkipVisibility
        tvObbSkipNote.visibility = obbSkipVisibility

        // Paso actual: el primero sin resolver (resalta su tarjeta).
        val current = Step.values().firstOrNull { !isGranted(it) && it !in visited }
        rowFiles.background = ContextCompat.getDrawable(
            requireContext(),
            if (current == Step.FILES) R.drawable.bg_perm_card else R.drawable.bg_card
        )
        rowInstall.background = ContextCompat.getDrawable(
            requireContext(),
            if (current == Step.INSTALL) R.drawable.bg_perm_card else R.drawable.bg_card
        )
        rowObb.background = ContextCompat.getDrawable(
            requireContext(),
            if (current == Step.OBB) R.drawable.bg_perm_card else R.drawable.bg_card
        )
        rowNotify.background = ContextCompat.getDrawable(
            requireContext(),
            if (current == Step.NOTIFY) R.drawable.bg_perm_card else R.drawable.bg_card
        )

        // v0.1.35 (Edu, captura 58629): la tarjeta del paso actual es
        // crema (bg_perm_card, casi blanca) y el texto de las filas
        // usa colorOnSurface, que en tema oscuro es casi blanco:
        // blanco sobre crema, ilegible. En la tarjeta actual el
        // título, la descripción y las notas van en tinta oscura
        // (gira_ink, ~13:1 sobre crema); en las demás, colorOnSurface
        // sobre la superficie del tema. La píldora de estado y las
        // insignias llevan sus propios colores y no se tocan.
        setRowTextColors(listOf(tvFilesTitle, tvFilesDesc), current == Step.FILES)
        setRowTextColors(listOf(tvInstallTitle, tvInstallDesc), current == Step.INSTALL)
        setRowTextColors(
            listOf(
                tvObbTitle, tvObbDesc, tvObbSessionNote,
                tvObbProbeResult, tvObbSkipNote
            ),
            current == Step.OBB
        )
        setRowTextColors(listOf(tvNotifyTitle, tvNotifyDesc), current == Step.NOTIFY)

        val allResolved = Step.values().all { isGranted(it) || it in visited }
        cardSummary.visibility = if (allResolved) View.VISIBLE else View.GONE
        if (allResolved) {
            setSummaryLine(tvSumFiles, Step.FILES)
            setSummaryLine(tvSumInstall, Step.INSTALL)
            setSummaryLine(tvSumObb, Step.OBB)
            setSummaryLine(tvSumNotify, Step.NOTIFY)
        }
    }

    /** v0.1.35: color de texto de una fila según su tarjeta: tinta
     *  oscura sobre la crema del paso actual, colorOnSurface del tema
     *  sobre la superficie (clara u oscura) de las demás. */
    private fun setRowTextColors(views: List<TextView>, isCurrent: Boolean) {
        val color = if (isCurrent) {
            ContextCompat.getColor(requireContext(), R.color.gira_ink)
        } else {
            onSurfaceColor()
        }
        views.forEach { it.setTextColor(color) }
    }

    private fun onSurfaceColor(): Int {
        val tv = TypedValue()
        return if (requireContext().theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true
            )
        ) {
            tv.data
        } else {
            ContextCompat.getColor(requireContext(), android.R.color.white)
        }
    }

    private fun updateRow(step: Step, row: LinearLayout, tvStatus: TextView, btn: Button) {
        val granted = isGranted(step)
        if (granted) {
            tvStatus.text = getString(R.string.onboarding_granted)
            // v0.1.34: verde vivo sobre la píldora oscura del estado
            // (contraste alto en tema claro y oscuro).
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.gira_green_bright))
            btn.text = getString(R.string.onboarding_granted)
            btn.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.onboarding_pending)
            // v0.1.34: amarillo vivo (sugerencia de Edu) sobre la
            // píldora oscura del estado: legible en ambos estados.
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.gira_yellow_bright))
            btn.text = getString(R.string.onboarding_grant)
            btn.isEnabled = true
        }
    }

    private fun setSummaryLine(tv: TextView, step: Step) {
        val ctx = requireContext()
        if (isGranted(step)) {
            tv.text = getString(R.string.onboarding_sum_granted, titleOf(step))
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.gira_green))
        } else {
            tv.text = getString(R.string.onboarding_sum_pending, titleOf(step))
            // v0.1.34: ámbar oscuro sobre la tarjeta crema del resumen
            // (~4,7:1; el amarillo oscuro anterior no se leía).
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.gira_amber_dark))
        }
    }

    /** Marca el onboarding como hecho, registra el resultado en el log
     *  y pasa a la lista de juegos. */
    private fun finish(skipped: Boolean) {
        settings.setPermissionsOnboardingDoneSync(true)
        val ctx = requireContext()
        val files = if (isGranted(Step.FILES)) "concedido" else "pendiente"
        val install = if (isGranted(Step.INSTALL)) "concedido" else "pendiente"
        val obb = when {
            isGranted(Step.OBB) -> "activo (MOUNT_EXTERNAL_INSTALLER)"
            GiraQuestApp.needsMountRestart(ctx) -> "listo tras reinicio automático"
            else -> "pendiente"
        }
        val notif = if (isGranted(Step.NOTIFY)) "concedido" else "pendiente"
        StartupLog.mark(
            ctx,
            "onboarding permisos${if (skipped) " (saltado por el usuario)" else ""}: " +
                "archivos=$files, instalar=$install, android/obb=$obb, notificaciones=$notif"
        )
        (activity as? MainActivity)?.onPermissionsOnboardingFinished()
    }

    companion object {
        private const val KEY_VISITED = "visited_steps"
    }
}
