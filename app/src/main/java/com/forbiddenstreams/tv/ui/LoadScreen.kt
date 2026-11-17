package com.forbiddenstreams.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay

/** True while the opening loading screen is up (AppShell waits with its focus until it's gone). */
object LoadScreen {
    var showing by mutableStateOf(true)
    var step by mutableStateOf("Starting…")
    var progress by mutableStateOf(0f)
    /** The intro video (res/raw/intro.mp4) has finished (or couldn't play). */
    var videoDone by mutableStateOf(false)
}

/**
 * Nov 16: a loading screen when the app is opened — the logo, the name and a green bar — while the app
 * gets ready behind it (saved channel list, playlist refresh, Home). It fades out once the channels are
 * there (at least 1.2 s, at most 6 s), so the first screen you see is ready to use.
 */
@Composable
fun LoadScreenHost() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Nov 16 ("don't skip the wait, load everything beforehand"): the loading screen stays until the
    // playlist is updated, the guide for your recent channels is in, Home's pictures are loaded and
    // today's games are fetched. (Gives up after 90 s and opens with what it has.)
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        kotlinx.coroutines.withTimeoutOrNull(90_000) {
            // 1. Playlist
            LoadScreen.step = "Updating your playlist…"; LoadScreen.progress = 0.05f
            while (Session.source == null) delay(100)
            Session.refreshInBackground(context)
            delay(200)
            Session.awaitRefresh()
            while (Session.liveAll == null && Session.liveError == null) delay(100)
            LoadScreen.progress = 0.55f
            // 2. Guide for the channels you watch
            LoadScreen.step = "Loading the TV guide…"
            val recent = com.forbiddenstreams.tv.data.Prefs(context).history.mapNotNull { Session.channelById(it) }.take(20)
            kotlinx.coroutines.withTimeoutOrNull(12_000) {
                kotlinx.coroutines.coroutineScope {
                    recent.map { c -> async { try { Session.epg(c) } catch (e: Exception) { } } }.awaitAll()
                }
            }
            LoadScreen.progress = 0.75f
            // 3. Today's games (Home's live games, Sports)
            LoadScreen.step = "Getting today's games…"
            kotlinx.coroutines.withTimeoutOrNull(8_000) {
                kotlinx.coroutines.coroutineScope {
                    com.forbiddenstreams.tv.sports.SPORTS_ORDER.map { l ->
                        async { try { com.forbiddenstreams.tv.sports.Espn.week(l, 0, 0) } catch (e: Exception) { } }
                    }.awaitAll()
                }
            }
            LoadScreen.progress = 0.8f
            // 3b. Channels for today's games (live first, then the next ones), so the game pop-ups open instantly.
            kotlinx.coroutines.withTimeoutOrNull(25_000) {
                val now = System.currentTimeMillis()
                val games = com.forbiddenstreams.tv.sports.SPORTS_ORDER.flatMap { l ->
                    (try { com.forbiddenstreams.tv.sports.Espn.week(l, 0, 0) } catch (e: Exception) { emptyMap() }).values.flatten().map { l to it }
                }.filter { (_, g) -> g.isLive || (!g.isFinal && g.start - now < 8 * 3_600_000L) }
                    .sortedWith(compareBy({ if (it.second.isLive) 0 else 1 }, { it.second.start })).take(40)
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                kotlinx.coroutines.coroutineScope {
                    games.map { (l, g) ->
                        async { gate.acquire(); try { GameChannelCache.prepare(l, g) } catch (e: Exception) { } finally { gate.release() } }
                    }.awaitAll()
                }
            }
            LoadScreen.progress = 0.88f
            // 4. Home's pictures (Keep watching, channel logos)
            LoadScreen.step = "Getting Home ready…"
            val urls = (com.forbiddenstreams.tv.data.Recents.all(context).mapNotNull { it.image } + recent.mapNotNull { it.logo })
                .distinct().take(40)
            val loader = coil.Coil.imageLoader(context)
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                kotlinx.coroutines.coroutineScope {
                    urls.map { u -> async { try { loader.execute(coil.request.ImageRequest.Builder(context).data(u).build()) } catch (e: Exception) { } } }.awaitAll()
                }
            }
            LoadScreen.progress = 1f
            LoadScreen.step = "Ready"
        }
        // Never shorter than 1.2 s so it doesn't just flash — and the intro video plays to its end first.
        val e = System.currentTimeMillis() - t0
        if (e < 1_200) delay(1_200 - e)
        LoadScreen.showing = false
    }
    AnimatedVisibility(visible = LoadScreen.showing, exit = fadeOut(tween(400))) {
        val pulse = rememberInfiniteTransition(label = "load")
        val bar = pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Restart), label = "bar")
        val glow = pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
        val shown by androidx.compose.animation.core.animateFloatAsState(LoadScreen.progress, tween(500), label = "p")
        Box(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF0B2412), Color.Black), radius = 1400f)),
            contentAlignment = Alignment.Center,
        ) {
            // Nov 16: just the logo (the intro video was taken out again).
            Image(painterResource(R.drawable.logo), null, Modifier.size(260.dp).alpha(glow.value))
            // "Plugging in the revolution…" and a thin green bar, at the bottom.
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PLUGGING IN THE REVOLUTION", color = FS.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 8f)))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(320.dp).height(6.dp).background(Color(0x44000000), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(shown.coerceAtLeast(0.03f)).fillMaxHeight().alpha(0.75f + 0.25f * bar.value)
                        .background(FS.Green, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}
