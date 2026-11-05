package org.streamdock.app.dl

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.streamdock.app.Diagnostics

/** System-wide network connectivity observer.
 *  - Immediately detects when device goes offline or comes back online.
 *  - Wakes up paused/interrupted download retry loops instantly without waiting for backoff timers.
 *  - Automatically resumes queued or interrupted in-app transfers when internet is restored. */
object NetworkTracker {
    @Volatile var isOnline: Boolean = true
        private set

    private val _reconnectEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnectEvents = _reconnectEvents.asSharedFlow()

    @Volatile private var registered = false

    fun init(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val app = context.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            isOnline = checkOnline(cm)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val wasOffline = !isOnline
                        isOnline = true
                        Diagnostics.stage("NetworkTracker: internet available (wasOffline=$wasOffline)")
                        _reconnectEvents.tryEmit(Unit)
                        DownloadCenter.onNetworkRestored()
                    }
                    override fun onLost(network: Network) {
                        isOnline = checkOnline(cm)
                        Diagnostics.stage("NetworkTracker: connection dropped; isOnline=$isOnline")
                    }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (hasInternet && !isOnline) {
                            isOnline = true
                            _reconnectEvents.tryEmit(Unit)
                            DownloadCenter.onNetworkRestored()
                        }
                    }
                }
                try {
                    cm.registerDefaultNetworkCallback(callback)
                    registered = true
                } catch (e: Exception) {
                    Diagnostics.error("NetworkTracker default callback registration failed", e)
                }
            } else {
                registered = true
            }
        }
    }

    fun checkOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return checkOnline(cm)
    }

    private fun checkOnline(cm: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(active) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /** Suspends for [waitMs] or returns immediately if the device reconnects to the internet. */
    suspend fun awaitBackoffOrReconnect(waitMs: Long) {
        if (isOnline) {
            kotlinx.coroutines.delay(waitMs)
        } else {
            withTimeoutOrNull(waitMs) {
                reconnectEvents.collect { return@collect }
            }
        }
    }
}
