package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Every game being played right now (all leagues), favorite teams first. Refreshes every minute. */
@Composable
fun liveGamesNow(): List<Pair<League, Game>> {
    val context = LocalContext.current
    val games by produceState(emptyList<Pair<League, Game>>(), FavTeams.version) {
        while (true) {
            val all = try {
                coroutineScope {
                    SPORTS_ORDER.map { l ->
                        async {
                            try { Espn.week(l, 0, 0).values.flatten().filter { it.isLive }.map { l to it } } catch (e: Exception) { emptyList() }
                        }
                    }.awaitAll().flatten()
                }
            } catch (e: Exception) { emptyList() }
            value = all.distinctBy { it.first.name + it.second.id }
                .sortedWith(compareBy({ if (FavTeams.inGame(context, it.first, it.second)) 0 else 1 }, { it.second.start }))
            delay(60_000)
        }
    }
    return games
}

/**
 * Home: "Live games" (Nov 13) — one row per sport, games left to right, the next sport underneath.
 * Sports with a favorite team playing always come first; the rest follow your order from the Sports tab
 * (hold OK on a sport there to move it). OK on a game = its channels.
 */
@Composable
fun LiveGamesRow(games: List<Pair<League, Game>>, onOpen: (League, String) -> Unit) {
    val context = LocalContext.current
    val orderV = com.forbiddenstreams.tv.sports.SportOrder.version
    val order = androidx.compose.runtime.remember(orderV) { com.forbiddenstreams.tv.sports.SportOrder.get(context) }
    val byLeague = games.groupBy { it.first }
        .map { (l, list) -> l to list.map { it.second }.distinctBy { it.id }
            .sortedWith(compareBy({ if (FavTeams.inGame(context, l, it)) 0 else 1 }, { it.start })) }
        .sortedWith(compareBy({ (l, list) -> if (list.any { FavTeams.inGame(context, l, it) }) 0 else 1 },
            { order.indexOf(it.first).let { i -> if (i < 0) 99 else i } }))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(10.dp))
            Text("Live games", color = FS.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("  ·  ${games.size} on now", color = FS.TextDim, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text("\"OK\" for the channels", color = FS.TextDim, fontSize = 13.sp)
        }
        byLeague.forEach { (l, list) ->
            androidx.compose.runtime.key(l.name) {
                val favLeague = list.any { FavTeams.inGame(context, l, it) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Sport header
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 40.dp)) {
                        Box(Modifier.width(4.dp).height(20.dp).background(if (favLeague) Color(0xFFFFC83D) else FS.Green, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(l.icon, fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(l.label.uppercase(), color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        Text("${list.size} LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.background(FS.Live, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                        if (favLeague) {
                            Spacer(Modifier.width(8.dp))
                            Text("★ Favorite", color = Color(0xFFFFC83D), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Its games, left to right
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 4.dp),
                    ) {
                        items(list, key = { it.id }) { g ->
                            Box(Modifier.width(280.dp)) { MiniGameCard(l, g, FavTeams.inGame(context, l, g), 84.dp) { onOpen(l, g.id) } }
                        }
                    }
                }
            }
        }
    }
}

/** A small live-score card: both teams with logos and scores, the clock on the right. */
@Composable
private fun MiniGameCard(l: League, g: Game, fav: Boolean, h: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(h),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = FS.Panel2, focusedContainerColor = Color(0xFF13301B)),
        border = ClickableSurfaceDefaults.border(
            border = if (fav) Border(BorderStroke(1.dp, Color(0xFFFFC83D)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, FS.Green), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniTeam(g.away.logo, (if (fav && FavTeams.isFav(LocalContext.current, l, g.away.abbreviation)) "★ " else "") + g.away.abbreviation, g.away.score)
                MiniTeam(g.home.logo, (if (fav && FavTeams.isFav(LocalContext.current, l, g.home.abbreviation)) "★ " else "") + g.home.abbreviation, g.home.score)
            }
            Spacer(Modifier.width(8.dp))
            Text(g.detail, color = FS.Live, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(70.dp))
        }
    }
}

@Composable
private fun MiniTeam(logo: String?, name: String, score: Any?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { NetImage(logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(8.dp))
        Text(name, color = FS.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        Text("${score ?: 0}", color = FS.Text, fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun TeamLine(logo: String?, name: String, score: Any?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { NetImage(logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(10.dp))
        Text(name, color = FS.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${score ?: 0}", color = FS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
