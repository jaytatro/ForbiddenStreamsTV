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
    val all = Session.liveAll.orEmpty().let { l -> if (hidden.isEmpty()) l else l.filter { it.categoryId !in hidden } }
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
        is Group.Cat -> Session.channelsIn(g.cat.id)
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
) {
    val context = LocalContext.current
    val src = Session.source ?: return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val hState = rememberScrollState()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val topMenu = LocalTopMenuFocus.current
    val previewReq = remember { FocusRequester() }
    var previewFocused by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var row by remember(group.key) { mutableIntStateOf(channels.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    var cursor by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var followNow by remember { mutableStateOf(true) }
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
    var dateMode by remember { mutableStateOf(false) }

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

    /** Scroll so [t] sits in the middle of the grid. */
    fun centerOn(t: Long): Int = (px(t - ws) - (gridWidthPx * 0.5f).toInt()).coerceAtLeast(0)

    LaunchedEffect(Unit) {
        while (true) {
            // Every 5 s: the time line stays in the middle and the shows slide slowly left.
            delay(5_000)
            now = System.currentTimeMillis()
            if (followNow && dayOffset == 0) {
                cursor = now
                if (gridWidthPx > 0) hState.animateScrollTo(centerOn(now), androidx.compose.animation.core.tween(4_000, easing = androidx.compose.animation.core.LinearEasing))
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

    fun step(dir: Int) {
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
        keepCursorVisible()
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
                val u = src.catchupUrl(ch, prog)
                if (u != null) playItem(PlayItem(prog.title, u, live = false, subtitle = ch.name))
                else toast(context, "Catch-up isn't available for this time")
            }
            p == null || p.isLive(t) -> playCh(channels, row)
            p.end <= t -> {
                val u = src.catchupUrl(ch, p)
                if (u != null) playItem(PlayItem(p.title, u, live = false, subtitle = ch.name))
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

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
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
                            if (down) dateMode = false
                            return@onPreviewKeyEvent false // on up to the search box / rail
                        }
                        else -> {}
                    }
                }
                when (ne.keyCode) {
                    AKeyEvent.KEYCODE_DPAD_UP -> {
                        // Held ▲ = go to the live preview (OK there = full screen); without a preview, to the rail.
                        if (down && ne.repeatCount >= 2) {
                            val target = if (PlayerHolder.isMiniActive && !overlay) previewReq else topMenu
                            try { target?.requestFocus() } catch (e: Exception) { }
                            return@onPreviewKeyEvent true
                        }
                        if (row == 0) {
                            if (overlay) return@onPreviewKeyEvent false
                            if (down && ne.repeatCount == 0) dateMode = true
                            return@onPreviewKeyEvent true
                        }
                        if (down && ne.repeatCount == 0) { row--; keepRowVisible() }
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
                        if (down) {
                            if (ne.repeatCount == 0) {
                                lrLong = false
                                lrDown = true
                                lrDownAt = System.currentTimeMillis()
                            } else {
                                // Held down for the set time (Settings › Buttons, 2 s by default):
                                // scroll through time, a few steps per second.
                                lrLong = true
                                val t = System.currentTimeMillis()
                                if (t - lrDownAt >= ButtonPrefs.holdMs && t - lastStep > 160) { lastStep = t; step(dir) }
                            }
                        } else {
                            if (lrDown && !lrLong) {
                                // Short press: ◀ = back to the categories (or one show back, see Settings › Buttons); ▶ = next show.
                                if (dir < 0 && leftOpensCategories && (ButtonPrefs.leftOpensCategories || onPlayChannel != null)) onCategories()
                                else step(dir)
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
        if (!overlay) GuideInfoPanel(curCh, curProg, previewReq) { previewFocused = it }
        // Day strip (moves by itself when you hold ◀ ▶ past midnight)
        if (!overlay) Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (dateMode) "◀ ▶ DAY" else "DATE", color = if (dateMode) FS.Green else FS.TextDim, fontSize = 14.sp,
                fontWeight = if (dateMode) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(end = 12.dp))
            // The 7 days spread evenly across the full width.
            for (d in -3..3) {
                val sel = d == dayOffset
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (d == 0) "TODAY" else Fmt.day(dayStart(d)),
                        color = if (sel && dateMode) androidx.compose.ui.graphics.Color.Black else if (sel) FS.Green else FS.TextDim,
                        fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .then(if (sel && dateMode) Modifier.background(FS.Green, RoundedCornerShape(50)) else Modifier)
                            .then(if (sel) Modifier.border(2.dp, FS.Green, RoundedCornerShape(50)) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            TimeHeader(if (overlay) "" else group.title, ws, we, now, hState, overlay) { gridWidthPx = it }
            LazyColumn(state = listState, userScrollEnabled = false) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                    GuideRow(
                        ch = ch, ws = ws, we = we, now = now, hState = hState,
                        cursor = if (i == row && active && !dateMode) cursor else null,
                        playing = PlayerHolder.current?.channelId == ch.id,
                        overlay = overlay,
                        onEpg = { epgTick++ },
                    )
                }
            }
        }
    }

    menu?.let { (ch, p) ->
        val t = System.currentTimeMillis()
        val opts = ArrayList<Pair<String, () -> Unit>>()
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
            val u = src.catchupUrl(ch, p)
            if (u != null) opts += "⏮  Watch from start" to {
                menu = null
                playItem(PlayItem(p.title, u, live = false, subtitle = ch.name))
            }
        }
        val fav = ch.id in Prefs(context).favorites
        opts += (if (fav) "★  Remove from favorites" else "☆  Add to favorites") to { menu = null; onToggleFav(ch) }
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

@Composable
private fun GuideInfoPanel(ch: LiveChannel?, p: Program?, previewReq: FocusRequester, onPreviewFocus: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 150.dp, height = 84.dp), contentAlignment = Alignment.Center) {
            NetImage(ch?.logo, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(ch?.name ?: "TV Guide", color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (p != null) {
                Row {
                    Text("Duration: ${Fmt.range(p.start, p.end)}", color = FS.Text, fontSize = 15.sp)
                    Spacer(Modifier.width(28.dp))
                    Text("Program: ${p.title}", color = FS.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(p.description, color = FS.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (ch != null) {
                Text("No program information", color = FS.TextDim, fontSize = 14.sp)
            }
        }
        // Live preview, top-right.
        MiniPlayer(
            210.dp, focusable = true,
            cardModifier = Modifier.focusRequester(previewReq).onFocusChanged { onPreviewFocus(it.isFocused) },
        )
    }
}

@Composable
private fun TimeHeader(title: String, ws: Long, we: Long, now: Long, hState: ScrollState, overlay: Boolean, onWidth: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp).background(if (overlay) Color.Transparent else FS.Panel2), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(CH_COL.dp).padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { onWidth(it.width) }.horizontalScroll(hState, enabled = false)) {
            Box(Modifier.width(widthDp(we - ws)).fillMaxHeight()) {
                var t = ws
                while (t < we) {
                    Text(Fmt.time(t), color = FS.Text, fontSize = 13.sp,
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
    ws: Long,
    we: Long,
    now: Long,
    hState: ScrollState,
    cursor: Long?,
    playing: Boolean,
    overlay: Boolean,
    onEpg: () -> Unit,
) {
    val epg by produceState(Session.cachedEpg(ch), ch.id) { value = Session.epg(ch); onEpg() }
    val cells = remember(epg, ws) { buildCells(epg, ws, we) }
    val shape = RoundedCornerShape(4.dp)
    val rowSel = cursor != null
    val density = LocalDensity.current
    val scrolledDp = with(density) { hState.value.toDp() }
    val line = Color(0xCCFFFFFF)
    Row(Modifier.fillMaxWidth().height(ROW_H.dp)) {
        Row(
            Modifier.width(CH_COL.dp).fillMaxHeight().padding(1.dp)
                .then(
                    if (overlay) Modifier.border(if (rowSel) 2.dp else 1.dp, if (rowSel) FS.Green else line, shape)
                    else Modifier.background(if (rowSel) FS.GreenDim else FS.Panel, shape)
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${ch.num}", fontSize = 14.sp, color = FS.Text, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 70.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                    NetImage(ch.logo, Modifier.fillMaxSize())
                }
                Text(ch.name, fontSize = 11.sp, color = FS.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playing) Text("▶", color = FS.Green, fontSize = 14.sp)
            if (ch.hasArchive) Text("⟲", color = FS.Text, fontSize = 15.sp)
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
                                else Modifier.background(if (sel) FS.GreenDeep else if (live) FS.Panel2 else FS.Panel, shape)
                                    .then(if (sel) Modifier.border(2.dp, FS.Green, shape) else Modifier)
                            )
                            .padding(start = 8.dp + shift, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val catchup = past && inArchive(ch, cell.start) && (p == null || p.hasArchive || ch.hasArchive)
                        if (catchup) Text("⟲ ", color = FS.Green, fontSize = 13.sp)
                        Text(
                            p?.title ?: if (epg == null) "…" else if (catchup) "${ch.name} · ${Fmt.time(cell.start)}" else "No information",
                            color = if (sel) Color.White else FS.Text,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (now in ws until we) {
                Box(Modifier.offset(x = widthDp(now - ws)).width(2.dp).fillMaxHeight().background(FS.Green))
            }
        }
    }
}
