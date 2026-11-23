package es.girarkas.giraquest.trailer

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog

/**
 * Reproductor de tráilers — WebView con IFrame API de YouTube.
 *
 * v0.1.41: se carga player.html con loadDataWithBaseURL("https://<paquete>/", ...)
 * en lugar de file:///android_asset/, porque YouTube rechaza los embeds sin
 * Referer/Origin HTTPS válido (Error 153 "Video player configuration error").
 */
class TrailerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_GAME_NAME = "game_name"
    }

    private lateinit var webView: WebView
    private var videoId: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trailer)
        videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        title = intent.getStringExtra(EXTRA_GAME_NAME) ?: getString(R.string.trailer_title)

        if (!Regex("[A-Za-z0-9_\\-]{11}").matches(videoId)) {
            Toast.makeText(this, R.string.no_trailer_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val tvError = findViewById<TextView>(R.id.tv_trailer_error)
        webView = findViewById(R.id.webview_trailer)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPlayerError(code: Int) {
                runOnUiThread {
                    StartupLog.mark(this@TrailerActivity, "trailer: YouTube player error=$code videoId=$videoId")
                    val detail = when (code) {
                        101, 150 -> getString(R.string.trailer_embed_restricted)
                        100 -> getString(R.string.trailer_video_unavailable)
                        else -> getString(R.string.trailer_error, "YouTube ($code)")
                    }
                    tvError.visibility = View.VISIBLE
                    tvError.text = detail
                }
            }
        }, "GiraTrailer")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("loadVideoById('$videoId')", null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    tvError.visibility = View.VISIBLE
                    tvError.text = getString(R.string.trailer_error, error.description)
                    StartupLog.mark(
                        this@TrailerActivity,
                        "trailer: error cargando player/YouTube (${error.errorCode}: ${error.description}) videoId=$videoId"
                    )
                }
            }
        }
        StartupLog.mark(this, "trailer: abriendo videoId=$videoId")
        val html = assets.open("player.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL(
            "https://${packageName}/",
            html,
            "text/html",
            "utf-8",
            null,
        )

        findViewById<Button>(R.id.btn_open_youtube).setOnClickListener {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=$videoId")
            )
            val resolved = if (appIntent.resolveActivity(packageManager) != null) appIntent else webIntent
            try {
                startActivity(resolved)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.trailer_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_close_trailer).setOnClickListener { finish() }
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
