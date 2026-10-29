package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import com.forbiddenstreams.tv.data.CategoryPrefs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.LoginConfig
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.recording.Recordings
import com.forbiddenstreams.tv.reminders.Reminders
import java.io.File

@Composable
fun MenuScreen(section: Int) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MiniPlayer(300.dp, Modifier.align(Alignment.End).padding(bottom = 8.dp))
            when (section) {
                0 -> {
                    val recs = remember(tick) { Recordings.all(context) }
                    Header("Recordings", "OK = play · Hold OK = delete")
                    if (recs.isEmpty()) MessageBox("No recordings yet", "In the TV guide, hold OK on a show and pick Record.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recs, key = { it.id }) { r ->
                            val f = File(r.file)
                            ListCard(
                                title = r.title,
                                sub = "${r.channelName} · ${Fmt.day(r.start)} ${Fmt.range(r.start, r.end)} · " + when (r.status) {
                                    "recording" -> "● Recording now"
                                    "done" -> "Ready" + if (f.exists()) " (${f.length() / 1_048_576} MB)" else ""
                                    "failed" -> "Failed"
                                    else -> "Scheduled"
                                },
                                onClick = {
                                    if (f.exists() && f.length() > 0) Playback.one(context, PlayItem(r.title, android.net.Uri.fromFile(f).toString(), live = false, subtitle = r.channelName))
                                    else toast(context, "Nothing recorded yet")
                                },
                                onLongClick = { Recordings.delete(context, r.id); tick++; toast(context, "Deleted") },
                            )
                        }
                    }
                }
                2 -> CategoryEditor()
                1 -> {
                    val rems = remember(tick) { Reminders.prune(context); Reminders.all(context) }
                    Header("Reminders", "Hold OK = delete")
                    if (rems.isEmpty()) MessageBox("No reminders", "In the TV guide, hold OK on an upcoming show and pick Set reminder.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rems, key = { it.id }) { r ->
                            ListCard(
                                title = r.title,
                                sub = "${r.channelName} · ${Fmt.day(r.start)} ${Fmt.time(r.start)}",
                                onClick = { toast(context, "Hold OK to delete this reminder") },
                                onLongClick = { Reminders.remove(context, r.id); tick++ },
                            )
                        }
                    }
                }
                else -> {
                    val login = remember { Prefs(context).loadLogin() }
                    Header("Account", null)
                    when (login) {
                        is LoginConfig.Xtream -> {
                            InfoLine("Type", "Xtream Codes")
                            InfoLine("Server", login.server)
                            InfoLine("Username", login.username)
                            InfoLine("Server time zone", login.timezone ?: "Unknown")
                        }
                        is LoginConfig.M3u -> {
                            InfoLine("Type", "M3U playlist")
                            InfoLine("Playlist", login.url)
                            InfoLine("EPG", login.epgUrl ?: "None")
                        }
                        null -> InfoLine("Status", "Not signed in")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Forbidden Streams TV · plug in the revolution", color = FS.TextDim, fontSize = 15.sp)
                    // Last crash (if any), so it can be photographed and sent for fixing.
                    val crash = remember { java.io.File(context.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText() }
                    if (crash != null) {
                        Spacer(Modifier.height(16.dp))
                        Text("Last crash", color = FS.Live, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(crash.lines().take(14).joinToString("\n"), color = FS.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }

}

@Composable
private fun Header(title: String, hint: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = FS.Green, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (hint != null) Text(hint, color = FS.TextDim, fontSize = 15.sp)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = FS.TextDim, fontSize = 19.sp, modifier = Modifier.width(220.dp))
        Text(value, color = FS.Text, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListCard(title: String, sub: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Settings › Categories: reorder (▲ ▼), hide/show and parental-lock the live TV categories. */
@Composable
private fun CategoryEditor() {
    val context = LocalContext.current
    val cats = Session.liveCats
    val pin = remember(CategoryPrefs.version) { CategoryPrefs.pin(context) }
    // With a parental code set, this screen needs the code first.
    var unlocked by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf<String?>(null) } // "open" | "set" | "lock:<id>"
    var pendingLock by remember { mutableStateOf<String?>(null) }

    if (pin != null && !unlocked) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("🔒 Categories are protected", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Pill("Enter parental code", selected = true, onClick = { askPin = "open" })
        }
    } else Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Categories", color = FS.Green, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("▲ ▼ move · Hide · 🔒 Lock", color = FS.TextDim, fontSize = 15.sp)
            Spacer(Modifier.width(16.dp))
            Pill(if (pin == null) "Set parental code" else "Change code", selected = false, onClick = { askPin = "set" })
            Spacer(Modifier.width(6.dp))
            Pill("Reset", selected = false, onClick = { CategoryPrefs.reset(context); toast(context, "Categories back to the provider's order") })
        }
        if (cats == null) { LoadingBox("Loading categories"); return@Column }
        val list = remember(cats, CategoryPrefs.version) { CategoryPrefs.ordered(context, cats) }
        val hidden = remember(CategoryPrefs.version) { CategoryPrefs.hidden(context) }
        val locked = remember(CategoryPrefs.version) { CategoryPrefs.locked(context) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                val off = c.id in hidden
                val lock = c.id in locked
                Row(
                    Modifier.fillMaxWidth().background(FS.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = FS.TextDim, fontSize = 16.sp, modifier = Modifier.width(44.dp))
                    Text(
                        c.name, color = if (off || lock) FS.TextDim else FS.Text, fontSize = 19.sp,
                        fontWeight = if (off || lock) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (lock) Text("🔒 locked", color = FS.Live, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                    else if (off) Text("hidden", color = FS.Live, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                    Pill("▲", selected = false, fontSize = 16.sp, onClick = { CategoryPrefs.move(context, cats, c.id, -1) })
                    Pill("▼", selected = false, fontSize = 16.sp, onClick = { CategoryPrefs.move(context, cats, c.id, 1) })
                    Spacer(Modifier.width(6.dp))
                    Pill(if (off) "Show" else "Hide", selected = off, fontSize = 15.sp, onClick = { CategoryPrefs.toggleHidden(context, c.id) })
                    Spacer(Modifier.width(6.dp))
                    Pill(if (lock) "🔓 Unlock" else "🔒 Lock", selected = lock, fontSize = 15.sp, onClick = {
                        if (pin == null) { pendingLock = c.id; askPin = "set" } // needs a code first
                        else CategoryPrefs.toggleLocked(context, c.id)
                    })
                }
            }
        }
    }

    when (val mode = askPin) {
        "open" -> PinDialog("Enter parental code", onDone = { code ->
            if (code == pin) { unlocked = true; askPin = null } else toast(context, "Wrong code")
        }, onCancel = { askPin = null })
        "set" -> PinDialog("Choose a 4-digit parental code", onDone = { code ->
            if (code.length == 4 && code.all { it.isDigit() }) {
                CategoryPrefs.setPin(context, code)
                unlocked = true
                pendingLock?.let { if (it !in CategoryPrefs.locked(context)) CategoryPrefs.toggleLocked(context, it) }
                pendingLock = null
                askPin = null
                toast(context, "Parental code saved")
            } else toast(context, "Use exactly 4 numbers")
        }, onCancel = { askPin = null; pendingLock = null })
        null -> Unit
        else -> Unit
    }
}

/** Asks for a 4-digit code. */
@Composable
fun PinDialog(title: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(460.dp).background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            FsTextField(
                "4-digit code", code, { v -> code = v.filter { it.isDigit() }.take(4) },
                androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                password = true, done = { onDone(code) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("OK", selected = true, onClick = { onDone(code) })
                Pill("Cancel", selected = false, onClick = onCancel)
            }
        }
    }
}
