package com.forbiddenstreams.tv.player

import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
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
import com.forbiddenstreams.tv.ui.GText
import com.forbiddenstreams.tv.ui.MultiViewScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
    /** Bumped on every key press: the playback banner (an earlier show playing) comes back for a few seconds. */
    private var keyPing by androidx.compose.runtime.mutableIntStateOf(0)
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
    private var favTick by mutableStateOf(0)

    /** "⏪ 10s" / "10s ⏩" flash for movies and episodes. */
    private var seekFlash by mutableStateOf<String?>(null)
    private var seekJob: Job? = null
    private var okDown = false
    /** Movie/episode settings menu: null, "main", "audio" or "speed". */
    private var vodMenu by mutableStateOf<String?>(null)

    /** Movie player buttons (play, seek, settings…): green background with a black icon when highlighted. */
    private fun greenFocus(root: android.view.View) {
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) greenFocus(root.getChildAt(i))
            return
        }
        if (root !is android.widget.ImageView || !root.isFocusable) return
        val d = resources.displayMetrics.density
        fun bg(color: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        root.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), bg(0xFF3FBF1F.toInt()))
            addState(intArrayOf(android.R.attr.state_focused), bg(0xFF7CFF3A.toInt()))
            addState(intArrayOf(), bg(0x00000000))
        }
        root.imageTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
        )
        root.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
    }
    private var lastKeyAt = 0L
    private var lastLeftAt = 0L

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
            useController = !live && PlayerHolder.current?.restartOf == null
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                infoVisible = v == android.view.View.VISIBLE
            })
        }

        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setKeyTimeIncrement(10_000)
            // Green seek bar.
            setPlayedColor(0xFF7CFF3A.toInt())
            setScrubberColor(0xFF7CFF3A.toInt())
            setBufferedColor(0x667CFF3A)
            setUnplayedColor(0x33FFFFFF)
        }
        greenFocus(view)
        // Our own settings menu (with Closed captions) instead of the built-in one.
        view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { vodMenu = "main" }

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
                    // Nov 13: an earlier show playing back — show which show, when it aired, and what's next.
                    if (item?.playbackOf != null && !guideVisible) {
                        var bannerOn by remember(item) { mutableStateOf(true) }
                        LaunchedEffect(item, keyPing) { bannerOn = true; delay(6_000); bannerOn = false }
                        if (bannerOn) {
                            val pch = Session.channelById(item.playbackOf)
                            val pEpg = pch?.let { Session.cachedEpg(it) }
                            val nextShow = pEpg?.filter { it.start >= item.progEnd - 60_000 && it.start > item.progStart }?.minByOrNull { it.start }
                            Column(
                                Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 28.dp, end = 32.dp)
                                    .background(Color(0xE6040604), RoundedCornerShape(16.dp))
                                    .border(1.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 22.dp, vertical = 14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⟲ PLAYBACK", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                                        modifier = Modifier.background(FS.Green, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(listOfNotNull(pch?.num?.toString(), item.subtitle).joinToString("  ·  "), color = FS.Green, fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                                }
                                Text(item.title, color = FS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                    modifier = Modifier.padding(top = 6.dp))
                                Text("Aired ${Fmt.day(item.progStart)}  ${Fmt.range(item.progStart, item.progEnd)}" +
                                    (nextShow?.let { "     Next: ${it.title}" } ?: "     Next: ${pch?.name ?: "live"}"),
                                    color = FS.TextDim, fontSize = 15.sp, maxLines = 1)
                                Text("Keeps playing the next show until you press BACK", color = FS.TextDim, fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    if ((infoVisible || actionsVisible) && item != null && !guideVisible) {
                      // Bottom bar: channel info, and under it (after OK) Last Channel · Record · Multi-View.
                      Column(
                          Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                              .padding(start = 32.dp, end = 32.dp, bottom = if (item.live || item.restartOf != null) 28.dp else 96.dp)
                              .background(Color(0xE6040604), RoundedCornerShape(16.dp))
                              .border(1.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                              .padding(horizontal = 24.dp, vertical = 16.dp),
                      ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    if (item.live && ch != null) {
                                        Text("${ch.num}", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(item.title, color = FS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                                val next = epg?.firstOrNull { prog != null && it.start >= prog.end }
                                val what = prog?.let { "${it.title}   ${Fmt.range(it.start, it.end)}" + (next?.let { n -> "     Next: ${n.title}" } ?: "") } ?: item.subtitle
                                what?.let { Text(it, color = FS.Text, fontSize = 15.sp, maxLines = 1) }
                                if (prog != null) {
                                    val f = ((now - prog.start).toFloat() / (prog.end - prog.start)).coerceIn(0f, 1f)
                                    Box(Modifier.padding(top = 6.dp).width(360.dp).height(4.dp).background(FS.Line, RoundedCornerShape(2.dp))) {
                                        Box(Modifier.fillMaxWidth(f).height(4.dp).background(FS.Green, RoundedCornerShape(2.dp)))
                                    }
                                }
                            }
                            PlayerHolder.audioInfo?.let {
                                GText("🔊 $it", color = if (it.startsWith("No") || it.startsWith("Audio not")) FS.Live else FS.Text,
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
                        // Start over of a live channel (Nov 10): time bar, and after OK ⏯ · ● LIVE · ⚙ Settings, all in this box.
                        if (item.restartOf != null) RestartBar(actionsVisible)
                        if (actionsVisible && item.restartOf == null) {
                            BackHandler { actionsVisible = false }
                            val first = remember { androidx.compose.ui.focus.FocusRequester() }
                            val last = remember { androidx.compose.ui.focus.FocusRequester() }
                            LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (e: Exception) { } }
                            // Nothing picked for 5 seconds: the buttons close by themselves.
                            LaunchedEffect(Unit) {
                                lastKeyAt = android.os.SystemClock.uptimeMillis()
                                while (true) {
                                    delay(500)
                                    if (android.os.SystemClock.uptimeMillis() - lastKeyAt > 5_000) { actionsVisible = false; break }
                                }
                            }
                            // ▼ on the buttons: your last 5 channels (OK plays one; ▲ back to the buttons).
                            val recent = remember(actionsVisible, item.channelId) {
                                com.forbiddenstreams.tv.data.Prefs(this@PlayerActivity).history
                                    .filter { it != item.channelId }.mapNotNull { Session.channelById(it) }.take(5)
                            }
                            var recentOpen by remember { mutableStateOf(false) }
                            val recentReq = remember { androidx.compose.ui.focus.FocusRequester() }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp)
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                            ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && !recentOpen) {
                                            if (recent.isEmpty()) com.forbiddenstreams.tv.ui.toast(this@PlayerActivity, "No previous channels yet")
                                            else {
                                                recentOpen = true
                                                lifecycleScope.launch { repeat(6) { delay(50); try { recentReq.requestFocus(); return@launch } catch (e: Exception) { } } }
                                            }
                                            true
                                        } else false
                                    },
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                            // Nov 10: no Last Channel button — ▼ shows the last 5 channels instead (below).
                            // ⟲ Start over: the show on now, from its beginning (catch-up channels only).
                            val startUrl = remember(prog, ch) {
                                if (ch != null && prog != null && ch.hasArchive) Session.source?.catchupUrl(ch, prog) else null
                            }
                            if (startUrl != null && prog != null) {
                                com.forbiddenstreams.tv.ui.Pill(
                                    "⟲  Start over", selected = false, fontSize = 18.sp,
                                    // Round robin: ◀ on the first button goes to CC (the last one).
                                    modifier = Modifier.focusRequester(first).focusProperties { left = last },
                                    onClick = {
                                        actionsVisible = false
                                        // A new player screen for the replay; the live channel is remembered for Last Channel.
                                        PlayerHolder.start(this@PlayerActivity, listOf(PlayItem(prog.title, startUrl, live = false, subtitle = "${item.title} · from the start", restartOf = item.channelId ?: "")), 0)
                                        finish()
                                    },
                                )
                            }
                            val recording = remember(actionsVisible, recTick) {

                                com.forbiddenstreams.tv.recording.Recordings.activeFor(this@PlayerActivity, item.url)
                            }
                            com.forbiddenstreams.tv.ui.Pill(
                                if (recording != null) "⏹  Recording…" else "⏺  Record", selected = recording != null, fontSize = 18.sp,
                                modifier = if (startUrl == null || prog == null) Modifier.focusRequester(first).focusProperties { left = last } else Modifier,
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
                            if (ch != null) {
                                val fav = remember(favTick, ch.id) { ch.id in com.forbiddenstreams.tv.data.Prefs(this@PlayerActivity).favorites }
                                com.forbiddenstreams.tv.ui.Pill(
                                    if (fav) "★  Favorite" else "☆  Favorite", selected = fav, fontSize = 18.sp,
                                    onClick = {
                                        val added = com.forbiddenstreams.tv.data.Prefs(this@PlayerActivity).toggleFavorite(ch.id)
                                        favTick++
                                        com.forbiddenstreams.tv.ui.toast(this@PlayerActivity, if (added) "Added ${ch.name} to favorites" else "Removed from favorites")
                                    },
                                )
                            }
                            // CC: closed captions on/off (boxed like the symbol on TV remotes). ▶ on it wraps to Last Channel.
                            CcButton(
                                on = PlayerHolder.captionsOn,
                                modifier = Modifier.focusRequester(last).focusProperties { right = first },
                                onClick = {
                                    val turnOn = !PlayerHolder.captionsOn
                                    val has = PlayerHolder.setCaptions(this@PlayerActivity, turnOn)
                                    com.forbiddenstreams.tv.ui.toast(
                                        this@PlayerActivity,
                                        if (!turnOn) "Captions off" else if (has) "Captions on" else "Captions on — this channel doesn't send any right now",
                                    )
                                },
                            )
                            }
                            if (!recentOpen) {
                                GText("▼  Recent channels", color = FS.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                            } else {
                                Text("RECENT CHANNELS", color = FS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                                Row(
                                    Modifier.fillMaxWidth().onPreviewKeyEvent { ev ->
                                        if (ev.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                            ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                            recentOpen = false
                                            try { first.requestFocus() } catch (e: Exception) { }
                                            true
                                        } else false
                                    },
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                                ) {
                                    recent.forEachIndexed { k, rc ->
                                        val on = Session.cachedEpg(rc)?.firstOrNull { it.isLive(now) }?.title
                                        com.forbiddenstreams.tv.ui.FocusCard(
                                            onClick = {
                                                actionsVisible = false
                                                playChannels(this@PlayerActivity, recent, k, fullScreen = false)
                                                showInfo()
                                            },
                                            modifier = Modifier.weight(1f).height(86.dp).then(if (k == 0) Modifier.focusRequester(recentReq) else Modifier),
                                            focusedScale = 1.04f,
                                        ) {
                                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.width(56.dp).height(40.dp), contentAlignment = Alignment.Center) {
                                                    com.forbiddenstreams.tv.ui.NetImage(rc.logo, Modifier.fillMaxSize())
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Column {
                                                    Text("${rc.num} · ${rc.name}", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                                    on?.let { Text(it, color = FS.TextDim, fontSize = 12.sp, maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                      }
                    }
                    if (infoVisible && item?.live == true && !guideVisible && !panelVisible && PlayerHolder.items.size > 1) {
                        // What's on the channels above and below, before you switch.
                        val list = PlayerHolder.items
                        val i = PlayerHolder.index
                        Column(
                            Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 32.dp).width(320.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            for ((arrow, j) in listOf("▲" to (i - 1 + list.size) % list.size, "▼" to (i + 1) % list.size)) {
                                val pi = list[j]
                                val pch = pi.channelId?.let { Session.channelById(it) }
                                val on = pch?.let { Session.cachedEpg(it) }?.firstOrNull { it.isLive(now) }?.title
                                Row(
                                    Modifier.fillMaxWidth().background(Color(0xD1040604), RoundedCornerShape(10.dp))
                                        .border(1.dp, Color(0xFF1A2E1C), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("$arrow ${pch?.num ?: ""}", color = FS.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(10.dp))
                                    Text(pi.title + (on?.let { " · $it" } ?: ""), color = FS.Text, fontSize = 14.sp, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
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
                            // ◀ on the categories = back to the side bar (channel keeps playing in the preview).
                            onSideBar = { panelVisible = false; PlayerHolder.requestSideBar(); close() },
                        )
                    }
                    vodMenu?.let { m -> VodSettings(m) }
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

    /** Settings menu for movies/episodes: Closed captions, Audio track, Speed. */
    @androidx.compose.runtime.Composable
    private fun VodSettings(m: String) {
        val p = PlayerHolder.existingPlayer() ?: run { vodMenu = null; return }
        val close = { vodMenu = null }
        when (m) {
            "audio" -> {
                val opts = ArrayList<Pair<String, () -> Unit>>()
                p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.forEach { g ->
                    for (i in 0 until g.length) {
                        val f = g.getTrackFormat(i)
                        val name = listOfNotNull(
                            f.label, f.language?.let { java.util.Locale(it).displayLanguage },
                            when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
                        ).joinToString(" · ").ifEmpty { "Track ${i + 1}" }
                        opts += ((if (g.isTrackSelected(i)) "✓  " else "     ") + name) to {
                            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build()
                            close()
                        }
                    }
                }
                if (opts.isEmpty()) opts += "No other audio tracks" to close
                com.forbiddenstreams.tv.ui.ChoiceDialog("🔊  Audio track", null, opts + ("Back" to { vodMenu = "main" }), onDismiss = close)
            }
            "speed" -> {
                val cur = p.playbackParameters.speed
                val opts = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { sp ->
                    ((if (kotlin.math.abs(sp - cur) < 0.01f) "✓  " else "     ") + (if (sp == 1f) "Normal" else "${sp}x")) to { p.setPlaybackSpeed(sp); close() }
                }
                com.forbiddenstreams.tv.ui.ChoiceDialog("⏩  Speed", null, opts + ("Back" to { vodMenu = "main" }), onDismiss = close)
            }
            else -> {
                val cc = PlayerHolder.captionsOn
                val speed = p.playbackParameters.speed
                com.forbiddenstreams.tv.ui.ChoiceDialog(
                    title = "Settings",
                    subtitle = null,
                    options = listOf(
                        "CC  Closed captions: ${if (cc) "On" else "Off"}" to {
                            val has = PlayerHolder.setCaptions(this, !cc)
                            com.forbiddenstreams.tv.ui.toast(this, if (cc) "Captions off" else if (has) "Captions on" else "Captions on — this video has none")
                            close()
                        },
                        "🔊  Audio track" to { vodMenu = "audio" },
                        "⏩  Speed: ${if (speed == 1f) "Normal" else "${speed}x"}" to { vodMenu = "speed" },
                        "Close" to close,
                    ),
                    onDismiss = close,
                )
            }
        }
    }

    /** Records what's on now (or the next hour if there's no guide info). */
    /** Time bar (and, after OK, the ⏯ · ● LIVE · ⚙ Settings buttons) for "Start over" of a live channel. */
    @androidx.compose.runtime.Composable
    private fun RestartBar(buttons: Boolean) {
        val p = PlayerHolder.existingPlayer()
        var pos by remember { mutableStateOf(0L) }
        var dur by remember { mutableStateOf(0L) }
        var playing by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            while (true) {
                val pl = PlayerHolder.existingPlayer()
                if (pl != null) { pos = pl.currentPosition.coerceAtLeast(0); dur = pl.duration.takeIf { it > 0 } ?: 0L; playing = pl.isPlaying }
                delay(500)
            }
        }
        fun t(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t(pos), color = FS.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.weight(1f).height(6.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(f).height(6.dp).background(FS.Green, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (dur > 0) t(dur) else "--:--", color = FS.TextDim, fontSize = 14.sp)
        }
        if (!buttons) {
            Text("◀ ▶ 10 s  ·  OK for Pause, LIVE and Settings", color = FS.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            return
        }
        BackHandler { actionsVisible = false }
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (e: Exception) { } }
        LaunchedEffect(Unit) {
            lastKeyAt = android.os.SystemClock.uptimeMillis()
            while (true) { delay(500); if (android.os.SystemClock.uptimeMillis() - lastKeyAt > 5_000) { actionsVisible = false; break } }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            com.forbiddenstreams.tv.ui.Pill(if (playing) "⏸  Pause" else "▶  Play", selected = false, fontSize = 18.sp,
                modifier = Modifier.focusRequester(first),
                onClick = { p?.let { if (it.isPlaying) it.pause() else it.play() }; playing = !playing })
            com.forbiddenstreams.tv.ui.Pill("●  LIVE", selected = false, fontSize = 18.sp, onClick = {
                actionsVisible = false
                // Back to the live channel this was started from.
                if (!PlayerHolder.playLast(this@PlayerActivity)) {
                    val ch = PlayerHolder.current?.restartOf?.let { Session.channelById(it) }
                    if (ch != null) playChannels(this@PlayerActivity, listOf(ch), 0, fullScreen = false)
                }
                showInfo()
            })
            com.forbiddenstreams.tv.ui.Pill("⚙  Settings", selected = false, fontSize = 18.sp, onClick = { actionsVisible = false; vodMenu = "main" })
        }
    }

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
        lastKeyAt = android.os.SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) keyPing++
        if (!::view.isInitialized || guideVisible || actionsVisible || multiVisible || panelVisible) return super.dispatchKeyEvent(event)
        val live = PlayerHolder.current?.live == true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_INFO) { if (down) showInfo(); return true }
        if (PlayerHolder.current?.restartOf != null) {
            // Start over: ◀ ▶ = 10 s back/ahead, OK = the buttons (⏯ · LIVE · Settings), ▲ ▼ = show the bar.
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) { skip(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000); showInfo() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) showInfo(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!down) { showInfo(); actionsVisible = true }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (down) PlayerHolder.existingPlayer()?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (!live) {
            // Movies/episodes. Bar hidden: ◀ ▶ = 10 s, OK = pause/play (and show the bar), ▲ ▼ = show the bar.
            // Bar showing: keys go straight to the player's buttons (Compose around the video
            // could swallow OK before, so pressing OK on a button did nothing).
            val k = event.keyCode
            val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
            val l = k == KeyEvent.KEYCODE_DPAD_LEFT
            val r = k == KeyEvent.KEYCODE_DPAD_RIGHT
            val ud = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (down) PlayerHolder.existingPlayer()?.let { p -> if (k == KeyEvent.KEYCODE_MEDIA_PAUSE || (k != KeyEvent.KEYCODE_MEDIA_PLAY && p.isPlaying)) p.pause() else p.play() }
                return true
            }
            if (!view.isControllerFullyVisible) {
                when {
                    l || r -> { if (down) skip(if (l) -10_000 else 10_000); return true }
                    ok -> {
                        if (down && event.repeatCount == 0) {
                            PlayerHolder.existingPlayer()?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                            view.showController()
                        }
                        return true
                    }
                    ud -> { if (down) view.showController(); return true }
                }
                return super.dispatchKeyEvent(event)
            }
            if (ok || l || r || ud) {
                if (view.findFocus() == null) view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
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
                    // Holding OK no longer opens the guide: OK (short or long) shows the buttons.
                    if (event.repeatCount == 0) { okDown = true; okLongFired = false }
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
private fun CcButton(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = FS.Text,
            focusedContainerColor = FS.Green, focusedContentColor = Color.Black,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = androidx.tv.material3.LocalContentColor.current
            Text(
                "CC", color = if (on) Color.Black else c, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .then(if (on) Modifier.background(FS.Green, RoundedCornerShape(3.dp)) else Modifier)
                    .border(2.dp, if (on) FS.Green else c, RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Text(if (on) "  On" else "  Off", fontSize = 16.sp)
        }
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
