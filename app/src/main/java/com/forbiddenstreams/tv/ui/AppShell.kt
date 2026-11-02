package com.forbiddenstreams.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.MovieMatcher
import com.forbiddenstreams.tv.data.RecentItem
import com.forbiddenstreams.tv.data.Recents
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.data.Tmdb
import com.forbiddenstreams.tv.data.TmdbMovie
import com.forbiddenstreams.tv.data.VodItem
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tabs across the top, left to right. */
private val TABS = listOf("Home", "Live TV", "Sports", "Movies", "TV Shows")

/**
 * The new app (started from scratch, Nov 1 2026): tabs across the top, always opens on Home.
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
        Column(Modifier.fillMaxSize()) {
            TopBar(
                tab = tab, reqs = tabReq,
                onTab = { tab = it },
                onFocusChange = { tabsFocused = it },
                onSettings = { settings = true },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> HomeTab(tmdbVersion, onAddKey = { keyDialog = true })
                    1 -> ComingNext("Live TV")
                    2 -> ComingNext("Sports")
                    3 -> ComingNext("Movies")
                    else -> ComingNext("TV Shows")
                }
            }
        }
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
                "⬆  Check for updates" to { settings = false; toast(context, "Checking for updates…") ; UpdateState.check(context, kotlinx.coroutines.MainScope(), manual = true) },
                "⎋  Log out" to { settings = false; onLogout() },
                "Close" to { settings = false },
            ),
            onDismiss = { settings = false },
        )
    }
    if (keyDialog) {
        TmdbKeyDialog(onDone = { keyDialog = false; tmdbVersion++ })
    }
    UpdatePrompt()
}

@Composable
private fun TopBar(tab: Int, reqs: List<FocusRequester>, onTab: (Int) -> Unit, onFocusChange: (Boolean) -> Unit, onSettings: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 36.dp).onFocusChanged { onFocusChange(it.hasFocus) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(54.dp))
        Spacer(Modifier.width(28.dp))
        TABS.forEachIndexed { i, name ->
            val shape = RoundedCornerShape(50)
            Surface(
                onClick = { onTab(i) },
                modifier = Modifier.padding(end = 8.dp).focusRequester(reqs[i]).onFocusChanged { if (it.isFocused) onTab(i) },
                shape = ClickableSurfaceDefaults.shape(shape = shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (i == tab) FS.Green else FS.Text,
                    focusedContainerColor = FS.Green,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, fontSize = 20.sp, fontWeight = if (i == tab) FontWeight.Bold else FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(Fmt.time(now), color = FS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Surface(
            onClick = onSettings,
            modifier = Modifier.size(44.dp),
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent, focusedContainerColor = FS.Green,
                contentColor = FS.Green, focusedContentColor = Color.Black,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LineIcon(RailIcon.SETTINGS, 24.dp, androidx.tv.material3.LocalContentColor.current)
            }
        }
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

private data class Row4(val title: String, val items: List<HomeItem>)

/** One poster on Home: something to resume, a provider movie, or a TMDB movie (matched to the provider when possible). */
private data class HomeItem(
    val key: String,
    val title: String,
    val poster: String?,
    val badge: String? = null,
    val progress: Float? = null,
    val recent: RecentItem? = null,
    val vod: VodItem? = null,
    val tmdb: TmdbMovie? = null,
)

/**
 * Home: Continue Watching TV Shows · Continue Watching Movies · In Theaters · New in HD ·
 * one row per genre (Action, Adventure, Animation, Comedy, Crime, …) from TMDB, showing the
 * movies your provider has.
 */
@Composable
private fun HomeTab(tmdbVersion: Int, onAddKey: () -> Unit) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<HomeItem?>(null) }
    var libraryReady by remember { mutableStateOf(MovieMatcher.ready) }
    LaunchedEffect(Unit) { libraryReady = MovieMatcher.load() }
    val hasKey = remember(tmdbVersion) { Tmdb.key(context) != null }

    // Continue watching (refreshed when coming back from the player).
    val recents = remember(PlayerHolder.fullScreen) { Recents.all(context) }
    val shows = remember(recents) {
        recents.filter { it.kind == "episode" }
            .distinctBy { it.title.substringBefore(" · ") }
            .map { r ->
                val m = Regex("S(\\d+)E(\\d+)").find(r.title)
                HomeItem(
                    key = r.key, title = r.title.substringBefore(" · "), poster = r.image,
                    badge = m?.let { "S${it.groupValues[1]} · E${it.groupValues[2]}" },
                    progress = if (r.durationMs > 0) r.positionMs.toFloat() / r.durationMs else null, recent = r,
                )
            }
    }
    val movies = remember(recents) {
        recents.filter { it.kind == "movie" }.map { r ->
            HomeItem(r.key, r.title, r.image, progress = if (r.durationMs > 0) r.positionMs.toFloat() / r.durationMs else null, recent = r)
        }
    }

    // TMDB rows: In Theaters (all), genres (only what your provider has, once its list is loaded).
    val rows by produceState<List<Row4>?>(null, tmdbVersion, libraryReady) {
        if (!hasKey) { value = emptyList(); return@produceState }
        val out = ArrayList<Row4>()
        fun item(m: TmdbMovie) = MovieMatcher.find(m).let { v ->
            HomeItem("t:${m.id}", m.title, m.poster, badge = if (v == null && libraryReady) "Not on your provider" else null, vod = v, tmdb = m)
        }
        try {
            val theaters = Tmdb.movies(context, "movie/now_playing", "region=US")
            out += Row4("In Theaters", theaters.map(::item))
            value = out.toList()
        } catch (e: Exception) {
            value = listOf(Row4("TMDB: ${e.message}", emptyList()))
            return@produceState
        }
        for ((name, id) in Tmdb.GENRES) {
            val list = try {
                Tmdb.movies(context, "discover/movie", "with_genres=$id&sort_by=popularity.desc&vote_count.gte=100", pages = 3)
            } catch (e: Exception) { emptyList() }
            val shown = if (libraryReady) list.map(::item).filter { it.vod != null } else list.map(::item)
            if (shown.isNotEmpty()) { out += Row4(name, shown.take(30)); value = out.toList() }
        }
    }
    val newHd = remember(libraryReady) {
        MovieMatcher.newest(40).map { HomeItem("v:${it.id}", it.name, it.poster, vod = it) }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(start = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (shows.isNotEmpty()) item { PosterRow("TV Shows · Continue Watching", shows) { detail = it } }
        if (movies.isNotEmpty()) item { PosterRow("Movies · Continue Watching", movies) { detail = it } }
        if (!hasKey) {
            item {
                FocusCard(onClick = onAddKey, modifier = Modifier.padding(end = 36.dp)) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp)) {
                        Text("🎬  Add your free TMDB key", color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("In Theaters and the genre rows come from The Movie Database. OK to add your key.",
                            color = FS.TextDim, fontSize = 15.sp)
                    }
                }
            }
        }
        val r = rows
        if (hasKey && r == null) item { Text("Loading movies…", color = FS.TextDim, fontSize = 16.sp) }
        r?.firstOrNull()?.let { first -> item(key = "row_" + first.title) { PosterRow(first.title, first.items) { detail = it } } }
        if (newHd.isNotEmpty()) item { PosterRow("New in HD", newHd) { detail = it } }
        r?.drop(1)?.forEach { row -> item(key = "row_" + row.title) { PosterRow(row.title, row.items) { detail = it } } }
        if (!libraryReady) item { Text("Loading your provider's movies…", color = FS.TextDim, fontSize = 14.sp) }
        item { Spacer(Modifier.height(30.dp)) }
    }

    detail?.let { d -> HomeItemDialog(d, onClose = { detail = null }) }
}

@Composable
private fun PosterRow(title: String, items: List<HomeItem>, onClick: (HomeItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) return@Column
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { _, it -> PosterCard(it) { onClick(it) } }
            item { Spacer(Modifier.width(24.dp)) }
        }
    }
}

/** Poster with the green "how far you are" bar inside it, and an S/E or note under it. */
@Composable
private fun PosterCard(it: HomeItem, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.width(150.dp), focusedScale = 1.06f, container = FS.Panel) {
        Column {
            Box(Modifier.fillMaxWidth().height(225.dp).background(FS.Panel2)) {
                if (it.poster != null) NetImage(it.poster, Modifier.fillMaxSize(), crop = true)
                else Text(it.title, color = FS.Text, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center).padding(10.dp))
                it.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Color(0xCC000000))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(6.dp).background(FS.Green))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(it.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                it.badge?.let { b ->
                    Text(b, color = if (b.startsWith("S")) FS.Green else FS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** OK on a poster: resume / play, or say it isn't on the provider. */
@Composable
private fun HomeItemDialog(d: HomeItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val src = Session.source
    val r = d.recent
    val vod = d.vod
    val opts = ArrayList<Pair<String, () -> Unit>>()
    if (r != null) {
        opts += "▶  Resume" to {
            onClose()
            Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle, resumeKey = r.key, kind = r.kind, image = r.image, startMs = r.positionMs))
        }
        opts += "⟲  Start over" to {
            onClose()
            Playback.one(context, PlayItem(r.title, r.url, live = false, subtitle = r.subtitle, resumeKey = r.key, kind = r.kind, image = r.image))
        }
        opts += "✖  Remove from Continue Watching" to { Recents.remove(context, r.key); onClose(); toast(context, "Removed") }
    } else if (vod != null && src != null) {
        val saved = Recents.get(context, "m:${vod.id}")
        if (saved != null) opts += "▶  Resume" to {
            onClose()
            Playback.one(context, PlayItem(vod.name, src.vodUrl(vod), live = false, resumeKey = "m:${vod.id}", kind = "movie", image = vod.poster ?: d.poster, startMs = saved.positionMs))
        }
        opts += (if (saved != null) "⟲  Start over" else "▶  Play") to {
            onClose()
            Playback.one(context, PlayItem(d.tmdb?.title ?: vod.name, src.vodUrl(vod), live = false, resumeKey = "m:${vod.id}", kind = "movie", image = d.poster ?: vod.poster))
        }
    }
    opts += "Close" to onClose
    val tm = d.tmdb
    val sub = listOfNotNull(
        tm?.year, tm?.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
        if (r == null && vod == null) "Not on your provider" else null,
    ).joinToString("  ·  ") + (tm?.overview?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
    ChoiceDialog(title = d.tmdb?.title ?: d.title, subtitle = sub.ifBlank { null }, options = opts, onDismiss = onClose)
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
