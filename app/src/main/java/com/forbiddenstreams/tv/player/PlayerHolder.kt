package com.forbiddenstreams.tv.player

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.forbiddenstreams.tv.data.Http
import java.util.Locale
import kotlinx.coroutines.launch

data class PlayItem(
    val title: String,
    val url: String,
    val live: Boolean,
    val subtitle: String? = null,
    val channelId: String? = null,
    /** Movies/episodes: key for Recently Watched ("m:<id>" / "e:<id>"), its kind and artwork. */
    val resumeKey: String? = null,
    val kind: String? = null,
    val image: String? = null,
    /** Start here instead of the beginning (resume). */
    val startMs: Long? = null,
)

/**
 * One player for the whole app, so a live channel keeps playing in the
 * small top-left window after BACK is pressed in full screen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlayerHolder {
    private var exo: ExoPlayer? = null
    private var appContext: Context? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var progressJob: kotlinx.coroutines.Job? = null

    var items: List<PlayItem> = emptyList()
        private set
    var index by mutableIntStateOf(0)
        private set

    /** The item playing (full screen or mini window), or null when stopped. */
    var current by mutableStateOf<PlayItem?>(null)
        private set

    /** e.g. "1080p · 60 fps · H.264 · 6.4 Mbps"; null until the video starts. */
    var quality by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Preview hidden (and muted) while the top-menu cursor is on VOD or SPORTS. */
    private var hiddenState by mutableStateOf(false)
    var previewHidden: Boolean
        get() = hiddenState
        set(v) { hiddenState = v; exo?.volume = if (v) 0f else 1f }

    /** True while the full-screen player owns the video; the mini window waits until it closes. */
    var fullScreen by mutableStateOf(false)

    var onChannelWatched: ((String) -> Unit)? = null

    val isMiniActive: Boolean get() = current?.live == true

    /**
     * Live TV: the plain player (the setup that always had sound on channels).
     * Movies/episodes ([vod] = true): adds the FFmpeg decoder so Dolby/DTS audio in
     * 4K and Dolby Vision files plays even when the box can't decode it itself.
     */
    fun createPlayer(context: Context, vod: Boolean = false): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val app = context.applicationContext
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8))
        if (!vod) return ExoPlayer.Builder(app).setMediaSourceFactory(sources).build()
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(app, renderers).setMediaSourceFactory(sources).build()
    }

    /**
     * Extra Multi-View screens: same sound setup as live TV, plus a software-decoder fallback
     * (boxes run out of hardware video decoders with 3–4 screens) and smaller buffers to save memory.
     */
    fun createTilePlayer(context: Context): ExoPlayer {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 15_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(app, androidx.media3.exoplayer.DefaultRenderersFactory(app).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http))
                    .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)),
            )
            .setLoadControl(load)
            .build()
        // Small screens don't need full-HD variants when the stream offers a choice.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
        return p
    }

    /** Bumped whenever a new player is built, so preview windows re-attach. */
    var playerVersion by mutableIntStateOf(0)
        private set

    /** Whether the shared player is the movie/episode (FFmpeg) kind. */
    private var exoVod = false

    /** Swaps the shared player when going between live TV and movies/episodes. */
    private fun ensureKind(context: Context, vod: Boolean) {
        val p = exo ?: run { nextVod = vod; return }
        if (exoVod == vod) return
        p.release()
        exo = null
        nextVod = vod
        player(context)
    }
    private var nextVod = false

    fun player(context: Context): ExoPlayer {
        exo?.let { return it }
        playerVersion++
        val app = context.applicationContext
        appContext = app
        exoVod = nextVod
        val p = createPlayer(app, exoVod)
        if (!ccLoaded) {
            ccLoaded = true
            captionsOn = app.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE).getBoolean("cc_on", false)
        }
        applyCaptions(p)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // Streams drop now and then: reconnect by itself before showing an error.
                if (reconnect(p, e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)) return
                reconnecting = false
                error = "This stream won't play right now (${e.errorCodeName})."
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { error = null; reconnecting = false; retries = 0; bufferingSince = 0L }
                if (state == Player.STATE_BUFFERING) { if (bufferingSince == 0L) bufferingSince = android.os.SystemClock.elapsedRealtime() }
                else bufferingSince = 0L
                if (state == Player.STATE_ENDED) {
                    // A live channel never really "ends": the provider closed the connection. Reconnect.
                    if (current?.live == true) reconnect(p, false) else saveProgress()
                }
                updateQuality(p)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = updateQuality(p)
            override fun onTracksChanged(tracks: Tracks) = updateQuality(p)
        })
        exo = p
        return p
    }

    /** True while the player is reconnecting a dropped stream (shown as "Reconnecting…"). */
    var reconnecting by mutableStateOf(false)
        private set
    private var retries = 0
    private var bufferingSince = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var watchdog: kotlinx.coroutines.Job? = null

    /** Restarts the current stream after a short pause; gives up after 6 tries in a row. */
    private fun reconnect(p: ExoPlayer, behindLive: Boolean): Boolean {
        val item = current ?: return false
        if (retries >= 6) return false
        retries++
        reconnecting = true
        error = null
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (current?.url != item.url || exo !== p) return@postDelayed
            if (item.live) {
                // Fresh connection, back at the live edge.
                p.setMediaItem(MediaItem.fromUri(item.url))
            } else if (behindLive) {
                p.seekToDefaultPosition()
            }
            p.prepare()
            p.playWhenReady = true
        }, if (retries == 1) 1_000L else 3_000L)
        return true
    }

    /** Live TV stuck "loading" for 15 s (frozen picture) also counts as a dropped stream. */
    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                val p = exo ?: continue
                val since = bufferingSince
                if (current?.live == true && p.playWhenReady && since > 0 &&
                    android.os.SystemClock.elapsedRealtime() - since > 15_000
                ) {
                    bufferingSince = 0L
                    reconnect(p, false)
                }
            }
        }
    }

    private fun updateQuality(p: ExoPlayer) {
        val f: Format? = p.videoFormat
        val w = if (p.videoSize.width > 0) p.videoSize.width else f?.width ?: 0
        val h = if (p.videoSize.height > 0) p.videoSize.height else f?.height ?: 0
        if (w <= 0 || h <= 0) { quality = null; audioInfo = null; return }
        quality = describe(w, h, f)
        audioInfo = describeAudio(p)
        // Remember each live channel's real picture quality (shown in the sports channel menu).
        val item = current
        val ctx = appContext
        if (item?.live == true && item.channelId != null && ctx != null) {
            StreamQuality.remember(ctx, item.channelId, quality!!.substringBefore(" · "))
        }
    }

    /** e.g. "AC-3 5.1" or "No audio track"; shown in the player's bottom bar. */
    var audioInfo by mutableStateOf<String?>(null)
        private set

    private fun describeAudio(p: ExoPlayer): String {
        val a = p.audioFormat
        val hasAudioTrack = p.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (a == null) return if (hasAudioTrack) "Audio not supported on this box" else "No audio in this stream"
        val codec = when (val m = (a.sampleMimeType ?: "").lowercase(Locale.US)) {
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "AC-3"
            "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
            "audio/true-hd" -> "TrueHD"
            "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
            "audio/mpeg", "audio/mpeg-l2" -> "MP3/MP2"
            "audio/opus" -> "Opus"
            else -> m.substringAfter("audio/").uppercase(Locale.US)
        }
        val ch = when (a.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> ""; else -> "${a.channelCount}ch" }
        return "$codec $ch".trim()
    }


    /**
     * The box's own media volume (separate from the TV's). Red warning when it's at 0 or muted,
     * or when the player itself is muted, so silent channels can be told apart from silent streams.
     */
    fun boxVolume(context: Context): Pair<String, Boolean>? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return null
        val s = android.media.AudioManager.STREAM_MUSIC
        val v = am.getStreamVolume(s)
        val max = am.getStreamMaxVolume(s)
        val muted = android.os.Build.VERSION.SDK_INT >= 23 && am.isStreamMute(s)
        val appVol = exo?.volume ?: 1f
        return when {
            muted -> "Box sound is muted – press Mute or Volume + on the box remote" to true
            v == 0 -> "Box volume is 0 – press Volume + on the box remote" to true
            appVol < 0.5f -> "Player muted" to true
            else -> "Vol $v/$max" to false
        }
    }

    fun describe(w: Int, h: Int, f: Format?): String {
        // Use the shorter side, so 1920x1080 and 1920x1072 both read 1080p.
        val s = minOf(w, h)
        val res = when {
            s >= 2000 -> "4K"
            s >= 1400 -> "1440p"
            s >= 1000 -> "1080p"
            s >= 700 -> "720p"
            s >= 560 -> "576p"
            s >= 460 -> "480p"
            else -> "${s}p"
        }
        val parts = mutableListOf(res)
        f?.frameRate?.takeIf { it > 0 }?.let { parts += "${Math.round(it)} fps" }
        val codec = (f?.sampleMimeType ?: f?.codecs ?: "").lowercase(Locale.US)
        when {
            codec.contains("avc") || codec.contains("h264") -> parts += "H.264"
            codec.contains("hevc") || codec.contains("hvc") || codec.contains("hev") || codec.contains("h265") -> parts += "HEVC"
            codec.contains("av01") || codec.contains("av1") -> parts += "AV1"
            codec.contains("mpeg2") -> parts += "MPEG-2"
        }
        val br = f?.let { if (it.bitrate != Format.NO_VALUE) it.bitrate else if (it.peakBitrate != Format.NO_VALUE) it.peakBitrate else -1 } ?: -1
        if (br > 0) parts += String.format(Locale.US, "%.1f Mbps", br / 1_000_000.0)
        parts += "${w}×${h}"
        return parts.joinToString(" · ")
    }

    /** Starts [items] at [start] and opens full screen. Re-opens without reloading if it's already playing. */
    /** The live channel that was on before the current one (for "Last Channel"). */
    private var prevItems: List<PlayItem>? = null
    private var prevIndex = 0
    val hasLast: Boolean get() = prevItems != null

    private fun rememberLast(nextUrl: String) {
        val cur = current ?: return
        if (cur.live && cur.url != nextUrl) { prevItems = items; prevIndex = index }
    }

    /** Jumps back to the previous live channel (and remembers this one in its place). */
    fun playLast(context: Context): Boolean {
        val list = prevItems ?: return false
        val i = prevIndex
        rememberLast(list[i].url)
        items = list
        index = i
        load(context)
        return true
    }

    fun start(context: Context, list: List<PlayItem>, start: Int, fullScreen: Boolean = true) {
        if (list.isEmpty()) return
        val i = start.coerceIn(0, list.size - 1)
        val same = current?.url == list[i].url && exo != null && list[i].startMs == null
        if (!same) rememberLast(list[i].url)
        items = list
        index = i
        if (!same) load(context)
        if (fullScreen) openFullScreen(context)
    }

    fun openFullScreen(context: Context) {
        if (current == null) return
        previewHidden = false
        fullScreen = true
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    /** Opens the full-screen player straight into split screen (needs a live channel playing). */
    fun openSplitScreen(context: Context): Boolean {
        if (current?.live != true) return false
        previewHidden = false
        fullScreen = true
        context.startActivity(Intent(context, PlayerActivity::class.java).putExtra("split", true))
        return true
    }

    fun zap(context: Context, step: Int): Boolean {
        val n = items.size
        if (n < 2) return false
        val next = (index + step + n) % n
        rememberLast(items[next].url)
        index = next
        load(context)
        return true
    }

    private fun load(context: Context) {
        saveProgress()
        val item = items[index]
        ensureKind(context, vod = !item.live)
        val p = player(context)
        error = null
        quality = null
        reconnecting = false
        retries = 0
        bufferingSince = 0L
        handler.removeCallbacksAndMessages(null)
        startWatchdog()
        current = item
        p.volume = 1f // never stay muted from Multi-View
        val start = item.startMs
        if (start != null && start > 0) p.setMediaItem(MediaItem.fromUri(item.url), start)
        else p.setMediaItem(MediaItem.fromUri(item.url))
        p.prepare()
        p.playWhenReady = true
        item.channelId?.let { onChannelWatched?.invoke(it) }
        // Movies/episodes: remember the spot every 10 seconds for Recently Watched.
        progressJob?.cancel()
        if (item.resumeKey != null) {
            progressJob = scope.launch {
                while (true) { kotlinx.coroutines.delay(10_000); saveProgress() }
            }
        }
    }

    /** Saves the current movie/episode position (no-op for live TV). */
    fun saveProgress() {
        val item = current ?: return
        val key = item.resumeKey ?: return
        val p = exo ?: return
        val ctx = appContext ?: return
        val dur = p.duration
        if (dur == androidx.media3.common.C.TIME_UNSET || dur <= 0) return
        val pos = if (p.playbackState == Player.STATE_ENDED) dur else p.currentPosition
        com.forbiddenstreams.tv.data.Recents.save(
            ctx,
            com.forbiddenstreams.tv.data.RecentItem(
                key = key, kind = item.kind ?: "movie", title = item.title, subtitle = item.subtitle,
                image = item.image, url = item.url, positionMs = pos, durationMs = dur,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun stop() {
        saveProgress()
        progressJob?.cancel()
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        current = null
        quality = null
        error = null
        reconnecting = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Set when ◀◀ in the live player asks for the side bar; the home screen opens the Channels list. */
    var sideBarRequest by mutableIntStateOf(0)
        private set
    fun requestSideBar() { sideBarRequest++ }

    /** Closed captions (CC) on/off; remembered on the box. */
    var captionsOn by mutableStateOf(false)
        private set
    private var ccLoaded = false

    private fun applyCaptions(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !captionsOn)
            .setSelectUndeterminedTextLanguage(captionsOn)
            .setPreferredTextLanguage(if (captionsOn) Locale.getDefault().language else null)
            .build()
    }

    /** Turns captions on/off. Returns false when the stream has no caption track. */
    fun setCaptions(context: Context, on: Boolean): Boolean {
        captionsOn = on
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE).edit().putBoolean("cc_on", on).apply()
        val p = exo ?: return true
        applyCaptions(p)
        return !on || p.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
    }

    fun existingPlayer(): ExoPlayer? = exo

    fun pause() { saveProgress(); exo?.pause() }
    fun resume() { if (current != null) exo?.play() }

}

object Playback {
    fun start(context: Context, items: List<PlayItem>, index: Int = 0) = PlayerHolder.start(context, items, index)
    fun one(context: Context, item: PlayItem) = start(context, listOf(item), 0)
}


/**
 * Picture quality of a live channel: what the player actually saw last time it played,
 * otherwise a guess from the channel name (FHD, HD, 4K, SD…).
 */
object StreamQuality {
    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("stream_quality", Context.MODE_PRIVATE)

    fun remember(context: Context, channelId: String, q: String) {
        if (sp(context).getString(channelId, null) != q) sp(context).edit().putString(channelId, q).apply()
    }

    fun seen(context: Context, channelId: String): String? = sp(context).getString(channelId, null)

    fun guess(name: String): String? {
        val n = " " + name.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ") + " "
        return when {
            n.contains(" 4K ") || n.contains(" UHD ") || n.contains(" 2160") -> "4K"
            n.contains(" FHD ") || n.contains(" 1080") -> "1080p"
            n.contains(" HD ") || n.contains(" 720") -> "720p"
            n.contains(" SD ") || n.contains(" 480") || n.contains(" 576") -> "SD"
            else -> null
        }
    }

    /** e.g. "1080p" (seen) or "720p?" (from the name), or null if unknown. */
    fun label(context: Context, channelId: String, name: String): String? =
        seen(context, channelId) ?: guess(name)?.let { "$it?" }
}
