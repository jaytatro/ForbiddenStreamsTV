package com.forbiddenstreams.tv

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayerHolder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.forbiddenstreams.tv.reminders.Alarms
import com.forbiddenstreams.tv.ui.FS
import com.forbiddenstreams.tv.ui.ForbiddenTheme
import com.forbiddenstreams.tv.ui.AppShell
import com.forbiddenstreams.tv.ui.LoginScreen

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.forbiddenstreams.tv.data.AppSettings.init(this)
        // Nov 21: the highlights player runs in its own process (its web player's memory can't push the
        // app out, so BACK returns to the app as it was). That process needs nothing else.
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
            try { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') } catch (e: Exception) { "" }
        if (proc.endsWith(":highlights")) return
        Alarms.createChannels(this)
        // Settings › General › Auto start on wake up (works while the app is still in memory).
        try {
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    if (com.forbiddenstreams.tv.data.AppSettings.autoStartWake) startMain(c)
                }
            }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
        } catch (_: Exception) { }
        // Keep the last crash on the box so it can be read in Settings › Account.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "last_crash.txt").writeText(
                    java.text.SimpleDateFormat("EEE M/d h:mm a", java.util.Locale.US).format(java.util.Date()) + "\n" + sw.toString().take(30000),
                )
            } catch (_: Exception) { }
            previous?.uncaughtException(t, e)
        }
        PlayerHolder.onChannelWatched = { id -> Prefs(this).addHistory(id) }
        // Pause when the whole app goes to the background (HOME), resume when it comes back.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = PlayerHolder.pause()
            override fun onStart(owner: LifecycleOwner) = PlayerHolder.resume()
        })
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Http.client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

/** Opens the app (auto start on boot / wake up). */
fun startMain(c: android.content.Context) {
    try {
        c.startActivity(android.content.Intent(c, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        com.forbiddenstreams.tv.data.AppSettings.init(context)
        if (com.forbiddenstreams.tv.data.AppSettings.autoStartBoot) startMain(context)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Nov 16: Android's splash (black + logo) stays up until the saved channel list is read (max ~1.5 s),
        // then our own loading screen takes over until the app is ready.
        val splash = installSplashScreen()
        val t0 = android.os.SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { Session.source != null && Session.liveAll == null && android.os.SystemClock.uptimeMillis() - t0 < 1_500 }
        super.onCreate(savedInstanceState)
        // Nov 21: coming back after Android closed the app in the background (e.g. while highlights played):
        // straight back to where you were — no loading screen; everything refreshes behind it.
        if (savedInstanceState != null) com.forbiddenstreams.tv.ui.LoadScreen.showing = false
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC // remote volume keys = media volume
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            ForbiddenTheme {
                val context = LocalContext.current
                var loggedIn by remember { mutableStateOf(Session.source != null || Session.restore(context)) }
                // Show the saved channel list instantly and update the playlist in the background.
                androidx.compose.runtime.LaunchedEffect(loggedIn) { if (loggedIn) Session.onAppOpened(context) }
                Box(Modifier.fillMaxSize().background(FS.background)) {
                    if (!loggedIn) {
                        LoginScreen(onLoggedIn = { loggedIn = true })
                    } else {
                        // The new app (started from scratch): tabs across the top, opens on Home.
                        AppShell(onLogout = {
                            PlayerHolder.stop()
                            Session.logout(context)
                            com.forbiddenstreams.tv.data.MovieMatcher.reset(); com.forbiddenstreams.tv.ui.resetLibrary()
                            loggedIn = false
                        })
                        // Loading screen over the app while it gets ready behind it (channels, guide, Home).
                        com.forbiddenstreams.tv.ui.LoadScreenHost()
                    }
                }
            }
        }
    }
}
