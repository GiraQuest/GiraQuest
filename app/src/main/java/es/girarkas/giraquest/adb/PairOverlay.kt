package es.girarkas.giraquest.adb

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import es.girarkas.giraquest.R
import es.girarkas.giraquest.crash.StartupLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Ventana flotante de emparejamiento (v0.1.7, alternativa a la
 * notificación).
 *
 * El problema: en el Xiaomi de Edu, al salir del diálogo "Emparejar
 * dispositivo con código" de Ajustes para copiar el código en la app, el
 * diálogo se cierra de una manera u otra y todo se reinicia (código y
 * puerto nuevos). Copiar el código cambiando de app es imposible en su
 * móvil. Esta ventanita flota ENCIMA de Ajustes (TYPE_APPLICATION_OVERLAY,
 * permiso SYSTEM_ALERT_WINDOW): Edu lee el código del diálogo de Ajustes
 * y lo escribe en la ventanita sin cambiar de app. Se arrastra desde el
 * título para no tapar el código.
 *
 * La ventana es independiente de la actividad: sobrevive mientras Edu está
 * en Ajustes. Al emparejar (éxito o fallo con motivo) muestra el resultado
 * en la propia ventana y en una notificación, y se cierra; si falla, la
 * notificación con el código se vuelve a publicar para reintentar desde la
 * barra sin salir de Ajustes.
 */
object PairOverlay {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var watchJob: Job? = null
    private var pairingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    val isShowing: Boolean get() = view != null

    /** Muestra la ventana. False si falta el permiso de superposición. */
    fun show(context: Context): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false
        val appContext = context.applicationContext
        val windowManager =
            appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = try {
            LayoutInflater.from(appContext).inflate(R.layout.overlay_pair, null)
        } catch (e: Throwable) {
            StartupLog.mark(
                appContext,
                "pair overlay inflate FAILED: ${AdbHealth.fullChainReason(e)}"
            )
            return false
        }
        val metrics = appContext.resources.displayMetrics
        val width = (metrics.widthPixels - (48 * metrics.density).toInt())
            .coerceAtLeast(280)
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_TOUCH_MODAL: los toques fuera de la ventana llegan a
            // Ajustes. SIN NOT_FOCUSABLE: el campo de texto puede coger
            // el foco y salir el teclado.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * metrics.density).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            windowManager.addView(v, lp)
        } catch (e: Throwable) {
            StartupLog.mark(
                appContext,
                "pair overlay addView FAILED: ${AdbHealth.fullChainReason(e)}"
            )
            return false
        }
        wm = windowManager
        view = v
        params = lp
        wireViews(appContext, v)
        startWatch(appContext)
        return true
    }

    /** Quita la ventana si está puesta. */
    fun hide() {
        watchJob?.cancel()
        watchJob = null
        pairingJob?.cancel()
        pairingJob = null
        val v = view
        val manager = wm
        view = null
        wm = null
        params = null
        if (v != null && manager != null) {
            try {
                manager.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun wireViews(appContext: Context, v: View) {
        val tvTitle: TextView = v.findViewById(R.id.tv_overlay_title)
        val tvPort: TextView = v.findViewById(R.id.tv_overlay_port)
        val etCode: EditText = v.findViewById(R.id.et_overlay_code)
        val tvStatus: TextView = v.findViewById(R.id.tv_overlay_status)
        val btnPair: Button = v.findViewById(R.id.btn_overlay_pair)
        val btnClose: Button = v.findViewById(R.id.btn_overlay_close)

        // Arrastrar la ventana desde el título (para no tapar el código
        // que muestra el diálogo de Ajustes detrás).
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        tvTitle.setOnTouchListener { _, ev ->
            val p = params
            val manager = wm
            if (p == null || manager == null) return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = p.x
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (abs(dx) + abs(dy) > 4) {
                        p.x = startX + dx
                        p.y = startY + dy
                        try {
                            manager.updateViewLayout(view, p)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        }

        btnClose.setOnClickListener { hide() }

        btnPair.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (!Regex("\\d{6}").matches(code)) {
                tvStatus.text = appContext.getString(R.string.pair_result_no_code)
                return@setOnClickListener
            }
            btnPair.isEnabled = false
            tvStatus.text = appContext.getString(R.string.overlay_pairing)
            pairingJob?.cancel()
            pairingJob = scope.launch(Dispatchers.IO) {
                val result = doPair(appContext, code)
                val ok = result == appContext.getString(R.string.pair_result_ok)
                withContext(Dispatchers.Main) {
                    tvStatus.text = result
                    btnPair.isEnabled = true
                }
                // Avisar al asistente (paso 3) y dejar el resultado en
                // una notificación: la ventana se cierra y el resultado
                // queda visible.
                appContext.sendBroadcast(Intent(ACTION_PAIRING_RESULT).apply {
                    putExtra(EXTRA_PAIRING_OK, ok)
                    putExtra(EXTRA_PAIRING_MESSAGE, result)
                    setPackage(appContext.packageName)
                })
                postPairingResultNotification(appContext, ok, result)
                if (!ok) {
                    // Fallo: re-publicar la notificación con el código
                    // (si el diálogo de Ajustes sigue abierto) para
                    // reintentar desde la barra sin salir de Ajustes.
                    delay(4_000)
                    val epNow = try {
                        AdbPortDiscovery.discoverPairing(appContext, 5_000)
                    } catch (_: Exception) {
                        null
                    }
                    if (epNow != null) postPairingCodeNotification(appContext, epNow)
                }
                // Mostrar el resultado en la ventanita un momento y
                // cerrar (éxito o fallo con motivo).
                mainHandler.postDelayed({ hide() }, 1_500)
            }
        }
    }

    /** Vigila el servicio de emparejamiento y actualiza el puerto visible. */
    private fun startWatch(appContext: Context) {
        watchJob?.cancel()
        watchJob = scope.launch(Dispatchers.IO) {
            var lastEp: AdbPortDiscovery.Endpoint? = null
            while (view != null) {
                val ep = try {
                    AdbPortDiscovery.discoverPairing(appContext, 5_000)
                } catch (_: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    val v = view ?: return@withContext
                    val tvPort: TextView = v.findViewById(R.id.tv_overlay_port)
                    if (ep != null) {
                        if (ep != lastEp) {
                            lastEp = ep
                            tvPort.text = appContext.getString(
                                R.string.overlay_pair_port,
                                ep.port
                            )
                        }
                    } else {
                        if (lastEp != null) {
                            lastEp = null
                            tvPort.text = appContext.getString(
                                R.string.overlay_pair_no_service
                            )
                        }
                    }
                }
                delay(if (ep != null) 3_000 else 2_000)
            }
        }
    }

    /**
     * Empareja desde la ventana flotante: mismo flujo que el diálogo de la
     * app y que PairingCodeReceiver (descubrimiento fresco, pair, conectar,
     * WRITE_SECURE_SETTINGS), con la cadena de causas completa en los
     * errores y en el StartupLog.
     */
    private suspend fun doPair(appContext: Context, code: String): String {
        val manager = try {
            GiraAdbManager.getInstance(appContext)
        } catch (e: Throwable) {
            return appContext.getString(
                R.string.pair_result_adb_down,
                AdbHealth.fullChainReason(e) ?: "desconocido"
            )
        }
        try {
            manager.ensureAdbReady()
        } catch (_: Throwable) {
        }
        if (manager.initError != null || AdbHealth.firstError != null) {
            val reason = AdbHealth.fullChainFirstError()
                ?: manager.initError?.let { AdbHealth.fullChainReason(it) }
                ?: "desconocido"
            return appContext.getString(R.string.pair_result_adb_down, reason)
        }
        val freshEp = try {
            AdbPortDiscovery.discoverPairing(appContext, 8_000)
        } catch (_: Exception) {
            null
        }
        if (freshEp == null) {
            return appContext.getString(R.string.pair_result_no_service_now)
        }
        val outcome = manager.pairDeviceDetailed(freshEp.host, freshEp.port, code)
        if (!outcome.ok) {
            val msg = if (outcome.failure == GiraAdbManager.PairFailure.CONNECTION) {
                appContext.getString(
                    R.string.pair_result_conn_fail,
                    freshEp.host,
                    freshEp.port,
                    outcome.reason ?: "?"
                )
            } else {
                appContext.getString(
                    R.string.pair_result_code_wrong,
                    outcome.reason ?: "?"
                )
            }
            StartupLog.mark(
                appContext,
                "pair overlay FAILED (${outcome.failure}): ${freshEp.host}:${freshEp.port}: ${outcome.reason}"
            )
            return msg
        }
        val connectEp = try {
            AdbPortDiscovery.discoverConnect(appContext, 15_000)
        } catch (_: Exception) {
            null
        }
        val connected = if (connectEp != null) {
            manager.connectTo(connectEp.host, connectEp.port)
        } else {
            manager.connectSaved()
        }
        if (!connected) {
            return appContext.getString(R.string.pair_result_no_connect)
        }
        manager.grantWriteSecureSettings()
        return appContext.getString(R.string.pair_result_ok)
    }
}
