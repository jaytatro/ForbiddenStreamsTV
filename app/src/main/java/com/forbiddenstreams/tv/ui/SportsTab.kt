package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.sports.League
import com.forbiddenstreams.tv.sports.SPORTS_ORDER
import kotlinx.coroutines.delay

/**
 * Sports tab (Nov 1 2026): the leagues as tiles, left to right, wrapping onto centered rows.
 * OK on a league → its games (teams, live score, where they're playing; 7-day date strip,
 * today in the middle). OK on a game → a pop-up with the channels it's on. BACK = the leagues.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportsTab() {
    var leagueName by rememberSaveable { mutableStateOf<String?>(null) }
    val league = leagueName?.let { n -> League.entries.firstOrNull { it.name == n } }
    val tileReq = remember { SPORTS_ORDER.associateWith { FocusRequester() } }
    var lastPicked by remember { mutableStateOf<League?>(null) }

    if (league != null) {
        BackHandler { lastPicked = league; leagueName = null }
        // Put the cursor into the games right away (the tile that had it is gone now).
        val gamesReq = remember { FocusRequester() }
        var inGames by remember { mutableStateOf(false) }
        LaunchedEffect(league) {
            repeat(12) {
                delay(150)
                if (inGames) return@LaunchedEffect
                try { gamesReq.requestFocus() } catch (e: Exception) { }
            }
        }
        Column(
            Modifier.fillMaxSize().padding(top = 76.dp)
                .onFocusChanged { inGames = it.hasFocus }
                .focusRequester(gamesReq).focusGroup(),
        ) {
            Text("${league.icon}  ${league.label}", color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 36.dp, bottom = 2.dp))
            SportsScreen(league, popup = true, onLeft = { lastPicked = league; leagueName = null })
        }
        return
    }

    // Back from a league: the cursor goes back onto its tile.
    LaunchedEffect(lastPicked) {
        val l = lastPicked ?: return@LaunchedEffect
        delay(120)
        try { tileReq[l]?.requestFocus() } catch (e: Exception) { }
    }
    Box(Modifier.fillMaxSize().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
        FlowRow(
            Modifier.widthIn(max = 1150.dp).fillMaxWidth().padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            for (l in SPORTS_ORDER) {
                val shape = RoundedCornerShape(16.dp)
                Surface(
                    onClick = { leagueName = l.name },
                    modifier = Modifier.size(width = 240.dp, height = 120.dp).focusRequester(tileReq.getValue(l)),
                    shape = ClickableSurfaceDefaults.shape(shape = shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = FS.Panel, contentColor = FS.Text,
                        focusedContainerColor = FS.GreenDeep, focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.dp, FS.GreenDim), shape = shape),
                        focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                ) {
                    Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(l.icon, fontSize = 34.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(l.label, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}
