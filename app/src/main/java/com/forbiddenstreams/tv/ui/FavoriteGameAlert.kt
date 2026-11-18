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
import androidx.compose.foundation.layout.size
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
 * (Nov 17: slim, full-width, red outline; ▶ Open = the live channels for it, ✕ Close; BACK = close).
 * Stays for Settings › Sports › Alert stays on screen. Each game pops up once.
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
                        // Settings › Sports: 1–5 minutes before (Nov 15).
                        val lead = com.forbiddenstreams.tv.data.AppSettings.favAlertMin * 60_000L
                        if (g.start - now in 0..(lead + 15_000) && FavTeams.inGame(context, l, g)) {
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
    // Settings › Sports › Alert stays on screen (0 = until closed).
    val stay = com.forbiddenstreams.tv.data.AppSettings.favAlertStaySec
    LaunchedEffect(a) { if (stay > 0) { delay(stay * 1_000L); if (alert == a) alert = null } }
    BackHandler { alert = null }
    val red = Color(0xFFFF3B3B)
    val mins = ((g.start - System.currentTimeMillis() + 30_000) / 60_000).coerceAtLeast(1)
    // Nov 17: a slim red-outlined bar across the whole bottom; Open / Close on top of it.
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)
            .background(Color(0xF2080404), RoundedCornerShape(14.dp))
            .border(2.dp, red, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GText("⭐ ${l.icon} ${l.label} · starts in $mins minute${if (mins > 1) "s" else ""}", color = red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            AlertButton("▶  Open", red, Modifier.focusRequester(req)) { alert = null; picked = l to g }
            Spacer(Modifier.width(10.dp))
            AlertButton("✕  Close", red) { alert = null }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetImage(g.away.logo, Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Text(g.away.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("  @  ", color = FS.TextDim, fontSize = 16.sp)
            NetImage(g.home.logo, Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Text(g.home.displayName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(listOfNotNull(Fmt.time(g.start), g.networks.takeIf { it.isNotEmpty() }?.joinToString(", "), g.venue).joinToString("  ·  "),
                color = FS.TextDim, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AlertButton(text: String, red: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    androidx.tv.material3.Surface(
        onClick = onClick, modifier = modifier.height(34.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0x22FF3B3B), contentColor = Color.White,
            focusedContainerColor = red, focusedContentColor = Color.White,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, red.copy(alpha = 0.6f)), shape = shape),
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = shape),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.padding(horizontal = 18.dp).height(34.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}
