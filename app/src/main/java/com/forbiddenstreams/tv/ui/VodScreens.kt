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
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.forbiddenstreams.tv.data.RecentItem
import com.forbiddenstreams.tv.data.Recents

/** Sidebar of groups + poster grid; shared by Movies and TV Shows. */
private const val RECENT = VOD_RECENT

/**
 * Movies / TV Shows content. The category (or Search / Recently Watched) is picked in the
 * rail box; [selected] null = Recently Watched if there's anything, else the first category.
 */
@Composable
private fun <T> PosterBrowser(
    kind: String,
    recentKind: String,
    selected: String?,
    onSelect: (String?) -> Unit,
    loadCats: suspend () -> List<Category>,
    loadItems: suspend (String) -> List<T>,
    loadAll: suspend () -> List<T>,
    title: (T) -> String,
    image: (T) -> String?,
    onOpen: (T) -> Unit,
) {
    val context = LocalContext.current
    var cats by remember { mutableStateOf<List<Category>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try { cats = loadCats(); error = null } catch (e: Exception) { error = e.message ?: "Network error" }
    }
    val c = cats
    if (c == null) {
        if (error != null) MessageBox("Couldn't load $kind", error, "Retry") { reload++ } else LoadingBox("Loading $kind")
        return
    }
    val catId = selected ?: if (Recents.all(context, recentKind).isNotEmpty()) RECENT else c.firstOrNull()?.id

    if (catId == VOD_SEARCH) {
        SearchScreen(kind, loadAll, title, image, onOpen = onOpen, onClose = { onSelect(null) })
        return
    }
    if (catId == RECENT) {
        RecentGrid(recentKind, Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp))
        return
    }
    val catItems by produceState<List<T>?>(null, catId) {
        value = if (catId == null) emptyList() else try { loadItems(catId) } catch (e: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.firstOrNull { it.id == catId }?.name ?: "", color = FS.Text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            catItems?.let { Text("${it.size} titles", color = FS.TextDim, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(6.dp))
        val list = catItems
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> MessageBox(if (c.isEmpty()) "No $kind on this account" else "Nothing in this group")
            else -> PosterGrid(list, title, image, onOpen)
        }
    }
}

@Composable
private fun <T> PosterGrid(list: List<T>, title: (T) -> String, image: (T) -> String?, onOpen: (T) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(list) { item -> Poster(title(item), image(item), onClick = { onOpen(item) }) }
    }
}

@Composable
private fun Poster(title: String, image: String?, progress: Float? = null, sub: String? = null, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.06f) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 14.sp, maxLines = 3, modifier = Modifier.padding(8.dp))
                NetImage(image, Modifier.fillMaxSize(), crop = true)
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0xAA000000))) {
                        Box(Modifier.fillMaxWidth(progress).height(5.dp).background(FS.Green))
                    }
                }
            }
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = if (sub == null) 6.dp else 0.dp))
            if (sub != null) Text(sub, fontSize = 12.sp, color = FS.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
        }
    }
}

private fun clock(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Movies or episodes that were started and not finished. OK = resume or start over. */
@Composable
private fun RecentGrid(kind: String, modifier: Modifier) {
    val context = LocalContext.current
    val list = remember(Recents.version) { Recents.all(context, kind) }
    var ask by remember { mutableStateOf<RecentItem?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recently Watched", color = FS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("OK = resume or start over", color = FS.TextDim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (list.isEmpty()) {
            MessageBox("Nothing here yet", "Movies and episodes you start show up here until there are 5 minutes or less left.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(list, key = { it.key }) { r ->
                    val left = ((r.durationMs - r.positionMs) / 60_000).coerceAtLeast(1)
                    Box {
                        Poster(
                            r.title, r.image,
                            progress = (r.positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f),
                            sub = (r.subtitle?.let { "$it · " } ?: "") + "$left min left",
                            onClick = { ask = r },
                        )
                    }
                }
            }
        }
    }
    ask?.let { r ->
        fun play(from: Long?) {
            ask = null
            Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle,
                resumeKey = r.key, kind = r.kind, image = r.image, startMs = from))
        }
        ChoiceDialog(
            title = r.title,
            subtitle = (r.subtitle?.let { "$it\n" } ?: "") + "Watched ${clock(r.positionMs)} of ${clock(r.durationMs)}",
            options = listOf(
                "▶  Resume from ${clock(r.positionMs)}" to { play(r.positionMs) },
                "⏮  Start over" to { play(0L) },
                "✕  Remove from Recently Watched" to { Recents.remove(context, r.key); ask = null },
            ),
            onDismiss = { ask = null },
        )
    }
}

/** Full-screen search: results fill in as you type. */
@Composable
private fun <T> SearchScreen(
    kind: String,
    loadAll: suspend () -> List<T>,
    title: (T) -> String,
    image: (T) -> String?,
    onOpen: (T) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    var query by rememberSaveable { mutableStateOf("") }
    val library by produceState<List<T>?>(null) { value = try { loadAll() } catch (e: Exception) { emptyList() } }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); try { box.requestFocus() } catch (e: Exception) { } }
    var shown by remember { mutableStateOf<List<T>>(emptyList()) }
    LaunchedEffect(query, library) {
        delay(250) // wait for typing to pause
        val q = query.trim()
        val lib = library ?: return@LaunchedEffect
        shown = if (q.isEmpty()) emptyList() else withContext(kotlinx.coroutines.Dispatchers.Default) {
            lib.filter { title(it).contains(q, ignoreCase = true) }
                .sortedBy { if (title(it).startsWith(q, ignoreCase = true)) 0 else 1 }
                .take(300)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().focusRequester(box)) {
            FsTextField("🔍 Search $kind", query, { query = it }, androidx.compose.ui.text.input.KeyboardType.Text)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                library == null -> "Loading the full $kind list…"
                query.isBlank() -> "Start typing a title. BACK to return."
                else -> "${shown.size} results"
            },
            color = FS.TextDim, fontSize = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (shown.isNotEmpty()) PosterGrid(shown, title, image, onOpen)
        else if (query.isNotBlank() && library != null) MessageBox("No $kind found for \"${query.trim()}\"")
    }
}

@Composable
fun MoviesScreen(selected: String?, onSelect: (String?) -> Unit) {
    val src = Session.source ?: return
    var open by remember { mutableStateOf<VodItem?>(null) }
    val o = open
    if (o != null) {
        MovieDetails(o, onBack = { open = null })
        return
    }
    PosterBrowser(
        kind = "movies",
        recentKind = "movie",
        selected = selected,
        onSelect = onSelect,
        loadCats = { src.vodCategories() },
        loadItems = { src.vodItems(it) },
        loadAll = { src.allVod() },
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
                    Playback.one(context, PlayItem(item.name, src.vodUrl(item), live = false,
                        resumeKey = "m:${item.id}", kind = "movie", image = item.poster))
                })
                Pill("Back", selected = false, onClick = onBack)
            }
        }
    }
}

@Composable
fun SeriesScreen(selected: String?, onSelect: (String?) -> Unit) {
    val src = Session.source ?: return
    var open by remember { mutableStateOf<SeriesItem?>(null) }
    val o = open
    if (o != null) {
        SeriesDetails(o, onBack = { open = null })
        return
    }
    PosterBrowser(
        kind = "TV shows",
        recentKind = "episode",
        selected = selected,
        onSelect = onSelect,
        loadCats = { src.seriesCategories() },
        loadItems = { src.seriesItems(it) },
        loadAll = { src.allSeries() },
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
                                        PlayItem("${series.name} · S${it.season}E${it.episodeNum}", src.episodeUrl(it), live = false, subtitle = it.title,
                                            resumeKey = "e:${it.id}", kind = "episode", image = series.cover ?: it.image)
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
