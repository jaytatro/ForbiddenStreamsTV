package com.forbiddenstreams.tv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.ui.FS
import com.forbiddenstreams.tv.ui.ForbiddenTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var view: PlayerView

    /** Channel/quality banner is visible. */
    private var infoVisible by mutableStateOf(true)
    private var infoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (PlayerHolder.current == null) { PlayerHolder.fullScreen = false; finish(); return }
        PlayerHolder.fullScreen = true

        view = PlayerView(this).apply {
            player = PlayerHolder.player(this@PlayerActivity)
            useController = true
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                if (v == android.view.View.VISIBLE) showInfo()
            })
        }
        if (PlayerHolder.current?.live == true) view.hideController()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.isControllerFullyVisible && PlayerHolder.current?.live != true) {
                    view.hideController()
                    return
                }
                // Live TV keeps playing in the small top-left window; movies/episodes stop.
                if (PlayerHolder.current?.live != true) PlayerHolder.stop()
                close()
            }
        })

        setContent {
            ForbiddenTheme {
                val item = PlayerHolder.current
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    if (infoVisible && item != null) {
                        Column(
                            Modifier.align(Alignment.TopStart).padding(32.dp)
                                .background(Color(0xCC050805), RoundedCornerShape(12.dp))
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                        ) {
                            val n = if (item.live && PlayerHolder.items.size > 1) "${PlayerHolder.index + 1}  " else ""
                            Text(n + item.title, color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            item.subtitle?.let { Text(it, color = FS.Text, fontSize = 16.sp) }
                        }
                        QualityBadge(
                            PlayerHolder.quality ?: "Loading…",
                            Modifier.align(Alignment.TopEnd).padding(32.dp),
                        )
                    }
                    PlayerHolder.error?.let {
                        Text(
                            it + if (item?.live == true) "\nPress up/down to try another channel or BACK to return." else "\nPress BACK to return.",
                            color = FS.Text, fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xDD0C140D), RoundedCornerShape(12.dp)).padding(24.dp),
                        )
                    }
                }
            }
        }
        showInfo()
    }

    private fun close() {
        view.player = null
        PlayerHolder.fullScreen = false
        finish()
    }

    private fun showInfo() {
        infoVisible = true
        infoJob?.cancel()
        infoJob = lifecycleScope.launch { delay(5000); infoVisible = false }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val live = PlayerHolder.current?.live == true
        if (event.action == KeyEvent.ACTION_DOWN && ::view.isInitialized) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            }
            if (live && !view.isControllerFullyVisible) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { PlayerHolder.zap(this, -1); showInfo(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { PlayerHolder.zap(this, 1); showInfo(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        showInfo()
                        view.showController()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::view.isInitialized && view.player != null) {
            view.player = null
            PlayerHolder.fullScreen = false
        }
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        color = Color.Black,
        fontSize = if (small) 11.sp else 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(FS.Green, RoundedCornerShape(6.dp))
            .padding(horizontal = if (small) 6.dp else 12.dp, vertical = if (small) 1.dp else 5.dp),
    )
}
