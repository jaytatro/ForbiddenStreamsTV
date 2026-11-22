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
            // Nov 21: our own mascot — Cosmo, a 1950s space-cadet kid, waving and winking while it loads.
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
 * Nov 21: our own mascot — "Cosmo", a 1950s space-cadet kid (our own design): bubble helmet with an antenna,
 * fringe haircut, ringed-planet badge on his suit, waving glove. Shaded to look rounded; 200 × 200 box.
 */
private object Cosmo {
    val lines = listOf(
        circ(100f, 78f, 52f), "M64 130 A36 8 0 1 0 136 130 A36 8 0 1 0 64 130",                // helmet, collar ring
        "M128 34 L138 16", circ(140f, 12f, 4f),                                                  // antenna
        "M68 84 Q68 116 100 118 Q132 116 132 84 Q132 52 100 52 Q68 52 68 84 Z",                  // head
        "M68 80 Q78 68 90 72 Q100 64 110 72 Q122 68 132 80",                                     // fringe
        "M66 84 Q60 86 62 94 Q64 98 68 96 M134 84 Q140 86 138 94 Q136 98 132 96",                // ears
        "M81 79 Q88 75 95 78", "M99 92 Q102 96 98 98", "M88 103 Q100 113 112 103",               // brow, nose, smile
        "M66 136 Q58 150 56 198 M134 136 Q140 150 144 172",                                      // suit
        circ(100f, 166f, 7f),                                                                     // badge planet
    )
    const val RING = "M86 170 Q100 164 114 162"
    val arm = listOf("M142 162 Q164 150 166 124", "M158 124 Q156 108 166 106 Q176 106 176 118 Q176 128 166 130 Q160 130 158 124 Z")
    val armThin = listOf("M136 152 Q154 144 156 124", "M157 126 L175 122")
    const val BROW_R = "M105 78 Q112 75 119 79"
    const val WINK = "M106 87 Q112 82 118 87"
    const val SUIT = "M66 134 Q58 150 56 198 L144 198 L144 172 Q140 150 134 134 Q100 146 66 134 Z"
    const val SLEEVE = "M142 162 Q164 150 166 124 L156 124 Q154 144 136 152 Z"
    const val FACE = "M68 84 Q68 116 100 118 Q132 116 132 84 Q132 52 100 52 Q68 52 68 84 Z"
    const val HAIR = "M68 80 Q68 52 100 52 Q132 52 132 80 Q122 68 110 72 Q100 64 90 72 Q78 68 68 80 Z"
    const val GLOVE = "M158 124 Q156 108 166 106 Q176 106 176 118 Q176 128 166 130 Q160 130 158 124 Z"
    const val GLARE = "M66 58 Q76 36 100 30"
    val eyeL = circ(88f, 86f, 4.5f); val eyeR = circ(112f, 86f, 4.5f)
}

/** Cosmo, glowing: pops in, bobs, waves, winks (eyebrow up) every ~2.4 s; antenna light blinks; helmet glints. */
@Composable
private fun TvGuy(color: Color, modifier: Modifier) {
    fun parse(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
    val P = androidx.compose.runtime.remember {
        object {
            val lines = Cosmo.lines.map { parse(it) }; val ring = parse(Cosmo.RING)
            val arm = Cosmo.arm.map { parse(it) }; val armThin = Cosmo.armThin.map { parse(it) }
            val browR = parse(Cosmo.BROW_R); val wink = parse(Cosmo.WINK)
            val suit = parse(Cosmo.SUIT); val sleeve = parse(Cosmo.SLEEVE); val face = parse(Cosmo.FACE)
            val hair = parse(Cosmo.HAIR); val glove = parse(Cosmo.GLOVE); val glare = parse(Cosmo.GLARE)
            val eyeL = parse(Cosmo.eyeL); val eyeR = parse(Cosmo.eyeR)
        }
    }
    val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 160f)) }
    val winkA = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { while (true) { delay(2_400); winkA.animateTo(1f, tween(160)); delay(320); winkA.animateTo(0f, tween(220)) } }
    val inf = rememberInfiniteTransition(label = "cosmo")
    val bob = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "b")
    val wave = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "w")
    val blink = inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "l")
    val glint = inf.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "g")
    androidx.compose.foundation.Canvas(modifier.alpha(enter.value.coerceIn(0f, 1f))) {
        val k = size.minDimension / 200f
        val t = winkA.value
        translate(top = (bob.value - 0.5f) * 3f * k + (1f - enter.value) * 60f * k) {
            scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                fun stroke(w: Float) = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round)
                fun glow(paths: List<androidx.compose.ui.graphics.Path>, w0: Float = 2.8f) {
                    for ((w, a) in listOf(w0 + 8f to 0.10f, w0 + 4f to 0.22f, w0 to 1f)) paths.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w)) }
                }
                fun rg(cx: Float, cy: Float, r: Float, vararg stops: Pair<Float, Color>) =
                    androidx.compose.ui.graphics.Brush.radialGradient(*stops, center = androidx.compose.ui.geometry.Offset(cx, cy), radius = r)
                val skin = rg(90f, 74f, 50f, 0f to Color(0xCCC8FF7A), 0.6f to Color(0x806FBF2E), 1f to Color(0xE6173A0C))
                val suit = rg(80f, 140f, 90f, 0f to Color(0xA68FD640), 1f to Color(0xE60C1E07))
                // Fills: suit, face, hair, cheeks, helmet glass.
                drawPath(P.suit, suit)
                drawPath(P.face, skin)
                drawPath(P.hair, rg(92f, 56f, 50f, 0f to Color(0xD97FC23A), 1f to Color(0xF20C1E07)))
                for (cx in listOf(80f, 120f)) drawCircle(rg(cx, 98f, 8f, 0f to Color(0x66C8FF7A), 1f to Color(0x00C8FF7A)), 8f, androidx.compose.ui.geometry.Offset(cx, 98f))
                drawCircle(rg(82f, 62f, 60f, 0f to Color(0x29E9FFD0), 0.8f to Color(0x0FA8EC4C), 1f to Color(0x40A8EC4C)), 52f, androidx.compose.ui.geometry.Offset(100f, 78f))
                // Lines.
                glow(P.lines); glow(listOf(P.ring), 2.2f)
                translate(top = -3f * t) { glow(listOf(P.browR)) }
                if (t > 0.5f) glow(listOf(P.wink), 3f)
                drawPath(P.eyeL, color); drawCircle(Color(0xFF06110A), 1.5f, androidx.compose.ui.geometry.Offset(89.5f, 84.5f))
                if (t <= 0.5f) { drawPath(P.eyeR, color); drawCircle(Color(0xFF06110A), 1.5f, androidx.compose.ui.geometry.Offset(113.5f, 84.5f)) }
                // Waving arm (from the shoulder).
                rotate(wave.value * 10f, pivot = androidx.compose.ui.geometry.Offset(140f, 160f)) {
                    drawPath(P.sleeve, suit); drawPath(P.glove, skin)
                    glow(P.arm); glow(P.armThin, 2.2f)
                }
                // Antenna light + helmet glint.
                drawCircle(rg(140f, 12f, 10f, 0f to Color(0xFFF0FFD6).copy(alpha = blink.value), 1f to Color(0x00A8EC4C)), 10f, androidx.compose.ui.geometry.Offset(140f, 12f))
                drawPath(P.glare, Color(0xFFF0FFD6).copy(alpha = glint.value), style = stroke(5f))
                drawCircle(Color(0xFFF0FFD6).copy(alpha = glint.value * 0.8f), 3f, androidx.compose.ui.geometry.Offset(132f, 44f))
            }
        }
    }
}
