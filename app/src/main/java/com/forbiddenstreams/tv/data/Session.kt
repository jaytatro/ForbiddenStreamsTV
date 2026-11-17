package com.forbiddenstreams.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The logged-in provider plus shared caches. */
object Session {
    var source: ContentSource? = null
        private set

    private val epgCache = ConcurrentHashMap<String, Pair<Long, List<Program>>>()
    private val epgLimiter = Semaphore(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- Live channel list: saved on the box so the app opens instantly,
    // ---- then refreshed from the provider in the background every time the app opens.
    var liveCats by mutableStateOf<List<Category>?>(null)
        private set
    var liveAll by mutableStateOf<List<LiveChannel>?>(null)
        private set
    var liveError by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var refreshJob: Job? = null

    /** True until the first screen has handled "resume last channel" for this app launch. */
    var resumePending = true

    /** Folder for the 2-day guide history (set as soon as the app starts). */
    @Volatile private var histDir: File? = null

    fun restore(context: Context): Boolean {
        histDir = File(context.applicationContext.filesDir, "epg_history")
        val p = Prefs(context)
        p.userAgent?.let { Http.userAgent = it }
        val c = p.loadLogin() ?: return false
        source = build(c)
        return true
    }

    private fun build(c: LoginConfig): ContentSource = when (c) {
        is LoginConfig.Xtream -> XtreamSource(c.server, c.username, c.password, c.timezone)
        is LoginConfig.M3u -> M3uSource(c.url, c.epgUrl)
    }

    suspend fun login(context: Context, c: LoginConfig) {
        histDir = File(context.applicationContext.filesDir, "epg_history")
        val prefs = Prefs(context)
        when (c) {
            is LoginConfig.Xtream -> {
                val s = XtreamSource(c.server, c.username, c.password, null)
                val tz = s.login()
                prefs.saveLogin(c.copy(server = s.base, timezone = tz))
                prefs.userAgent = Http.userAgent
                source = s
            }
            is LoginConfig.M3u -> {
                val s = M3uSource(c.url, c.epgUrl)
                if (s.load().isEmpty()) throw IOException("That playlist is empty or isn't an M3U playlist.")
                prefs.saveLogin(c)
                source = s
            }
        }
        epgCache.clear()
        liveCats = null
        liveAll = null
        cacheFile(context).delete()
    }

    fun logout(context: Context) {
        Prefs(context).clearLogin()
        refreshJob?.cancel()
        source = null
        epgCache.clear()
        liveCats = null
        liveAll = null
        cacheFile(context).delete()
    }

    /** Called when the app opens: show the saved list right away, then update it quietly. */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (liveAll == null) {
                // Reading the saved list can take a moment on big playlists: do it off the screen thread.
                val saved = withContext(Dispatchers.IO) { readSaved(app) }
                if (saved != null && liveAll == null) { liveCats = saved.first; liveAll = saved.second }
            }
            // Settings › Playlists › Update on app start (always when nothing is saved yet).
            if (liveAll == null || AppSettings.playlistOnStart) refreshInBackground(app)
            if (AppSettings.epgOnStart) importPastGuide(app, force = true)
        }
    }

    fun refreshInBackground(context: Context) {
        if (refreshJob?.isActive == true) return
        val src = source ?: return
        refreshJob = scope.launch {
            refreshing = true
            try {
                val (cats, all) = withContext(Dispatchers.Default) {
                    src.clearCache()
                    // A channel listed twice breaks the lists (crash while scrolling): keep the first.
                    src.liveCategories().distinctBy { it.id } to src.allLiveChannels().distinctBy { it.id }
                }
                liveCats = cats
                liveAll = all
                liveError = null
                epgCache.clear()
                withContext(Dispatchers.IO) { save(context, cats, all) }
                importPastGuide(context)
                loadEpgSources(context)
            } catch (e: Exception) {
                if (liveAll == null) liveError = e.message ?: "Couldn't load channels"
            } finally {
                refreshing = false
            }
        }
    }

    /** Bumped when earlier days' show names arrive, so open guides reload. */
    var guideVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private var pastJob: kotlinx.coroutines.Job? = null

    /**
     * Nov 13: names of earlier days' shows for catch-up channels. Many providers' per-channel guide only
     * starts today, so the full XMLTV guide is read (only catch-up channels, last 4 days) and saved into
     * the on-box show history. At most every 3 hours.
     */
    fun importPastGuide(context: Context, force: Boolean = false) {
        if (pastJob?.isActive == true) return
        val src = source as? XtreamSource ?: return
        val sp = context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("past_guide_at", 0L) < AppSettings.epgEveryH.coerceAtLeast(1) * 3_600_000L) return
        val days = AppSettings.epgPastDays.coerceIn(1, 7)
        pastJob = scope.launch(Dispatchers.IO) {
            try {
                // Catch-up channels, matched by guide id — or by name when the provider gave no id (Nov 15).
                val arch = liveAll.orEmpty().filter { it.hasArchive }
                if (arch.isEmpty()) return@launch
                val byEpg = arch.filter { !it.epgId.isNullOrBlank() }.groupBy { it.epgId!!.lowercase() }
                val byName = arch.groupBy { it.name.trim().lowercase() }
                val nameIds = HashMap<String, String>()
                val map = Http.stream(src.xmltvUrl()) {
                    XmltvParser.parse(it, now - days * 86_400_000L, now, byEpg.keys, byName.keys, nameIds)
                }
                var n = 0
                val done = HashSet<String>()
                fun save(ch: LiveChannel, progs: List<Program>) {
                    if (!done.add(ch.id)) return
                    val past = progs.filter { it.start <= now }.map { it.copy(hasArchive = true) }
                    if (past.isEmpty()) return
                    val current = epgCache[ch.id]?.second.orEmpty().filter { it.end > now - 3_600_000L }
                    withHistory(ch.id, (past.filter { p -> current.none { it.start < p.end && it.end > p.start } } + current).sortedBy { it.start })
                    n++
                }
                for ((k, progs) in map) byEpg[k]?.forEach { save(it, progs) }
                for ((name, id) in nameIds) map[id]?.let { progs -> byName[name]?.forEach { save(it, progs) } }
                sp.edit().putLong("past_guide_at", now).apply()
                AppSettings.set("epg_status", "EPG updated for $n of ${arch.size} catch-up channels on " +
                    java.text.SimpleDateFormat("M/d/yyyy h:mm a", java.util.Locale.US).format(java.util.Date(now)))
                if (n > 0) { epgCache.clear(); withContext(Dispatchers.Main) { guideVersion++ } }
            } catch (e: Exception) {
                // No XMLTV on this provider: the hour-by-hour list still works.
                AppSettings.set("epg_status", "Last update failed: ${e.message ?: "no answer"}")
            }
        }
    }

    // ---- Settings › EPG › EPG sources (Nov 15): your own XMLTV guides fill in what the provider's guide is missing ----
    @Volatile private var extGuide: Map<String, List<Program>> = emptyMap()
    private var extJob: Job? = null

    private fun extFor(ch: LiveChannel): List<Program> =
        ch.epgId?.lowercase()?.let { extGuide[it] } ?: extGuide[ch.name.lowercase()] ?: emptyList()

    fun loadEpgSources(context: Context, force: Boolean = false) {
        val urls = AppSettings.epgSources()
        if (urls.isEmpty()) { if (extGuide.isNotEmpty()) { extGuide = emptyMap(); epgCache.clear(); guideVersion++ }; return }
        if (extJob?.isActive == true) return
        val sp = context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && extGuide.isNotEmpty() && now - sp.getLong("ext_epg_at", 0L) < AppSettings.epgEveryH.coerceAtLeast(1) * 3_600_000L) return
        val all = liveAll ?: return
        extJob = scope.launch(Dispatchers.IO) {
            val keys = HashSet<String>()
            all.forEach { c -> c.epgId?.lowercase()?.let { keys += it }; keys += c.name.lowercase() }
            val days = AppSettings.epgPastDays.coerceIn(1, 7)
            val map = HashMap<String, MutableList<Program>>()
            var ok = 0
            for (u in urls) {
                try {
                    val part = Http.stream(u) { raw ->
                        val b = java.io.BufferedInputStream(raw)
                        b.mark(4)
                        val gz = b.read() == 0x1f && b.read() == 0x8b
                        b.reset()
                        XmltvParser.parse(if (gz) java.util.zip.GZIPInputStream(b) else b, now - days * 86_400_000L, now + 4 * 86_400_000L, keys)
                    }
                    part.forEach { (k, v) -> map.getOrPut(k) { ArrayList() } += v }
                    ok++
                } catch (e: Exception) { }
            }
            extGuide = map.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } }
            sp.edit().putLong("ext_epg_at", now).apply()
            AppSettings.set("epg_status", "EPG sources: $ok of ${urls.size} loaded · ${map.size} channels · " +
                java.text.SimpleDateFormat("M/d h:mm a", java.util.Locale.US).format(java.util.Date(now)))
            epgCache.clear()
            withContext(Dispatchers.Main) { guideVersion++ }
        }
    }

    /** Settings › EPG › Clear EPG: forgets saved shows and reloads the guide. */
    fun clearEpg(context: Context) {
        try { histDir?.listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
        epgCache.clear()
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE).edit().remove("past_guide_at").apply()
        AppSettings.set("epg_status", "EPG cleared")
        guideVersion++
    }

    /** Nov 16 (loading screen): waits until the playlist refresh that's running (if any) is finished. */
    suspend fun awaitRefresh() { refreshJob?.join() }

    fun channelsIn(categoryId: String): List<LiveChannel> = liveAll.orEmpty().filter { it.categoryId == categoryId }

    fun channelById(id: String): LiveChannel? = liveAll?.firstOrNull { it.id == id }

    private fun cacheFile(context: Context) = File(context.filesDir, "live_cache.json")

    private fun readSaved(context: Context): Pair<List<Category>, List<LiveChannel>>? {
        return try {
            val f = cacheFile(context)
            if (!f.exists()) return null
            val j = JSONObject(f.readText())
            val ca = j.getJSONArray("cats")
            val cats = (0 until ca.length()).map { i ->
                val o = ca.getJSONObject(i); Category(o.getString("id"), o.getString("name"))
            }.distinctBy { it.id }
            val ch = j.getJSONArray("ch")
            val all = (0 until ch.length()).map { i ->
                val o = ch.getJSONObject(i)
                LiveChannel(
                    id = o.getString("id"), num = o.optInt("num"), name = o.getString("name"),
                    logo = o.optString("logo").ifEmpty { null }, categoryId = o.optString("cat"),
                    epgId = o.optString("epg").ifEmpty { null }, hasArchive = o.optBoolean("arc"),
                    archiveDays = o.optInt("days"), url = o.getString("url"),
                )
            }.distinctBy { it.id }
            cats to all
        } catch (e: Exception) {
            null
        }
    }

    private fun save(context: Context, cats: List<Category>, all: List<LiveChannel>) {
        val ca = JSONArray()
        cats.forEach { ca.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val ch = JSONArray()
        all.forEach {
            ch.put(JSONObject().put("id", it.id).put("num", it.num).put("name", it.name).put("logo", it.logo ?: "")
                .put("cat", it.categoryId).put("epg", it.epgId ?: "").put("arc", it.hasArchive)
                .put("days", it.archiveDays).put("url", it.url))
        }
        val tmp = File(context.filesDir, "live_cache.tmp")
        tmp.writeText(JSONObject().put("cats", ca).put("ch", ch).toString())
        tmp.renameTo(cacheFile(context))
    }

    fun refresh(context: Context) {
        source?.clearCache()
        epgCache.clear()
        refreshInBackground(context)
    }

    /** EPG for one channel, cached for 15 minutes, max 6 requests at once. */
    suspend fun epg(channel: LiveChannel): List<Program> {
        val now = System.currentTimeMillis()
        epgCache[channel.id]?.let { (time, list) -> if (now - time < 15 * 60_000) return list }
        val src = source ?: return emptyList()
        val list = try {
            // Download and decode the guide off the screen thread so scrolling stays smooth.
            epgLimiter.withPermit { withContext(Dispatchers.Default) { withHistory(channel.id, src.epg(channel)) } }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { withHistory(channel.id, emptyList()) }
        }
        // Shows from your own EPG sources fill the gaps.
        val ext = extFor(channel)
        val merged = if (ext.isEmpty()) list else (list + ext.filter { e -> list.none { it.start < e.end && it.end > e.start } }
            .map { if (channel.hasArchive && it.end <= now) it.copy(hasArchive = true) else it }).sortedBy { it.start }
        epgCache[channel.id] = now to merged
        return merged
    }

    /**
     * Keeps 2 days of what each channel played: providers often drop past shows from their guide,
     * so shows seen before are saved on the box and merged back in (new info wins).
     */
    private fun withHistory(channelId: String, fresh: List<Program>): List<Program> {
        val dir = histDir ?: return fresh
        val now = System.currentTimeMillis()
        val cutoff = now - AppSettings.epgPastDays.coerceIn(1, 7) * 86_400_000L
        val f = File(dir, channelId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
        val old = try {
            if (!f.exists()) emptyList() else {
                val a = JSONArray(f.readText())
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    Program(o.optString("t"), o.optString("d"), o.optLong("s"), o.optLong("e"), o.optBoolean("a"))
                }
            }
        } catch (e: Exception) { emptyList() }
        val freshStarts = fresh.map { it.start }.toHashSet()
        // A saved show is dropped if a fresh one overlaps it (the guide was corrected).
        val kept = old.filter { p -> p.end > cutoff && p.start !in freshStarts && fresh.none { it.start < p.end && it.end > p.start } }
        val merged = (kept + fresh).filter { it.end > cutoff }.sortedBy { it.start }
        // Save the shows that already started (the future comes from the provider anyway).
        try {
            dir.mkdirs()
            val a = JSONArray()
            val desc = AppSettings.epgDescriptions
            merged.filter { it.start <= now }.forEach {
                a.put(JSONObject().put("t", it.title).put("d", if (desc) it.description else "").put("s", it.start).put("e", it.end).put("a", it.hasArchive))
            }
            f.writeText(a.toString())
        } catch (e: Exception) { /* storage full: just show what we have */ }
        return merged
    }

    fun cachedEpg(channel: LiveChannel): List<Program>? = epgCache[channel.id]?.second
}
