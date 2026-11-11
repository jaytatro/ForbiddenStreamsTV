package com.forbiddenstreams.tv.ui

import android.view.TextureView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Program
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val DAY_MS = 86_400_000L

private fun dayStartMs(offset: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, offset)
}.timeInMillis

private fun keyIs(ev: androidx.compose.ui.input.key.KeyEvent, code: Int) =
    ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == code

/**
 * Live TV tab (Nov 3 2026, from the user's photos). The playing channel fills the screen behind a dark see-through layer.
 *  Step 1: categories (History, Favorite List, the provider's categories) on the left, that category's
 *          channels next to them (number · logo · name · show on now · ⟲ catch-up), "1/113" counter.
 *  Step 2 (▶ / OK on a category): the categories slide away; channels on the left, the highlighted
 *          channel's shows for the day in the middle (time · title; on now in green; ⟲ past shows with
 *          catch-up), and the dates down the right (past days · Today · next days).
 *  OK on a channel: plays it behind the menu; OK again on the playing channel = full screen. Hold OK = favorite.
 *  ▶ from a channel → its shows (OK = watch / catch-up); ▶ again → dates. ◀ goes back a step.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveChannelsTab() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val cats = Session.liveCats

    Box(Modifier.fillMaxSize()) {
        LiveBackdrop()
        // Dark see-through layer so the lists read over the video.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2000000), 0.75f to Color(0xD9000000), 1f to Color(0x99000000))))

        if (cats == null) {
            Box(Modifier.fillMaxSize().padding(top = 76.dp)) {
                val err = Session.liveError
                if (err != null && !Session.refreshing) MessageBox("Couldn't load channels", err, "Retry") { Session.refreshInBackground(context) }
                else LoadingBox("Loading channels")
            }
            return@Box
        }

        val groups = remember(cats, CategoryPrefs.version) {
            groupsFor(CategoryPrefs.visible(context, cats)).filter { it != Group.Search && it != Group.All }
        }
        var favTick by remember { mutableIntStateOf(0) }
        var step by remember { mutableIntStateOf(1) }
        // Always starts at History (Nov 8).
        var shown by remember { mutableStateOf(groups.firstOrNull { it == Group.History } ?: groups.firstOrNull()) }
        var pending by remember { mutableStateOf<Group?>(null) }
        LaunchedEffect(pending) { val g = pending ?: return@LaunchedEffect; delay(250); shown = g }

        // Search bar above the categories: typing shows matching channels from every category.
        var chQuery by remember { mutableStateOf("") }
        LaunchedEffect(chQuery) { if (chQuery.trim().length >= 2) { delay(250); shown = Group.Search } }
        val channels = remember(shown?.key, Session.liveAll, favTick, if (shown == Group.Search) chQuery else "") {
            val g = shown ?: return@remember emptyList<LiveChannel>()
            if (g == Group.Search) {
                val q = chQuery.trim()
                if (q.length < 2) emptyList() else channelsOf(prefs, g).filter { channelMatches(it.name, q) }
            } else channelsOf(prefs, g)
        }
        var chIndex by remember(shown?.key) {
            mutableIntStateOf(channels.indexOfFirst { it.id == PlayerHolder.current?.channelId }.coerceAtLeast(0))
        }
        val ch = channels.getOrNull(chIndex)
        var dayOffset by remember { mutableIntStateOf(0) }
        LaunchedEffect(ch?.id) { dayOffset = 0 }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

        val catReq = remember { FocusRequester() }
        val chReq = remember { FocusRequester() }
        val progReq = remember { FocusRequester() }
        val dateReq = remember { FocusRequester() }
        fun focus(r: FocusRequester) {
            scope.launch { repeat(8) { delay(70); try { r.requestFocus(); return@launch } catch (e: Exception) { } } }
        }
        fun openChannels(g: Group) {
            shown = g; pending = null
            prefs.lastGroup = g.key
            step = 2
            focus(chReq)
        }
        fun backToCategories() { step = 1; focus(catReq) }
        var hideAsk by remember { mutableStateOf<Group.Cat?>(null) }
        var searching by remember { mutableStateOf(false) }
        // ▼ from the Live TV tab: the category (step 1) or the channel (step 2) — safely (no crash if it scrolled away).
        val goIn: () -> Boolean = {
            try { (if (step == 1) catReq else chReq).requestFocus(); true } catch (e: Exception) { false }
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            TabDown.handler = goIn
            onDispose { if (TabDown.handler === goIn) TabDown.handler = null }
        }
        if (searching) ChannelSearchDialog(onClose = { searching = false })
        hideAsk?.let { g -> HideCategoryDialog(g, onClose = { hideAsk = null; focus(catReq) }) }

        val catW by animateDpAsState(if (step == 1) 300.dp else 0.dp, tween(240), label = "cats")

        Row(
            Modifier.fillMaxSize().padding(top = 76.dp, start = 18.dp, end = 18.dp)
                // ▼ from the tabs lands on the category (step 1) or the channel (step 2).
                .focusGroup(),
        ) {
            // ---- Categories ----
            if (catW > 0.dp) {
                Box(Modifier.width(catW).fillMaxHeight().clipToBounds()) {
                  Column(Modifier.width(300.dp).fillMaxHeight()) {
                    // Nov 8: a 🔍 button; OK opens the search with the keyboard up right away.
                    Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)) {
                        Pill("🔍", selected = false, outlined = true, fontSize = 18.sp, hPad = 16.dp, onClick = { searching = true })
                    }
                    val sel = groups.indexOfFirst { it.key == shown?.key }.coerceAtLeast(0)
                    val catState = rememberLazyListState((sel - 3).coerceAtLeast(0))
                    val topReq = remember { FocusRequester() }
                    LazyColumn(
                        Modifier.width(300.dp).fillMaxHeight(),
                        state = catState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        itemsIndexed(groups, key = { _, g -> g.key }) { i, g ->
                            ListPill(
                                selected = g.key == shown?.key,
                                onClick = { openChannels(g) },
                                onLongClick = { if (g is Group.Cat) hideAsk = g },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (i == sel) Modifier.focusRequester(catReq) else Modifier)
                                    .then(if (i == 0) Modifier.focusRequester(topReq) else Modifier)
                                    .onFocusChanged { if (it.isFocused && step == 1) pending = g }
                                    .onPreviewKeyEvent { ev ->
                                        when {
                                            keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_RIGHT) -> {
                                                if (ev.nativeKeyEvent.repeatCount == 0) openChannels(g); true
                                            }
                                            // ▼ on the last category → back to the top (round robin).
                                            keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_DOWN) && i == groups.size - 1 -> {
                                                scope.launch {
                                                    try { catState.scrollToItem(0) } catch (e: Exception) { }
                                                    repeat(5) { delay(30); try { topReq.requestFocus(); return@launch } catch (e: Exception) { } }
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                            ) {
                                Text(g.title, color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                            }
                        }
                    }
                  }
                }
            }

            // ---- Channels ----
            Column(Modifier.then(if (step == 1) Modifier.weight(1f) else Modifier.width(560.dp)).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 4.dp)) {
                    Text(if (shown == Group.Search) "🔍 \"${chQuery.trim()}\"" else shown?.title ?: "", color = FS.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channels.isNotEmpty()) Text("${chIndex + 1}/${channels.size}", color = FS.TextDim, fontSize = 16.sp)
                }
                if (channels.isEmpty()) {
                    Text(if (shown == Group.Favorites) "No favorite channels yet. Hold OK on a channel to add it."
                        else "No channels in this category.",
                        color = FS.TextDim, fontSize = 17.sp, modifier = Modifier.padding(24.dp))
                } else {
                    val state = rememberLazyListState((chIndex - 2).coerceAtLeast(0))
                    LazyColumn(
                        Modifier.fillMaxSize(), state = state,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 6.dp),
                    ) {
                        itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                            val playing = PlayerHolder.current?.channelId == c.id
                            ListPill(
                                selected = i == chIndex && step == 2,
                                onClick = {
                                    if (playing) PlayerHolder.openFullScreen(context)
                                    else playChannels(context, channels, i, fullScreen = false)
                                },
                                onLongClick = {
                                    val added = prefs.toggleFavorite(c.id); favTick++
                                    toast(context, if (added) "Added ${c.name} to Favorite List" else "Removed ${c.name} from Favorite List")
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (i == chIndex) Modifier.focusRequester(chReq) else Modifier)
                                    .onFocusChanged { if (it.isFocused) { chIndex = i; if (step == 1) step = 2 } }
                                    .onPreviewKeyEvent { ev ->
                                        when {
                                            keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_LEFT) -> { if (ev.nativeKeyEvent.repeatCount == 0) backToCategories(); true }
                                            keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_RIGHT) -> { if (ev.nativeKeyEvent.repeatCount == 0) focus(progReq); true }
                                            else -> false
                                        }
                                    },
                            ) { ChannelLine(c, playing, now) }
                        }
                    }
                }
            }

            // ---- The highlighted channel's shows, and the dates ----
            if (step == 2 && ch != null) {
                Spacer(Modifier.width(18.dp))
                ProgramList(
                    ch = ch, dayOffset = dayOffset, now = now, req = progReq,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onLeft = { focus(chReq) },
                    onRight = { focus(dateReq) },
                    onPlay = { p ->
                        val src = Session.source ?: return@ProgramList
                        when {
                            p.isLive(System.currentTimeMillis()) -> playChannels(context, channels, chIndex, fullScreen = true)
                            p.end <= System.currentTimeMillis() -> {
                                val u = src.catchupUrl(ch, p)
                                if (u != null) Playback.one(context, PlayItem(p.title, u, live = false, subtitle = ch.name))
                                else toast(context, "Catch-up isn't available for this show")
                            }
                            else -> toast(context, "${p.title} starts at ${Fmt.time(p.start)}")
                        }
                    },
                )
                DateColumn(
                    selected = dayOffset, req = dateReq,
                    onPick = { dayOffset = it },
                    onLeft = { focus(progReq) },
                )
            }
        }
    }
}

/** A rounded row that lights up when highlighted (like the photos), green outline in our colors. */
@Composable
private fun ListPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick, onLongClick = onLongClick, modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(28.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x33FFFFFF) else Color.Transparent, contentColor = FS.Text,
            focusedContainerColor = FS.GreenDeep, focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, FS.Green), shape = RoundedCornerShape(28.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) { content() }
}

@Composable
private fun ChannelLine(c: LiveChannel, playing: Boolean, now: Long) {
    val on by produceState(Session.cachedEpg(c)?.firstOrNull { it.isLive(now) }?.title, c.id, now) {
        value = Session.epg(c).firstOrNull { it.isLive(System.currentTimeMillis()) }?.title
    }
    Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${c.num}", color = FS.TextDim, fontSize = 17.sp, modifier = Modifier.width(54.dp))
        Box(Modifier.size(width = 84.dp, height = 46.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = if (playing) FS.Green else Color.Unspecified, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(on ?: "N/A", color = FS.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) { Spacer(Modifier.width(8.dp)); LiveDot() }
        if (c.hasArchive) { Spacer(Modifier.width(10.dp)); GText("⟲", color = FS.TextDim, fontSize = 22.sp) }
    }
}

@Composable
private fun ProgramList(
    ch: LiveChannel, dayOffset: Int, now: Long, req: FocusRequester, modifier: Modifier,
    onLeft: () -> Unit, onRight: () -> Unit, onPlay: (Program) -> Unit,
) {
    // A short pause so scrolling down the channels stays smooth.
    val epg by produceState(Session.cachedEpg(ch), ch.id) { delay(200); value = Session.epg(ch) }
    val from = dayStartMs(dayOffset)
    val day = remember(epg, dayOffset) { epg.orEmpty().filter { it.end > from && it.start < from + DAY_MS }.sortedBy { it.start } }
    val focusIdx = remember(day, now) {
        day.indexOfFirst { it.isLive(now) }.takeIf { it >= 0 } ?: day.indexOfFirst { it.end > now }.takeIf { it >= 0 } ?: 0
    }
    Box(modifier) {
        when {
            epg == null -> LoadingBox("Loading the guide")
            day.isEmpty() -> Text("No guide information for this day", color = FS.TextDim, fontSize = 17.sp, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = remember(ch.id, dayOffset) { androidx.compose.foundation.lazy.LazyListState((focusIdx - 1).coerceAtLeast(0)) },
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                itemsIndexed(day, key = { i, p -> "$i:${p.start}" }) { i, p ->
                    val live = p.isLive(now)
                    val past = p.end <= now
                    ListPill(
                        selected = false, onClick = { onPlay(p) },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i == focusIdx) Modifier.focusRequester(req) else Modifier)
                            .onPreviewKeyEvent { ev ->
                                when {
                                    keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_LEFT) -> { onLeft(); true }
                                    keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_RIGHT) -> { onRight(); true }
                                    else -> false
                                }
                            },
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Fmt.range(p.start, p.end), color = if (live) FS.Green else FS.TextDim, fontSize = 14.sp)
                                Text(p.title, color = if (live) FS.Green else Color.Unspecified, fontSize = 19.sp,
                                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            when {
                                live -> LiveDot()
                                past && ch.hasArchive -> GText("⟲", color = FS.TextDim, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dates down the right: 3 days back · Today · 3 days ahead. Highlighting one shows its shows. */
@Composable
private fun DateColumn(selected: Int, req: FocusRequester, onPick: (Int) -> Unit, onLeft: () -> Unit) {
    Column(Modifier.width(96.dp).fillMaxHeight().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("DATE", color = FS.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        for (d in -3..3) {
            val t = dayStartMs(d)
            ListPill(
                selected = d == selected, onClick = { onPick(d) },
                modifier = Modifier.width(88.dp)
                    .then(if (d == selected) Modifier.focusRequester(req) else Modifier)
                    .onFocusChanged { if (it.isFocused) onPick(d) }
                    .onPreviewKeyEvent { ev -> if (keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_LEFT)) { onLeft(); true } else false },
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (d == 0) "Today" else SimpleDateFormat("d", Locale.US).format(t), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(SimpleDateFormat("EEE", Locale.US).format(t).uppercase(Locale.US), color = FS.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

/** The playing live channel, full screen behind the Live TV menu. */
@Composable
private fun LiveBackdrop() {
    val item = PlayerHolder.current
    if (item == null || !item.live || PlayerHolder.fullScreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)); return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.runtime.key(PlayerHolder.playerVersion) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { PlayerHolder.player(ctx).setVideoTextureView(it) } },
                onRelease = { PlayerHolder.existingPlayer()?.clearVideoTextureView(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
