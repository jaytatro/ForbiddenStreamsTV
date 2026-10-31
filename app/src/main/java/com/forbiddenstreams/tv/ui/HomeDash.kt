package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Recents
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay

/** ◀ on the first item of a row = back to the side bar. */
private fun Modifier.leftOut(first: Boolean, onLeft: () -> Unit) = onPreviewKeyEvent { ev ->
    if (first && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
        if (ev.nativeKeyEvent.repeatCount == 0) onLeft()
        true
    } else false
}

/**
 * v2 Home ("Now"): the last channel playing big with what's on, Continue watching
 * (movies and episodes you stopped part-way), and your favorite channels with what's on now.
 */
@Composable
fun HomeDashboard(onGuide: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    // Nothing playing yet: start the last channel in the preview.
    LaunchedEffect(Session.liveAll) {
        if (PlayerHolder.current != null || Session.liveAll == null) return@LaunchedEffect
        val last = prefs.history.firstOrNull()?.let { Session.channelById(it) } ?: return@LaunchedEffect
        playChannels(context, listOf(last), 0, fullScreen = false)
    }

    val item = PlayerHolder.current
    val ch: LiveChannel? = item?.channelId?.let { Session.channelById(it) }
    val epg by produceState(ch?.let { Session.cachedEpg(it) }, ch?.id) { if (ch != null) value = Session.epg(ch) }
    val prog = epg?.firstOrNull { it.isLive(now) }
    val next = prog?.let { p -> epg?.firstOrNull { it.start >= p.end } }

    val recents = remember(now) { Recents.all(context) }
    val favs = remember(Session.liveAll, now) {
        prefs.favorites.mapNotNull { Session.channelById(it) }.sortedBy { it.num }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(start = 32.dp, end = 28.dp, top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // ---- Hero: the channel you were watching ----
        item {
            Row(Modifier.fillMaxWidth().height(290.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("PICK UP WHERE YOU LEFT OFF", color = FS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (item?.live == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(FS.Live, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("${ch?.num ?: ""} · ${item.title}", color = FS.TextDim, fontSize = 15.sp, maxLines = 1)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(prog?.title ?: item.title, color = FS.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (prog != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Fmt.range(prog.start, prog.end), color = FS.Text, fontSize = 15.sp)
                                Spacer(Modifier.width(12.dp))
                                val f = ((now - prog.start).toFloat() / (prog.end - prog.start)).coerceIn(0f, 1f)
                                Box(Modifier.width(200.dp).height(4.dp).background(FS.Line, RoundedCornerShape(2.dp))) {
                                    Box(Modifier.fillMaxWidth(f).height(4.dp).background(FS.Green, RoundedCornerShape(2.dp)))
                                }
                                next?.let {
                                    Spacer(Modifier.width(12.dp))
                                    Text("Next: ${it.title}", color = FS.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Pill("▶  Watch", selected = true, fontSize = 18.sp,
                                modifier = Modifier.leftOut(true, onLeft),
                                onClick = { PlayerHolder.openFullScreen(context) })
                            Pill("Guide", selected = false, fontSize = 17.sp, onClick = onGuide)
                            if (ch != null && prog != null) {
                                Pill("⏺  Record", selected = false, fontSize = 17.sp, onClick = {
                                    com.forbiddenstreams.tv.recording.Recordings.schedule(context, ch, prog)
                                    toast(context, "Recording ${prog.title}")
                                })
                            }
                        }
                    } else {
                        Text("Pick a channel to start watching", color = FS.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Pill("Open the TV guide", selected = true, fontSize = 18.sp, modifier = Modifier.leftOut(true, onLeft), onClick = onGuide)
                    }
                }
                Box(Modifier.width(500.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    MiniPlayer(500.dp, focusable = false)
                }
            }
        }

        // ---- Continue watching ----
        if (recents.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CONTINUE WATCHING", color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(recents, key = { _, r -> r.key }) { i, r ->
                            FocusCard(
                                onClick = {
                                    Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle,
                                        resumeKey = r.key, kind = r.kind, image = r.image, startMs = r.positionMs))
                                },
                                modifier = Modifier.width(210.dp).leftOut(i == 0, onLeft),
                                focusedScale = 1.04f,
                            ) {
                                Column {
                                    Box(Modifier.fillMaxWidth().height(118.dp).background(FS.Panel2)) {
                                        NetImage(r.image, Modifier.fillMaxSize(), crop = true)
                                        val f = if (r.durationMs > 0) (r.positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f) else 0f
                                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(FS.Line)) {
                                            Box(Modifier.fillMaxWidth(f).height(4.dp).background(FS.Green))
                                        }
                                    }
                                    Text(r.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Favorite channels, on now ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YOUR FAVORITES · ON NOW", color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                if (favs.isEmpty()) {
                    Text("No favorite channels yet. In the TV guide, hold OK on a channel and pick Add to favorites.",
                        color = FS.TextDim, fontSize = 15.sp)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(favs, key = { _, c -> c.id }) { i, c ->
                            val on by produceState(Session.cachedEpg(c)?.firstOrNull { it.isLive(now) }?.title, c.id, now) {
                                value = Session.epg(c).firstOrNull { it.isLive(System.currentTimeMillis()) }?.title
                            }
                            FocusCard(
                                onClick = { playChannels(context, favs, i) },
                                modifier = Modifier.width(220.dp).height(84.dp).leftOut(i == 0, onLeft),
                                focusedScale = 1.04f,
                            ) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                                    Text("${c.num} · ${c.name}", color = FS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(on ?: "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
