package org.streamdock.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Real website icons, the way the sites themselves publish them.
 *
 * Order of trust: icons bundled with the app (the known sites) -> the icon the site
 * hosts on its own origin (/favicon.ico, /apple-touch-icon.png) -> the DuckDuckGo
 * icon mirror -> the Google favicon service. Every fetch is cached on disk and in
 * memory, so a tile or tab shows the real mark instantly the next time.
 */
object SiteIcons {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LinkedHashMap<String, Bitmap>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 48
    }

    /** Rounded-corner drawable so every favicon reads as the same physical object. */
    class Rounded(private val bitmap: Bitmap, private val radiusPx: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        private val path = Path()
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        override fun getIntrinsicWidth() = bitmap.width
        override fun getIntrinsicHeight() = bitmap.height
        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            path.reset()
            path.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                bounds.bottom.toFloat(), radiusPx, radiusPx, Path.Direction.CW)
        }
        override fun draw(canvas: Canvas) { canvas.drawPath(path, paint) }
    }

    private fun key(host: String) = MessageDigest.getInstance("SHA-1")
        .digest(host.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun normalize(source: Bitmap, size: Int = 128): Bitmap {
        val w = source.width; val h = source.height; val s = minOf(w, h)
        val sq = if (w == h) source else Bitmap.createBitmap(source, (w - s) / 2, (h - s) / 2, s, s)
        return if (sq.width == size) sq else Bitmap.createScaledBitmap(sq, size, size, true)
    }

    private fun bundledIcon(context: Context, host: String): Bitmap? = runCatching {
        val h = host.lowercase().removePrefix("www.")
        val candidates = listOf(h, h.removePrefix("m."), "terabox.com".takeIf { h.endsWith("terabox.com") || h == "1024tera.com" }, "terabox.com".takeIf { h.endsWith("teraboxapp.com") })
            .filterNotNull().distinct()
        for (name in candidates) {
            context.assets.open("sites/$name.png").use { input ->
                return@runCatching BitmapFactory.decodeStream(input)
            }
        }
        null
    }.getOrNull()

    private fun fetchRemote(host: String): Bitmap? {
        val origins = listOf("https://$host/", "https://www.$host/")
        val paths = listOf("/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png")
        val queue = ArrayList<String>()
        for (origin in origins) for (path in paths) queue += origin.trimEnd('/') + path
        queue += "https://icons.duckduckgo.com/ip3/$host.ico"
        queue += "https://www.google.com/s2/favicons?domain=$host&sz=128"
        for (url in queue) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000; connection.readTimeout = 8000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                if (connection.responseCode in 200..299) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null && decoded.width >= 16) return normalize(decoded)
                }
                connection.disconnect()
            }
        }
        return null
    }

    private fun fromDisk(context: Context, host: String): Bitmap? = runCatching {
        val file = File(context.cacheDir, "favicons/${key(host)}.png")
        if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }.getOrNull()

    private fun toDisk(context: Context, host: String, bitmap: Bitmap) = runCatching {
        val dir = File(context.cacheDir, "favicons").apply { mkdirs() }
        File(dir, "${key(host)}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** True when [host] has a bundled brand icon (no network needed). */
    fun bundled(context: Context, host: String): Boolean = bundledIcon(context, host) != null

    /** Load the real icon of [host] into [view], rounded to [radiusDp]. Safe from any thread. */
    fun load(context: Context, host: String, view: ImageView, radiusDp: Float = 9f, fallback: Drawable? = null) {
        val h = host.lowercase().removePrefix("www.").ifBlank { return }
        view.tag = h
        val radiusPx = Dock.px(view.context, radiusDp)
        fun show(bitmap: Bitmap) {
            if (view.tag == h) view.setImageDrawable(Rounded(bitmap, radiusPx))
        }
        synchronized(memory) { memory[h] }?.let { show(it); return }
        fallback?.let { view.setImageDrawable(it) }
        executor.execute {
            val disk = fromDisk(view.context, h)
            val found = disk ?: bundledIcon(view.context, h) ?: fetchRemote(h)?.also { toDisk(view.context, h, it) }
            if (found != null) {
                synchronized(memory) { memory[h] = found }
                main.post { show(found) }
            }
        }
    }

    /** Synchronous best-effort icon for lists that already run off the main thread. */
    fun bitmapFor(context: Context, host: String): Bitmap? {
        val h = host.lowercase().removePrefix("www.")
        synchronized(memory) { memory[h] }?.let { return it }
        val found = fromDisk(context, h) ?: bundledIcon(context, h)
        if (found != null) { synchronized(memory) { memory[h] = found }; return found }
        return null
    }
}
