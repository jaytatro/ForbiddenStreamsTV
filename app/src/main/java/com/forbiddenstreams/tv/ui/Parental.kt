package com.forbiddenstreams.tv.ui

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
                    CategoryPrefs.setAdultUnlocked(false)
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
                    CategoryPrefs.setAdultUnlocked(!CategoryPrefs.adultUnlocked)
                    toast(context, if (CategoryPrefs.adultUnlocked) "18+ shown until the app closes" else "18+ hidden")
                    onClose()
                }
                opts += "🔓  Turn off parental code" to {
                    CategoryPrefs.setPin(context, null)
                    CategoryPrefs.setAdultUnlocked(false)
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
