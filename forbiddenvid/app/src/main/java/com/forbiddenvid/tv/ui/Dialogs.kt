package com.forbiddenvid.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** In a list of [size]: ▼ on the last item → first, ▲ on the first → last ([go] moves the cursor). */
fun Modifier.roundRobin(i: Int, size: Int, go: (Int) -> Unit): Modifier = onPreviewKeyEvent { ev ->
    if (size < 2 || ev.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
    when {
        ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && i == size - 1 -> { go(0); true }
        ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && i == 0 -> { go(size - 1); true }
        else -> false
    }
}

/** Pop-up with a title and a scrolling list of choices (same look as Forbidden Streams TV). */
@Composable
fun ChoiceDialog(
    title: String,
    subtitle: String? = null,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // A long-press opens this while OK is still held down: ignore that held key so nothing is picked by accident.
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
                .width(560.dp)
                .background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = FS.TextDim, fontSize = 15.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(6.dp))
            val reqs = remember(options.size) { List(options.size) { if (it == 0) first else FocusRequester() } }
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            LazyColumn(
                state = state,
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(options.size) { i ->
                    val (label, action) = options[i]
                    FocusCard(
                        onClick = action,
                        modifier = Modifier.fillMaxWidth().focusRequester(reqs[i])
                            .roundRobin(i, options.size) { to ->
                                scope.launch {
                                    try { state.scrollToItem(to) } catch (e: Exception) { }
                                    repeat(5) { delay(30); try { reqs[to].requestFocus(); return@launch } catch (e: Exception) { } }
                                }
                            },
                    ) {
                        Text(label, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); try { first.requestFocus() } catch (e: Exception) { } }
}

/** A green-bordered text box for the TV (OK opens the on-screen keyboard). */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
        cursorBrush = SolidColor(FS.Green),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onDone() }, onSearch = { keyboard?.hide(); onDone() }, onGo = { keyboard?.hide(); onDone() }),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .background(if (focused) FS.GreenDim else FS.Panel2, RoundedCornerShape(12.dp))
                    .border(2.dp, if (focused) FS.Green else FS.Line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = FS.TextDim, fontSize = 20.sp, maxLines = 1)
                inner()
            }
        },
    )
}

/** Pop-up asking for one line of text (an add-on address). */
@Composable
fun TextInputDialog(
    title: String,
    subtitle: String?,
    placeholder: String,
    confirm: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(680.dp)
                .background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = FS.TextDim, fontSize = 15.sp)
            TvTextField(text, { text = it }, placeholder, onDone = { if (text.isNotBlank()) onConfirm(text.trim()) },
                modifier = Modifier.fillMaxWidth().focusRequester(field), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(confirm, selected = true, onClick = { if (text.isNotBlank()) onConfirm(text.trim()) })
                Pill("Cancel", selected = false, onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { delay(150); try { field.requestFocus() } catch (e: Exception) { } }
}
