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
import androidx.compose.foundation.layout.heightIn
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
import com.forbiddenstreams.tv.data.ProviderArt
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
    val tabReq = remember { List(TABS.size) { FocusRequester() } }
    var tabsFocused by remember { mutableStateOf(false) }
    var exitHint by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    LaunchedEffect(Unit) { delay(150); try { tabReq[0].requestFocus() } catch (e: Exception) { } }
    LaunchedEffect(exitHint) { if (exitHint) { delay(2500); exitHint = false } }
    // Leaving Live TV (or coming back from the player to another tab) stops the live channel.
    LaunchedEffect(tab, PlayerHolder.fullScreen) {
        if (tab != 1 && !PlayerHolder.fullScreen && PlayerHolder.current?.live == true) PlayerHolder.stop()
    }
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
            0 -> HomeTab()
            1 -> LiveTvTab()
            2 -> SportsTab()
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
    val backdrop: String? = null, // known up front
    val channel: com.forbiddenstreams.tv.data.LiveChannel? = null,
)

/**
 * Home, like the user's photo: a big picture of the highlighted title at the top (date,
 * length, season/episode, story), then rows of wide cards:
 * Continue watching · Shows, Continue watching · Movies, New Movies, Popular TV Shows.
 * (Later: Continue watching · Live TV, then Favorite sports teams.)
 */
@Composable
private fun HomeTab() {
    val context = LocalContext.current
    var focused by remember { mutableStateOf<Card?>(null) }
    var menu by remember { mutableStateOf<Card?>(null) }
    var showPick by remember { mutableStateOf<SeriesItem?>(null) }
    // The provider's show list (for "Seasons & episodes" and episode details).
    LaunchedEffect(Unit) { MovieMatcher.loadSeries() }

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
    // Live channels you watched lately (newest first), with what's on now.
    val channels = remember(recents, Session.liveAll) {
        com.forbiddenstreams.tv.data.Prefs(context).history.mapNotNull { Session.channelById(it) }.take(20).map { ch ->
            Card("c:${ch.id}", "channel", ch.name, ch.logo, channel = ch)
        }
    }
    LaunchedEffect(shows, movies, channels) { if (focused == null) focused = shows.firstOrNull() ?: movies.firstOrNull() ?: channels.firstOrNull() }

    Column(Modifier.fillMaxSize()) {
        Hero(focused, Modifier.fillMaxWidth().height(330.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (shows.isNotEmpty()) item { CardRow("Continue watching", "Shows", RailIcon.SHOWS, shows, { focused = it }, { menu = it }) }
            if (movies.isNotEmpty()) item { CardRow("Continue watching", "Movies", RailIcon.MOVIES, movies, { focused = it }, { menu = it }) }
            if (channels.isNotEmpty()) item { CardRow("Continue watching", "Live TV", RailIcon.CHANNELS, channels, { focused = it }, { menu = it }) }
            if (shows.isEmpty() && movies.isEmpty() && channels.isEmpty()) {
                item {
                    Text("Nothing here yet. TV shows and movies you start watching show up here so you can pick up where you left off.",
                        color = FS.TextDim, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 36.dp, vertical = 20.dp))
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
    menu?.let { CardMenu(it, onClose = { menu = null }, onSeasons = { s -> menu = null; showPick = s }) }
    showPick?.let { ShowPicker(it, onClose = { showPick = null }) }
    LaunchedEffect(Unit) { openShow = { s -> showPick = s } }
}

/** The provider's own picture and details for a card (cached). */
@Composable
private fun cardArt(c: Card?): ProviderArt.Art? {
    val art by produceState<ProviderArt.Art?>(null, c?.key) {
        value = null
        if (c == null) return@produceState
        value = when {
            c.series != null -> ProviderArt.show(c.series)
            c.kind == "episode" -> ProviderArt.episode(c.title, c.recent?.key?.removePrefix("e:"), c.season, c.episode)
            else -> (c.vod?.id ?: c.recent?.key?.removePrefix("m:"))?.let { ProviderArt.movie(it, c.title) }
        }
    }
    return art
}

/** Top of Home: the highlighted title's big picture, fading into black on the left and bottom. */
@Composable
private fun Hero(c: Card?, modifier: Modifier) {
    if (c?.channel != null) { ChannelHero(c.channel, modifier); return }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            LineIcon(icon, 22.dp, FS.Green)
            Spacer(Modifier.width(10.dp))
            Text(title, color = FS.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            sub?.let { Text("  ·  $it", color = FS.TextDim, fontSize = 16.sp) }
            Spacer(Modifier.weight(1f))
            Text("Press and hold \"OK\" for more", color = FS.TextDim, fontSize = 13.sp)
        }
        // Room around the cards so the green outline (and the slight zoom) of a highlighted card
        // isn't cut off at the screen edge.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 36.dp, vertical = 8.dp),
        ) {
            itemsIndexed(cards, key = { _, c -> c.key }) { _, c ->
                WideCard(c, onFocus = { onFocus(c) }, onClick = { playCard(context, c) }, onLongClick = { onMore(c) })
            }
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
            if (c.channel != null) { ChannelCardBody(c.channel); return@Surface }
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
        c.series != null -> openShow?.invoke(c.series) ?: playShow(context, c.series)
        c.channel != null -> {
            // Watch it; ▲ ▼ in the player go through your recently watched channels.
            val hist = com.forbiddenstreams.tv.data.Prefs(context).history.mapNotNull { Session.channelById(it) }.ifEmpty { listOf(c.channel) }
            playChannels(context, hist, hist.indexOfFirst { it.id == c.channel.id }.coerceAtLeast(0))
        }
    }
}

/** OK on a show card opens its seasons (set by Home). */
private var openShow: ((SeriesItem) -> Unit)? = null

/** A show from New TV Shows: the next episode after the last one you watched, else S1 E1. */
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
private fun CardMenu(c: Card, onClose: () -> Unit, onSeasons: (SeriesItem) -> Unit) {
    val context = LocalContext.current
    val r = c.recent
    val opts = ArrayList<Pair<String, () -> Unit>>()
    val showOf = c.series ?: if (c.kind == "episode") MovieMatcher.findSeries(c.title) else null
    if (c.channel != null) {
        val ch = c.channel
        ChoiceDialog(
            title = ch.name, subtitle = null,
            options = listOf(
                "▶  Watch" to { onClose(); playCard(context, c) },
                "✖  Remove from Continue watching" to {
                    val p = com.forbiddenstreams.tv.data.Prefs(context)
                    p.history = p.history.filter { it != ch.id }
                    onClose(); toast(context, "Removed")
                },
                "Close" to onClose,
            ),
            onDismiss = onClose,
        )
        return
    }
    if (r != null) {
        opts += "▶  Resume" to { onClose(); playCard(context, c) }
        opts += "⟲  Start over" to {
            onClose()
            Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle, resumeKey = r.key, kind = r.kind, image = r.image))
        }
        opts += "✖  Remove from Continue watching" to { Recents.remove(context, r.key); onClose(); toast(context, "Removed") }
    } else if (c.series == null) {
        opts += "▶  Play" to { onClose(); playCard(context, c) }
    }
    if (showOf != null) opts += "📺  Seasons & episodes" to { onSeasons(showOf) }
    // Movies: the trailer on YouTube (the provider's trailer link, else a YouTube search).
    val movieId = c.vod?.id ?: r?.key?.takeIf { it.startsWith("m:") }?.removePrefix("m:")
    if (c.kind == "movie" && movieId != null) {
        opts += "🎬  Trailers" to { onClose(); openTrailer(context, movieId, c.title, c.year) }
    }
    opts += "Close" to onClose
    val sub = listOfNotNull(
        if (c.season != null) "S${c.season} · E${c.episode}" + (c.episodeTitle?.let { " · $it" } ?: "") else null,
        c.year,
    ).joinToString("  ·  ").ifBlank { null }
    ChoiceDialog(title = c.title, subtitle = sub, options = opts, onDismiss = onClose)
}


/** Opens the movie's trailer in YouTube: the provider's trailer if it has one, else a YouTube search. */
private fun openTrailer(context: android.content.Context, vodId: String, title: String, year: String?) {
    val src = Session.source ?: return
    kotlinx.coroutines.MainScope().launch {
        val t = try { src.vodDetails(VodItem(vodId, title, null, null, "", "mp4"))?.trailer } catch (e: Exception) { null }
        val id = t?.let { youtubeId(it) }
        if (id != null) {
            val ok = tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id"))) ||
                tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")))
            if (!ok) toast(context, "YouTube isn't installed on this box")
            return@launch
        }
        // No trailer from the provider: search YouTube for it.
        val q = "$title ${year ?: ""} official trailer".replace(Regex("\\s+"), " ").trim()
        val ok = tryStart(context, android.content.Intent(android.content.Intent.ACTION_SEARCH)
            .setPackage("com.google.android.youtube.tv").putExtra(android.app.SearchManager.QUERY, q)) ||
            tryStart(context, android.content.Intent(android.content.Intent.ACTION_SEARCH)
                .setPackage("com.google.android.youtube").putExtra(android.app.SearchManager.QUERY, q)) ||
            tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8"))))
        if (!ok) toast(context, "YouTube isn't installed on this box")
    }
}

private fun youtubeId(t: String): String? {
    val s = t.trim()
    Regex("(?:v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{11})").find(s)?.let { return it.groupValues[1] }
    return s.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
}

private fun tryStart(context: android.content.Context, i: android.content.Intent): Boolean = try {
    context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) { false }

/**
 * OK on a TV show: a pop-up with its seasons; picking a season lists its episodes;
 * picking an episode plays it (and keeps going into the next ones).
 */
@Composable
private fun ShowPicker(series: SeriesItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val src = Session.source ?: run { onClose(); return }
    val eps by produceState<List<com.forbiddenstreams.tv.data.Episode>?>(null, series.id) {
        value = try { src.episodes(series) } catch (e: Exception) { emptyList() }
    }
    var season by remember { mutableStateOf<Int?>(null) }
    val list = eps
    val name = MovieMatcher.cleanTitle(series.name)
    when {
        list == null -> ListDialog(name, "Loading seasons…", emptyList(), onClose)
        list.isEmpty() -> ListDialog(name, "No episodes found", emptyList(), onClose)
        season == null -> {
            val seasons = list.groupBy { it.season }.toSortedMap()
            ListDialog(
                name, "${seasons.size} season" + (if (seasons.size == 1) "" else "s"),
                seasons.map { (s, e) -> Triple("Season $s", "${e.size} episode" + (if (e.size == 1) "" else "s"), { season = s }) },
                onClose,
            )
        }
        else -> {
            val all = list.sortedWith(compareBy({ it.season }, { it.episodeNum }))
            val inSeason = all.filter { it.season == season }
            val items = all.map {
                PlayItem("$name · S${it.season}E${it.episodeNum}", src.episodeUrl(it), live = false, subtitle = it.title,
                    resumeKey = "e:${it.id}", kind = "episode", image = series.cover ?: it.image)
            }
            ListDialog(
                "$name · Season $season", "${inSeason.size} episodes · BACK = seasons",
                inSeason.map { e ->
                    val saved = Recents.get(context, "e:${e.id}")
                    val note = listOfNotNull(
                        saved?.let { r -> if (r.durationMs > 0) "▶ ${((r.durationMs - r.positionMs) / 60_000).coerceAtLeast(1)} min left" else "▶ Resume" },
                        e.minutes?.let { "$it min" },
                    ).joinToString("  ·  ").ifEmpty { null }
                    Triple("E${e.episodeNum} · ${e.title}", note, {
                        onClose()
                        val i = all.indexOf(e)
                        Playback.start(context, items.mapIndexed { j, it -> if (j == i && saved != null) it.copy(startMs = saved.positionMs) else it }, i)
                    })
                },
                onBack = { season = null },
            )
        }
    }
}

/** A pop-up with a scrolling list (seasons, episodes). */
@Composable
private fun ListDialog(
    title: String,
    subtitle: String?,
    items: List<Triple<String, String?, () -> Unit>>,
    onBack: () -> Unit,
) {
    val first = remember(title) { FocusRequester() }
    LaunchedEffect(title, items.size) { delay(120); try { first.requestFocus() } catch (e: Exception) { } }
    Dialog(onDismissRequest = onBack) {
        Column(
            Modifier.width(620.dp).background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = FS.TextDim, fontSize = 15.sp) }
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { i, (label, note, action) ->
                    FocusCard(
                        onClick = action,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        focusedScale = 1.02f,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            note?.let { Text(it, color = FS.Green, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp)) }
                        }
                    }
                }
            }
        }
    }
}


/** What's on a channel right now (refreshes every minute). */
@Composable
private fun nowOn(ch: com.forbiddenstreams.tv.data.LiveChannel): Pair<com.forbiddenstreams.tv.data.Program?, com.forbiddenstreams.tv.data.Program?> {
    val epg by produceState(Session.cachedEpg(ch), ch.id) { value = Session.epg(ch) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val cur = epg?.firstOrNull { it.isLive(now) }
    val next = cur?.let { c -> epg?.firstOrNull { it.start >= c.end } }
    return cur to next
}

/** Live channel card: the channel's logo, and what's on now with how far along it is. */
@Composable
private fun ChannelCardBody(ch: com.forbiddenstreams.tv.data.LiveChannel) {
    val (cur, _) = nowOn(ch)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E1A10), Color(0xFF060906))))) {
        Box(Modifier.align(Alignment.Center).padding(bottom = 30.dp).fillMaxWidth(0.62f).height(82.dp), contentAlignment = Alignment.Center) {
            if (ch.logo != null) NetImage(ch.logo, Modifier.fillMaxSize())
            else Text(ch.name, color = FS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(cur?.title ?: "Live", color = FS.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cur != null) {
                val f = ((System.currentTimeMillis() - cur.start).toFloat() / (cur.end - cur.start)).coerceIn(0.03f, 1f)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).background(Color(0x99FFFFFF), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(f).height(5.dp).background(FS.Green, RoundedCornerShape(3.dp)))
                }
            }
        }
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(FS.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** Top of Home for a live channel: big logo on the right, what's on now and next on the left. */
@Composable
private fun ChannelHero(ch: com.forbiddenstreams.tv.data.LiveChannel, modifier: Modifier) {
    val (cur, next) = nowOn(ch)
    Box(modifier) {
        if (ch.logo != null) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 90.dp, top = 40.dp).size(width = 360.dp, height = 200.dp), contentAlignment = Alignment.Center) {
                NetImage(ch.logo, Modifier.fillMaxSize())
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 10.dp).width(620.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(FS.Live, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text("${ch.num} · ${ch.name}", color = FS.TextDim, fontSize = 16.sp, maxLines = 1)
            }
            Text(cur?.title ?: ch.name, color = FS.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 42.sp)
            if (cur != null) {
                Text("⏱  ${Fmt.range(cur.start, cur.end)}" + (next?.let { "      Next: ${it.title}" } ?: ""),
                    color = FS.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                cur.description.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = FS.Text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                }
            }
        }
    }
}
