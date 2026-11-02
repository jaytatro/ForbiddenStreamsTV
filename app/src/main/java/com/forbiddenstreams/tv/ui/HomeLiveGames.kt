package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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

/** Home: "Live games · Sports" — OK on a game opens the Sports tab with its channel pop-up. */
@Composable
fun LiveGamesRow(games: List<Pair<League, Game>>, onOpen: (League, String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(10.dp))
            Text("Live games", color = FS.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("  ·  Sports", color = FS.TextDim, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text("\"OK\" for the channels", color = FS.TextDim, fontSize = 13.sp)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp),
        ) {
            items(games, key = { it.first.name + ":" + it.second.id }) { (l, g) ->
                val fav = FavTeams.inGame(context, l, g)
                val shape = RoundedCornerShape(6.dp)
                Surface(
                    onClick = { onOpen(l, g.id) },
                    modifier = Modifier.width(300.dp).height(169.dp),
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = FS.Panel2, focusedContainerColor = FS.Panel2),
                    border = ClickableSurfaceDefaults.border(
                        border = if (fav) Border(BorderStroke(1.dp, FS.GreenMid), shape = shape) else Border.None,
                        focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(FS.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                            Spacer(Modifier.width(8.dp))
                            Text((if (fav) "⭐ " else "") + "${l.icon} ${l.label}", color = FS.TextDim, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(g.detail, color = FS.Live, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f))
                        TeamLine(g.away.logo, g.away.displayName, g.away.score)
                        Spacer(Modifier.height(8.dp))
                        TeamLine(g.home.logo, g.home.displayName, g.home.score)
                        Spacer(Modifier.weight(1f))
                        if (g.networks.isNotEmpty()) {
                            Text("📺 " + g.networks.take(3).joinToString(" · "), color = FS.TextDim, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
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
