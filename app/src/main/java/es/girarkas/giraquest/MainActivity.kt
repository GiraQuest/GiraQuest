package es.girarkas.giraquest

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import es.girarkas.giraquest.adb.GiraAdbManager
import es.girarkas.giraquest.crash.CrashReporter
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import es.girarkas.giraquest.install.InstallFailureEvents
import es.girarkas.giraquest.install.InstallSuccessEvents
import es.girarkas.giraquest.install.Installer
import es.girarkas.giraquest.install.ProcessRebirth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import es.girarkas.giraquest.ui.FileExplorerFragment
import es.girarkas.giraquest.ui.InstallFailureOverlay
import es.girarkas.giraquest.ui.InstallSuccessOverlay
import es.girarkas.giraquest.ui.InstalledFragment
import es.girarkas.giraquest.ui.LocalFragment
import es.girarkas.giraquest.ui.LocaleHelper
import es.girarkas.giraquest.ui.PermissionsOnboardingFragment
import es.girarkas.giraquest.ui.RepoFragment
import es.girarkas.giraquest.ui.SettingsFragment

/**
 * Actividad principal: 5 pestañas en barra inferior.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Cada cuánto reintenta los OBBs pendientes mientras la app está abierta. */
        private const val OBB_RETRY_TIMER_MS = 60_000L

        /** Pestaña a restaurar tras recrear la actividad (p. ej. cambio de idioma). */
        var pendingTabId: Int? = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private var mountRestartTriggered = false
    private var pendingCelebration: InstallSuccessEvents.Event? = null
    private var pendingFailure: InstallFailureEvents.Event? = null

    /** Si el usuario concedió REQUEST_INSTALL_PACKAGES tras arrancar
     *  el proceso, Android 12–14 NO reinicia el proceso y Zygote mantiene
     *  MOUNT_EXTERNAL_DEFAULT. Al volver en reposo, reiniciamos el proceso
     *  limpiamente para activar MOUNT_EXTERNAL_INSTALLER. */
    override fun onResume() {
        super.onResume()
        val onboardingVisible = supportFragmentManager.findFragmentByTag("onboarding") != null
        if (!onboardingVisible &&
            !mountRestartTriggered &&
            !Installer.isAnyInstalling() &&
            GiraQuestApp.needsMountRestart(this) &&
            ProcessRebirth.canTriggerAutoRestart(this)
        ) {
            mountRestartTriggered = true
            Toast.makeText(this, R.string.restarting_to_activate_obb, Toast.LENGTH_SHORT).show()
            ProcessRebirth.triggerRebirth(this)
            return
        }
        pendingCelebration?.let { ev ->
            pendingCelebration = null
            InstallSuccessOverlay.show(this, ev)
        }
        pendingFailure?.let { ev ->
            pendingFailure = null
            InstallFailureOverlay.show(this, ev)
        }
    }

    /** Marcador de cierre limpio. */
    override fun onDestroy() {
        super.onDestroy()
        StartupLog.markCleanShutdown(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupLog.mark(this, "main onCreate start")

        // Inflar el layout: si falla (recursos, BottomNavigationView...),
        // NO crashear: pantalla de error a mano y a esperar al usuario.
        val contentOk = try {
            setContentView(R.layout.activity_main)
            true
        } catch (e: Throwable) {
            StartupLog.mark(this, "main setContentView FAILED: ${e.javaClass.simpleName}: ${e.message}")
            CrashReporter.writeCrash(this, Thread.currentThread(), e)
            showFallbackScreen(e)
            return
        }
        if (!contentOk) return
        StartupLog.mark(this, "main content set")

        // El diálogo del crash anterior, LO PRIMERO.
        CrashReporter.showLastCrashIfAny(this)

        // v0.1.26: SIN asistente inicial — la app abre DIRECTO en la
        // lista de juegos. En Quest 3 no se puede emparejar ADB por
        // Ajustes y la puerta del asistente era absurda: los permisos
        // se piden ahora en contexto (tarjeta en la lista + diálogo
        // previo a instalar), y el ADB que haya se usa por detrás.

        // v0.1.28: onboarding de permisos de PRIMER ARRANQUE (a petición
        // de Edu): al arrancar por primera vez, la app pide TODOS los
        // permisos que necesita (archivos, instalar apps, notificaciones),
        // no solo en contexto antes de instalar. Si ya se hizo, abre
        // directo en la lista como siempre.
        val showOnboarding = savedInstanceState == null &&
            !SettingsStore(this).permissionsOnboardingDone

        try {
            val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
            if (nav == null) {
                StartupLog.mark(this, "main bottom_nav NULL")
                showTabError(getString(R.string.main_nav_missing))
            } else {
                nav.setOnItemSelectedListener { item -> switchToTab(item.itemId) }
                nav.setOnItemReselectedListener { item ->
                    if (item.itemId == R.id.nav_repo) {
                        val repo = supportFragmentManager.findFragmentByTag("tab_repo") as? RepoFragment
                        if (repo != null) {
                            repo.fastScrollToTop()
                        } else {
                            switchToTab(R.id.nav_repo)
                        }
                    }
                }
                val restoreTab = pendingTabId
                pendingTabId = null
                if (!showOnboarding) {
                    val targetTab = restoreTab ?: if (savedInstanceState == null) R.id.nav_repo else null
                    if (targetTab != null) {
                        try {
                            switchToTab(targetTab)
                            if (nav.selectedItemId != targetTab) {
                                nav.selectedItemId = targetTab
                            }
                        } catch (e: Throwable) {
                            StartupLog.mark(this, "main initial tab FAILED: ${e.javaClass.simpleName}: ${e.message}")
                            showTabError(getString(R.string.main_tab_open_error, e.message ?: ""))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            StartupLog.mark(this, "main nav setup FAILED: ${e.javaClass.simpleName}: ${e.message}")
            showTabError(getString(R.string.main_nav_error, e.message ?: ""))
        }

        // v0.1.28: mostrar el onboarding (barra inferior oculta mientras
        // tanto, para que no se pueda esquivar por las pestañas; la salida
        // es "Empezar" o "Continuar sin conceder"). Al recrear (rotación)
        // el fragmento sobrevive solo y la barra sigue oculta.
        if (showOnboarding) {
            try {
                findViewById<BottomNavigationView>(R.id.bottom_nav)?.visibility = View.GONE
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, PermissionsOnboardingFragment(), "onboarding")
                    .commit()
                StartupLog.mark(this, "main: onboarding de permisos (primer arranque)")
            } catch (e: Throwable) {
                StartupLog.mark(this, "main onboarding FAILED: ${e.javaClass.simpleName}: ${e.message}")
                findViewById<BottomNavigationView>(R.id.bottom_nav)?.visibility = View.VISIBLE
            }
        } else if (supportFragmentManager.findFragmentByTag("onboarding") != null) {
            findViewById<BottomNavigationView>(R.id.bottom_nav)?.visibility = View.GONE
        }

        StartupLog.mark(this, "main onCreate ok")

        // Alerta animada cartoon al completar con éxito una instalación.
        lifecycleScope.launch {
            InstallSuccessEvents.flow.collect { ev ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    pendingCelebration = null
                    InstallSuccessOverlay.show(this@MainActivity, ev)
                } else {
                    pendingCelebration = ev
                }
            }
        }

        // Alerta animada cartoon al fallar una instalación (imagen, sonido y motivo).
        lifecycleScope.launch {
            InstallFailureEvents.flow.collect { ev ->
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    pendingFailure = null
                    InstallFailureOverlay.show(this@MainActivity, ev)
                } else {
                    pendingFailure = ev
                }
            }
        }

        // Re-activar la depuración inalámbrica directa si WRITE_SECURE_SETTINGS está concedido.
        lifecycleScope.launch {
            try {
                val store = SettingsStore(this@MainActivity)
                if (store.writeSecureSettingsGranted) {
                    val adb = try {
                        GiraAdbManager.getInstance(this@MainActivity)
                    } catch (_: Throwable) {
                        null
                    }
                    adb?.enableWirelessDebuggingDirect()
                }
            } catch (_: Throwable) {
            }
        }

        // v0.1.22: reintento por TEMPORIZADOR de OBBs pendientes. Si el
        // ADB estaba muerto al instalar, el OBB queda pendiente; mientras
        // la app esté abierta, reintentarlo cada 60 s sin que el usuario
        // tenga que abrir la biblioteca ni tocar nada (antes el reintento
        // solo disparaba al volver a la pestaña de repos — y con el
        // throttle de 5 min consumido por intentos con ADB muerto, el OBB
        // esperaba eternamente). El timer corre en lifecycleScope PLANO
        // (SIN repeatOnLifecycle RESUMED) y hace un primer intento ~5 s
        // después de arrancar, luego cada 60 s. Loguea cada disparo.
        lifecycleScope.launch {
            StartupLog.mark(
                this@MainActivity,
                "main timer OBB: activo (primer intento en 5s, luego cada 60s)"
            )
            delay(5_000)
            while (true) {
                try {
                    val pending = SettingsStore(this@MainActivity).pendingObbs
                    if (pending.isNotEmpty()) {
                        StartupLog.mark(
                            this@MainActivity,
                            "main timer OBB: disparo con ${pending.size} pendientes (${pending.joinToString(", ") { it.packageName }})"
                        )
                        val completed = Installer(this@MainActivity).completePendingObbs()
                        if (completed.isNotEmpty()) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.obb_completed_toast, completed.joinToString(", ")),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (_: Throwable) {
                }
                delay(OBB_RETRY_TIMER_MS)
            }
        }
    }

    private fun switchToTab(itemId: Int): Boolean {
        val tabTags = listOf("tab_repo", "tab_installed", "tab_local", "tab_files", "tab_settings")
        val targetTag = when (itemId) {
            R.id.nav_repo -> "tab_repo"
            R.id.nav_installed -> "tab_installed"
            R.id.nav_local -> "tab_local"
            R.id.nav_files -> "tab_files"
            R.id.nav_settings -> "tab_settings"
            else -> "tab_repo"
        }
        return try {
            val fm = supportFragmentManager
            val tx = fm.beginTransaction()
            fm.findFragmentByTag("onboarding")?.let { tx.remove(it) }
            for (t in tabTags) {
                if (t != targetTag) {
                    fm.findFragmentByTag(t)?.let { existing ->
                        if (!existing.isHidden) tx.hide(existing)
                        tx.setMaxLifecycle(existing, Lifecycle.State.STARTED)
                    }
                }
            }
            val target = fm.findFragmentByTag(targetTag)
            if (target == null) {
                val created: Fragment = when (itemId) {
                    R.id.nav_repo -> RepoFragment()
                    R.id.nav_installed -> InstalledFragment()
                    R.id.nav_local -> LocalFragment()
                    R.id.nav_files -> FileExplorerFragment()
                    R.id.nav_settings -> SettingsFragment()
                    else -> RepoFragment()
                }
                tx.add(R.id.fragment_container, created, targetTag)
            } else {
                tx.show(target)
                tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
            }
            tx.commitAllowingStateLoss()
            true
        } catch (e: Throwable) {
            StartupLog.mark(this, "main fragment switch FAILED: ${e.javaClass.simpleName}: ${e.message}")
            showTabError(getString(R.string.main_tab_open_error, e.message ?: ""))
            false
        }
    }

    /** v0.1.28: el onboarding terminó ("Empezar" o "Continuar sin
     *  conceder"): vuelve la barra inferior y se abre la lista de juegos.
     *  v0.1.37: si durante el onboarding se concedió REQUEST_INSTALL_PACKAGES
     *  en este mismo proceso, reiniciamos el proceso antes de entrar a la
     *  lista para que Zygote monte /Android/obb con MOUNT_EXTERNAL_INSTALLER. */
    fun onPermissionsOnboardingFinished() {
        StartupLog.mark(this, "main: onboarding de permisos terminado, a la lista de juegos")
        if (!mountRestartTriggered &&
            GiraQuestApp.needsMountRestart(this) &&
            ProcessRebirth.canTriggerAutoRestart(this)
        ) {
            mountRestartTriggered = true
            Toast.makeText(this, R.string.restarting_to_activate_obb, Toast.LENGTH_SHORT).show()
            ProcessRebirth.triggerRebirth(this)
            return
        }
        try {
            val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
            nav?.visibility = View.VISIBLE
            switchToTab(R.id.nav_repo)
            if (nav != null && nav.selectedItemId != R.id.nav_repo) {
                nav.selectedItemId = R.id.nav_repo
            }
        } catch (e: Throwable) {
            StartupLog.mark(this, "main onboarding finish FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** v0.1.28: abre el explorador de archivos en una carpeta (o en la
     *  raíz si es null). Lo usa el botón "Instalar APK" de la lista para
     *  abrir Descargas directamente. */
    fun openFileExplorer(startPath: String?) {
        FileExplorerFragment.pendingStartPath = startPath
        try {
            val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
            if (nav != null && nav.selectedItemId == R.id.nav_files) {
                switchToTab(R.id.nav_files)
            } else {
                nav?.selectedItemId = R.id.nav_files
            }
        } catch (e: Throwable) {
            StartupLog.mark(this, "main openFileExplorer FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Muestra un error dentro del contenedor de fragmentos, sin crashear. */
    private fun showTabError(message: String) {
        try {
            val container = findViewById<ViewGroup>(R.id.fragment_container) ?: return
            val tv = TextView(this).apply {
                text = message
                setPadding(32, 32, 32, 32)
            }
            container.removeAllViews()
            container.addView(tv)
        } catch (_: Throwable) {
        }
    }

    /**
     * Pantalla de error construida a mano (sin recursos XML): se usa solo
     * si setContentView falla. La app queda abierta y explica el problema,
     * con botones para compartir el error y reintentar.
     */
    private fun showFallbackScreen(error: Throwable) {
        try {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
            val title = TextView(this).apply {
                text = "GiraQuest no pudo abrir su pantalla principal"
                textSize = 20f
            }
            val body = TextView(this).apply {
                text = "Error: ${error.javaClass.simpleName}: ${error.message}\n\n" +
                    "El error quedó guardado. Compártelo para arreglarlo."
                setPadding(0, 24, 0, 24)
            }
            val share = Button(this).apply {
                text = "Compartir error"
                setOnClickListener {
                    val startup = StartupLog.readStartupLog(this@MainActivity) ?: ""
                    val crash = CrashReporter.readLastCrash(this@MainActivity) ?: ""
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "GiraQuest — informe de crash")
                        putExtra(Intent.EXTRA_TEXT, crash + "\n\n--- Arranque ---\n" + startup)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Compartir informe de crash"))
                }
            }
            val retry = Button(this).apply {
                text = "Reintentar"
                setOnClickListener { recreate() }
            }
            layout.addView(title)
            layout.addView(body)
            layout.addView(share)
            layout.addView(retry)
            setContentView(layout)
        } catch (_: Throwable) {
            // Si ni la pantalla a mano funciona, no queda nada que hacer.
        }
    }
}
