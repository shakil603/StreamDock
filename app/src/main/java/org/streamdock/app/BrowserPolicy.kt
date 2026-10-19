package org.streamdock.app

import java.net.URI
import java.net.URLEncoder

/** URL/search decisions have no WebView dependency and can be tested on the JVM. */
object BrowserPolicy {
    enum class Search(val label: String, val home: String, val query: String) {
        GOOGLE("Google", "https://www.google.com/", "https://www.google.com/search?q="),
        DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/", "https://duckduckgo.com/?q="),
        BING("Bing", "https://www.bing.com/", "https://www.bing.com/search?q=");
        override fun toString() = label
    }
    private val teraDomains = setOf("terabox.com", "terabox.app", "teraboxapp.com", "1024terabox.com", "1024tera.com",
        "teraboxlink.com", "teraboxshare.com", "terasharelink.com", "terasharefile.com", "terafileshare.com",
        "4funbox.com", "mirrobox.com", "nephobox.com", "dubox.com", "freeterabox.com")
    val helperDomains = setOf("terasaver.com", "teraplayer.in", "teraboxdl.site")
    fun host(raw: String): String = runCatching { URI(raw).host?.lowercase().orEmpty() }.getOrDefault("")
    private fun matches(host: String, domains: Set<String>) = domains.any { host == it || host.endsWith(".$it") }
    fun browserOnly(raw: String) = matches(host(raw), teraDomains + helperDomains)
    fun helper(raw: String) = matches(host(raw), helperDomains)
    fun https(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Enter a valid HTTPS address.") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Only HTTPS websites are supported. Local files, embedded credentials and app links are blocked."
        }
        return "https" + value.substring(value.indexOf(':'))
    }
    fun input(raw: String, search: Search): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "Enter a website address or search query." }
        if (value.startsWith("https://", true)) return https(value)
        if (value.startsWith("http://", true)) return https("https://" + value.substring(7))
        require(!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) { "This address scheme is not supported." }
        if (!value.any { it.isWhitespace() } && (value.substringBefore('/').contains('.') || value.startsWith("localhost"))) {
            return https("https://$value")
        }
        return search.query + URLEncoder.encode(value, "UTF-8")
    }
    fun origin(raw: String): String? = runCatching {
        val uri = URI(https(raw)); URI("https",null,uri.host,uri.port,"/",null,null).toString()
    }.getOrNull()
    fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[^\\p{L}\\p{M}\\p{N} ._-]"), "_").trim(' ','.').ifBlank { "download" }
        val suffix = cleaned.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) && cleaned.contains('.') }?.let { ".$it" }.orEmpty()
        val stem = if (suffix.isEmpty()) cleaned else cleaned.dropLast(suffix.length)
        val result = StringBuilder(); var used = 0; var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i); val part = String(Character.toChars(cp)); val bytes = part.toByteArray(Charsets.UTF_8).size
            if (used + bytes > 180 - suffix.length) break
            result.append(part); used += bytes; i += Character.charCount(cp)
        }
        return result.toString().ifBlank { "download" } + suffix
    }
}
