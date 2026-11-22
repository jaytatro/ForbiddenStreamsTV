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
            // Nov 21: our own mascot — an old-school newsboy kid, waving and winking while it loads.
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
 * Nov 21: our own mascot — "Newsie", a 1930s-style newsboy (flat cap, bow tie, suspenders), shaded so he
 * looks rounded. His signature move: he tips his cap and winks. All in a 200 × 200 box, lit from the top-left.
 */
private object Newsie {
    // Cap (tips with the hand)
    val capLines = listOf(
        "M58 82 Q50 52 78 42 Q100 34 124 42 Q150 52 142 80", "M58 82 Q100 72 142 80",
        "M70 80 Q100 92 130 79 Q118 90 100 92 Q80 92 70 80", "M82 44 Q92 60 100 76 M118 44 Q110 60 100 76", circ(100f, 38f, 3f),
    )
    const val CAP = "M58 82 Q50 52 78 42 Q100 34 124 42 Q150 52 142 80 Q100 72 58 82 Z"
    const val BRIM = "M70 80 Q100 92 130 79 Q118 90 100 92 Q80 92 70 80 Z"
    // Hand on the brim (moves with the cap)
    const val HAND = "M130 90 Q126 78 134 72 Q142 66 150 74 Q156 84 148 92 Q140 98 130 90 Z"
    val handLines = listOf(HAND)
    val handThin = listOf("M133 78 Q139 76 143 80 M131 84 Q138 83 142 87")
    // Everything else
    val bodyLines = listOf(
        "M64 80 Q62 130 100 134 Q138 130 136 80",                                                // head
        "M64 96 Q52 98 56 110 Q60 116 66 112", "M136 96 Q148 98 144 110 Q140 116 134 112",       // ears
        "M76 96 Q84 92 92 95",                                                                   // left brow (right one animates)
        "M100 106 Q104 112 98 114",                                                              // nose
        "M92 134 V142 M108 134 V142", "M80 144 L92 142 L100 152 L108 142 L120 144",               // neck, collar
        "M100 152 L88 146 L88 158 Z M100 152 L112 146 L112 158 Z",                                // bow tie
        "M80 144 Q58 150 54 198", "M80 152 L78 198 M120 152 L122 198",                           // shoulder, suspenders
        circ(78f, 186f, 2.5f), circ(122f, 186f, 2.5f),
        "M146 178 Q166 140 152 96", "M126 156 Q140 128 134 96", "M131 100 Q143 104 155 98",       // raised arm + cuff
    )
    // Nov 21 (v2): a folded newspaper held at his side, hair peeking out under the cap.
    const val PAPER = "M40 150 L70 140 L82 180 L52 190 Z"
    val paperLines = listOf("M40 150 L70 140 L82 180 L52 190 Z", "M80 146 Q62 150 62 160")
    val paperThin = listOf("M46 156 L66 149 M48 162 L60 158 M50 168 L58 165", "M64 82 Q59 90 65 93 M136 82 Q141 90 135 93")
    val paperBold = listOf("M47 151 L69 143")
    const val LEFT_HAND = "M60 168 Q58 160 66 158 Q74 158 74 166 Q74 174 66 175 Q60 175 60 168 Z"
    const val BROW_R = "M108 95 Q116 92 124 96"
    const val SMIRK = "M88 121 Q102 126 113 119 Q116 117 117 114"
    const val SMIRK_BIG = "M86 120 Q102 129 116 118 Q119 116 120 112"
    const val BLINK_L = "M78 105 Q84 101 90 105"
    val dots = listOf(circ(76f, 114f, 1.4f), circ(81f, 117f, 1.4f), circ(119f, 117f, 1.4f), circ(124f, 114f, 1.4f))
    const val BODY = "M80 144 Q58 150 54 198 L146 198 L146 178 Q142 150 120 144 L108 142 L100 152 L92 142 Z"
    const val SLEEVE = "M146 178 Q166 140 152 96 L134 96 Q140 128 126 156 Z"
    const val NECK = "M92 132 L92 144 L100 152 L108 144 L108 132 Z"
    const val FACE = "M64 80 Q62 130 100 134 Q138 130 136 80 Q100 72 64 80 Z"
    const val EARS = "M64 96 Q52 98 56 110 Q60 116 66 112 Z M136 96 Q148 98 144 110 Q140 116 134 112 Z"
    const val CAP_SHADOW = "M66 84 Q100 96 134 83 Q132 92 100 98 Q70 94 66 84 Z"
    const val FACE_SHADE = "M120 134 Q134 120 136 96 Q140 128 104 136 Z"
    const val TIE = "M100 152 L88 146 L88 158 Z M100 152 L112 146 L112 158 Z"
    val eyeL = circ(84f, 104f, 4.5f)
    val eyeR = circ(116f, 104f, 4.5f)
    const val WINK = "M110 105 Q116 99 122 105"
}

/** Newsie, glowing: every ~2.4 s he tips his cap and winks; he bobs gently the rest of the time. */
@Composable
private fun TvGuy(color: Color, modifier: Modifier) {
    fun parse(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
    val P = androidx.compose.runtime.remember {
        object {
            val capLines = Newsie.capLines.map { parse(it) }
            val handLines = Newsie.handLines.map { parse(it) }
            val handThin = Newsie.handThin.map { parse(it) }
            val bodyLines = Newsie.bodyLines.map { parse(it) }
            val dots = Newsie.dots.map { parse(it) }
            val cap = parse(Newsie.CAP); val brim = parse(Newsie.BRIM); val hand = parse(Newsie.HAND)
            val body = parse(Newsie.BODY); val sleeve = parse(Newsie.SLEEVE); val neck = parse(Newsie.NECK)
            val face = parse(Newsie.FACE); val ears = parse(Newsie.EARS); val capShadow = parse(Newsie.CAP_SHADOW)
            val faceShade = parse(Newsie.FACE_SHADE); val tie = parse(Newsie.TIE)
            val eyeL = parse(Newsie.eyeL); val eyeR = parse(Newsie.eyeR); val wink = parse(Newsie.WINK)
            val paper = parse(Newsie.PAPER); val paperLines = Newsie.paperLines.map { parse(it) }
            val paperThin = Newsie.paperThin.map { parse(it) }; val paperBold = Newsie.paperBold.map { parse(it) }
            val leftHand = parse(Newsie.LEFT_HAND); val browR = parse(Newsie.BROW_R)
            val smirk = parse(Newsie.SMIRK); val smirkBig = parse(Newsie.SMIRK_BIG); val blinkL = parse(Newsie.BLINK_L)
        }
    }
    // The tip: 0 → 1 → 0 over ~0.6 s; the wink rides on the top of it.
    val tip = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_400)
            tip.animateTo(1f, tween(220))
            delay(260)
            tip.animateTo(0f, tween(320))
        }
    }
    // He pops up into view when the loading screen opens, and now and then blinks.
    val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 160f)) }
    var blink by androidx.compose.runtime.remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(1_300); blink = true; delay(140); blink = false; delay(2_900) } }
    val bob = rememberInfiniteTransition(label = "bob").animateFloat(0f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "b")
    androidx.compose.foundation.Canvas(modifier.alpha(enter.value.coerceIn(0f, 1f))) {
        val k = size.minDimension / 200f
        val t = tip.value
        val winking = t > 0.6f
        val closedBoth = blink && t < 0.1f
        translate(top = (bob.value - 0.5f) * 3f * k + (1f - enter.value) * 60f * k) {
            scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                fun stroke(w: Float) = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round)
                fun rg(cx: Float, cy: Float, r: Float, vararg stops: Pair<Float, Color>) =
                    androidx.compose.ui.graphics.Brush.radialGradient(*stops, center = androidx.compose.ui.geometry.Offset(cx, cy), radius = r)
                val skin = rg(92f, 91f, 62f, 0f to Color(0xBFC8FF7A), 0.55f to Color(0x736FBF2E), 1f to Color(0xD9173A0C))
                val cloth = rg(86f, 153f, 85f, 0f to Color(0x998FD640), 1f to Color(0xD90C1E07))
                fun glow(paths: List<androidx.compose.ui.graphics.Path>, thinPaths: List<androidx.compose.ui.graphics.Path> = emptyList()) {
                    for ((w, a) in listOf(11f to 0.10f, 7f to 0.22f, 3f to 1f)) {
                        paths.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w)) }
                        thinPaths.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w * 0.6f)) }
                    }
                }
                // Body, face (shaded), then lines.
                drawPath(P.body, cloth); drawPath(P.neck, skin); drawPath(P.face, skin); drawPath(P.ears, skin)
                drawPath(P.faceShade, Color(0x8006110A))
                drawPath(P.tie, Color(0x73A8EC4C))
                for (cx in listOf(76f, 124f)) drawCircle(rg(cx, 116f, 9f, 0f to Color(0x59C8FF7A), 1f to Color(0x00C8FF7A)), 9f, androidx.compose.ui.geometry.Offset(cx, 116f))
                drawPath(P.sleeve, cloth)
                val rClosed = winking || closedBoth
                glow(P.bodyLines + listOfNotNull(if (rClosed) P.wink else P.eyeR, if (closedBoth) P.blinkL else null,
                    if (t > 0.3f) P.smirkBig else P.smirk))
                // Right eyebrow goes up with the wink.
                translate(top = -3.5f * t) { glow(listOf(P.browR)) }
                if (!closedBoth) {
                    drawPath(P.eyeL, color)
                    drawCircle(Color(0xFF06110A), 1.5f, androidx.compose.ui.geometry.Offset(85.5f, 102.5f))
                }
                P.dots.forEach { drawPath(it, color) }
                if (!rClosed) { drawPath(P.eyeR, color); drawCircle(Color(0xFF06110A), 1.5f, androidx.compose.ui.geometry.Offset(117.5f, 102.5f)) }
                // The newspaper and the hand holding it.
                drawPath(P.paper, Color(0x38E9FFD0))
                glow(P.paperLines, P.paperThin)
                for ((w, a) in listOf(9f to 0.12f, 3.6f to 1f)) P.paperBold.forEach { drawPath(it, color.copy(alpha = a), style = stroke(w)) }
                drawPath(P.leftHand, skin)
                glow(listOf(P.leftHand))
                // Cap + hand tip together: lift and tilt back around the brim's right end.
                translate(top = -5f * t) {
                    rotate(-9f * t, pivot = androidx.compose.ui.geometry.Offset(132f, 82f)) {
                        if (t < 0.3f) drawPath(P.capShadow, Color(0x9906110A))
                        drawPath(P.cap, rg(85f, 46f, 70f, 0f to Color(0xCCA8EC4C), 0.65f to Color(0x993D7A1C), 1f to Color(0xE60C1E07)))
                        drawPath(P.brim, rg(90f, 82f, 40f, 0f to Color(0xCCA8EC4C), 1f to Color(0xE60C1E07)))
                        rotate(-18f, pivot = androidx.compose.ui.geometry.Offset(84f, 52f)) {
                            drawOval(Color(0x73F0FFD6), topLeft = androidx.compose.ui.geometry.Offset(70f, 46f), size = androidx.compose.ui.geometry.Size(28f, 12f))
                        }
                        glow(P.capLines)
                        drawPath(P.hand, skin)
                        glow(P.handLines, P.handThin)
                    }
                }
            }
        }
    }
}
