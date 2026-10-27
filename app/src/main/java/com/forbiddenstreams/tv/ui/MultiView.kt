package com.forbiddenstreams.tv.ui

import android.content.Context
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay

/** Extra channels shown next to the main one in split screen (up to 3, so 4 screens total). */
object MultiView {
    val extras = mutableStateListOf<Pair<LiveChannel, ExoPlayer>>()

    fun add(context: Context, ch: LiveChannel) {
        if (extras.size >= 3) return
        val p = PlayerHolder.createPlayer(context)
        p.setMediaItem(MediaItem.fromUri(ch.url))
        p.volume = 0f
        p.prepare()
        p.playWhenReady = true
        extras += ch to p
    }

    fun remove(i: Int) {
        if (i !in extras.indices) return
        extras[i].second.release()
        extras.removeAt(i)
    }

    fun clear() {
        extras.forEach { it.second.release() }
        extras.clear()
    }
}

/**
 * Split screen: the channel that was playing on the left, a + on the right.
 * + opens the TV guide to pick another channel (◀ in that guide = categories).
 * Up to 4 screens. The highlighted screen has the sound.
 * OK on a screen = watch it full screen; hold OK on an added screen = remove it; BACK = leave.
 */
@Composable
fun MultiViewScreen(onExit: (LiveChannel?) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val main = PlayerHolder.current
    val mainCh = main?.channelId?.let { Session.channelById(it) }
    var picker by remember { mutableStateOf(false) }
    var focused by remember { mutableIntStateOf(0) } // 0 = main, 1..3 = extras
    val firstFocus = remember { FocusRequester() }

    // Only the highlighted screen plays sound.
    LaunchedEffect(focused, MultiView.extras.size) {
        PlayerHolder.existingPlayer()?.volume = if (focused == 0) 1f else 0f
        MultiView.extras.forEachIndexed { i, (_, p) -> p.volume = if (focused == i + 1) 1f else 0f }
    }
    LaunchedEffect(picker) { if (!picker) { delay(150); try { firstFocus.requestFocus() } catch (e: Exception) { } } }

    BackHandler(enabled = !picker) {
        PlayerHolder.existingPlayer()?.volume = 1f
        MultiView.clear()
        onExit(null)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val count = 1 + MultiView.extras.size
        val cells = if (count < 4) count + 1 else 4 // + 1 for the "+" tile
        @Composable
        fun cell(i: Int, scope: RowScope) = with(scope) {
            Box(Modifier.weight(1f).padding(6.dp), contentAlignment = Alignment.Center) {
                when {
                    i == 0 -> VideoTile(
                        title = main?.title ?: "Channel",
                        player = PlayerHolder.existingPlayer(),
                        hasSound = focused == 0,
                        modifier = Modifier.focusRequester(firstFocus).onFocusChanged { if (it.isFocused) focused = 0 },
                        onClick = { PlayerHolder.existingPlayer()?.volume = 1f; MultiView.clear(); onExit(null) },
                        onLongClick = { },
                    )
                    i < count -> {
                        val (ch, p) = MultiView.extras[i - 1]
                        VideoTile(
                            title = ch.name,
                            player = p,
                            hasSound = focused == i,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) focused = i },
                            onClick = { MultiView.clear(); onExit(ch) },
                            onLongClick = { MultiView.remove(i - 1); focused = 0 },
                        )
                    }
                    else -> FocusCard(
                        onClick = { picker = true },
                        shape = RoundedCornerShape(10.dp),
                        container = FS.Panel,
                        focusedScale = 1.02f,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    ) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("+", fontSize = 90.sp, fontWeight = FontWeight.Bold, color = FS.Green)
                            Text("Add a channel", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            if (cells <= 2) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until cells) cell(i, this)
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) { cell(0, this); cell(1, this) }
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    cell(2, this)
                    if (cells == 4) cell(3, this) else Box(Modifier.weight(1f))
                }
            }
            Text(
                "Highlighted screen has the sound · OK = full screen · hold OK = remove · BACK = exit split screen",
                color = FS.TextDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
            )
        }

        if (picker) {
            ChannelPicker(
                prefs = prefs,
                startChannel = mainCh,
                onPick = { ch ->
                    picker = false
                    MultiView.add(context, ch)
                },
                onClose = { picker = false },
            )
        }
    }
}

@Composable
private fun VideoTile(
    title: String,
    player: ExoPlayer?,
    hasSound: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(10.dp),
        container = Color.Black,
        focusedScale = 1.02f,
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
    ) {
        Box(Modifier.fillMaxSize().padding(3.dp)) {
            if (player != null) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    onRelease = { player.clearVideoTextureView(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                (if (hasSound) "🔊 " else "") + title,
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0xB3050805), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** TV guide for picking a channel to add. Short ◀ = category list. */
@Composable
private fun ChannelPicker(prefs: Prefs, startChannel: LiveChannel?, onPick: (LiveChannel) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val groups = remember(Session.liveCats, CategoryPrefs.version) { groupsFor(CategoryPrefs.visible(context, Session.liveCats.orEmpty())) }
    val startGroup = remember {
        groups.firstOrNull { g -> g is Group.Cat && g.cat.id == startChannel?.categoryId } ?: Group.All
    }
    var group by remember { mutableStateOf(startGroup) }
    var drawer by remember { mutableStateOf(false) }
    val list = remember(group.key, Session.liveAll) { channelsOf(prefs, group) }
    BackHandler(enabled = !drawer) { onClose() }
    Box(Modifier.fillMaxSize().background(FS.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Pick a channel to add · ◀ = categories · BACK = cancel",
                color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 12.dp),
            )
            if (list.isEmpty()) {
                MessageBox("No channels in this category", "Press ◀ for categories.")
            } else {
                GuideView(
                    group = group,
                    channels = list,
                    startId = startChannel?.id,
                    active = !drawer,
                    onCategories = { drawer = true },
                    onToggleFav = { prefs.toggleFavorite(it.id) },
                    onPlayChannel = { l, i -> onPick(l[i]) },
                    onPlayItem = { toast(context, "Split screen plays live channels only") },
                    leftOpensCategories = true,
                )
            }
        }
        if (drawer) {
            CategoryDrawer(
                groups, group,
                onPick = { group = it; drawer = false },
                onClose = { drawer = false },
            )
        }
    }
}
