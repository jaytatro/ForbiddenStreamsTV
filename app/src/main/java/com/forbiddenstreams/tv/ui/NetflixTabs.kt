package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.Episode
import com.forbiddenstreams.tv.data.MovieMatcher
import com.forbiddenstreams.tv.data.ProviderArt
import com.forbiddenstreams.tv.data.RecentItem
import com.forbiddenstreams.tv.data.Recents
import com.forbiddenstreams.tv.data.SeriesItem
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.data.VodItem
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/** One poster in a row: a movie, a show, or something you're part-way through. */
private data class Tile(
    val key: String,
    val title: String,
    val poster: String?,
    val vod: VodItem? = null,
    val series: SeriesItem? = null,
    val recent: RecentItem? = null,
    val progress: Float? = null,
    val sub: String? = null,
)

private class Shelf(val title: String, tiles: List<Tile>) { val tiles = tiles.distinctBy { it.key } }

@Composable fun MoviesTab() = BrowseTab(movies = true)
@Composable fun ShowsTab() = BrowseTab(movies = false)

/**
 * Movies / TV Shows, Netflix style (Nov 3 2026): a big picture of the highlighted title at the top
 * (title, year · length · rating, story), then rows of posters: Continue watching, Recently added,
 * then one row per provider category. OK on a poster = its page (Play / Resume / Start over /
 * Trailer; shows: seasons and episodes; More like this).
 */
/**
 * The movie / show lists, kept for the whole session so the tabs open instantly the second time.
 * Fast start: only the category list is downloaded first; each row downloads its own (small)
 * category when it comes on screen. The big full list (for "Recently added") loads afterwards
 * in the background. Heavy sorting and name cleaning run off the screen thread.
 */
private object VodLib {
    val cats = HashMap<Boolean, List<com.forbiddenstreams.tv.data.Category>>()
    val rows = HashMap<String, List<Tile>>()
    val recent = HashMap<Boolean, List<Tile>>()
    private val gate = kotlinx.coroutines.sync.Semaphore(3)

    fun reset() { cats.clear(); rows.clear(); recent.clear() }

    fun tileOf(v: VodItem) = Tile("m:${v.id}", MovieMatcher.cleanTitle(v.name), v.poster, vod = v)
    fun tileOf(s: SeriesItem) = Tile("s:${s.id}", MovieMatcher.cleanTitle(s.name), s.cover, series = s)

    suspend fun cats(movies: Boolean): List<com.forbiddenstreams.tv.data.Category> {
        cats[movies]?.let { return it }
        val src = Session.source ?: return emptyList()
        val c = (if (movies) src.vodCategories() else src.seriesCategories()).distinctBy { it.id }
        cats[movies] = c
        return c
    }

    suspend fun row(movies: Boolean, catId: String): List<Tile> {
        val key = (if (movies) "m:" else "s:") + catId
        rows[key]?.let { return it }
        val src = Session.source ?: return emptyList()
        val out = gate.withPermit {
            rows[key] ?: try {
                if (movies) { val l = src.vodItems(catId); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { l.map { tileOf(it) }.distinctBy { it.key } } }
                else { val l = src.seriesItems(catId); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { l.map { tileOf(it) }.distinctBy { it.key } } }
            } catch (e: Exception) { emptyList() }
        }
        rows[key] = out
        return out
    }

    suspend fun recent(movies: Boolean): List<Tile> {
        recent[movies]?.let { return it }
        val src = Session.source ?: return emptyList()
        val out = try {
            if (movies) {
                val all = src.allVod()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    all.filter { it.added > 0 }.sortedByDescending { it.added }.take(40).map { tileOf(it) }.distinctBy { it.key }
                }
            } else {
                val all = src.allSeries()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    all.filter { it.lastModified > 0 }.sortedByDescending { it.lastModified }.take(40).map { tileOf(it) }.distinctBy { it.key }
                }
            }
        } catch (e: Throwable) { emptyList() } // includes running out of memory on huge lists
        recent[movies] = out
        return out
    }
}

/** Starts loading the movie and show categories in the background (called once the app is up). */
suspend fun prefetchLibrary() {
    try { VodLib.cats(true); VodLib.cats(false) } catch (e: Exception) { }
}

fun resetLibrary() = VodLib.reset()

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BrowseTab(movies: Boolean) {
    val context = LocalContext.current
    val tick = PlayerHolder.fullScreen // refresh "Continue watching" after watching
    val cats by produceState(VodLib.cats[movies], movies, Session.source) {
        if (value == null) value = try { VodLib.cats(movies) } catch (e: Exception) { emptyList() }
    }
    val recentRow by produceState(VodLib.recent[movies], movies, Session.source) {
        if (value == null) { delay(2500); value = VodLib.recent(movies) }
    }
    var ask by remember { mutableStateOf<RecentItem?>(null) }
    val continueRow = remember(movies, tick, ask) { keepTiles(context, movies) }
    ask?.let { r -> ResumeReplayDialog(r, onClose = { ask = null }) }

    var focused by remember { mutableStateOf<Tile?>(null) }
    var open by remember { mutableStateOf<Tile?>(null) }
    val recentReq = remember { FocusRequester() }
    var recentReady by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val recentRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // ▼ on the Movies / TV Shows tab: the newest title, far left of Recently added / updated.
    val goRecent: () -> Boolean = {
        if (recentRow.isNullOrEmpty()) false else {
            scope.launch {
                try { listState.scrollToItem(if (continueRow.isNotEmpty()) 1 else 0) } catch (e: Exception) { }
                try { recentRowState.scrollToItem(0) } catch (e: Exception) { }
                repeat(10) {
                    delay(60)
                    if (recentReady) try { recentReq.requestFocus(); return@launch } catch (e: Exception) { }
                }
            }
            true
        }
    }
    androidx.compose.runtime.DisposableEffect(goRecent) {
        TabDown.handler = goRecent
        onDispose { if (TabDown.handler === goRecent) TabDown.handler = null }
    }
    val list = cats

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BigPicture(focused, movies, Modifier.fillMaxWidth().height(330.dp))
        when {
            list == null -> LoadingBox(if (movies) "Loading movies" else "Loading TV shows")
            list.isEmpty() && continueRow.isEmpty() -> MessageBox(
                if (movies) "No movies" else "No TV shows", "Your provider didn't send any.")
            else -> LazyColumn(
                state = listState,
                // ▼ from the tabs always lands on the first poster of Recently added / updated.
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .focusProperties { enter = { if (recentReady) recentReq else FocusRequester.Default } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                if (continueRow.isNotEmpty()) item(key = "continue") {
                    PosterShelf(Shelf("Keep watching", continueRow), onFocus = { focused = it }, onOpen = { open = it }, onRecent = { ask = it })
                }
                item(key = "recent") {
                    val r = recentRow
                    if (r == null) LoadingShelf(if (movies) "Recently added" else "Recently updated")
                    else if (r.isNotEmpty()) PosterShelf(Shelf(if (movies) "Recently added" else "Recently updated", r),
                        onFocus = { focused = it }, onOpen = { open = it },
                        firstReq = recentReq, onFirstAttached = { recentReady = it }, rowState = recentRowState)
                }
                itemsIndexed(list, key = { i, c -> "$i:${c.id}" }) { _, c ->
                    // Each row downloads its own category when it comes on screen.
                    val tiles by produceState(VodLib.rows[(if (movies) "m:" else "s:") + c.id], c.id) {
                        if (value == null) value = VodLib.row(movies, c.id)
                    }
                    val t = tiles
                    if (t == null) LoadingShelf(c.name)
                    else if (t.isNotEmpty()) PosterShelf(Shelf(c.name, t), onFocus = { focused = it }, onOpen = { open = it })
                }
            }
        }
    }
    LaunchedEffect(continueRow, recentRow) { if (focused == null) focused = continueRow.firstOrNull() ?: recentRow?.firstOrNull() }

    open?.let { t ->
        val cat = t.vod?.categoryId ?: t.series?.categoryId
        val more = remember(t.key) {
            VodLib.rows[(if (movies) "m:" else "s:") + cat].orEmpty().filter { it.key != t.key }.take(30)
        }
        TitlePage(t, movies, more, onOpen = { open = it }, onClose = { open = null })
    }
}

/** A row's place while it downloads: its name and grey poster shapes (so the screen doesn't jump). */
@Composable
private fun LoadingShelf(title: String) {
    Column {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 40.dp, top = 4.dp))
        Row(Modifier.padding(horizontal = 40.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(7) { Box(Modifier.width(120.dp).height(180.dp).background(FS.Panel2, RoundedCornerShape(6.dp))) }
        }
    }
}

/** Row title + a sideways row of posters. */
@Composable
private fun PosterShelf(s: Shelf, onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit, firstReq: FocusRequester? = null, onFirstAttached: (Boolean) -> Unit = {},
    rowState: androidx.compose.foundation.lazy.LazyListState? = null, onRecent: ((RecentItem) -> Unit)? = null) {
    val context = LocalContext.current
    Column {
        Text(s.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 40.dp, top = 4.dp))
        LazyRow(
            state = rowState ?: androidx.compose.foundation.lazy.rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 16.dp),
        ) {
            itemsIndexed(s.tiles, key = { _, t -> t.key }) { i, t ->
                if (i == 0 && firstReq != null) {
                    androidx.compose.runtime.DisposableEffect(Unit) { onFirstAttached(true); onDispose { onFirstAttached(false) } }
                }
                PosterCard(t, onFocus = { onFocus(t) },
                    modifier = if (i == 0 && firstReq != null) Modifier.focusRequester(firstReq) else Modifier,
                    onClick = { if (t.recent != null) (onRecent ?: { r -> resume(context, r) })(t.recent) else onOpen(t) },
                    onLongClick = { onOpen(t) })
            }
        }
    }
}

@Composable
private fun PosterCard(t: Tile, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.width(120.dp).height(180.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = FS.Panel2, focusedContainerColor = FS.Panel2),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The name shows until the picture loads (or when there's no picture).
            Text(t.title, color = FS.TextDim, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp))
            NetImage(t.poster, Modifier.fillMaxSize(), crop = false)
            t.sub?.let {
                Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            t.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(Color(0x99FFFFFF))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).height(5.dp).background(FS.Green))
                }
            }
        }
    }
}

/** Provider details for a tile (cached), loaded after a short pause so scrolling stays smooth. */
@Composable
private fun tileArt(t: Tile?): ProviderArt.Art? {
    val art by produceState<ProviderArt.Art?>(null, t?.key) {
        value = null
        if (t == null) return@produceState
        delay(350)
        value = when {
            t.series != null -> ProviderArt.show(t.series)
            t.vod != null -> ProviderArt.movie(t.vod.id, t.vod.name)
            t.recent?.kind == "movie" -> ProviderArt.movie(t.recent.key.removePrefix("m:"), t.recent.title)
            t.recent != null -> {
                val m = Regex("S(\\d+)E(\\d+)").find(t.recent.title)
                ProviderArt.episode(t.title, t.recent.key.removePrefix("e:"), m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull())
            }
            else -> null
        }
    }
    return art
}

private fun metaLine(t: Tile, art: ProviderArt.Art?): String {
    val year = (art?.date ?: t.vod?.releaseDate ?: t.series?.releaseDate)?.take(4)?.takeIf { it.matches(Regex("\\d{4}")) }
        ?: MovieMatcher.yearOf(t.vod?.name ?: t.series?.name ?: t.title)
    val rating = (t.vod?.rating ?: t.series?.rating)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
    val len = art?.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
    return listOfNotNull(year, len, rating).joinToString("   ·   ")
}

/** Top of the screen: the highlighted title's wide picture fading into black, with its name and story. */
@Composable
private fun BigPicture(t: Tile?, movies: Boolean, modifier: Modifier) {
    val art = tileArt(t)
    Box(modifier) {
        val pic = art?.backdrop ?: t?.series?.backdrop
        if (pic != null) {
            NetImage(pic, Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.7f), crop = true)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.28f to Color.Black, 0.55f to Color(0x99000000), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black)))
        }
        if (t != null) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 8.dp).width(600.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (movies) "MOVIE" else "SERIES", color = FS.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(t.title, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                val meta = metaLine(t, art)
                if (meta.isNotBlank()) Text(meta, color = FS.Text, fontSize = 16.sp)
                art?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = FS.Text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
                Text(if (t.recent != null) "OK = resume  ·  Hold OK = details" else "OK = details", color = FS.TextDim, fontSize = 13.sp)
            }
        }
    }
}

private fun resume(context: android.content.Context, r: RecentItem) =
    Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle, resumeKey = r.key, kind = r.kind, image = r.image, startMs = r.positionMs))

/** A title's own page (full screen): big picture, buttons, episodes for shows, More like this. */
@Composable
private fun TitlePage(t: Tile, movies: Boolean, more: List<Tile>, onOpen: (Tile) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val src = Session.source ?: return
    // A show from Continue watching: find the show itself.
    val series = t.series ?: if (!movies) remember(t.key) { MovieMatcher.findSeries(t.title) } else null
    val vodId = t.vod?.id ?: t.recent?.key?.takeIf { it.startsWith("m:") }?.removePrefix("m:")
    val art = tileArt(t)
    val playReq = remember(t.key) { FocusRequester() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { onClose() }
        LaunchedEffect(t.key) { repeat(8) { delay(120); try { playReq.requestFocus(); return@LaunchedEffect } catch (e: Exception) { } } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pic = art?.backdrop ?: series?.backdrop
            if (pic != null) {
                NetImage(pic, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.75f).height(480.dp), crop = true)
                Box(Modifier.fillMaxWidth().height(480.dp).background(Brush.horizontalGradient(0.25f to Color.Black, 0.6f to Color(0x88000000), 1f to Color.Transparent)))
                Box(Modifier.fillMaxWidth().height(480.dp).background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)))
            }
            val eps by produceState<List<Episode>?>(null, series?.id) {
                value = if (series == null) emptyList() else try { src.episodes(series) } catch (e: Exception) { emptyList() }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 120.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Column(Modifier.padding(start = 48.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t.title, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, lineHeight = 48.sp)
                        val meta = metaLine(t, art)
                        if (meta.isNotBlank()) Text(meta, color = FS.Text, fontSize = 17.sp)
                        (art?.overview ?: series?.plot)?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = FS.Text, fontSize = 16.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (movies && vodId != null) {
                                val saved = remember(vodId, PlayerHolder.fullScreen) { Recents.get(context, "m:$vodId") }
                                val url = t.vod?.let { src.vodUrl(it) } ?: t.recent?.url
                                fun play(from: Long?) {
                                    if (url == null) return
                                    Playback.one(context, PlayItem(t.vod?.name ?: t.recent?.title ?: t.title, url, live = false,
                                        resumeKey = "m:$vodId", kind = "movie", image = t.poster, startMs = from))
                                }
                                Pill(if (saved != null) "▶  Resume" else "▶  Play", selected = true, fontSize = 18.sp,
                                    modifier = Modifier.focusRequester(playReq), onClick = { play(saved?.positionMs) })
                                if (saved != null) Pill("⟲  Start over", selected = false, fontSize = 17.sp, onClick = { play(null) })
                                Pill("🎬  Trailer", selected = false, fontSize = 17.sp,
                                    onClick = { openTrailer(context, vodId, t.title, MovieMatcher.yearOf(t.vod?.name ?: t.title)) })
                            } else if (series != null) {
                                val list = eps.orEmpty().sortedWith(compareBy({ it.season }, { it.episodeNum }))
                                val last = remember(series.id, PlayerHolder.fullScreen) {
                                    Recents.all(context, "episode").firstOrNull { it.title.startsWith(MovieMatcher.cleanTitle(series.name) + " · ") || it.title.startsWith(series.name + " · ") }
                                }
                                val lastEp = last?.let { r -> list.firstOrNull { "e:${it.id}" == r.key } }
                                Pill(
                                    when { lastEp != null -> "▶  Resume S${lastEp.season}:E${lastEp.episodeNum}"; list.isNotEmpty() -> "▶  Play S${list[0].season}:E${list[0].episodeNum}"; else -> "▶  Play" },
                                    selected = true, fontSize = 18.sp, modifier = Modifier.focusRequester(playReq),
                                    onClick = { if (list.isNotEmpty()) playEpisodes(context, series, list, lastEp ?: list[0], last?.positionMs) },
                                )
                                Pill("🎬  Trailer", selected = false, fontSize = 17.sp,
                                    onClick = { openTrailer(context, "", MovieMatcher.cleanTitle(series.name), null) })
                            } else {
                                Pill("▶  Resume", selected = true, fontSize = 18.sp, modifier = Modifier.focusRequester(playReq),
                                    onClick = { t.recent?.let { resume(context, it) } })
                            }
                        }
                    }
                }
                // Shows: season tabs and the season's episodes.
                if (series != null) item {
                    val list = eps
                    if (list == null) LoadingBox("Loading episodes")
                    else if (list.isNotEmpty()) EpisodeShelf(series, list)
                }
                if (more.isNotEmpty()) item {
                    PosterShelf(Shelf("More like this", more), onFocus = {}, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun EpisodeShelf(series: SeriesItem, all: List<Episode>) {
    val context = LocalContext.current
    val sorted = remember(all) { all.sortedWith(compareBy({ it.season }, { it.episodeNum })) }
    val seasons = remember(sorted) { sorted.map { it.season }.distinct() }
    var season by remember(series.id) { mutableIntStateOf(seasons.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 48.dp)) {
            items(seasons) { s ->
                Pill("Season $s", selected = s == season, outlined = true, fontSize = 16.sp, onClick = { season = s },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) season = s })
            }
        }
        val inSeason = sorted.filter { it.season == season }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp)) {
            itemsIndexed(inSeason, key = { i, e -> "$i:${e.id}" }) { _, e ->
                val saved = remember(e.id, PlayerHolder.fullScreen) { Recents.get(context, "e:${e.id}") }
                val shape = RoundedCornerShape(6.dp)
                Column(Modifier.width(280.dp)) {
                    Surface(
                        onClick = { playEpisodes(context, series, sorted, e, saved?.positionMs) },
                        modifier = Modifier.fillMaxWidth().height(158.dp),
                        shape = ClickableSurfaceDefaults.shape(shape = shape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = FS.Panel2, focusedContainerColor = FS.Panel2),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            NetImage(e.image ?: series.backdrop ?: series.cover, Modifier.fillMaxSize(), crop = true)
                            Text("E${e.episodeNum}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            if (saved != null && saved.durationMs > 0) {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp).background(Color(0x99FFFFFF))) {
                                    Box(Modifier.fillMaxWidth((saved.positionMs.toFloat() / saved.durationMs).coerceIn(0.03f, 1f)).height(5.dp).background(FS.Green))
                                }
                            }
                        }
                    }
                    Text(e.title, color = FS.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    e.minutes?.let { Text("$it min", color = FS.TextDim, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** Plays [from] and keeps going into the next episodes. */
private fun playEpisodes(context: android.content.Context, s: SeriesItem, sorted: List<Episode>, from: Episode, startMs: Long?) {
    val src = Session.source ?: return
    val name = MovieMatcher.cleanTitle(s.name)
    val items = sorted.map {
        PlayItem("$name · S${it.season}E${it.episodeNum}", src.episodeUrl(it), live = false, subtitle = it.title,
            resumeKey = "e:${it.id}", kind = "episode", image = s.cover ?: it.image)
    }
    val i = sorted.indexOf(from).coerceAtLeast(0)
    Playback.start(context, items.mapIndexed { j, it -> if (j == i && startMs != null) it.copy(startMs = startMs) else it }, i)
}


/** Movies (true), episodes (false) or both (null) you stopped part-way, newest first; one per show. */
private fun keepTiles(context: android.content.Context, movies: Boolean?): List<Tile> {
    val all = Recents.all(context, when (movies) { true -> "movie"; false -> "episode"; null -> null })
        .filter { it.kind == "movie" || it.kind == "episode" }
        .sortedByDescending { it.updatedAt }
        .distinctBy { if (it.kind == "episode") "show:" + it.title.substringBefore(" · ") else it.key }
    return all.map { x ->
        val ep = x.kind == "episode"
        Tile(x.key, if (ep) x.title.substringBefore(" · ") else MovieMatcher.cleanTitle(x.title), x.image, recent = x,
            progress = if (x.durationMs > 0) x.positionMs.toFloat() / x.durationMs else null,
            sub = if (ep) Regex("S\\d+E\\d+").find(x.title)?.value else null)
    }
}

/** OK on something in Keep watching: Resume (where you stopped) or Replay (from the start). */
@Composable
fun ResumeReplayDialog(r: RecentItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val left = if (r.durationMs > 0) ((r.durationMs - r.positionMs) / 60_000).coerceAtLeast(1) else null
    val name = if (r.kind == "episode") r.title.replace(" · ", "  ·  ") else MovieMatcher.cleanTitle(r.title)
    ChoiceDialog(
        title = name,
        subtitle = listOfNotNull(r.subtitle?.takeIf { r.kind == "episode" }, left?.let { "$it min left" }).joinToString("  ·  ").ifBlank { null },
        options = listOf(
            "▶  Resume" to { onClose(); resume(context, r) },
            "⟲  Replay" to {
                onClose()
                Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle, resumeKey = r.key, kind = r.kind, image = r.image))
            },
            "✖  Remove from Keep watching" to {
                // A show is removed as a whole (all its started episodes), so an older episode doesn't pop back up.
                if (r.kind == "episode") {
                    val show = r.title.substringBefore(" · ") + " · "
                    Recents.all(context, "episode").filter { it.title.startsWith(show) }.forEach { Recents.remove(context, it.key) }
                } else Recents.remove(context, r.key)
                toast(context, "Removed from Keep watching")
                onClose()
            },
            "Close" to onClose,
        ),
        onDismiss = onClose,
    )
}

/**
 * Home's Keep watching row: movies and episodes you stopped part-way (posters with the green bar).
 * OK = Resume / Replay pop-up. Hold OK = [onMore] (Home's options).
 */
@Composable
fun KeepWatchingRow(refresh: Any?, onFocusKey: (String) -> Unit, onMore: (String) -> Unit) {
    val context = LocalContext.current
    var ask by remember { mutableStateOf<RecentItem?>(null) }
    val tiles = remember(refresh, ask, PlayerHolder.fullScreen) { keepTiles(context, null) }
    if (tiles.isEmpty()) return
    PosterShelf(Shelf("Keep watching", tiles), onFocus = { onFocusKey(it.key) }, onOpen = { onMore(it.key) }, onRecent = { ask = it })
    ask?.let { r -> ResumeReplayDialog(r, onClose = { ask = null }) }
}
