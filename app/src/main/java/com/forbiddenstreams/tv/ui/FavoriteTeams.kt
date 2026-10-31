package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.sports.FavTeams
import com.forbiddenstreams.tv.sports.League
import com.forbiddenstreams.tv.sports.TeamInfo
import com.forbiddenstreams.tv.sports.TeamList

/**
 * Sports › Favorite Teams: sports across the top; highlighting one lists all its teams with logos.
 * OK on a team flips its on/off switch.
 */
@Composable
fun FavoriteTeamsScreen() {
    val context = LocalContext.current
    var league by remember { mutableStateOf(League.entries.minBy { it.label }) }
    val favs = remember(FavTeams.version) { FavTeams.all(context) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⭐ Favorite Teams", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(24.dp))
            Text("OK on a team switches it on or off · ${favs.size} on", color = FS.TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (l in League.entries.sortedBy { it.label }) {
                Pill(
                    "${l.icon}  ${l.label}", selected = l == league, outlined = true,
                    onClick = { league = l },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) league = l },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val teams by produceState<List<TeamInfo>?>(null, league) {
            value = null
            value = try { TeamList.teams(league) } catch (e: Exception) { emptyList() }
        }
        val list = teams
        when {
            list == null -> LoadingBox("Loading ${league.label} teams")
            list.isEmpty() -> MessageBox("Couldn't load the ${league.label} teams", "Check the internet connection and try again.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { t ->
                    val fav = "${league.name}:${t.abbreviation.uppercase()}" in favs
                    // OK flips the on/off switch next to the team.
                    FocusCard(
                        onClick = {
                            val added = FavTeams.toggle(context, league, t.abbreviation)
                            toast(context, if (added) "⭐ ${t.name} on" else "${t.name} off")
                        },
                        selected = fav,
                        focusedScale = 1.03f,
                        modifier = Modifier.fillMaxWidth().height(76.dp),
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { NetImage(t.logo, Modifier.fillMaxSize()) }
                            Spacer(Modifier.size(12.dp))
                            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.size(10.dp))
                            OnOffSwitch(fav)
                        }
                    }
                }
            }
        }
    }
}

/** Small on/off switch: green with the knob on the right = on. */
@Composable
fun OnOffSwitch(on: Boolean) {
    Box(
        Modifier.size(width = 54.dp, height = 28.dp)
            .background(if (on) FS.Green else FS.GreenDim, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .border(1.dp, if (on) FS.Green else FS.GreenDeep, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(if (on) androidx.compose.ui.graphics.Color.Black else FS.TextDim, androidx.compose.foundation.shape.CircleShape))
    }
}
