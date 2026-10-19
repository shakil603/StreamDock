package org.streamdock.app.terabox

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.streamdock.app.CinemaUi
import org.streamdock.app.Dock

/**
 * The in-app TeraBox check sheet.
 *
 * TeraBox refuses to mint a direct file link until its own anti-bot check is
 * completed. This sheet runs that check INSIDE StreamDock — a compact embedded
 * page, not the browser app and never an external one. When the check is done
 * the sheet hands the minted TeraBox-host cookies to the native engine and the
 * file plays in StreamDock's own player. No account login, no password and no
 * imported cookie is ever requested from the user.
 */
class TeraCheckView(
    private val activity: AppCompatActivity,
    private val link: String,
    private val onReady: () -> Unit
) : LinearLayout(activity) {

    private val statusLine: TextView
    private val continueButton: TextView
    private val progress: ProgressBar
    private var ready = false

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(10))
        background = CinemaUi.glassPane(activity, 18f, 1f)

        statusLine = CinemaUi.text(
            activity,
            "Complete TeraBox's own check on this page (slide or tap). No account login is requested. Then press Continue — the file plays inside StreamDock.",
            11f, CinemaUi.muted
        )
        addView(statusLine, LayoutParams(-1, -2))

        progress = ProgressBar(activity).apply { isIndeterminate = true }
        addView(progress, LayoutParams(-1, dp(3)).apply { topMargin = dp(6) })

        val web = WebView(activity)
        configure(web)
        addView(web, LayoutParams(-1, dp(300)).apply { topMargin = dp(6) })

        continueButton = CinemaUi.button(activity, "Continue — play inside StreamDock", true) {
            TeraCookies.captureAfterCheck(activity)
            onReady()
        }
        addView(continueButton, LayoutParams(-1, -2).apply { topMargin = dp(8) })

        web.loadUrl(link)
        pollCookies()
    }

    private fun dp(n: Int) = CinemaUi.dp(activity, n)

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true            // the check widget itself needs JS; no bridge is injected
            domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true; displayZoomControls = false
            userAgentString = TeraEngine.AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webChromeClient = object : WebChromeClient() {}
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                val ok = listOf("terabox", "1024tera", "dubox", "baidu").any { host.contains(it) }
                if (!ok) {
                    statusLine.text = "That page is outside TeraBox. Stay on the TeraBox check to finish."
                    return true
                }
                return false
            }
            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                val cookies = TeraCookies.headerFor("https://www.1024terabox.com/", activity)
                if (cookies.isNotBlank()) markReady("Check completed — TeraBox session ready.")
                else statusLine.text = "Finish the check above, then press Continue. If the page shows a slider or tap test, complete it here."
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) statusLine.text = "The TeraBox page could not load. Check your connection and try again."
            }
        }
    }

    /** Watches for the cookies TeraBox's own check sets on its own hosts. */
    private fun pollCookies() {
        postDelayed(object : Runnable {
            override fun run() {
                if (!ready && TeraCookies.headerFor("https://www.1024terabox.com/", activity).isNotBlank()) {
                    markReady("Check completed — TeraBox session ready. Press Continue.")
                }
                if (!ready) postDelayed(this, 1200)
            }
        }, 1200)
    }

    private fun markReady(message: String) {
        ready = true
        statusLine.text = message
        continueButton.alpha = 1f
    }
}
