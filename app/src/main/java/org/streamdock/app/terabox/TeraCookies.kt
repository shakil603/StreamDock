package org.streamdock.app.terabox

import android.content.Context
import android.webkit.CookieManager

/**
 * TeraBox verification state, scoped with hard walls.
 *
 * When the in-app TeraBox check sheet completes TeraBox's own anti-bot step,
 * TeraBox sets cookies on its own hosts inside this app's cookie jar. Those —
 * and ONLY those — are kept here so the native share engine can mint the file
 * link the page would have used.
 *
 * Hard rules, enforced below and asserted by tools/verify_browser_release.py:
 *  - cookies are read only for TeraBox-family hosts (the allowlist);
 *  - nothing is ever read for any other site;
 *  - no account password, no imported/exported browser session, no third-party
 *    downloader website is involved.
 */
object TeraCookies {

    @Volatile private var app: Context? = null
    /** Called once when the TeraBox section is created. */
    fun attach(context: Context) { app = context.applicationContext }

    /** The only hosts whose cookies this app may ever read. */
    private val HOSTS = listOf(
        "terabox.com", "www.terabox.com", "1024terabox.com", "www.1024terabox.com",
        "teraboxapp.com", "www.teraboxapp.com", "terabox.app", "www.terabox.app",
        "1024tera.com", "www.1024tera.com"
    )

    private fun permitted(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return HOSTS.any { h == it.removePrefix("www.") || h.endsWith("." + it.removePrefix("www.")) }
    }

    /** Cookie header for TeraBox engine requests to [origin], or "" when none is known. */
    fun headerFor(origin: String, context: Context? = null): String {
        val host = runCatching { java.net.URI(origin).host.orEmpty() }.getOrDefault("")
        if (!permitted(host)) return ""
        val live = runCatching { CookieManager.getInstance().getCookie("https://$host/") }.getOrNull()
        val ctx = context ?: app
        val stored = ctx?.getSharedPreferences("terabox-check", Context.MODE_PRIVATE)
            ?.getString("cookie", "").orEmpty()
        val merged = LinkedHashMap<String, String>()
        listOf(stored, live.orEmpty()).forEach { raw ->
            raw.split(";").forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) merged[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Capture the verification cookies TeraBox's own check minted on its own
     * hosts, so later Play/Save calls need no second check. Scoped: only the
     * allowlisted TeraBox hosts are ever read.
     */
    fun captureAfterCheck(context: Context) {
        val prefs = context.getSharedPreferences("terabox-check", Context.MODE_PRIVATE)
        val merged = LinkedHashMap<String, String>()
        runCatching {
            (prefs.getString("cookie", "").orEmpty()).split(";").forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) merged[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        for (host in HOSTS) {
            if (!permitted(host)) continue
            val raw = runCatching { CookieManager.getInstance().getCookie("https://$host/") }.getOrNull() ?: continue
            raw.split(";").forEach { part ->
                val eq = part.indexOf('=')
                if (eq > 0) merged[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        prefs.edit().putString("cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" }).apply()
    }

    /** True once the check minted anything TeraBox recognises. */
    fun hasCheckState(context: Context): Boolean = headerFor("https://www.1024terabox.com/", context).isNotBlank()
}
