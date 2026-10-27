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
fun MenuScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    var section by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    val sections = listOf("⏺  Recordings", "⏰  Reminders", "⟳  Refresh content", "👤  Account", "⎋  Log out")

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(200.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            sections.forEachIndexed { i, label ->
                SideItem(label, selected = section == i, modifier = Modifier.fillMaxWidth(), onClick = {
                    when (i) {
                        2 -> { Session.refresh(); toast(context, "Channels, movies and shows will reload") }
                        4 -> { confirmLogout = true }
                        else -> { section = i }
                    }
                })
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
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
                }
            }
        }
    }

    if (confirmLogout) {
        ChoiceDialog(
            title = "Log out?",
            subtitle = "You'll need your login details to sign back in.",
            options = listOf(
                "Log out" to { confirmLogout = false; onLogout() },
                "Cancel" to { confirmLogout = false },
            ),
            onDismiss = { confirmLogout = false },
        )
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
