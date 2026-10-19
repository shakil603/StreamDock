package org.streamdock.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/**
 * Shared UI kit. 1.6.0 replaces the old flat "cinema" surfaces with the Frozen Glass language in
 * [Dock]: frosted slabs, holographic rims, ice-blue accent and an aurora page backdrop.
 *
 * The public surface below is intentionally unchanged (background / surface / red / muted / paper /
 * hairline, text / display / kicker / titlebar / button / card / nav / shape / backdrop). Every
 * screen is written against these names, so the whole app moves to the new language at once and no
 * download, resolver or TeraBox behaviour is touched.
 */
object CinemaUi {
    val background = Color.rgb(0xE4, 0xEF, 0xF9)
    val surface = Color.argb(214, 255, 255, 255)
    /** Accent. Kept under the historical name so every existing screen picks it up. */
    val red = Dock.accent
    val muted = Dock.inkSoft
    val paper = Dock.ink
    val hairline = Dock.line

    fun dp(c: Context, n: Int) = Dock.dp(c, n)

    fun text(c: Context, label: String, size: Float = 14f, color: Int = paper) = TextView(c).apply {
        text = label
        textSize = size
        setTextColor(color)
        typeface = if (size >= 15f) Dock.medium(c) else Dock.regular(c)
        includeFontPadding = false
        setLineSpacing(0f, 1.24f)
        setPadding(0, dp(c, 3), 0, dp(c, 3))
    }

    /** Display type: tight, confident, no all-caps shouting. */
    fun display(c: Context, label: String, size: Float = 24f, color: Int = paper): TextView =
        text(c, label, size, color).apply {
            typeface = if (size >= 19f) Dock.bold(c) else Dock.medium(c)
            letterSpacing = if (size >= 19f) -0.022f else 0f
            setLineSpacing(0f, 1.02f)
        }

    /** Section label: an ice dot plus tracked small caps, sitting on the glass like a laser etch. */
    fun kicker(c: Context, label: String, color: Int = red): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(c, 2), 0, dp(c, 2))
        addView(View(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(c, 1), Color.argb(120, 255, 255, 255))
            }
            elevation = dp(c, 2).toFloat()
        }, LinearLayout.LayoutParams(dp(c, 6), dp(c, 6)).apply { rightMargin = dp(c, 8) })
        addView(text(c, label.uppercase(), 9.5f, color).apply {
            letterSpacing = .17f
            typeface = Dock.bold(c)
            setPadding(0, 0, 0, 0)
        })
    }

    /** Screen title block: etched label, chrome-weight title, quiet meta line and a light divider. */
    fun titlebar(c: Context, kicker: String, title: String, meta: String = ""): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(c, 2), 0, dp(c, 8))
        if (kicker.isNotBlank()) addView(kicker(c, kicker))
        addView(display(c, title, 25f))
        if (meta.isNotBlank()) addView(text(c, meta, 11.5f, muted))
        addView(View(c).apply { background = divider(c) }, LinearLayout.LayoutParams(-1, dp(c, 1)).apply { topMargin = dp(c, 12) })
    }

    /**
     * Primary action: an ink-black key that looks machined into the glass.
     * Secondary action: a clear glass pill that still reads as a real object.
     */
    fun button(c: Context, label: String, secondary: Boolean = false, action: () -> Unit) = MaterialButton(c).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = if (secondary) Dock.medium(c) else Dock.bold(c)
        letterSpacing = -0.005f
        cornerRadius = dp(c, 25)
        minHeight = dp(c, 48)
        insetTop = 0
        insetBottom = 0
        stateListAnimator = null
        if (secondary) {
            backgroundTintList = ColorStateList.valueOf(Color.argb(200, 255, 255, 255))
            strokeWidth = dp(c, 1)
            strokeColor = ColorStateList.valueOf(Color.argb(130, 176, 208, 234))
            setTextColor(paper)
            elevation = dp(c, 2).toFloat()
        } else {
            backgroundTintList = ColorStateList.valueOf(Dock.ink)
            strokeWidth = 0
            setTextColor(Color.WHITE)
            elevation = dp(c, 8).toFloat()
        }
        setOnClickListener { action() }
    }

    /** Frosted slab with real elevation and a coloured shadow. */
    fun card(c: Context, parent: LinearLayout): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 17), dp(c, 15), dp(c, 17), dp(c, 15))
        background = Dock.glass(c, 22f)
        Dock.depth(this, Dock.px(c, 22f), Dock.px(c, 7f))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 14) })
    }

    /** Compact glass tile used by Quick Access and small pickers. */
    fun tile(c: Context, radiusDp: Float = 18f, strength: Float = 1f, lift: Int = 5): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Dock.glass(c, radiusDp, strength)
            Dock.depth(this, Dock.px(c, radiusDp), Dock.px(c, lift.toFloat()))
            isClickable = true
            isFocusable = true
            Dock.pressable(this)
        }

    /**
     * Bottom navigation. Structure, height, icon order, labels and click routing are unchanged from
     * the approved version — only the surface now speaks the glass language.
     */
    fun nav(c: Context, selected: Int, action: (Int) -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(238, 255, 255, 255), Color.argb(214, 234, 244, 252))).apply {
            cornerRadius = Dock.px(c, 26f)
            setStroke(dp(c, 1), Color.argb(150, 255, 255, 255))
        }
        val icons = listOf("home", "box", "globe", "download", "tools")
        listOf("Home", "Tera", "Browser", "Downloads", "Tools").forEachIndexed { i, label ->
            val tint = if (i == selected) Dock.accent else Dock.inkSoft
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (i == selected) Dock.glass(c, 18f, 1f) else null
                isClickable = true
                isFocusable = true
                contentDescription = label
                setPadding(dp(c, 1), dp(c, 5), dp(c, 1), dp(c, 4))
                addView(ImageView(c).apply {
                    setImageDrawable(DockIcon(icons[i], tint))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(c, 19), dp(c, 19)))
                addView(TextView(c).apply {
                    text = label
                    setTextColor(tint)
                    typeface = if (i == selected) Dock.bold(c) else Dock.medium(c)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(c, 1), 0, 0)
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this, 7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { action(i) }
            }
            addView(cell, LinearLayout.LayoutParams(0, dp(c, 52), 1f).apply { setMargins(dp(c, 3), 0, dp(c, 3), 0) })
        }
        Dock.depth(this, Dock.px(c, 26f), Dock.px(c, 6f))
    }

    /** Frosted pill used for the search field and inline controls. */
    fun shape(color: Int = surface, radius: Float = 12f, c: Context): Drawable =
        Dock.glass(c, radius, 0.94f)

    fun glassPane(c: Context, radiusDp: Float = 18f, strength: Float = 0.95f): Drawable =
        Dock.glass(c, radiusDp, strength)

    /** Aurora page backdrop. */
    fun backdrop(c: Context): Drawable = AuroraBackdrop(c.resources.displayMetrics.density)

    /** Light hairline that fades out at both ends. */
    fun divider(c: Context): Drawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.TRANSPARENT, Color.argb(120, 150, 178, 202), Color.TRANSPARENT))

    fun progress(c: Context): ProgressBar = GlassProgress(c)

    /** Dresses a Material text field in glass, with the ice accent on focus. */
    fun field(c: Context, layout: TextInputLayout) {
        layout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        runCatching { layout.setBoxCornerRadii(Dock.px(c, 16f), Dock.px(c, 16f), Dock.px(c, 16f), Dock.px(c, 16f)) }
        layout.boxBackgroundColor = Color.argb(205, 255, 255, 255)
        layout.setBoxStrokeColor(Dock.accent)
        layout.setBoxStrokeWidth(dp(c, 1))
        layout.hintTextColor = ColorStateList.valueOf(Dock.inkSoft)
        layout.defaultHintTextColor = ColorStateList.valueOf(Dock.inkSoft)
    }

    /** Spinner styled as a small glass chip. */
    fun spinnerChrome(c: Context, spinner: Spinner) {
        spinner.background = Dock.glass(c, 14f, 0.92f)
        spinner.setPadding(dp(c, 12), 0, dp(c, 6), 0)
        Dock.depth(spinner, Dock.px(c, 14f), Dock.px(c, 2f))
    }
}

class DockIcon(name: String, private val color: Int = Color.WHITE) : Drawable() {
    private val path = androidx.core.graphics.PathParser.createPathFromPathData(when (name) {
        "home" -> "M3,10 L12,3 L21,10 M5,9 L5,21 L10,21 L10,15 L14,15 L14,21 L19,21 L19,9"
        "download" -> "M12,3 L12,16 M7,11 L12,16 L17,11 M4,17 L4,21 L20,21 L20,17"
        "video" -> "M3,6 L21,6 L21,18 L3,18 Z M10,9.5 L15,12 L10,14.5 Z"
        "box" -> "M12,3 L20,7.5 L20,16.5 L12,21 L4,16.5 L4,7.5 Z M4,7.5 L12,12 L20,7.5 M12,12 L12,21"
        "tools" -> "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18 M8,3 L8,9 M16,9 L16,15 M9,15 L9,21"
        "search" -> "M16,16 L21,21 M10,3 A7,7 0,1 0,10,17 A7,7 0,1 0,10,3"
        "back" -> "M15,5 L8,12 L15,19"
        "forward" -> "M9,5 L16,12 L9,19"
        "reload" -> "M20,4 L20,10 L14,10 M19,9 A8,8 0,1 0,20,16"
        "external" -> "M14,3 L21,3 L21,10 M21,3 L10,14 M10,5 L4,5 L4,21 L19,21 L19,14"
        "close" -> "M5,5 L19,19 M19,5 L5,19"
        "chrome" -> "M12,2 A10,10 0,1 0,12,22 A10,10 0,1 0,12,2 M12,8 A4,4 0,1 0,12,16 A4,4 0,1 0,12,8 M12,2 L12,8 M4.3,17.2 L9.8,13.9 M19.7,17.2 L14.2,13.9"
        "play" -> "M8,5 L19,12 L8,19 Z"
        "pause" -> "M9,5 L9,19 M15,5 L15,19"
        "prev" -> "M18,6 L8,12 L18,18 Z M6,6 L6,18"
        "next" -> "M6,6 L16,12 L6,18 Z M18,6 L18,18"
        "rwd10" -> "M11,4 L6,8 L12,8 A7,7 0,1 1,5,13 M9,13 L7,16 L10,17"
        "fwd10" -> "M13,4 L18,8 L12,8 A7,7 0,1 0,19,13 M15,13 L17,16 L14,17"
        "repeat" -> "M6,8 L18,8 L15,5 M18,8 L18,16 L6,16 L9,19"
        "repeat1" -> "M6,8 L18,8 L15,5 M18,8 L18,16 L6,16 L9,19 M11,10 L12,10 L12,15"
        "headphones" -> "M4,14 L4,12 A8,8 0,0 1,20,12 L20,14 M4,14 L7,14 L7,19 L5,19 A1,1 0,0 1,4,18 Z M20,14 L17,14 L17,19 L19,19 A1,1 0,0 0,20,18 Z"
        "videoOn" -> "M3,7 L14,7 L14,17 L3,17 Z M14,10 L21,7 L21,17 L14,14"
        "lock" -> "M6,11 L18,11 L18,20 L6,20 Z M8,11 L8,8 A4,4 0,0 1,16,8 L16,11"
        "unlock" -> "M6,11 L18,11 L18,20 L6,20 Z M8,11 L8,8 A4,4 0,0 1,16,8"
        "rotate" -> "M4,10 A8,8 0,1 1,6,16 M4,10 L4,5 M4,10 L9,10 M20,14 A8,8 0,1 1,18,8"
        "speed" -> "M12,4 A8,8 0,1 1,12,20 A8,8 0,1 1,12,4 M12,12 L16,8 M7,17 L17,17"
        "tabs" -> "M4,8 L14,8 L14,20 L4,20 Z M8,8 L8,5 L20,5 L20,17 L14,17"
        "star" -> "M12,3 L14.6,9 L21,9.5 L16.2,13.7 L17.8,20 L12,16.4 L6.2,20 L7.8,13.7 L3,9.5 L9.4,9 Z"
        "history" -> "M4,12 A8,8 0,1 0,7,6 M4,12 L4,7 M4,12 L9,12 M12,8 L12,12 L15,14"
        "find" -> "M16,16 L21,21 M10,3 A7,7 0,1 0,10,17 A7,7 0,1 0,10,3 M8,10 L12,10"
        "desktop" -> "M3,5 L21,5 L21,16 L3,16 Z M9,20 L15,20 M12,16 L12,20"
        "share" -> "M8,12 L16,7 M8,12 L16,17 M18,5 A2.5,2.5 0,1 0,18,10 A2.5,2.5 0,1 0,18,5 M6,10 A2.5,2.5 0,1 0,6,15 A2.5,2.5 0,1 0,6,10 M18,14 A2.5,2.5 0,1 0,18,19 A2.5,2.5 0,1 0,18,14"
        "plus" -> "M12,5 L12,19 M5,12 L19,12"
        "minus" -> "M5,12 L19,12"
        "menu" -> "M4,7 L20,7 M4,12 L20,12 M4,17 L20,17"
        else -> "M12,2 A10,10 0,1 0,12,22 A10,10 0,1 0,12,2 M2,12 L22,12 M12,2 C6,7 6,17 12,22 M12,2 C18,7 18,17 12,22"
    })!!
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@DockIcon.color
        style = Paint.Style.STROKE
        strokeWidth = 1.75f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 24
    override fun getIntrinsicHeight() = 24
}

/** Keeps the whole interface inside the phone screen: the layout is already sized in sp, so a system
 *  font scale above 100% only made titles and buttons oversized and wrapped the bottom bar. */
object FontFit {
    fun context(base: Context): Context {
        val config = android.content.res.Configuration(base.resources.configuration)
        if (config.fontScale <= 1.0f) return base
        config.fontScale = 1.0f
        return base.createConfigurationContext(config)
    }
}
