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
    val groups = remember(cats, CategoryPrefs.version) { groupsFor(CategoryPrefs.visible(context, cats)).filter { it != Group.Search } }
    val scope = rememberCoroutineScope()

    var listOpen by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf(groups.firstOrNull { it.key == prefs.lastGroup } ?: groups.firstOrNull()) }
    var shown by remember { mutableStateOf(picked) }          // the guide on the right
    var pending by remember { mutableStateOf<Group?>(null) }  // highlighted, shown after a short pause
    val selReq = remember { FocusRequester() }
    val guideReq = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    var guideFocused by remember { mutableStateOf(false) }

    LaunchedEffect(pending) {
        val g = pending ?: return@LaunchedEffect
        delay(300) // smooth while scrolling through the categories
        shown = g
    }

    // Smooth slide: the list's width and fade animate; it leaves the screen once fully closed.
    val listW by animateDpAsState(if (listOpen) 320.dp else 0.dp, tween(260), label = "list")
    val listA by animateFloatAsState(if (listOpen) 1f else 0f, tween(200), label = "listAlpha")

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

    Row(Modifier.fillMaxSize().padding(top = 76.dp)) {
        if (listOpen || listW > 0.dp) {
            Box(
                Modifier.width(listW).fillMaxHeight().clipToBounds().alpha(listA)
                    .background(Color.Black)
                    .onFocusChanged { listFocused = it.hasFocus },
            ) {
                Column(Modifier.width(320.dp).fillMaxHeight().padding(start = 16.dp, end = 12.dp, top = 6.dp)) {
                    Text("CATEGORIES", color = FS.GreenMid, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
                    val selIndex = groups.indexOfFirst { it.key == picked?.key }.coerceAtLeast(0)
                    val state = rememberLazyListState(selIndex)
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(groups, key = { _, g -> g.key }) { i, g ->
                            SideItem(
                                g.title, selected = g.key == picked?.key,
                                textColor = FS.Green,
                                onFocus = { if (listOpen) pending = g },
                                onClick = { pick(g) },
                                modifier = Modifier.fillMaxWidth().then(if (i == selIndex) Modifier.focusRequester(selReq) else Modifier)
                                    // ▶ on a category does the same as OK.
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                                            if (ev.nativeKeyEvent.repeatCount == 0) pick(g)
                                            true
                                        } else false
                                    },
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier.weight(1f).fillMaxHeight()
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
                val list = remember(g.key, Session.liveAll) { channelsOf(prefs, g) }
                if (list.isEmpty()) {
                    MessageBox("No channels in this category", "Press ◀ for the categories.")
                } else {
                    androidx.compose.runtime.key(g.key) {
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
