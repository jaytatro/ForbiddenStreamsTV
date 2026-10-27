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
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SportsScreen(league: League) {
    val src = Session.source ?: return
    val context = LocalContext.current
    val todayKey = remember { Espn.dayKey(System.currentTimeMillis()) }

    var week by remember(league) { mutableStateOf<Map<String, List<Game>>?>(null) }
    var error by remember(league) { mutableStateOf<String?>(null) }
    var nextGame by remember(league) { mutableStateOf<Long?>(null) }
    var day by remember(league) { mutableStateOf(todayKey) }
    var teamChannels by remember(league) { mutableStateOf<List<LiveChannel>?>(null) }
    var groupMissing by remember(league) { mutableStateOf(false) }
    var matches by remember(league) { mutableStateOf<Map<String, GameChannels>>(emptyMap()) }
    var picker by remember { mutableStateOf<Pair<Game, GameChannels>?>(null) }
    val epgCache = remember(league) { HashMap<String, List<Program>>() }

    // Team channel group (only the agreed "USA | <League> Teams" group is used).
    LaunchedEffect(league) {
        try {
            val g = GameMatcher.findGroup(src.liveCategories(), league)
            if (g == null) { groupMissing = true; teamChannels = emptyList() }
            else { teamChannels = src.liveChannels(g.id) }
        } catch (e: Exception) {
            teamChannels = emptyList()
        }
    }

    // Scores: refresh every 30 seconds while this tab is open.
    LaunchedEffect(league) {
        while (true) {
            try {
                val w = Espn.week(league)
                week = w
                error = null
                if (w.isEmpty() && nextGame == null) nextGame = Espn.nextGameAfter(league)
            } catch (e: Exception) {
                if (week == null) { error = e.message ?: "Couldn't reach the scoreboard" }
            }
            delay(30_000)
        }
    }

    val games = week?.get(day).orEmpty()
    val gameIds = games.joinToString(",") { it.id }
    LaunchedEffect(gameIds, teamChannels) {
        val ch = teamChannels ?: return@LaunchedEffect
        val out = HashMap(matches)
        for (g in games) {
            if (out.containsKey(g.id)) continue
            out[g.id] = GameMatcher.match(g, league, ch) { c ->
                epgCache[c.id] ?: Session.epg(c).also { epgCache[c.id] = it }
            }
        }
        matches = out
    }

    Column(Modifier.fillMaxSize()) {
        // Date strip with game-count badges
        val w = week
        val days = remember(w) { ((w?.keys ?: emptySet()) + todayKey).sorted() }
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(days) { key ->
                val count = w?.get(key)?.size ?: 0
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

        when {
            w == null && error != null -> MessageBox("Scores unavailable", error)
            w == null -> LoadingBox("Loading ${league.label} games")
            games.isEmpty() -> MessageBox(
                if (day == todayKey) "No games today" else "No games this day",
                nextGame?.let { "Next game: ${SimpleDateFormat("EEE M/d 'at' h:mm a", Locale.US).format(it)}" }
                    ?: w.orEmpty().keys.filter { it > day }.minOrNull()?.let { "Next game: ${dayLabel(it)}" },
            )
            else -> {
                val sorted = games.sortedWith(compareBy({ if (it.isLive) 0 else if (it.isFinal) 2 else 1 }, { it.start }))
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(sorted, key = { it.id }) { g ->
                        GameRow(g, matches[g.id], groupMissing, teamChannels == null) {
                            val m = matches[g.id]
                            val opts = m?.options.orEmpty()
                            when {
                                opts.isEmpty() -> toast(context, m?.note ?: "Still looking for the team channel…")
                                opts.size == 1 -> openChannel(context, teamChannels.orEmpty(), opts[0])
                                else -> { picker = g to m!! }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    picker?.let { (g, m) ->
        val options = ArrayList<Pair<String, () -> Unit>>()
        m.away?.let { c -> options.add("${g.away.displayName} feed · ${c.name}" to { picker = null; openChannel(context, teamChannels.orEmpty(), c) }) }
        m.home?.let { c -> options.add("${g.home.displayName} feed · ${c.name}" to { picker = null; openChannel(context, teamChannels.orEmpty(), c) }) }
        ChoiceDialog("${g.away.displayName} vs. ${g.home.displayName}", "Pick which team's channel to watch", options, onDismiss = { picker = null })
    }
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
private fun GameRow(game: Game, channels: GameChannels?, groupMissing: Boolean, loadingChannels: Boolean, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(150.dp),
        container = FS.Panel.copy(alpha = 0.85f),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) { NetImage(game.away.logo, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    buildAnnotatedString {
                        append(game.away.displayName)
                        withStyle(SpanStyle(fontSize = 20.sp, color = FS.TextDim)) { append("  vs.  ") }
                        append(game.home.displayName)
                    },
                    fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                game.venue?.let { Text(it, color = FS.Green, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val chText = when {
                    groupMissing -> "Team channels group not found on your provider"
                    channels == null -> if (loadingChannels) "Finding team channels…" else ""
                    channels.options.isEmpty() -> channels.note ?: "No team channel found"
                    else -> "📺 " + channels.options.joinToString("   |   ") { it.name }
                }
                if (chText.isNotEmpty()) {
                    Text(chText, color = FS.TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) { NetImage(game.home.logo, Modifier.fillMaxSize()) }
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
