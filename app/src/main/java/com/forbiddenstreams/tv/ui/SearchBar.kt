package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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
            Text("🔍", fontSize = fontSize * 0.85f)
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
