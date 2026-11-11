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
    val results = remember(q, all) { if (q.length < 2) emptyList() else all.filter { channelMatches(it.name, q) }.take(200) }
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
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    q.length < 2 -> "Type at least 2 letters"
                    results.isEmpty() -> "No channels match \"$q\""
                    else -> "${results.size} channels · ▼ to pick one · OK to watch"
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
                        onClick = { onClose(); playChannels(context, results, i) },
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
            }
        }
    }
}

