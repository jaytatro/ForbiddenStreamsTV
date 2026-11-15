package com.forbiddenstreams.tv.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.io.File

/** Storage the box has: its own (shared folders + the app's folder) and any USB drive / SD card. */
private fun roots(context: Context): List<Pair<String, File>> {
    val out = ArrayList<Pair<String, File>>()
    val appDirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
    @Suppress("DEPRECATION")
    val shared = Environment.getExternalStorageDirectory()
    out += "📺  Box storage" to shared
    out += "📁  Box storage · app folder (always works)" to (appDirs.firstOrNull() ?: context.filesDir)
    appDirs.drop(1).forEachIndexed { i, d ->
        // /storage/XXXX-XXXX/Android/data/… → the drive itself is /storage/XXXX-XXXX
        val drive = d.absolutePath.substringBefore("/Android/").let { File(it) }
        out += "💾  USB / SD card ${i + 1}" to drive
        out += "📁  USB / SD card ${i + 1} · app folder (always works)" to d
    }
    return out
}

/** True when this app can write files in [dir] (tries a small test file). */
private fun canWrite(dir: File): Boolean = try {
    dir.mkdirs()
    val t = File(dir, ".fs_test_${System.nanoTime()}")
    t.writeText("x"); t.delete(); true
} catch (e: Exception) { false }

/** Asks Android for permission to write in shared folders (Download, Movies, USB drives…). */
private fun askStoragePermission(context: Context) {
    var c: Context? = context
    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
    val act = c as? android.app.Activity
    try {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            toast(context, "Turn on \"Allow access to manage all files\", then come back")
        } else if (Build.VERSION.SDK_INT >= 23) {
            act?.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        }
    } catch (e: Exception) {
        toast(context, "This box can't give storage access — use an \"app folder\" instead")
    }
}

/**
 * Settings › Other › Recording › Recordings folder (Nov 15): browse the box's storage.
 * OK on a folder opens it, "⬆ Up" goes back; **Choose folder** picks the folder you're in,
 * **New folder** makes one here (and opens it).
 */
@Composable
fun FolderPickerDialog(start: String, onChoose: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val rootList = remember { roots(context) }
    var dir by remember { mutableStateOf<File?>(File(start).takeIf { it.isDirectory }) }
    var tick by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    val firstReq = remember { FocusRequester() }
    val entries: List<Pair<String, () -> Unit>> = remember(dir, tick) {
        val d = dir
        if (d == null) rootList.map { (name, f) -> name to { dir = f } }
        else {
            val up: Pair<String, () -> Unit> = "⬆  Up" to {
                dir = if (rootList.any { it.second.absolutePath == d.absolutePath }) null else d.parentFile
            }
            val subs = (d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
                .sortedBy { it.name.lowercase() }
                .map { f -> "📁  ${f.name}" to { dir = f } }
            listOf(up) + subs
        }
    }
    LaunchedEffect(dir) { repeat(8) { delay(60); try { firstReq.requestFocus(); return@LaunchedEffect } catch (e: Exception) { } } }

    Dialog(onDismissRequest = onClose) {
        BackHandler { val d = dir; if (d == null) onClose() else entries.first().second() }
        Column(
            Modifier.width(720.dp).background(FS.Panel, RoundedCornerShape(18.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(18.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Recordings folder", color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(dir?.absolutePath ?: "Pick a storage", color = FS.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            // The two buttons (once you're inside a folder).
            if (dir != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("✔  Choose folder", selected = true, fontSize = 17.sp, onClick = {
                    val d = dir ?: return@Pill
                    if (canWrite(d)) { onChoose(d) } else {
                        toast(context, "Can't save recordings here yet")
                        askStoragePermission(context)
                    }
                })
                Pill("➕  New folder", selected = false, outlined = true, fontSize = 17.sp, onClick = { newFolder = true })
                Spacer(Modifier.weight(1f))
                Pill("Cancel", selected = false, outlined = true, fontSize = 15.sp, onClick = onClose)
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(entries, key = { i, e -> "$i:${e.first}" }) { i, (name, go) ->
                    ListPill(selected = false, onClick = go,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstReq) else Modifier)) {
                        Text(name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
                if (dir != null && entries.size == 1) item {
                    Text("No folders here. Use ➕ New folder, or ✔ Choose folder to save here.", color = FS.TextDim, fontSize = 15.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("OK opens a folder · ⬆ Up goes back · Choose folder saves recordings in the folder shown above",
                color = FS.TextDim, fontSize = 13.sp)
        }
    }
    if (newFolder) TextInputDialog("New folder", "Folder name, e.g. Recordings", button = "Make folder", onDone = { name ->
        val d = dir
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        if (d == null || clean.isEmpty()) { toast(context, "Type a name"); return@TextInputDialog }
        val f = File(d, clean)
        if (f.isDirectory || f.mkdirs()) { newFolder = false; dir = f; tick++; toast(context, "Folder \"$clean\" made") }
        else { toast(context, "Can't make a folder here yet"); askStoragePermission(context) }
    }, onCancel = { newFolder = false })
}
