package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.player.PlayItem
import com.forbiddenstreams.tv.player.Playback
import com.forbiddenstreams.tv.player.PlayerHolder
import com.forbiddenstreams.tv.reminders.Reminder
import com.forbiddenstreams.tv.reminders.Reminders
import com.forbiddenstreams.tv.sports.League
import kotlinx.coroutines.delay
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.launch

enum class Sec { CHANNELS, MOVIES, SHOWS, SPORTS, SPLIT, RECORDINGS, SETTINGS }

private val railIcon = mapOf(
    Sec.CHANNELS to RailIcon.CHANNELS, Sec.MOVIES to RailIcon.MOVIES, Sec.SHOWS to RailIcon.SHOWS,
    Sec.SPORTS to RailIcon.SPORTS, Sec.SPLIT to RailIcon.SPLIT, Sec.RECORDINGS to RailIcon.RECORDINGS, Sec.SETTINGS to RailIcon.SETTINGS,
)

/** Settings box: MenuScreen section number to label (Recordings moved to its own rail icon). */
val SETTINGS_SECTIONS = listOf(
    1 to "⏰  Reminders", 2 to "🗂  Categories", 6 to "⭐  Favorite Teams", 7 to "Buttons",
    3 to "⟳  Refresh content", 8 to "⬆  Check for updates", 4 to "👤  Account", 5 to "⎋  Log out",
)

/**
 * Left rail (logo, line icons for Channels, Movies, TV Shows, Sports, Settings, clock).
 * Moving onto an icon opens a small box next to it (same black/green look as the rail):
 *  Channels → categories; picking one closes the box and shows that category's guide.
 *  Movies / TV Shows → Search, Recently Watched, categories; picking one shows the posters.
 *  Sports → NFL, NBA, NHL, MLB; highlighting one shows its games right away.
 *  Settings → settings sections.
 * BACK on any screen comes back to the rail; BACK on the rail = "click back one more time".
 */
@Composable
fun HomeScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val density = LocalDensity.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }

    var section by rememberSaveable { mutableStateOf(Sec.CHANNELS) }      // what the screen shows
    var hover by remember { mutableStateOf<Sec?>(Sec.CHANNELS) }         // which box is open
    var railFocus by remember { mutableStateOf(false) }
    var flyFocus by remember { mutableStateOf(false) }
    var contentFocus by remember { mutableStateOf(false) }
    // Selections made in the boxes.
    var chGroup by rememberSaveable { mutableStateOf<String?>(null) }    // null = no guide yet (preview)
    var movieCat by rememberSaveable { mutableStateOf<String?>(null) }
    var showCat by rememberSaveable { mutableStateOf<String?>(null) }
    var league by rememberSaveable { mutableStateOf(League.NFL) }
    var favTeams by rememberSaveable { mutableStateOf(false) }        // Sports › Favorite Teams
    var openGame by remember { mutableStateOf<String?>(null) }         // from the favorite-team alert
    var setSec by rememberSaveable { mutableIntStateOf(1) }
    var confirmLogout by remember { mutableStateOf(false) }

    val iconReq = remember { Sec.entries.associateWith { FocusRequester() } }
    val flyEntry = remember { FocusRequester() }
    val contentReq = remember { FocusRequester() }
    val iconY = remember { mutableStateMapOf<Sec, Float>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { delay(200); try { iconReq.getValue(Sec.CHANNELS).requestFocus() } catch (e: Exception) { } }
    remember { ButtonPrefs.load(context); true }
    // First start after install: walk through the buttons.
    var showTour by remember { mutableStateOf(!ButtonPrefs.tourSeen(context)) }
    // Keep the box (and its screensaver) from stopping the preview while something plays.
    val playingNow = PlayerHolder.current != null
    LaunchedEffect(playingNow) {
        val w = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        if (playingNow) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // Movies, TV Shows and Sports don't show the live preview: opening them stops it.
    // Also when coming back from full screen to one of them (e.g. BACK after watching a game).
    LaunchedEffect(section, PlayerHolder.fullScreen) {
        if (!PlayerHolder.fullScreen && section in setOf(Sec.MOVIES, Sec.SHOWS, Sec.SPORTS) && PlayerHolder.isMiniActive) PlayerHolder.stop()
    }

    // Sports list order / hidden leagues (hold OK on a league in the Sports list).
    val sportsV = com.forbiddenstreams.tv.sports.SportPrefs.version
    val sportsList = remember(sportsV) { com.forbiddenstreams.tv.sports.SportPrefs.order(context) }
    LaunchedEffect(sportsList) { if (league !in sportsList && sportsList.isNotEmpty()) league = sportsList.first() }

    // Category highlighted in the Channels list; shown after a short pause so scrolling stays smooth.
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingGroup) {
        val k = pendingGroup ?: return@LaunchedEffect
        delay(300)
        chGroup = k
        if (k != Group.Search.key) prefs.lastGroup = k
        section = Sec.CHANNELS
    }

    /** Opens the current screen's category list with the cursor on the category in use. */
    fun openList() {
        hover = section
        scope.launch {
            try { iconReq.getValue(section).requestFocus() } catch (e: Exception) { }
            delay(60)
            try { flyEntry.requestFocus() } catch (e: Exception) { }
        }
    }
    // ◀◀ in the live player: back here with the Channels list open.
    LaunchedEffect(PlayerHolder.sideBarRequest) {
        if (PlayerHolder.sideBarRequest == 0) return@LaunchedEffect
        section = Sec.CHANNELS
        // The home screen may still be coming back to the front: try a few times.
        repeat(4) { delay(250); openList() }
    }
    // When ◀ takes the cursor out of a screen onto the side bar, go to the category list instead.
    var contentLeftAt by remember { mutableLongStateOf(0L) }
    var backToRail by remember { mutableStateOf(false) }

    fun toContent() {
        scope.launch {
            delay(80)
            try { contentReq.requestFocus() } catch (e: Exception) { }
        }
    }

    // BACK: from a screen → back to the rail; on the rail → exit hint, then close.
    val activity = context as? android.app.Activity
    var exitHint by remember { mutableStateOf(false) }
    BackHandler {
        if (exitHint) {
            PlayerHolder.stop()
            activity?.finish()
        } else {
            exitHint = true
        }
    }
    BackHandler(enabled = contentFocus) {
        backToRail = true
        try { iconReq.getValue(section).requestFocus() } catch (e: Exception) { }
    }
    LaunchedEffect(exitHint) { if (exitHint) { delay(2500); exitHint = false } }

    @Composable
    fun CategoryColumn() {
    val open = hover
    if (open != null && open != Sec.SPLIT && (railFocus || flyFocus)) {
        val back = iconReq.getValue(open)
        Box(
            Modifier.fillMaxHeight().background(Color.Black)
                .onFocusChanged { flyFocus = it.hasFocus }
                .padding(horizontal = 6.dp, vertical = 10.dp),
        ) {
            when (open) {
                Sec.CHANNELS -> {
                    val groups = remember(Session.liveCats, CategoryPrefs.version) {
                        groupsFor(CategoryPrefs.visible(context, Session.liveCats.orEmpty()))
                    }
                    if (groups.size <= 4) FlyMessage("Loading channels…", flyEntry, back)
                    else FlyList("CHANNELS", groups.map { it.key to it.title }, chGroup, flyEntry, back,
                        // ▶ = into the screen (the live preview on start-up, or the guide); the box closes.
                        onRight = { section = Sec.CHANNELS; toContent() },
                        // Hold OK on a category = move it with ▲ ▼; OK again stops (no pop-up).
                        canMove = { k -> k.startsWith("c_") },
                        onMove = { k, dir ->
                            CategoryPrefs.moveVisible(context, Session.liveCats.orEmpty(), k.removePrefix("c_"), dir)
                        },
                        // Highlighting a category shows its guide next to the list (like the photo).
                        // On start-up the first highlight is skipped so the live preview stays.
                        onFocusItem = { k ->
                            if (!(chGroup == null && k == Group.Search.key)) pendingGroup = k
                        },
                        onPick = { k ->
                        pendingGroup = null
                        chGroup = k
                        if (k != Group.Search.key) prefs.lastGroup = k
                        section = Sec.CHANNELS
                        toContent()
                    })
                }
                Sec.MOVIES, Sec.SHOWS -> {
                    val movies = open == Sec.MOVIES
                    val cats by produceState<List<com.forbiddenstreams.tv.data.Category>?>(null, open) {
                        value = try { if (movies) Session.source?.vodCategories() else Session.source?.seriesCategories() } catch (e: Exception) { emptyList() }
                    }
                    val c = cats
                    if (c == null) FlyMessage("Loading…", flyEntry, back)
                    else FlyList(
                        if (movies) "MOVIES" else "TV SHOWS",
                        listOf(VOD_SEARCH to "🔍  Search", VOD_RECENT to "🕘  Recently Watched") + c.map { it.id to it.name },
                        if (movies) movieCat else showCat, flyEntry, back,
                        // Highlighting a category shows its movies / shows on the right.
                        onFocusItem = { k ->
                            if (k != VOD_SEARCH) {
                                if (movies) { movieCat = k; section = Sec.MOVIES } else { showCat = k; section = Sec.SHOWS }
                            }
                        },
                        onPick = { k ->
                            if (movies) { movieCat = k; section = Sec.MOVIES } else { showCat = k; section = Sec.SHOWS }
                            toContent()
                        },
                    )
                }
                Sec.SPORTS -> SportsDockList(
                    sportsList, league, flyEntry, back,
                    onFocusLeague = { lg -> favTeams = false; league = lg; section = Sec.SPORTS },
                    onPick = { lg ->
                        favTeams = false; league = lg
                        section = Sec.SPORTS
                        toContent()
                    },
                )
                Sec.RECORDINGS -> {
                    // Your recordings, newest first. OK plays one; "All recordings" opens the full screen (hold OK there = delete).
                    val recs = remember(railFocus, flyFocus) { com.forbiddenstreams.tv.recording.Recordings.all(context) }
                    FlyList(
                        "RECORDINGS",
                        listOf("__all__" to "📼  All recordings") + recs.map { r ->
                            "${r.id}" to (when (r.status) { "recording" -> "● "; "scheduled" -> "⏰ "; "failed" -> "✖ "; else -> "" } +
                                "${r.title} · ${Fmt.day(r.start)} ${Fmt.time(r.start)}")
                        } + (if (recs.isEmpty()) listOf("__none__" to "No recordings yet") else emptyList()),
                        null, flyEntry, back,
                        onPick = { k ->
                            when (k) {
                                "__all__", "__none__" -> { section = Sec.RECORDINGS; toContent() }
                                else -> {
                                    val r = recs.firstOrNull { "${it.id}" == k }
                                    val f = r?.let { java.io.File(it.file) }
                                    if (r != null && f != null && f.exists() && f.length() > 0) {
                                        Playback.one(context, PlayItem(r.title, android.net.Uri.fromFile(f).toString(), live = false, subtitle = r.channelName))
                                    } else {
                                        toast(context, if (r?.status == "scheduled") "Not recorded yet — starts ${Fmt.time(r.start)}" else "Nothing recorded yet")
                                    }
                                }
                            }
                        },
                    )
                }
                Sec.SPLIT -> Unit // no box
                Sec.SETTINGS -> FlyList(
                    "SETTINGS", SETTINGS_SECTIONS.map { (i, t) -> "$i" to t }, "$setSec", flyEntry, back,
                    icons = mapOf("7" to RailIcon.REMOTE), // TV remote drawn as a line icon
                    onPick = { k ->
                        when (val i = k.toInt()) {
                            3 -> { Session.refresh(context); toast(context, "Updating channels, movies and shows…") }
                            5 -> { confirmLogout = true }
                            8 -> { toast(context, "Checking for updates…"); UpdateState.check(context, scope, manual = true) }
                            else -> { setSec = i; section = Sec.SETTINGS; toContent() }
                        }
                    },
                )
            }
        }
    }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // ---- Rail ----
            Column(
                Modifier.width(RAIL_W).fillMaxHeight().background(RAIL_BG)
                    .onFocusChanged { railFocus = it.hasFocus }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp))
                Spacer(Modifier.weight(1f))
                for (s in listOf(Sec.CHANNELS, Sec.MOVIES, Sec.SHOWS, Sec.SPORTS, Sec.RECORDINGS)) {
                    RailButton(s, selected = section == s, req = iconReq.getValue(s), flyEntry = flyEntry,
                        onY = { iconY[s] = it },
                        onFocus = {
                            hover = s
                            val fromScreen = android.os.SystemClock.uptimeMillis() - contentLeftAt < 400
                            if (fromScreen && !backToRail) { contentLeftAt = 0L; openList() }
                            backToRail = false
                        },
                        onClick = {
                            hover = s
                            when (s) {
                                Sec.RECORDINGS -> try { flyEntry.requestFocus() } catch (e: Exception) { }
                                Sec.SPLIT -> {
                                    if (!PlayerHolder.openSplitScreen(context)) toast(context, "Start a channel first, then press Split Screen")
                                }
                                else -> try { flyEntry.requestFocus() } catch (e: Exception) { }
                            }
                        })
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.weight(1f))
                RailButton(Sec.SETTINGS, selected = section == Sec.SETTINGS, req = iconReq.getValue(Sec.SETTINGS), flyEntry = flyEntry,
                    onY = { iconY[Sec.SETTINGS] = it },
                    onFocus = {
                        hover = Sec.SETTINGS
                        val fromScreen = android.os.SystemClock.uptimeMillis() - contentLeftAt < 400
                        if (fromScreen && !backToRail) { contentLeftAt = 0L; openList() }
                        backToRail = false
                    },
                    onClick = { hover = Sec.SETTINGS; try { flyEntry.requestFocus() } catch (e: Exception) { } })
                Spacer(Modifier.height(14.dp))
                Text(Fmt.time(now).substringBefore(' '), color = FS.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(Fmt.time(now).substringAfter(' '), color = FS.TextDim, fontSize = 10.sp)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF12301A)))
            // ---- Category list: a black column docked next to the rail (the screen moves over) ----
            CategoryColumn()
            // ---- Screen ----
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged {
                        if (contentFocus && !it.hasFocus) contentLeftAt = android.os.SystemClock.uptimeMillis()
                        contentFocus = it.hasFocus
                    }
                    .focusRequester(contentReq)
                    .focusGroup(),
            ) {
                CompositionLocalProvider(LocalTopMenuFocus provides iconReq.getValue(section)) {
                    when (section) {
                        Sec.CHANNELS -> ChannelsScreen(chGroup, onGroup = { chGroup = it }, active = !(railFocus || flyFocus),
                            onCategories = { openList() })
                        Sec.MOVIES -> MoviesScreen(movieCat, onSelect = { movieCat = it })
                        Sec.SHOWS -> SeriesScreen(showCat, onSelect = { showCat = it })
                        Sec.SPORTS -> if (favTeams) FavoriteTeamsScreen() else SportsScreen(league, openGame, onOpened = { openGame = null }, onLeft = { openList() })
                        Sec.SPLIT -> ChannelsScreen(chGroup, onGroup = { chGroup = it })
                        Sec.RECORDINGS -> MenuScreen(0)
                        Sec.SETTINGS -> when (setSec) {
                            6 -> FavoriteTeamsScreen()
                            7 -> ButtonSetupScreen()
                            else -> MenuScreen(setSec)
                        }
                    }
                }
                // Moving through the side bar (or another icon's list): just the logo, not the old screen.
                // The screen stays loaded underneath so it comes back as it was.
                val logoOnly = (railFocus || (flyFocus && hover != section)) &&
                    !(section == Sec.CHANNELS && chGroup == null) // start-up: keep the live preview
                if (logoOnly) Box(Modifier.matchParentSize().background(FS.Bg))
            }
        }
        // The logo sits in one fixed spot on the screen (not centered in the area next to the
        // list, which changes width from list to list and made the logo jump around).
        val logoShown = (railFocus || (flyFocus && hover != section)) && !(section == Sec.CHANNELS && chGroup == null)
        if (logoShown) {
            Image(
                painterResource(R.drawable.logo), null,
                Modifier.align(Alignment.CenterEnd).padding(end = 110.dp).size(400.dp),
            )
        }

        FavoriteGameAlert(onWatch = { lg, id ->
            favTeams = false
            league = lg
            openGame = id
            section = Sec.SPORTS
            toContent()
        })

        if (exitHint) {
            Text(
                "Click back one more time to close",
                color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                    .background(FS.Green, RoundedCornerShape(50))
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
    if (confirmLogout) {
        ChoiceDialog(
            title = "Log out?",
            subtitle = "You'll need your login details to sign back in.",
            options = listOf(
                "Log out" to { confirmLogout = false; onLogout() },
                "Cancel" to { confirmLogout = false },
            ),
            onDismiss = { confirmLogout = false },
        )
    }
    ReminderWatcher()
    if (!showTour) UpdatePrompt()
    if (showTour) Walkthrough(onDone = { ButtonPrefs.setTourSeen(context); showTour = false })
}

private val RAIL_W = 60.dp
private val RAIL_BG = Color(0xFF0F160F)
const val VOD_SEARCH = "__search__"
const val VOD_RECENT = "__recent__"

@Composable
private fun RailButton(
    sec: Sec,
    selected: Boolean,
    req: FocusRequester,
    flyEntry: FocusRequester,
    onY: (Float) -> Unit,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier.size(42.dp)
            .focusRequester(req)
            .focusProperties { right = flyEntry }
            .onGloballyPositioned { onY(it.positionInRoot().y) }
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF0F2A10) else RAIL_BG,
            focusedContainerColor = Color(0xFF0F2A10),
            pressedContainerColor = FS.GreenDeep,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = if (selected) androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, FS.GreenDeep), shape = shape)
                else androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, FS.Green), shape = shape),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LineIcon(railIcon.getValue(sec), 22.dp, FS.Green)
        }
    }
}

/** The list inside a rail box: green on black, sized to its longest entry; ▼ at the end wraps to the top. */
@Composable
private fun FlyList(
    title: String,
    allItems: List<Pair<String, String>>,
    selectedKey: String?,
    entry: FocusRequester,
    back: FocusRequester,
    onFocusItem: ((String) -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    icons: Map<String, RailIcon> = emptyMap(),
    onLongPick: ((String) -> Unit)? = null,
    /** Hold OK on an entry this allows = start moving it; ▲ ▼ move it ([onMove]), OK again stops. */
    canMove: ((String) -> Boolean)? = null,
    onMove: ((String, Int) -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    // Providers sometimes list a category twice; a repeated key would crash the list while scrolling.
    val items = remember(allItems) { allItems.distinctBy { it.first } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var moving by remember { mutableStateOf<String?>(null) }
    val slotReq = remember { HashMap<Int, FocusRequester>() }
    fun slot(i: Int) = slotReq.getOrPut(i) { FocusRequester() }
    BackHandler(enabled = moving != null) { moving = null }
    val top = remember { FocusRequester() }
    val selIndex = items.indexOfFirst { it.first == selectedKey }.let { if (it < 0) 0 else it }
    val state = rememberLazyListState(selIndex)
    // Wide enough for every word of the longest entry (emoji count double, icons add room).
    val longest: Int = items.maxOfOrNull { (k, label) ->
        var n = 0
        for (c in label) n += if (c.isSurrogate()) 1 else if (c.code > 0x2000) 2 else 1
        n + (if (k in icons) 3 else 0)
    } ?: 10
    val w = (longest * 12 + 64).coerceIn(210, 420).dp
    Column(Modifier.width(w)) {
        Text(title, color = FS.GreenMid, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Movable lists use row positions as keys: rows stay put and only labels swap while
            // moving, so the cursor never gets lost (that closed the list before).
            itemsIndexed(items, key = { i, it -> if (onMove != null) i else it.first }) { i, (key, label) ->
                val isMoving = moving == key
                SideItem(
                    if (isMoving) "⇅  $label" else label, selected = key == selectedKey || isMoving,
                    onClick = { if (moving != null) moving = null else onPick(key) },
                    onFocus = { if (moving == null) onFocusItem?.invoke(key) },
                    textColor = FS.Green,
                    icon = icons[key],
                    onLongClick = when {
                        onMove != null && canMove?.invoke(key) == true -> {
                            { moving = key; toast(context, "▲ ▼ to move · OK when done") }
                        }
                        onMove != null -> null
                        else -> onLongPick?.let { f -> { f(key) } }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isMoving) Modifier.border(2.dp, FS.Green, RoundedCornerShape(50)) else Modifier)
                        .then(if (onMove != null) Modifier.focusRequester(slot(i)) else Modifier)
                        .onPreviewKeyEvent { ev ->
                            if (!isMoving || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val dir = when (ev.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0
                                else -> return@onPreviewKeyEvent false
                            }
                            val to = i + dir
                            if (dir != 0 && to in items.indices && canMove?.invoke(items[to].first) == true) {
                                // Cursor first (that row already exists), then swap the entries.
                                try { slot(to).requestFocus() } catch (e: Exception) { }
                                onMove?.invoke(key, dir)
                            }
                            true
                        }
                        .then(if (i == selIndex) Modifier.focusRequester(entry) else Modifier)
                        .then(if (i == 0) Modifier.focusRequester(top) else Modifier)
                        .focusProperties { left = back }
                        .onPreviewKeyEvent { ev ->
                            if (onRight != null && ev.type == KeyEventType.KeyDown &&
                                ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { onRight(); true } else false
                        }
                        .then(
                            if (i == items.size - 1) Modifier.onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                    scope.launch { state.scrollToItem(0); delay(60); try { top.requestFocus() } catch (e: Exception) { } }
                                    true
                                } else false
                            } else Modifier
                        ),
                )
            }
        }
    }
}

@Composable
private fun FlyMessage(text: String, entry: FocusRequester, back: FocusRequester) {
    Text(
        text, color = FS.Green, fontSize = 16.sp,
        modifier = Modifier.width(220.dp).padding(14.dp).focusRequester(entry).focusProperties { left = back }.focusable(),
    )
}

/** Pops up "Starting now" when a reminder is due while the app is open. */
@Composable
private fun ReminderWatcher() {
    val context = LocalContext.current
    var showing by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (showing == null) {
                showing = Reminders.due(context).firstOrNull()
            }
            delay(20_000)
        }
    }
    val r = showing ?: return
    ChoiceDialog(
        title = "Starting now: ${r.title}",
        subtitle = "On ${r.channelName}",
        options = listOf(
            "▶ Watch now" to {
                Reminders.remove(context, r.id)
                showing = null
                Playback.one(context, PlayItem(r.channelName, r.channelUrl, live = true, subtitle = r.title))
            },
            "Dismiss" to {
                Reminders.remove(context, r.id)
                showing = null
            },
        ),
        onDismiss = { Reminders.remove(context, r.id); showing = null },
    )
}

@Composable
fun ChoiceDialog(
    title: String,
    subtitle: String? = null,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // A long-press opens this while OK is still held down. Ignore that held key (and its
    // release) so the first option isn't picked by accident; a fresh press works normally.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { ev ->
                    val ne = ev.nativeKeyEvent
                    val ok = ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        ne.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        ne.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!ok || armed) return@onPreviewKeyEvent false
                    if (ne.action == android.view.KeyEvent.ACTION_DOWN && ne.repeatCount == 0) {
                        armed = true
                        false
                    } else {
                        true
                    }
                }
                .width(520.dp)
                .background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = FS.TextDim, fontSize = 16.sp, maxLines = 4)
            Spacer(Modifier.size(6.dp))
            options.forEachIndexed { i, (label, action) ->
                FocusCard(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    Text(label, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); try { first.requestFocus() } catch (e: Exception) { } }
}


/**
 * 1 minute before a favorite team's game: a banner at the bottom of the screen.
 * OK on it opens the channel list for that game; BACK dismisses it. Hides after 3 minutes.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FavoriteGameAlert(onWatch: (League, String) -> Unit) {
    val context = LocalContext.current
    var alert by remember { mutableStateOf<Pair<League, com.forbiddenstreams.tv.sports.Game>?>(null) }
    val shown = remember { mutableSetOf<String>() }
    LaunchedEffect(Unit) {
        while (true) {
            val favs = com.forbiddenstreams.tv.sports.FavTeams.all(context)
            if (favs.isNotEmpty() && alert == null) {
                val leagues = League.entries.filter { l -> favs.any { it.startsWith(l.name + ":") } }
                val now = System.currentTimeMillis()
                for (l in leagues) {
                    val games = try { com.forbiddenstreams.tv.sports.Espn.week(l, 0, 0).values.flatten() } catch (e: Exception) { emptyList() }
                    val g = games.firstOrNull { g ->
                        g.id !in shown && g.start - now in 0..90_000 &&
                            com.forbiddenstreams.tv.sports.FavTeams.inGame(context, l, g)
                    }
                    if (g != null) { shown += g.id; alert = l to g; break }
                }
            }
            delay(30_000)
        }
    }
    val a = alert ?: return
    val (l, g) = a
    val req = remember { FocusRequester() }
    LaunchedEffect(a) { delay(150); try { req.requestFocus() } catch (e: Exception) { } }
    LaunchedEffect(a) { delay(180_000); alert = null }
    BackHandler { alert = null }
    FocusCard(
        onClick = { alert = null; onWatch(l, g.id) },
        shape = RoundedCornerShape(50),
        container = Color(0xE6050805),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).focusRequester(req),
    ) {
        Row(Modifier.padding(horizontal = 26.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⭐ ${g.away.displayName} vs. ${g.home.displayName} starts in 1 minute", color = FS.Green,
                fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Text("OK = pick a channel · BACK = dismiss", color = FS.Text, fontSize = 15.sp)
        }
    }
}


/**
 * Sports list in the side bar. Each league has a small ⇅ button next to it (▶ to reach it):
 * OK on ⇅ starts moving that league, ▲ ▼ move it, OK again (or BACK) ends the moving.
 */
@Composable
private fun SportsDockList(
    leagues: List<League>,
    selected: League,
    entry: FocusRequester,
    back: FocusRequester,
    onFocusLeague: (League) -> Unit,
    onPick: (League) -> Unit,
) {
    val context = LocalContext.current
    var moving by remember { mutableStateOf<League?>(null) }
    // One focus target per row position. Rows are NOT keyed by league on purpose: when a league
    // moves, the rows stay put and only their labels swap, so the cursor never gets lost
    // (a lost cursor closed the whole Sports list in build 47).
    val arrowReq = remember { List(League.entries.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = moving != null) { moving = null }
    Column(Modifier.width(250.dp)) {
        Text("SPORTS", color = FS.GreenMid, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            leagues.forEachIndexed { index, lg ->
                run {
                    val isMoving = moving == lg
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (isMoving) Modifier.border(2.dp, FS.Green, RoundedCornerShape(50)) else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SideItem(
                            "${lg.icon}  ${lg.label}", selected = lg == selected,
                            onClick = { onPick(lg) },
                            onFocus = { if (moving == null) onFocusLeague(lg) },
                            textColor = FS.Green,
                            modifier = Modifier.weight(1f)
                                .then(if (lg == selected) Modifier.focusRequester(entry) else Modifier)
                                .focusProperties { left = back; canFocus = moving == null },
                        )
                        // ⇅ move button
                        val shape = RoundedCornerShape(8.dp)
                        androidx.tv.material3.Surface(
                            onClick = {
                                moving = if (isMoving) null else lg
                                if (!isMoving) toast(context, "▲ ▼ to move ${lg.label} · OK when done")
                            },
                            modifier = Modifier.padding(start = 4.dp).size(34.dp)
                                .focusRequester(arrowReq[index])
                                // ▶ from the league reaches it; while moving, ▲ ▼ ◀ ▶ are taken by the moving row.
                                .focusProperties { canFocus = true; left = back.takeIf { false } ?: androidx.compose.ui.focus.FocusRequester.Default }
                                .onPreviewKeyEvent { ev ->
                                    if (!isMoving || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val dir = when (ev.nativeKeyEvent.keyCode) {
                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> -1
                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1
                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0
                                        else -> return@onPreviewKeyEvent false
                                    }
                                    val to = index + dir
                                    if (dir != 0 && to in leagues.indices) {
                                        // Cursor first (that row already exists), then swap the leagues.
                                        try { arrowReq[to].requestFocus() } catch (e: Exception) { }
                                        com.forbiddenstreams.tv.sports.SportPrefs.move(context, lg, dir)
                                    }
                                    true
                                },
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = shape),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = if (isMoving) FS.Green else Color.Transparent,
                                contentColor = if (isMoving) Color.Black else FS.GreenMid,
                                focusedContainerColor = FS.Green, focusedContentColor = Color.Black,
                            ),
                            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("⇅", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
