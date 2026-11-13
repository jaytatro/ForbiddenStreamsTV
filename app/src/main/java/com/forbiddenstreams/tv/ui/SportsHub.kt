package com.forbiddenstreams.tv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.sports.Espn
import com.forbiddenstreams.tv.sports.FavTeams
import com.forbiddenstreams.tv.sports.Game
import com.forbiddenstreams.tv.sports.League
import com.forbiddenstreams.tv.sports.SPORTS_ORDER
import com.forbiddenstreams.tv.sports.SportOrder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val Led = Color(0xFF9CFF5A)
private val Panel = Color(0xFF0B120C)
private val PanelHi = Color(0xFF13201A)
private val LiveRed = Color(0xFFFF3B3B)
private val Gold = Color(0xFFFFC83D)

private fun dayKeyOffset(d: Int): String =
    Espn.dayKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, d) }.timeInMillis)

/**
 * Sports tab, redesigned Nov 13 2026 ("make it unique") — a stadium scoreboard:
 *  - a live-score ticker running across the top (every league),
 *  - the leagues down the left like a scoreboard rail with live / today counts,
 *  - the picked league's games as LED-style scoreboard cards (2 across), with a compact 7-day date bar.
 * OK on a game = the channels it's on. Favorite teams' games first (gold star), live games glow red.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SportsTab(open: Pair<League, String>? = null, onOpened: () -> Unit = {}, enter: Int = 0) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("forbidden_streams", android.content.Context.MODE_PRIVATE) }
    var leagueName by rememberSaveable { mutableStateOf(sp.getString("sports_league", null) ?: SPORTS_ORDER.first().name) }
    val league = League.entries.firstOrNull { it.name == leagueName } ?: SPORTS_ORDER.first()
    val scope = rememberCoroutineScope()
    val favV = FavTeams.version

    // Today's games for every league (ticker + rail counts), refreshed every minute.
    val today by produceState<Map<League, List<Game>>>(emptyMap()) {
        while (true) {
            value = try {
                coroutineScope {
                    SPORTS_ORDER.map { l -> async { l to (try { Espn.week(l, 0, 0).values.flatten() } catch (e: Exception) { emptyList() }) } }
                        .awaitAll().toMap()
                }
            } catch (e: Exception) { value }
            delay(60_000)
        }
    }
    // The picked league's week (3 days back, today, 3 ahead), refreshed every 30 s.
    var day by remember(league) { mutableStateOf(dayKeyOffset(0)) }
    val week by produceState<Map<String, List<Game>>?>(null, league) {
        value = null
        while (true) {
            value = try { Espn.week(league) } catch (e: Exception) { value ?: emptyMap() }
            delay(30_000)
        }
    }
    LaunchedEffect(open) { open?.let { leagueName = it.first.name } }

    var pick by remember { mutableStateOf<Pair<League, Game>?>(null) }
    LaunchedEffect(open, week) {
        val o = open ?: return@LaunchedEffect
        val g = week?.values?.flatten()?.firstOrNull { it.id == o.second } ?: return@LaunchedEffect
        day = Espn.dayKey(g.start); pick = o.first to g; onOpened()
    }

    val railReq = remember { SPORTS_ORDER.associateWith { FocusRequester() } }
    // Your own sport order (hold OK on a sport → ▲▼ to move → OK to set). Home follows it.
    val order = remember { androidx.compose.runtime.mutableStateListOf<League>().apply { addAll(SportOrder.get(context)) } }
    var moving by remember { mutableStateOf<League?>(null) }
    var heldFromLong by remember { mutableStateOf(false) }
    var swallowUp by remember { mutableStateOf(false) }
    fun setOrder() { SportOrder.set(context, order.toList()); moving = null }
    val firstGameReq = remember { FocusRequester() }
    fun focusRail() { scope.launch { repeat(6) { delay(50); try { railReq[league]?.requestFocus(); return@launch } catch (e: Exception) { } } } }
    fun focusGames(): Boolean = try { firstGameReq.requestFocus(); true } catch (e: Exception) { false }

    // Highlighting the tab doesn't move the cursor; ▼ (or OK on the tab) goes to the league rail.
    val firstEnter = remember { enter }
    LaunchedEffect(enter) { if (enter != firstEnter) focusRail() }
    val goIn: () -> Boolean = { try { railReq[league]?.requestFocus(); true } catch (e: Exception) { false } }
    DisposableEffect(Unit) {
        TabDown.handler = goIn
        onDispose { if (TabDown.handler === goIn) TabDown.handler = null }
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050A06), Color.Black))).padding(top = 76.dp)) {
        Ticker(today, order)
        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            // ---- League rail ----
            LazyColumn(
                Modifier.width(250.dp).fillMaxHeight(),
                contentPadding = PaddingValues(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(order, key = { _, l -> l.name }) { idx, l ->
                    val games = today[l].orEmpty()
                    RailItem(
                        l, selected = l == league,
                        live = games.count { it.isLive }, total = games.size,
                        moving = moving == l,
                        modifier = Modifier.animateItem().focusRequester(railReq.getValue(l)).onPreviewKeyEvent { ev ->
                            val ne = ev.nativeKeyEvent
                            val ok = ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || ne.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                ne.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                            if (moving == l) {
                                // Move mode: ▲▼ moves this sport, OK (a fresh press) sets it where it is.
                                if (ne.action == android.view.KeyEvent.ACTION_DOWN) {
                                    when {
                                        ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                            val i = order.indexOf(l); if (i > 0) { order.removeAt(i); order.add(i - 1, l) }
                                            scope.launch { delay(30); try { railReq[l]?.requestFocus() } catch (e: Exception) { } }
                                        }
                                        ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            val i = order.indexOf(l); if (i in 0 until order.size - 1) { order.removeAt(i); order.add(i + 1, l) }
                                            scope.launch { delay(30); try { railReq[l]?.requestFocus() } catch (e: Exception) { } }
                                        }
                                        ok && ne.repeatCount == 0 && !heldFromLong -> { setOrder(); swallowUp = true }
                                        ne.keyCode == android.view.KeyEvent.KEYCODE_BACK -> setOrder()
                                    }
                                    true
                                } else {
                                    if (ok && heldFromLong) heldFromLong = false
                                    if (ok && swallowUp) swallowUp = false
                                    ok || ne.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                                        ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                }
                            } else if (ok && swallowUp && ne.action == android.view.KeyEvent.ACTION_UP) { swallowUp = false; true }
                            else if (ne.action == android.view.KeyEvent.ACTION_DOWN && ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) focusGames()
                            else false
                        },
                        onFocus = {
                            if (moving == null && leagueName != l.name) { leagueName = l.name; sp.edit().putString("sports_league", l.name).apply() }
                        },
                        onClick = { if (moving == null) focusGames() },
                        onLongClick = { if (moving == null) { moving = l; heldFromLong = true } },
                    )
                }
            }
            // ---- Scoreboard ----
            Column(Modifier.fillMaxSize().padding(end = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Text(league.icon, fontSize = 30.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(league.label.uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.width(14.dp))
                    val dg = week?.get(day).orEmpty()
                    val lv = dg.count { it.isLive }
                    Text(
                        listOfNotNull(if (lv > 0) "$lv LIVE" else null, "${dg.size} game${if (dg.size == 1) "" else "s"}").joinToString("  ·  "),
                        color = if (lv > 0) LiveRed else FS.TextDim, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                if (moving != null) Text("Moving ${moving!!.icon} ${railName(moving!!)}  —  ▲▼ to move it, OK to set it here",
                    color = Color(0xFFFFC83D), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                DateBar(day, week, onPick = { day = it })
                val games = week?.get(day).orEmpty()
                val sorted = remember(games, favV) {
                    games.distinctBy { it.id }.sortedWith(compareBy(
                        { if (FavTeams.inGame(context, league, it)) 0 else 1 },
                        { if (it.isLive) 0 else if (it.isFinal) 2 else 1 }, { it.start },
                    ))
                }
                when {
                    week == null -> LoadingBox("Loading ${league.label}")
                    sorted.isEmpty() -> MessageBox(if (day == dayKeyOffset(0)) "No games today" else "No games this day",
                        "Pick another day above, or another league on the left.")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(sorted.size, key = { sorted[it].id }) { i ->
                            val g = sorted[i]
                            ScoreCard(
                                league, g, fav = FavTeams.inGame(context, league, g),
                                modifier = Modifier
                                    .then(if (i == 0) Modifier.focusRequester(firstGameReq) else Modifier)
                                    .onPreviewKeyEvent { ev ->
                                        // ◀ on the left column → back to the league rail.
                                        if (i % 2 == 0 && ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                            ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { focusRail(); true } else false
                                    },
                                onClick = { pick = league to g },
                            )
                        }
                    }
                }
            }
        }
    }
    pick?.let { (l, g) -> GameChannelsDialog(l, g, onClose = { pick = null }) }
}

/** A thin scoreboard ticker of every game being played right now, scrolling right to left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Ticker(today: Map<League, List<Game>>, order: List<League>) {
    val live = order.flatMap { l -> today[l].orEmpty().filter { it.isLive }.map { l to it } }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF070D08))
            .border(BorderStroke(1.dp, Color(0xFF16301C))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().background(if (live.isNotEmpty()) LiveRed else FS.GreenDeep).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).alpha(if (live.isNotEmpty()) pulse.value else 1f).background(Color.White, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(if (live.isNotEmpty()) "LIVE" else "SCORES", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
        val text = if (live.isEmpty()) {
            val next = SPORTS_ORDER.flatMap { l -> today[l].orEmpty().filter { !it.isFinal && !it.isLive }.map { l to it } }.minByOrNull { it.second.start }
            next?.let { (l, g) -> "No games live right now   ·   Next up: ${l.icon} ${g.away.abbreviation} @ ${g.home.abbreviation}  ${Fmt.time(g.start)}" }
                ?: "No games live right now"
        } else live.joinToString("        ") { (l, g) ->
            "${l.icon} ${g.away.abbreviation} ${g.away.score ?: 0}  –  ${g.home.score ?: 0} ${g.home.abbreviation}   ${g.detail}"
        }
        Text(
            text, color = Led, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
                .then(if (live.isNotEmpty()) Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp) else Modifier),
        )
    }
}

/** One league on the rail: icon, name, and today's live / total counts. */
@Composable
private fun RailItem(l: League, selected: Boolean, live: Int, total: Int, moving: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(50.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PanelHi else Color.Transparent, contentColor = if (selected) FS.Green else FS.Text,
            focusedContainerColor = if (moving) Color(0xFFFFC83D) else FS.Green, focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, FS.GreenDeep), shape = shape) else Border.None,
            focusedBorder = if (moving) Border(BorderStroke(2.dp, Color.White), shape = shape) else Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // A thin stripe marks the league being shown.
            Box(Modifier.width(4.dp).height(26.dp).background(if (selected) FS.Green else Color.Transparent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Text(l.icon, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(railName(l), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (moving) Text("▲▼", fontSize = 15.sp, fontWeight = FontWeight.Black)
            if (!moving && live > 0) {
                Text("$live", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(LiveRed, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
            }
            if (!moving && total > 0) Text("$total", fontSize = 13.sp, modifier = Modifier.alpha(0.7f))
        }
    }
}

private fun railName(l: League) = when (l) {
    League.NCAAB -> "NCAA Men's BB"
    League.NCAAW -> "NCAA Women's BB"
    League.NCAABASE -> "NCAA Baseball"
    else -> l.label
}

/** 7 days (3 back · TODAY · 3 ahead) as compact chips with game counts. */
@Composable
private fun DateBar(day: String, week: Map<String, List<Game>>?, onPick: (String) -> Unit) {
    val days = remember { (-3..3).map { it to dayKeyOffset(it) } }
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        itemsIndexed(days, key = { _, d -> d.second }) { _, (off, key) ->
            val n = week?.get(key)?.size ?: 0
            val label = when (off) {
                0 -> "TODAY"; -1 -> "YESTERDAY"; 1 -> "TOMORROW"
                else -> try { SimpleDateFormat("EEE M/d", Locale.US).format(SimpleDateFormat("yyyyMMdd", Locale.US).parse(key)!!).uppercase(Locale.US) } catch (e: Exception) { key }
            }
            val shape = RoundedCornerShape(50)
            val sel = key == day
            Surface(
                onClick = { onPick(key) },
                modifier = Modifier.onFocusChanged { if (it.isFocused) onPick(key) },
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (sel) FS.GreenDeep else Panel, contentColor = if (sel) Color.White else FS.TextDim,
                    focusedContainerColor = FS.Green, focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    if (n > 0) { Spacer(Modifier.width(6.dp)); Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.alpha(0.75f)) }
                }
            }
        }
    }
}

/** A game as a scoreboard card: logos and abbreviations on the sides, LED score (or start time) in the middle. */
@Composable
private fun ScoreCard(l: League, g: Game, fav: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val glow = rememberInfiniteTransition(label = "glow").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "g")
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(150.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = PanelHi),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (g.isLive) 2.dp else 1.dp, if (g.isLive) LiveRed.copy(alpha = glow.value) else Color(0xFF1C2E20)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Faint stadium-light sweep behind the score.
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x1A7CFF3A), Color.Transparent), radius = 420f)))
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        g.isLive -> Text("● LIVE", color = LiveRed, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        g.isFinal -> Text("FINAL", color = FS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        else -> Text(SimpleDateFormat("EEE", Locale.US).format(java.util.Date(g.start)).uppercase(Locale.US),
                            color = FS.Green, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (fav) Text("★", color = Gold, fontSize = 16.sp)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TeamSide(g.away.logo, g.away.abbreviation, winner = g.isFinal && g.away.winner)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val led = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                            shadow = Shadow(Led.copy(alpha = 0.7f), Offset.Zero, 14f))
                        if (g.isLive || g.isFinal) {
                            Text("${g.away.score ?: 0}  ${g.home.score ?: 0}", color = if (g.isFinal) Color(0xFFCFE8C6) else Led,
                                fontSize = 34.sp, style = led, textAlign = TextAlign.Center, maxLines = 1)
                            Text(if (g.isLive) g.detail else "Final", color = if (g.isLive) LiveRed else FS.TextDim, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text(Fmt.time(g.start), color = Led, fontSize = 26.sp, style = led, maxLines = 1)
                            Text("@", color = FS.TextDim, fontSize = 13.sp)
                        }
                    }
                    TeamSide(g.home.logo, g.home.abbreviation, winner = g.isFinal && g.home.winner)
                }
                // Venue normally; the TV networks while highlighted.
                Text(
                    if (focused && g.networks.isNotEmpty()) "📺 " + g.networks.take(4).joinToString(" · ") + "   ·   OK for channels"
                    else g.venue?.let { "📍 $it" } ?: g.networks.take(3).joinToString(" · "),
                    color = if (focused) FS.Green else FS.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TeamSide(logo: String?, abbr: String, winner: Boolean) {
    Column(Modifier.width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { NetImage(logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(4.dp))
        Text(abbr, color = if (winner) Gold else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
    }
}
