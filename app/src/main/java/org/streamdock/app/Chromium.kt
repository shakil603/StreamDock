package org.streamdock.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/** Real Chromium, directly: Chrome (or another Custom Tabs provider) is launched with the page,
 *  so the user gets the full Chromium engine, its complete feature set and their own browser session.
 *  Nothing is passed along — no cookies, no headers, no JavaScript bridge. */
object Chromium {
    /** Package name of the installed Chromium-based Custom Tabs provider, or null when there is none. */
    fun provider(context: Context): String? =
        runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun installed(context: Context): Boolean = provider(context) != null

    /** Friendly provider label for the settings dialog. */
    fun label(context: Context, pkg: String): String = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        val name = context.packageManager.getApplicationLabel(info.applicationInfo ?: info.applicationInfo!!).toString()
        "$name ${info.versionName ?: ""}".trim()
    }.getOrDefault(pkg)

    /** Opens the page in real Chromium. Returns false when no provider is installed. */
    fun open(context: Context, url: String): Boolean {
        val pkg = provider(context) ?: return false
        runCatching {
            val builder = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .setToolbarColor(CinemaUi.surface)
                .setSecondaryToolbarColor(CinemaUi.background)
                .setCloseButtonIcon(android.graphics.BitmapFactory.decodeResource(context.resources, android.R.drawable.ic_menu_close_clear_cancel))
            runCatching { builder.setColorScheme(CustomTabsIntent.COLOR_SCHEME_LIGHT) }
            val intent = builder.build()
            intent.intent.setPackage(pkg)
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent.intent)
        }.onFailure { return false }
        return true
    }

    /** Store page for installing/updating Chrome. */
    fun storeIntent(context: Context, pkg: String = "com.android.chrome"): Intent {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return market.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
