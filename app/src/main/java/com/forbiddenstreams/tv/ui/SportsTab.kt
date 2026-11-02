package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * Sports tab (Nov 1 2026, like the user's photo): the leagues as pills in one row across the top,
 * the 7-day date strip under them (today in the middle), then each game as a full-width row:
 * away logo · "Away vs. Home" with the venue · home logo │ networks · time or live score.
 * OK on a league pill switches league; OK on a game pops up the channels it's on.
 */
@Composable
fun SportsTab(open: Pair<League, String>? = null, onOpened: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sp = remember { context.getSharedPreferences("forbidden_streams", android.content.Context.MODE_PRIVATE) }
    var leagueName by rememberSaveable { mutableStateOf(sp.getString("sports_league", null) ?: SPORTS_ORDER.first().name) }
    val league = League.entries.firstOrNull { it.name == leagueName } ?: SPORTS_ORDER.first()
    val pillReq = remember { SPORTS_ORDER.associateWith { FocusRequester() } }
    var inside by remember { mutableStateOf(false) }

    // From Home's "Live games": that league, and the game's channel pop-up.
    LaunchedEffect(open) { open?.let { leagueName = it.first.name } }
    // Put the cursor on the picked league's pill when the tab opens.
    LaunchedEffect(Unit) {
        repeat(10) {
            delay(150)
            if (inside) return@LaunchedEffect
            try { pillReq[league]?.requestFocus() } catch (e: Exception) { }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 76.dp)
            .onFocusChanged { inside = it.hasFocus }
            .focusGroup(),
    ) {
        // One row; slides sideways if it doesn't all fit, so every league keeps its name.
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            items(SPORTS_ORDER.size) { i ->
                val l = SPORTS_ORDER[i]
                Pill("${l.icon} ${shortName(l)}", selected = l == league, fontSize = 14.sp, hPad = 11.dp,
                    modifier = Modifier.focusRequester(pillReq.getValue(l)),
                    onClick = {
                        leagueName = l.name
                        sp.edit().putString("sports_league", l.name).apply()
                    })
            }
        }
        androidx.compose.runtime.key(league) {
            SportsScreen(league, openGameId = open?.takeIf { it.first == league }?.second, onOpened = onOpened,
                popup = true, onLeft = { })
        }
    }
}

private fun shortName(l: League): String = when (l) {
    League.NCAAB -> "NCAAM"
    League.NCAAW -> "NCAAW"
    League.NCAABASE -> "NCAA Baseball"
    else -> l.label
}
