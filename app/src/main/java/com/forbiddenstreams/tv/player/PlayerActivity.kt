package com.forbiddenstreams.tv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.ui.FS
import com.forbiddenstreams.tv.ui.ForbiddenTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlayItem(
    val title: String,
    val url: String,
    val live: Boolean,
    val subtitle: String? = null,
    val channelId: String? = null,
)

/** The list being played; live channels can be flipped with up/down. */
object PlayQueue {
    var items: List<PlayItem> = emptyList()
    var index: Int = 0
    var onChannelWatched: ((String) -> Unit)? = null
}

object Playback {
    fun start(context: Context, items: List<PlayItem>, index: Int = 0) {
        if (items.isEmpty()) return
        PlayQueue.items = items
        PlayQueue.index = index.coerceIn(0, items.size - 1)
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    fun one(context: Context, item: PlayItem) = start(context, listOf(item), 0)
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView

    private var banner by mutableStateOf<String?>(null)
    private var bannerSub by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var bannerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (PlayQueue.items.isEmpty()) { finish(); return }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = DefaultDataSource.Factory(this, http)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "This stream won't play right now (${e.errorCodeName}).\nPress up/down to try another channel or BACK to return."
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
            }
        })

        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = true
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
        }

        setContent {
            ForbiddenTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    banner?.let { title ->
                        Column(
                            Modifier.align(Alignment.TopStart).padding(32.dp)
                                .background(Color(0xCC050805), RoundedCornerShape(12.dp))
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                        ) {
                            Text(title, color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            bannerSub?.let { Text(it, color = FS.Text, fontSize = 16.sp) }
                        }
                    }
                    error?.let {
                        Text(
                            it, color = FS.Text, fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xDD0C140D), RoundedCornerShape(12.dp)).padding(24.dp),
                        )
                    }
                }
            }
        }
        playCurrent()
    }

    private val current get() = PlayQueue.items[PlayQueue.index]

    private fun playCurrent() {
        val item = current
        error = null
        player.setMediaItem(MediaItem.fromUri(item.url))
        player.prepare()
        player.playWhenReady = true
        if (item.live) view.hideController()
        item.channelId?.let { PlayQueue.onChannelWatched?.invoke(it) }
        showBanner(
            if (item.live && PlayQueue.items.size > 1) "${PlayQueue.index + 1}  ${item.title}" else item.title,
            item.subtitle,
        )
    }

    private fun showBanner(title: String, sub: String?) {
        banner = title
        bannerSub = sub
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch { delay(4000); banner = null }
    }

    private fun zap(step: Int) {
        val n = PlayQueue.items.size
        if (n < 2) return
        PlayQueue.index = (PlayQueue.index + step + n) % n
        playCurrent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::view.isInitialized && current.live && !view.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showBanner("${PlayQueue.index + 1}  ${current.title}", current.subtitle)
                    view.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
