package com.forbiddenvid.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.forbiddenvid.tv.data.Addons
import com.forbiddenvid.tv.data.Meta
import com.forbiddenvid.tv.data.Stream
import com.forbiddenvid.tv.data.Video
import com.forbiddenvid.tv.data.Watch
import com.forbiddenvid.tv.player.PlayerActivity
import kotlinx.coroutines.delay

fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** What the user chose to play: the title and the movie / episode. */
data class PlayTarget(val meta: Meta, val videoId: String, val label: String, val next: List<Video>)

/** Title page: picture, info, Play (movies) or seasons + episodes (series). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DetailsScreen(preview: Meta, onBack: () -> Unit) {
    var meta by remember(preview.id) { mutableStateOf(preview) }
    var loading by remember(preview.id) { mutableStateOf(true) }
    var target by remember { mutableStateOf<PlayTarget?>(null) }
    val playReq = remember { FocusRequester() }

    LaunchedEffect(preview.id) {
        meta = try { Addons.meta(preview) } catch (e: Exception) { preview }
        loading = false
    }
    BackHandler { onBack() }

    // Keep the cursor inside this page while it's open.
    Box(
        Modifier.fillMaxSize().background(FS.Bg)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) {
        NetImage(meta.background ?: meta.poster, Modifier.fillMaxSize(), crop = true)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to FS.Bg, 0.45f to Color(0xEE040604), 1f to Color(0x55040604))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to FS.Bg)))

        val series = meta.videos.size > 1 || (meta.type == "series" && meta.videos.isNotEmpty())
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = 40.dp)) {
            // Left: info.
            Column(Modifier.width(if (series) 480.dp else 760.dp).fillMaxHeight()) {
                if (!meta.logo.isNullOrBlank()) {
                    NetImage(meta.logo, Modifier.height(110.dp).width(420.dp))
                } else {
                    Text(meta.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                }
                Gap(h = 10.dp)
                InfoRow(meta.releaseInfo, meta.runtime, meta.rating?.let { "★ $it" }, typeName(meta.type))
                if (meta.genres.isNotEmpty()) {
                    Gap(h = 8.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { meta.genres.take(4).forEach { SmallTag(it) } }
                }
                Gap(h = 14.dp)
                if (!meta.description.isNullOrBlank()) {
                    Text(meta.description ?: "", color = FS.Text, fontSize = 17.sp, maxLines = if (series) 8 else 6, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp)
                }
                if (meta.cast.isNotEmpty()) {
                    Gap(h = 10.dp)
                    Text("Starring " + meta.cast.take(4).joinToString(", "), color = FS.TextDim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Gap(h = 22.dp)
                if (loading) {
                    Text("Loading details…", color = FS.Green, fontSize = 16.sp)
                } else if (!series) {
                    val videoId = if (meta.videos.size == 1) meta.videos[0].id else meta.id
                    val w = Watch.get(videoId)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill(if (w != null && w.resumable) "▶  Resume ${clock(w.position)}" else "▶  Play", selected = true,
                            onClick = { target = PlayTarget(meta, videoId, "", emptyList()) },
                            modifier = Modifier.focusRequester(playReq), fontSize = 20.sp, hPad = 26.dp)
                    }
                    LaunchedEffect(Unit) { delay(150); try { playReq.requestFocus() } catch (e: Exception) { } }
                }
            }
            if (series && !loading) {
                Gap(w = 36.dp)
                Episodes(meta, onPick = { v, rest -> target = PlayTarget(meta, v.id, v.label, rest) })
            }
        }
    }

    target?.let { t ->
        StreamsDialog(t, onDismiss = { target = null })
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Episodes(meta: Meta, onPick: (Video, List<Video>) -> Unit) {
    val seasons = remember(meta) {
        meta.videos.map { it.season ?: 0 }.distinct().sortedWith(compareBy({ it == 0 }, { it }))
    }
    // Start on the season of the last episode watched, else the first real season.
    val lastWatched = remember(meta) { Watch.entries.firstOrNull { it.metaId == meta.id } }
    val startSeason = remember(meta) {
        lastWatched?.let { w -> meta.videos.firstOrNull { it.id == w.videoId }?.season } ?: seasons.firstOrNull() ?: 0
    }
    var season by remember(meta) { mutableStateOf(startSeason) }
    val eps = meta.videos.filter { (it.season ?: 0) == season }
    val firstEp = remember { FocusRequester() }
    // The episode to land on: the one in progress, or the one after the last one finished.
    val startIdx = remember(meta, season) {
        val lw = lastWatched
        val i = if (lw == null) -1 else eps.indexOfFirst { it.id == lw.videoId }
        when {
            i < 0 -> 0
            lw != null && !lw.resumable && lw.position > 0 && i + 1 < eps.size -> i + 1
            else -> i
        }
    }
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                items(seasons) { s ->
                    Pill(if (s == 0) "Specials" else "Season $s", selected = s == season, onClick = { season = s })
                }
            }
            Gap(h = 10.dp)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).focusRestorer(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp, end = 8.dp),
        ) {
            items(eps, key = { it.id }) { v ->
                val w = Watch.get(v.id)
                FocusCard(
                    onClick = { onPick(v, meta.videos.dropWhile { it.id != v.id }.drop(1)) },
                    modifier = Modifier.fillMaxWidth().then(if (v == eps.getOrNull(startIdx)) Modifier.focusRequester(firstEp) else Modifier),
                    container = Color(0xCC0C140D),
                    focusedScale = 1.02f,
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(176.dp, 99.dp).background(FS.Panel2, RoundedCornerShape(8.dp))) {
                            NetImage(v.thumbnail, Modifier.fillMaxSize(), crop = true)
                            if (w != null) ProgressLine(w.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp))
                        }
                        Gap(w = 14.dp)
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(v.episode?.let { "E$it" }, v.title).joinToString("  ·  "), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (v.released != null) Text(v.released, color = FS.TextDim, fontSize = 13.sp)
                            if (!v.overview.isNullOrBlank()) Text(v.overview, color = FS.TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // Open on the episode after the last one watched (or the one in progress).
        if (startIdx > 0) try { listState.scrollToItem(startIdx) } catch (e: Exception) { }
        delay(200)
        try { firstEp.requestFocus() } catch (e: Exception) { }
    }
}

/**
 * Asks every add-on for links to the chosen movie / episode and lists them as they come in.
 * Links that can't play in the app (torrents, YouTube…) are shown greyed at the bottom.
 */
@Composable
fun StreamsDialog(t: PlayTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val streams = remember(t) { mutableStateListOf<Stream>() }
    val failed = remember(t) { mutableStateListOf<String>() }
    var asked by remember(t) { mutableStateOf(0) }
    var answered by remember(t) { mutableStateOf(0) }
    var resumeFor by remember(t) { mutableStateOf<Stream?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(t) {
        asked = Addons.enabled.count { it.handles("stream", t.meta.type, t.videoId) }
        Addons.streams(t.meta.type, t.videoId) { a, list, err ->
            answered++
            streams.addAll(list)
            if (err != null) failed += "${a.name}: $err"
        }
    }

    fun play(s: Stream, fromStart: Boolean) {
        val w = Watch.get(t.videoId)
        PlayerActivity.start(context, t, s, startMs = if (!fromStart && w != null && w.resumable) w.position else 0L)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(760.dp).heightIn(max = 600.dp)
                .background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(listOf(t.meta.name, t.label).filter { it.isNotBlank() }.joinToString("  ·  "), color = FS.Green, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val playable = streams.filter { it.url != null }
            val other = streams.filter { it.url == null }
            Text(
                when {
                    asked == 0 -> "None of your add-ons offer links for this. Install one that does in Add-ons."
                    answered < asked -> "Asking $asked add-on${if (asked == 1) "" else "s"}… ($answered answered, ${playable.size} links)"
                    playable.isEmpty() -> "No playable links found." + (if (failed.isNotEmpty()) "\n" + failed.joinToString("\n") else "")
                    else -> "${playable.size} link${if (playable.size == 1) "" else "s"} found"
                },
                color = FS.TextDim, fontSize = 15.sp, maxLines = 6,
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playable.size) { i ->
                    val s = playable[i]
                    FocusCard(
                        onClick = {
                            val w = Watch.get(t.videoId)
                            if (w != null && w.resumable) resumeFor = s else play(s, fromStart = true)
                        },
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(s.name.replace('\n', ' '), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.title.isNotBlank()) Text(s.title, fontSize = 14.sp, color = FS.TextDim, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                items(other.size) { i ->
                    val s = other[i]
                    FocusCard(onClick = { toast(context, s.unsupported ?: "Can't play this link") }, modifier = Modifier.fillMaxWidth(), container = Color(0xFF080C08)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(s.name.replace('\n', ' '), fontSize = 15.sp, color = FS.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.unsupported ?: "", fontSize = 13.sp, color = FS.TextDim, maxLines = 1)
                        }
                    }
                }
            }
            Pill("Close", selected = false, onClick = onDismiss)
        }
    }
    // Cursor onto the first link once it's there.
    val firstReady = streams.any { it.url != null }
    LaunchedEffect(firstReady) {
        if (firstReady) { delay(120); try { first.requestFocus() } catch (e: Exception) { } }
    }

    resumeFor?.let { s ->
        val w = Watch.get(t.videoId)
        ChoiceDialog(
            title = "Carry on where you stopped?",
            options = listOf(
                "▶  Resume from ${clock(w?.position ?: 0)}" to { resumeFor = null; play(s, fromStart = false) },
                "⟲  Start from the beginning" to { resumeFor = null; play(s, fromStart = true) },
            ),
            onDismiss = { resumeFor = null },
        )
    }
}
