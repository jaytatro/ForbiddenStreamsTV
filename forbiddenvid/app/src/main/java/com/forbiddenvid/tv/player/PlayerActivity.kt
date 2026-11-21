package com.forbiddenvid.tv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.forbiddenvid.tv.data.Http
import com.forbiddenvid.tv.data.Stream
import com.forbiddenvid.tv.data.Watch
import com.forbiddenvid.tv.data.WatchEntry
import com.forbiddenvid.tv.ui.PlayTarget
import org.json.JSONObject

/**
 * Full-screen player for one link. ◀ ▶ skip back 10 s / forward 30 s, OK shows the bar (play/pause),
 * BACK leaves. Where you stopped is saved for Continue watching.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var titleView: TextView
    private lateinit var errorView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var metaId = ""
    private var type = "movie"
    private var name = ""
    private var poster: String? = null
    private var background: String? = null
    private var videoId = ""
    private var label = ""

    private val saver = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val i = intent
        metaId = i.getStringExtra("metaId") ?: ""
        type = i.getStringExtra("type") ?: "movie"
        name = i.getStringExtra("name") ?: ""
        poster = i.getStringExtra("poster")
        background = i.getStringExtra("background")
        videoId = i.getStringExtra("videoId") ?: metaId
        label = i.getStringExtra("label") ?: ""
        val url = i.getStringExtra("url") ?: run { finish(); return }
        val headers = HashMap<String, String>()
        i.getStringExtra("headers")?.let { h ->
            try { val o = JSONObject(h); o.keys().forEach { k -> headers[k] = o.getString(k) } } catch (e: Exception) { }
        }
        val startMs = i.getLongExtra("startMs", 0L)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowSubtitleButton(true)
            keepScreenOn = true
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        titleView = TextView(this).apply {
            text = listOf(name, label).filter { it.isNotBlank() }.joinToString("  ·  ")
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setPadding(48, 36, 48, 0)
        }
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        errorView = TextView(this).apply {
            setTextColor(Color.parseColor("#7CFF3A"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(80, 0, 80, 0)
            visibility = View.GONE
        }
        root.addView(errorView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> titleView.visibility = v })

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = p
        view.player = p

        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
            val path = url.substringBefore('?').lowercase()
            when {
                path.endsWith(".m3u8") || path.contains("/hls/") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }.build()
        p.setMediaItem(item, if (startMs > 0) startMs else C.TIME_UNSET)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorView.text = "This link won't play.\n${error.errorCodeName}\n\nPress BACK and try another link."
                errorView.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) errorView.visibility = View.GONE
                if (state == Player.STATE_ENDED) {
                    save(finished = true)
                    finish()
                }
            }
        })
        p.playWhenReady = true
        p.prepare()
        handler.postDelayed(saver, 10_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekForward(); view.showController(); return true }
            }
        }
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    p.playWhenReady = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) true
                        else if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) false else !p.playWhenReady
                    view.showController()
                    return true
                }
            }
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun save(finished: Boolean = false) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = if (finished) dur else p.currentPosition
        if (pos <= 0 && !finished) return
        Watch.update(WatchEntry(metaId, type, name, poster, background, videoId, label, pos, dur, System.currentTimeMillis()))
    }

    override fun onPause() {
        super.onPause()
        save()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        save()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, t: PlayTarget, s: Stream, startMs: Long) {
            val url = s.url ?: return
            val h = JSONObject()
            s.headers.forEach { (k, v) -> h.put(k, v) }
            try {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java)
                        .putExtra("url", url)
                        .putExtra("headers", h.toString())
                        .putExtra("metaId", t.meta.id)
                        .putExtra("type", t.meta.type)
                        .putExtra("name", t.meta.name)
                        .putExtra("poster", t.meta.poster)
                        .putExtra("background", t.meta.background)
                        .putExtra("videoId", t.videoId)
                        .putExtra("label", t.label)
                        .putExtra("startMs", startMs),
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't open the player", Toast.LENGTH_LONG).show()
            }
        }
    }
}
