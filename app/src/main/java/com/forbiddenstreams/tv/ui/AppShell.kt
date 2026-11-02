package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.MovieMatcher
import com.forbiddenstreams.tv.data.RecentItem
import com.forbiddenstreams.tv.data.Recents
import com.forbiddenstreams.tv.data.SeriesItem
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.data.Tmdb
import com.forbiddenstreams.tv.data.VodItem
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Tabs across the top, left to right, centered. */
private val TABS = listOf("Home", "Live TV", "Sports", "Movies", "TV Shows")

/**
 * The new app (started from scratch, Nov 1 2026): tabs centered across the top, always opens on Home.
 */
@Composable
fun AppShell(onLogout: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) } // always opens on Home
    var settings by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var tmdbVersion by remember { mutableIntStateOf(0) }
    val tabReq = remember { List(TABS.size) { FocusRequester() } }
    var tabsFocused by remember { mutableStateOf(false) }
    var exitHint by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    LaunchedEffect(Unit) { delay(150); try { tabReq[0].requestFocus() } catch (e: Exception) { } }
    LaunchedEffect(exitHint) { if (exitHint) { delay(2500); exitHint = false } }
    // BACK: from a screen → the tabs; on the tabs → "click back one more time", then close.
    BackHandler {
        when {
            !tabsFocused -> try { tabReq[tab].requestFocus() } catch (e: Exception) { }
            exitHint -> { PlayerHolder.stop(); activity?.finish() }
            else -> exitHint = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            0 -> HomeTab(tmdbVersion, onAddKey = { keyDialog = true })
            1 -> ComingNext("Live TV")
            2 -> ComingNext("Sports")
            3 -> ComingNext("Movies")
            else -> ComingNext("TV Shows")
        }
        TopBar(
            tab = tab, reqs = tabReq,
            onTab = { tab = it },
            onFocusChange = { tabsFocused = it },
            onSettings = { settings = true },
        )
        if (exitHint) {
            Text(
                "Click back one more time to close", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(FS.Green, RoundedCornerShape(50)).padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }

    if (settings) {
        ChoiceDialog(
            title = "Settings",
            options = listOf(
                (if (Tmdb.key(context) == null) "🎬  Add TMDB key" else "🎬  Change TMDB key") to { settings = false; keyDialog = true },
                "⬆  Check for updates" to {
                    settings = false
                    toast(context, "Checking for updates…")
                    UpdateState.check(context, kotlinx.coroutines.MainScope(), manual = true)
                },
                "⎋  Log out" to { settings = false; onLogout() },
                "Close" to { settings = false },
            ),
            onDismiss = { settings = false },
        )
    }
    if (keyDialog) TmdbKeyDialog(onDone = { keyDialog = false; tmdbVersion++ })
    UpdatePrompt()
}

/** Logo on the left, the tabs in the center, clock and ⚙ on the right. */
@Composable
private fun TopBar(tab: Int, reqs: List<FocusRequester>, onTab: (Int) -> Unit, onFocusChange: (Boolean) -> Unit, onSettings: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    Box(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 36.dp)) {
        Image(painterResource(R.drawable.logo), null, Modifier.align(Alignment.CenterStart).size(52.dp))
        Row(
            Modifier.align(Alignment.Center).onFocusChanged { onFocusChange(it.hasFocus) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS.forEachIndexed { i, name ->
                Surface(
                    onClick = { onTab(i) },
                    modifier = Modifier.padding(horizontal = 4.dp).focusRequester(reqs[i]).onFocusChanged { if (it.isFocused) onTab(i) },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (i == tab) Color(0x33000000) else Color.Transparent,
                        contentColor = if (i == tab) FS.Green else FS.Text,
                        focusedContainerColor = FS.Green,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                ) {
                    Text(name, fontSize = 20.sp, fontWeight = if (i == tab) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                onClick = onSettings,
                modifier = Modifier.size(42.dp),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent, focusedContainerColor = FS.Green,
                    contentColor = FS.Green, focusedContentColor = Color.Black,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LineIcon(RailIcon.SETTINGS, 22.dp, androidx.tv.material3.LocalContentColor.current)
                }
            }
        }
        Text(Fmt.time(now), color = FS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun ComingNext(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = FS.Green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Coming next: tell me how you want this tab to look and work.", color = FS.TextDim, fontSize = 18.sp)
        }
    }
}

// ------------------------------------------------------------ Home

/** One card on Home. */
private data class Card(
    val key: String,
    val kind: String,            // "episode" | "movie" | "show"
    val title: String,           // show or movie name (clean)
    val image: String?,          // provider picture (fallback)
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val year: String? = null,
    val progress: Float? = null,
    val recent: RecentItem? = null,
    val vod: VodItem? = null,
    val series: SeriesItem? = null,
    val backdrop: String? = null, // known up front (TMDB lists)
)

/**
 * Home, like the user's photo: a big picture of the highlighted title at the top (date,
 * length, season/episode, story), then rows of wide cards:
 * Continue watching · Shows, Continue watching · Movies, New Movies, Popular TV Shows.
 * (Later: Continue watching · Live TV, then Favorite sports teams.)
 */
@Composable
private fun HomeTab(tmdbVersion: Int, onAddKey: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf<Card?>(null) }
    var menu by remember { mutableStateOf<Card?>(null) }
    var libraryReady by remember { mutableStateOf(MovieMatcher.ready) }
    var seriesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { libraryReady = MovieMatcher.load() }
    LaunchedEffect(Unit) { seriesReady = MovieMatcher.loadSeries() }
    val hasKey = remember(tmdbVersion) { Tmdb.key(context) != null }

    val recents = remember(PlayerHolder.fullScreen, menu) { Recents.all(context) }
    val shows = remember(recents) {
        recents.filter { it.kind == "episode" }.distinctBy { it.title.substringBefore(" · ") }.map { r ->
            val m = Regex("S(\\d+)E(\\d+)").find(r.title)
            Card(
                key = r.key, kind = "episode", title = r.title.substringBefore(" · "), image = r.image,
                season = m?.groupValues?.get(1)?.toIntOrNull(), episode = m?.groupValues?.get(2)?.toIntOrNull(),
                episodeTitle = r.subtitle, progress = if (r.durationMs > 0) r.positionMs.toFloat() / r.durationMs else null, recent = r,
            )
        }
    }
    val movies = remember(recents) {
        recents.filter { it.kind == "movie" }.map { r ->
            Card(r.key, "movie", MovieMatcher.cleanTitle(r.title), r.image, year = MovieMatcher.yearOf(r.title),
                progress = if (r.durationMs > 0) r.positionMs.toFloat() / r.durationMs else null, recent = r)
        }
    }
    val newMovies = remember(libraryReady) {
        MovieMatcher.newest(30).map { Card("v:${it.id}", "movie", MovieMatcher.cleanTitle(it.name), it.poster, year = MovieMatcher.yearOf(it.name), vod = it) }
    }
    val popular by produceState(emptyList<Card>(), tmdbVersion, seriesReady) {
        if (!hasKey || !seriesReady) return@produceState
        value = Tmdb.popularShows(context).mapNotNull { (name, backdrop) ->
            MovieMatcher.findSeries(name)?.let { s -> Card("s:${s.id}", "show", name, s.cover, series = s, backdrop = backdrop) }
        }.distinctBy { it.key }.take(30)
    }
    LaunchedEffect(shows, movies, newMovies) { if (focused == null) focused = shows.firstOrNull() ?: movies.firstOrNull() ?: newMovies.firstOrNull() }

    Column(Modifier.fillMaxSize()) {
        Hero(focused, Modifier.fillMaxWidth().height(330.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(start = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (shows.isNotEmpty()) item { CardRow("Continue watching", "Shows", RailIcon.SHOWS, shows, { focused = it }, { menu = it }) }
            if (movies.isNotEmpty()) item { CardRow("Continue watching", "Movies", RailIcon.MOVIES, movies, { focused = it }, { menu = it }) }
            if (newMovies.isNotEmpty()) item { CardRow("New Movies", null, RailIcon.MOVIES, newMovies, { focused = it }, { menu = it }) }
            else if (!libraryReady) item { Text("Loading new movies…", color = FS.TextDim, fontSize = 15.sp) }
            if (popular.isNotEmpty()) item { CardRow("Popular TV Shows", null, RailIcon.SHOWS, popular, { focused = it }, { menu = it }) }
            if (!hasKey) {
                item {
                    FocusCard(onClick = onAddKey, modifier = Modifier.padding(end = 36.dp)) {
                        Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
                            Text("🎬  Add your free TMDB key", color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Big pictures, episode details and Popular TV Shows come from The Movie Database.",
                                color = FS.TextDim, fontSize = 14.sp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
    menu?.let { CardMenu(it, onClose = { menu = null }) }
}

/** TMDB artwork + details for a card (cached by TMDB lookups). */
@Composable
private fun cardArt(c: Card?): Tmdb.Art? {
    val context = LocalContext.current
    val art by produceState<Tmdb.Art?>(null, c?.key) {
        value = null
        if (c == null) return@produceState
        value = when (c.kind) {
            "movie" -> Tmdb.movieArt(context, c.title, c.year)
            else -> Tmdb.showArt(context, c.title, c.season, c.episode)
        }
    }
    return art
}

/** Top of Home: the highlighted title's big picture, fading into black on the left and bottom. */
@Composable
private fun Hero(c: Card?, modifier: Modifier) {
    val art = cardArt(c)
    Box(modifier) {
        val pic = art?.backdrop ?: c?.backdrop ?: c?.image
        if (pic != null) {
            NetImage(pic, Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.68f), crop = true)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.30f to FS.Bg, 0.55f to Color(0x99040604), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to FS.Bg)))
        }
        if (c != null) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 10.dp).width(560.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(art?.title ?: c.title, color = FS.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 42.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    art?.date?.let { d ->
                        Text("📅  " + niceDate(d), color = FS.Text, fontSize = 16.sp)
                        Spacer(Modifier.width(18.dp))
                    }
                    art?.runtimeMin?.let { Text("⏱  $it Min", color = FS.Text, fontSize = 16.sp) }
                }
                if (c.season != null && c.episode != null) {
                    Text("S${c.season}:E${c.episode}" + ((art?.episodeName ?: c.episodeTitle)?.let { " - $it" } ?: ""),
                        color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                art?.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = FS.Text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
            }
        }
    }
}

private fun niceDate(d: String): String = try {
    SimpleDateFormat("dd MMM yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!!)
} catch (e: Exception) { d }

/** A row like the photo: icon + "Continue watching · Shows" and "Press and hold OK for more" on the right. */
@Composable
private fun CardRow(title: String, sub: String?, icon: RailIcon, cards: List<Card>, onFocus: (Card) -> Unit, onMore: (Card) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(end = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(icon, 22.dp, FS.Green)
            Spacer(Modifier.width(10.dp))
            Text(title, color = FS.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            sub?.let { Text("  ·  $it", color = FS.TextDim, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            Text("Press and hold \"OK\" for more", color = FS.TextDim, fontSize = 13.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(cards, key = { _, c -> c.key }) { _, c ->
                WideCard(c, onFocus = { onFocus(c) }, onClick = { playCard(context, c) }, onLongClick = { onMore(c) })
            }
            item { Spacer(Modifier.width(24.dp)) }
        }
    }
}

/** Wide picture card with the green "how far you are" bar inside it, name under it. */
@Composable
private fun WideCard(c: Card, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit) {
    val art = cardArt(c)
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(300.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().height(169.dp).onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = FS.Panel2, focusedContainerColor = FS.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, FS.Green), shape = shape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        ) {
            Box(Modifier.fillMaxSize()) {
                val pic = c.backdrop ?: art?.backdrop ?: c.image
                if (pic != null) NetImage(pic, Modifier.fillMaxSize(), crop = true)
                else Text(c.title, color = FS.Text, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(12.dp))
                c.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth().height(5.dp)
                        .background(Color(0x99FFFFFF), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0.03f, 1f)).height(5.dp).background(FS.Green, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
        Text(c.title, color = FS.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** OK on a card: resume / play right away. */
private fun playCard(context: android.content.Context, c: Card) {
    val src = Session.source ?: return
    val r = c.recent
    when {
        r != null -> Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle,
            resumeKey = r.key, kind = r.kind, image = r.image, startMs = r.positionMs))
        c.vod != null -> {
            val saved = Recents.get(context, "m:${c.vod.id}")
            Playback.one(context, PlayItem(c.title, src.vodUrl(c.vod), live = false, resumeKey = "m:${c.vod.id}",
                kind = "movie", image = c.vod.poster, startMs = saved?.positionMs))
        }
        c.series != null -> playShow(context, c.series)
    }
}

/** A show from Popular TV Shows: the next episode after the last one you watched, else S1 E1. */
private fun playShow(context: android.content.Context, s: SeriesItem) {
    val src = Session.source ?: return
    kotlinx.coroutines.MainScope().launch {
        val eps = try { src.episodes(s) } catch (e: Exception) { emptyList() }
            .sortedWith(compareBy({ it.season }, { it.episodeNum }))
        if (eps.isEmpty()) { toast(context, "No episodes found for ${s.name}"); return@launch }
        val items = eps.map {
            PlayItem("${s.name} · S${it.season}E${it.episodeNum}", src.episodeUrl(it), live = false, subtitle = it.title,
                resumeKey = "e:${it.id}", kind = "episode", image = s.cover ?: it.image)
        }
        val lastSeen = Recents.all(context, "episode").firstOrNull { it.title.startsWith(s.name + " · ") }
        val i = lastSeen?.let { r -> items.indexOfFirst { it.resumeKey == r.key } }?.takeIf { it >= 0 } ?: 0
        Playback.start(context, items, i)
    }
}

/** Hold OK on a card: more choices. */
@Composable
private fun CardMenu(c: Card, onClose: () -> Unit) {
    val context = LocalContext.current
    val r = c.recent
    val opts = ArrayList<Pair<String, () -> Unit>>()
    if (r != null) {
        opts += "▶  Resume" to { onClose(); playCard(context, c) }
        opts += "⟲  Start over" to {
            onClose()
            Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle, resumeKey = r.key, kind = r.kind, image = r.image))
        }
        opts += "✖  Remove from Continue watching" to { Recents.remove(context, r.key); onClose(); toast(context, "Removed") }
    } else {
        opts += "▶  Play" to { onClose(); playCard(context, c) }
    }
    opts += "Close" to onClose
    val sub = listOfNotNull(
        if (c.season != null) "S${c.season} · E${c.episode}" + (c.episodeTitle?.let { " · $it" } ?: "") else null,
        c.year,
    ).joinToString("  ·  ").ifBlank { null }
    ChoiceDialog(title = c.title, subtitle = sub, options = opts, onDismiss = onClose)
}

/** Settings › TMDB key: paste the free key from themoviedb.org (Settings › API). */
@Composable
private fun TmdbKeyDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(Tmdb.key(context) ?: "") }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); try { box.requestFocus() } catch (e: Exception) { } }
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.width(640.dp).background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("TMDB key", color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Free at themoviedb.org → sign up → Settings → API. Paste the \"API Key\" (or the long \"Read Access Token\").",
                color = FS.TextDim, fontSize = 15.sp)
            Box(Modifier.fillMaxWidth().focusRequester(box)) {
                FsTextField("TMDB key", key, { key = it }, androidx.compose.ui.text.input.KeyboardType.Text,
                    done = { Tmdb.setKey(context, key); onDone() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Save", selected = true, onClick = { Tmdb.setKey(context, key); toast(context, "TMDB key saved"); onDone() })
                Pill("Cancel", selected = false, onClick = onDone)
            }
        }
    }
}
