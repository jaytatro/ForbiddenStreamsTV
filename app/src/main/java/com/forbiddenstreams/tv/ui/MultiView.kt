package com.forbiddenstreams.tv.ui

import android.content.Context
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay

/** One screen in Multi-View. The first one uses the app's main player. */
class MvTile(ch: LiveChannel?, val player: ExoPlayer, val main: Boolean) {
    var ch by mutableStateOf(ch)
    var paused by mutableStateOf(false)
}

/** Screens in Multi-View (up to 4). */
object MultiView {
    val tiles = mutableStateListOf<MvTile>()

    fun start(context: Context) {
        clear()
        val p = PlayerHolder.existingPlayer() ?: PlayerHolder.player(context)
        tiles += MvTile(PlayerHolder.current?.channelId?.let { Session.channelById(it) }, p, main = true)
    }

    fun add(context: Context, ch: LiveChannel) {
        if (tiles.size >= 4) return
        val p = PlayerHolder.createPlayer(context)
        p.setMediaItem(MediaItem.fromUri(ch.url))
        p.volume = 0f
        p.prepare()
        p.playWhenReady = true
        tiles += MvTile(ch, p, main = false)
    }

    fun change(context: Context, i: Int, ch: LiveChannel) {
        val t = tiles.getOrNull(i) ?: return
        if (t.main) playChannels(context, listOf(ch), 0, fullScreen = false)
        else { t.player.setMediaItem(MediaItem.fromUri(ch.url)); t.player.prepare(); t.player.playWhenReady = true }
        t.ch = ch
        t.paused = false
    }

    fun togglePause(i: Int) {
        val t = tiles.getOrNull(i) ?: return
        t.paused = !t.paused
        t.player.playWhenReady = !t.paused
    }

    /** Removing the main screen moves the next screen's channel onto the main player. */
    fun remove(context: Context, i: Int) {
        val t = tiles.getOrNull(i) ?: return
        if (tiles.size == 1) return
        if (t.main) {
            val next = tiles[1]
            next.ch?.let { playChannels(context, listOf(it), 0, fullScreen = false); t.ch = it }
            next.player.release()
            tiles.removeAt(1)
        } else {
            t.player.release()
            tiles.removeAt(i)
        }
    }

    fun clear() {
        tiles.filter { !it.main }.forEach { it.player.release() }
        tiles.clear()
        PlayerHolder.existingPlayer()?.let { it.volume = 1f; it.playWhenReady = true }
    }
}

/**
 * Multi-View (from the live player's OK menu).
 *  - Starts with the channel that was playing, in the middle.
 *  - Arrows move between screens; the highlighted screen has the sound.
 *  - OK = menu on the right: Add screen · Search and add · Change channel
 *    (+ Pause · Enlarge screen · Full screen · Remove screen with 2 or more screens).
 *  - 2 screens side by side, 3 = two on top + one below, 4 = 2×2; Enlarge = one big on top, the rest below.
 *  - BACK closes the menu, then leaves Multi-View.
 */
@Composable
fun MultiViewScreen(onExit: (LiveChannel?) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    LaunchedEffect(Unit) { if (MultiView.tiles.isEmpty()) MultiView.start(context) }
    val tiles = MultiView.tiles
    var sel by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf<Int?>(null) }
    var picker by remember { mutableStateOf<String?>(null) } // "add" | "change" | "search"
    val reqs = remember { List(4) { FocusRequester() } }

    if (sel >= tiles.size) sel = (tiles.size - 1).coerceAtLeast(0)
    if (enlarged != null && enlarged!! >= tiles.size) enlarged = null

    // Only the highlighted screen plays sound.
    LaunchedEffect(sel, tiles.size) { tiles.forEachIndexed { i, t -> t.player.volume = if (i == sel) 1f else 0f } }
    LaunchedEffect(menu, picker, tiles.size, enlarged) {
        if (!menu && picker == null) { delay(120); try { reqs[sel].requestFocus() } catch (e: Exception) { } }
    }

    fun exit(ch: LiveChannel?) { MultiView.clear(); onExit(ch) }
    BackHandler(enabled = picker == null) { if (menu) menu = false else exit(null) }

    @Composable
    fun tile(i: Int, modifier: Modifier) {
        val t = tiles.getOrNull(i) ?: return
        MvVideo(
            t, selected = i == sel, req = reqs[i],
            modifier = modifier,
            onFocus = { sel = i },
            onClick = { sel = i; menu = true },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val e = enlarged
        when {
            e != null && tiles.size > 1 -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                tile(e, Modifier.weight(3f).aspectRatio(16f / 9f))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.indices.filter { it != e }.forEach { i -> tile(i, Modifier.fillMaxHeight().aspectRatio(16f / 9f)) }
                }
            }
            tiles.size <= 1 -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                tile(0, Modifier.width(820.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.height(18.dp))
                Text("Press OK to show menu", color = Color.White, fontSize = 17.sp)
                Text("Your IPTV provider may limit the number of concurrent connections", color = FS.TextDim, fontSize = 13.sp)
            }
            tiles.size == 2 -> Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tile(0, Modifier.weight(1f).aspectRatio(16f / 9f)); tile(1, Modifier.weight(1f).aspectRatio(16f / 9f))
            }
            else -> Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tile(0, Modifier.fillMaxHeight().aspectRatio(16f / 9f)); tile(1, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tile(2, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                    if (tiles.size == 4) tile(3, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
                }
            }
        }

        if (menu) {
            val t = tiles.getOrNull(sel)
            val opts = ArrayList<Pair<String, () -> Unit>>()
            if (tiles.size < 4) opts += "Add screen" to { menu = false; picker = "add" }
            if (tiles.size < 4) opts += "Search and add" to { menu = false; picker = "search" }
            opts += "Change channel" to { menu = false; picker = "change" }
            if (tiles.size > 1 && t != null) {
                opts += (if (t.paused) "Play" else "Pause") to { MultiView.togglePause(sel); menu = false }
                opts += (if (enlarged == sel) "Normal size" else "Enlarge screen") to { enlarged = if (enlarged == sel) null else sel; menu = false }
                opts += "Full screen" to { val c = t.ch; exit(if (t.main) null else c) }
                opts += "Remove screen" to { MultiView.remove(context, sel); if (enlarged == sel) enlarged = null; menu = false }
            }
            MvMenu(opts, Modifier.align(Alignment.CenterEnd).padding(end = 36.dp))
        }

        when (picker) {
            "add", "change" -> ChannelPicker(
                prefs = prefs,
                startChannel = tiles.getOrNull(sel)?.ch,
                title = if (picker == "add") "Pick a channel to add" else "Pick a new channel for this screen",
                onPick = { ch ->
                    if (picker == "add") { MultiView.add(context, ch); sel = tiles.size - 1 } else MultiView.change(context, sel, ch)
                    picker = null
                },
                onClose = { picker = null },
            )
            "search" -> SearchChannelPicker(
                onPick = { ch -> MultiView.add(context, ch); sel = tiles.size - 1; picker = null },
                onClose = { picker = null },
            )
        }
    }
}

@Composable
private fun MvVideo(t: MvTile, selected: Boolean, req: FocusRequester, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.focusRequester(req).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, Color(0x33FFFFFF)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().padding(3.dp)) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { t.player.setVideoTextureView(it) } },
                onRelease = { try { t.player.clearVideoTextureView(it) } catch (e: Exception) { } },
                modifier = Modifier.fillMaxSize(),
            )
            if (selected || t.paused) {
                Text(
                    (if (selected) "🔊 " else "") + (if (t.paused) "⏸ " else "") + (t.ch?.name ?: ""),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** The menu on the right side of the screen. */
@Composable
private fun MvMenu(options: List<Pair<String, () -> Unit>>, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); try { first.requestFocus() } catch (e: Exception) { } }
    Column(
        modifier.width(300.dp).background(Color(0xF2262626), RoundedCornerShape(12.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, (label, action) ->
            val shape = RoundedCornerShape(8.dp)
            Surface(
                onClick = action,
                modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent, contentColor = Color.White,
                    focusedContainerColor = Color(0xFFE8E8E8), focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text(label, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

/** "Search and add": type a channel name, matches appear as you type. */
@Composable
private fun SearchChannelPicker(onPick: (LiveChannel) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onClose() }
    var q by remember { mutableStateOf("") }
    val all = Session.liveAll.orEmpty()
    val excluded = remember { CategoryPrefs.excluded(context) }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); try { box.requestFocus() } catch (e: Exception) { } }
    val results = remember(q, all) {
        val t = q.trim()
        if (t.length < 2) emptyList()
        else all.asSequence().filter { it.categoryId !in excluded && it.name.contains(t, ignoreCase = true) }
            .distinctBy { it.id }.take(120).toList()
    }
    Column(Modifier.fillMaxSize().background(FS.Bg).padding(horizontal = 40.dp, vertical = 20.dp)) {
        Text("Search and add", color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().focusRequester(box)) {
            FsTextField("Channel name", q, { q = it }, androidx.compose.ui.text.input.KeyboardType.Text)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(results, key = { _, c -> c.id }) { _, c ->
                FocusCard(onClick = { onPick(c) }, modifier = Modifier.fillMaxWidth().height(58.dp), focusedScale = 1.01f) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 70.dp, height = 38.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
                        Spacer(Modifier.width(14.dp))
                        Text(c.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** TV guide for picking a channel. Short ◀ = category list. */
@Composable
private fun ChannelPicker(
    prefs: Prefs,
    startChannel: LiveChannel?,
    title: String,
    onPick: (LiveChannel) -> Unit,
    onClose: () -> Unit,
) {
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
                "$title · ◀ = categories · BACK = cancel",
                color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 12.dp),
            )
            if (list.isEmpty()) {
                MessageBox("No channels in this category", "Press BACK to cancel.")
            } else {
                GuideView(
                    group = group,
                    channels = list,
                    startId = startChannel?.id,
                    active = !drawer,
                    onCategories = { drawer = true },
                    onToggleFav = { prefs.toggleFavorite(it.id) },
                    onPlayChannel = { l, i -> onPick(l[i]) },
                    onPlayItem = { toast(context, "Multi-View plays live channels only") },
                    leftOpensCategories = true,
                )
            }
        }
        if (drawer) {
            CategoryDrawer(groups, group, onPick = { group = it; drawer = false }, onClose = { drawer = false })
        }
    }
}
