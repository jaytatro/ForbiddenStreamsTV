package com.forbiddenstreams.tv.ui

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.recording.Recording
import com.forbiddenstreams.tv.recording.Recordings
import kotlinx.coroutines.delay
import java.io.File

/** Where you stopped watching a recording (kept apart from Keep watching). */
object DvrProgress {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dvr_progress", Context.MODE_PRIVATE)
    fun save(c: Context, id: Long, pos: Long, dur: Long) {
        sp(c).edit().putLong("p$id", pos).putLong("d$id", dur).apply()
    }
    /** (position, duration) or null when never started. */
    fun get(c: Context, id: Long): Pair<Long, Long>? {
        val p = sp(c).getLong("p$id", -1L)
        return if (p < 0) null else p to sp(c).getLong("d$id", 0L)
    }
    fun clear(c: Context, id: Long) { sp(c).edit().remove("p$id").remove("d$id").apply() }
}

/** Part-way through (started, and not within the last 2 minutes / 97%). */
private fun partWatched(p: Pair<Long, Long>?): Boolean {
    if (p == null || p.first < 15_000) return false
    val (pos, dur) = p
    return dur <= 0 || (pos < dur - 120_000 && pos < dur * 0.97)
}

private fun playRecording(context: Context, r: Recording, fromMs: Long?) {
    val f = File(r.file)
    if (!f.exists() || f.length() == 0L) { toast(context, "Nothing recorded yet"); return }
    Playback.one(context, PlayItem(
        r.title, android.net.Uri.fromFile(f).toString(), live = false,
        subtitle = "${r.channelName} · ${Fmt.day(r.start)} ${Fmt.range(r.start, r.end)}",
        resumeKey = "r:${r.id}", kind = "recording", startMs = fromMs,
    ))
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

/**
 * DVR tab (Nov 15 2026): everything recorded, recording now and scheduled.
 *  - Recording now → OK: ■ Stop · ▶ Play · 🗑 Delete
 *  - Recorded → OK: ▶ Play (⏵ Resume + ↺ Play from the start when part-watched) · 🗑 Delete
 *  - Scheduled → OK: ✕ Cancel recording
 */
@Composable
fun DvrTab() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Refresh every 5 s so "recording now" and its size keep moving.
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    val all = remember(tick) { Recordings.all(context) }
    val now = remember(tick) { System.currentTimeMillis() }
    val recordingNow = all.filter { it.status == "recording" }
    val scheduled = all.filter { it.status == "scheduled" }.sortedBy { it.start }
    val recorded = all.filter { it.status == "done" || it.status == "failed" }.sortedByDescending { it.start }
    val used = remember(tick / 6) { all.sumOf { File(it.file).let { f -> if (f.exists()) f.length() else 0L } } }
    val free = remember(tick / 6) {
        try { (context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir).usableSpace } catch (e: Exception) { 0L }
    }
    var pick by remember { mutableStateOf<Recording?>(null) }
    val firstReq = remember { FocusRequester() }
    val goIn: () -> Boolean = { try { firstReq.requestFocus(); true } catch (e: Exception) { false } }
    DisposableEffect(Unit) {
        TabDown.handler = goIn
        onDispose { if (TabDown.handler === goIn) TabDown.handler = null }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07120A), Color.Black)))) {
        Column(Modifier.fillMaxSize().padding(top = 84.dp, start = 36.dp, end = 36.dp)) {
            // Header: DVR · counts · storage
            Row(verticalAlignment = Alignment.Bottom) {
                Text("DVR", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Spacer(Modifier.width(18.dp))
                if (recordingNow.isNotEmpty()) {
                    RecDot()
                    Spacer(Modifier.width(6.dp))
                    Text("${recordingNow.size} recording now", color = Color(0xFFFF5A5A), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.width(16.dp))
                }
                Text("${recorded.count { it.status == "done" }} recorded  ·  ${scheduled.size} scheduled", color = FS.TextDim, fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                Text("Used ${sizeText(used)}  ·  ${sizeText(free)} free", color = FS.Green, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (all.isEmpty()) {
                MessageBox("No recordings yet",
                    "In Live TV or the TV Guide pick a show and choose ⏺ Record — or press OK while watching a channel and choose Record.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 30.dp, start = 2.dp, end = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    var first = true
                    fun section(title: String, list: List<Recording>) {
                        if (list.isEmpty()) return
                        item(span = { GridItemSpan(3) }, key = "h_$title") {
                            Text(title, color = FS.GreenMid, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                        }
                        list.forEachIndexed { idx, r ->
                            val isFirst = first && idx == 0
                            item(key = "r_${r.id}") {
                                DvrCard(r, now, Modifier.then(if (isFirst) Modifier.focusRequester(firstReq) else Modifier)) { pick = r }
                            }
                        }
                        first = false
                    }
                    section("RECORDING NOW", recordingNow)
                    section("RECORDED", recorded)
                    section("SCHEDULED", scheduled)
                }
            }
        }
    }

    pick?.let { r0 ->
        val r = Recordings.get(context, r0.id) ?: run { pick = null; return@let }
        val prog = DvrProgress.get(context, r.id)
        val opts = ArrayList<Pair<String, () -> Unit>>()
        when (r.status) {
            "recording" -> {
                opts += "■  Stop" to { Recordings.stopNow(context, r.id); toast(context, "Stopped · ${r.title} is saved"); pick = null; tick++ }
                opts += "▶  Play" to { pick = null; playRecording(context, r, null) }
                opts += "🗑  Delete" to { Recordings.delete(context, r.id); DvrProgress.clear(context, r.id); toast(context, "Deleted"); pick = null; tick++ }
            }
            "scheduled" -> {
                opts += "✕  Cancel recording" to { Recordings.delete(context, r.id); toast(context, "Cancelled"); pick = null; tick++ }
            }
            else -> {
                if (r.status == "done") {
                    if (partWatched(prog)) {
                        opts += "⏵  Resume from ${mmss(prog!!.first)}" to { pick = null; playRecording(context, r, prog.first) }
                        opts += "▶  Play from the start" to { pick = null; DvrProgress.clear(context, r.id); playRecording(context, r, null) }
                    } else {
                        opts += "▶  Play" to { pick = null; playRecording(context, r, null) }
                    }
                }
                opts += "🗑  Delete" to { Recordings.delete(context, r.id); DvrProgress.clear(context, r.id); toast(context, "Deleted"); pick = null; tick++ }
            }
        }
        opts += "Close" to { pick = null }
        ChoiceDialog(
            title = r.title,
            subtitle = "${r.channelName} · ${Fmt.day(r.start)} ${Fmt.range(r.start, r.end)}" + when (r.status) {
                "recording" -> "\n● Recording now"
                "scheduled" -> "\nWill record at ${Fmt.time(r.start)}"
                "failed" -> "\nThis recording failed (the channel didn't send anything)"
                else -> ""
            },
            options = opts,
            onDismiss = { pick = null },
        )
    }
}

@Composable
private fun RecDot() {
    val pulse = rememberInfiniteTransition(label = "rec").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.padding(bottom = 8.dp).size(10.dp).alpha(pulse.value).background(Color(0xFFFF3B3B), RoundedCornerShape(50)))
}

@Composable
private fun DvrCard(r: Recording, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val rec = r.status == "recording"
    val size = remember(now) { File(r.file).let { if (it.exists()) it.length() else 0L } }
    val prog = remember(now) { DvrProgress.get(context, r.id) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(138.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xB8050B07), contentColor = FS.Text,
            focusedContainerColor = Color(0xFF12301B), focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(if (rec) 2.dp else 1.dp, if (rec) Color(0xFFFF3B3B) else Color(0x337CFF3A)), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, if (rec) Color(0xFFFF3B3B) else FS.Green), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (tag, bg, fg) = when (r.status) {
                    "recording" -> Triple("● REC", Color(0xFFD62828), Color.White)
                    "scheduled" -> Triple("⏰ ${Fmt.time(r.start)}", Color(0x337CFF3A), FS.Green)
                    "failed" -> Triple("FAILED", Color(0xFF3A2A2A), Color(0xFFFF8080))
                    else -> Triple(if (partWatched(prog)) "⏵ PART-WATCHED" else "✓ RECORDED", Color(0x227CFF3A), FS.Green)
                }
                Text(tag, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                    modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.weight(1f))
                if (size > 0) Text(sizeText(size), color = FS.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            Text(r.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.channelName, color = FS.Green, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.day(r.start)}  ${Fmt.range(r.start, r.end)}", color = FS.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // Bar: how far the recording has got (red) or how much you've watched (green).
            val f = when {
                rec -> ((now - r.start).toFloat() / (r.end - r.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                prog != null && prog.second > 0 -> (prog.first.toFloat() / prog.second).coerceIn(0f, 1f)
                else -> -1f
            }
            if (f >= 0f) Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x22FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(if (rec) Color(0xFFFF3B3B) else FS.Green, RoundedCornerShape(2.dp)))
            }
        }
    }
}
