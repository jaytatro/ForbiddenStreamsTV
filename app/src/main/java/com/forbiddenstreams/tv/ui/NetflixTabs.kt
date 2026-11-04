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
@Composable
private fun BrowseTab(movies: Boolean) {
    val context = LocalContext.current
    val tick = PlayerHolder.fullScreen // refresh "Continue watching" after watching
    val shelves by produceState<List<Shelf>?>(null, movies, Session.source) {
        val src = Session.source ?: return@produceState
        value = try {
            if (movies) {
                val cats = src.vodCategories()
                val all = src.allVod()
                val byCat = all.groupBy { it.categoryId }
                fun t(v: VodItem) = Tile("m:${v.id}", MovieMatcher.cleanTitle(v.name), v.poster, vod = v)
                listOf(Shelf("Recently added", all.filter { it.added > 0 }.sortedByDescending { it.added }.take(40).map(::t))) +
                    cats.mapNotNull { c -> byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { Shelf(c.name, it.map(::t)) } }
            } else {
                val cats = src.seriesCategories()
                val all = src.allSeries()
                val byCat = all.groupBy { it.categoryId }
                fun t(s: SeriesItem) = Tile("s:${s.id}", MovieMatcher.cleanTitle(s.name), s.cover, series = s)
                listOf(Shelf("Recently updated", all.filter { it.lastModified > 0 }.sortedByDescending { it.lastModified }.take(40).map(::t))) +
                    cats.mapNotNull { c -> byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { Shelf(c.name, it.map(::t)) } }
            }.filter { it.tiles.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
    }
    val continueRow = remember(movies, tick) {
        val r = Recents.all(context, if (movies) "movie" else "episode")
        val list = if (movies) r else r.distinctBy { it.title.substringBefore(" · ") }
        list.map { x ->
            Tile(x.key, if (movies) MovieMatcher.cleanTitle(x.title) else x.title.substringBefore(" · "), x.image, recent = x,
                progress = if (x.durationMs > 0) x.positionMs.toFloat() / x.durationMs else null,
                sub = if (movies) null else Regex("S\\d+E\\d+").find(x.title)?.value)
        }
    }

    var focused by remember { mutableStateOf<Tile?>(null) }
    var open by remember { mutableStateOf<Tile?>(null) }
    val list = shelves

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BigPicture(focused, movies, Modifier.fillMaxWidth().height(360.dp))
        when {
            list == null -> LoadingBox(if (movies) "Loading movies" else "Loading TV shows")
            list.isEmpty() && continueRow.isEmpty() -> MessageBox(
                if (movies) "No movies" else "No TV shows", "Your provider didn't send any.")
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
            ) {
                if (continueRow.isNotEmpty()) item(key = "continue") {
                    PosterShelf(Shelf("Continue watching", continueRow), onFocus = { focused = it }, onOpen = { open = it })
                }
                itemsIndexed(list, key = { i, s -> "$i:${s.title}" }) { _, s ->
                    PosterShelf(s, onFocus = { focused = it }, onOpen = { open = it })
                }
            }
        }
    }
    LaunchedEffect(list, continueRow) { if (focused == null) focused = continueRow.firstOrNull() ?: list?.firstOrNull()?.tiles?.firstOrNull() }

    open?.let { t ->
        val sameCat = remember(t.key, list) {
            val cat = t.vod?.categoryId ?: t.series?.categoryId
            list.orEmpty().flatMap { it.tiles }.filter { x -> cat != null && (x.vod?.categoryId ?: x.series?.categoryId) == cat && x.key != t.key }
                .distinctBy { it.key }.take(30)
        }
        TitlePage(t, movies, sameCat, onOpen = { open = it }, onClose = { open = null })
    }
}

/** Row title + a sideways row of posters. */
@Composable
private fun PosterShelf(s: Shelf, onFocus: (Tile) -> Unit, onOpen: (Tile) -> Unit) {
    val context = LocalContext.current
    Column {
        Text(s.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 40.dp, top = 4.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp),
        ) {
            items(s.tiles, key = { it.key }) { t ->
                PosterCard(t, onFocus = { onFocus(t) },
                    onClick = { if (t.recent != null) resume(context, t.recent) else onOpen(t) },
                    onLongClick = { onOpen(t) })
            }
        }
    }
}

@Composable
private fun PosterCard(t: Tile, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = Modifier.width(140.dp).height(210.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = FS.Panel2, focusedContainerColor = FS.Panel2),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The name shows until the picture loads (or when there's no picture).
            Text(t.title, color = FS.TextDim, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(10.dp))
            NetImage(t.poster, Modifier.fillMaxSize(), crop = true)
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
            itemsIndexed(inSeason, key = { _, e -> e.id }) { _, e ->
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
