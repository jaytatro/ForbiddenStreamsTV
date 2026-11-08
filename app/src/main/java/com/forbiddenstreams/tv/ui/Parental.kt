package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.Session

/** Hold OK on a category (Live TV / TV Guide): "Hide category". */
@Composable
fun HideCategoryDialog(g: Group.Cat, onClose: () -> Unit) {
    val context = LocalContext.current
    ChoiceDialog(
        title = g.title,
        subtitle = "Hidden categories can be shown again in Settings › Parental code" +
            if (CategoryPrefs.pin(context) != null) " (needs your code)." else ".",
        options = listOf(
            "🚫  Hide category" to {
                CategoryPrefs.toggleHidden(context, g.cat.id)
                toast(context, "${g.title} hidden")
                onClose()
            },
            "Close" to onClose,
        ),
        onDismiss = onClose,
    )
}

/**
 * Settings › Parental code (Nov 8 2026).
 * With a code on: the code is asked first; hidden categories can only be seen/shown again with it,
 * and anything named 18+ (also XXX / Adult) is hidden everywhere unless unlocked for this session.
 */
@Composable
fun ParentalSettings(onClose: () -> Unit) {
    val context = LocalContext.current
    val hasPin = CategoryPrefs.pin(context) != null
    var step by remember { mutableStateOf(if (hasPin) "ask" else "menu") }
    var firstCode by remember { mutableStateOf<String?>(null) }
    when (step) {
        "ask" -> PinDialog("Enter your parental code", onDone = { code ->
            if (code == CategoryPrefs.pin(context)) step = "menu" else toast(context, "Wrong code")
        }, onCancel = onClose)

        "set" -> PinDialog(if (firstCode == null) "Choose a 4-digit parental code" else "Enter the same code again", onDone = { code ->
            when {
                code.length != 4 -> toast(context, "The code needs 4 numbers")
                firstCode == null -> firstCode = code
                code == firstCode -> {
                    CategoryPrefs.setPin(context, code)
                    CategoryPrefs.unlockAdult(false)
                    toast(context, "Parental code is on · 18+ is hidden")
                    firstCode = null; step = "menu"
                }
                else -> { toast(context, "The codes didn't match. Try again."); firstCode = null }
            }
        }, onCancel = { firstCode = null; step = "menu" })

        "hidden" -> {
            val cats = Session.liveCats.orEmpty()
            val hidden = remember(CategoryPrefs.version) { CategoryPrefs.hidden(context) }
            val list = cats.filter { it.id in hidden }
            ChoiceDialog(
                title = "Hidden categories",
                subtitle = if (list.isEmpty()) "Nothing is hidden. Hold OK on a category in Live TV or TV Guide to hide it."
                    else "OK on a category shows it again.",
                options = list.map { c ->
                    "👁  ${c.name}" to { CategoryPrefs.toggleHidden(context, c.id); toast(context, "${c.name} is back") }
                } + listOf("Back" to { step = "menu" }),
                onDismiss = { step = "menu" },
            )
        }

        else -> {
            val on = CategoryPrefs.pin(context) != null
            val nHidden = CategoryPrefs.hidden(context).size
            val opts = ArrayList<Pair<String, () -> Unit>>()
            if (!on) {
                opts += "🔒  Turn on parental code" to { step = "set" }
            } else {
                opts += "🔑  Change code" to { step = "set" }
                opts += (if (CategoryPrefs.adultUnlocked) "🔞  Hide 18+ again" else "🔞  Show 18+ until the app closes") to {
                    CategoryPrefs.unlockAdult(!CategoryPrefs.adultUnlocked)
                    toast(context, if (CategoryPrefs.adultUnlocked) "18+ shown until the app closes" else "18+ hidden")
                    onClose()
                }
                opts += "🔓  Turn off parental code" to {
                    CategoryPrefs.setPin(context, null)
                    CategoryPrefs.unlockAdult(false)
                    toast(context, "Parental code is off")
                    onClose()
                }
            }
            opts += "🚫  Hidden categories ($nHidden)" to { step = "hidden" }
            opts += "Close" to onClose
            ChoiceDialog(
                title = "Parental code",
                subtitle = if (on) "On · hidden categories need the code · 18+ is ${if (CategoryPrefs.adultUnlocked) "shown for now" else "hidden"}"
                    else "Off · turn it on to lock hidden categories and hide anything 18+",
                options = opts,
                onDismiss = onClose,
            )
        }
    }
}

/**
 * Settings › Re-order and hide categories (Live TV and TV Guide; Nov 8 2026).
 * OK on a category picks it up (⇅): ▲ ▼ move it, OK again puts it down there. ▶ goes to its switch:
 * on = shown, off = hidden. With a parental code on, the code is asked first.
 */
@Composable
fun ReorderCategories(onClose: () -> Unit) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(CategoryPrefs.pin(context) == null) }
    if (!unlocked) {
        PinDialog("Enter your parental code", onDone = { code ->
            if (code == CategoryPrefs.pin(context)) unlocked = true else toast(context, "Wrong code")
        }, onCancel = onClose)
        return
    }
    val cats = Session.liveCats.orEmpty().distinctBy { it.id }
    val list = remember(cats, CategoryPrefs.version) { CategoryPrefs.ordered(context, cats) }
    val hidden = remember(CategoryPrefs.version) { CategoryPrefs.hidden(context) }
    var moving by remember { mutableStateOf<String?>(null) }
    val reqs = remember(list.size) { List(list.size) { androidx.compose.ui.focus.FocusRequester() } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    fun focusAt(i: Int) {
        scope.launch {
            try { state.scrollToItem((i - 3).coerceAtLeast(0)) } catch (e: Exception) { }
            repeat(6) { kotlinx.coroutines.delay(40); try { reqs[i].requestFocus(); return@launch } catch (e: Exception) { } }
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (moving != null) moving = null else onClose() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.activity.compose.BackHandler { if (moving != null) moving = null else onClose() }
        androidx.compose.runtime.LaunchedEffect(Unit) { if (list.isNotEmpty()) focusAt(0) }
        Column(Modifier.fillMaxSize().background(FS.Bg).padding(horizontal = 80.dp, vertical = 26.dp)) {
            Text("Re-order and hide categories", color = FS.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (moving != null) "Moving: ▲ ▼ to move it · OK to put it here"
                else "Live TV and TV Guide · OK on a category to move it · ▶ to its switch (on = shown, off = hidden)",
                color = FS.TextDim, fontSize = 15.sp,
            )
            Spacer(Modifier.height(14.dp))
            if (list.isEmpty()) { Text("No categories loaded yet.", color = FS.TextDim, fontSize = 17.sp); return@Column }
            androidx.compose.foundation.lazy.LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp)) {
                // Rows are kept by position (not by category) so the cursor stays put while names move.
                items(list.size) { i ->
                    val c = list[i]
                    val shown = c.id !in hidden
                    val isMoving = moving == c.id
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FocusCard(
                            onClick = {
                                if (isMoving) { moving = null; toast(context, "${c.name} set at #${i + 1}") }
                                else moving = c.id
                            },
                            selected = isMoving,
                            focusedScale = 1.01f,
                            modifier = Modifier.weight(1f).height(54.dp).focusRequester(reqs[i])
                                .onPreviewKeyEvent { ev ->
                                    if (!isMoving || ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val step = when (ev.nativeKeyEvent.keyCode) {
                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0
                                        else -> return@onPreviewKeyEvent false
                                    }
                                    val j = i + step
                                    if (step != 0 && j in list.indices) {
                                        CategoryPrefs.move(context, cats, c.id, step)
                                        focusAt(j)
                                    }
                                    true
                                },
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = FS.TextDim, fontSize = 15.sp, modifier = Modifier.width(44.dp))
                                Text(if (isMoving) "⇅" else "≡", color = if (isMoving) FS.Green else FS.TextDim, fontSize = 20.sp)
                                Spacer(Modifier.width(14.dp))
                                Text(c.name, color = if (shown) FS.Green else FS.TextDim, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (!shown) Text("Hidden", color = FS.TextDim, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        FocusCard(
                            onClick = { if (moving == null) CategoryPrefs.toggleHidden(context, c.id) },
                            focusedScale = 1.05f,
                            container = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.width(84.dp).height(54.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { OnOffSwitch(shown) }
                        }
                    }
                }
            }
        }
    }
}
