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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
data class Highlight(val videoId: String, val title: String, val search: String)

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
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class HighlightActivity : androidx.activity.ComponentActivity() {
    private var web: WebView? = null
    private var hint: android.widget.TextView? = null
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
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val w = WebView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(object {
                @JavascriptInterface fun onError(code: Int) {
                    post {
                        toast(this@HighlightActivity, "This video can't play here — opening YouTube")
                        openInYouTube(id); finish()
                    }
                }
                @JavascriptInterface fun onEnded() { post { finish() } }
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
        setContentView(root)
        web = w
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#v{width:100%;height:100%}</style></head>
            <body><div id="v"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var p;
              function onYouTubeIframeAPIReady(){
                p = new YT.Player('v',{width:'100%',height:'100%',videoId:'$id',
                  playerVars:{autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:0,iv_load_policy:3},
                  events:{
                    onReady:function(e){e.target.playVideo();},
                    onError:function(e){FS.onError(e.data);},
                    onStateChange:function(e){ if(e.data==0) FS.onEnded(); }
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
        handler.removeCallbacks(hideHint)
        try { web?.destroy() } catch (e: Exception) { }
        web = null
        super.onDestroy()
    }
}
