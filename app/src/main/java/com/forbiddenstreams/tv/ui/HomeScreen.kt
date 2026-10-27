package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import com.forbiddenstreams.tv.reminders.Reminder
import com.forbiddenstreams.tv.reminders.Reminders
import com.forbiddenstreams.tv.sports.League
import kotlinx.coroutines.delay

private data class TopTab(val label: String, val league: League? = null)

private val tabs = listOf(
    TopTab("MENU"),
    TopTab("CHANNELS"),
    TopTab("🎬 MOVIES"),
    TopTab("📼 TV SHOWS"),
    TopTab("🏈 NFL", League.NFL),
    TopTab("🏀 NBA", League.NBA),
    TopTab("🏒 NHL", League.NHL),
    TopTab("⚾ MLB", League.MLB),
)

@Composable
fun HomeScreen(onLogout: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { startFocus.requestFocus() } catch (e: Exception) { } }
    // VOD (Movies, TV Shows) and Sports don't show the live preview: switch it off there.
    LaunchedEffect(tab) { if (tab in 2..7 && PlayerHolder.isMiniActive) PlayerHolder.stop() }

    // BACK on the main screens: first press shows a hint, second press within 2.5 s closes the app.
    val activity = LocalContext.current as? android.app.Activity
    var exitHint by remember { mutableStateOf(false) }
    BackHandler {
        if (exitHint) {
            PlayerHolder.stop()
            activity?.finish()
        } else {
            exitHint = true
        }
    }
    LaunchedEffect(exitHint) { if (exitHint) { delay(2500); exitHint = false } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Top menu: CHANNELS · VOD · SPORTS. Moving onto VOD or SPORTS shows its tabs underneath.
        val vodTabs = listOf(2, 3)
        val sportTabs = listOf(4, 5, 6, 7)
        var expanded by remember { mutableIntStateOf(if (tab in vodTabs) 1 else if (tab in sportTabs) 2 else 0) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Color(0xFF0B260C), Color(0xFF050805), Color(0xFF0B260C))))
                .padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(84.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        "📺 CHANNELS", selected = tab == 1, onClick = { tab = 1; expanded = 0 },
                        modifier = Modifier.focusRequester(startFocus).onFocusChanged { if (it.isFocused) expanded = 0 },
                    )
                    Pill(
                        "🎬 VOD", selected = tab in vodTabs, onClick = { if (tab !in vodTabs) tab = 2; expanded = 1 },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) expanded = 1 },
                    )
                    Pill(
                        "🏆 SPORTS", selected = tab in sportTabs, onClick = { if (tab !in sportTabs) tab = 4; expanded = 2 },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) expanded = 2 },
                    )
                }
                Row(
                    Modifier.height(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val sub = when (expanded) { 1 -> vodTabs; 2 -> sportTabs; else -> emptyList() }
                    // Sub-tabs start at the left edge, under CHANNELS.
                    for (i in sub) {
                        // The preview keeps playing until Movies, TV Shows or a sport is actually opened.
                        Pill(tabs[i].label, selected = tab == i, onClick = { tab = i }, fontSize = 15.sp,
                            modifier = Modifier)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.time(now), color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(Fmt.longDay(now), color = FS.TextDim, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            // Settings (was the MENU tab).
            Pill("⚙", selected = tab == 0, onClick = { tab = 0; expanded = 0 }, fontSize = 24.sp,
                modifier = Modifier.onFocusChanged { if (it.isFocused) expanded = 0 })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val t = tabs[tab]
            when {
                tab == 0 -> MenuScreen(onLogout = onLogout)
                tab == 1 -> ChannelsScreen()
                tab == 2 -> MoviesScreen()
                tab == 3 -> SeriesScreen()
                t.league != null -> SportsScreen(t.league)
            }
        }
    }
    if (exitHint) {
        Text(
            "Click back one more time to close",
            color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .background(FS.Green, RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }
    }
    ReminderWatcher()
}

/** Pops up "Starting now" when a reminder is due while the app is open. */
@Composable
private fun ReminderWatcher() {
    val context = LocalContext.current
    var showing by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (showing == null) {
                showing = Reminders.due(context).firstOrNull()
            }
            delay(20_000)
        }
    }
    val r = showing ?: return
    ChoiceDialog(
        title = "Starting now: ${r.title}",
        subtitle = "On ${r.channelName}",
        options = listOf(
            "▶ Watch now" to {
                Reminders.remove(context, r.id)
                showing = null
                Playback.one(context, PlayItem(r.channelName, r.channelUrl, live = true, subtitle = r.title))
            },
            "Dismiss" to {
                Reminders.remove(context, r.id)
                showing = null
            },
        ),
        onDismiss = { Reminders.remove(context, r.id); showing = null },
    )
}

@Composable
fun ChoiceDialog(
    title: String,
    subtitle: String? = null,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // A long-press opens this while OK is still held down. Ignore that held key (and its
    // release) so the first option isn't picked by accident; a fresh press works normally.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { ev ->
                    val ne = ev.nativeKeyEvent
                    val ok = ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        ne.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        ne.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!ok || armed) return@onPreviewKeyEvent false
                    if (ne.action == android.view.KeyEvent.ACTION_DOWN && ne.repeatCount == 0) {
                        armed = true
                        false
                    } else {
                        true
                    }
                }
                .width(520.dp)
                .background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = FS.TextDim, fontSize = 16.sp, maxLines = 4)
            Spacer(Modifier.size(6.dp))
            options.forEachIndexed { i, (label, action) ->
                FocusCard(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    Text(label, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); try { first.requestFocus() } catch (e: Exception) { } }
}
