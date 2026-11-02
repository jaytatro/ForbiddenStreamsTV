package com.forbiddenstreams.tv.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Program
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.sports.Espn
import com.forbiddenstreams.tv.sports.Game
import com.forbiddenstreams.tv.sports.GameChannels
import com.forbiddenstreams.tv.sports.GameMatcher
import com.forbiddenstreams.tv.sports.League
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SportsScreen(league: League, openGameId: String? = null, onOpened: () -> Unit = {}, onLeft: () -> Unit = {}, popup: Boolean = false) {
    if (Session.source == null) return
    val context = LocalContext.current
    val todayKey = remember { Espn.dayKey(System.currentTimeMillis()) }

    var week by remember(league) { mutableStateOf(SportsCache.weeks[league]) }
    var error by remember(league) { mutableStateOf<String?>(null) }
    var nextGame by remember(league) { mutableStateOf<Long?>(null) }
    var day by remember(league) { mutableStateOf(todayKey) }
    var teamChannels by remember(league) { mutableStateOf<List<LiveChannel>?>(null) }
    var groupMissing by remember(league) { mutableStateOf(false) }
    var matches by remember(league) { mutableStateOf(SportsCache.matches[league].orEmpty()) }
    var picker by remember { mutableStateOf<Game?>(null) }
    LaunchedEffect(openGameId, week) {
        val id = openGameId ?: return@LaunchedEffect
        val g = week?.values?.flatten()?.firstOrNull { it.id == id } ?: return@LaunchedEffect
        day = Espn.dayKey(g.start)
        picker = g
        onOpened()
    }
    val epgCache = remember(league) { HashMap<String, List<Program>>() }

    // Team channel group (only the agreed "USA | <League> Teams" group is used).
    LaunchedEffect(league, Session.liveCats, Session.liveAll) {
        val cats = Session.liveCats ?: return@LaunchedEffect
        val g = GameMatcher.findGroup(cats, league)
        groupMissing = g == null
        teamChannels = if (g == null) emptyList() else Session.channelsIn(g.id)
    }

    // Scores: refresh every 30 seconds while this tab is open.
    LaunchedEffect(league) {
        while (true) {
            try {
                val w = Espn.week(league)
                week = w
                SportsCache.weeks[league] = w
                error = null
                if (w.isEmpty() && nextGame == null) nextGame = Espn.nextGameAfter(league)
            } catch (e: Exception) {
                if (week == null) { error = e.message ?: "Couldn't reach the scoreboard" }
            }
            delay(30_000)
        }
    }

    val games = week?.get(day).orEmpty()
    // Match every game of the week at once, off the main thread, without waiting on the
    // network, so the channel list pops up instantly when a game is picked.
    val allIds = week?.values?.flatten()?.joinToString(",") { it.id }
    LaunchedEffect(allIds, teamChannels) {
        val ch = teamChannels ?: return@LaunchedEffect
        val all = week?.values?.flatten().orEmpty()
        val out = withContext(Dispatchers.Default) {
            val m = HashMap(matches)
            for (g in all) {
                if (m.containsKey(g.id)) continue
                m[g.id] = GameMatcher.match(g, league, ch) { c -> epgCache[c.id] ?: Session.cachedEpg(c).orEmpty() }
            }
            m
        }
        matches = out
        SportsCache.matches[league] = out
    }

    Column(Modifier.fillMaxSize()) {
        // Date strip with game-count badges
        val w = week
        // Always 3 days back, TODAY in the middle, 3 days ahead.
        val days = remember(todayKey) {
            (-3..3).map { d ->
                Espn.dayKey(java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, d) }.timeInMillis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 24.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Dates spread evenly across the full width.
        Row(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 12.dp)) {
            for (key in days) {
                val count = w?.get(key)?.size ?: 0
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box {
                    Pill(
                        if (key == todayKey) "TODAY" else dayLabel(key),
                        selected = key == day,
                        outlined = true,
                        onClick = { day = key },
                        modifier = Modifier.padding(top = 10.dp, end = 10.dp),
                    )
                    if (count > 0) {
                        Text(
                            "$count",
                            color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .background(FS.Green, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                }
            }
        }
        }

        when {
            w == null && error != null -> MessageBox("Scores unavailable", error)
            w == null -> LoadingBox("Loading ${league.label} games")
            games.isEmpty() -> MessageBox(
                if (day == todayKey) "No games today" else "No games this day",
                nextGame?.let { "Next game: ${SimpleDateFormat("EEE M/d 'at' h:mm a", Locale.US).format(it)}" }
                    ?: w.orEmpty().keys.filter { it > day }.minOrNull()?.let { "Next game: ${dayLabel(it)}" },
            )
            else -> {
                // Favorite teams' games first (⭐), then live, upcoming, final.
                val favV = com.forbiddenstreams.tv.sports.FavTeams.version
                val sorted = remember(games, favV) {
                    games.sortedWith(compareBy(
                        { if (com.forbiddenstreams.tv.sports.FavTeams.inGame(context, league, it)) 0 else 1 },
                        { if (it.isLive) 0 else if (it.isFinal) 2 else 1 }, { it.start },
                    ))
                }
                if (popup) {
                    // Full-width rows like the user's photo (Heat Live style): away logo · "Away vs. Home" with
                    // the venue under it · home logo │ networks · time or score. OK = channels pop-up.
                    LazyColumn(
                        Modifier.fillMaxSize()
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                    ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                ) { if (ev.nativeKeyEvent.repeatCount == 0) onLeft(); true } else false
                            }
                            .padding(horizontal = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp),
                    ) {
                        items(sorted, key = { it.id }) { g ->
                            GameRow(g, matches[g.id], groupMissing, teamChannels == null,
                                fav = com.forbiddenstreams.tv.sports.FavTeams.inGame(context, league, g)) { picker = g }
                        }
                    }
                } else GameBoard(
                    games = sorted.map { league to it },
                    matchFor = { _, g -> matches[g.id] },
                    teamFor = { teamChannels.orEmpty() },
                    favFor = { l, g -> com.forbiddenstreams.tv.sports.FavTeams.inGame(context, l, g) },
                    onLeft = onLeft,
                    // New Sports tab: OK on a game pops up its channels.
                    onPick = if (popup) { { _, g -> picker = g } } else null,
                )
            }
        }
    }

    // OK on a game: show where to watch it (or replay it, if it's over).
    picker?.let { g ->
        val options = remember(g.id, matches[g.id], teamChannels, Session.liveAll) { gameOptions(context, g, matches[g.id], teamChannels.orEmpty()) }
        val tv = g.networks.takeIf { it.isNotEmpty() }?.let { " · TV: ${it.joinToString(", ")}" } ?: ""
        val sub = when {
            options.isNotEmpty() && g.isFinal -> "Final ${g.away.score ?: ""}–${g.home.score ?: ""}. Pick a channel to replay the game$tv"
            options.isNotEmpty() -> "Pick a channel to watch$tv"
            g.isFinal -> "This game is over and none of its channels have catch-up (⟲), so it can't be replayed$tv"
            matches[g.id] == null -> "Still matching channels. Try again in a second."
            else -> (matches[g.id]?.note ?: "No channel found") + tv
        }
        ChoiceDialog(
            "${g.away.displayName} vs. ${g.home.displayName}", sub,
            options.map { (label, action) -> label to { picker = null; action() } }
                .ifEmpty { listOf<Pair<String, () -> Unit>>("OK" to { picker = null }) },
            onDismiss = { picker = null },
        )
    }
}

/** Remembers the last scores and channel matches per league so tabs open instantly. */
private object SportsCache {
    val weeks = HashMap<League, Map<String, List<Game>>>()
    val matches = HashMap<League, Map<String, GameChannels>>()
}

private fun gameLengthMs(g: Game): Long = when {
    g.home.logo?.contains("/nfl/") == true -> 210 * 60_000L
    g.home.logo?.contains("/mlb/") == true -> 200 * 60_000L
    g.home.logo?.contains("/nhl/") == true -> 170 * 60_000L
    else -> 160 * 60_000L
}

/** Channel choices for a game: team feeds and the TV network. Finished games replay from catch-up. */
private fun gameOptions(
    context: android.content.Context,
    g: Game,
    m: GameChannels?,
    team: List<LiveChannel>,
): List<Pair<String, () -> Unit>> {
    val out = ArrayList<Pair<String, () -> Unit>>()
    val feeds = ArrayList<Triple<String, LiveChannel, List<LiveChannel>>>()
    m?.away?.let { feeds += Triple("📺 ${g.away.displayName} feed", it, team) }
    m?.home?.let { feeds += Triple("📺 ${g.home.displayName} feed", it, team) }
    val used = m?.options.orEmpty().map { it.id }.toSet()
    GameMatcher.networkChannels(g, Session.liveAll.orEmpty(), used).forEach { (net, c) -> feeds += Triple("📡 $net", c, listOf(c)) }

    if (g.isFinal) {
        val src = Session.source ?: return out
        for ((label, c, _) in feeds) {
            // The show on the channel's guide that covers kick-off, or a window around the game.
            val prog = Session.cachedEpg(c)?.firstOrNull { g.start >= it.start - 15 * 60_000 && g.start < it.end }
                ?: Program("${g.away.displayName} vs. ${g.home.displayName}", "", g.start - 10 * 60_000, g.start + gameLengthMs(g), true)
            val url = src.catchupUrl(c, prog) ?: continue
            val q = com.forbiddenstreams.tv.player.StreamQuality.label(context, c.id, c.name)
            out += "⏮ Replay · $label · ${c.name}" + (q?.let { "   [$it]" } ?: "") to {
                Playback.one(context, PlayItem("${g.away.displayName} vs. ${g.home.displayName}", url, live = false, subtitle = "Replay from ${c.name}"))
            }
        }
    } else {
        if (g.isLive) {
            val src = Session.source
            for ((label, c, _) in feeds) {
                if (!c.hasArchive || src == null) continue
                val prog = Program("${g.away.displayName} vs. ${g.home.displayName}", "", g.start - 5 * 60_000, g.start + gameLengthMs(g), true)
                val url = src.catchupUrl(c, prog) ?: continue
                out += "⟲ From kickoff · $label" to {
                    Playback.one(context, PlayItem("${g.away.displayName} vs. ${g.home.displayName}", url, live = false, subtitle = "From kickoff · ${c.name}"))
                }
                break
            }
        }
        for ((label, c, list) in feeds) {
            // Picture quality: what the player saw last time on this channel, or a guess from its name ("?").
            val q = com.forbiddenstreams.tv.player.StreamQuality.label(context, c.id, c.name)
            out += "$label · ${c.name}" + (q?.let { "   [$it]" } ?: "") to { openChannel(context, list, c) }
        }
    }
    return out
}

private fun openChannel(context: android.content.Context, all: List<LiveChannel>, ch: LiveChannel) {
    val list = all.ifEmpty { listOf(ch) }
    playChannels(context, list, list.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
}

private fun dayLabel(key: String): String = try {
    val d = SimpleDateFormat("yyyyMMdd", Locale.US).parse(key)
    SimpleDateFormat("EEE, M/d", Locale.US).format(d!!).uppercase(Locale.US)
} catch (e: Exception) { key }

@Composable
internal fun GameRow(game: Game, channels: GameChannels?, groupMissing: Boolean, loadingChannels: Boolean, fav: Boolean = false, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(150.dp),
        container = FS.Panel.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { NetImage(game.away.logo, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Full team names: smaller type for long names, and wrap rather than cut off.
                val len = game.away.displayName.length + game.home.displayName.length
                val size = when { len <= 30 -> 28.sp; len <= 38 -> 24.sp; else -> 21.sp }
                Text(
                    buildAnnotatedString {
                        if (fav) append("⭐ ")
                        append(game.away.displayName)
                        withStyle(SpanStyle(fontSize = 18.sp, color = FS.TextDim)) { append("  vs.  ") }
                        append(game.home.displayName)
                    },
                    fontSize = size, fontWeight = FontWeight.Bold, maxLines = 2, softWrap = true,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                game.venue?.let { Text(it, color = FS.Green, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val chText = when {
                    groupMissing -> if (game.networks.isNotEmpty()) "📡 " + game.networks.joinToString(" · ") + " · OK for channels"
                        else "No team channels on your provider · OK for channels"
                    channels == null -> if (loadingChannels) "Finding team channels…" else ""
                    channels.options.isEmpty() -> channels.note ?: "No team channel found"
                    else -> "📺 " + channels.options.joinToString("   |   ") { it.name }
                }
                if (chText.isNotEmpty()) {
                    Text(chText, color = FS.TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { NetImage(game.home.logo, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.width(2.dp).fillMaxHeight(0.55f).background(FS.Line))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.width(210.dp), horizontalAlignment = Alignment.End) {
                if (game.networks.isNotEmpty()) {
                    Text(game.networks.take(3).joinToString(" · "), color = FS.TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    game.isLive -> {
                        Text("${game.away.score ?: 0} – ${game.home.score ?: 0}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveDot(Modifier.offset(y = 1.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(game.detail, color = FS.Live, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    game.isFinal -> {
                        Text("${game.away.score ?: 0} – ${game.home.score ?: 0}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(game.detail.ifEmpty { "Final" }, color = FS.TextDim, fontSize = 15.sp)
                    }
                    else -> Text(Fmt.time(game.start), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


// ------------------------------------------------------------ v2 Game Center

/**
 * Game cards in two columns; where to watch the highlighted game is shown in the panel on the
 * right (no pop-up). OK on a card moves into that panel. ◀ on the left column = the Sports list.
 */
@Composable
private fun GameBoard(
    games: List<Pair<League, Game>>,
    matchFor: (League, Game) -> GameChannels?,
    teamFor: (League) -> List<LiveChannel>,
    favFor: (League, Game) -> Boolean,
    onLeft: () -> Unit,
    onPick: ((League, Game) -> Unit)? = null,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sel by remember { mutableStateOf<Pair<League, Game>?>(null) }
    val panelReq = remember { androidx.compose.ui.focus.FocusRequester() }
    // Keep the highlighted game's latest score/clock.
    val current = sel?.let { (l, g) -> games.firstOrNull { it.first == l && it.second.id == g.id } ?: sel }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (onPick != null) 3 else 2),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(games.size, key = { i -> games[i].first.name + ":" + games[i].second.id }) { i ->
                val (l, g) = games[i]
                GameCard(
                    l, g, fav = favFor(l, g), selected = current?.second?.id == g.id,
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) sel = l to g }
                        .onPreviewKeyEvent { ev ->
                            if (i % (if (onPick != null) 3 else 2) == 0 && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            ) { if (ev.nativeKeyEvent.repeatCount == 0) onLeft(); true } else false
                        },
                    onClick = {
                        sel = l to g
                        if (onPick != null) onPick(l, g)
                        else scope.launch { delay(80); try { panelReq.requestFocus() } catch (e: Exception) { } }
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (onPick == null) {
            Spacer(Modifier.width(18.dp))
            WatchPanel(current, matchFor, teamFor, panelReq, Modifier.width(340.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun GameCard(league: League, g: Game, fav: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(172.dp),
        container = if (selected) FS.GreenDim else FS.Panel,
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val state = when {
                    g.isLive -> "● LIVE · ${g.detail}"
                    g.isFinal -> g.detail.ifEmpty { "Final" }
                    else -> "${Fmt.day(g.start)} ${Fmt.time(g.start)}"
                }
                Text(state, color = if (g.isLive) Color(0xFFFF6B61) else FS.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text((if (fav) "⭐ " else "") + league.label + (g.networks.firstOrNull()?.let { " · $it" } ?: ""),
                    color = FS.TextDim, fontSize = 13.sp, maxLines = 1)
            }
            for (t in listOf(g.away, g.home)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { NetImage(t.logo, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Text(t.displayName, fontSize = 18.sp, fontWeight = if (t.winner) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (g.isLive || g.isFinal) Text(t.score ?: "0", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Where they're playing.
            g.venue?.let { Text("📍 $it", color = FS.Green, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun WatchPanel(
    sel: Pair<League, Game>?,
    matchFor: (League, Game) -> GameChannels?,
    teamFor: (League) -> List<LiveChannel>,
    firstReq: androidx.compose.ui.focus.FocusRequester,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.background(Color(0xFF0A120B), RoundedCornerShape(16.dp))
            .border(1.dp, FS.GreenDeep, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (sel?.second?.isFinal == true) "REPLAY THIS GAME" else "WATCH THIS GAME", color = FS.TextDim, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        if (sel == null) {
            Text("Highlight a game to see where to watch it.", color = FS.Text, fontSize = 16.sp)
            return@Column
        }
        val (l, g) = sel
        Text("${g.away.displayName} at ${g.home.displayName}", color = FS.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        g.venue?.let { Text(it, color = FS.Green, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val m = matchFor(l, g)
        val team = teamFor(l)
        val options = remember(g.id, g.state, m, team, Session.liveAll) { gameOptions(context, g, m, team) }
        if (options.isEmpty()) {
            Text(
                when {
                    g.isFinal -> "None of this game's channels have catch-up (⟲), so it can't be replayed."
                    m == null -> "Finding channels…"
                    else -> m.note ?: "No channel found for this game."
                },
                color = FS.TextDim, fontSize = 15.sp,
            )
        }
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options.size) { i ->
                val (label, action) = options[i]
                FocusCard(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstReq) else Modifier),
                    focusedScale = 1.02f,
                ) {
                    Text(label, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/**
 * "● Live now" (games in progress in every league) or "⭐ My teams" (your favorite teams'
 * games today and this week), in the same board as a league.
 */
@Composable
fun LiveGamesScreen(mine: Boolean, onLeft: () -> Unit = {}) {
    val context = LocalContext.current
    var games by remember(mine) { mutableStateOf<List<Pair<League, Game>>?>(null) }
    LaunchedEffect(mine) {
        while (true) {
            val favs = com.forbiddenstreams.tv.sports.FavTeams.all(context)
            val leagues = if (mine) League.entries.filter { l -> favs.any { it.startsWith(l.name + ":") } } else League.entries
            val found = withContext(Dispatchers.Default) {
                kotlinx.coroutines.coroutineScope {
                    leagues.map { l ->
                        async {
                            val w = try { if (mine) Espn.week(l, 0, 6) else Espn.week(l, 0, 0) } catch (e: Exception) { emptyMap() }
                            w.values.flatten().filter { g ->
                                if (mine) com.forbiddenstreams.tv.sports.FavTeams.inGame(context, l, g) && !g.isFinal else g.isLive
                            }.map { l to it }
                        }
                    }.awaitAll().flatten()
                }
            }
            games = found.distinctBy { it.first.name + it.second.id }
                .sortedWith(compareBy({ if (it.second.isLive) 0 else 1 }, { it.second.start }))
            delay(30_000)
        }
    }
    // Team channels and channel matching per league.
    val matches = remember { androidx.compose.runtime.mutableStateMapOf<String, GameChannels>() }
    val teams = remember(Session.liveCats, Session.liveAll) {
        val cats = Session.liveCats.orEmpty()
        League.entries.associateWith { l -> GameMatcher.findGroup(cats, l)?.let { Session.channelsIn(it.id) }.orEmpty() }
    }
    LaunchedEffect(games, teams) {
        val list = games ?: return@LaunchedEffect
        for ((l, g) in list) {
            val key = l.name + ":" + g.id
            if (matches.containsKey(key)) continue
            matches[key] = withContext(Dispatchers.Default) {
                GameMatcher.match(g, l, teams[l].orEmpty()) { c -> Session.cachedEpg(c).orEmpty() }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            if (mine) "⭐  My teams" else "●  Live now",
            color = if (mine) FS.Green else Color(0xFFFF6B61), fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 12.dp),
        )
        val list = games
        when {
            list == null -> LoadingBox(if (mine) "Finding your teams' games" else "Finding live games")
            list.isEmpty() -> MessageBox(
                if (mine) "No upcoming games for your teams" else "No games live right now",
                if (mine) "Pick your teams in Settings › Favorite Teams." else "Check back at game time, or pick a league.",
            )
            else -> GameBoard(
                games = list,
                matchFor = { l, g -> matches[l.name + ":" + g.id] },
                teamFor = { l -> teams[l].orEmpty() },
                favFor = { l, g -> com.forbiddenstreams.tv.sports.FavTeams.inGame(context, l, g) },
                onLeft = onLeft,
            )
        }
    }
}
