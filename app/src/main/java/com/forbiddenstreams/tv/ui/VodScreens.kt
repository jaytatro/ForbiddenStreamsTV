package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.Category
import com.forbiddenstreams.tv.data.Episode
import com.forbiddenstreams.tv.data.SeriesItem
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.data.VodDetails
import com.forbiddenstreams.tv.data.VodItem
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import kotlinx.coroutines.delay

/** Sidebar of groups + poster grid; shared by Movies and TV Shows. */
@Composable
private fun <T> PosterBrowser(
    kind: String,
    loadCats: suspend () -> List<Category>,
    loadItems: suspend (String) -> List<T>,
    title: (T) -> String,
    image: (T) -> String?,
    onOpen: (T) -> Unit,
) {
    var cats by remember { mutableStateOf<List<Category>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(reload) {
        try {
            val c = loadCats()
            cats = c
            if (selected == null || c.none { it.id == selected }) selected = c.firstOrNull()?.id
            error = null
        } catch (e: Exception) { error = e.message ?: "Network error" }
    }
    val c = cats
    if (c == null) {
        if (error != null) MessageBox("Couldn't load $kind", error, "Retry") { reload++ } else LoadingBox("Loading $kind")
        return
    }
    if (c.isEmpty()) { MessageBox("No $kind on this account"); return }

    Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 8.dp)) {
        LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(c, key = { it.id }) { cat ->
                SideItem(cat.name, cat.id == selected, onClick = { selected = cat.id }, modifier = Modifier.fillMaxWidth())
            }
        }
        val catId = selected
        val catItems by produceState<List<T>?>(null, catId) {
            value = if (catId == null) emptyList() else try { loadItems(catId) } catch (e: Exception) { emptyList() }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.firstOrNull { it.id == catId }?.name ?: "", color = FS.Text, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                catItems?.let { Text("${it.size} titles", color = FS.TextDim, fontSize = 16.sp) }
            }
            FsTextField("Filter this group", query, { query = it }, androidx.compose.ui.text.input.KeyboardType.Text)
            Spacer(Modifier.height(10.dp))
            val list = catItems
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> MessageBox("Nothing in this group")
                else -> {
                    val shown = if (query.isBlank()) list else list.filter { title(it).contains(query.trim(), true) }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(170.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(shown) { item ->
                            FocusCard(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.06f) {
                                Column {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
                                        Text(title(item), fontSize = 14.sp, maxLines = 3, modifier = Modifier.padding(8.dp))
                                        NetImage(image(item), Modifier.fillMaxSize(), crop = true)
                                    }
                                    Text(title(item), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoviesScreen() {
    val src = Session.source ?: return
    var open by remember { mutableStateOf<VodItem?>(null) }
    val o = open
    if (o != null) {
        MovieDetails(o, onBack = { open = null })
        return
    }
    PosterBrowser(
        kind = "movies",
        loadCats = { src.vodCategories() },
        loadItems = { src.vodItems(it) },
        title = { it.name },
        image = { it.poster },
        onOpen = { open = it },
    )
}

@Composable
private fun MovieDetails(item: VodItem, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val src = Session.source ?: return
    val details by produceState<VodDetails?>(null, item.id) {
        value = try { src.vodDetails(item) } catch (e: Exception) { null }
    }
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); try { play.requestFocus() } catch (e: Exception) { } }
    Row(Modifier.fillMaxSize().padding(40.dp)) {
        NetImage(item.poster, Modifier.width(300.dp).aspectRatio(2f / 3f), crop = true)
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.name, color = FS.Green, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            val d = details
            val meta = listOfNotNull(d?.year, d?.duration, d?.genre, item.rating?.let { "★ $it" }).joinToString("   ·   ")
            if (meta.isNotEmpty()) Text(meta, color = FS.TextDim, fontSize = 18.sp)
            d?.plot?.let { Text(it, color = FS.Text, fontSize = 19.sp, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            d?.cast?.let { Text("Cast: $it", color = FS.TextDim, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("▶  Play", selected = true, fontSize = 22.sp, modifier = Modifier.focusRequester(play), onClick = {
                    Playback.one(context, PlayItem(item.name, src.vodUrl(item), live = false))
                })
                Pill("Back", selected = false, onClick = onBack)
            }
        }
    }
}

@Composable
fun SeriesScreen() {
    val src = Session.source ?: return
    var open by remember { mutableStateOf<SeriesItem?>(null) }
    val o = open
    if (o != null) {
        SeriesDetails(o, onBack = { open = null })
        return
    }
    PosterBrowser(
        kind = "TV shows",
        loadCats = { src.seriesCategories() },
        loadItems = { src.seriesItems(it) },
        title = { it.name },
        image = { it.cover },
        onOpen = { open = it },
    )
}

@Composable
private fun SeriesDetails(series: SeriesItem, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val src = Session.source ?: return
    val episodes by produceState<List<Episode>?>(null, series.id) {
        value = try { src.episodes(series) } catch (e: Exception) { emptyList() }
    }
    var season by remember { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxSize().padding(32.dp)) {
        Column(Modifier.width(280.dp)) {
            NetImage(series.cover, Modifier.fillMaxWidth().aspectRatio(2f / 3f), crop = true)
            Spacer(Modifier.height(12.dp))
            series.rating?.let { Text("★ $it", color = FS.TextDim, fontSize = 16.sp) }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(series.name, color = FS.Green, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            series.plot?.let { Text(it, color = FS.Text, fontSize = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            val eps = episodes
            when {
                eps == null -> LoadingBox("Loading episodes")
                eps.isEmpty() -> MessageBox("No episodes found")
                else -> {
                    val seasons = remember(eps) { eps.map { it.season }.distinct().sorted() }
                    val current = if (season in seasons) season else seasons.first()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(seasons) { s ->
                            Pill("Season $s", selected = s == current, outlined = true, onClick = { season = s })
                        }
                    }
                    val list = eps.filter { it.season == current }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                            FocusCard(
                                onClick = {
                                    Playback.start(context, list.map {
                                        PlayItem("${series.name} · S${it.season}E${it.episodeNum}", src.episodeUrl(it), live = false, subtitle = it.title)
                                    }, i)
                                },
                                modifier = Modifier.fillMaxWidth().height(96.dp),
                                focusedScale = 1.01f,
                            ) {
                                Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 136.dp, height = 76.dp).padding(end = 12.dp)) {
                                        NetImage(e.image, Modifier.fillMaxSize(), crop = true)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("E${e.episodeNum}  ${e.title}", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        e.plot?.let { Text(it, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
