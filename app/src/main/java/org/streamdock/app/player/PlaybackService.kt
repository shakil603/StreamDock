@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.streamdock.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.streamdock.app.MainActivity

/**
 * The one playback engine behind both video and audio-only listening.
 *
 * It owns the ExoPlayer and a MediaSession, so playback continues with a media
 * notification (play/pause/next/previous/stop) when the user leaves the screen or
 * turns the screen off — exactly the NewPipe background-playback contract.
 * Separate video/audio streams are merged here, the same way the old inline
 * preview did, so every analyzed stream keeps playing.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /** Set by the player screen right before the service is started. */
        @Volatile var pending: List<Playable>? = null
        const val ACTION_STOP = "org.streamdock.app.player.STOP"
    }

    private var mediaSession: MediaSession? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel("media") == null) {
                manager.createNotificationChannel(
                    NotificationChannel("media", "Playback", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun placeholder(): Notification =
        NotificationCompat.Builder(this, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("StreamDock player")
            .setContentText("Preparing playback…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Android requires a foreground service to call startForeground within seconds of
        // being started; media3 upgrades this placeholder with its own media notification.
        ensureChannel()
        ServiceCompat.startForeground(
            this, 1001, placeholder(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // handle audio focus
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep streaming while the screen is off
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun sourceOf(item: Playable): androidx.media3.exoplayer.source.MediaSource {
        val headers = if (item.headers.isEmpty()) mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        ) else item.headers
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title).setArtist(item.subtitle)
            .setIsBrowsable(false).setIsPlayable(true).build()
        val videoItem = MediaItem.Builder().setUri(item.videoUrl).setMediaId(item.title)
            .setMediaMetadata(metadata).build()
        val video = DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(20000).setReadTimeoutMs(20000)
        ).createMediaSource(videoItem)
        val audio = item.audioUrl?.takeIf { it.isNotBlank() && it != item.videoUrl } ?: return video
        val audioSource = DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(if (item.audioHeaders.isEmpty()) headers else item.audioHeaders)
                .setConnectTimeoutMs(20000).setReadTimeoutMs(20000)
        ).createMediaSource(MediaItem.fromUri(audio))
        return MergingMediaSource(video, audioSource)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            mediaSession?.player?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val queue = pending
        if (queue != null && queue.isNotEmpty()) {
            pending = null
            val player = mediaSession?.player as? ExoPlayer
            if (player != null) {
                player.setMediaSources(
                    queue.map { sourceOf(it) },
                    intent?.getIntExtra("index", 0) ?: 0,
                    intent?.getLongExtra("positionMs", 0L) ?: 0L
                )
                player.prepare()
                player.playWhenReady = true
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Swiping the app away while audio is playing keeps the music; an idle service stops.
        if (player == null || player.mediaItemCount == 0 || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run { player.release(); release() }
        mediaSession = null
        super.onDestroy()
    }
}
