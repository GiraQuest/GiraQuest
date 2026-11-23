package es.girarkas.giraquest.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import es.girarkas.giraquest.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pase de Cloudflare con el motor real de Chrome del móvil (v0.1.10).
 *
 * El servidor de algunos repositorios devuelve HTTP 403 a
 * OkHttp/Conscrypt aunque la red sea la misma que la del PC (donde rclone
 * sí pasa): Cloudflare rechaza al cliente por su huella, no por la IP.
 * Un WebView es un Chrome de verdad: ejecuta el JavaScript del challenge
 * de Cloudflare y el servidor le deja la cookie `cf_clearance`. La app la
 * lee con CookieManager y reintenta la descarga con OkHttp enviando esa
 * cookie y el mismo User-Agent del WebView.
 *
 * Flujo:
 *  1. El WebView arranca OCULTO (1x1 px) cargando la raíz del mirror.
 *     En el caso normal el challenge es automático: se resuelve solo en
 *     unos segundos, sin que Edu tenga que hacer nada.
 *  2. Se vigila la cookie `cf_clearance` cada 600 ms. En cuanto aparece,
 *     se guarda (cookie + UA) en SettingsStore y la actividad termina con
 *     RESULT_OK; la biblioteca reintenta la descarga sola.
 *  3. Si la página pide interacción humana (checkbox de Turnstile), el
 *     WebView se hace VISIBLE a pantalla completa para que Edu lo toque.
 *  4. Si la página es un bloqueo duro ("You have been blocked") o pasan
 *     150 s sin cookie, termina con RESULT_CANCELED y el motivo en
 *     EXTRA_REASON.
 *
 * Por qué no un WebView siempre oculto: sin ventana ni viewport el
 * challenge puede no completarse, y si aparece el checkbox no habría
 * forma de tocarlo. Por qué no siempre visible: en el caso normal no
 * hay nada que ver ni que tocar.
 *
 * Todo queda en el StartupLog: URL, challenge detectado, cookie sí/no,
 * resultado.
 */
class CloudflareActivity : AppCompatActivity() {

    companion object {
        /** URL a cargar (la raíz del repositorio configurado). */
        const val EXTRA_URL = "url"
        /** Motivo del fallo cuando el resultado es RESULT_CANCELED. */
        const val EXTRA_REASON = "reason"

        private const val TIMEOUT_MS = 150_000L
        private const val POLL_MS = 600L
        /** Tiempo viendo "You have been blocked" antes de rendirse. */
        private const val BLOCKED_GRACE_MS = 10_000L
    }

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var settings: SettingsStore

    private var baseUrl: String = ""
    private var finished = false
    private var webViewVisible = false
    private var blockedSince: Long = 0L
    private val startTime = System.currentTimeMillis()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloudflare)
        settings = SettingsStore(this)
        baseUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (baseUrl.isBlank()) {
            finishWith(false, "sin URL")
            return
        }
        title = getString(R.string.cf_title)

        tvStatus = findViewById(R.id.tv_cf_status)
        progress = findViewById(R.id.progress_cf)
        webView = findViewById(R.id.webview_cf)

        StartupLog.mark(this, "cloudflare: abriendo WebView a $baseUrl")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // UA: el del WebView, a propósito (un Chrome real de este móvil).
        // Se guarda junto a la cookie: Cloudflare puede atar el pase al UA.
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false // todo dentro del WebView

            override fun onPageFinished(view: WebView, url: String) {
                StartupLog.mark(this@CloudflareActivity, "cloudflare: página cargada: $url (título: ${view.title})")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    StartupLog.mark(
                        this@CloudflareActivity,
                        "cloudflare: error WebView ${error.errorCode} en ${request.url}"
                    )
                }
            }
        }
        webView.loadUrl(baseUrl)

        lifecycleScope.launch { pollLoop() }
    }

    /** Bucle de vigilancia: cookie, challenge interactivo, bloqueo duro, timeout. */
    private suspend fun pollLoop() {
        while (!finished) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > TIMEOUT_MS) {
                StartupLog.mark(this, "cloudflare: sin cookie tras ${TIMEOUT_MS / 1000} s")
                finishWith(false, "timeout")
                return
            }
            // 1) ¿Ya hay cookie cf_clearance para este dominio?
            val cookies = try {
                CookieManager.getInstance().getCookie(baseUrl)
            } catch (_: Exception) {
                null
            }
            if (!cookies.isNullOrBlank() && "cf_clearance=" in cookies) {
                settings.cfClearanceCookie = cookies
                settings.cfUserAgent = webView.settings.userAgentString
                CookieManager.getInstance().flush()
                StartupLog.mark(
                    this,
                    "cloudflare: cookie cf_clearance obtenida (${elapsed / 1000} s)"
                )
                finishWith(true, null)
                return
            }
            // 2) ¿La página pide interacción o es un bloqueo duro?
            val flags = evalPageFlags()
            if (flags != null) {
                val interactive = flags.getOrNull(0) == '1'
                val blocked = flags.getOrNull(1) == '1'
                if (interactive && !webViewVisible) {
                    showWebView()
                    StartupLog.mark(this, "cloudflare: challenge interactivo, mostrando WebView")
                }
                if (blocked) {
                    if (blockedSince == 0L) blockedSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - blockedSince > BLOCKED_GRACE_MS) {
                        StartupLog.mark(this, "cloudflare: bloqueo duro también en el WebView")
                        finishWith(false, "blocked")
                        return
                    }
                } else {
                    blockedSince = 0L
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * Devuelve dos caracteres: [0] = '1' si hay widget de verificación
     * humana (Turnstile), [1] = '1' si la página es un bloqueo duro.
     * Null si el JS no se pudo evaluar todavía.
     */
    private suspend fun evalPageFlags(): String? = suspendCancellableCoroutine { cont ->
        try {
            webView.evaluateJavascript(
                "(function(){try{" +
                    "var b=document.body?document.body.innerText:'';" +
                    "var t=document.title||'';" +
                    "var inter=(document.querySelector('iframe[src*=\"challenges.cloudflare.com\"]')!=null)" +
                    "||/verify you are human/i.test(b);" +
                    "var blocked=/you have been blocked/i.test(b)||/attention required/i.test(t);" +
                    "return (inter?'1':'0')+(blocked?'1':'0');" +
                    "}catch(e){return '00';}})()",
            ) { value ->
                if (cont.isActive) {
                    cont.resume(value?.trim()?.trim('"')?.takeIf { it.length == 2 })
                }
            }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** El challenge necesita a Edu: WebView visible a pantalla completa. */
    private fun showWebView() {
        webViewVisible = true
        tvStatus.text = getString(R.string.cf_tap_checkbox)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
        ).apply {
            // LinearLayout con peso: el WebView ocupa lo que queda.
            if (this is android.widget.LinearLayout.LayoutParams) {
                weight = 1f
            }
        }
        webView.visibility = View.VISIBLE
    }

    private fun finishWith(ok: Boolean, reason: String?) {
        if (finished) return
        finished = true
        if (ok) {
            setResult(Activity.RESULT_OK)
        } else {
            setResult(
                Activity.RESULT_CANCELED,
                Intent().putExtra(EXTRA_REASON, reason ?: "?"),
            )
        }
        finish()
    }

    override fun onBackPressed() {
        StartupLog.mark(this, "cloudflare: cancelado por el usuario (atrás)")
        finishWith(false, "cancelled")
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
