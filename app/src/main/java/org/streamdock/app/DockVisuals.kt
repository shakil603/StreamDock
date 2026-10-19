package org.streamdock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatTextView
import java.util.Random

/**
 * StreamDock 1.6.0 visual language — "Frozen Glass".
 *
 * Everything on screen is drawn to read as a physical, frosted glass slab: a soft vertical
 * light falloff, a specular highlight in the top corner, a darker bottom edge for thickness and a
 * holographic rim that catches cyan / violet / amber. The accent is ice blue; body copy is deep ink
 * so the interface stays readable outdoors.
 *
 * Pure framework graphics (no external libraries), so the APK stays small and every draw call works
 * from Android 7 to current.
 */
object Dock {
    val ink = Color.rgb(0x0C, 0x16, 0x20)
    val inkSoft = Color.rgb(0x57, 0x6A, 0x7B)
    val inkFaint = Color.rgb(0x8A, 0x9C, 0xAC)
    val accent = Color.rgb(0x18, 0x8F, 0xD6)
    val accentSoft = Color.rgb(0x5A, 0xCB, 0xF2)
    val line = Color.rgb(0xC4, 0xD7, 0xE7)
    val iceGrad = intArrayOf(0xFF8FE6FF.toInt(), 0xFF3FB7F2.toInt(), 0xFF5C7BFF.toInt())

    private val faces = HashMap<String, Typeface>()
    private fun face(context: Context, asset: String, fallback: String, style: Int): Typeface =
        faces.getOrPut(asset) {
            runCatching { Typeface.createFromAsset(context.assets, asset) }
                .getOrElse { Typeface.create(fallback, style) }
        }

    fun light(c: Context) = face(c, "fonts/manrope-300.ttf", "sans-serif-light", Typeface.NORMAL)
    fun regular(c: Context) = face(c, "fonts/manrope-400.ttf", "sans-serif", Typeface.NORMAL)
    fun medium(c: Context) = face(c, "fonts/manrope-600.ttf", "sans-serif-medium", Typeface.NORMAL)
    fun bold(c: Context) = face(c, "fonts/manrope-800.ttf", "sans-serif", Typeface.BOLD)

    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()
    fun px(c: Context, n: Float) = n * c.resources.displayMetrics.density

    fun glass(c: Context, radiusDp: Float, strength: Float = 1f, holographic: Boolean = true) =
        GlassSurface(px(c, radiusDp), px(c, 1.1f), strength, holographic)

    /** Real elevation plus a coloured shadow, so a slab looks lifted instead of pasted on. */
    fun depth(view: View, radiusPx: Float, elevationPx: Float) {
        view.elevation = elevationPx
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            view.outlineAmbientShadowColor = Color.argb(66, 0x6E, 0x93, 0xB2)
            view.outlineSpotShadowColor = Color.argb(84, 0x53, 0x7C, 0x9C)
        }
    }

    /** Soft tactile response: the surface sinks towards the finger and springs back. */
    fun pressable(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.972f).scaleY(0.972f).setDuration(85).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }
}

/**
 * A frosted slab. Drawn in local coordinates so it can be reused at any size:
 * 1) vertical body wash, 2) a specular bloom in the top-right, 3) a bottom shade for thickness,
 * 4) a catch-light along the top edge, 5) a holographic rim around the whole shape.
 */
class GlassSurface(
    private val radiusPx: Float,
    private val strokePx: Float,
    private val strength: Float,
    private val holographic: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var body: Shader? = null
    private var spec: Shader? = null
    private var shade: Shader? = null
    private var rim: Shader? = null
    private var w = 0f
    private var h = 0f
    private var built = false

    override fun onBoundsChange(bounds: Rect) {
        w = bounds.width().toFloat()
        h = bounds.height().toFloat()
        built = false
        if (w > 0f && h > 0f) build()
    }

    private fun build() {
        built = true
        path.reset()
        path.addRoundRect(0f, 0f, w, h, radiusPx, radiusPx, Path.Direction.CW)
        body = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb((238 * strength).toInt(), 255, 255, 255),
                Color.argb((198 * strength).toInt(), 248, 252, 255),
                Color.argb((170 * strength).toInt(), 222, 236, 248)
            ),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        spec = RadialGradient(
            w * 0.85f, -h * 0.10f, w * 0.90f,
            Color.argb((92 * strength).toInt(), 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        shade = LinearGradient(
            0f, h * 0.55f, 0f, h,
            intArrayOf(Color.TRANSPARENT, Color.argb((26 * strength).toInt(), 22, 46, 70)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        rim = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.argb(230, 255, 255, 255),
                Color.argb(155, 173, 232, 255),
                Color.argb(125, 232, 201, 255),
                Color.argb(110, 255, 214, 184)
            ),
            floatArrayOf(0f, 0.38f, 0.70f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (w <= 0f || h <= 0f) return
        if (!built) build()
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.clipPath(path)

        paint.style = Paint.Style.FILL
        paint.shader = body
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = spec
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = shade
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokePx
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb((210 * strength).toInt(), 255, 255, 255)
        canvas.drawLine(radiusPx * 0.55f, strokePx * 0.8f, w - radiusPx * 0.55f, strokePx * 0.8f, paint)
        canvas.restoreToCount(save)

        if (holographic) {
            val rimSave = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx
            paint.shader = rim
            val i = strokePx / 2f
            canvas.drawRoundRect(i, i, w - i, h - i, radiusPx, radiusPx, paint)
            paint.shader = null
            canvas.restoreToCount(rimSave)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Page backdrop: a cold silver sky with three soft aurora pools (cyan, violet, peach) and a very
 * light film grain. Drawn once per size change; costs nothing while the user scrolls.
 */
class AuroraBackdrop(private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sky: Shader? = null
    private var grain: BitmapShader? = null
    private var grainMatrix = Matrix()
    private var w = 0f
    private var h = 0f

    init {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val random = Random(11)
        for (i in pixels.indices) {
            val tone = if (random.nextInt(2) == 0) 255 else 0
            pixels[i] = Color.argb(random.nextInt(14), tone, tone, tone)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        grain = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onBoundsChange(bounds: Rect) {
        w = bounds.width().toFloat()
        h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        sky = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFFF7FBFF.toInt(), 0xFFEAF3FB.toInt(), 0xFFDCEAF6.toInt(), 0xFFD0E1F0.toInt()),
            floatArrayOf(0f, 0.42f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun pool(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        val tinted = (color and 0x00FFFFFF) or (alpha shl 24)
        paint.shader = RadialGradient(cx, cy, radius, tinted, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
    }

    override fun draw(canvas: Canvas) {
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())

        paint.style = Paint.Style.FILL
        paint.shader = sky
        canvas.drawRect(0f, 0f, w, h, paint)

        pool(canvas, w * 0.10f, h * 0.05f, w * 0.95f, 0x7CD8FF, 120)
        pool(canvas, w * 0.98f, h * 0.30f, w * 0.85f, 0xB9A7FF, 96)
        pool(canvas, w * 0.18f, h * 0.72f, w * 0.95f, 0xFFCFB4, 78)
        pool(canvas, w * 0.72f, h * 0.95f, w * 0.80f, 0x9FE8FF, 70)

        val grainSave = canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        grain?.let {
            grainMatrix.setScale(density.coerceAtLeast(1f), density.coerceAtLeast(1f))
            it.setLocalMatrix(grainMatrix)
            paint.shader = it
            paint.alpha = 30
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.alpha = 255
        }
        canvas.restoreToCount(grainSave)
        paint.shader = null
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** Chrome wordmark: a polished metal sweep across the letters, the way the dock plate is engraved. */
class ChromeText(context: Context, label: String, size: Float, tracking: Float = 0.015f) : AppCompatTextView(context) {
    init {
        text = label
        textSize = size
        letterSpacing = tracking
        typeface = Dock.bold(context)
        includeFontPadding = false
        setTextColor(Dock.ink)
    }

    override fun onDraw(canvas: Canvas) {
        val width = paint.measureText(text.toString()).coerceAtLeast(1f)
        paint.shader = LinearGradient(
            0f, 0f, width, 0f,
            intArrayOf(
                0xFF6E7A86.toInt(), 0xFFFDFEFF.toInt(), 0xFFAEBBC8.toInt(),
                0xFF56636F.toInt(), 0xFFE7F0F8.toInt()
            ),
            floatArrayOf(0f, 0.24f, 0.50f, 0.74f, 1f), Shader.TileMode.CLAMP
        )
        super.onDraw(canvas)
        paint.shader = null
    }
}

/** Ice progress bar drawn by hand: glass track, gradient fill and a glossy top edge. */
class GlassProgress(context: Context) : ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = context.resources.displayMetrics.density

    init {
        max = 100
        background = null
        progressDrawable = null
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat().coerceAtLeast(1f)
        val radius = height / 2f

        track.color = Color.argb(165, 255, 255, 255)
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, track)

        edge.style = Paint.Style.STROKE
        edge.strokeWidth = density
        edge.color = Color.argb(90, 120, 152, 178)
        canvas.drawRoundRect(density / 2f, density / 2f, width - density / 2f, height - density / 2f, radius, radius, edge)

        val fraction = if (max > 0) progress.toFloat() / max else 0f
        val filled = (width * fraction).coerceAtLeast(if (fraction > 0f) height else 0f).coerceAtMost(width)
        if (filled > 0f) {
            fill.shader = LinearGradient(
                0f, 0f, width, 0f, Dock.iceGrad,
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(0f, 0f, filled, height, radius, radius, fill)
            shade.shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(Color.argb(150, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(0f, 0f, filled, height * 0.55f, radius, radius, shade)
            fill.shader = null
            shade.shader = null
        }
    }
}
