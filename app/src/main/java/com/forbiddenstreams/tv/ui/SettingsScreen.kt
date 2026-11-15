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

private data class Section(val key: String, val icon: String, val title: String)

private val SECTIONS = listOf(
    Section("general", "⚙", "General"),
    Section("playlists", "📋", "Playlists"),
    Section("epg", "🗓", "EPG"),
    Section("appearance", "🎨", "Appearance"),
    Section("playback", "▶", "Playback"),
    Section("remote", "🎮", "Remote control"),
    Section("parental", "🔒", "Parental controls"),
    Section("sports", "🏈", "Sports"),
    Section("other", "⋯", "Other"),
    Section("about", "ℹ", "About"),
)

private val SUB_TITLES = mapOf("search" to "Search", "reminders" to "Reminders", "recording" to "Recording")

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

    val secReq = remember { SECTIONS.associate { it.key to FocusRequester() } }
    val firstRowReq = remember { FocusRequester() }

    fun focusSection() { scope.launch { repeat(6) { delay(40); try { secReq[section]?.requestFocus(); return@launch } catch (e: Exception) { } } } }
    fun focusRows() { scope.launch { repeat(8) { delay(50); try { firstRowReq.requestFocus(); return@launch } catch (e: Exception) { } } } }
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
                SRow.Value("User-Agent", USER_AGENTS.firstOrNull { it.second == Http.userAgent }?.first ?: Http.userAgent) {
                    pick("User-Agent", USER_AGENTS.map { it.first }) { name ->
                        val ua = USER_AGENTS.first { it.first == name }.second
                        Http.userAgent = ua; Prefs(context).userAgent = ua
                        PlayerHolder.settingsChanged()
                        toast(context, "User-Agent: $name · if channels stop loading, switch back")
                    }
                },
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
                    SRow.Action("Log out", "Sign in with another playlist", danger = true) { onLogout() },
                )
            }
            "epg" -> listOf(
                SRow.Value("Past days to keep EPG", "${S.epgPastDays}") {
                    pick("Past days to keep EPG", (1..7).map { "$it" }) { S.set("epg_days", it.toInt()) }
                },
                t("epg_desc", "Store program descriptions", S.epgDescriptions),
                SRow.Header("Update options"),
                SRow.Value("Update interval, hours", "${S.epgEveryH}") {
                    pick("Update interval, hours", listOf("1", "3", "6", "12", "24")) { S.set("epg_every", it.toInt()) }
                },
                t("epg_on_start", "Update on app start", S.epgOnStart),
                SRow.Action("Update EPG", "Earlier days' show names for catch-up channels") {
                    Session.importPastGuide(context, force = true); toast(context, "Updating the EPG… (can take a minute)")
                },
                SRow.Action("Clear EPG", danger = true) { Session.clearEpg(context); toast(context, "EPG cleared") },
                SRow.Info("Latest update status", S.epgStatus),
            )
            "appearance" -> listOf(
                t("clock24", "24-hour clock", S.clock24),
                t("show_clock", "Show clock in the top bar", S.showClock),
                SRow.Header("Home screen"),
                t("home_keep", "Keep watching row", S.homeKeepWatching),
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
                SRow.Value("Use external player", if (S.externalPlayer) "Yes" else "No") {
                    pick("Use external player", listOf("No", "Yes")) { S.set("external", it == "Yes") }
                },
                SRow.Value("Skip steps", "${S.skipSec} seconds") {
                    pick("Skip steps (◀ ▶ in movies, recordings, start over)", listOf("5", "10", "15", "30", "60")) { S.set("skip", it.toInt()) }
                },
            )
            "remote" -> listOf(
                SRow.Value("▲ ▼ change channel", if (S.channelKeysReversed) "Reversed (▲ = next)" else "Normal (▼ = next)") {
                    pick("▲ ▼ change channel", listOf("Normal (▼ = next)", "Reversed (▲ = next)")) { S.set("ch_reversed", it.startsWith("Reversed")) }
                },
                t("number_keys", "Number keys change channel", S.numberKeys, "Type a channel number while watching"),
                SRow.Value("◀ in the TV Guide", if (ButtonPrefs.leftOpensCategories) "Opens the categories" else "One show back") {
                    pick("◀ in the TV Guide", listOf("Opens the categories", "One show back")) { ButtonPrefs.setLeft(context, it.startsWith("Opens")); S.set("x", 0) }
                },
                SRow.Value("Hold ◀ ▶ to scroll the guide after", "${ButtonPrefs.holdMs / 1000.0} s".replace(".0 s", " s")) {
                    pick("Hold ◀ ▶ to scroll the guide after", listOf("1 s", "2 s", "3 s")) { ButtonPrefs.setHold(context, it.take(1).toInt() * 1000); S.set("x", 0) }
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
                t("fav_alerts", "Alert 1 minute before a favorite team's game", S.favGameAlerts),
                SRow.Info("Re-order sports", "In the Sports tab, hold OK on a sport and move it ▲ ▼"),
            )
            "other" -> listOf(
                SRow.Action("Search") { stack += "search" },
                SRow.Action("Reminders") { stack += "reminders" },
                SRow.Action("Recording") { stack += "recording" },
                SRow.Action("Last crash report") { onCrash() },
            )
            "search" -> listOf(
                t("search_history", "Show search history", S.searchHistory),
                t("search_fav_first", "Show favorite channels first", S.searchFavFirst),
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
            )
            "recording" -> {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir
                listOf(
                    SRow.Info("Recordings folder", dir.absolutePath),
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
                )
            }
            else -> emptyList()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler {
            when {
                stack.isNotEmpty() -> { stack.removeAt(stack.size - 1); focusRows() }
                inPage -> { inPage = false; focusSection() }
                else -> onClose()
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF020402), Color(0xFF07140A))))) {
            Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
                // ---- Sections ----
                Column(Modifier.width(360.dp).fillMaxHeight().glass().padding(vertical = 18.dp)) {
                    Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
                    val acct by produceState<com.forbiddenstreams.tv.data.AccountInfo?>(null) {
                        value = try { Session.source?.account() } catch (e: Exception) { null }
                    }
                    Text(acct?.let { serviceLine(it) } ?: " ", color = FS.TextDim, fontSize = 12.sp, maxLines = 2,
                        modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 12.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                        itemsIndexed(SECTIONS, key = { _, s -> s.key }) { i, s ->
                            ListPill(
                                selected = s.key == section,
                                onClick = { enter() },
                                modifier = Modifier.fillMaxWidth().focusRequester(secReq.getValue(s.key))
                                    .onFocusChanged { if (it.isFocused) { if (section != s.key) { section = s.key; stack.clear() }; inPage = false } }
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
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { Text(s.icon, fontSize = 18.sp) }
                                    Spacer(Modifier.width(10.dp))
                                    Text(s.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (locked(s.key)) Text("🔒", fontSize = 14.sp)
                                    Text("›", color = FS.TextDim, fontSize = 22.sp, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(22.dp))
                // ---- The section's options ----
                Column(Modifier.weight(1f).fillMaxHeight().glass().padding(vertical = 18.dp)) {
                    Row(Modifier.padding(start = 28.dp, end = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val main = SECTIONS.first { it.key == section }
                        Text(if (stack.isEmpty()) main.title else "${main.title}  ›  ${SUB_TITLES[page] ?: page}",
                            color = FS.Green, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        if (!inPage) Text("OK or ▶ to change", color = FS.TextDim, fontSize = 13.sp)
                    }
                    if (locked(section)) {
                        Text("🔒  Locked with your parental code — press OK and enter it.", color = FS.TextDim, fontSize = 17.sp,
                            modifier = Modifier.padding(28.dp))
                    } else {
                        val firstFocusable = rows.indexOfFirst { it !is SRow.Header }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            itemsIndexed(rows, key = { i, r -> "$page:$i:${r::class.simpleName}" }) { i, r ->
                                SettingRow(
                                    r, focusable = inPage,
                                    modifier = Modifier.then(if (i == firstFocusable) Modifier.focusRequester(firstRowReq) else Modifier)
                                        .onPreviewKeyEvent { ev ->
                                            val ne = ev.nativeKeyEvent
                                            if (ne.action == android.view.KeyEvent.ACTION_DOWN && ne.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                                if (stack.isNotEmpty()) { stack.removeAt(stack.size - 1); focusRows() } else { inPage = false; focusSection() }
                                                true
                                            } else false
                                        },
                                )
                            }
                        }
                    }
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
        Text(r.text, color = FS.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp))
        return
    }
    val shape = RoundedCornerShape(12.dp)
    val (title, sub, click) = when (r) {
        is SRow.Toggle -> Triple(r.title, r.sub, { r.change(!r.on) })
        is SRow.Value -> Triple(r.title, r.value, r.click)
        is SRow.Action -> Triple(r.title, r.sub, r.click)
        is SRow.Info -> Triple(r.title, r.value, {})
        else -> Triple("", null, {})
    }
    Surface(
        onClick = click,
        enabled = focusable,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = FS.Text,
            focusedContainerColor = Color(0xFF12301B), focusedContentColor = Color.White,
            disabledContainerColor = Color.Transparent, disabledContentColor = FS.Text,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.5.dp, FS.Green), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    color = if (r is SRow.Action && r.danger) Color(0xFFFF7070) else Color.Unspecified)
                if (sub != null) Text(sub, color = if (r is SRow.Value) FS.Green else FS.TextDim, fontSize = 14.sp,
                    fontWeight = if (r is SRow.Value) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = if (r is SRow.Info) FontFamily.Monospace else null,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            when (r) {
                is SRow.Toggle -> Switch(r.on)
                is SRow.Action -> Text("›", color = FS.TextDim, fontSize = 24.sp)
                else -> {}
            }
        }
    }
}

/** A green on/off switch. */
@Composable
private fun Switch(on: Boolean) {
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
