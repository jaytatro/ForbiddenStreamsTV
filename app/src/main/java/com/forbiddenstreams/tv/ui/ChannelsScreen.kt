package com.forbiddenstreams.tv.ui

import android.content.Context
import android.view.KeyEvent as AKeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.Category
import com.forbiddenstreams.tv.data.CategoryPrefs
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
import kotlinx.coroutines.launch
import java.util.Calendar

private const val DP_PER_MIN = 6f
private const val CH_COL = 210
private const val ROW_H = 62
/** Channel column of the TV Guide tab (Nov 16 photo layout). */
private const val GUIDE_CH_COL = 250
private const val HOUR = 3_600_000L
private const val MIN = 60_000L
private const val DAY = 24 * HOUR

sealed class Group(val key: String, val title: String) {
    object Search : Group("search", "🔍 Search")
    object All : Group("all", "All")
    object History : Group("history", "History")
    object Favorites : Group("fav", "Favorite List")
    class Cat(val cat: Category) : Group("c_" + cat.id, cat.name)
}

fun groupsFor(cats: List<Category>): List<Group> =
    listOf<Group>(Group.Search, Group.All, Group.History, Group.Favorites) + cats.distinctBy { it.id }.map { Group.Cat(it) }

fun channelsOf(prefs: Prefs, g: Group): List<LiveChannel> = channelsOfRaw(prefs, g).distinctBy { it.id }

private fun channelsOfRaw(prefs: Prefs, g: Group): List<LiveChannel> {
    // Channels in hidden categories are left out of All and Search too.
    val hidden = CategoryPrefs.excluded(prefs.context)
    val adult = CategoryPrefs.adultBlocked(prefs.context)
    val all = Session.liveAll.orEmpty().let { l -> if (hidden.isEmpty()) l else l.filter { it.categoryId !in hidden } }
        .let { l -> if (adult) l.filter { !CategoryPrefs.isAdult(it.name) } else l }
    return when (g) {
        Group.Search, Group.All -> all
        Group.History -> {
            val byId = all.associateBy { it.id }
            prefs.history.mapNotNull { byId[it] }
        }
        Group.Favorites -> {
            val f = prefs.favorites
            all.filter { it.id in f }
        }
        is Group.Cat -> Session.channelsIn(g.cat.id).let { l -> if (adult) l.filter { !CategoryPrefs.isAdult(it.name) } else l }
    }
}

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

fun playChannels(context: Context, list: List<LiveChannel>, index: Int, fullScreen: Boolean = true) {
    val now = System.currentTimeMillis()
    PlayerHolder.start(
        context,
        list.map { c ->
            PlayItem(c.name, c.url, live = true,
                subtitle = Session.cachedEpg(c)?.firstOrNull { it.isLive(now) }?.title,
                channelId = c.id)
        },
        index,
        fullScreen,
    )
}

// ------------------------------------------------------------ Channels tab

@Composable
fun ChannelsScreen(groupKey: String?, onGroup: (String?) -> Unit, active: Boolean = true, onCategories: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val cats = Session.liveCats
    var favTick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }

    // When the app opens: play the last channel in the preview (right side, middle).
    LaunchedEffect(Session.liveAll) {
        if (!Session.resumePending || Session.liveAll == null) return@LaunchedEffect
        Session.resumePending = false
        val last = prefs.history.firstOrNull()?.let { Session.channelById(it) } ?: return@LaunchedEffect
        val gk = prefs.lastGroup
        val g = groupsFor(Session.liveCats.orEmpty()).firstOrNull { it.key == gk && it != Group.Search } ?: Group.All
        val list = channelsOf(prefs, g).let { l -> if (l.any { it.id == last.id }) l else listOf(last) }
        if (PlayerHolder.current == null) playChannels(context, list, list.indexOfFirst { it.id == last.id }, fullScreen = false)
    }

    if (cats == null) {
        val err = Session.liveError
        if (err != null && !Session.refreshing) {
            MessageBox("Couldn't load channels", err, "Retry") { Session.refreshInBackground(context) }
        } else {
            LoadingBox("Loading channels")
        }
        return
    }
    val groups = remember(cats, CategoryPrefs.version) { groupsFor(CategoryPrefs.visible(context, cats)) }
    val group = groupKey?.let { k -> groups.firstOrNull { it.key == k } }

    fun toggleFav(ch: LiveChannel) {
        val added = prefs.toggleFavorite(ch.id)
        toast(context, if (added) "Added ${ch.name} to favorites" else "Removed ${ch.name} from favorites")
        favTick++
    }

    if (group == null) {
        // Nothing picked yet: the live preview, right side, middle of the screen.
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            if (PlayerHolder.isMiniActive) {
                MiniPlayer(820.dp, Modifier.align(Alignment.CenterEnd))
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(300.dp).alpha(0.3f))
                    Spacer(Modifier.height(12.dp))
                    Text("Pick a category to open its TV guide", color = FS.TextDim, fontSize = 20.sp)
                }
            }
            if (Session.refreshing) {
                Text("Updating playlist…", color = FS.TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        return
    }

    // A category was picked in the rail box: show only its TV guide.
    val list = remember(group.key, Session.liveAll, favTick) { channelsOf(prefs, group) }
    Column(Modifier.fillMaxSize()) {
        if (group == Group.Search) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    FsTextField(
                        "Search channels (type, then press Enter/Done)", query, { query = it },
                        androidx.compose.ui.text.input.KeyboardType.Text,
                        done = { submitted = query.trim() },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Pill("Search", selected = true, onClick = { submitted = query.trim() })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown = if (group == Group.Search) {
                if (submitted.length < 2) null else list.filter { it.name.contains(submitted, ignoreCase = true) }
            } else list
            when {
                shown == null -> MessageBox("Search all channels", "Type at least 2 letters of a channel name.")
                shown.isEmpty() -> MessageBox(
                    when (group) {
                        Group.Favorites -> "No favorites yet"
                        Group.History -> "Nothing watched yet"
                        Group.Search -> "No channels found"
                        else -> "No channels in this category"
                    },
                    if (group == Group.Favorites) "In the guide, hold OK on a show and pick Add to favorites." else "Press BACK for categories.",
                )
                else -> GuideView(
                    group = group,
                    channels = shown,
                    startId = PlayerHolder.current?.channelId,
                    active = active,
                    onCategories = onCategories,
                    leftOpensCategories = true,
                    onToggleFav = { toggleFav(it) },
                )
            }
        }
    }
}

@Composable
fun GroupList(
    groups: List<Group>,
    selected: Group,
    modifier: Modifier,
    onFocus: ((Group) -> Unit)? = null,
    textColor: Color? = null,
    onPick: (Group) -> Unit,
) {
    val sel = remember { FocusRequester() }
    val top = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState(groups.indexOfFirst { it.key == selected.key }.coerceAtLeast(0))
    LazyColumn(modifier.fillMaxHeight(), state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(groups, key = { _, g -> g.key }) { i, g ->
            val last = i == groups.size - 1
            SideItem(
                g.title, selected = g.key == selected.key, onClick = { onPick(g) },
                onFocus = { onFocus?.invoke(g) },
                textColor = textColor,
                modifier = Modifier.fillMaxWidth()
                    .then(if (g.key == selected.key) Modifier.focusRequester(sel) else Modifier)
                    .then(if (i == 0) Modifier.focusRequester(top) else Modifier)
                    .then(
                        // End of the list: ▼ goes back to the top.
                        if (last) Modifier.onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AKeyEvent.KEYCODE_DPAD_DOWN) {
                                scope.launch {
                                    state.scrollToItem(0)
                                    delay(60)
                                    try { top.requestFocus() } catch (e: Exception) { }
                                }
                                true
                            } else false
                        } else Modifier
                    ),
            )
        }
    }
    LaunchedEffect(Unit) { delay(150); try { sel.requestFocus() } catch (e: Exception) { } }
}

/** Slides in from the left over the guide when ◀ is pressed. */
@Composable
fun CategoryDrawer(groups: List<Group>, selected: Group, onPick: (Group) -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(400.dp).fillMaxHeight()
                .background(FS.Panel)
                .border(1.dp, FS.GreenDeep)
                .padding(12.dp),
        ) {
            Text("Categories", color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            GroupList(groups.filter { it != Group.Search } + Group.Search, selected, Modifier.weight(1f), onPick = onPick)
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xAA000000)))
    }
}

// ------------------------------------------------------------ TV guide

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

/** True if [time] is inside the channel's catch-up window. */
private fun inArchive(ch: LiveChannel, time: Long): Boolean {
    if (!ch.hasArchive) return false
    val days = if (ch.archiveDays > 0) ch.archiveDays else 7
    return time >= System.currentTimeMillis() - days * DAY
}

private fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, offset)
}.timeInMillis

private fun widthDp(ms: Long) = (ms / MIN.toFloat() * DP_PER_MIN).dp

/**
 * Remote control in the guide:
 *  ▲▼ = channel up/down, staying on the show at the time line
 *  hold ◀ ▶ = scroll through time (short ▶ does nothing)
 *  BACK = category list (handled by the Channels tab)
 *  OK = watch / catch-up / options, hold OK = options (record, remind, favorite)
 */
@Composable
fun GuideView(
    group: Group,
    channels: List<LiveChannel>,
    startId: String?,
    active: Boolean,
    onCategories: () -> Unit,
    onToggleFav: (LiveChannel) -> Unit,
    /** See-through version shown over the video: white outlines, words and the time line only. */
    overlay: Boolean = false,
    onPlayChannel: ((List<LiveChannel>, Int) -> Unit)? = null,
    onPlayItem: ((PlayItem) -> Unit)? = null,
    leftOpensCategories: Boolean = false,
    /** Lets the screen around the guide put the cursor in it. */
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val src = Session.source ?: return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val hState = rememberScrollState()
    val listState = rememberLazyListState()
    val focus = focusRequester ?: remember { FocusRequester() }
    val topMenu = LocalTopMenuFocus.current
    val previewReq = remember { FocusRequester() }
    var previewFocused by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dayOffset by remember(group.key) { mutableIntStateOf(0) } // a new category always opens on TODAY
    var row by remember(group.key) { mutableIntStateOf(channels.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    var cursor by remember(group.key) { mutableLongStateOf(System.currentTimeMillis()) }
    var followNow by remember(group.key) { mutableStateOf(true) }
    var epgTick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<Pair<LiveChannel, Program?>?>(null) }
    var gridWidthPx by remember { mutableIntStateOf(0) }

    // Key-press bookkeeping for short vs. long presses.
    var lrLong by remember { mutableStateOf(false) }
    var lastStep by remember { mutableLongStateOf(0L) }
    var okLong by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    var lrDown by remember { mutableStateOf(false) }
    var lrDownAt by remember { mutableLongStateOf(0L) }
    // ▲ on the top channel highlights the DATE strip: ◀ ▶ then change the day.
    var dateMode by remember(group.key) { mutableStateOf(false) }

    fun playCh(list: List<LiveChannel>, i: Int) =
        onPlayChannel?.invoke(list, i) ?: playChannels(context, list, i)
    fun playItem(item: PlayItem) = onPlayItem?.invoke(item) ?: Playback.one(context, item)

    // Each day's grid runs 9 PM the night before to 3 AM the next morning, so the
    // time line can sit in the middle even around midnight.
    val ws = remember(dayOffset) { dayStart(dayOffset) - 3 * HOUR }
    val we = ws + DAY + 6 * HOUR
    LaunchedEffect(channels.size) { if (row >= channels.size) row = (channels.size - 1).coerceAtLeast(0) }


    fun cellsOf(r: Int): List<Cell> = channels.getOrNull(r)?.let { buildCells(Session.cachedEpg(it), ws, we) } ?: emptyList()
    fun cellAt(r: Int): Cell? = cellsOf(r).firstOrNull { cursor >= it.start && cursor < it.end }

    fun px(ms: Long): Int = with(density) { widthDp(ms).toPx() }.toInt()

    /**
     * Scroll so [t] sits in the middle of the grid — or, in the Live TV tab (Nov 16, like the user's photo),
     * a little right of the channel names (15 %, Nov 16: "move the live channels and live line over a little more").
     */
    fun centerOn(t: Long): Int = (px(t - ws) - (gridWidthPx * (if (overlay) 0.5f else 0.15f)).toInt()).coerceAtLeast(0)

    LaunchedEffect(Unit) {
        while (true) {
            // Nov 15: the times stay where they are — only the time line moves along. When it gets near
            // the right edge, the guide moves over once (time line back to the left third).
            delay(5_000)
            now = System.currentTimeMillis()
            if (followNow && dayOffset == 0) {
                cursor = now
                if (gridWidthPx > 0) {
                    val x = px(now - ws) - hState.value
                    if (x < 0 || x > gridWidthPx * (if (overlay) 0.8f else 0.45f)) {
                        hState.animateScrollTo(centerOn(now))
                    }
                }
            }
        }
    }

    fun keepCursorVisible() {
        val viewport = gridWidthPx
        if (viewport <= 0) return
        val c = cellAt(row)
        val x = px((c?.start ?: cursor).coerceAtLeast(ws) - ws)
        val cx = px(cursor - ws)
        val left = hState.value
        if (x < left || cx > left + viewport - px(20 * MIN)) {
            scope.launch { hState.animateScrollTo(centerOn(cursor)) }
        }
    }

    fun keepRowVisible() {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) { scope.launch { listState.scrollToItem(row) }; return }
        val first = visible.first().index
        val lastFull = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: visible.last().index
        when {
            row < first -> scope.launch { listState.scrollToItem(row) }
            row > lastFull -> scope.launch { listState.scrollToItem((row - (lastFull - first)).coerceAtLeast(0)) }
        }
    }

    /** Nov 16: the show the cursor is on goes to the left, right against the channel names. */
    fun alignCursorLeft() {
        val start = (cellAt(row)?.start ?: cursor).coerceAtLeast(ws)
        scope.launch { try { hState.animateScrollTo(px(start - ws).coerceAtLeast(0)) } catch (e: Exception) { } }
    }

    fun step(dir: Int, alignLeft: Boolean = false) {
        val cells = cellsOf(row)
        val i = cells.indexOfFirst { cursor >= it.start && cursor < it.end }
        val j = i + dir
        when {
            j in cells.indices -> cursor = cells[j].start
            dir > 0 && dayOffset < 3 -> { dayOffset++; cursor = dayStart(dayOffset) + 3 * HOUR }
            dir < 0 && dayOffset > -3 -> { dayOffset--; cursor = dayStart(dayOffset) + DAY - 3 * HOUR }
            else -> return
        }
        val t = System.currentTimeMillis()
        followNow = dayOffset == 0 && cellAt(row)?.let { t >= it.start && t < it.end } == true
        if (followNow) cursor = t
        if (alignLeft) alignCursorLeft() else keepCursorVisible()
    }

    fun changeDay(d: Int) {
        val n = (dayOffset + d).coerceIn(-3, 3)
        if (n == dayOffset) return
        val t = System.currentTimeMillis()
        dayOffset = n
        // Same time of day on the new date (now, for today).
        cursor = if (n == 0) t else dayStart(n) + (t - dayStart(0))
        followNow = n == 0
    }

    fun openMenu() {
        val ch = channels.getOrNull(row) ?: return
        menu = ch to cellAt(row)?.program
    }

    fun click() {
        val ch = channels.getOrNull(row) ?: return
        val p = cellAt(row)?.program
        val t = System.currentTimeMillis()
        when {
            p == null && cellAt(row)?.let { it.end <= t } == true && inArchive(ch, cellAt(row)!!.start) -> {
                // Past hour with no guide info on a catch-up channel: play that hour.
                val c = cellAt(row)!!
                val prog = Program("${ch.name} · ${Fmt.time(c.start)}", "", c.start, c.end, true)
                val it = com.forbiddenstreams.tv.player.playbackItem(ch, prog)
                if (it != null) playItem(it)
                else toast(context, "Catch-up isn't available for this time")
            }
            p == null || p.isLive(t) -> playCh(channels, row)
            p.end <= t -> {
                val it = com.forbiddenstreams.tv.player.playbackItem(ch, p)
                if (it != null) playItem(it)
                else toast(context, "Catch-up isn't available for this show")
            }
            else -> openMenu()
        }
    }

    // First layout: jump to the time line and the starting channel, then take focus.
    LaunchedEffect(group.key, dayOffset) {
        snapshotFlow { hState.maxValue }.first { it > 0 }
        snapshotFlow { gridWidthPx }.first { it > 0 }
        hState.scrollTo(centerOn(cursor))
        if (channels.isNotEmpty()) listState.scrollToItem((row - 2).coerceAtLeast(0))
    }
    LaunchedEffect(active) {
        if (active) { delay(120); try { focus.requestFocus() } catch (e: Exception) { } }
    }

    val curCh = channels.getOrNull(row)
    val curProg = remember(row, cursor, epgTick, ws) { if (curCh != null) cellAt(row)?.program else null }

    // Nov 8: BACK in the guide → the live preview (top left); BACK again (handled by the app) → the tabs.
    var guideHasFocus by remember { mutableStateOf(false) }
    // Nov 16: BACK while the preview plays (top left) → the channel full screen.
    BackHandler(enabled = !overlay && guideHasFocus && !previewFocused && PlayerHolder.isMiniActive) {
        PlayerHolder.openFullScreen(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onFocusChanged { guideHasFocus = it.hasFocus }
            .onPreviewKeyEvent { ev ->
                val ne = ev.nativeKeyEvent
                val down = ev.type == KeyEventType.KeyDown
                if (previewFocused) {
                    // The preview has the cursor: ▼ goes back to the guide; OK etc. go to the preview.
                    if (ne.keyCode == AKeyEvent.KEYCODE_DPAD_DOWN) {
                        if (down) try { focus.requestFocus() } catch (e: Exception) { }
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (dateMode) {
                    when (ne.keyCode) {
                        AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (down) changeDay(if (ne.keyCode == AKeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                            return@onPreviewKeyEvent true
                        }
                        AKeyEvent.KEYCODE_DPAD_DOWN, AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (down) { dateMode = false; okDown = false }
                            return@onPreviewKeyEvent true
                        }
                        AKeyEvent.KEYCODE_DPAD_UP -> {
                            // Stay in the guide; only the channel search has a box above to go to.
                            if (group == Group.Search && down && ne.repeatCount == 0) { dateMode = false; return@onPreviewKeyEvent false }
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                when (ne.keyCode) {
                    AKeyEvent.KEYCODE_DPAD_UP -> {
                        // Held ▲ scrolls up through the channels and never leaves the guide.
                        // Still held at the top channel: the live preview (OK there = full screen), if one is playing.
                        if (down && ne.repeatCount > 0) {
                            if (row > 0) { row--; keepRowVisible() }
                            else if (ne.repeatCount >= 6 && PlayerHolder.isMiniActive && !overlay) {
                                try { previewReq.requestFocus() } catch (e: Exception) { }
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (row == 0) {
                            if (overlay) return@onPreviewKeyEvent true
                            if (down) dateMode = true
                            return@onPreviewKeyEvent true
                        }
                        if (down) { row--; keepRowVisible() }
                        true
                    }
                    AKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down && row < channels.size - 1) { row++; keepRowVisible() }
                        true
                    }
                    AKeyEvent.KEYCODE_CHANNEL_UP -> { if (down) { row = (row - 8).coerceAtLeast(0); keepRowVisible() }; true }
                    AKeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) { row = (row + 8).coerceAtMost(channels.size - 1); keepRowVisible() }; true }
                    AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val dir = if (ne.keyCode == AKeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        // Nov 16: ▶ = the next show, moved over to the left against the channel.
                        //         ◀ held for 1 second = one show back (catch-up); it steps once per hold —
                        //         let go and hold again for the next one. A short ◀ opens the categories.
                        if (down) {
                            if (ne.repeatCount == 0) {
                                lrLong = false
                                lrDown = true
                                lrDownAt = System.currentTimeMillis()
                            } else if (dir < 0) {
                                if (!lrLong && System.currentTimeMillis() - lrDownAt >= 1_000) { lrLong = true; step(-1, alignLeft = true) }
                            } else {
                                // ▶ held (Settings hold time): keeps going a show every 2 seconds.
                                val t = System.currentTimeMillis()
                                if (t - lrDownAt >= ButtonPrefs.holdMs && t - lastStep >= 2_000) { lrLong = true; lastStep = t; step(1, alignLeft = true) }
                            }
                        } else {
                            if (lrDown && !lrLong) {
                                if (dir < 0 && leftOpensCategories && (ButtonPrefs.leftOpensCategories || onPlayChannel != null)) onCategories()
                                else step(dir, alignLeft = true)
                            }
                            lrDown = false
                        }
                        true
                    }
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down) {
                            if (ne.repeatCount == 0) { okLong = false; okDown = true }
                            else if (okDown && !okLong) { okLong = true; openMenu() }
                        } else {
                            if (okDown && !okLong) click()
                            okDown = false
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (!overlay) GuideInfoPanel(curCh, curProg, previewReq, group.title, now) { previewFocused = it }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            // Nov 16 (user's photo): "‹ Mon, 16 Nov 2026" over the channels, "ON NOW" + bold times over the grid.
            if (overlay) TimeHeader("", ws, we, now, hState, true) { gridWidthPx = it }
            else GuideHeader(dayStart(dayOffset), dateMode, ws, we, now, hState) { gridWidthPx = it }
            // Nov 15: the TV Guide tab fits 5 channels in the space it has (the player's guide keeps its size).
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val rowH = if (overlay) ROW_H.dp else (maxHeight / 5).coerceIn(44.dp, 90.dp)
            LazyColumn(state = listState, userScrollEnabled = false) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                    GuideRow(
                        ch = ch, ws = ws, we = we, now = now, hState = hState,
                        cursor = if (i == row && active && !dateMode) cursor else null,
                        playing = PlayerHolder.current?.channelId == ch.id,
                        overlay = overlay,
                        onEpg = { epgTick++ },
                        rowH = rowH,
                    )
                }
            }
            }
        }
    }

    menu?.let { (ch, p) ->
        val t = System.currentTimeMillis()
        val opts = ArrayList<Pair<String, () -> Unit>>()
        val isFav = ch.id in Prefs(context).favorites
        opts += (if (isFav) "★  Remove from favorites" else "☆  Add to favorites") to { menu = null; onToggleFav(ch) }
        opts += "▶  Watch ${ch.name} live" to {
            menu = null
            playCh(channels, channels.indexOf(ch).coerceAtLeast(0))
        }
        if (p != null && p.end > t) opts += "⏺  Record" to {
            Recordings.schedule(context, ch, p)
            toast(context, if (p.start <= t) "Recording ${p.title}" else "Will record ${p.title} at ${Fmt.time(p.start)}")
            menu = null
        }
        if (p != null && p.start > t) opts += "⏰  Set reminder" to {
            Reminders.add(context, ch, p)
            toast(context, "Reminder set for ${Fmt.time(p.start)}")
            menu = null
        }
        if (p != null && p.start <= t) {
            val it0 = com.forbiddenstreams.tv.player.playbackItem(ch, p)
            if (it0 != null) opts += "⏮  Watch from start" to {
                menu = null
                playItem(it0)
            }
        }
        ChoiceDialog(
            title = p?.title ?: ch.name,
            subtitle = if (p != null) "${ch.name} · ${Fmt.day(p.start)} ${Fmt.range(p.start, p.end)}" +
                (if (p.description.isNotBlank()) "\n${p.description}" else "") else null,
            options = opts,
            onDismiss = { menu = null },
        )
    }
    LaunchedEffect(menu) { if (menu == null && active) { delay(80); try { focus.requestFocus() } catch (e: Exception) { } } }
}

/**
 * Nov 16 (user's photo): big live preview top-left; next to it the show — title, time, a progress bar with
 * "N Minutes Left" and the description; top-right two pills: the clock + date and the category ("Group: …").
 */
@Composable
private fun GuideInfoPanel(ch: LiveChannel?, p: Program?, previewReq: FocusRequester, groupTitle: String, now: Long, onPreviewFocus: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(170.dp).padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)) {
        Box(Modifier.width(290.dp).fillMaxHeight().background(Color.Black, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (PlayerHolder.isMiniActive) MiniPlayer(
                290.dp, focusable = true,
                cardModifier = Modifier.focusRequester(previewReq).onFocusChanged { onPreviewFocus(it.isFocused) },
            ) else Box(Modifier.size(width = 150.dp, height = 84.dp), contentAlignment = Alignment.Center) { NetImage(ch?.logo, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p?.title ?: ch?.name ?: "", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (p != null) {
                Text(Fmt.range(p.start, p.end), color = FS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (p.isLive(now)) Row(verticalAlignment = Alignment.CenterVertically) {
                    val f = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.width(150.dp).height(5.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(FS.Green, RoundedCornerShape(3.dp)))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${((p.end - now) / 60_000).coerceAtLeast(0)} Minutes Left", color = FS.TextDim, fontSize = 13.sp)
                }
                if (p.description.isNotBlank()) Text(p.description, color = FS.Text, fontSize = 15.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            } else if (ch != null) Text("No Information", color = FS.TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val pill = Modifier.background(Color(0x33000000), RoundedCornerShape(50)).border(1.dp, Color(0x557CFF3A), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 4.dp)
            Text("Group: $groupTitle", color = FS.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = pill.widthIn(max = 320.dp))
        }
    }
}

/** Header row (non-overlay guide): the date over the channels ("‹" = categories; ◀ ▶ in date mode), then the times. */
@Composable
private fun GuideHeader(day: Long, dateMode: Boolean, ws: Long, we: Long, now: Long, hState: ScrollState, onWidth: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(GUIDE_CH_COL.dp).fillMaxHeight()
            .then(if (dateMode) Modifier.background(FS.GreenDeep, RoundedCornerShape(50)).border(1.5.dp, FS.Green, RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (dateMode) "◀" else "‹", color = FS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(java.text.SimpleDateFormat("EEE, d MMM yyyy", java.util.Locale.US).format(java.util.Date(day)),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            if (dateMode) Text("▶", color = FS.Text, fontSize = 16.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { onWidth(it.width) }.horizontalScroll(hState, enabled = false)) {
            Box(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                var t = ws
                while (t < we) {
                    Text(Fmt.time(t), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(x = widthDp(t - ws) + 8.dp).align(Alignment.CenterStart))
                    t += 30 * MIN
                }
                if (now in ws until we) Text("LIVE", color = Color(0xFFFF4040), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                    modifier = Modifier.offset(x = (widthDp(now - ws) - 22.dp).coerceAtLeast(0.dp)).align(Alignment.BottomStart)
                        .background(Color(0xE6040604), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun TimeHeader(title: String, ws: Long, we: Long, now: Long, hState: ScrollState, overlay: Boolean, onWidth: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp).then(if (overlay) Modifier else Modifier.background(Color(0x14FFFFFF), RoundedCornerShape(8.dp))),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (overlay) title else title.uppercase(), color = FS.Green, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1,
            letterSpacing = if (overlay) 0.sp else 1.sp,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(CH_COL.dp).padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { onWidth(it.width) }.horizontalScroll(hState, enabled = false)) {
            Box(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                var t = ws
                while (t < we) {
                    Text(Fmt.time(t), color = if (overlay) FS.Text else FS.TextDim, fontSize = 13.sp,
                        fontFamily = if (overlay) null else androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.offset(x = widthDp(t - ws) + 6.dp).align(Alignment.CenterStart))
                    t += 30 * MIN
                }
                if (now in ws until we) {
                    Box(Modifier.offset(x = widthDp(now - ws)).width(3.dp).fillMaxHeight().background(if (overlay) FS.Green else Color(0xFFFF4040)))
                    if (!overlay) Text("NOW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.offset(x = widthDp(now - ws) + 4.dp).align(Alignment.TopStart)
                            .background(Color(0xFFD62828), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    ch: LiveChannel,
    ws: Long,
    we: Long,
    now: Long,
    hState: ScrollState,
    cursor: Long?,
    playing: Boolean,
    overlay: Boolean,
    onEpg: () -> Unit,
    rowH: androidx.compose.ui.unit.Dp = ROW_H.dp,
) {
    val epg by produceState(Session.cachedEpg(ch), ch.id, Session.guideVersion) { value = Session.epg(ch); onEpg() }
    val cells = remember(epg, ws) { buildCells(epg, ws, we) }
    if (!overlay) { PhotoGuideRow(ch, cells, epg == null, ws, we, now, hState, cursor, playing, rowH); return }
    val shape = RoundedCornerShape(4.dp)
    val rowSel = cursor != null
    val density = LocalDensity.current
    val scrolledDp = with(density) { hState.value.toDp() }
    val line = Color(0xCCFFFFFF)
    val cellShape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().height(rowH)) {
        Row(
            Modifier.width(CH_COL.dp).fillMaxHeight().padding(1.dp)
                .then(
                    if (overlay) Modifier.border(if (rowSel) 2.dp else 1.dp, if (rowSel) FS.Green else line, shape)
                    // The highlighted channel stands out: green fill with a bright green outline.
                    // Nov 13 look (same as Live TV): glass row, neon edge + bar when highlighted, LED channel number.
                    else Modifier.background(if (rowSel) Color(0xFF12301B) else Color(0x0FFFFFFF), cellShape)
                        .border(if (rowSel) 1.5.dp else 1.dp, if (rowSel) FS.Green else Color(0x1F7CFF3A), cellShape)
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (overlay) Text("${ch.num}", fontSize = 14.sp, color = FS.Text, modifier = Modifier.width(40.dp))
            else {
                Box(Modifier.width(3.dp).height(26.dp).background(if (rowSel) FS.Green else Color.Transparent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(44.dp).background(Color(0xFF020503), RoundedCornerShape(6.dp)).border(1.dp, Color(0x337CFF3A), RoundedCornerShape(6.dp))
                    .padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                    Text("${ch.num}", color = if (playing) Color(0xFFFF5A5A) else FS.Green, fontSize = 13.sp, fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 70.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                    NetImage(ch.logo, Modifier.fillMaxSize())
                }
                Text(ch.name, fontSize = 11.sp, color = FS.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playing) GText("▶", color = FS.Green, fontSize = 14.sp)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val isFav = remember(ch.id) { ch.id in com.forbiddenstreams.tv.data.Prefs(ctx).favorites }
            if (isFav) GText("★", color = FS.Green, fontSize = 14.sp)
            if (ch.hasArchive) GText("⟲", color = FS.Text, fontSize = 15.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hState, enabled = false)) {
            Row(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                cells.forEach { cell ->
                    val p = cell.program
                    val live = now >= cell.start && now < cell.end
                    val past = cell.end <= now
                    val sel = cursor != null && cursor >= cell.start && cursor < cell.end
                    // Keep the title on screen when the show started before the left edge.
                    val startDp = widthDp(cell.start - ws)
                    val endDp = widthDp(cell.end - ws)
                    val shift = if (startDp < scrolledDp && endDp > scrolledDp + 60.dp) scrolledDp - startDp else 0.dp
                    Row(
                        Modifier.width(widthDp(cell.end - cell.start)).fillMaxHeight().padding(1.dp)
                            .then(
                                if (overlay) Modifier.border(if (sel) 3.dp else 1.dp, if (sel) FS.Green else line, shape)
                                else Modifier.background(
                                    when { sel -> Color(0xFF12301B); live -> Color(0x1AFF4040); past -> Color(0x08FFFFFF); else -> Color(0x0FFFFFFF) }, cellShape)
                                    .border(if (sel) 1.5.dp else 1.dp, when { sel -> FS.Green; live -> Color(0x55FF4040); else -> Color(0x1A7CFF3A) }, cellShape)
                            )
                            .padding(start = 8.dp + shift, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val playingBack = PlayerHolder.current?.let { it.playbackOf == ch.id && it.progStart == cell.start } == true
                        if (playingBack) {
                            Text("▶ PLAYING ", color = FS.Green, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                        if (!overlay && live) {
                            Box(Modifier.size(7.dp).background(Color(0xFFFF4040), RoundedCornerShape(50)))
                            Spacer(Modifier.width(6.dp))
                        }
                        val catchup = past && inArchive(ch, cell.start) && (p == null || p.hasArchive || ch.hasArchive)
                        if (catchup) GText("⟲ ", color = FS.Green, fontSize = 13.sp)
                        Text(
                            p?.title ?: if (epg == null) "…" else if (catchup) "${ch.name} · ${Fmt.time(cell.start)}" else "No information",
                            color = if (sel) Color.White else if (!overlay && past) FS.TextDim else if (!overlay && live) FS.Green else FS.Text,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (!overlay && live) FontWeight.SemiBold else null,
                        )
                    }
                }
            }
            if (now in ws until we) {
                Box(Modifier.offset(x = widthDp(now - ws)).width(2.dp).fillMaxHeight().background(if (overlay) FS.Green else Color(0xCCFF4040)))
            }
        }
    }
}


/**
 * Nov 16 (user's photo): a TV Guide row — the channel as a rounded tile (logo, name, number, ⟲) and the shows
 * as rounded tiles with the title and the time underneath. The highlighted channel is filled green; the
 * highlighted show gets a green edge. Colors stay black / green.
 */
@Composable
private fun PhotoGuideRow(
    ch: LiveChannel, cells: List<Cell>, loading: Boolean, ws: Long, we: Long, now: Long, hState: ScrollState,
    cursor: Long?, playing: Boolean, rowH: androidx.compose.ui.unit.Dp,
) {
    val rowSel = cursor != null
    val density = LocalDensity.current
    val scrolledDp = with(density) { hState.value.toDp() }
    val tile = RoundedCornerShape(12.dp)
    // Nov 16: shows that are live are a steady red (no glow).
    Row(Modifier.fillMaxWidth().height(rowH).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(GUIDE_CH_COL.dp).fillMaxHeight().padding(end = 4.dp)
                .background(if (rowSel) FS.GreenDeep else Color(0x1FFFFFFF), tile)
                .border(if (rowSel) 2.dp else 1.dp, if (rowSel) FS.Green else Color(0x14FFFFFF), tile)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 64.dp, height = (rowH - 16.dp).coerceAtLeast(24.dp)), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            // Nov 16: what's on now is always readable, right under the channel name.
            Column(Modifier.weight(1f)) {
                Text(ch.name, color = if (rowSel) Color.White else FS.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val onNow = cells.firstOrNull { now >= it.start && now < it.end }?.program?.title
                if (onNow != null) Text(onNow, color = if (rowSel) Color.White else Color(0xFFFF6B6B), fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxHeight().padding(vertical = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ch.hasArchive) GText("⟲ ", color = FS.TextDim, fontSize = 11.sp)
                    Text("${ch.num}", color = if (playing) Color(0xFFFF5A5A) else FS.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (playing) GText("▶", color = if (rowSel) Color.White else FS.Green, fontSize = 14.sp)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hState, enabled = false)) {
            Row(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                cells.forEach { cell ->
                    val p = cell.program
                    val live = now >= cell.start && now < cell.end
                    val past = cell.end <= now
                    val sel = cursor != null && cursor >= cell.start && cursor < cell.end
                    val startDp = widthDp(cell.start - ws)
                    val endDp = widthDp(cell.end - ws)
                    val shift = if (startDp < scrolledDp && endDp > scrolledDp + 24.dp) scrolledDp - startDp else 0.dp
                    val catchup = past && inArchive(ch, cell.start) && (p == null || p.hasArchive || ch.hasArchive)
                    Column(
                        Modifier.width(widthDp(cell.end - cell.start)).fillMaxHeight().padding(horizontal = 2.dp)
                            .then(if (live) Modifier.background(Color(0x2EFF2A2A), tile) else Modifier)
                            .background(
                                when { sel && !live -> Color(0xFF173A22); sel -> Color(0x33FFFFFF); rowSel -> Color(0x26FFFFFF); else -> Color(0x12FFFFFF) }, tile)
                            .border(
                                when { live && sel -> 3.dp; live -> 2.dp; sel -> 2.dp; else -> 1.dp },
                                when { live -> Color(0xFFFF3B3B); sel -> FS.Green; else -> Color(0x10FFFFFF) }, tile)
                            .padding(start = 10.dp + shift, end = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val playingBack = PlayerHolder.current?.let { it.playbackOf == ch.id && it.progStart == cell.start } == true
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (playingBack) Text("▶ ", color = FS.Green, fontSize = 13.sp, fontWeight = FontWeight.Black)
                            if (catchup) GText("⟲ ", color = FS.Green, fontSize = 13.sp)
                            Text(
                                p?.title ?: if (loading) "…" else if (catchup) "${ch.name} · ${Fmt.time(cell.start)}" else "No Information",
                                color = if (sel) Color.White else if (past) FS.TextDim else Color.White,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (p != null && rowH >= 46.dp) Text(Fmt.range(p.start, p.end), color = FS.TextDim, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            if (now in ws until we) {
                Box(Modifier.offset(x = widthDp(now - ws)).width(2.dp).fillMaxHeight().background(Color(0xCCFF4040)))
            }
        }
    }
}
