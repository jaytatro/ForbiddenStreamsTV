package com.forbiddenstreams.tv.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
 * Search bar used everywhere (Movies, TV Shows, Live TV, TV Guide; Nov 8 2026):
 * a rounded text box and a 🎤 voice-search button next to it. Saying a name fills the box.
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
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { onChange(said); onSearch() }
    }
    val shape = RoundedCornerShape(50)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = FS.Text, fontSize = fontSize),
            cursorBrush = SolidColor(FS.Green),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch() }),
            modifier = fieldModifier
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) FS.GreenDim else FS.Panel, shape)
                .border(2.dp, if (focused) FS.Green else FS.Line, shape)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔍", fontSize = fontSize * 0.85f)
                    Spacer(Modifier.width(10.dp))
                    Box {
                        if (query.isEmpty()) Text(hint, color = FS.TextDim, fontSize = fontSize, maxLines = 1)
                        inner()
                    }
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        FocusCard(
            onClick = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, hint)
                try { voice.launch(i) } catch (e: Exception) { toast(context, "Voice search isn't available on this box") }
            },
            shape = CircleShape,
            container = FS.Panel,
            focusedScale = 1.1f,
            modifier = Modifier.size(48.dp),
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("🎤", fontSize = 20.sp, color = Color.White) }
        }
    }
}

/** Live TV / TV Guide: does a channel match what was typed (every word)? */
fun channelMatches(name: String, query: String): Boolean {
    val n = name.lowercase()
    return query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.all { n.contains(it) }
}
