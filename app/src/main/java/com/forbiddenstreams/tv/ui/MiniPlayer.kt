package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.player.PlayerHolder
import com.forbiddenstreams.tv.player.QualityBadge

/**
 * The live channel that keeps playing after BACK, shown top-left.
 * OK = back to full screen, hold OK = stop.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(width: Dp, modifier: Modifier = Modifier) {
    val item = PlayerHolder.current ?: return
    if (!item.live) return
    val context = LocalContext.current
    Column(modifier.width(width)) {
        FocusCard(
            onClick = { PlayerHolder.openFullScreen(context) },
            onLongClick = { PlayerHolder.stop() },
            shape = RoundedCornerShape(8.dp),
            container = Color.Black,
            focusedScale = 1f,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            if (!PlayerHolder.fullScreen) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            player = PlayerHolder.player(ctx)
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                )
            }
            PlayerHolder.quality?.let {
                QualityBadge(it.substringBefore(" · "), Modifier.align(Alignment.TopEnd).padding(8.dp), small = true)
            }
        }
        Box(Modifier.fillMaxWidth().background(FS.Panel, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                item.title + (item.subtitle?.let { " · $it" } ?: ""),
                color = FS.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
