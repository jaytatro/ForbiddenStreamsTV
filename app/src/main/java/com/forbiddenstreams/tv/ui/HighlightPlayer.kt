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
 * Remote: OK = pause / play, ◀ ▶ = 10 s back / ahead, BACK = close. If the video can't be played
 * inside other apps, it opens in YouTube instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HighlightPlayerHost() {
    val h = Highlights.playing ?: return
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var hint by remember(h) { mutableStateOf(true) }
    val req = remember { FocusRequester() }
    fun close() { Highlights.playing = null }
    fun js(code: String) { try { web?.evaluateJavascript(code, null) } catch (e: Exception) { } }
    LaunchedEffect(h) { com.forbiddenstreams.tv.player.PlayerHolder.pause(); delay(5_000); hint = false }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { close() }
        LaunchedEffect(Unit) { repeat(10) { delay(80); try { req.requestFocus(); return@LaunchedEffect } catch (e: Exception) { } } }
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(req).focusable()
                .onPreviewKeyEvent { ev ->
                    val ne = ev.nativeKeyEvent
                    if (ne.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    hint = true
                    when (ne.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (ne.repeatCount == 0) js("if(window.p){p.getPlayerState()==1?p.pauseVideo():p.playVideo();}"); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            js("if(window.p){p.seekTo(Math.max(0,p.getCurrentTime()-${AppSettings.skipSec}),true);}"); true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            js("if(window.p){p.seekTo(p.getCurrentTime()+${AppSettings.skipSec},true);}"); true
                        }
                        else -> false
                    }
                },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
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
                            // The video doesn't allow playing inside other apps (or isn't available): open it in YouTube.
                            @JavascriptInterface fun onError(code: Int) {
                                post {
                                    close()
                                    toast(context, "This video can't play here — opening YouTube")
                                    tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:${h.videoId}"))) ||
                                        tryStart(context, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=${h.videoId}")))
                                }
                            }
                            @JavascriptInterface fun onEnded() { post { close() } }
                        }, "FS")
                        val html = """
                            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#v{width:100%;height:100%}</style></head>
                            <body><div id="v"></div>
                            <script src="https://www.youtube.com/iframe_api"></script>
                            <script>
                              var p;
                              function onYouTubeIframeAPIReady(){
                                p = new YT.Player('v',{width:'100%',height:'100%',videoId:'${h.videoId}',
                                  playerVars:{autoplay:1,controls:1,rel:0,modestbranding:1,playsinline:1,fs:0,iv_load_policy:3},
                                  events:{
                                    onReady:function(e){e.target.playVideo();},
                                    onError:function(e){FS.onError(e.data);},
                                    onStateChange:function(e){ if(e.data==0) FS.onEnded(); }
                                  }});
                              }
                            </script></body></html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://forbiddenstreams.tv/", html, "text/html", "utf-8", null)
                        web = this
                    }
                },
            )
            if (hint) Row(
                Modifier.align(Alignment.TopStart).padding(28.dp)
                    .background(Color(0xE6040604), RoundedCornerShape(14.dp))
                    .border(1.dp, FS.GreenDeep, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▶ HIGHLIGHTS", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(FS.Green, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(12.dp))
                Text(h.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(16.dp))
                Text("OK pause · ◀ ▶ ${AppSettings.skipSec}s · BACK close", color = FS.TextDim, fontSize = 13.sp)
            }
        }
    }
    DisposableEffect(h) { onDispose { try { web?.destroy() } catch (e: Exception) { }; web = null } }
}
