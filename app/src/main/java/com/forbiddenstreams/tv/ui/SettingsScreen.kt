package com.forbiddenstreams.tv.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.data.AppSettings
import com.forbiddenstreams.tv.data.CategoryPrefs
import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One line on a settings page. */
private sealed class SRow {
    data class Header(val text: String) : SRow()
    data class Toggle(val title: String, val sub: String? = null, val on: Boolean, val change: (Boolean) -> Unit) : SRow()
    data class Value(val title: String, val value: String, val click: () -> Unit) : SRow()
    data class Action(val title: String, val sub: String? = null, val danger: Boolean = false, val click: () -> Unit) : SRow()
    data class Info(val title: String, val value: String) : SRow()
}

private data class Section(val key: String, val icon: String, val title: String, val about: String)

private val SECTIONS = listOf(
    Section("sports", "🏈", "Sports", "Favorite teams and game alerts"),
    Section("general", "⚙", "General", "How the app starts, stops and behaves on your box"),
    Section("playlists", "📋", "Playlists", "Your channel list and how often it refreshes"),
    Section("epg", "🗓", "EPG", "The TV guide: where it comes from and how long it's kept"),
    Section("appearance", "🎨", "Appearance", "Clock and what shows on the Home screen"),
    Section("playback", "▶", "Playback", "How the player plays your streams"),
    Section("parental", "🔒", "Parental controls", "A code to lock categories, 18+ and parts of the app"),
    Section("other", "⋯", "Other", "Search, reminders and recording"),
    Section("about", "ℹ", "About", "Version, updates and your account"),
)

private val SUB_TITLES = mapOf("search" to "Search", "reminders" to "Reminders", "recording" to "Recording", "epgsources" to "EPG sources")

/** User-Agents the provider can be asked with (Settings › General › User-Agent). */
private val USER_AGENTS = listOf(
    "Forbidden Streams" to Http.DEFAULT_USER_AGENT,
    "TiviMate" to "TiviMate/5.1.0 (Android 12)",
    "IPTV Smarters Pro" to "IPTVSmartersPro",
    "VLC" to "VLC/3.0.20 LibVLC/3.0.20",
    "Kodi" to "Kodi/21.0 (Linux; Android 12.0) Android/12.0.0",
    "Chrome browser" to "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
)

/** A code is needed for [flag] right now (code on, and not unlocked for this session). */
fun pinNeeded(context: Context, flag: Boolean): Boolean =
    CategoryPrefs.pin(context) != null && flag &&
        !(AppSettings.pinMemory == "Until the app closes" && AppSettings.pinUnlocked)

/** Correct code typed: remembered for the session when "Until the app closes". */
fun pinAccepted() { AppSettings.pinUnlocked = true }

/**
 * Settings (Nov 15 2026 redesign, from the user's TiviMate photos): sections down the left,
 * the highlighted section's options on the right. OK / ▶ goes into a section, ◀ / BACK comes out,
 * BACK on the sections closes Settings.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onFavTeams: () -> Unit,
    onReorder: () -> Unit,
    onCrash: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = AppSettings.version + CategoryPrefs.version // re-read the pages on any change
    var section by remember { mutableStateOf(SECTIONS.first().key) }
    val stack = remember { mutableStateListOf<String>() } // sub-pages (Other › Search …)
    val page = stack.lastOrNull() ?: section
    var inPage by remember { mutableStateOf(false) }
    val unlocked = remember { mutableStateListOf<String>() }
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinTitle by remember { mutableStateOf("Enter your parental code") }
    var picker by remember { mutableStateOf<Triple<String, List<String>, (String) -> Unit>?>(null) }
    var pinFlow by remember { mutableStateOf<String?>(null) } // parental: "set", "off"
    var firstCode by remember { mutableStateOf<String?>(null) }
    var hiddenList by remember { mutableStateOf(false) }
    var addSource by remember { mutableStateOf(false) }
    var folderPick by remember { mutableStateOf(false) }


    val secReq = remember { SECTIONS.associate { it.key to FocusRequester() } }
    val firstRowReq = remember { FocusRequester() }

    fun focusSection() { scope.launch { repeat(6) { delay(40); try { secReq[section]?.requestFocus(); return@launch } catch (e: Exception) { } } } }
    fun focusRows() { scope.launch { repeat(12) { delay(60); try { firstRowReq.requestFocus(); return@launch } catch (e: Exception) { } } } }
    /** Opens a page inside a section (Other › Search …) with the cursor on its first line. */
    var switchingAt by remember { mutableStateOf(0L) }
    fun open(sub: String) { switchingAt = System.currentTimeMillis(); stack += sub; inPage = true; focusRows() }
    fun back() { switchingAt = System.currentTimeMillis(); stack.removeAt(stack.size - 1); inPage = true; focusRows() }
    fun withPin(flag: Boolean, then: () -> Unit) {
        if (pinNeeded(context, flag)) { pinTitle = "Enter your parental code"; pinFor = then } else then()
    }
    fun locked(key: String) = key !in unlocked && when (key) {
        "playlists" -> pinNeeded(context, AppSettings.pinPlaylists)
        "epg" -> pinNeeded(context, AppSettings.pinEpg)
        "parental" -> CategoryPrefs.pin(context) != null && !AppSettings.pinUnlocked
        else -> false
    }
    fun enter() {
        if (locked(section)) withPin(true) { unlocked += section; inPage = true; focusRows() }
        else { inPage = true; focusRows() }
    }
    fun pick(title: String, options: List<String>, set: (String) -> Unit) { picker = Triple(title, options, set) }
    fun playbackChanged() = PlayerHolder.settingsChanged()

    LaunchedEffect(Unit) { focusSection() }

    // ---------------- the rows of each page ----------------
    val rows: List<SRow> = remember(page, v, unlocked.size) {
        val S = AppSettings
        fun t(key: String, title: String, on: Boolean, sub: String? = null, after: () -> Unit = {}) =
            SRow.Toggle(title, sub, on) { S.set(key, it); after() }
        when (page) {
            "general" -> listOf(
                t("boot", "Auto start app on boot", S.autoStartBoot, "May not work on all devices"),
                t("wake", "Auto start app on wake up from sleep mode", S.autoStartWake, "May not work on all devices"),
                t("last_channel", "Turn on last channel on app start", S.lastChannelOnStart),
                t("pip", "Switch to picture-in-picture mode on press Home", S.pipOnHome, "Android 8 and newer"),
                t("confirm_exit", "Confirm exit by second press Back", S.confirmExit),
            )
            "playlists" -> {
                val login = Prefs(context).loadLogin()
                val desc = when (login) {
                    is com.forbiddenstreams.tv.data.LoginConfig.Xtream -> "Xtream Codes · ${login.server} · ${login.username}"
                    is com.forbiddenstreams.tv.data.LoginConfig.M3u -> "M3U · ${login.url.take(60)}"
                    else -> "Not logged in"
                }
                listOf(
                    SRow.Info("Playlist", desc),
                    SRow.Info("Channels", "${Session.liveAll?.size ?: 0} channels in ${Session.liveCats?.size ?: 0} categories"),
                    SRow.Action("Refresh playlist", "Channels, guide, movies and shows load fresh") {
                        refreshPlaylist(context); toast(context, "Refreshing the playlist…")
                    },
                    SRow.Action("Re-order and hide categories", "Live TV and TV Guide") { onReorder() },
                    SRow.Header("Update options"),
                    SRow.Value("Update interval, hours", if (S.playlistEveryH == 0) "Off" else "${S.playlistEveryH}") {
                        pick("Update interval, hours", listOf("Off", "6", "12", "24", "48")) { S.set("playlist_every", it.toIntOrNull() ?: 0) }
                    },
                    t("playlist_on_start", "Update on app start", S.playlistOnStart),
                )
            }
            "epg" -> listOf(
                SRow.Action("EPG sources", if (S.epgSources().isEmpty()) "Only your provider's guide" else "${S.epgSources().size} added") { open("epgsources") },
                SRow.Value("Past days to keep EPG", "${S.epgPastDays}") {
                    pick("Past days to keep EPG", (1..7).map { "$it" }) { S.set("epg_days", it.toInt()) }
                },
                t("epg_desc", "Store program descriptions", S.epgDescriptions),
                SRow.Header("Update options"),
                SRow.Value("Update interval, hours", "${S.epgEveryH}") {
                    pick("Update interval, hours", listOf("1", "3", "6", "12", "24")) { S.set("epg_every", it.toInt()) }
                },
                t("epg_on_start", "Update on app start", S.epgOnStart),
                SRow.Action("Update EPG", "Your provider's guide and your EPG sources") {
                    Session.importPastGuide(context, force = true); Session.loadEpgSources(context, force = true)
                    toast(context, "Updating the EPG… (can take a minute)")
                },
                SRow.Action("Clear EPG", danger = true) { Session.clearEpg(context); toast(context, "EPG cleared") },
                SRow.Info("Latest update status", S.epgStatus),
            )
            "epgsources" -> listOf<SRow>(
                SRow.Info("Your provider's guide", "Always used"),
            ) + S.epgSources().map { u ->
                SRow.Action(u, "OK to remove") {
                    pick("Remove this EPG source?", listOf("Remove", "Keep")) { if (it == "Remove") { S.removeEpgSource(u); Session.loadEpgSources(context, force = true) } }
                }
            } + listOf(SRow.Action("➕  Add EPG source", "An XMLTV address (.xml or .xml.gz)") { addSource = true })
            "appearance" -> listOf(
                t("clock24", "24-hour clock", S.clock24),
                t("show_clock", "Show clock in the top bar", S.showClock),
                SRow.Header("Home screen"),
                t("home_recent", "Continue watching · Live TV row", S.homeRecentChannels),
                t("home_games", "Live games row", S.homeLiveGames),
            )
            "playback" -> listOf(
                SRow.Value("Buffer size", S.bufferSize) {
                    pick("Buffer size", listOf("Small", "Medium", "Large")) { S.set("buffer", it); playbackChanged() }
                },
                SRow.Value("Audio decoder", S.audioDecoder) {
                    pick("Audio decoder", listOf("Hardware", "Software")) { S.set("audio_dec", it); playbackChanged() }
                },
                SRow.Value("Video decoder", S.videoDecoder) {
                    pick("Video decoder", listOf("Hardware", "Software")) { S.set("video_dec", it); playbackChanged() }
                },
                SRow.Value("Auto frame rate (AFR)", if (S.afr) "On" else "Off") {
                    pick("Auto frame rate (AFR)", listOf("Off", "On")) { S.set("afr", it == "On") }
                },
                t("surround", "Select surround audio track by default", S.surroundFirst) { playbackChanged() },
                t("passthrough", "Audio passthrough", S.passthrough, "Dolby / DTS straight to your TV or receiver") { playbackChanged() },
                SRow.Value("Use external player", if (!S.externalPlayer) "No" else S.externalName.ifBlank { "Ask each time" }) {
                    // Nov 15: the players installed on this box.
                    val players = installedPlayers(context)
                    pick(if (players.isEmpty()) "No other video players found on this box" else "Use external player",
                        listOf("No (built-in player)") + players.map { it.first } + listOf("Ask each time")) { name ->
                        when (name) {
                            "No (built-in player)" -> S.set("external", false)
                            "Ask each time" -> { S.set("external_pkg", ""); S.set("external_name", ""); S.set("external", true) }
                            else -> players.firstOrNull { it.first == name }?.let { (n, pkg) ->
                                S.set("external_pkg", pkg); S.set("external_name", n); S.set("external", true)
                            }
                        }
                    }
                },
                SRow.Value("Skip steps", "${S.skipSec} seconds") {
                    pick("Skip steps (◀ ▶ in movies, recordings, start over)", listOf("5", "10", "15", "30", "60")) { S.set("skip", it.toInt()) }
                },
            )
            "parental" -> {
                val on = CategoryPrefs.pin(context) != null
                val list = arrayListOf<SRow>(
                    SRow.Toggle(if (on) "On" else "Off", "Parental code", on) { want ->
                        if (want) { firstCode = null; pinFlow = "set" } else pinFlow = "off"
                    },
                )
                if (on) {
                    list += SRow.Action("Change PIN") { firstCode = null; pinFlow = "set" }
                    list += SRow.Value("Don't require PIN after unlocking", S.pinMemory) {
                        pick("Don't require PIN after unlocking", listOf("Always require", "Until the app closes")) { S.set("pin_memory", it) }
                    }
                    list += SRow.Action(if (CategoryPrefs.adultUnlocked) "Hide 18+ again" else "Show 18+ until the app closes") {
                        CategoryPrefs.unlockAdult(!CategoryPrefs.adultUnlocked)
                    }
                }
                list += SRow.Action("Hidden categories (${CategoryPrefs.hidden(context).size})", "Show a hidden category again") { hiddenList = true }
                if (on) {
                    list += SRow.Header("Require PIN for")
                    list += t("pin_settings", "Settings", S.pinSettings)
                    list += t("pin_playlists", "Settings | Playlists", S.pinPlaylists)
                    list += t("pin_epg", "Settings | EPG", S.pinEpg)
                    list += t("pin_cat_options", "Category options", S.pinCategoryOptions, "Hide category · Re-order and hide categories")
                    list += t("pin_dvr", "DVR", S.pinDvr)
                }
                list
            }
            "sports" -> listOf(
                SRow.Action("Favorite teams", "Their games go first · alerts before they start") { onFavTeams() },
                t("fav_alerts", "Alert before a favorite team's game", S.favGameAlerts),
                SRow.Value("Alert pop-up time", "${S.favAlertMin} minute${if (S.favAlertMin == 1) "" else "s"} before") {
                    pick("Alert pop-up time", (1..5).map { "$it minute${if (it == 1) "" else "s"} before" }) { S.set("fav_alert_min", it.take(1).toInt()) }
                },
                SRow.Value("Play game highlights in", S.highlightsIn) {
                    val tt = Highlights.tizenTube(context) != null
                    pick("Play game highlights in", listOf("Forbidden Streams", "TizenTube" + if (tt) "" else " (not installed)", "YouTube app")) {
                        S.set("highlights_in", it.removeSuffix(" (not installed)"))
                    }
                },
                SRow.Info("Re-order sports", "In the Sports tab, hold OK on a sport and move it ▲ ▼"),
            )
            "other" -> listOf(
                SRow.Action("Search") { open("search") },
                SRow.Action("Reminders") { open("reminders") },
                SRow.Action("Recording") { open("recording") },
            )
            "search" -> listOf(
                t("search_history", "Show search history", S.searchHistory),
                t("search_fav_first", "Show favorite channels first", S.searchFavFirst),
                t("search_past", "Show past programs without catch-up", S.searchPastNoCatchup),
                t("search_stay", "Stay on search screen when switching channels", S.searchStay),
                SRow.Action("Clear search history") { S.clearSearches(); toast(context, "Search history cleared") },
            )
            "reminders" -> listOf(
                SRow.Value("Remind before program start, min", "${S.remindBeforeMin}") {
                    pick("Remind before program start, min", listOf("0", "1", "2", "5", "10", "15")) { S.set("remind_before", it.toInt()) }
                },
                SRow.Value("Popup timeout, sec", "${S.reminderTimeoutSec}") {
                    pick("Popup timeout, sec", listOf("10", "20", "30", "60")) { S.set("remind_timeout", it.toInt()) }
                },
                SRow.Value("Default action", S.reminderAction) {
                    pick("When the pop-up times out", listOf("Watch", "Dismiss")) { S.set("remind_action", it) }
                },
                t("remind_wake", "Wake up from sleep mode", S.reminderWake, "May not work on all devices"),
            )
            "recording" -> {
                // Every storage the box has: its own, plus USB drives / SD cards.
                val dirs = context.getExternalFilesDirs(android.os.Environment.DIRECTORY_MOVIES).filterNotNull()
                val cur = S.recFolder.ifBlank { (dirs.firstOrNull() ?: context.filesDir).absolutePath }
                listOf(
                    SRow.Value("Recordings folder", cur) { folderPick = true },
                    SRow.Value("Start recording before program start, min", "${S.recStartBeforeMin}") {
                        pick("Start recording before program start, min", listOf("0", "1", "2", "5", "10")) { S.set("rec_before", it.toInt()) }
                    },
                    SRow.Value("Stop recording after program end, min", "${S.recStopAfterMin}") {
                        pick("Stop recording after program end, min", listOf("0", "1", "2", "5", "10", "15", "30")) { S.set("rec_after", it.toInt()) }
                    },
                )
            }
            "about" -> {
                val ver = try {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    "${pi.versionName} (build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)})"
                } catch (e: Exception) { "?" }
                listOf(
                    SRow.Info("Version", ver),
                    SRow.Action("Check for updates") {
                        toast(context, "Checking for updates…")
                        UpdateState.check(context, kotlinx.coroutines.MainScope(), manual = true)
                    },
                    SRow.Action("Last crash report") { onCrash() },
                    SRow.Action("Log out", "Sign in with another playlist", danger = true) { onLogout() },
                )
            }
            else -> emptyList()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler {
            when {
                stack.isNotEmpty() -> back()
                inPage -> { inPage = false; focusSection() }
                else -> onClose()
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07140A), Color(0xFF010201))))) {
          Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 22.dp)) {
            // ---- Top strip: CONTROL CENTER · service · clock ----
            val acct by produceState<com.forbiddenstreams.tv.data.AccountInfo?>(null) {
                value = try { Session.source?.account() } catch (e: Exception) { null }
            }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
            Row(Modifier.fillMaxWidth().height(44.dp).glass().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⚙ CONTROL CENTER", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                    modifier = Modifier.background(FS.Green, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
                Spacer(Modifier.width(14.dp))
                Text("Forbidden Streams TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text(acct?.let { serviceLine(it) } ?: "", color = FS.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(Fmt.time(now), color = FS.Green, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // ---- Sections: icon tiles with a live status line ----
                Column(Modifier.width(380.dp).fillMaxHeight().glass().padding(vertical = 12.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                        itemsIndexed(SECTIONS, key = { _, s -> s.key }) { i, s ->
                            val sel = s.key == section
                            ListPill(
                                selected = sel,
                                onClick = { enter() },
                                modifier = Modifier.fillMaxWidth().focusRequester(secReq.getValue(s.key))
                                    .onFocusChanged {
                                        if (it.isFocused && section != s.key && System.currentTimeMillis() - switchingAt > 800) { section = s.key; stack.clear(); inPage = false }
                                    }
                                    .onPreviewKeyEvent { ev ->
                                        val ne = ev.nativeKeyEvent
                                        if (ne.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                        when (ne.keyCode) {
                                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { enter(); true }
                                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (i == SECTIONS.size - 1) {
                                                try { secReq.getValue(SECTIONS.first().key).requestFocus() } catch (e: Exception) { }; true
                                            } else false
                                            android.view.KeyEvent.KEYCODE_DPAD_UP -> if (i == 0) {
                                                try { secReq.getValue(SECTIONS.last().key).requestFocus() } catch (e: Exception) { }; true
                                            } else false
                                            else -> false
                                        }
                                    },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(42.dp).background(if (sel) Color(0x337CFF3A) else Color(0x14FFFFFF), RoundedCornerShape(11.dp))
                                        .border(1.dp, if (sel) FS.Green else Color(0x22FFFFFF), RoundedCornerShape(11.dp)),
                                        contentAlignment = Alignment.Center) { Text(s.icon, fontSize = 19.sp) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(remember(v, s.key) { sectionSummary(context, s.key) }, color = if (sel) FS.Green else FS.TextDim,
                                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (locked(s.key)) Text("🔒", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(18.dp))
                // ---- The section's page ----
                Column(Modifier.weight(1f).fillMaxHeight().glass().padding(top = 16.dp, bottom = 10.dp)) {
                    val main = SECTIONS.first { it.key == section }
                    Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(Color(0x227CFF3A), RoundedCornerShape(14.dp))
                            .border(1.5.dp, FS.Green, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Text(main.icon, fontSize = 26.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(main.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                                if (stack.isNotEmpty()) Text("  ›  ${SUB_TITLES[page] ?: page}", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Black)
                            }
                            Text(main.about, color = FS.TextDim, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(Color(0x337CFF3A)))
                    if (locked(section)) {
                        Text("🔒  Locked with your parental code — press OK and enter it.", color = FS.TextDim, fontSize = 17.sp,
                            modifier = Modifier.padding(28.dp))
                    } else {
                        val firstFocusable = rows.indexOfFirst { it !is SRow.Header }
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            itemsIndexed(rows, key = { i, _ -> i }) { i, r ->
                                SettingRow(
                                    r, focusable = inPage,
                                    modifier = Modifier.then(if (i == firstFocusable) Modifier.focusRequester(firstRowReq) else Modifier)
                                        .onPreviewKeyEvent { ev ->
                                            val ne = ev.nativeKeyEvent
                                            if (ne.action == android.view.KeyEvent.ACTION_DOWN && ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                                if (stack.isNotEmpty()) back() else { inPage = false; focusSection() }
                                                true
                                            } else false
                                        },
                                )
                            }
                        }
                    }
                }
            }
            // ---- Key hints ----
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (inPage) { KeyCap("OK", "change"); KeyCap("◀", "back") } else { KeyCap("OK / ▶", "open"); KeyCap("▲ ▼", "move") }
                KeyCap("BACK", if (inPage) "back" else "close")
            }
          }
        }

        // ---- pop-ups over Settings ----
        picker?.let { (title, options, set) ->
            ChoiceDialog(
                title = title,
                options = options.map { o -> o to { picker = null; set(o) } },
                onDismiss = { picker = null },
            )
        }
        pinFor?.let { then ->
            PinDialog(pinTitle, onDone = { code ->
                if (code == CategoryPrefs.pin(context)) { pinAccepted(); pinFor = null; then() } else toast(context, "Wrong code")
            }, onCancel = { pinFor = null })
        }
        when (pinFlow) {
            "set" -> PinDialog(if (firstCode == null) "Choose a 4-digit parental code" else "Enter the same code again", onDone = { code ->
                when {
                    code.length != 4 -> toast(context, "The code needs 4 numbers")
                    firstCode == null -> firstCode = code
                    code == firstCode -> {
                        CategoryPrefs.setPin(context, code); CategoryPrefs.unlockAdult(false); pinAccepted()
                        toast(context, "Parental code is on · 18+ is hidden")
                        firstCode = null; pinFlow = null; focusRows()
                    }
                    else -> { toast(context, "The codes didn't match. Try again."); firstCode = null }
                }
            }, onCancel = { firstCode = null; pinFlow = null })
            "off" -> PinDialog("Enter your code to turn it off", onDone = { code ->
                if (code == CategoryPrefs.pin(context)) {
                    CategoryPrefs.setPin(context, null); CategoryPrefs.unlockAdult(false)
                    toast(context, "Parental code is off"); pinFlow = null; focusRows()
                } else toast(context, "Wrong code")
            }, onCancel = { pinFlow = null })
        }
        if (folderPick) FolderPickerDialog(
            start = AppSettings.recFolder.ifBlank { (context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir).absolutePath },
            onChoose = { d -> AppSettings.set("rec_folder", d.absolutePath); folderPick = false; toast(context, "Recordings will be saved in ${d.absolutePath}") },
            onClose = { folderPick = false },
        )
        if (addSource) TextInputDialog("Add EPG source", "XMLTV address, e.g. http://example.com/guide.xml.gz",
            onDone = { u ->
                if (u.startsWith("http")) {
                    AppSettings.addEpgSource(u); addSource = false
                    Session.loadEpgSources(context, force = true); toast(context, "Loading the EPG source…")
                } else toast(context, "Enter an address starting with http")
            }, onCancel = { addSource = false })
        if (hiddenList) {
            val cats = Session.liveCats.orEmpty()
            val hidden = CategoryPrefs.hidden(context)
            val list = cats.filter { it.id in hidden }
            ChoiceDialog(
                title = "Hidden categories",
                subtitle = if (list.isEmpty()) "Nothing is hidden. Hold OK on a category in Live TV or TV Guide to hide it." else "OK on a category shows it again.",
                options = list.map { c -> "👁  ${c.name}" to { CategoryPrefs.toggleHidden(context, c.id); toast(context, "${c.name} is back") } } +
                    listOf("Back" to { hiddenList = false }),
                onDismiss = { hiddenList = false },
            )
        }
    }
}

@Composable
private fun SettingRow(r: SRow, focusable: Boolean, modifier: Modifier) {
    if (r is SRow.Header) {
        // Group label with a thin neon rule.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.text.uppercase(), color = FS.Green, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Color(0x337CFF3A)))
        }
        return
    }
    val shape = RoundedCornerShape(14.dp)
    val (title, sub, click) = when (r) {
        is SRow.Toggle -> Triple(r.title, r.sub, { r.change(!r.on) })
        is SRow.Value -> Triple(r.title, null, r.click)
        is SRow.Action -> Triple(r.title, r.sub, r.click)
        is SRow.Info -> Triple(r.title, null, {})
        else -> Triple("", null, {})
    }
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = click,
        enabled = focusable,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x0DFFFFFF), contentColor = FS.Text,
            focusedContainerColor = Color(0xFF12301B), focusedContentColor = Color.White,
            disabledContainerColor = Color(0x0DFFFFFF), disabledContentColor = FS.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x1A7CFF3A)), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, FS.Green), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            // Neon bar on the left of the highlighted line.
            Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 10.dp)
                .background(if (focused) FS.Green else Color.Transparent, RoundedCornerShape(2.dp)))
            Row(Modifier.weight(1f).padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (r is SRow.Action && r.danger) Color(0xFFFF7070) else Color.Unspecified)
                    if (sub != null) Text(sub, color = FS.TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Spacer(Modifier.width(14.dp))
                when (r) {
                    is SRow.Toggle -> Switch(r.on)
                    // The value in a green chip on the right.
                    is SRow.Value -> Text(r.value, color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 380.dp).background(Color(0x1F7CFF3A), RoundedCornerShape(50))
                            .border(1.dp, Color(0x557CFF3A), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 5.dp))
                    is SRow.Info -> Text(r.value, color = FS.TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.widthIn(max = 460.dp))
                    is SRow.Action -> Text("›", color = if (focused) FS.Green else FS.TextDim, fontSize = 26.sp)
                    else -> {}
                }
            }
        }
    }
}

/** A green on/off switch with ON / OFF next to it. */
@Composable
private fun Switch(on: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(if (on) "ON" else "OFF", color = if (on) FS.Green else FS.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Black,
        letterSpacing = 1.sp, modifier = Modifier.padding(end = 8.dp))
    Box(
        Modifier.width(52.dp).height(28.dp)
            .background(if (on) FS.GreenDeep else Color(0x33FFFFFF), RoundedCornerShape(50))
            .border(1.dp, if (on) FS.Green else Color(0x55FFFFFF), RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(if (on) FS.Green else Color(0xFFBBBBBB), RoundedCornerShape(50)))
    }
  }
}

/**
 * "Starting now" pop-up for reminders while the app is open (Nov 15: Settings › Other › Reminders).
 * Counts down the popup timeout, then does the default action (Watch / Dismiss).
 */
@Composable
fun ReminderPopup() {
    val context = LocalContext.current
    var showing by remember { mutableStateOf<com.forbiddenstreams.tv.reminders.Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (showing == null) showing = com.forbiddenstreams.tv.reminders.Reminders.due(context).firstOrNull()
            delay(15_000)
        }
    }
    val r = showing ?: return
    fun watch() {
        com.forbiddenstreams.tv.reminders.Reminders.remove(context, r.id)
        showing = null
        val ch = Session.liveAll?.firstOrNull { it.url == r.channelUrl }
        if (ch != null) playChannels(context, Session.channelsIn(ch.categoryId).ifEmpty { listOf(ch) },
            Session.channelsIn(ch.categoryId).indexOf(ch).coerceAtLeast(0))
        else com.forbiddenstreams.tv.player.Playback.one(context, com.forbiddenstreams.tv.player.PlayItem(r.channelName, r.channelUrl, live = true, subtitle = r.title))
    }
    fun dismiss() { com.forbiddenstreams.tv.reminders.Reminders.remove(context, r.id); showing = null }
    var left by remember(r.id) { mutableStateOf(AppSettings.reminderTimeoutSec) }
    LaunchedEffect(r.id) {
        while (left > 0) { delay(1_000); left-- }
        if (showing?.id == r.id) { if (AppSettings.reminderAction == "Watch") watch() else dismiss() }
    }
    val starts = if (r.start > System.currentTimeMillis() + 30_000) "Starts at ${Fmt.time(r.start)}" else "Starting now"
    ChoiceDialog(
        title = "⏰  $starts: ${r.title}",
        subtitle = "On ${r.channelName}  ·  ${if (AppSettings.reminderAction == "Watch") "switching" else "closing"} in $left s",
        options = listOf("▶  Watch now" to { watch() }, "Dismiss" to { dismiss() }),
        onDismiss = { dismiss() },
    )
}


/** Video players installed on the box: (name, package). */
private fun installedPlayers(context: Context): List<Pair<String, String>> = try {
    val pm = context.packageManager
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("http://example.com/live.ts"), "video/*")
    @Suppress("DEPRECATION")
    pm.queryIntentActivities(i, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        .distinctBy { it.second }.sortedBy { it.first.lowercase() }
} catch (e: Exception) { emptyList() }

/** A pop-up with one text box (keyboard opens). */
@Composable
fun TextInputDialog(title: String, hint: String, button: String = "Add", onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(640.dp).background(FS.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, FS.GreenDeep, RoundedCornerShape(16.dp)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = FS.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            FsTextField(hint, text, { text = it }, androidx.compose.ui.text.input.KeyboardType.Uri, done = { onDone(text.trim()) })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(button, selected = true, onClick = { onDone(text.trim()) })
                Pill("Cancel", selected = false, onClick = onCancel)
            }
        }
    }
}


/** The live status line under each section on the left (Nov 15 "make it unique"). */
private fun sectionSummary(context: Context, key: String): String {
    val S = AppSettings
    fun on(b: Boolean) = if (b) "on" else "off"
    return when (key) {
        "general" -> "Boot start ${on(S.autoStartBoot)} · PiP ${on(S.pipOnHome)}"
        "playlists" -> "${Session.liveAll?.size ?: 0} channels · " + (if (S.playlistEveryH == 0) "manual" else "every ${S.playlistEveryH} h")
        "epg" -> "${S.epgSources().size} source${if (S.epgSources().size == 1) "" else "s"} · keeps ${S.epgPastDays} days"
        "appearance" -> (if (S.clock24) "24-hour" else "12-hour") + " clock · " + (if (S.showClock) "shown" else "hidden")
        "playback" -> "${S.bufferSize} buffer · ${S.videoDecoder}" + if (S.externalPlayer) " · external" else ""
        "parental" -> if (CategoryPrefs.pin(context) != null) "Code on" else "Code off"
        "sports" -> "${com.forbiddenstreams.tv.sports.FavTeams.all(context).size} favorite teams · alert ${S.favAlertMin} min"
        "other" -> "Search · Reminders · Recording"
        "about" -> try { "Version " + context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "" }
        else -> ""
    }
}

/** A key cap for the hint bar at the bottom. */
@Composable
private fun KeyCap(key: String, what: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.background(FS.Green, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp))
        Spacer(Modifier.width(6.dp))
        Text(what, color = FS.TextDim, fontSize = 13.sp)
        Spacer(Modifier.width(18.dp))
    }
}
