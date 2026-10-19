@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.streamdock.app.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import org.streamdock.app.CinemaUi
import org.streamdock.app.Dock
import org.streamdock.app.DockIcon
import org.streamdock.app.FontFit

/**
 * The single player interface: video and audio share one screen, one control set
 * and one playback engine. Audio-only mode (the NewPipe listening mode) switches
 * the same queue to the audio track and keeps playing with a media notification
 * when the screen is off or the app is in the background.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        fun start(
            context: Context,
            items: List<Playable>,
            index: Int = 0,
            positionMs: Long = 0L,
            audioOnly: Boolean = false
        ) {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra("queue", ArrayList(items))
                .putExtra("index", index)
                .putExtra("positionMs", positionMs)
                .putExtra("audioOnly", audioOnly)
            context.startActivity(intent)
        }
    }

    private var controller: MediaController? = null
    private lateinit var playerView: PlayerView
    private lateinit var audioPanel: RelativeLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var centerRow: LinearLayout
    private lateinit var playButton: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var speedChip: TextView
    private lateinit var repeatButton: ImageButton
    private lateinit var audioButton: ImageButton
    private lateinit var lockButton: ImageButton
    private lateinit var buffering: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var unlockFab: TextView

    private var queue: List<Playable> = emptyList()
    private var startIndex = 0
    private var startMs = 0L
    private var audioOnly = false
    private var locked = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { updateTime(); handler.postDelayed(this, 400) }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(FontFit.context(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        @Suppress("DEPRECATION")
        queue = (intent.getSerializableExtra("queue") as? ArrayList<Playable>)
            ?: (PlaybackService.pending ?: emptyList())
        startIndex = intent.getIntExtra("index", 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        startMs = intent.getLongExtra("positionMs", 0L)
        audioOnly = intent.getBooleanExtra("audioOnly", false)

        buildUi()
        if (queue.isEmpty()) {
            errorView.visibility = View.VISIBLE
            errorView.text = "Nothing to play."
            return
        }

        // Hand the queue to the one playback engine, then attach this screen to it.
        PlaybackService.pending = queue
        val serviceIntent = Intent(this, PlaybackService::class.java)
            .putExtra("index", startIndex).putExtra("positionMs", startMs)
        ContextCompat.startForegroundService(this, serviceIntent)

        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            runCatching {
                val mediaController = future.get()
                controller = mediaController
                attach(mediaController)
            }.onFailure {
                errorView.visibility = View.VISIBLE
                errorView.text = "The playback engine could not start. Try again."
            }
        }, ContextCompat.getMainExecutor(this))

        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun dp(n: Int) = CinemaUi.dp(this, n)

    private fun glassBar(radius: Float = 26f) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = Dock.px(this@PlayerActivity, radius)
        setColor(Color.argb(120, 12, 22, 32))
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun toolButton(name: String, label: String, size: Int = 42, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageDrawable(DockIcon(name, Color.WHITE))
            contentDescription = label
            background = glassBar(21f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { action() }
        }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true; isFocusable = true
        }

        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        // Audio-only artwork: aurora field + glass disc + equalizer bars.
        audioPanel = RelativeLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0B1A28.toInt(), 0xFF123049.toInt(), 0xFF1B2E4A.toInt(), 0xFF241E3E.toInt())
            )
            visibility = View.GONE
        }
        val disc = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(50, 255, 255, 255))
                setStroke(dp(2), Color.argb(90, 140, 220, 255))
            }
        }
        val eq = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(28, 48, 34, 56, 30).forEach { h ->
            eq.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Dock.px(this@PlayerActivity, 3f)
                    setColor(Color.argb(190, 120, 210, 255))
                }
            }, LinearLayout.LayoutParams(dp(7), dp(h)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val discWrap = FrameLayout(this).apply {
            addView(disc, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
            addView(eq, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        audioPanel.addView(discWrap, RelativeLayout.LayoutParams(dp(240), dp(240)).apply {
            addRule(RelativeLayout.CENTER_IN_PARENT)
        })
        root.addView(audioPanel, FrameLayout.LayoutParams(-1, -1))

        // Gesture layer over the video: double-tap ±10s, drag to seek, edge swipes for brightness/volume.
        val gestureView = object : View(this) {}
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return true
                if (e.x < gestureView.width * 0.4f) { c.seekTo((c.currentPosition - 10_000).coerceAtLeast(0)); flash("-10s") }
                else if (e.x > gestureView.width * 0.6f) { c.seekTo(c.currentPosition + 10_000); flash("+10s") }
                else togglePlay()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val e1 = e1 ?: return false
                val c = controller ?: return false
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    val duration = c.duration.coerceAtLeast(1)
                    c.seekTo((c.currentPosition - (dx * (duration / 900f)).toLong()).coerceIn(0, duration))
                } else {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    val stream = AudioManager.STREAM_MUSIC
                    if (e1.x < gestureView.width * 0.5f) {
                        val lp = window.attributes
                        lp.screenBrightness = (lp.screenBrightness - dy / gestureView.height.coerceAtLeast(1)).coerceIn(0.05f, 1f)
                        window.attributes = lp
                    } else {
                        val max = am.getStreamMaxVolume(stream)
                        am.setStreamVolume(stream, (am.getStreamVolume(stream) - (dy / gestureView.height.coerceAtLeast(1) * max).toInt()).coerceIn(0, max), 0)
                    }
                }
                return true
            }
        })
        gestureView.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
        root.addView(gestureView, FrameLayout.LayoutParams(-1, -1))

        // Top glass bar.
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassBar(22f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        (topBar as LinearLayout).addView(toolButton("back", "Close player", 38) { finish() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13.5f; typeface = Dock.medium(this@PlayerActivity)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = TextView(this).apply {
            setTextColor(Color.argb(180, 255, 255, 255)); textSize = 10.5f; typeface = Dock.regular(this@PlayerActivity)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleBox.addView(titleView); titleBox.addView(subtitleView)
        (topBar as LinearLayout).addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        audioButton = toolButton("headphones", "Audio-only mode") { setAudioOnly(!audioOnly) }
        (topBar as LinearLayout).addView(audioButton, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(4) })
        lockButton = toolButton("lock", "Lock controls") { setLocked(true) }
        (topBar as LinearLayout).addView(lockButton, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(4) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(48)).apply {
            gravity = Gravity.TOP; setMargins(dp(10), dp(28), dp(10), 0)
        })

        // Center transport: rewind 10s, play/pause, forward 10s.
        centerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        centerRow.addView(toolButton("rwd10", "Back 10 seconds", 52) { val c = controller; c?.seekTo((c.currentPosition - 10_000).coerceAtLeast(0)) },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(18), 0, dp(18), 0) })
        playButton = toolButton("pause", "Play or pause", 74) { togglePlay() }.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(120, 12, 22, 32))
                setStroke(dp(2), Color.argb(110, 140, 220, 255))
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        centerRow.addView(playButton, LinearLayout.LayoutParams(dp(74), dp(74)))
        centerRow.addView(toolButton("fwd10", "Forward 10 seconds", 52) { val c = controller; c?.seekTo(c.currentPosition + 10_000) },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(18), 0, dp(18), 0) })
        root.addView(centerRow, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        // Bottom glass panel: seek line + transport row.
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBar(22f)
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeCurrent = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10.5f; typeface = Dock.medium(this@PlayerActivity); text = "0:00"
        }
        timeTotal = TextView(this).apply {
            setTextColor(Color.argb(180, 255, 255, 255)); textSize = 10.5f; typeface = Dock.medium(this@PlayerActivity); text = "0:00"
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(0xFF5ACBF2.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF8FE6FF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) controller?.seekTo(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        seekRow.addView(timeCurrent, LinearLayout.LayoutParams(-2, -2))
        seekRow.addView(seek, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        seekRow.addView(timeTotal, LinearLayout.LayoutParams(-2, -2))
        bottomBar.addView(seekRow)

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        repeatButton = toolButton("repeat", "Repeat") {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller?.repeatMode = repeatMode
            repeatButton.setImageDrawable(DockIcon(if (repeatMode == Player.REPEAT_MODE_ONE) "repeat1" else "repeat", Color.WHITE))
            repeatButton.alpha = if (repeatMode == Player.REPEAT_MODE_OFF) 0.6f else 1f
        }
        buttons.addView(repeatButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        buttons.addView(toolButton("prev", "Previous") { controller?.seekToPreviousMediaItem() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        buttons.addView(toolButton("next", "Next") { controller?.seekToNextMediaItem() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        speedChip = TextView(this).apply {
            text = "1.0x"; setTextColor(Color.WHITE); textSize = 12f; typeface = Dock.bold(this@PlayerActivity)
            gravity = Gravity.CENTER; background = glassBar(16f); setPadding(dp(12), dp(9), dp(12), dp(9))
            contentDescription = "Playback speed"
            setOnClickListener {
                speedIndex = (speedIndex + 1) % speeds.size
                controller?.setPlaybackSpeed(speeds[speedIndex])
                text = "${speeds[speedIndex]}x".removeSuffix(".0x").let { if (it == "1x") "1.0x" else it }
            }
        }
        buttons.addView(speedChip, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(8) })
        buttons.addView(toolButton("videoOn", "Video picture") { setAudioOnly(false) }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        buttons.addView(toolButton("rotate", "Rotate screen") {
            requestedOrientation =
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        bottomBar.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(22)) })

        buffering = ProgressBar(this).apply { visibility = View.GONE; indeterminateTintList = ColorStateList.valueOf(0xFF8FE6FF.toInt()) }
        root.addView(buffering, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

        errorView = TextView(this).apply {
            setTextColor(0xFFFFC9C9.toInt()); textSize = 12f; typeface = Dock.medium(this@PlayerActivity)
            gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0); visibility = View.GONE
        }
        root.addView(errorView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        unlockFab = TextView(this).apply {
            text = "Unlock"; setTextColor(Color.WHITE); textSize = 11f; typeface = Dock.bold(this@PlayerActivity)
            gravity = Gravity.CENTER; background = glassBar(16f); visibility = View.GONE
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { setLocked(false) }
        }
        root.addView(unlockFab, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(30), dp(14), 0)
        })

        setContentView(root)
    }

    private fun attach(mediaController: MediaController) {
        playerView.player = mediaController
        mediaController.repeatMode = repeatMode
        mediaController.setPlaybackSpeed(speeds[speedIndex])
        titleView.text = queue.getOrNull(mediaController.currentMediaItemIndex)?.title ?: "StreamDock"
        subtitleView.text = queue.getOrNull(mediaController.currentMediaItemIndex)?.subtitle ?: ""
        if (audioOnly) setAudioOnly(true)
        mediaController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.setImageDrawable(DockIcon(if (isPlaying) "pause" else "play", Color.WHITE))
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                updateTime()
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                val entry = queue.getOrNull(mediaController.currentMediaItemIndex)
                titleView.text = entry?.title ?: "StreamDock"
                subtitleView.text = entry?.subtitle ?: ""
            }
            override fun onPlayerError(error: PlaybackException) {
                errorView.visibility = View.VISIBLE
                errorView.text = "Playback failed: ${error.errorCodeName}. The link may have expired — analyze the page again."
            }
        })
        handler.post(ticker)
    }

    private fun updateTime() {
        val c = controller ?: return
        seek.max = c.duration.coerceAtLeast(0).toInt().coerceAtLeast(1)
        if (seek.progress != c.currentPosition.toInt()) seek.progress = c.currentPosition.toInt().coerceIn(0, seek.max)
        timeCurrent.text = format(c.currentPosition)
        timeTotal.text = format(c.duration)
    }

    private fun format(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun togglePlay() {
        val c = controller ?: return
        c.playWhenReady = !c.playWhenReady
    }

    private fun setAudioOnly(on: Boolean) {
        audioOnly = on
        val c = controller
        if (c != null) {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build()
        }
        audioPanel.visibility = if (on) View.VISIBLE else View.GONE
        playerView.visibility = if (on) View.INVISIBLE else View.VISIBLE
        audioButton.alpha = if (on) 1f else 0.62f
    }

    private fun setLocked(on: Boolean) {
        locked = on
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        bottomBar.visibility = if (on) View.GONE else View.VISIBLE
        centerRow.visibility = if (on) View.GONE else View.VISIBLE
        unlockFab.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun toggleControls() {
        if (locked) return
        val visible = topBar.visibility == View.VISIBLE
        topBar.visibility = if (visible) View.GONE else View.VISIBLE
        bottomBar.visibility = if (visible) View.GONE else View.VISIBLE
        centerRow.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun flash(text: String) {
        errorView.visibility = View.VISIBLE
        errorView.setTextColor(Color.WHITE)
        errorView.text = text
        handler.postDelayed({ errorView.visibility = View.GONE }, 500)
    }

    override fun onPause() {
        super.onPause()
        // The service keeps playing; this screen simply stops driving the clock.
        handler.removeCallbacks(ticker)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        updateTime()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        runCatching { controller?.release() }
        controller = null
        super.onDestroy()
    }
}
