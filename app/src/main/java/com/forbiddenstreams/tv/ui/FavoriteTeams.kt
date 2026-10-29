package com.forbiddenstreams.tv.ui

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
 * Hold OK on a team = add / remove favorite.
 */
@Composable
fun FavoriteTeamsScreen() {
    val context = LocalContext.current
    var league by remember { mutableStateOf(League.NFL) }
    val favs = remember(FavTeams.version) { FavTeams.all(context) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⭐ Favorite Teams", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(24.dp))
            Text("Hold OK on a team to add or remove it · ${favs.size} picked", color = FS.TextDim, fontSize = 15.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (l in League.entries) {
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
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(list, key = { it.id }) { t ->
                    val fav = "${league.name}:${t.abbreviation.uppercase()}" in favs
                    FocusCard(
                        onClick = { toast(context, "Hold OK to ${if (fav) "remove" else "add"} ${t.name}") },
                        onLongClick = {
                            val added = FavTeams.toggle(context, league, t.abbreviation)
                            toast(context, if (added) "⭐ ${t.name} added" else "${t.name} removed")
                        },
                        selected = fav,
                        focusedScale = 1.05f,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    ) {
                        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { NetImage(t.logo, Modifier.fillMaxSize()) }
                            Spacer(Modifier.height(6.dp))
                            Text((if (fav) "⭐ " else "") + t.name, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
