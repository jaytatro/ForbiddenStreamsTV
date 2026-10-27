package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.delay

private val shadow = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 6f))

/**
 * ◀ while watching live TV: categories down the left over the video (10% dark background).
 * Moving onto a category lists its channels and what's on now; OK on a channel switches to it.
 * BACK closes it.
 */
@Composable
fun LiveCategoryPanel(currentChannelId: String?, onPlay: (List<LiveChannel>, Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val groups = remember(Session.liveCats, CategoryPrefs.version) {
        groupsFor(CategoryPrefs.visible(context, Session.liveCats.orEmpty())).filter { it != Group.Search }
    }
    val current = currentChannelId?.let { Session.channelById(it) }
    val startGroup = remember {
        groups.firstOrNull { it is Group.Cat && it.cat.id == current?.categoryId }
            ?: groups.firstOrNull { it.key == prefs.lastGroup } ?: Group.All
    }
    var group by remember { mutableStateOf(startGroup) }
    val channels = remember(group.key, Session.liveAll) { channelsOf(prefs, group) }
    BackHandler { onClose() }

    Row(Modifier.fillMaxSize().background(Color(0x1A000000))) { // 10% background: the video shows through
        Column(Modifier.width(360.dp).fillMaxHeight().padding(start = 20.dp, top = 20.dp, bottom = 20.dp)) {
            Text("Categories", color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold, style = shadow,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            GroupList(
                groups, group, Modifier.weight(1f),
                onFocus = { group = it },
                textColor = Color.White,
                onPick = { group = it },
            )
        }
        Column(Modifier.width(620.dp).fillMaxHeight().padding(start = 16.dp, top = 20.dp, bottom = 20.dp)) {
            Text(group.title, color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold, style = shadow,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            if (channels.isEmpty()) {
                Text("No channels", color = Color.White, fontSize = 18.sp, style = shadow, modifier = Modifier.padding(8.dp))
            } else {
                val state = rememberLazyListState()
                LaunchedEffect(group.key) { state.scrollToItem(0) }
                LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                        PanelChannelRow(ch, playing = ch.id == currentChannelId) { onPlay(channels, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelChannelRow(ch: LiveChannel, playing: Boolean, onClick: () -> Unit) {
    val epg by produceState(Session.cachedEpg(ch), ch.id) { delay(120); value = Session.epg(ch) }
    val now = System.currentTimeMillis()
    val prog = epg?.firstOrNull { it.isLive(now) }
    FocusCard(
        onClick = onClick,
        container = Color.Transparent,
        focusedScale = 1.01f,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.num}", color = Color.White, fontSize = 15.sp, style = shadow, modifier = Modifier.width(52.dp))
            Box(Modifier.size(width = 64.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((if (playing) "▶ " else "") + ch.name, color = if (playing) FS.Green else Color.White,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, style = shadow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(prog?.let { "${it.title}  ·  ${Fmt.range(it.start, it.end)}" } ?: "",
                    color = Color(0xFFE0E0E0), fontSize = 14.sp, style = shadow, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
