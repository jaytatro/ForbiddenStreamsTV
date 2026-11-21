package com.forbiddenstreams.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.AppSettings
import kotlinx.coroutines.delay

/** A YouTube video playing inside the app (Nov 15: game highlights without leaving Forbidden Streams). */
data class Highlight(
    val videoId: String, val title: String, val search: String,
    /** For the loading screen (Nov 15): both teams, their logos and the final score. */
    val away: String = "", val home: String = "", val awayLogo: String? = null, val homeLogo: String? = null,
    val awayScore: String? = null, val homeScore: String? = null, val league: String = "", val icon: String = "", val date: String = "",
)

object Highlights {
    /** Set to play a highlight full screen; shown by [HighlightPlayerHost] in the app shell. */
    var playing by mutableStateOf<Highlight?>(null)

    /** Installed TizenTube (package), if any: an app whose name contains "TizenTube". */
    fun tizenTube(context: Context): String? = try {
        val pm = context.packageManager
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN)
        @Suppress("DEPRECATION")
        (pm.queryIntentActivities(android.content.Intent(i).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(android.content.Intent(i).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0))
            .firstOrNull { it.loadLabel(pm).toString().contains("tizentube", ignoreCase = true) }?.activityInfo?.packageName
    } catch (e: Exception) { null }

    /** Opens the video in TizenTube (the video link, or just the app if it doesn't take links). */
    fun openInTizenTube(context: Context, pkg: String, id: String): Boolean {
        val url = android.net.Uri.parse("https://www.youtube.com/watch?v=$id")
        return tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW, url).setPackage(pkg)) ||
            (context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: context.packageManager.getLaunchIntentForPackage(pkg))
                ?.let { tryStart(context, it) } == true
    }
}

/**
 * Full-screen YouTube player inside the app (YouTube's own embedded player in a WebView).
 * Nov 15: its own screen (an Activity) — inside a pop-up the video drew underneath and only sound came through.
 * Remote: OK = pause / play, ◀ ▶ = skip back / ahead, BACK = close. If the video can't be played
 * inside other apps, it opens in YouTube instead.
 */
@Composable
fun HighlightPlayerHost() {
    val h = Highlights.playing ?: return
    val context = LocalContext.current
    LaunchedEffect(h) {
        Highlights.playing = null
        com.forbiddenstreams.tv.player.PlayerHolder.pause()
        context.startActivity(android.content.Intent(context, HighlightActivity::class.java)
            .putExtra("id", h.videoId).putExtra("title", h.title)
            .putExtra("away", h.away).putExtra("home", h.home).putExtra("awayLogo", h.awayLogo).putExtra("homeLogo", h.homeLogo)
            .putExtra("awayScore", h.awayScore).putExtra("homeScore", h.homeScore)
            .putExtra("league", h.league).putExtra("icon", h.icon).putExtra("date", h.date)
            .putExtra("backups", com.forbiddenstreams.tv.sports.HighlightFinder.backupsFor(h.videoId).toTypedArray())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class HighlightActivity : androidx.activity.ComponentActivity() {
    private var web: WebView? = null
    private var hint: android.widget.TextView? = null
    private var cover: android.view.View? = null
    private val tries = ArrayList<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideHint = Runnable { hint?.visibility = android.view.View.GONE }

    private fun showHint() {
        hint?.visibility = android.view.View.VISIBLE
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 5_000)
    }

    private fun js(code: String) { try { web?.evaluateJavascript(code, null) } catch (e: Exception) { } }

    private var vq = "hd1080"
    private var currentId = ""
    @Volatile private var playing = false
    @Volatile private var failed = false

    /**
     * Nov 21 ("make sure the Forbidden player works"): YouTube refuses embeds that come from a page with no
     * web address (error 152/153), which is what the old in-memory page was on some boxes. Now the YouTube
     * player page itself is opened (with a referrer), and the app hooks into its player for OK / ◀ ▶,
     * quality, "playing" and errors.
     */
    private fun load(id: String) {
        currentId = id
        playing = false
        web?.loadUrl("https://www.youtube.com/tv#/watch?v=$id", mapOf("Referer" to "https://www.youtube.com/tv"))
    }

    /** Hooks the page's video: OK / ◀ ▶ work on it, "playing" takes the teams screen away, the end closes. */
    private fun hook() = js("""
        (function(){
          if(!window.__fsAds){ window.__fsAds=1; try{ $AD_STRIP_INLINE }catch(e){} }
          var tries=0;
          function go(){
            var v=document.querySelector('video');
            if(!v){ if(++tries<80) setTimeout(go,500); return; }
            window.v=v;
            if(!v.__fs){
              v.__fs=1;
              v.addEventListener('playing',function(){ FS.onPlaying(); });
              v.addEventListener('ended',function(){ FS.onEnded(); });
              v.addEventListener('error',function(){ FS.onError(5); });
            }
            v.muted=false; v.volume=1;
            if(v.paused){ var pr=v.play(); if(pr&&pr.catch) pr.catch(function(){}); }
            if(!v.paused && v.readyState>2) FS.onPlaying();
          }
          go();
        })();
    """.trimIndent())

    private fun openInYouTube(id: String) {
        tryStart(this, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id"))) ||
            tryStart(this, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getStringExtra("id") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""
        tries.clear(); tries.addAll(intent.getStringArrayExtra("backups").orEmpty())
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val w = WebView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Nov 17 ("highest quality"): desktop browser + a page as wide as the screen's real pixels, so YouTube
            // serves 1080p (or 4K on a 4K box) instead of the small-screen 480p/720p.
            // Nov 21 ("take it from TizenTube"): the same YouTube TV player TizenTube runs (youtube.com/tv), as a Samsung
            // Tizen TV — it plays on TVs without the embed restrictions, at TV quality, with TizenTube's ad removal.
            settings.userAgentString = TV_UA
            // Nov 21: the page is laid out at the screen's real pixels (1 page pixel = 1 screen pixel), so YouTube
            // picks 1080p / 4K for the full-screen player.
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            setInitialScale(100)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { hook() }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun onError(code: Int) {
                    post {
                        // Nov 16: some channels turn off playing in other apps — try the next match for this game first.
                        if (failed) return@post
                        val next = tries.removeFirstOrNull()
                        if (next != null) { load(next); return@post }
                        failed = true
                        toast(this@HighlightActivity, "This video can't play here — opening YouTube")
                        openInYouTube(currentId); finish()
                    }
                }
                @JavascriptInterface fun onEnded() { post { finish() } }
                // The video is playing: take the teams screen away.
                @JavascriptInterface fun onPlaying() { post { playing = true; cover?.visibility = android.view.View.GONE } }
            }, "FS")
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, AD_STRIP, setOf("https://www.youtube.com")) } catch (e: Exception) { }
        }
        root.addView(w, android.widget.FrameLayout.LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        hint = android.widget.TextView(this).apply {
            text = "▶ HIGHLIGHTS   $title     OK pause · ◀ ▶ ${AppSettings.skipSec}s · BACK close"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6040604.toInt()); cornerRadius = 14 * d; setStroke((1 * d).toInt(), 0xFF2E7D1A.toInt())
            }
        }
        root.addView(hint, android.widget.FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = (28 * d).toInt(); topMargin = (28 * d).toInt()
        })
        // Loading screen with the two teams (instead of YouTube's grey play-button screen) until the video plays.
        val c = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                HighlightCover(
                    away = intent.getStringExtra("away") ?: "", home = intent.getStringExtra("home") ?: "",
                    awayLogo = intent.getStringExtra("awayLogo"), homeLogo = intent.getStringExtra("homeLogo"),
                    awayScore = intent.getStringExtra("awayScore"), homeScore = intent.getStringExtra("homeScore"),
                    league = intent.getStringExtra("league") ?: "", icon = intent.getStringExtra("icon") ?: "",
                    date = intent.getStringExtra("date") ?: "",
                )
            }
        }
        root.addView(c, android.widget.FrameLayout.LayoutParams(-1, -1))
        cover = c
        hint?.bringToFront()
        // Still not playing after 12 s (autoplay blocked on some boxes): show the video so OK can start it.
        handler.postDelayed({ if (cover?.visibility == android.view.View.VISIBLE) hook() }, 6_000)
        handler.postDelayed({ cover?.visibility = android.view.View.GONE }, 12_000)
        setContentView(root)
        web = w
        val screenW = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).let {
            (if (android.os.Build.VERSION.SDK_INT >= 23) windowManager.defaultDisplay.mode.physicalWidth else it).coerceAtLeast(it)
        }
        vq = if (screenW >= 3000) "hd2160" else if (screenW >= 1700) "hd1080" else "hd720"
        load(id)
        // Watchdog: not playing after 25 s → the next match for this game, then the YouTube app.
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (playing || failed || isFinishing) return
                val next = tries.removeFirstOrNull()
                if (next != null) { load(next); handler.postDelayed(this, 25_000) }
                else { failed = true; toast(this@HighlightActivity, "This video can't play here — opening YouTube"); openInYouTube(currentId); finish() }
            }
        }, 25_000)
        showHint()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val skip = AppSettings.skipSec
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.repeatCount == 0) js("(function(){var v=window.v||document.querySelector('video');if(v){v.paused?v.play():v.pause();}})()")
                    showHint(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    js("(function(){var v=window.v||document.querySelector('video');if(v){v.currentTime=Math.max(0,v.currentTime-$skip);}})()"); showHint(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    js("(function(){var v=window.v||document.querySelector('video');if(v){v.currentTime=v.currentTime+$skip;}})()"); showHint(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { showHint(); return true }
            }
        } else if (event.keyCode != android.view.KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { web?.destroy() } catch (e: Exception) { }
        web = null
        super.onDestroy()
    }
}


/** The loading screen: league, both teams with logos and the final score, "Loading highlights…". */
@Composable
private fun HighlightCover(
    away: String, home: String, awayLogo: String?, homeLogo: String?, awayScore: String?, homeScore: String?,
    league: String, icon: String, date: String,
) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "l").animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0A1F10), Color.Black))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text("$icon  ${league.uppercase()}  ·  GAME HIGHLIGHTS", color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        if (date.isNotBlank()) Text(date, color = FS.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(36.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverTeam(away, awayLogo)
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 48.dp)) {
                if (awayScore != null || homeScore != null) {
                    Text("${awayScore ?: 0}  –  ${homeScore ?: 0}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("FINAL", color = FS.TextDim, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                } else Text("VS", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Black)
            }
            CoverTeam(home, homeLogo)
        }
        Spacer(Modifier.height(44.dp))
        Text("Loading highlights…", color = FS.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(pulse.value))
    }
}

@Composable
private fun CoverTeam(name: String, logo: String?) {
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(300.dp)) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) { NetImage(logo, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(14.dp))
        Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}


/** A Samsung Tizen TV browser — what TizenTube presents to YouTube for its TV player. */
private const val TV_UA = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 (KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36"

/** TizenTube-style ad removal: strip the ad parts out of YouTube's player data as the page reads it. */
private const val AD_STRIP_INLINE = "var __p=JSON.parse;JSON.parse=function(){var r=__p.apply(this,arguments);try{if(r&&typeof r==='object'){if(r.adPlacements)r.adPlacements=[];if(r.playerAds)r.playerAds=[];if(r.adSlots)r.adSlots=[];if(r.adBreakHeartbeatParams)delete r.adBreakHeartbeatParams;}}catch(e){}return r;};"
private const val AD_STRIP = "(function(){if(window.__fsAds)return;window.__fsAds=1;" + AD_STRIP_INLINE + "})();"
