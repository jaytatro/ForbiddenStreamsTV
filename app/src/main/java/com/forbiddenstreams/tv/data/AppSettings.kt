package com.forbiddenstreams.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Everything set in Settings (Nov 15 2026 redesign, from the user's TiviMate photos):
 * General · Playlists · EPG · Appearance · Playback · Remote control · Parental controls · Other · About.
 * Read anywhere with AppSettings.x; screens that read [version] refresh when something changes.
 */
object AppSettings {
    private const val FILE = "forbidden_streams"
    private var sp: android.content.SharedPreferences? = null

    /** Bumped on every change. */
    var version by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun b(key: String, def: Boolean) = sp?.getBoolean("set_$key", def) ?: def
    private fun i(key: String, def: Int) = sp?.getInt("set_$key", def) ?: def
    private fun s(key: String, def: String) = sp?.getString("set_$key", def) ?: def

    fun set(key: String, v: Boolean) { sp?.edit()?.putBoolean("set_$key", v)?.apply(); version++ }
    fun set(key: String, v: Int) { sp?.edit()?.putInt("set_$key", v)?.apply(); version++ }
    fun set(key: String, v: String) { sp?.edit()?.putString("set_$key", v)?.apply(); version++ }

    // ---- General ----
    val autoStartBoot get() = b("boot", false)
    val autoStartWake get() = b("wake", false)
    val lastChannelOnStart get() = b("last_channel", false)
    val pipOnHome get() = b("pip", false)
    val confirmExit get() = b("confirm_exit", true)

    // ---- Playlists ----
    /** Hours between automatic playlist refreshes (0 = off). */
    val playlistEveryH get() = i("playlist_every", 24)
    val playlistOnStart get() = b("playlist_on_start", true)

    // ---- EPG ----
    val epgPastDays get() = i("epg_days", 4)
    val epgDescriptions get() = b("epg_desc", true)
    val epgEveryH get() = i("epg_every", 3)
    val epgOnStart get() = b("epg_on_start", false)
    val epgStatus get() = s("epg_status", "Not updated yet")

    // ---- Appearance ----
    val clock24 get() = b("clock24", false)
    val showClock get() = b("show_clock", true)
    val homeKeepWatching get() = b("home_keep", true)
    val homeRecentChannels get() = b("home_recent", true)
    val homeLiveGames get() = b("home_games", true)
    val showChannelNumbers get() = b("ch_numbers", true)

    // ---- Playback ----
    /** "Small" · "Medium" · "Large" */
    val bufferSize get() = s("buffer", "Medium")
    /** "Hardware" · "Software" */
    val audioDecoder get() = s("audio_dec", "Hardware")
    val videoDecoder get() = s("video_dec", "Hardware")
    val afr get() = b("afr", false)
    val surroundFirst get() = b("surround", false)
    val passthrough get() = b("passthrough", false)
    val externalPlayer get() = b("external", false)
    val skipSec get() = i("skip", 10)

    // ---- Remote control ----
    val channelKeysReversed get() = b("ch_reversed", false)
    val numberKeys get() = b("number_keys", true)

    // ---- Parental controls (the code itself lives in CategoryPrefs) ----
    /** "Always require" · "Until the app closes" */
    val pinMemory get() = s("pin_memory", "Always require")
    val pinSettings get() = b("pin_settings", false)
    val pinPlaylists get() = b("pin_playlists", false)
    val pinEpg get() = b("pin_epg", false)
    val pinCategoryOptions get() = b("pin_cat_options", false)
    val pinDvr get() = b("pin_dvr", false)

    // ---- Other › Search ----
    val searchHistory get() = b("search_history", true)
    val searchFavFirst get() = b("search_fav_first", true)
    val searchStay get() = b("search_stay", false)
    fun searches(): List<String> = s("searches", "").split('\u0001').filter { it.isNotBlank() }
    fun addSearch(q: String) {
        if (q.isBlank() || !searchHistory) return
        set("searches", (listOf(q.trim()) + searches().filter { !it.equals(q.trim(), true) }).take(8).joinToString("\u0001"))
    }
    fun clearSearches() = set("searches", "")

    // ---- Other › Reminders ----
    val remindBeforeMin get() = i("remind_before", 1)
    val reminderTimeoutSec get() = i("remind_timeout", 20)
    /** What happens when the reminder pop-up times out: "Watch" · "Dismiss" */
    val reminderAction get() = s("remind_action", "Watch")

    // ---- Other › Recording ----
    val recStartBeforeMin get() = i("rec_before", 0)
    val recStopAfterMin get() = i("rec_after", 0)

    // ---- Other › Sports ----
    val favGameAlerts get() = b("fav_alerts", true)

    /** Parental: the code was entered this session (when "Until the app closes"). */
    @Volatile var pinUnlocked = false
}
