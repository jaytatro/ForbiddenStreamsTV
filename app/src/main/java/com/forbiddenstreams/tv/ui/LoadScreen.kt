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
import androidx.compose.ui.graphics.drawscope.rotate
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
            // Nov 21: our own mascot — Sparky, a 1950s atomic-age robot, waving and winking while it loads.
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

/**
 * Nov 21: our own mascot — "Sparky", a 1950s atomic-age tin-toy robot (our own design): dome-cornered head,
 * antenna bulb, round lens eyes, grille mouth, chest dial, claw hands. 200 × 200 box, lit from the top-left.
 */
private object Sparky {
    val lines = listOf(
        "M100 30 V15", circ(100f, 10f, 5f),                                              // antenna
        rrect(66f, 30f, 68f, 54f, 16f), circ(62f, 58f, 5f), circ(138f, 58f, 5f),          // head, ear bolts
        circ(86f, 54f, 9f), circ(114f, 54f, 9f),                                          // eye lenses
        rrect(82f, 68f, 36f, 9f, 3f),                                                     // mouth grille
        rrect(58f, 92f, 84f, 76f, 14f), circ(100f, 120f, 13f),                            // body, dial
        circ(84f, 152f, 3.5f), circ(100f, 152f, 3.5f), circ(116f, 152f, 3.5f),            // buttons
        "M58 106 L44 120 L36 132 M53 110 L49 106 M46 118 L42 114",                        // left arm
        "M30 138 Q28 128 36 128 Q44 128 42 136 M36 142 L34 138",                          // left claw
        "M74 168 H90 V188 H74 Z", "M110 168 H126 V188 H110 Z",                            // legs
        rrect(68f, 188f, 26f, 8f, 4f), rrect(106f, 188f, 26f, 8f, 4f),                    // feet
    )
    val thin = listOf("M89 68 V77 M95 68 V77 M101 68 V77 M107 68 V77 M113 68 V77", "M92 84 V92 M108 84 V92 M92 88 H108", "M90 126 H110")
    val armR = listOf("M142 106 L156 92 L164 80 M147 102 L151 106 M154 94 L158 98", "M164 74 Q162 64 170 64 Q178 64 176 72 M170 78 L168 74")
    val dots = listOf(circ(64f, 98f, 1.6f), circ(136f, 98f, 1.6f), circ(64f, 162f, 1.6f), circ(136f, 162f, 1.6f))
    val pupilL = circ(86f, 54f, 3.6f); val pupilR = circ(114f, 54f, 3.6f)
    const val WINK = "M106 55 Q114 50 122 55"
    val metal = listOf(rrect(58f, 92f, 84f, 76f, 14f), rrect(66f, 30f, 68f, 54f, 16f), "M92 84 H108 V92 H92 Z",
        "M74 168 H90 V188 H74 Z", "M110 168 H126 V188 H110 Z", rrect(68f, 188f, 26f, 8f, 4f), rrect(106f, 188f, 26f, 8f, 4f))
}

/** Sparky, glowing: pops in, bobs, waves, winks every ~2.4 s, antenna bulb pulses, chest dial follows the loading. */
@Composable
private fun TvGuy(color: Color, modifier: Modifier) {
    fun parse(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
    val P = androidx.compose.runtime.remember {
        object {
            val lines = Sparky.lines.map { parse(it) }; val thin = Sparky.thin.map { parse(it) }
            val armR = Sparky.armR.map { parse(it) }; val dots = Sparky.dots.map { parse(it) }
            val pupilL = parse(Sparky.pupilL); val pupilR = parse(Sparky.pupilR); val wink = parse(Sparky.WINK)
            val metal = Sparky.metal.map { parse(it) }
        }
    }
    val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 160f)) }
    var winking by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2_400); winking = true; delay(380); winking = false } }
    val inf = rememberInfiniteTransition(label = "sparky")
    val bob = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "b")
    val wave = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "w")
    val bulb = inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "l")
    val dial by androidx.compose.animation.core.animateFloatAsState(LoadScreen.progress.coerceIn(0f, 1f), tween(500), label = "d")
    androidx.compose.foundation.Canvas(modifier.alpha(enter.value.coerceIn(0f, 1f))) {
        val k = size.minDimension / 200f
        translate(top = (bob.value - 0.5f) * 3f * k + (1f - enter.value) * 60f * k) {
            scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                fun stroke(w: Float) = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round)
                fun glow(paths: List<androidx.compose.ui.graphics.Path>, w0: Float = 2.8f) {
                    for ((w, a) in listOf(w0 + 8f to 0.10f, w0 + 4f to 0.22f, w0 to 1f)) paths.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w)) }
                }
                val metal = androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to Color(0xB3C8FF7A), 0.55f to Color(0x734F9A22), 1f to Color(0xE60C1E07),
                    center = androidx.compose.ui.geometry.Offset(80f, 60f), radius = 150f)
                P.metal.forEach { drawPath(it, metal) }
                // Antenna bulb glow.
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFFF0FFD6).copy(alpha = bulb.value), Color(0x00A8EC4C)),
                    center = androidx.compose.ui.geometry.Offset(100f, 10f), radius = 12f), 12f, androidx.compose.ui.geometry.Offset(100f, 10f))
                // Shine spots.
                rotate(-12f, pivot = androidx.compose.ui.geometry.Offset(82f, 38f)) {
                    drawOval(Color(0x73F0FFD6), androidx.compose.ui.geometry.Offset(72f, 34f), androidx.compose.ui.geometry.Size(20f, 8f))
                }
                rotate(-20f, pivot = androidx.compose.ui.geometry.Offset(72f, 104f)) {
                    drawOval(Color(0x59F0FFD6), androidx.compose.ui.geometry.Offset(64f, 100f), androidx.compose.ui.geometry.Size(16f, 8f))
                }
                glow(P.lines); glow(P.thin, 1.8f)
                // Waving arm.
                rotate(wave.value * 12f, pivot = androidx.compose.ui.geometry.Offset(142f, 106f)) { glow(P.armR) }
                // Eyes: right one winks.
                drawPath(P.pupilL, color)
                if (winking) glow(listOf(P.wink), 3f) else drawPath(P.pupilR, color)
                P.dots.forEach { drawPath(it, color) }
                // Chest dial needle follows the loading bar (left → right across the top).
                val ang = Math.toRadians(180.0 + 180.0 * dial)
                val end = androidx.compose.ui.geometry.Offset(100f + 10f * kotlin.math.cos(ang).toFloat(), 120f + 10f * kotlin.math.sin(ang).toFloat())
                for ((w, a) in listOf(8f to 0.15f, 2.6f to 1f)) drawLine(color.copy(alpha = a), androidx.compose.ui.geometry.Offset(100f, 120f), end, w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(color, 2.2f, androidx.compose.ui.geometry.Offset(100f, 120f))
            }
        }
    }
}
