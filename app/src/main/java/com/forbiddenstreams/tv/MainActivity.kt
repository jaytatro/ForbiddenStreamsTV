package com.forbiddenstreams.tv

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
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
import com.forbiddenstreams.tv.ui.HomeScreen
import com.forbiddenstreams.tv.ui.LoginScreen

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Alarms.createChannels(this)
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        HomeScreen(onLogout = {
                            PlayerHolder.stop()
                            Session.logout(context)
                            loggedIn = false
                        })
                    }
                }
            }
        }
    }
}
