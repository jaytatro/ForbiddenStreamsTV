package com.forbiddenstreams.tv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.ui.Fmt
import com.forbiddenstreams.tv.ui.GuideView
import com.forbiddenstreams.tv.ui.Group
import com.forbiddenstreams.tv.ui.playChannels
import com.forbiddenstreams.tv.ui.MultiViewScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.ui.FS
import com.forbiddenstreams.tv.ui.ForbiddenTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var view: PlayerView

    /** Bottom info bar (channel, what's on, quality) is visible. */
    private var infoVisible by mutableStateOf(true)
    private var infoJob: Job? = null

    /** See-through TV guide over the video (hold OK on live TV). */
    private var guideVisible by mutableStateOf(false)
    private var okLongFired = false

    /** OK on live TV opens the action tabs (Last Channel, Record, Split Screen). */
    private var actionsVisible by mutableStateOf(false)
    /** ◀ on live TV opens the see-through category / channel panel. */
    private var panelVisible by mutableStateOf(false)
    /** Split screen (up to 4 channels). */
    private var multiVisible by mutableStateOf(false)
    private var recStopAsk by mutableStateOf<com.forbiddenstreams.tv.recording.Recording?>(null)
    private var recTick by mutableStateOf(0)

    /** "⏪ 10s" / "10s ⏩" flash for movies and episodes. */
    private var seekFlash by mutableStateOf<String?>(null)
    private var seekJob: Job? = null
    private var okDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC // remote volume keys = media volume
        if (PlayerHolder.current == null) { PlayerHolder.fullScreen = false; finish(); return }
        PlayerHolder.fullScreen = true
        val live = PlayerHolder.current?.live == true

        view = PlayerView(this).apply {
            player = PlayerHolder.player(this@PlayerActivity)
            // Live TV has no seek bar; our own bottom bar shows the info instead.
            useController = !live
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                infoVisible = v == android.view.View.VISIBLE
            })
        }

        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setKeyTimeIncrement(10_000)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (PlayerHolder.current?.live != true && view.isControllerFullyVisible) {
                    view.hideController()
                    return
                }
                // Live TV keeps playing in the preview window; movies/episodes stop.
                if (PlayerHolder.current?.live != true) PlayerHolder.stop()
                close()
            }
        })

        setContent {
            ForbiddenTheme {
                val item = PlayerHolder.current
                val ch = item?.channelId?.let { Session.channelById(it) }
                val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(15_000) } }
                val epg by produceState(ch?.let { Session.cachedEpg(it) }, ch?.id) { if (ch != null) value = Session.epg(ch) }
                val prog = epg?.firstOrNull { it.isLive(now) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    if (infoVisible && item != null && !guideVisible) {
                        // Bottom bar: where the time and settings buttons are.
                        Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .padding(start = 32.dp, end = 32.dp, bottom = if (item.live) 28.dp else 96.dp)
                                .background(Color(0xD9050805), RoundedCornerShape(12.dp))
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                val n = if (item.live && PlayerHolder.items.size > 1) "${PlayerHolder.index + 1}  " else ""
                                Text(n + item.title, color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                val what = prog?.let { "${it.title}   ${Fmt.range(it.start, it.end)}" } ?: item.subtitle
                                what?.let { Text(it, color = FS.Text, fontSize = 15.sp, maxLines = 1) }
                                if (prog != null) {
                                    val f = ((now - prog.start).toFloat() / (prog.end - prog.start)).coerceIn(0f, 1f)
                                    Box(Modifier.padding(top = 6.dp).width(360.dp).height(4.dp).background(FS.Line, RoundedCornerShape(2.dp))) {
                                        Box(Modifier.fillMaxWidth(f).height(4.dp).background(FS.Green, RoundedCornerShape(2.dp)))
                                    }
                                }
                            }
                            PlayerHolder.audioInfo?.let {
                                Text("🔊 $it", color = if (it.startsWith("No") || it.startsWith("Audio not")) FS.Live else FS.Text,
                                    fontSize = 15.sp, modifier = Modifier.padding(end = 14.dp))
                            }
                            produceState(PlayerHolder.boxVolume(this@PlayerActivity)) { while (true) { delay(1_000); value = PlayerHolder.boxVolume(this@PlayerActivity) } }.value?.let { (txt, bad) ->
                                Text(txt, color = if (bad) FS.Live else FS.TextDim, fontSize = 15.sp,
                                    fontWeight = if (bad) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(end = 14.dp))
                            }
                            QualityBadge(PlayerHolder.quality?.substringBeforeLast(" · ") ?: "Loading…")
                            Spacer(Modifier.width(18.dp))
                            Text(Fmt.time(now), color = FS.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (actionsVisible && item != null) {
                        BackHandler { actionsVisible = false }
                        val first = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (e: Exception) { } }
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp)
                                .background(Color(0xE6050805), RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                        ) {
                            com.forbiddenstreams.tv.ui.Pill(
                                "⏮  Last Channel", selected = false, fontSize = 18.sp,
                                modifier = Modifier.focusRequester(first),
                                onClick = {
                                    actionsVisible = false
                                    if (PlayerHolder.playLast(this@PlayerActivity)) showInfo()
                                    else com.forbiddenstreams.tv.ui.toast(this@PlayerActivity, "No previous channel yet")
                                },
                            )
                            val recording = remember(actionsVisible, recTick) {
                                com.forbiddenstreams.tv.recording.Recordings.activeFor(this@PlayerActivity, item.url)
                            }
                            com.forbiddenstreams.tv.ui.Pill(
                                if (recording != null) "⏹  Recording…" else "⏺  Record", selected = recording != null, fontSize = 18.sp,
                                onClick = {
                                    actionsVisible = false
                                    if (recording != null) recStopAsk = recording else { recordNow(prog); recTick++ }
                                },
                            )
                            com.forbiddenstreams.tv.ui.Pill(
                                "⊞  Multi-View", selected = false, fontSize = 18.sp,
                                onClick = {
                                    actionsVisible = false
                                    infoVisible = false
                                    view.player = null // the split-screen tiles draw the video now
                                    com.forbiddenstreams.tv.ui.MultiView.start(this@PlayerActivity)
                                    multiVisible = true
                                },
                            )
                        }
                    }
                    if (panelVisible) {
                        com.forbiddenstreams.tv.ui.LiveCategoryPanel(
                            currentChannelId = item?.channelId,
                            onPlay = { l, i ->
                                panelVisible = false
                                playChannels(this@PlayerActivity, l, i, fullScreen = false)
                                showInfo()
                            },
                            onClose = { panelVisible = false },
                        )
                    }
                    recStopAsk?.let { r ->
                        com.forbiddenstreams.tv.ui.ChoiceDialog(
                            title = "Recording ${r.title}",
                            subtitle = "Until ${Fmt.time(r.end)} on ${r.channelName}",
                            options = listOf(
                                "⏹  Stop recording (keep it)" to {
                                    com.forbiddenstreams.tv.recording.Recordings.stopNow(this@PlayerActivity, r.id)
                                    com.forbiddenstreams.tv.ui.toast(this@PlayerActivity, "Recording stopped and saved")
                                    recStopAsk = null; recTick++
                                },
                                "✖  Cancel recording (delete it)" to {
                                    com.forbiddenstreams.tv.recording.Recordings.delete(this@PlayerActivity, r.id)
                                    com.forbiddenstreams.tv.ui.toast(this@PlayerActivity, "Recording cancelled")
                                    recStopAsk = null; recTick++
                                },
                                "Keep recording" to { recStopAsk = null },
                            ),
                            onDismiss = { recStopAsk = null },
                        )
                    }
                    if (multiVisible) {
                        MultiViewScreen(onExit = { ch ->
                            multiVisible = false
                            if (ch != null) {
                                playChannels(this@PlayerActivity, listOf(ch), 0, fullScreen = false)
                            }
                            view.player = PlayerHolder.player(this@PlayerActivity)
                            showInfo()
                        })
                    }
                    seekFlash?.let {
                        Text(
                            it, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xB3050805), RoundedCornerShape(12.dp))
                                .padding(horizontal = 26.dp, vertical = 14.dp),
                        )
                    }
                    if (PlayerHolder.reconnecting) {
                        Text(
                            "Reconnecting…", color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                                .background(Color(0xCC000000), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                    PlayerHolder.error?.let {
                        Text(
                            it + if (item?.live == true) "\nPress up/down to try another channel or BACK to return." else "\nPress BACK to return.",
                            color = FS.Text, fontSize = 20.sp,
                            modifier = Modifier.align(Alignment.Center)
                                .background(Color(0xDD0C140D), RoundedCornerShape(12.dp)).padding(24.dp),
                        )
                    }
                    if (guideVisible) {
                        BackHandler { guideVisible = false }
                        val list = remember(PlayerHolder.items) { PlayerHolder.items.mapNotNull { it.channelId?.let { id -> Session.channelById(id) } } }
                        Box(Modifier.fillMaxSize().background(Color(0x66000000)).padding(top = 24.dp)) {
                            if (list.isEmpty()) {
                                Text("No guide for this stream", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
                            } else {
                                GuideView(
                                    group = Group.All,
                                    channels = list,
                                    startId = item?.channelId,
                                    active = true,
                                    onCategories = { },
                                    onToggleFav = { c -> Prefs(this@PlayerActivity).toggleFavorite(c.id) },
                                    overlay = true,
                                    onPlayChannel = { l, i ->
                                        guideVisible = false
                                        playChannels(this@PlayerActivity, l, i, fullScreen = false)
                                        showInfo()
                                    },
                                    onPlayItem = { pi ->
                                        guideVisible = false
                                        PlayerHolder.start(this@PlayerActivity, listOf(pi), 0, fullScreen = false)
                                        showInfo()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (intent.getBooleanExtra("split", false)) {
            // Opened from the rail's Split Screen button.
            view.player = null
            infoVisible = false
            multiVisible = true
        } else {
            showInfo()
        }
    }

    /** Records what's on now (or the next hour if there's no guide info). */
    private fun recordNow(prog: com.forbiddenstreams.tv.data.Program?) {
        val ch = PlayerHolder.current?.channelId?.let { Session.channelById(it) }
        if (ch == null) { com.forbiddenstreams.tv.ui.toast(this, "Can't record this stream"); return }
        val now = System.currentTimeMillis()
        val p = prog ?: com.forbiddenstreams.tv.data.Program(ch.name, "", now, now + 60 * 60_000L, false)
        com.forbiddenstreams.tv.recording.Recordings.schedule(this, ch, p)
        com.forbiddenstreams.tv.ui.toast(this, "Recording ${p.title} until ${Fmt.time(p.end)}")
    }

    private fun skip(ms: Long) {
        val p = PlayerHolder.existingPlayer() ?: return
        val dur = p.duration
        var target = p.currentPosition + ms
        if (target < 0) target = 0
        if (dur > 0 && target > dur - 1000) target = dur - 1000
        p.seekTo(target)
        val pos = target / 1000
        val at = if (pos >= 3600) "%d:%02d:%02d".format(pos / 3600, pos % 3600 / 60, pos % 60) else "%d:%02d".format(pos / 60, pos % 60)
        seekFlash = (if (ms < 0) "⏪ 10s" else "10s ⏩") + "   $at"
        seekJob?.cancel()
        seekJob = lifecycleScope.launch { delay(1200); seekFlash = null }
    }

    private fun close() {
        view.player = null
        PlayerHolder.fullScreen = false
        finish()
    }

    private fun showInfo() {
        infoVisible = true
        infoJob?.cancel()
        infoJob = lifecycleScope.launch {
            delay(5000)
            if (!::view.isInitialized || !view.isControllerFullyVisible) infoVisible = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::view.isInitialized || guideVisible || actionsVisible || multiVisible || panelVisible) return super.dispatchKeyEvent(event)
        val live = PlayerHolder.current?.live == true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_INFO) { if (down) showInfo(); return true }
        if (!live) {
            // Movies/episodes: ◀ = back 10 s, ▶ = forward 10 s (when the control bar isn't open).
            val l = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            val r = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if ((l || r) && !view.isControllerFullyVisible) {
                if (down) skip(if (l) -10_000 else 10_000)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) { PlayerHolder.zap(this, -1); showInfo() }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) { PlayerHolder.zap(this, 1); showInfo() }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down && event.repeatCount == 0) { infoVisible = false; panelVisible = true }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) { okDown = true; okLongFired = false }
                    else if (okDown && !okLongFired) {
                        // Held OK: see-through guide over the video.
                        okLongFired = true
                        infoVisible = false
                        guideVisible = true
                    }
                } else {
                    if (okDown && !okLongFired) { showInfo(); actionsVisible = true }
                    okDown = false
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        com.forbiddenstreams.tv.ui.MultiView.clear()
        PlayerHolder.existingPlayer()?.volume = 1f
        if (::view.isInitialized) view.player = null
        PlayerHolder.fullScreen = false
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        color = Color.Black,
        fontSize = if (small) 11.sp else 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(FS.Green, RoundedCornerShape(6.dp))
            .padding(horizontal = if (small) 6.dp else 12.dp, vertical = if (small) 1.dp else 5.dp),
    )
}
