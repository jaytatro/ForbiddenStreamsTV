package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.sports.Espn
import com.forbiddenstreams.tv.sports.FavTeams
import com.forbiddenstreams.tv.sports.Game
import com.forbiddenstreams.tv.sports.League
import com.forbiddenstreams.tv.sports.SPORTS_ORDER
import kotlinx.coroutines.delay

/**
 * 1 minute before a favorite team's game starts: a pop-up at the bottom with the whole game card
 * (both teams, logos, venue, networks, start time). OK = the channels it's on. BACK = dismiss.
 * Goes away by itself after 3 minutes. Each game pops up once.
 */
@Composable
fun BoxScope.FavGameStartAlert(onWatch: (League, String) -> Unit) {
    val context = LocalContext.current
    var alert by remember { mutableStateOf<Pair<League, Game>?>(null) }
    val shown = remember { mutableSetOf<String>() }
    // OK on the alert: the game's channels pop up right where you are.
    var picked by remember { mutableStateOf<Pair<League, Game>?>(null) }
    picked?.let { (pl, pg) -> GameChannelsDialog(pl, pg, onClose = { picked = null }) }
    LaunchedEffect(Unit) {
        while (true) {
            val favs = FavTeams.all(context)
            if (favs.isNotEmpty() && alert == null) {
                val now = System.currentTimeMillis()
                loop@ for (l in SPORTS_ORDER.filter { l -> favs.any { it.startsWith(l.name + ":") } }) {
                    val games = try { Espn.week(l, 0, 0).values.flatten() } catch (e: Exception) { emptyList() }
                    for (g in games) {
                        val key = l.name + ":" + g.id
                        if (key in shown || g.isFinal) continue
                        // Starts within the next ~75 seconds (checked every 20 s, so it lands about 1 minute before).
                        if (g.start - now in 0..75_000 && FavTeams.inGame(context, l, g)) {
                            shown += key; alert = l to g; break@loop
                        }
                    }
                }
            }
            delay(20_000)
        }
    }
    val a = alert ?: return
    val (l, g) = a
    val req = remember(a) { FocusRequester() }
    LaunchedEffect(a) {
        repeat(8) { delay(120); try { req.requestFocus(); return@LaunchedEffect } catch (e: Exception) { } }
    }
    LaunchedEffect(a) { delay(180_000); if (alert == a) alert = null }
    BackHandler { alert = null }
    Column(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 60.dp, vertical = 30.dp).fillMaxWidth()
            .background(Color(0xF2040604), RoundedCornerShape(18.dp))
            .border(2.dp, FS.Green, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⭐ ${l.icon} ${l.label} · Game starting in 1 minute", color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(hint("OK = channels to watch it on · BACK = dismiss", "Tap = channels to watch it on · Back = dismiss"), color = FS.TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.focusRequester(req)) {
            GameRow(g, null, groupMissing = false, loadingChannels = false, fav = true) {
                alert = null
                picked = l to g
            }
        }
    }
}
