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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
            LoadScreen.progress = 0.66f
            // (Nov 21: Movies / TV Series removed — not loaded.)
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
            LoadScreen.progress = 0.82f
            // 3b2. Nov 21: highlights first — the right video for every finished game of the last 2 days
            // (favorite teams first), 4 at a time; waits up to 20 s, the rest keeps going in the background.
            val hlJob = SportsPreload.scope.launch {
                val fin = com.forbiddenstreams.tv.sports.SPORTS_ORDER.flatMap { l ->
                    (try { com.forbiddenstreams.tv.sports.Espn.week(l, -2, 0) } catch (e: Exception) { emptyMap() }).values.flatten()
                        .filter { highlightsOffered(it) }.map { l to it }
                }.distinctBy { it.first.name + it.second.id }
                    .sortedWith(compareBy({ if (com.forbiddenstreams.tv.sports.FavTeams.inGame(context, it.first, it.second)) 0 else 1 }, { -it.second.start }))
                    .take(80)
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                kotlinx.coroutines.coroutineScope {
                    fin.map { (l, g) -> async { gate.acquire(); try { com.forbiddenstreams.tv.sports.HighlightFinder.find(l, g) } catch (e: Exception) { } finally { gate.release() } } }.awaitAll()
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(20_000) { hlJob.join() }
            LoadScreen.progress = 0.84f
            // 3c. Nov 17: replays and highlights for finished games (today and yesterday, favorite teams first) —
            // the replay channels and the right highlight video are found now. Waits up to 20 s here; whatever
            // isn't done keeps going in the background after Home opens.
            val finishedJob = SportsPreload.scope.launch {
                val sports = com.forbiddenstreams.tv.sports.SPORTS_ORDER
                val finished = sports.flatMap { l ->
                    (try { com.forbiddenstreams.tv.sports.Espn.week(l, -2, 0) } catch (e: Exception) { emptyMap() }).values.flatten()
                        .filter { highlightsOffered(it) }.map { l to it }
                }.distinctBy { it.first.name + it.second.id }
                    .sortedWith(compareBy({ if (com.forbiddenstreams.tv.sports.FavTeams.inGame(context, it.first, it.second)) 0 else 1 }, { -it.second.start }))
                    .take(60)
                val gate = kotlinx.coroutines.sync.Semaphore(2)
                kotlinx.coroutines.coroutineScope {
                    finished.map { (l, g) ->
                        async {
                            gate.acquire()
                            try {
                                try { GameChannelCache.prepare(l, g) } catch (e: Exception) { }
                                try { com.forbiddenstreams.tv.sports.HighlightFinder.find(l, g) } catch (e: Exception) { }
                            } finally { gate.release() }
                        }
                    }.awaitAll()
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(20_000) { finishedJob.join() }
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
        RetroLoading()
    }
}

/**
 * Nov 17 (user's photo, "exactly like this"): the user's own old-TV picture is the background
 * (`drawable-nodpi/crt_screen.jpg` — his photo with the text and bar painted out), and on it, placed where
 * they are in the photo: "Forbidden Streams TV", "LOADING" with its dots coming in one by one, the chunky
 * segmented bar in a glowing outline that fills as the app loads, and "Plugging In The Revolution".
 * Positions/sizes are fractions of the screen measured from the photo (2576 × 1438).
 */
@Composable
private fun RetroLoading() {
    val lime = Color(0xFFA8EC4C)
    // Nov 17: Share Tech Mono (Google Fonts, OFL) — the closest open font to the photo's terminal lettering.
    val mono = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(R.font.share_tech_mono))
    val glow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(lime.copy(alpha = 0.8f), blurRadius = 22f))
    var dots by androidx.compose.runtime.remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(450); dots = (dots + 1) % 4 } }
    val flicker = rememberInfiniteTransition(label = "crt").animateFloat(0.92f, 1f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "f")
    val shown by androidx.compose.animation.core.animateFloatAsState(LoadScreen.progress, tween(500), label = "p")
    val density = androidx.compose.ui.platform.LocalDensity.current

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = maxWidth; val h = maxHeight
        fun sp(fracOfH: Float) = with(density) { (h * fracOfH).toSp() }
        Image(painterResource(R.drawable.crt_screen), null, Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
        Box(Modifier.fillMaxSize().alpha(flicker.value)) {
            // Nov 21: our own mascot — a little glowing TV giving a thumbs-up and winking while it loads.
            TvGuy(lime, Modifier.align(Alignment.TopCenter).padding(top = h * 0.075f).size(h * 0.36f))
            // Forbidden Streams TV — above LOADING, same green.
            Text("FORBIDDEN STREAMS TV", color = lime, fontSize = sp(0.064f), fontWeight = FontWeight.Bold, fontFamily = mono,
                letterSpacing = sp(0.004f), style = glow, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.455f))
            // LOADING + dots one by one (padded so the word doesn't move).
            Text("LOADING" + ".".repeat(dots) + " ".repeat(3 - dots), color = lime, fontSize = sp(0.062f), fontWeight = FontWeight.Bold,
                fontFamily = mono, style = glow, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.575f)
                    .then(Modifier.graphicsLayer(scaleY = 1.15f)))
            // Nov 17 ("make the loading bar look the same as the picture"): the bar is cut from the photo
            // itself — its rough glowing outline (crt_bar_frame) and one of its blocks (crt_bar_segment), drawn
            // where they are in the photo (2576 × 1438 px); 28 blocks fill as the app loads.
            val frameImg = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.crt_bar_frame)
            val segImg = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.crt_bar_segment)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / 2576f; val sy = size.height / 1438f
                fun ri(v: Float) = kotlin.math.round(v).toInt()
                drawImage(frameImg, dstOffset = androidx.compose.ui.unit.IntOffset(ri(600 * sx), ri(932 * sy)),
                    dstSize = androidx.compose.ui.unit.IntSize(ri(1380 * sx), ri(184 * sy)),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                val n = 28
                val filled = (shown.coerceIn(0f, 1f) * n).toInt().coerceAtLeast(1)
                for (i in 0 until filled) {
                    val x = 665.5f + i * 44.76f
                    drawImage(segImg, dstOffset = androidx.compose.ui.unit.IntOffset(ri(x * sx), ri(986 * sy)),
                        dstSize = androidx.compose.ui.unit.IntSize(ri(45 * sx), ri(81 * sy)),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                }
            }
            Text("Plugging In The Revolution", color = lime, fontSize = sp(0.056f), fontWeight = FontWeight.Normal, fontFamily = mono,
                style = glow, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.772f))
        }
        // Fine scan lines over everything, like the photo.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val step = 3.dp.toPx(); var y = 0f
            while (y < size.height) { drawRect(Color(0x22000000), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, step / 2)); y += step }
        }
    }
}


/** Sports work that may outlive the loading screen (finishes in the background). */
object SportsPreload {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + com.forbiddenstreams.tv.data.Http.backgroundLane)
}


private fun circ(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"
private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} $y H${x + w - r} Q${x + w} $y ${x + w} ${y + r} V${y + h - r} Q${x + w} ${y + h} ${x + w - r} ${y + h} " +
        "H${x + r} Q$x ${y + h} $x ${y + h - r} V${y + r} Q$x $y ${x + r} $y Z"

/** The TV guy's lines, in a 200 × 200 box (our own design, Nov 21). */
private val TV_GUY_LINES = listOf(
    "M90 44 L70 14", circ(68f, 11f, 4f), "M110 44 L134 16", circ(136f, 13f, 4f),            // antennas
    rrect(38f, 44f, 124f, 96f, 18f), rrect(50f, 55f, 80f, 74f, 14f),                       // body, screen
    circ(146f, 70f, 6f), "M146 66 L146 70", circ(146f, 92f, 6f), "M143 92 L149 92",          // knobs
    "M139 110 H153 M139 117 H153 M139 124 H153",                                              // speaker
    "M66 74 Q73 69 80 73", "M100 73 Q107 69 114 74",                                          // eyebrows
    "M68 101 Q90 122 112 101",                                                                // smile
    "M72 140 L68 162 L58 164", "M128 140 L132 162 L142 164",                                  // legs
    "M38 104 Q24 104 22 92", rrect(12f, 76f, 22f, 18f, 7f), "M18 77 L17 64 Q17 58 22 59 Q26 60 25 66 L24 76", // thumbs-up arm
    "M162 100 Q182 108 172 124 Q168 130 162 126",                                             // hand on hip
)
private val TV_GUY_THIN = listOf("M14 83 H30 M14 88 H30")
private val TV_GUY_EYE_L = circ(73f, 84f, 4.5f)
private val TV_GUY_EYE_R = circ(107f, 84f, 4.5f)
private val TV_GUY_WINK = "M101 85 Q107 79 113 85"

/** The glowing TV guy: winks every couple of seconds and bobs gently. */
@Composable
private fun TvGuy(color: Color, modifier: Modifier) {
    fun parse(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
    val lines = androidx.compose.runtime.remember { TV_GUY_LINES.map { parse(it) } }
    val thin = androidx.compose.runtime.remember { TV_GUY_THIN.map { parse(it) } }
    val eyeL = androidx.compose.runtime.remember { parse(TV_GUY_EYE_L) }
    val eyeR = androidx.compose.runtime.remember { parse(TV_GUY_EYE_R) }
    val wink = androidx.compose.runtime.remember { parse(TV_GUY_WINK) }
    var winking by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2_200); winking = true; delay(320); winking = false } }
    val bob = rememberInfiniteTransition(label = "bob").animateFloat(0f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "b")
    androidx.compose.foundation.Canvas(modifier) {
        val k = size.minDimension / 200f
        translate(top = (bob.value - 0.5f) * 3f * k) {
            scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                fun stroke(w: Float) = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val all = lines + listOf(if (winking) wink else eyeR)
                // Glow, then the line itself.
                for ((w, a) in listOf(11f to 0.10f, 7f to 0.22f, 3.4f to 1f)) {
                    all.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w)) }
                    thin.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w * 0.6f)) }
                }
                drawPath(eyeL, color)
                if (!winking) drawPath(eyeR, color)
            }
        }
    }
}
