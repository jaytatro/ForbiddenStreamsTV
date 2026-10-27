package com.forbiddenstreams.tv.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.player.PlayerHolder
import com.forbiddenstreams.tv.player.QualityBadge

/**
 * Live preview window (top-right of the screen).
 * Uses a TextureView: a SurfaceView inside Compose's layers shows black, which is
 * why the first version of the preview didn't work.
 * When [focusable] is true: OK = full screen, hold OK = stop.
 */
@Composable
fun MiniPlayer(width: Dp, modifier: Modifier = Modifier, focusable: Boolean = true) {
    val item = PlayerHolder.current ?: return
    if (!item.live) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    val video: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!PlayerHolder.fullScreen) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { PlayerHolder.player(ctx).setVideoTextureView(it) } },
                    onRelease = { PlayerHolder.existingPlayer()?.clearVideoTextureView(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            PlayerHolder.quality?.let {
                QualityBadge(it.substringBefore(" · "), Modifier.align(Alignment.TopEnd).padding(6.dp), small = true)
            }
            PlayerHolder.error?.let {
                Text("Stream error", color = FS.Live, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
    Column(modifier.width(width)) {
        if (focusable) {
            FocusCard(
                onClick = { PlayerHolder.openFullScreen(context) },
                onLongClick = { PlayerHolder.stop() },
                shape = shape,
                container = Color.Black,
                focusedScale = 1f,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            ) { Box(Modifier.padding(3.dp).clip(shape)) { video() } }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(2.dp, FS.GreenDeep, shape).padding(2.dp).clip(shape)) { video() }
        }
        Text(
            item.title + (item.subtitle?.let { " · $it" } ?: ""),
            color = FS.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
