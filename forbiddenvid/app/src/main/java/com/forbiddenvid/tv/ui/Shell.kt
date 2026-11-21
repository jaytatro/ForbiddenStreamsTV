package com.forbiddenvid.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenvid.tv.R
import com.forbiddenvid.tv.data.Addons
import com.forbiddenvid.tv.data.Meta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val RAIL_W = 72.dp
val RAIL_OPEN_W = 230.dp

enum class Tab(val title: String, val icon: Icon) {
    HOME("Home", Icon.HOME),
    MOVIES("Movies", Icon.MOVIES),
    SERIES("TV Shows", Icon.SHOWS),
    SEARCH("Search", Icon.SEARCH),
    ADDONS("Add-ons", Icon.ADDONS),
}

/** Screens opened on top of the tabs (title details). BACK closes the top one. */
object Nav {
    class Entry(val meta: Meta, val returnTo: FocusRequester?)
    val stack = mutableStateListOf<Entry>()
    fun open(m: Meta, returnTo: FocusRequester? = null) { stack += Entry(m, returnTo) }
    fun back() {
        if (stack.isEmpty()) return
        val e = stack.removeAt(stack.size - 1)
        // Put the cursor back on the card that opened it.
        val r = e.returnTo ?: return
        kotlinx.coroutines.MainScope().launch {
            repeat(6) { delay(60); try { r.requestFocus(); return@launch } catch (ex: Exception) { } }
        }
    }
}

@Composable
fun AppShell() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var railFocused by remember { mutableStateOf(false) }
    val reqs = remember { Tab.entries.map { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try { Addons.refreshAll() } catch (e: Exception) { }
    }

    fun goTab(t: Tab) {
        tab = t
        scope.launch {
            delay(250)
            try { focusManager.moveFocus(FocusDirection.Right) } catch (e: Exception) { }
        }
    }

    Box(Modifier.fillMaxSize().background(FS.background)) {
        val detail = Nav.stack.lastOrNull()
        // The tabs stay underneath (so they keep their place) but can't be reached while a title is open.
        Row(Modifier.fillMaxSize()) {
            SideRail(tab, reqs, onPick = { goTab(it) }, onFocus = { railFocused = it })
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    Tab.HOME -> BrowseScreen(null, "Home", onAddons = { goTab(Tab.ADDONS) })
                    Tab.MOVIES -> BrowseScreen("movie", "Movies", onAddons = { goTab(Tab.ADDONS) })
                    Tab.SERIES -> BrowseScreen("series", "TV Shows", onAddons = { goTab(Tab.ADDONS) })
                    Tab.SEARCH -> SearchScreen(onAddons = { goTab(Tab.ADDONS) })
                    Tab.ADDONS -> AddonsScreen()
                }
            }
        }
        if (detail != null) {
            androidx.compose.runtime.key(detail.meta.type + ":" + detail.meta.id + ":" + Nav.stack.size) {
                DetailsScreen(detail.meta, onBack = { Nav.back() })
            }
        }
        UpdatePrompt()
    }

    // BACK in a screen goes to the side menu; BACK on the side menu leaves the app.
    BackHandler(enabled = Nav.stack.isEmpty() && !railFocused) {
        try { reqs[tab.ordinal].requestFocus() } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) { delay(300); try { reqs[0].requestFocus() } catch (e: Exception) { } }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideRail(tab: Tab, reqs: List<FocusRequester>, onPick: (Tab) -> Unit, onFocus: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val w by animateDpAsState(if (open) RAIL_OPEN_W else RAIL_W, tween(180), label = "rail")
    Box(Modifier.width(w).fillMaxHeight().clipToBounds().background(if (open) Color(0xF5020402) else Color(0xFF030503))) {
        Column(Modifier.fillMaxHeight().padding(vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(48.dp))
                if (open) {
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Forbidden", color = FS.Green, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Text("Vid", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().weight(3f)
                    .focusProperties { enter = { reqs[tab.ordinal] } }
                    .focusGroup()
                    .onFocusChanged { open = it.hasFocus; onFocus(it.hasFocus) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tab.entries.forEach { t ->
                    RailButton(t.title, t.icon, selected = t == tab, open = open,
                        modifier = Modifier.focusRequester(reqs[t.ordinal]), onClick = { onPick(t) })
                }
            }
        }
    }
}

@Composable
private fun RailButton(name: String, icon: Icon, selected: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(28.dp).background(if (selected) FS.Green else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Surface(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused }
                .then(if (open) Modifier.fillMaxWidth().padding(end = 12.dp) else Modifier.size(46.dp)).height(46.dp),
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent, contentColor = if (selected) FS.Green else FS.Text,
                focusedContainerColor = FS.Green, focusedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        ) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(icon, 24.dp, if (focused) Color.Black else FS.Green)
                if (open) {
                    Spacer(Modifier.width(14.dp))
                    Text(name, fontSize = 18.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** Shared header for the tab screens. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FS.Green, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = modifier)
}
