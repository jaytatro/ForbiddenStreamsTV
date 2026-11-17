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
        tries.clear(); tries.addAll(com.forbiddenstreams.tv.sports.HighlightFinder.backupsFor(id))
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
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(object {
                @JavascriptInterface fun onError(code: Int) {
                    post {
                        // Nov 16: some channels turn off playing in other apps — try the next match for this game first.
                        val next = tries.removeFirstOrNull()
                        if (next != null) { js("if(window.p){p.loadVideoById('$next');}"); return@post }
                        toast(this@HighlightActivity, "This video can't play here — opening YouTube")
                        openInYouTube(id); finish()
                    }
                }
                @JavascriptInterface fun onEnded() { post { finish() } }
                // The video is playing: take the teams screen away.
                @JavascriptInterface fun onPlaying() { post { cover?.visibility = android.view.View.GONE } }
            }, "FS")
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
        handler.postDelayed({ if (cover?.visibility == android.view.View.VISIBLE) { js("if(window.p){p.playVideo();}") } }, 6_000)
        handler.postDelayed({ cover?.visibility = android.view.View.GONE }, 12_000)
        setContentView(root)
        web = w
        val screenW = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).let {
            (if (android.os.Build.VERSION.SDK_INT >= 23) windowManager.defaultDisplay.mode.physicalWidth else it).coerceAtLeast(it)
        }
        val vq = if (screenW >= 3000) "hd2160" else if (screenW >= 1700) "hd1080" else "hd720"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=$screenW">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#v{width:100%;height:100%}</style></head>
            <body><div id="v"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var p;
              function onYouTubeIframeAPIReady(){
                p = new YT.Player('v',{width:'100%',height:'100%',videoId:'$id',
                  playerVars:{vq:'$vq',autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:0,iv_load_policy:3},
                  events:{
                    onReady:function(e){try{e.target.setPlaybackQuality('$vq');}catch(x){} e.target.playVideo();},
                    onError:function(e){FS.onError(e.data);},
                    onStateChange:function(e){ if(e.data==0) FS.onEnded(); if(e.data==1) FS.onPlaying(); }
                  }});
              }
            </script></body></html>
        """.trimIndent()
        w.loadDataWithBaseURL("https://forbiddenstreams.tv/", html, "text/html", "utf-8", null)
        showHint()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val skip = AppSettings.skipSec
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.repeatCount == 0) js("if(window.p){p.getPlayerState()==1?p.pauseVideo():p.playVideo();}")
                    showHint(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    js("if(window.p){p.seekTo(Math.max(0,p.getCurrentTime()-$skip),true);}"); showHint(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    js("if(window.p){p.seekTo(p.getCurrentTime()+$skip,true);}"); showHint(); return true
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
