package com.forbiddenstreams.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.update.AppUpdate
import com.forbiddenstreams.tv.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Update pop-up state, shared by the start-up check and Settings › Check for updates. */
object UpdateState {
    var available by mutableStateOf<AppUpdate?>(null)
    var checking by mutableStateOf(false)
    private var lastCheck = 0L

    fun check(context: Context, scope: CoroutineScope, manual: Boolean) {
        if (checking) return
        if (!manual && System.currentTimeMillis() - lastCheck < 30 * 60_000L) return
        checking = true
        lastCheck = System.currentTimeMillis()
        scope.launch {
            try {
                val u = Updater.check(context)
                if (u != null) available = u
                else if (manual) toast(context, "You have the newest version (build ${Updater.currentBuild(context)})")
            } catch (e: Exception) {
                if (manual) toast(context, e.message ?: "Couldn't check for updates")
            } finally {
                checking = false
            }
        }
    }
}

/**
 * "Update available" pop-up: checks when the app opens (and every 6 hours while it's open).
 * Update now → downloads the new APK with a progress bar → opens Android's installer.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        delay(4_000) // let the channels load first
        while (true) {
            UpdateState.check(context, scope, manual = false)
            delay(6 * 60 * 60_000L)
        }
    }
    val u = UpdateState.available ?: return
    var progress by androidx.compose.runtime.remember(u) { mutableStateOf<Int?>(null) } // null = not downloading
    var apk by androidx.compose.runtime.remember(u) { mutableStateOf<File?>(null) }
    var error by androidx.compose.runtime.remember(u) { mutableStateOf<String?>(null) }

    fun install(f: File) {
        if (!Updater.canInstall(context)) {
            toast(context, "Turn on \"Allow from this source\" for Forbidden Streams, then press BACK and Install")
            Updater.openInstallPermission(context)
        } else {
            Updater.install(context, f)
        }
    }

    val p = progress
    val f = apk
    when {
        f != null -> ChoiceDialog(
            title = "Ready to install build ${u.build}",
            subtitle = "Android will ask to install the update. Your login and settings stay.",
            options = listOf(
                "⬆  Install" to { install(f) },
                "Later" to { UpdateState.available = null },
            ),
            onDismiss = { UpdateState.available = null },
        )
        p != null -> Dialog(onDismissRequest = { }) {
            Column(
                Modifier.width(520.dp).background(FS.Panel, RoundedCornerShape(16.dp))
                    .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp)).padding(28.dp),
            ) {
                Text("Downloading build ${u.build}…", color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).background(FS.GreenDim, RoundedCornerShape(5.dp))) {
                    Box(Modifier.fillMaxWidth((if (p < 0) 0.5f else p / 100f).coerceIn(0.02f, 1f)).height(10.dp)
                        .background(FS.Green, RoundedCornerShape(5.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Text(if (p < 0) "Downloading…" else "$p%", color = FS.Text, fontSize = 16.sp)
            }
        }
        else -> ChoiceDialog(
            title = "Update available",
            subtitle = (error?.let { "$it\n" } ?: "") +
                "Build ${u.build} is ready (you have build ${u.current}). Update now?",
            options = listOf(
                "⬇  Update now" to {
                    error = null
                    progress = 0
                    scope.launch {
                        try {
                            val file = Updater.download(context, u) { progress = it }
                            apk = file
                            progress = null
                            install(file)
                        } catch (e: Exception) {
                            progress = null
                            error = e.message ?: "Download failed"
                        }
                    }
                    Unit
                },
                "Later" to { UpdateState.available = null },
            ),
            onDismiss = { UpdateState.available = null },
        )
    }
}
