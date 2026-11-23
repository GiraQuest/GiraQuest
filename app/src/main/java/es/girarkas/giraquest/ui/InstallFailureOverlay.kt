package es.girarkas.giraquest.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import es.girarkas.giraquest.R
import es.girarkas.giraquest.install.InstallFailureEvents
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vista personalizada con rayos giratorios cartoon en tono rojo/ámbar
 * + lluvia cómic, optimizada para Quest 3 (un único animador por vsync y Paths precalculados).
 */
class CartoonFailureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Shape { CROSS, SPARK, DOT, TRIANGLE }

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var rot: Float,
        var vRot: Float,
        val size: Float,
        val color: Int,
        val shape: Shape,
    )

    private val palette = intArrayOf(
        0xFFFF5C77.toInt(), // rojo coral cómic
        0xFFFFB020.toInt(), // ámbar aviso
        0xFFFB7185.toInt(), // rosa alerta
        0xFFF59E0B.toInt(), // naranja
        0xFF6C4CF1.toInt(), // violeta GiraQuest
    )

    private val evenRaysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FF5C77
    }
    private val oddRaysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x18FFB020
    }
    private val partPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val evenRaysPath = Path()
    private val oddRaysPath = Path()
    private val unitTrianglePath = Path()
    private val particles = mutableListOf<Particle>()

    private var rayAngle = 0f
    private var burstElapsed = 0f
    private val burstDurationSec = 3.4f
    private var frameAnimator: ValueAnimator? = null
    private var lastTickNs = 0L

    init {
        unitTrianglePath.moveTo(0f, -0.75f)
        unitTrianglePath.lineTo(0.68f, 0.55f)
        unitTrianglePath.lineTo(-0.68f, 0.55f)
        unitTrianglePath.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildRays(w, h)
    }

    private fun rebuildRays(w: Int, h: Int) {
        evenRaysPath.reset()
        oddRaysPath.reset()
        if (w <= 0 || h <= 0) return
        val maxR = maxOf(w, h) * 0.72f
        val numRays = 12
        val stepDeg = 360f / numRays
        for (i in 0 until numRays) {
            val target = if (i % 2 == 0) evenRaysPath else oddRaysPath
            val a1 = Math.toRadians((i * stepDeg).toDouble())
            val a2 = Math.toRadians((i * stepDeg + stepDeg * 0.48f).toDouble())
            target.moveTo(0f, 0f)
            target.lineTo((cos(a1) * maxR).toFloat(), (sin(a1) * maxR).toFloat())
            target.lineTo((cos(a2) * maxR).toFloat(), (sin(a2) * maxR).toFloat())
            target.close()
        }
    }

    fun startAnimation() {
        stopAnimation()
        post {
            if (!isAttachedToWindow) return@post
            if (evenRaysPath.isEmpty) rebuildRays(width, height)
            spawnParticles()
            burstElapsed = 0f
            lastTickNs = System.nanoTime()
            frameAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val nowNs = System.nanoTime()
                    val dt = ((nowNs - lastTickNs) / 1_000_000_000f).coerceIn(0f, 0.04f)
                    lastTickNs = nowNs
                    rayAngle = (rayAngle - 32f * dt) % 360f
                    if (burstElapsed < burstDurationSec) {
                        burstElapsed += dt
                        stepParticles(dt)
                    }
                    invalidate()
                }
                start()
            }
        }
    }

    fun stopAnimation() {
        frameAnimator?.cancel()
        frameAnimator = null
    }

    private fun spawnParticles() {
        particles.clear()
        val cx = width * 0.5f
        val cy = height * 0.48f
        if (cx <= 0f || cy <= 0f) return
        val density = resources.displayMetrics.density
        val rnd = Random(System.currentTimeMillis())
        val shapes = Shape.values()
        repeat(28) {
            val angle = rnd.nextFloat() * (Math.PI.toFloat() * 2f)
            val speed = (180f + rnd.nextFloat() * 360f) * density
            particles += Particle(
                x = cx + (rnd.nextFloat() - 0.5f) * 28f * density,
                y = cy + (rnd.nextFloat() - 0.5f) * 28f * density,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 120f * density,
                rot = rnd.nextFloat() * 360f,
                vRot = (rnd.nextFloat() - 0.5f) * 400f,
                size = (6f + rnd.nextFloat() * 7.5f) * density,
                color = palette[rnd.nextInt(palette.size)],
                shape = shapes[rnd.nextInt(shapes.size)],
            )
        }
    }

    private fun stepParticles(dt: Float) {
        val gravity = 640f * resources.displayMetrics.density
        val drag = (1f - 1.4f * dt).coerceAtLeast(0.8f)
        for (p in particles) {
            p.vx *= drag
            p.vy = p.vy * drag + gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vRot * dt
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        if (cx <= 0f || cy <= 0f) return

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rayAngle)
        canvas.drawPath(evenRaysPath, evenRaysPaint)
        canvas.drawPath(oddRaysPath, oddRaysPaint)
        canvas.restore()

        val progress = (burstElapsed / burstDurationSec).coerceIn(0f, 1f)
        if (progress >= 1f) return
        val alphaFactor = if (progress < 0.7f) 1f else ((1f - progress) / 0.3f).coerceIn(0f, 1f)
        val alphaInt = (255 * alphaFactor).toInt().coerceIn(0, 255)
        if (alphaInt <= 0) return

        for (p in particles) {
            partPaint.color = p.color
            partPaint.alpha = alphaInt
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rot)
            val s = p.size
            when (p.shape) {
                Shape.CROSS -> {
                    partPaint.style = Paint.Style.STROKE
                    partPaint.strokeWidth = s * 0.36f
                    canvas.drawLine(-s * 0.6f, -s * 0.6f, s * 0.6f, s * 0.6f, partPaint)
                    canvas.drawLine(s * 0.6f, -s * 0.6f, -s * 0.6f, s * 0.6f, partPaint)
                    partPaint.style = Paint.Style.FILL
                }
                Shape.SPARK -> canvas.drawRect(-s * 0.7f, -s * 0.3f, s * 0.7f, s * 0.3f, partPaint)
                Shape.DOT -> canvas.drawCircle(0f, 0f, s * 0.42f, partPaint)
                Shape.TRIANGLE -> {
                    canvas.scale(s, s)
                    canvas.drawPath(unitTrianglePath, partPaint)
                }
            }
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}

/**
 * Controlador de la alerta animada cartoon en el centro de la app cuando
 * falla la instalación de un juego o APK, mostrando mascota, sonido y motivo.
 */
object InstallFailureOverlay {

    private var activeOverlay: View? = null
    private var wobbleAnimator: AnimatorSet? = null
    private var enterAnimator: AnimatorSet? = null
    private var dismissRunnable: Runnable? = null

    fun show(activity: Activity, event: InstallFailureEvents.Event) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(R.id.main_root) ?: return

        dismissImmediate(root)

        val overlay = LayoutInflater.from(activity)
            .inflate(R.layout.overlay_install_failure, root, false)
        activeOverlay = overlay

        val backdrop = overlay.findViewById<View>(R.id.install_failure_backdrop)
        val failureBg = overlay.findViewById<CartoonFailureView>(R.id.cartoon_failure_view)
        val card = overlay.findViewById<View>(R.id.cartoon_fail_card)
        val stamp = overlay.findViewById<TextView>(R.id.tv_cartoon_fail_stamp)
        val mascot = overlay.findViewById<ImageView>(R.id.iv_cartoon_fail_mascot)
        val title = overlay.findViewById<TextView>(R.id.tv_cartoon_fail_title)
        val funny = overlay.findViewById<TextView>(R.id.tv_cartoon_fail_funny)
        val reason = overlay.findViewById<TextView>(R.id.tv_cartoon_fail_reason)
        val okBtn = overlay.findViewById<Button>(R.id.btn_cartoon_fail_ok)

        val funnyPhrases = listOf(
            activity.getString(R.string.install_failure_sub_1),
            activity.getString(R.string.install_failure_sub_2),
            activity.getString(R.string.install_failure_sub_3),
        )
        title.text = event.gameName
        funny.text = funnyPhrases.random()
        reason.text = activity.getString(R.string.install_failure_reason_fmt, event.reason)

        backdrop.alpha = 0f
        card.scaleX = 0.28f
        card.scaleY = 0.28f
        card.alpha = 0f
        stamp.scaleX = 2.4f
        stamp.scaleY = 2.4f
        stamp.alpha = 0f
        val density = activity.resources.displayMetrics.density
        mascot.translationY = 56f * density
        mascot.scaleX = 0.72f
        mascot.scaleY = 0.72f
        mascot.alpha = 0f

        root.addView(overlay)
        playFailureSound(activity)

        card.post {
            if (activeOverlay !== overlay || overlay.parent == null) return@post
            card.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            mascot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            stamp.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            failureBg.startAnimation()

            val fadeIn = ObjectAnimator.ofFloat(backdrop, View.ALPHA, 0f, 1f).setDuration(200L)
            val cardAlpha = ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).setDuration(160L)
            val kx = PropertyValuesHolder.ofKeyframe(
                View.SCALE_X,
                Keyframe.ofFloat(0f, 0.28f),
                Keyframe.ofFloat(0.42f, 1.12f),
                Keyframe.ofFloat(0.68f, 0.94f),
                Keyframe.ofFloat(0.86f, 1.03f),
                Keyframe.ofFloat(1f, 1.0f),
            )
            val ky = PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y,
                Keyframe.ofFloat(0f, 0.28f),
                Keyframe.ofFloat(0.42f, 0.89f),
                Keyframe.ofFloat(0.68f, 1.07f),
                Keyframe.ofFloat(0.86f, 0.97f),
                Keyframe.ofFloat(1f, 1.0f),
            )
            val ktx = PropertyValuesHolder.ofKeyframe(
                View.TRANSLATION_X,
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.30f, -10f * density),
                Keyframe.ofFloat(0.50f, 10f * density),
                Keyframe.ofFloat(0.70f, -6f * density),
                Keyframe.ofFloat(0.85f, 4f * density),
                Keyframe.ofFloat(1f, 0f),
            )
            val cardPop = ObjectAnimator.ofPropertyValuesHolder(card, kx, ky, ktx).apply {
                duration = 540L
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        card.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                })
            }

            val mascotPop = AnimatorSet().apply {
                startDelay = 110L
                playTogether(
                    ObjectAnimator.ofFloat(mascot, View.ALPHA, 0f, 1f).setDuration(160L),
                    ObjectAnimator.ofFloat(mascot, View.TRANSLATION_Y, 56f * density, 0f).apply {
                        duration = 440L
                        interpolator = OvershootInterpolator(1.9f)
                    },
                    ObjectAnimator.ofFloat(mascot, View.SCALE_X, 0.72f, 1.0f).apply {
                        duration = 440L
                        interpolator = OvershootInterpolator(1.6f)
                    },
                    ObjectAnimator.ofFloat(mascot, View.SCALE_Y, 0.72f, 1.0f).apply {
                        duration = 440L
                        interpolator = OvershootInterpolator(1.6f)
                    },
                )
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (activeOverlay === overlay && overlay.parent != null) {
                            startMascotWobble(mascot, density)
                        } else {
                            mascot.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    }
                })
            }

            val stampSlam = AnimatorSet().apply {
                startDelay = 240L
                playTogether(
                    ObjectAnimator.ofFloat(stamp, View.ALPHA, 0f, 1f).setDuration(120L),
                    ObjectAnimator.ofFloat(stamp, View.SCALE_X, 2.4f, 0.92f, 1.0f).apply {
                        duration = 280L
                        interpolator = DecelerateInterpolator()
                    },
                    ObjectAnimator.ofFloat(stamp, View.SCALE_Y, 2.4f, 0.92f, 1.0f).apply {
                        duration = 280L
                        interpolator = DecelerateInterpolator()
                    },
                )
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        stamp.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                })
            }

            enterAnimator = AnimatorSet().apply {
                playTogether(fadeIn, cardAlpha, cardPop, mascotPop, stampSlam)
                start()
            }
        }

        val closeAction = { dismissAnimated(root, overlay) }
        okBtn.setOnClickListener { closeAction() }
        backdrop.setOnClickListener { closeAction() }
        card.setOnClickListener { /* mantener abierto si toca dentro de la tarjeta */ }

        val r = Runnable { dismissAnimated(root, overlay) }
        dismissRunnable = r
        overlay.postDelayed(r, 8_500L)
    }

    private fun startMascotWobble(mascot: ImageView, density: Float) {
        wobbleAnimator?.cancel()
        val floatY = ObjectAnimator.ofFloat(mascot, View.TRANSLATION_Y, 0f, -6f * density, 0f).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
        }
        val sway = ObjectAnimator.ofFloat(mascot, View.ROTATION, -6f, 6f, -6f).apply {
            duration = 1_100L
            repeatCount = ValueAnimator.INFINITE
        }
        wobbleAnimator = AnimatorSet().apply {
            playTogether(floatY, sway)
            start()
        }
    }

    private fun dismissAnimated(root: ViewGroup, overlay: View) {
        if (activeOverlay !== overlay) return
        dismissRunnable?.let { overlay.removeCallbacks(it) }
        dismissRunnable = null
        activeOverlay = null
        enterAnimator?.cancel()
        enterAnimator = null
        wobbleAnimator?.cancel()
        wobbleAnimator = null

        val card = overlay.findViewById<View>(R.id.cartoon_fail_card)
        val mascot = overlay.findViewById<View>(R.id.iv_cartoon_fail_mascot)
        mascot?.setLayerType(View.LAYER_TYPE_NONE, null)
        overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val outSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, overlay.alpha, 0f).apply {
                    duration = 280L
                    interpolator = DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(card, View.SCALE_X, card.scaleX, 0.82f).apply {
                    duration = 280L
                    interpolator = DecelerateInterpolator()
                },
                ObjectAnimator.ofFloat(card, View.SCALE_Y, card.scaleY, 0.82f).apply {
                    duration = 280L
                    interpolator = DecelerateInterpolator()
                },
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.findViewById<CartoonFailureView>(R.id.cartoon_failure_view)
                        ?.stopAnimation()
                    overlay.setLayerType(View.LAYER_TYPE_NONE, null)
                    card.setLayerType(View.LAYER_TYPE_NONE, null)
                    root.removeView(overlay)
                }
            })
        }
        outSet.start()
    }

    private fun dismissImmediate(root: ViewGroup) {
        activeOverlay?.let { prev ->
            dismissRunnable?.let { prev.removeCallbacks(it) }
            dismissRunnable = null
            enterAnimator?.cancel()
            enterAnimator = null
            wobbleAnimator?.cancel()
            wobbleAnimator = null
            prev.findViewById<CartoonFailureView>(R.id.cartoon_failure_view)?.stopAnimation()
            root.removeView(prev)
            activeOverlay = null
        }
    }

    private fun playFailureSound(context: Context) {
        val appCtx = context.applicationContext
        Thread {
            try {
                val mp = MediaPlayer.create(appCtx, R.raw.install_failure) ?: return@Thread
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setOnCompletionListener { it.release() }
                mp.setOnErrorListener { player, _, _ ->
                    player.release()
                    true
                }
                mp.start()
            } catch (_: Throwable) {
            }
        }.start()
    }
}
