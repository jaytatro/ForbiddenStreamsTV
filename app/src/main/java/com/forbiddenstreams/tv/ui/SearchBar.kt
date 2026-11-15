package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Search bar used everywhere (Movies, TV Shows, Live TV, TV Guide).
 * Nov 8: no voice button, and the keyboard only opens when OK is pressed on the bar —
 * highlighting it just highlights it. ▼ / BACK leave it.
 */
@Composable
fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    onSearch: () -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    val editReq = remember { FocusRequester() }
    val shape = RoundedCornerShape(50)
    @Composable
    fun Inside(inner: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GText("🔍", fontSize = fontSize * 0.85f)
            Spacer(Modifier.width(10.dp))
            Box {
                if (query.isEmpty()) Text(hint, color = FS.TextDim, fontSize = fontSize, maxLines = 1)
                inner()
            }
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!editing) {
            // Looks like the box; OK opens the keyboard.
            FocusCard(
                onClick = { editing = true },
                shape = shape,
                container = FS.Panel,
                focusedScale = 1.02f,
                modifier = fieldModifier,
            ) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Inside { if (query.isNotEmpty()) Text(query, color = androidx.tv.material3.LocalContentColor.current, fontSize = fontSize, maxLines = 1) }
                }
            }
        } else {
            var hadFocus by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                for (t in 0 until 8) {
                    kotlinx.coroutines.delay(50)
                    val ok = try { editReq.requestFocus(); true } catch (e: Exception) { false }
                    if (ok) {
                        // The keyboard can ignore the first request while the text box is still connecting: ask a few times.
                        keyboard?.show()
                        kotlinx.coroutines.delay(120); keyboard?.show()
                        kotlinx.coroutines.delay(250); keyboard?.show()
                        break
                    }
                }
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = FS.Text, fontSize = fontSize),
                cursorBrush = SolidColor(FS.Green),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); editing = false; onSearch() }),
                modifier = fieldModifier.focusRequester(editReq)
                    .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) editing = false }
                    .background(FS.GreenDim, shape)
                    .border(2.dp, FS.Green, shape)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                decorationBox = { inner -> Inside(inner) },
            )
        }
    }
}

/** Live TV / TV Guide: does a channel match what was typed (every word)? */
fun channelMatches(name: String, query: String): Boolean {
    val n = name.lowercase()
    return query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { n.contains(it) }
}

/**
 * Live TV / TV Guide (Nov 8): the 🔍 button opens this. The text box takes the cursor and the keyboard
 * opens right away; matching channels (every word) list underneath as you type. OK on one plays it.
 */
@Composable
fun ChannelSearchDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { com.forbiddenstreams.tv.data.Prefs(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    var query by remember { mutableStateOf("") }
    val fieldReq = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val all = remember(com.forbiddenstreams.tv.data.Session.liveAll, com.forbiddenstreams.tv.data.CategoryPrefs.version) {
        channelsOf(prefs, Group.Search)
    }
    val q = query.trim()
    // Settings › Other › Search › Show favorite channels first (Nov 15).
    val results = remember(q, all) {
        if (q.length < 2) emptyList() else {
            val m = all.filter { channelMatches(it.name, q) }
            val favs = prefs.favorites
            (if (com.forbiddenstreams.tv.data.AppSettings.searchFavFirst) m.sortedBy { if (it.id in favs) 0 else 1 } else m).take(200)
        }
    }
    // Shows on now / later / earlier whose name matches (from the guides already loaded) — Nov 15.
    val shows = remember(q, all) {
        if (q.length < 3) emptyList() else {
            val now = System.currentTimeMillis()
            val pastToo = com.forbiddenstreams.tv.data.AppSettings.searchPastNoCatchup
            all.asSequence().flatMap { c ->
                (com.forbiddenstreams.tv.data.Session.cachedEpg(c) ?: emptyList()).asSequence()
                    .filter { p -> channelMatches(p.title, q) && (p.end > now || pastToo || c.hasArchive) }
                    .map { c to it }
            }.sortedWith(compareBy({ if (it.second.isLive(now)) 0 else if (it.second.start > now) 1 else 2 }, { kotlin.math.abs(it.second.start - now) }))
                .take(60).toList()
        }
    }
    // Settings › Other › Search › Prefer voice search: the microphone opens first.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (com.forbiddenstreams.tv.data.AppSettings.preferVoice) {
            try {
                voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel or show"))
            } catch (e: Exception) { toast(context, "Voice search isn't available on this box") }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.activity.compose.BackHandler { onClose() }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            for (t in 0 until 10) {
                kotlinx.coroutines.delay(60)
                val ok = try { fieldReq.requestFocus(); true } catch (e: Exception) { false }
                if (ok) {
                    // Some boxes ignore the first request while the box is connecting: ask a few times.
                    repeat(4) {
                        keyboard?.show()
                        try {
                            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showSoftInput(view.findFocus() ?: view, 0)
                        } catch (e: Exception) { }
                        kotlinx.coroutines.delay(150)
                    }
                    break
                }
            }
        }
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().background(FS.Bg).padding(horizontal = 80.dp, vertical = 28.dp),
        ) {
            Text("Search channels", color = FS.Green, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            val shape = RoundedCornerShape(50)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = FS.Text, fontSize = 22.sp),
                cursorBrush = SolidColor(FS.Green),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    if (results.isNotEmpty()) try { firstResult.requestFocus() } catch (e: Exception) { }
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldReq)
                    .background(FS.GreenDim, shape).border(2.dp, FS.Green, shape)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GText("🔍", fontSize = 20.sp)
                        Spacer(Modifier.width(12.dp))
                        Box {
                            if (query.isEmpty()) Text("Type a channel name…", color = FS.TextDim, fontSize = 22.sp)
                            inner()
                        }
                    }
                },
            )
            // Search history (Settings › Other › Search › Show search history).
            val past = remember(com.forbiddenstreams.tv.data.AppSettings.version) { com.forbiddenstreams.tv.data.AppSettings.searches() }
            if (q.isEmpty() && past.isNotEmpty() && com.forbiddenstreams.tv.data.AppSettings.searchHistory) {
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent:", color = FS.TextDim, fontSize = 15.sp)
                    past.forEach { p -> Pill("🕘 $p", selected = false, outlined = true, fontSize = 15.sp, onClick = { query = p }) }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    q.length < 2 -> "Type at least 2 letters"
                    results.isEmpty() && shows.isEmpty() -> "No channels or shows match \"$q\""
                    else -> "${results.size} channels · ${shows.size} shows · ▼ to pick one · OK to watch"
                },
                color = FS.TextDim, fontSize = 15.sp,
            )
            Spacer(Modifier.size(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                items(results.size, key = { results[it].id }) { i ->
                    val c = results[i]
                    FocusCard(
                        onClick = {
                            com.forbiddenstreams.tv.data.AppSettings.addSearch(q)
                            // "Stay on search screen when switching channels": play behind, keep searching.
                            if (com.forbiddenstreams.tv.data.AppSettings.searchStay) playChannels(context, results, i, fullScreen = false)
                            else { onClose(); playChannels(context, results, i) }
                        },
                        focusedScale = 1.01f,
                        modifier = Modifier.fillMaxWidth().height(64.dp)
                            .then(if (i == 0) Modifier.focusRequester(firstResult) else Modifier),
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.num}", color = FS.TextDim, fontSize = 16.sp, modifier = Modifier.width(60.dp))
                            Box(Modifier.size(width = 76.dp, height = 44.dp), contentAlignment = Alignment.Center) { NetImage(c.logo, Modifier.size(width = 76.dp, height = 44.dp)) }
                            Spacer(Modifier.width(16.dp))
                            val on = com.forbiddenstreams.tv.data.Session.cachedEpg(c)?.firstOrNull { it.isLive(System.currentTimeMillis()) }?.title
                            androidx.compose.foundation.layout.Column {
                                Text(c.name, color = FS.Green, fontSize = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                                if (on != null) Text(on, color = FS.TextDim, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }
                if (shows.isNotEmpty()) item(key = "shows_h") {
                    Text("SHOWS", color = FS.GreenMid, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        modifier = Modifier.padding(top = 10.dp, start = 6.dp))
                }
                items(shows.size, key = { "s${shows[it].first.id}:${shows[it].second.start}" }) { i ->
                    val (c, p) = shows[i]
                    val now = System.currentTimeMillis()
                    val live = p.isLive(now)
                    val past = p.end <= now
                    FocusCard(
                        onClick = {
                            com.forbiddenstreams.tv.data.AppSettings.addSearch(q)
                            when {
                                live -> { if (!com.forbiddenstreams.tv.data.AppSettings.searchStay) onClose(); playChannels(context, listOf(c), 0, fullScreen = !com.forbiddenstreams.tv.data.AppSettings.searchStay) }
                                past -> {
                                    val it = if (c.hasArchive) com.forbiddenstreams.tv.player.playbackItem(c, p) else null
                                    if (it != null) { if (!com.forbiddenstreams.tv.data.AppSettings.searchStay) onClose(); com.forbiddenstreams.tv.player.Playback.one(context, it) }
                                    else toast(context, "Playback isn't available for this show")
                                }
                                else -> { com.forbiddenstreams.tv.reminders.Reminders.add(context, c, p); toast(context, "Reminder set for ${Fmt.day(p.start)} ${Fmt.time(p.start)}") }
                            }
                        },
                        focusedScale = 1.01f,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (live) "● NOW" else if (past) "⟲ ${Fmt.day(p.start)}" else Fmt.day(p.start),
                                color = if (live) Color(0xFFFF5A5A) else FS.TextDim, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                Text(p.title, color = FS.Text, fontSize = 18.sp, maxLines = 1)
                                Text("${c.name} · ${Fmt.range(p.start, p.end)}" + if (!live && !past) "  ·  OK = reminder" else "",
                                    color = FS.TextDim, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

