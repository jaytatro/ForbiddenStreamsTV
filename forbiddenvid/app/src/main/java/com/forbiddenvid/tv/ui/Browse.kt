package com.forbiddenvid.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenvid.tv.data.Addon
import com.forbiddenvid.tv.data.Addons
import com.forbiddenvid.tv.data.Catalog
import com.forbiddenvid.tv.data.Meta
import com.forbiddenvid.tv.data.Watch
import com.forbiddenvid.tv.data.WatchEntry
import kotlinx.coroutines.launch

fun typeName(t: String): String = when (t) {
    "movie" -> "Movies"
    "series" -> "TV Shows"
    "tv" -> "Live TV"
    "channel" -> "Channels"
    "anime" -> "Anime"
    else -> t.replaceFirstChar { it.uppercase() }
}

/**
 * Home / Movies / TV Shows: a big preview of the highlighted title on top, then one row per add-on catalog.
 * [type] null = every type (Home, with Continue watching first).
 */
@Composable
fun BrowseScreen(type: String?, title: String, onAddons: () -> Unit) {
    val rows = Addons.rows(type)
    var hero by remember(type) { mutableStateOf<Meta?>(null) }
    val cont = if (type == null) Watch.continueRow() else Watch.continueRow().filter { it.type == type }

    if (Addons.list.isEmpty()) {
        MessageBox("No add-ons yet", "Forbidden Vid comes empty. Install an add-on to fill it with rows.", "Add an add-on", onAddons)
        return
    }
    if (rows.isEmpty() && cont.isEmpty()) {
        MessageBox(
            "Nothing here yet",
            if (type == null) "Your add-ons don't offer any rows. Check they're turned on in Add-ons." else "None of your add-ons offer ${title.lowercase()}.",
            "Open Add-ons", onAddons,
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Hero(hero, title)
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (cont.isNotEmpty()) {
                item(key = "continue") { ContinueRow(cont, onFocus = { hero = it.toMeta() }) }
            }
            rows.forEach { (a, c) ->
                item(key = a.manifestUrl + "|" + c.type + "|" + c.id) {
                    CatalogRow(a, c, label = if (type == null) "${c.name} · ${typeName(c.type)}" else c.name, onFocus = { hero = it })
                }
            }
        }
    }
}

/** The highlighted title, big: picture on the right fading into black, name + info + description on the left. */
@Composable
fun Hero(m: Meta?, fallbackTitle: String) {
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        if (m != null) {
            NetImage(m.background ?: m.poster, Modifier.fillMaxSize().padding(start = 220.dp), crop = true)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.2f to FS.Bg, 0.65f to Color(0x99040604), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to FS.Bg)))
            Column(Modifier.padding(start = 36.dp, top = 26.dp, end = 400.dp)) {
                Text(m.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = listOfNotNull(m.releaseInfo, m.runtime, m.rating?.let { "★ $it" }, m.genres.take(3).joinToString(" · ").ifBlank { null })
                if (info.isNotEmpty()) Text(info.joinToString("   "), color = FS.Green, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                if (!m.description.isNullOrBlank()) Text(m.description, color = FS.Text, fontSize = 16.sp, maxLines = 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp), lineHeight = 22.sp)
            }
        } else {
            ScreenTitle(fallbackTitle, Modifier.padding(start = 36.dp, top = 30.dp))
        }
    }
}

@Composable
fun RowLabel(text: String) {
    Text(text, color = FS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.padding(start = 36.dp, bottom = 8.dp))
}

/** One add-on catalog as a row of posters. Loads more as you get near the end. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CatalogRow(a: Addon, c: Catalog, label: String, search: String? = null, onFocus: (Meta) -> Unit) {
    val items = remember(a.manifestUrl, c.id, c.type, search) { mutableStateListOf<Meta>() }
    var state by remember(a.manifestUrl, c.id, c.type, search) { mutableStateOf("loading") } // loading / ok / empty / error
    var more by remember(a.manifestUrl, c.id, c.type, search) { mutableStateOf(false) }
    var end by remember(a.manifestUrl, c.id, c.type, search) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(a.manifestUrl, c.id, c.type, search) {
        state = try {
            val r = Addons.catalog(a, c, search = search)
            items.clear(); items.addAll(r.distinctBy { it.id })
            if (items.isEmpty()) "empty" else "ok"
        } catch (e: Exception) { "error" }
    }

    fun loadMore() {
        if (more || end || search != null) return
        more = true
        scope.launch {
            try {
                val have = items.map { it.id }.toHashSet()
                val r = Addons.catalog(a, c, skip = items.size).filter { have.add(it.id) }
                if (r.isEmpty()) end = true else items.addAll(r)
            } catch (e: Exception) { end = true }
            more = false
        }
    }

    when (state) {
        "empty" -> return
        "error" -> if (search != null) return
    }
    Column {
        RowLabel(label + "  ·  " + a.name)
        when (state) {
            "loading" -> LoadingBox("Loading", Modifier.fillMaxWidth().height(200.dp))
            "error" -> Text("Couldn't load this row.", color = FS.TextDim, fontSize = 15.sp, modifier = Modifier.padding(start = 36.dp, bottom = 10.dp))
            else -> LazyRow(
                Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(items, key = { _, m -> m.id }) { i, m ->
                    PosterCard(m, onFocus = {
                        onFocus(m)
                        if (i >= items.size - 6) loadMore()
                    })
                }
            }
        }
    }
}

fun posterSize(shape: String?): Pair<Dp, Dp> = when (shape) {
    "landscape" -> 240.dp to 135.dp
    "square" -> 160.dp to 160.dp
    else -> 132.dp to 198.dp
}

@Composable
fun PosterCard(m: Meta, onFocus: () -> Unit) {
    val req = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val (w, h) = posterSize(m.posterShape)
    Column(Modifier.width(w)) {
        FocusCard(
            onClick = { Nav.open(m, req) },
            modifier = Modifier.size(w, h).focusRequester(req).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            focusedScale = 1.07f,
        ) {
            Box(Modifier.fillMaxSize().background(FS.Panel2), contentAlignment = Alignment.Center) {
                Text(m.name, color = FS.TextDim, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 4, modifier = Modifier.padding(8.dp))
                NetImage(m.poster, Modifier.fillMaxSize(), crop = true)
            }
        }
        Text(m.name, color = if (focused) Color.White else FS.TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Continue watching: wide cards with the picture, episode and a green progress line. Hold OK to remove. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContinueRow(list: List<WatchEntry>, onFocus: (WatchEntry) -> Unit) {
    var removing by remember { mutableStateOf<WatchEntry?>(null) }
    Column {
        RowLabel("Continue watching")
        LazyRow(
            Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(list, key = { _, e -> e.metaId }) { _, e ->
                val req = remember { FocusRequester() }
                Column(Modifier.width(240.dp)) {
                    FocusCard(
                        onClick = { Nav.open(e.toMeta(), req) },
                        onLongClick = { removing = e },
                        modifier = Modifier.size(240.dp, 135.dp).focusRequester(req).onFocusChanged { if (it.isFocused) onFocus(e) },
                        focusedScale = 1.06f,
                    ) {
                        Box(Modifier.fillMaxSize().background(FS.Panel2)) {
                            NetImage(e.background ?: e.poster, Modifier.fillMaxSize(), crop = true)
                            ProgressLine(e.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp))
                        }
                    }
                    Text(listOf(e.episodeLabel, e.name).filter { it.isNotBlank() }.joinToString(" · "), color = FS.TextDim, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    removing?.let { e ->
        ChoiceDialog(
            title = e.name,
            options = listOf(
                "Remove from Continue watching" to { Watch.removeTitle(e.metaId); removing = null },
                "Cancel" to { removing = null },
            ),
            onDismiss = { removing = null },
        )
    }
}

@Composable
fun SmallTag(text: String) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(FS.GreenDim).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, color = FS.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))

@Composable
fun InfoRow(vararg parts: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.filterNotNull().filter { it.isNotBlank() }.forEach { Text(it, color = FS.Green, fontSize = 16.sp, maxLines = 1) }
    }
}
