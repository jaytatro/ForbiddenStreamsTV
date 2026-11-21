package com.forbiddenstreams.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import com.forbiddenstreams.tv.R

/** Width of the side menu when it's closed (icons only); screens start right of it. */
val SIDE_RAIL_W = 72.dp
/** Width when the cursor is in the menu (the screen moves over by this much). */
val SIDE_RAIL_OPEN_W = 230.dp

private data class RailEntry(val name: String, val icon: RailIcon)

private val RAIL = listOf(
    RailEntry("Home", RailIcon.HOME),
    RailEntry("Live TV", RailIcon.CHANNELS),
    RailEntry("Sports", RailIcon.SPORTS),
    RailEntry("Movies", RailIcon.MOVIES),
    RailEntry("TV Series", RailIcon.SHOWS),
    RailEntry("DVR", RailIcon.DVR),
)

/**
 * Nov 16 (user's iMPlayer photos): the menu on the left instead of the top dock. Closed it shows the logo
 * and green icons (a green bar marks the tab you're on); when the cursor is on it, it opens wider with the
 * names, the highlighted one in a green pill. Highlighting a tab shows it; ▶ or OK goes into the screen;
 * ◀ from a screen's left edge (or BACK) comes back here. ⚙ Settings at the bottom.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SideRail(tab: Int, reqs: List<FocusRequester>, onTab: (Int) -> Unit, onEnter: (Int) -> Unit, onFocusChange: (Boolean) -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val w by animateDpAsState(if (open) SIDE_RAIL_OPEN_W else SIDE_RAIL_W, tween(180), label = "rail")
    Box(
        Modifier.width(w).fillMaxHeight().clipToBounds()
            .background(if (open) Color(0xF5020402) else Color(0xFF030503))
            .border(width = 1.dp, color = Color(0x227CFF3A), shape = RoundedCornerShape(topEnd = 0.dp)),
    ) {
        Column(Modifier.fillMaxHeight().padding(vertical = 18.dp)) {
            // Logo (and the name when open).
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(48.dp))
                if (open) {
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Forbidden", color = FS.Green, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Text("Streams TV", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().weight(3f)
                    // Coming into the menu always lands on the tab you're on.
                    .focusProperties { enter = { reqs[tab] } }
                    .focusGroup()
                    .onFocusChanged { open = it.hasFocus; onFocusChange(it.hasFocus) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RAIL.forEachIndexed { i, e ->
                    RailButton(e.name, e.icon, selected = i == tab, open = open,
                        // Nov 16: highlighting a tab doesn't open it — OK does. ▶ goes into the screen you're on.
                        modifier = Modifier.focusRequester(reqs[i])
                            .onPreviewKeyEvent { ev ->
                                ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                    ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                                    (i != tab || TabDown.handler?.invoke() == true)
                            },
                        onClick = {
                            if (i != tab) {
                                onTab(i); onEnter(i)
                                // Into the new screen once it's there.
                                scope.launch {
                                    kotlinx.coroutines.delay(250)
                                    if (TabDown.handler?.invoke() != true) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
                                }
                            } else {
                                onEnter(i)
                                TabDown.handler?.invoke()
                            }
                        })
                }
                Spacer(Modifier.weight(1f))
                // ⚙ Settings all the way at the bottom.
                RailButton("Settings", RailIcon.SETTINGS, selected = false, open = open, modifier = Modifier, onClick = onSettings)
            }
        }
    }
}

@Composable
private fun RailButton(name: String, icon: RailIcon, selected: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        // Green bar on the left of the tab you're on.
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
                // Icons stay green (black on the green pill when highlighted).
                LineIcon(icon, 24.dp, if (focused) Color.Black else FS.Green)
                if (open) {
                    Spacer(Modifier.width(14.dp))
                    Text(name, fontSize = 18.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}
