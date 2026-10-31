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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SportsScreen(league: League, openGameId: String? = null, onOpened: () -> Unit = {}, onLeft: () -> Unit = {}) {
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
                LazyColumn(
                    Modifier.fillMaxSize()
                        // ◀ on a game = back to the Sports list in the side bar.
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            ) { if (ev.nativeKeyEvent.repeatCount == 0) onLeft(); true } else false
                        }
                        .padding(horizontal = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(sorted, key = { it.id }) { g ->
                        GameRow(g, matches[g.id], groupMissing, teamChannels == null,
                            fav = remember(favV, g.id) { com.forbiddenstreams.tv.sports.FavTeams.inGame(context, league, g) }) { picker = g }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
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
private fun GameRow(game: Game, channels: GameChannels?, groupMissing: Boolean, loadingChannels: Boolean, fav: Boolean = false, onClick: () -> Unit) {
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
