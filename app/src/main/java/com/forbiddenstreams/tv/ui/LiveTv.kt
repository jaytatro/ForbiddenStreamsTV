package com.forbiddenstreams.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV tab (Nov 1 2026):
 *  - Categories down the left; highlighting one shows its TV guide on the right.
 *  - OK on a category: the list slides away and the guide fills the screen.
 *  - ◀ in the guide: the list slides back with the cursor on that category.
 */
@Composable
fun LiveTvTab() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    remember { ButtonPrefs.load(context); true }
    val cats = Session.liveCats
    if (cats == null) {
        Box(Modifier.fillMaxSize().padding(top = 76.dp)) {
            val err = Session.liveError
            if (err != null && !Session.refreshing) MessageBox("Couldn't load channels", err, "Retry") { Session.refreshInBackground(context) }
            else LoadingBox("Loading channels")
        }
        return
    }
    val groups = remember(cats, CategoryPrefs.version) { groupsFor(CategoryPrefs.visible(context, cats)).filter { it != Group.Search && it != Group.All } }
    // Search bar above the categories (Nov 8): matching channels from every category.
    var chQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Session.liveAll != null) { if (Session.liveAll != null) Session.importPastGuide(context) }

    var listOpen by remember { mutableStateOf(true) }
    // Always starts at History (Nov 8).
    var picked by remember { mutableStateOf(groups.firstOrNull { it == Group.History } ?: groups.firstOrNull()) }
    var shown by remember { mutableStateOf(picked) }          // the guide on the right
    var pending by remember { mutableStateOf<Group?>(null) }  // highlighted, shown after a short pause
    val selReq = remember { FocusRequester() }
    val guideReq = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    var guideFocused by remember { mutableStateOf(false) }

    LaunchedEffect(chQuery) { if (chQuery.trim().length >= 2) { delay(300); shown = Group.Search } }
    LaunchedEffect(pending) {
        val g = pending ?: return@LaunchedEffect
        delay(300) // smooth while scrolling through the categories
        shown = g
    }

    // Smooth slide: the list's width and fade animate; it leaves the screen once fully closed.
    val listW by animateDpAsState(if (listOpen) 320.dp else 0.dp, tween(260), label = "list")
    val listA by animateFloatAsState(if (listOpen) 1f else 0f, tween(200), label = "listAlpha")

    var hideAsk by remember { mutableStateOf<Group.Cat?>(null) }
    // ▼ from the tabs goes back to where you were: the category list, or the channel you were on in the guide.
    val goBackIn: () -> Boolean = {
        scope.launch {
            repeat(6) {
                delay(40)
                try { if (listOpen) selReq.requestFocus() else guideReq.requestFocus(); return@launch } catch (e: Exception) { }
            }
        }
        true
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        TabDown.handler = goBackIn
        onDispose { if (TabDown.handler === goBackIn) TabDown.handler = null }
    }
    var searching by remember { mutableStateOf(false) }
    if (searching) ChannelSearchDialog(onClose = { searching = false })

    fun openList() {
        listOpen = true
        scope.launch { repeat(4) { delay(90); if (listFocused) return@launch; try { selReq.requestFocus() } catch (e: Exception) { } } }
    }

    /** OK (or ▶) on a category: its guide, the cursor into it, and the list slides away. */
    fun pick(g: Group) {
        picked = g
        pending = null
        shown = g
        if (g != Group.Search) prefs.lastGroup = g.key
        listOpen = false
        scope.launch {
            repeat(8) {
                delay(70)
                if (guideFocused) return@launch
                try { guideReq.requestFocus() } catch (e: Exception) { }
            }
        }
    }

    hideAsk?.let { g -> HideCategoryDialog(g, onClose = { hideAsk = null; openList() }) }
    // Nov 13: same control-room look as Live TV — dark backdrop, ON AIR strip, glass panels, neon-bar rows.
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val counts = remember(Session.liveAll) { Session.liveAll.orEmpty().groupingBy { it.categoryId }.eachCount() }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF07120A), Color.Black))))
    OnAirStrip(now, Modifier.padding(top = 76.dp, start = 18.dp, end = 18.dp))
    Row(Modifier.fillMaxSize().padding(top = 122.dp, start = 18.dp, end = 18.dp, bottom = 14.dp)) {
        if (listOpen || listW > 0.dp) {
            Box(
                Modifier.width(listW).fillMaxHeight().clipToBounds().alpha(listA)
                    .onFocusChanged { listFocused = it.hasFocus },
            ) {
                Column(Modifier.width(306.dp).padding(end = 14.dp).fillMaxHeight().glass()) {
                    // Nov 8: a 🔍 button; OK opens the search with the keyboard up right away.
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("CATEGORIES", color = FS.GreenMid, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                        Pill("🔍", selected = false, outlined = true, fontSize = 16.sp, hPad = 14.dp, onClick = { searching = true })
                    }
                    val selIndex = groups.indexOfFirst { it.key == picked?.key }.coerceAtLeast(0)
                    val state = rememberLazyListState((selIndex - 3).coerceAtLeast(0))
                    val topReq = remember { FocusRequester() }
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 6.dp)) {
                        itemsIndexed(groups, key = { _, g -> g.key }) { i, g ->
                            ListPill(
                                selected = g.key == picked?.key,
                                onClick = { pick(g) },
                                onLongClick = { if (g is Group.Cat) hideAsk = g },
                                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && listOpen) pending = g }
                                    .then(if (i == selIndex) Modifier.focusRequester(selReq) else Modifier)
                                    .then(if (i == 0) Modifier.focusRequester(topReq) else Modifier)
                                    // ▶ on a category does the same as OK; ▼ on the last one goes back to the top.
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when {
                                            ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                if (ev.nativeKeyEvent.repeatCount == 0) pick(g)
                                                true
                                            }
                                            ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && i == groups.size - 1 -> {
                                                scope.launch {
                                                    try { state.scrollToItem(0) } catch (e: Exception) { }
                                                    repeat(5) { delay(30); try { topReq.requestFocus(); return@launch } catch (e: Exception) { } }
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.title, color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    val n = when (g) {
                                        is Group.Cat -> counts[g.cat.id] ?: 0
                                        Group.History -> prefs.history.size
                                        Group.Favorites -> prefs.favorites.size
                                        else -> 0
                                    }
                                    if (n > 0) Text("$n", color = FS.TextDim, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        modifier = Modifier.background(Color(0x22FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier.weight(1f).fillMaxHeight().glass().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(top = 6.dp, bottom = 6.dp)
                .onFocusChanged {
                    guideFocused = it.hasFocus
                    // While the list is open, the cursor belongs in the list (▼ from the tabs lands there).
                    if (it.hasFocus && listOpen && !listFocused) openList()
                }
        ) {
            val g = shown
            if (g == null) {
                MessageBox("No channels", "Your provider didn't send any live categories.")
            } else {
                val list = remember(g.key, Session.liveAll, if (g == Group.Search) chQuery else "") {
                    if (g == Group.Search) chQuery.trim().let { q -> if (q.length < 2) emptyList() else channelsOf(prefs, g).filter { channelMatches(it.name, q) } }
                    else channelsOf(prefs, g)
                }
                if (list.isEmpty()) {
                    MessageBox("No channels in this category", "Press ◀ for the categories.")
                } else {
                    androidx.compose.runtime.key(g.key + if (g == Group.Search) chQuery else "") {
                        GuideView(
                            group = g,
                            channels = list,
                            startId = com.forbiddenstreams.tv.player.PlayerHolder.current?.channelId,
                            active = !listOpen,
                            focusRequester = guideReq,
                            onCategories = { openList() },
                            onToggleFav = { ch ->
                                val added = prefs.toggleFavorite(ch.id)
                                toast(context, if (added) "Added ${ch.name} to favorites" else "Removed ${ch.name} from favorites")
                            },
                            leftOpensCategories = true,
                            onPlayChannel = { l, i -> playChannels(context, l, i) },
                        )
                    }
                }
            }
        }
    }
}
