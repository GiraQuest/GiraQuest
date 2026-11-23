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
import es.girarkas.giraquest.install.InstallSuccessEvents
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vista personalizada con rayos giratorios cartoon + explosión de confeti,
 * optimizada para Quest 3 (un único animador por vsync y Paths precalculados).
 */
class CartoonCelebrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Shape { RIBBON, STAR, DOT, DIAMOND }

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
        0xFFFFB020.toInt(), // amarillo sol
        0xFF22D3EE.toInt(), // cian lente
        0xFF6C4CF1.toInt(), // violeta GiraQuest
        0xFF14B8A6.toInt(), // turquesa
        0xFFFF4D8D.toInt(), // magenta cómic
        0xFF69F0AE.toInt(), // verde brillante
    )

    private val evenRaysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x24FFB020
    }
    private val oddRaysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1A22D3EE
    }
    private val partPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val evenRaysPath = Path()
    private val oddRaysPath = Path()
    private val unitStarPath = Path()
    private val unitDiamondPath = Path()
    private val particles = mutableListOf<Particle>()

    private var rayAngle = 0f
    private var burstElapsed = 0f
    private val burstDurationSec = 3.2f
    private var frameAnimator: ValueAnimator? = null
    private var lastTickNs = 0L

    init {
        for (k in 0 until 10) {
            val r = if (k % 2 == 0) 0.75f else 0.32f
            val ang = Math.toRadians((k * 36.0) - 90.0)
            val px = (cos(ang) * r).toFloat()
            val py = (sin(ang) * r).toFloat()
            if (k == 0) unitStarPath.moveTo(px, py) else unitStarPath.lineTo(px, py)
        }
        unitStarPath.close()

        unitDiamondPath.moveTo(0f, -0.75f)
        unitDiamondPath.lineTo(0.55f, 0f)
        unitDiamondPath.lineTo(0f, 0.75f)
        unitDiamondPath.lineTo(-0.55f, 0f)
        unitDiamondPath.close()
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

    fun startCelebration() {
        stopCelebration()
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
                    rayAngle = (rayAngle + 40f * dt) % 360f
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

    fun stopCelebration() {
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
        repeat(36) {
            val angle = rnd.nextFloat() * (Math.PI.toFloat() * 2f)
            val speed = (220f + rnd.nextFloat() * 480f) * density
            particles += Particle(
                x = cx + (rnd.nextFloat() - 0.5f) * 32f * density,
                y = cy + (rnd.nextFloat() - 0.5f) * 32f * density,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 180f * density,
                rot = rnd.nextFloat() * 360f,
                vRot = (rnd.nextFloat() - 0.5f) * 520f,
                size = (6f + rnd.nextFloat() * 8f) * density,
                color = palette[rnd.nextInt(palette.size)],
                shape = shapes[rnd.nextInt(shapes.size)],
            )
        }
    }

    private fun stepParticles(dt: Float) {
        val gravity = 680f * resources.displayMetrics.density
        val drag = (1f - 1.3f * dt).coerceAtLeast(0.8f)
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
                Shape.RIBBON -> canvas.drawRect(-s * 0.8f, -s * 0.35f, s * 0.8f, s * 0.35f, partPaint)
                Shape.DOT -> canvas.drawCircle(0f, 0f, s * 0.45f, partPaint)
                Shape.DIAMOND -> {
                    canvas.scale(s, s)
                    canvas.drawPath(unitDiamondPath, partPaint)
                }
                Shape.STAR -> {
                    canvas.scale(s, s)
                    canvas.drawPath(unitStarPath, partPaint)
                }
            }
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        stopCelebration()
        super.onDetachedFromWindow()
    }
}

/**
 * Controlador de la alerta animada cartoon en el centro de la app al
 * completar con éxito la instalación de un juego o APK.
 */
object InstallSuccessOverlay {

    private var activeOverlay: View? = null
    private var danceAnimator: AnimatorSet? = null
    private var enterAnimator: AnimatorSet? = null
    private var dismissRunnable: Runnable? = null

    fun show(activity: Activity, event: InstallSuccessEvents.Event) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(R.id.main_root) ?: return

        dismissImmediate(root)

        val overlay = LayoutInflater.from(activity)
            .inflate(R.layout.overlay_install_success, root, false)
        activeOverlay = overlay

        val backdrop = overlay.findViewById<View>(R.id.install_success_backdrop)
        val celebration = overlay.findViewById<CartoonCelebrationView>(R.id.cartoon_celebration_view)
        val card = overlay.findViewById<View>(R.id.cartoon_success_card)
        val stamp = overlay.findViewById<TextView>(R.id.tv_cartoon_stamp)
        val mascot = overlay.findViewById<ImageView>(R.id.iv_cartoon_mascot)
        val title = overlay.findViewById<TextView>(R.id.tv_cartoon_title)
        val funny = overlay.findViewById<TextView>(R.id.tv_cartoon_funny)
        val detail = overlay.findViewById<TextView>(R.id.tv_cartoon_detail)
        val okBtn = overlay.findViewById<Button>(R.id.btn_cartoon_ok)

        val funnyPhrases = listOf(
            activity.getString(R.string.install_success_sub_1),
            activity.getString(R.string.install_success_sub_2),
            activity.getString(R.string.install_success_sub_3),
            activity.getString(R.string.install_success_sub_4),
        )
        title.text = event.gameName
        funny.text = funnyPhrases.random()
        if (event.detail.isNotBlank()) {
            detail.text = event.detail
            detail.visibility = View.VISIBLE
        } else {
            detail.visibility = View.GONE
        }

        backdrop.alpha = 0f
        card.scaleX = 0.25f
        card.scaleY = 0.25f
        card.alpha = 0f
        stamp.scaleX = 2.4f
        stamp.scaleY = 2.4f
        stamp.alpha = 0f
        val density = activity.resources.displayMetrics.density
        mascot.translationY = 72f * density
        mascot.scaleX = 0.7f
        mascot.scaleY = 0.7f
        mascot.alpha = 0f

        root.addView(overlay)
        playSuccessSound(activity)

        card.post {
            if (activeOverlay !== overlay || overlay.parent == null) return@post
            card.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            mascot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            stamp.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            celebration.startCelebration()

            val fadeIn = ObjectAnimator.ofFloat(backdrop, View.ALPHA, 0f, 1f).setDuration(200L)
            val cardAlpha = ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).setDuration(160L)
            val kx = PropertyValuesHolder.ofKeyframe(
                View.SCALE_X,
                Keyframe.ofFloat(0f, 0.25f),
                Keyframe.ofFloat(0.45f, 1.14f),
                Keyframe.ofFloat(0.68f, 0.93f),
                Keyframe.ofFloat(0.85f, 1.03f),
                Keyframe.ofFloat(1f, 1.0f),
            )
            val ky = PropertyValuesHolder.ofKeyframe(
                View.SCALE_Y,
                Keyframe.ofFloat(0f, 0.25f),
                Keyframe.ofFloat(0.45f, 0.87f),
                Keyframe.ofFloat(0.68f, 1.09f),
                Keyframe.ofFloat(0.85f, 0.97f),
                Keyframe.ofFloat(1f, 1.0f),
            )
            val kr = PropertyValuesHolder.ofKeyframe(
                View.ROTATION,
                Keyframe.ofFloat(0f, -8f),
                Keyframe.ofFloat(0.45f, 4.5f),
                Keyframe.ofFloat(0.75f, -2f),
                Keyframe.ofFloat(1f, 0f),
            )
            val cardPop = ObjectAnimator.ofPropertyValuesHolder(card, kx, ky, kr).apply {
                duration = 540L
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        card.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                })
            }

            val mascotJump = AnimatorSet().apply {
                startDelay = 110L
                playTogether(
                    ObjectAnimator.ofFloat(mascot, View.ALPHA, 0f, 1f).setDuration(160L),
                    ObjectAnimator.ofFloat(mascot, View.TRANSLATION_Y, 72f * density, 0f).apply {
                        duration = 460L
                        interpolator = OvershootInterpolator(2.0f)
                    },
                    ObjectAnimator.ofFloat(mascot, View.SCALE_X, 0.7f, 1.0f).apply {
                        duration = 460L
                        interpolator = OvershootInterpolator(1.7f)
                    },
                    ObjectAnimator.ofFloat(mascot, View.SCALE_Y, 0.7f, 1.0f).apply {
                        duration = 460L
                        interpolator = OvershootInterpolator(1.7f)
                    },
                )
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (activeOverlay === overlay && overlay.parent != null) {
                            startMascotDance(mascot, density)
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
                playTogether(fadeIn, cardAlpha, cardPop, mascotJump, stampSlam)
                start()
            }
        }

        val closeAction = { dismissAnimated(root, overlay) }
        okBtn.setOnClickListener { closeAction() }
        backdrop.setOnClickListener { closeAction() }
        card.setOnClickListener { /* mantener abierto si toca dentro de la tarjeta */ }

        val r = Runnable { dismissAnimated(root, overlay) }
        dismissRunnable = r
        overlay.postDelayed(r, 6_200L)
    }

    private fun startMascotDance(mascot: ImageView, density: Float) {
        danceAnimator?.cancel()
        val bob = ObjectAnimator.ofFloat(mascot, View.TRANSLATION_Y, 0f, -9f * density, 0f).apply {
            duration = 660L
            repeatCount = ValueAnimator.INFINITE
        }
        val wiggle = ObjectAnimator.ofFloat(mascot, View.ROTATION, -5f, 5f, -5f).apply {
            duration = 960L
            repeatCount = ValueAnimator.INFINITE
        }
        val squash = ObjectAnimator.ofPropertyValuesHolder(
            mascot,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.04f, 0.97f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 0.96f, 1.03f, 1.0f),
        ).apply {
            duration = 660L
            repeatCount = ValueAnimator.INFINITE
        }
        danceAnimator = AnimatorSet().apply {
            playTogether(bob, wiggle, squash)
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
        danceAnimator?.cancel()
        danceAnimator = null

        val card = overlay.findViewById<View>(R.id.cartoon_success_card)
        val mascot = overlay.findViewById<View>(R.id.iv_cartoon_mascot)
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
                    overlay.findViewById<CartoonCelebrationView>(R.id.cartoon_celebration_view)
                        ?.stopCelebration()
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
            danceAnimator?.cancel()
            danceAnimator = null
            prev.findViewById<CartoonCelebrationView>(R.id.cartoon_celebration_view)?.stopCelebration()
            root.removeView(prev)
            activeOverlay = null
        }
    }

    private fun playSuccessSound(context: Context) {
        val appCtx = context.applicationContext
        Thread {
            try {
                val mp = MediaPlayer.create(appCtx, R.raw.install_success) ?: return@Thread
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
