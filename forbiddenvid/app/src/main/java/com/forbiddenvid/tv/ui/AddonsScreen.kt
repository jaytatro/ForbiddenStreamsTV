package com.forbiddenvid.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenvid.tv.data.Addon
import com.forbiddenvid.tv.data.Addons
import com.forbiddenvid.tv.data.PhoneLink
import com.forbiddenvid.tv.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add-ons: install by address (typed on the TV or sent from a phone), turn on/off, re-order, remove.
 * Forbidden Vid ships with no add-ons; what's installed is up to the user.
 */
@Composable
fun AddonsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf<String?>(null) } // address being read
    var confirm by remember { mutableStateOf<Addon?>(null) }
    var options by remember { mutableStateOf<Addon?>(null) }
    var phoneAddress by remember { mutableStateOf<String?>(null) }

    fun preview(address: String) {
        checking = address
        scope.launch {
            try {
                confirm = Addons.fetch(address)
            } catch (e: Exception) {
                toast(context, e.message ?: "Couldn't read that add-on")
            }
            checking = null
        }
    }

    // Phone page runs only while this screen is open.
    DisposableEffect(Unit) {
        val link = PhoneLink { url -> scope.launch(Dispatchers.Main) { preview(url) } }
        scope.launch(Dispatchers.IO) {
            val a = link.start()
            withContext(Dispatchers.Main) { phoneAddress = a }
        }
        onDispose { link.stop() }
    }

    Row(Modifier.fillMaxSize().padding(start = 36.dp, top = 30.dp, end = 36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenTitle("Add-ons")
            Text("Forbidden Vid comes empty. Add-ons you install fill it with rows, details and links.",
                color = FS.TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 30.dp, end = 10.dp, top = 4.dp),
            ) {
                item(key = "add") {
                    FocusCard(onClick = { typing = true }, modifier = Modifier.fillMaxWidth(), container = FS.GreenDim) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("＋", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Black)
                            Gap(w = 14.dp)
                            Column {
                                Text(if (checking != null) "Reading add-on…" else "Add an add-on", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Type its address (usually ends in manifest.json)", fontSize = 14.sp, color = FS.TextDim)
                            }
                        }
                    }
                }
                if (Addons.list.isEmpty()) {
                    item(key = "none") {
                        Text("No add-ons installed.", color = FS.TextDim, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                }
                items(Addons.list, key = { it.manifestUrl }) { a -> AddonCard(a, onClick = { options = a }) }
                item(key = "about") {
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (UpdateState.checking) "Checking…" else "Check for updates", selected = false,
                            onClick = { UpdateState.check(context, scope, manual = true) })
                        Gap(w = 16.dp)
                        Text("Forbidden Vid · build ${Updater.currentBuild(context)}", color = FS.TextDim, fontSize = 15.sp)
                    }
                }
            }
        }
        Gap(w = 30.dp)
        PhonePanel(phoneAddress)
    }

    if (typing) {
        TextInputDialog(
            title = "Add an add-on",
            subtitle = "Enter the add-on's address. Tip: it's easier to send it from your phone with the QR code.",
            placeholder = "https://…/manifest.json",
            confirm = "Continue",
            onConfirm = { typing = false; preview(it) },
            onDismiss = { typing = false },
        )
    }

    confirm?.let { a ->
        val offers = buildList {
            if (a.catalogs.isNotEmpty()) add("${a.catalogs.size} row${if (a.catalogs.size == 1) "" else "s"}")
            a.resources.map { it.name }.distinct().forEach { r ->
                when (r) { "meta" -> add("details"); "stream" -> add("links"); "subtitles" -> add("subtitles") }
            }
        }
        val installed = Addons.list.any { it.manifestUrl == a.manifestUrl || it.id == a.id }
        ChoiceDialog(
            title = (if (installed) "Update " else "Install ") + a.name + (if (a.version.isNotBlank()) " v${a.version}" else "") + "?",
            subtitle = listOf(a.description, "Offers: " + offers.joinToString(", ").ifBlank { "nothing listed" },
                "Types: " + a.types.joinToString(", ") { typeName(it) }, a.manifestUrl).filter { it.isNotBlank() }.joinToString("\n"),
            options = listOf(
                (if (installed) "⬆  Update" else "＋  Install") to {
                    confirm = null
                    scope.launch {
                        try {
                            val x = Addons.install(a.manifestUrl)
                            toast(context, "${x.name} installed")
                        } catch (e: Exception) {
                            toast(context, e.message ?: "Couldn't install")
                        }
                    }
                    Unit
                },
                "Cancel" to { confirm = null },
            ),
            onDismiss = { confirm = null },
        )
    }

    options?.let { a ->
        val i = Addons.list.indexOfFirst { it.manifestUrl == a.manifestUrl }
        val opts = buildList<Pair<String, () -> Unit>> {
            add((if (a.enabled) "Turn off" else "Turn on") to { Addons.setEnabled(a, !a.enabled); options = null })
            if (i > 0) add("▲  Move up" to { Addons.move(a, -1); options = null })
            if (i >= 0 && i < Addons.list.size - 1) add("▼  Move down" to { Addons.move(a, 1); options = null })
            add("⟳  Refresh" to { options = null; preview(a.manifestUrl) })
            add("✕  Remove" to { Addons.remove(a); options = null; toast(context, "${a.name} removed") })
            add("Cancel" to { options = null })
        }
        ChoiceDialog(title = a.name, subtitle = a.manifestUrl, options = opts, onDismiss = { options = null })
    }
}

@Composable
private fun AddonCard(a: Addon, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(FS.Panel2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(a.name.take(1).uppercase(), color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Black)
                NetImage(a.logo, Modifier.fillMaxSize().padding(4.dp))
            }
            Gap(w = 16.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (a.version.isNotBlank()) Text("  v${a.version}", fontSize = 14.sp, color = FS.TextDim)
                }
                if (a.description.isNotBlank()) Text(a.description, fontSize = 14.sp, color = FS.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(a.types.joinToString(", ") { typeName(it) }, "${a.catalogs.size} rows").filter { it.isNotBlank() }.joinToString("  ·  "),
                    fontSize = 13.sp, color = FS.Green, maxLines = 1,
                )
            }
            Gap(w = 12.dp)
            Box(
                Modifier.background(if (a.enabled) FS.Green else FS.Panel2, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(if (a.enabled) "ON" else "OFF", color = if (a.enabled) Color.Black else FS.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PhonePanel(address: String?) {
    Column(
        Modifier.width(340.dp)
            .background(FS.Panel, RoundedCornerShape(16.dp))
            .border(1.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Add from your phone", color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Gap(h = 6.dp)
        Text("Scan with a phone on the same Wi-Fi, paste the add-on address and tap Send.", color = FS.TextDim, fontSize = 14.sp)
        Gap(h = 14.dp)
        if (address != null) {
            val bmp = remember(address) { try { PhoneLink.qr(address) } catch (e: Exception) { null } }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.size(230.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp))
            }
            Gap(h = 10.dp)
            Text(address, color = FS.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        } else {
            Text("Not connected to a home network.", color = FS.TextDim, fontSize = 15.sp)
        }
    }
}
