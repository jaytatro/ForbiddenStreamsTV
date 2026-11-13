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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontFamily
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
        // A show later on: pop-up with "Add a reminder" and "Record" (Nov 13).
        var later by remember { mutableStateOf<Pair<LiveChannel, Program>?>(null) }
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

        // Nov 13 restyle ("make the Live TV screen unique, same idea"): a broadcast control-room look —
        // an ON AIR strip, glass panels with neon edges, LED channel numbers and a timeline of shows.
        OnAirStrip(now, Modifier.padding(top = 76.dp, start = 18.dp, end = 18.dp))
        val counts = remember(Session.liveAll) { Session.liveAll.orEmpty().groupingBy { it.categoryId }.eachCount() }
        Row(
            Modifier.fillMaxSize().padding(top = 122.dp, start = 18.dp, end = 18.dp, bottom = 14.dp)
                // ▼ from the tabs lands on the category (step 1) or the channel (step 2).
                .focusGroup(),
        ) {
            // ---- Categories ----
            if (catW > 0.dp) {
                Box(Modifier.width(catW).fillMaxHeight().clipToBounds()) {
                  Column(Modifier.width(288.dp).fillMaxHeight().glass()) {
                    // Nov 8: a 🔍 button; OK opens the search with the keyboard up right away.
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("CATEGORIES", color = FS.GreenMid, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                        Pill("🔍", selected = false, outlined = true, fontSize = 16.sp, hPad = 14.dp, onClick = { searching = true })
                    }
                    val sel = groups.indexOfFirst { it.key == shown?.key }.coerceAtLeast(0)
                    val catState = rememberLazyListState((sel - 3).coerceAtLeast(0))
                    val topReq = remember { FocusRequester() }
                    LazyColumn(
                        Modifier.width(288.dp).fillMaxHeight(),
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
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.title, color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    val n = when (g) {
                                        is Group.Cat -> counts[g.cat.id] ?: 0
                                        Group.History -> prefs.history.size
                                        Group.Favorites -> prefs.favorites.size
                                        else -> 0
                                    }
                                    if (n > 0) Text("$n", color = FS.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                  }
                }
            }

            // ---- Channels ----
            if (catW > 0.dp) Spacer(Modifier.width(14.dp))
            Column(Modifier.then(if (step == 1) Modifier.weight(1f) else Modifier.width(540.dp)).fillMaxHeight().glass()) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(Modifier.width(14.dp))
                ProgramList(
                    ch = ch, dayOffset = dayOffset, now = now, req = progReq,
                    modifier = Modifier.weight(1f).fillMaxHeight().glass(),
                    onLeft = { focus(chReq) },
                    onRight = { focus(dateReq) },
                    onPlay = { p ->
                        val src = Session.source ?: return@ProgramList
                        when {
                            p.isLive(System.currentTimeMillis()) -> playChannels(context, channels, chIndex, fullScreen = true)
                            p.end <= System.currentTimeMillis() -> {
                                // Playback of an earlier show (yesterday and older too) when the channel has catch-up.
                                val u = if (canPlayBack(ch, p)) src.catchupUrl(ch, p) else null
                                if (u != null) Playback.one(context, PlayItem(p.title, u, live = false, subtitle = ch.name))
                                else toast(context, "Playback isn't available for this show")
                            }
                            else -> later = ch to p
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                DateColumn(
                    selected = dayOffset, req = dateReq,
                    onPick = { dayOffset = it },
                    onLeft = { focus(progReq) },
                )
            }
        }
        later?.let { (c, p) ->
            val hasRem = remember(p) { com.forbiddenstreams.tv.reminders.Reminders.all(context).firstOrNull { it.title == p.title && it.start == p.start } }
            val hasRec = remember(p) { com.forbiddenstreams.tv.recording.Recordings.all(context).any { it.title == p.title && it.start == p.start } }
            val opts = ArrayList<Pair<String, () -> Unit>>()
            opts += (if (hasRem != null) "⏰  Remove reminder" else "⏰  Add a reminder") to {
                if (hasRem != null) { com.forbiddenstreams.tv.reminders.Reminders.remove(context, hasRem.id); toast(context, "Reminder removed") }
                else { com.forbiddenstreams.tv.reminders.Reminders.add(context, c, p); toast(context, "Reminder set for ${Fmt.day(p.start)} ${Fmt.time(p.start)}") }
                later = null
            }
            opts += (if (hasRec) "⏺  Already set to record" else "⏺  Record") to {
                if (!hasRec) {
                    com.forbiddenstreams.tv.recording.Recordings.schedule(context, c, p)
                    toast(context, "Will record ${p.title} at ${Fmt.time(p.start)}")
                }
                later = null
            }
            opts += "✕  Close" to { later = null }
            ChoiceDialog(
                title = p.title,
                subtitle = "${c.name} · ${Fmt.day(p.start)} ${Fmt.range(p.start, p.end)}" +
                    (if (p.description.isNotBlank()) "\n${p.description}" else ""),
                options = opts,
                onDismiss = { later = null },
            )
        }
    }
}

/** Can this earlier show be played back? The channel has catch-up and the show is within its catch-up days. */
private fun canPlayBack(ch: LiveChannel, p: Program): Boolean {
    if (!ch.hasArchive && !p.hasArchive) return false
    val days = if (ch.archiveDays > 0) ch.archiveDays else 7
    return p.end <= System.currentTimeMillis() && p.start >= System.currentTimeMillis() - days * DAY_MS
}

/** A glass row: a neon bar on its left when highlighted (green) or picked (dim green). */
@Composable
private fun ListPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, onLongClick = onLongClick, modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x1A7CFF3A) else Color.Transparent, contentColor = FS.Text,
            focusedContainerColor = Color(0xFF12301B), focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.5.dp, FS.Green), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 8.dp)
                .background(if (focused) FS.Green else if (selected) FS.GreenDeep else Color.Transparent, RoundedCornerShape(2.dp)))
            content()
        }
    }
}

/** See-through dark panel with a thin neon edge. */
private fun Modifier.glass() = this
    .background(Color(0xB8050B07), RoundedCornerShape(20.dp))
    .border(1.dp, Color(0x337CFF3A), RoundedCornerShape(20.dp))

/** Strip above the lists: ON AIR · the playing channel · its show and time left · the clock. */
@Composable
private fun OnAirStrip(now: Long, modifier: Modifier) {
    val item = PlayerHolder.current
    val ch = item?.channelId?.let { Session.channelById(it) }
    val prog = ch?.let { Session.cachedEpg(it) }?.firstOrNull { it.isLive(now) }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "air").animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Row(modifier.fillMaxWidth().height(38.dp).glass().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val on = item?.live == true && ch != null
        Row(Modifier.background(if (on) Color(0xFFD62828) else Color(0xFF2A3A2E), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).alpha(if (on) pulse.value else 1f).background(Color.White, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(if (on) "ON AIR" else "OFF AIR", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
        Spacer(Modifier.width(14.dp))
        if (on && ch != null) {
            Text("${ch.num}", color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Text(ch.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            prog?.let { p ->
                Text("   ·   ${p.title}", color = FS.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("   ${((p.end - now) / 60_000).coerceAtLeast(0)} min left", color = FS.TextDim, fontSize = 13.sp)
            }
        } else {
            Text("Pick a channel — OK plays it behind the menu, OK again goes full screen", color = FS.TextDim, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        Text(Fmt.time(now), color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ChannelLine(c: LiveChannel, playing: Boolean, now: Long) {
    val prog by produceState(Session.cachedEpg(c)?.firstOrNull { it.isLive(now) }, c.id, now) {
        value = Session.epg(c).firstOrNull { it.isLive(System.currentTimeMillis()) }
    }
    Row(Modifier.fillMaxWidth().height(68.dp).padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        // LED-style channel number.
        Box(Modifier.width(58.dp).background(Color(0xFF020503), RoundedCornerShape(8.dp)).border(1.dp, Color(0x337CFF3A), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text("${c.num}", color = if (playing) Color(0xFFFF5A5A) else FS.Green, fontSize = 16.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(width = 76.dp, height = 44.dp).background(Color(0x14FFFFFF), RoundedCornerShape(8.dp)).padding(4.dp),
            contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (playing) {
                    Spacer(Modifier.width(8.dp))
                    Text("ON AIR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                        modifier = Modifier.background(Color(0xFFD62828), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            val p = prog
            Text(p?.title ?: "No information", color = FS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                val f = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.85f).height(3.dp).background(Color(0x22FFFFFF), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(f).height(3.dp).background(FS.Green, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (c.hasArchive) { Spacer(Modifier.width(10.dp)); GText("⟲", color = FS.TextDim, fontSize = 20.sp) }
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
    val day = remember(epg, dayOffset) {
        val real = epg.orEmpty().filter { it.end > from && it.start < from + DAY_MS }.sortedBy { it.start }
        // An earlier day with no guide info on a catch-up channel: offer it hour by hour so it can still be played back.
        if (real.isEmpty() && epg != null && dayOffset < 0 && ch.hasArchive)
            (0 until 24).map { h -> Program("${Fmt.time(from + h * 3_600_000L)} – ${Fmt.time(from + (h + 1) * 3_600_000L)}", "", from + h * 3_600_000L, from + (h + 1) * 3_600_000L, true) }
                .filter { canPlayBack(ch, it) }
        else real
    }
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
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
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
                        // Timeline: start time · a dot on a line (red = on now, grey = over, green ring = later) · the show.
                        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(Fmt.time(p.start), color = if (live) FS.Green else FS.TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(78.dp).padding(start = 8.dp))
                            Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0x337CFF3A)))
                                Box(Modifier.size(if (live) 12.dp else 9.dp).background(
                                    when { live -> Color(0xFFFF4040); past -> Color(0xFF5A6A5E); else -> Color(0xFF050B07) }, RoundedCornerShape(50))
                                    .border(2.dp, if (live) Color(0x66FF4040) else if (past) Color.Transparent else FS.Green, RoundedCornerShape(50)))
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp, top = 9.dp, bottom = 9.dp)) {
                                Text(p.title, color = if (live) FS.Green else if (past) FS.TextDim else Color.Unspecified, fontSize = 17.sp,
                                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (live) "On now · until ${Fmt.time(p.end)}" else "${((p.end - p.start) / 60_000)} min",
                                    color = if (live) Color(0xFFFF7070) else FS.TextDim, fontSize = 12.sp)
                            }
                            if (past && canPlayBack(ch, p)) Text("⟲ PLAYBACK", color = FS.Green, fontSize = 11.sp, fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp, modifier = Modifier.border(1.dp, FS.GreenDeep, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                            else if (!past && !live) Text("⏰ ⏺", fontSize = 13.sp, modifier = Modifier.alpha(0.6f))
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
    Column(Modifier.width(92.dp).fillMaxHeight().glass().padding(top = 10.dp, start = 4.dp, end = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("DAY", color = FS.GreenMid, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        for (d in -3..3) {
            val t = dayStartMs(d)
            ListPill(
                selected = d == selected, onClick = { onPick(d) },
                modifier = Modifier.width(82.dp)
                    .then(if (d == selected) Modifier.focusRequester(req) else Modifier)
                    .onFocusChanged { if (it.isFocused) onPick(d) }
                    .onPreviewKeyEvent { ev -> if (keyIs(ev, android.view.KeyEvent.KEYCODE_DPAD_LEFT)) { onLeft(); true } else false },
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (d == 0) "TODAY" else SimpleDateFormat("d", Locale.US).format(t), fontSize = if (d == 0) 14.sp else 18.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
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
