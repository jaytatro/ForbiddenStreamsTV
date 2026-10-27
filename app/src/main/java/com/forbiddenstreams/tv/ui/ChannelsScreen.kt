package com.forbiddenstreams.tv.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.Category
import com.forbiddenstreams.tv.data.ContentSource
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Program
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import com.forbiddenstreams.tv.recording.Recordings
import com.forbiddenstreams.tv.reminders.Reminders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Calendar

private const val DP_PER_MIN = 7f
private const val CH_COL = 230
private const val ROW_H = 74
private const val HOUR = 3_600_000L
private const val MIN = 60_000L

sealed class Group(val key: String, val title: String) {
    object Search : Group("search", "🔍 Search")
    object All : Group("all", "All")
    object History : Group("history", "History")
    object Favorites : Group("fav", "Favorite List")
    class Cat(val cat: Category) : Group("c_" + cat.id, cat.name)
}

private suspend fun loadGroup(src: ContentSource, prefs: Prefs, g: Group): List<LiveChannel> = when (g) {
    Group.Search, Group.All -> src.allLiveChannels()
    Group.History -> {
        val all = src.allLiveChannels().associateBy { it.id }
        prefs.history.mapNotNull { all[it] }
    }
    Group.Favorites -> {
        val f = prefs.favorites
        src.allLiveChannels().filter { it.id in f }
    }
    is Group.Cat -> src.liveChannels(g.cat.id)
}

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

fun playChannels(context: Context, list: List<LiveChannel>, index: Int) {
    val now = System.currentTimeMillis()
    Playback.start(
        context,
        list.map { c ->
            PlayItem(c.name, c.url, live = true,
                subtitle = Session.cachedEpg(c)?.firstOrNull { it.isLive(now) }?.title,
                channelId = c.id)
        },
        index,
    )
}

@Composable
fun ChannelsScreen() {
    val src = Session.source ?: return
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var cats by remember { mutableStateOf<List<Category>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var groupKey by rememberSaveable { mutableStateOf("all") }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    var guideTarget by remember { mutableStateOf<String?>(null) }
    var favTick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        try { cats = src.liveCategories(); loadError = null } catch (e: Exception) { loadError = e.message ?: "Network error" }
    }
    val c = cats
    if (c == null) {
        if (loadError != null) MessageBox("Couldn't load channels", loadError, "Retry") { reload++ }
        else LoadingBox("Loading channels")
        return
    }
    val groups = remember(c) {
        listOf<Group>(Group.Search, Group.All, Group.History, Group.Favorites) + c.map { Group.Cat(it) }
    }
    val group = groups.firstOrNull { it.key == groupKey } ?: Group.All
    var channels by remember(group.key) { mutableStateOf<List<LiveChannel>?>(null) }
    LaunchedEffect(group.key, favTick) {
        delay(200) // wait while the cursor is still moving through the groups
        channels = try { loadGroup(src, prefs, group) } catch (e: Exception) { emptyList() }
    }
    val shown = channels?.let { list ->
        if (group == Group.Search) {
            if (query.trim().length < 2) emptyList() else list.filter { it.name.contains(query.trim(), ignoreCase = true) }
        } else list
    }

    fun toggleFav(ch: LiveChannel) {
        val added = prefs.toggleFavorite(ch.id)
        toast(context, if (added) "Added ${ch.name} to favorites" else "Removed ${ch.name} from favorites")
        favTick++
    }

    if (guideOpen && shown != null) {
        GuideView(
            group = group,
            channels = shown,
            targetId = guideTarget,
            onBack = { guideOpen = false },
            onToggleFav = { toggleFav(it) },
        )
        return
    }

    Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 8.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            MiniPlayer(300.dp, Modifier.padding(bottom = 10.dp))
            GroupSidebar(
                groups, group, Modifier.weight(1f),
                onFocus = { groupKey = it.key },
                onSelect = { g ->
                    groupKey = g.key
                    // Picking a group hides this list and opens its full-width TV guide.
                    if (g != Group.Search) { guideTarget = null; guideOpen = true }
                },
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            var focusedIndex by remember(group.key) { mutableIntStateOf(0) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.title, color = FS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("OK on a group = full TV guide   Hold OK = favorite   ▶ = guide", color = FS.TextDim, fontSize = 14.sp)
                Spacer(Modifier.width(24.dp))
                if (shown != null) Text("${if (shown.isEmpty()) 0 else focusedIndex + 1}/${shown.size}", color = FS.TextDim, fontSize = 18.sp)
            }
            if (group == Group.Search) {
                FsTextField("Type a channel name", query, { query = it }, androidx.compose.ui.text.input.KeyboardType.Text)
                Spacer(Modifier.height(8.dp))
            }
            when {
                shown == null -> LoadingBox()
                shown.isEmpty() -> MessageBox(
                    when (group) {
                        Group.Favorites -> "No favorites yet"
                        Group.History -> "Nothing watched yet"
                        Group.Search -> if (query.length < 2) "Search all channels" else "No channels found"
                        else -> "No channels in this group"
                    },
                    when (group) {
                        Group.Favorites -> "Hold OK on any channel to add it here."
                        else -> null
                    },
                )
                else -> {
                    val favs = remember(favTick) { prefs.favorites }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(shown, key = { _, ch -> ch.id }) { i, ch ->
                            ChannelRow(
                                ch = ch,
                                isFav = ch.id in favs,
                                onPlay = { playChannels(context, shown, i) },
                                onFav = { toggleFav(ch) },
                                onGuide = { guideTarget = ch.id; guideOpen = true },
                                onFocused = { focusedIndex = i },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSidebar(
    groups: List<Group>,
    selected: Group,
    modifier: Modifier,
    onFocus: (Group) -> Unit,
    onSelect: (Group) -> Unit,
) {
    LazyColumn(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(groups, key = { it.key }) { g ->
            SideItem(
                g.title, selected = g.key == selected.key, onClick = { onSelect(g) },
                onFocus = { onFocus(g) }, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    ch: LiveChannel,
    isFav: Boolean,
    onPlay: () -> Unit,
    onFav: () -> Unit,
    onGuide: () -> Unit,
    onFocused: () -> Unit,
) {
    val epg by produceState(Session.cachedEpg(ch), ch.id) { value = Session.epg(ch) }
    val now = System.currentTimeMillis()
    val current = epg?.firstOrNull { it.isLive(now) }
    FocusCard(
        onClick = onPlay,
        onLongClick = onFav,
        container = Color.Transparent,
        focusedScale = 1.01f,
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) { onGuide(); true } else false
            },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.num}", fontSize = 20.sp, modifier = Modifier.width(64.dp))
            Box(Modifier.size(width = 100.dp, height = 56.dp), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    current?.title ?: if (epg == null) "" else "No program info",
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (isFav) Text("★", color = FS.Green, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 8.dp))
            if (ch.hasArchive) Text("⟲", fontSize = 26.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Text("›", fontSize = 30.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- TV guide

private data class Cell(val start: Long, val end: Long, val program: Program?)

private fun buildCells(programs: List<Program>?, ws: Long, we: Long): List<Cell> {
    val out = ArrayList<Cell>()
    fun gaps(a: Long, b: Long) {
        var x = a
        while (x < b) {
            val next = minOf(b, (x / HOUR + 1) * HOUR)
            out += Cell(x, next, null)
            x = next
        }
    }
    var t = ws
    programs?.filter { it.end > ws && it.start < we }?.sortedBy { it.start }?.forEach { p ->
        val s = maxOf(p.start, t)
        val e = minOf(p.end, we)
        if (e <= s) return@forEach
        if (s > t) gaps(t, s)
        out += Cell(s, e, p)
        t = e
    }
    if (t < we) gaps(t, we)
    return out
}

private fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, offset)
}.timeInMillis

private fun widthDp(ms: Long) = (ms / MIN.toFloat() * DP_PER_MIN).dp

@Composable
private fun GuideView(
    group: Group,
    channels: List<LiveChannel>,
    targetId: String?,
    onBack: () -> Unit,
    onToggleFav: (LiveChannel) -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val src = Session.source ?: return
    val density = LocalDensity.current
    var dayOffset by remember { mutableIntStateOf(0) }
    val ws = remember(dayOffset) { dayStart(dayOffset) }
    val we = ws + 26 * HOUR
    val hState = rememberScrollState()
    val listState = rememberLazyListState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    var info by remember { mutableStateOf<Pair<LiveChannel, Program?>?>(null) }
    var menu by remember { mutableStateOf<Pair<LiveChannel, Program>?>(null) }
    val targetIndex = channels.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
    val nowFocus = remember { FocusRequester() }

    LaunchedEffect(dayOffset, channels) {
        snapshotFlow { hState.maxValue }.first { it > 0 }
        val anchor = if (dayOffset == 0) now - 30 * MIN else ws + 18 * HOUR
        hState.scrollTo(with(density) { widthDp(anchor - ws).toPx() }.toInt())
        if (dayOffset == 0 && channels.isNotEmpty()) {
            listState.scrollToItem(targetIndex)
            delay(300)
            try { nowFocus.requestFocus() } catch (e: Exception) { }
        }
    }

    fun clickProgram(ch: LiveChannel, index: Int, p: Program?) {
        val t = System.currentTimeMillis()
        when {
            p == null || p.isLive(t) -> playChannels(context, channels, index)
            p.end <= t -> {
                val u = src.catchupUrl(ch, p)
                if (u != null) Playback.one(context, PlayItem(p.title, u, live = false, subtitle = ch.name))
                else toast(context, "Catch-up isn't available for this show")
            }
            else -> { menu = ch to p }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GuideInfoPanel(info, now)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OK = watch   Hold OK = record / remind / start over   BACK = groups", color = FS.TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("DATE", color = FS.TextDim, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items((-3..3).toList()) { d ->
                    Pill(
                        if (d == 0) "TODAY" else Fmt.day(dayStart(d)).substringBefore(','),
                        selected = d == dayOffset, outlined = true, fontSize = 14.sp,
                        onClick = { dayOffset = d },
                    )
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp)) {
                TimeHeader(group.title, ws, we, now, hState)
                if (channels.isEmpty()) {
                    MessageBox("No channels in this group")
                } else {
                    LazyColumn(state = listState) {
                        itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                            GuideRow(
                                ch = ch, index = i, ws = ws, we = we, now = now, hState = hState,
                                nowFocus = if (i == targetIndex && dayOffset == 0) nowFocus else null,
                                onInfo = { c, p -> info = c to p },
                                onPlay = { playChannels(context, channels, i) },
                                onProgram = { p -> clickProgram(ch, i, p) },
                                onMenu = { p -> if (p != null) { menu = ch to p } else { onToggleFav(ch) } },
                            )
                        }
                    }
                }
            }
        }
    }

    menu?.let { (ch, p) ->
        val t = System.currentTimeMillis()
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (p.end > t) opts += "⏺  Record" to {
            Recordings.schedule(context, ch, p)
            toast(context, if (p.start <= t) "Recording ${p.title}" else "Will record ${p.title} at ${Fmt.time(p.start)}")
            menu = null
        }
        if (p.start > t) opts += "⏰  Set reminder" to {
            Reminders.add(context, ch, p)
            toast(context, "Reminder set for ${Fmt.time(p.start)}")
            menu = null
        }
        if (p.start <= t) {
            val u = src.catchupUrl(ch, p)
            if (u != null) opts += "⏮  Watch from start" to {
                menu = null
                Playback.one(context, PlayItem(p.title, u, live = false, subtitle = ch.name))
            }
        }
        opts += "▶  Watch ${ch.name} live" to {
            menu = null
            playChannels(context, channels, channels.indexOf(ch).coerceAtLeast(0))
        }
        opts += "★  Add / remove favorite" to { menu = null; onToggleFav(ch) }
        ChoiceDialog(
            title = p.title,
            subtitle = "${ch.name} · ${Fmt.day(p.start)} ${Fmt.range(p.start, p.end)}" +
                if (p.description.isNotBlank()) "\n${p.description}" else "",
            options = opts,
            onDismiss = { menu = null },
        )
    }
}

@Composable
private fun GuideInfoPanel(info: Pair<LiveChannel, Program?>?, now: Long) {
    val mini = PlayerHolder.isMiniActive
    Row(
        Modifier.fillMaxWidth().height(if (mini) 180.dp else 140.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ch = info?.first
        val p = info?.second
        if (mini) {
            MiniPlayer(272.dp)
        } else {
            Box(Modifier.size(width = 170.dp, height = 100.dp), contentAlignment = Alignment.Center) {
                NetImage(ch?.logo, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(ch?.name ?: "TV Guide", color = FS.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (p != null) {
                Row {
                    Text("Duration: ${Fmt.range(p.start, p.end)}", color = FS.Text, fontSize = 17.sp)
                    Spacer(Modifier.width(32.dp))
                    Text("Program: ${p.title}", color = FS.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(p.description, color = FS.TextDim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (ch != null) {
                Text("No program information", color = FS.TextDim, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TimeHeader(title: String, ws: Long, we: Long, now: Long, hState: ScrollState) {
    Row(Modifier.fillMaxWidth().height(36.dp).background(FS.Panel2), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = FS.Green, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(CH_COL.dp).padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hState)) {
            Box(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                var t = ws
                while (t < we) {
                    Text(Fmt.time(t), color = FS.Text, fontSize = 15.sp,
                        modifier = Modifier.offset(x = widthDp(t - ws) + 6.dp).align(Alignment.CenterStart))
                    t += 30 * MIN
                }
                if (now in ws until we) {
                    Box(Modifier.offset(x = widthDp(now - ws)).width(3.dp).fillMaxHeight().background(FS.Green))
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: LiveChannel,
    index: Int,
    ws: Long,
    we: Long,
    now: Long,
    hState: ScrollState,
    nowFocus: FocusRequester?,
    onInfo: (LiveChannel, Program?) -> Unit,
    onPlay: () -> Unit,
    onProgram: (Program?) -> Unit,
    onMenu: (Program?) -> Unit,
) {
    val epg by produceState(Session.cachedEpg(ch), ch.id) { value = Session.epg(ch) }
    val cells = remember(epg, ws) { buildCells(epg, ws, we) }
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.fillMaxWidth().height(ROW_H.dp)) {
        FocusCard(
            onClick = onPlay,
            onLongClick = { onMenu(null) },
            shape = shape,
            focusedScale = 1f,
            modifier = Modifier.width(CH_COL.dp).fillMaxHeight().padding(1.dp)
                .onFocusChanged { if (it.isFocused) onInfo(ch, epg?.firstOrNull { p -> p.isLive(now) }) },
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.num}", fontSize = 16.sp, modifier = Modifier.width(44.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(width = 80.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                        NetImage(ch.logo, Modifier.fillMaxSize())
                    }
                    Text(ch.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (ch.hasArchive) Text("⟲", fontSize = 18.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hState)) {
            Row(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                cells.forEach { cell ->
                    val p = cell.program
                    val live = now >= cell.start && now < cell.end
                    val past = cell.end <= now
                    val req = if (live && nowFocus != null) Modifier.focusRequester(nowFocus) else Modifier
                    FocusCard(
                        onClick = { onProgram(p) },
                        onLongClick = { onMenu(p) },
                        shape = shape,
                        focusedScale = 1f,
                        container = if (live) FS.Panel2 else FS.Panel,
                        modifier = Modifier.width(widthDp(cell.end - cell.start)).fillMaxHeight().padding(1.dp)
                            .then(req)
                            .onFocusChanged { if (it.isFocused) onInfo(ch, p) },
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (p != null && past && p.hasArchive) Text("⟲ ", color = FS.Green, fontSize = 15.sp)
                            Text(
                                p?.title ?: if (epg == null) "…" else "No information",
                                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (now in ws until we) {
                Box(Modifier.offset(x = widthDp(now - ws)).width(2.dp).fillMaxHeight().background(FS.Green))
            }
        }
    }
}
