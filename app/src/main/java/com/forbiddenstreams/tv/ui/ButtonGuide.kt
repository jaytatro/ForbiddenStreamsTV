package com.forbiddenstreams.tv.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Button settings (Settings › Buttons) and whether the first-start walkthrough was seen. */
object ButtonPrefs {
    private const val FILE = "forbidden_streams"

    /** How long ◀ / ▶ must be held in the TV guide before it scrolls through time. */
    var holdMs by mutableIntStateOf(2000)
        private set

    /** Short ◀ in the TV guide: true = back to the category list, false = one show back. */
    var leftOpensCategories by mutableStateOf(true)
        private set

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        holdMs = sp.getInt("btn_hold_ms", 2000)
        leftOpensCategories = sp.getBoolean("btn_left_categories", true)
    }

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setHold(context: Context, ms: Int) { holdMs = ms; sp(context).edit().putInt("btn_hold_ms", ms).apply() }
    fun setLeft(context: Context, categories: Boolean) {
        leftOpensCategories = categories
        sp(context).edit().putBoolean("btn_left_categories", categories).apply()
    }

    fun tourSeen(context: Context) = sp(context).getBoolean("tour_seen", false)
    fun setTourSeen(context: Context) = sp(context).edit().putBoolean("tour_seen", true).apply()
}

/** One page of the walkthrough: a title and (button, what it does) rows. */
data class GuidePage(val title: String, val rows: List<Pair<String, String>>)

fun guidePages(): List<GuidePage> {
    val hold = "${ButtonPrefs.holdMs / 1000} s"
    return listOf(
        GuidePage(
            "Welcome to Forbidden Streams TV",
            listOf(
                "▲ ▼ ◀ ▶" to "Move around",
                "OK" to "Pick / open",
                "Hold OK" to "More options (record, reminders, favorites…)",
                "BACK" to "Go back · twice on the side bar closes the app",
            ),
        ),
        GuidePage(
            "Side bar and categories",
            listOf(
                "▲ ▼ on the side bar" to "Channels · Movies · TV Shows · Sports · Recordings · Settings",
                "▶ from an icon" to "Into its category list",
                "▲ ▼ in the list" to "Highlighting a category shows it on the right",
                "OK or ▶" to "Go into that category",
                "◀ on any screen" to "Back to the category you were in",
            ),
        ),
        GuidePage(
            "TV guide",
            listOf(
                "▲ ▼" to "Change channel",
                "CH + / CH –" to "Jump 8 channels",
                "▶ / ◀" to "Next show / back to the categories",
                "Hold ◀ ▶ ($hold)" to "Scroll through the day",
                "▲ on the top channel" to "Dates: ◀ ▶ change the day, ▼ back",
                "Hold ▲" to "The live preview (OK = full screen)",
                "OK" to "Watch live · catch-up for past shows",
                "Hold OK" to "Record · reminder · watch from start · favorites",
            ),
        ),
        GuidePage(
            "Watching live TV",
            listOf(
                "OK" to "Last Channel · Record (again = stop/cancel) · Multi-View",
                "▲ ▼ / CH ±" to "Change channel",
                "◀" to "Category and channel list over the picture",
                "Hold OK" to "See-through TV guide",
                "BACK" to "Keeps playing in the preview window",
            ),
        ),
        GuidePage(
            "Movies, shows and Multi-View",
            listOf(
                "◀ / ▶ while playing" to "Skip back / ahead 10 seconds",
                "OK while playing" to "Pause / play",
                "Recently Watched" to "Resume or start over",
                "Multi-View: arrows" to "Pick a screen (it has the sound)",
                "Multi-View: OK" to "Menu: add, change, enlarge, remove screens",
            ),
        ),
        GuidePage(
            "Sports",
            listOf(
                "▲ ▼ in the Sports list" to "NFL · NBA · NHL · MLB",
                "OK on a game" to "Pick a team or network channel",
                "Settings › Favorite Teams" to "Hold OK on a team; its games go first with ⭐",
                "Settings › Buttons" to "Change button timing and see this guide again",
            ),
        ),
    )
}

@Composable
private fun PageBody(page: GuidePage) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        page.rows.forEach { (btn, what) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    btn, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(250.dp).background(FS.Green, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(18.dp))
                Text(what, color = FS.Green, fontSize = 18.sp)
            }
        }
    }
}

/**
 * Shown the first time the app opens after install (and from Settings › Buttons).
 * OK / ▶ = next, ◀ = back, BACK = close.
 */
@Composable
fun Walkthrough(onDone: () -> Unit) {
    val pages = remember { guidePages() }
    var i by remember { mutableIntStateOf(0) }
    val req = remember { FocusRequester() }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { onDone() }
        LaunchedEffect(Unit) { delay(120); try { req.requestFocus() } catch (e: Exception) { } }
        Box(
            Modifier.fillMaxSize().background(Color(0xF2000000))
                .focusRequester(req)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (ev.nativeKeyEvent.repeatCount == 0) { if (i < pages.size - 1) i++ else onDone() }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (i > 0) i--; true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(900.dp).background(Color.Black, RoundedCornerShape(18.dp))
                    .border(2.dp, FS.GreenDeep, RoundedCornerShape(18.dp)).padding(36.dp),
            ) {
                Text("${i + 1} / ${pages.size}", color = FS.TextDim, fontSize = 14.sp)
                Text(pages[i].title, color = FS.Green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(22.dp))
                PageBody(pages[i])
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    pages.indices.forEach { d ->
                        Box(Modifier.padding(end = 8.dp).size(10.dp).background(if (d == i) FS.Green else FS.GreenDim, CircleShape))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (i < pages.size - 1) "OK = next · ◀ = back · BACK = skip" else "OK = start watching",
                        color = FS.Green, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Settings › Buttons: button timing, what ◀ does in the guide, and the full button list. */
@Composable
fun ButtonSetupScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tour by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Buttons", color = FS.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Set up how the remote works, and see what every button does.", color = FS.TextDim, fontSize = 15.sp)
        }
        item {
            Text("Hold ◀ ▶ in the TV guide to scroll through time after", color = FS.Green, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1000 to "1 second", 2000 to "2 seconds", 3000 to "3 seconds").forEach { (ms, label) ->
                    Pill(label, selected = ButtonPrefs.holdMs == ms, onClick = { ButtonPrefs.setHold(context, ms) })
                }
            }
        }
        item {
            Text("◀ in the TV guide", color = FS.Green, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Back to the categories", selected = ButtonPrefs.leftOpensCategories, onClick = { ButtonPrefs.setLeft(context, true) })
                Pill("One show back", selected = !ButtonPrefs.leftOpensCategories, onClick = { ButtonPrefs.setLeft(context, false) })
            }
        }
        item { Pill("▶  Show the walkthrough again", selected = false, onClick = { tour = true }) }
        items(guidePages()) { page ->
            // Focusable so ▼ scrolls down through the list.
            var focused by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(12.dp))
                    .border(if (focused) 2.dp else 1.dp, if (focused) FS.Green else FS.GreenDim, RoundedCornerShape(12.dp))
                    .onFocusChanged { focused = it.isFocused }.focusable().padding(18.dp),
            ) {
                Text(page.title, color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                PageBody(page)
            }
        }
    }
    if (tour) Walkthrough(onDone = { tour = false })
}
