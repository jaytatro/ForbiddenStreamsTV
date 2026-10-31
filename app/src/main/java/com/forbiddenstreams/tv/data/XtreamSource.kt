package com.forbiddenstreams.tv.data

import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class XtreamSource(
    server: String,
    private val user: String,
    private val pass: String,
    var timezone: String?,
) : ContentSource {

    var base: String = normalizeServer(server)
        private set

    private val lock = Mutex()
    private var liveCats: List<Category>? = null
    private var allLive: List<LiveChannel>? = null
    private val liveByCat = HashMap<String, List<LiveChannel>>()
    private var vodCats: List<Category>? = null
    private val vodByCat = HashMap<String, List<VodItem>>()
    private var seriesCats: List<Category>? = null
    private val seriesByCat = HashMap<String, List<SeriesItem>>()

    private fun api(action: String? = null, extra: String = ""): String =
        "$base/player_api.php?username=${Http.enc(user)}&password=${Http.enc(pass)}" +
            (if (action != null) "&action=$action" else "") + extra

    /**
     * Checks the account. Returns the server's time zone (used for catch-up links).
     * If the address as typed doesn't answer like an Xtream Codes server, common ports
     * (8080, 80, 25461, 8000, 2095, 2082) and http/https are tried, with a few app
     * names (some providers only answer known IPTV apps). What works is kept.
     */
    suspend fun login(): String? = kotlinx.coroutines.coroutineScope {
        val bases = candidateBases(base)
        val agents = listOf(Http.DEFAULT_USER_AGENT, "IPTVSmartersPro", "okhttp/4.12.0", "VLC/3.0.20 LibVLC/3.0.20")
        var lastAnswer: String? = null
        // First the address exactly as typed (one request at a time), then the other ports together.
        val rounds = agents.map { listOf(bases.first()) to it } + agents.map { bases.drop(1) to it }
        for ((group, ua) in rounds) {
            if (group.isEmpty()) continue
            val results = group.map { b ->
                async { b to Http.probe("$b/player_api.php?username=${Http.enc(user)}&password=${Http.enc(pass)}", ua) }
            }.map { it.await() }
            for ((b, r) in results) {
                if (r == null) continue
                val (code, body) = r
                val j = jsonObject(body)
                val ui = j?.optJSONObject("user_info")
                if (ui == null) {
                    if (b == bases.first()) lastAnswer = when {
                        code == 401 || code == 403 -> "the server refused (HTTP $code)"
                        code >= 400 -> "HTTP $code"
                        body.trimStart().startsWith("<") -> "a web page, not an IPTV login"
                        body.isBlank() -> "an empty answer"
                        else -> "something that isn't an Xtream login"
                    }
                    continue
                }
                // Found the server.
                base = b
                Http.userAgent = ua
                if (ui.int("auth") != 1) throw IOException("Login failed. Check your username and password.")
                val status = ui.str("status")
                if (status != null && !status.equals("Active", ignoreCase = true)) {
                    throw IOException("Your account status is: $status")
                }
                timezone = j.optJSONObject("server_info")?.str("timezone")
                return@coroutineScope timezone
            }
        }
        throw IOException(
            if (lastAnswer == null) "Couldn't reach $base. Check the server address and your internet connection."
            else "No Xtream Codes login found at $base (it answered with $lastAnswer). " +
                "Check the address and port in your provider's details, e.g. http://server.com:8080",
        )
    }

    // ---------- Live ----------

    override suspend fun liveCategories(): List<Category> = lock.withLock {
        liveCats ?: parseCategories(Http.get(api("get_live_categories"))).also { liveCats = it }
    }

    override suspend fun allLiveChannels(): List<LiveChannel> {
        allLive?.let { return it }
        val list = parseLive(Http.get(api("get_live_streams")))
        allLive = list
        return list
    }

    override suspend fun liveChannels(categoryId: String): List<LiveChannel> {
        liveByCat[categoryId]?.let { return it }
        val list = parseLive(Http.get(api("get_live_streams", "&category_id=${Http.enc(categoryId)}")))
        liveByCat[categoryId] = list
        return list
    }

    private fun parseLive(text: String): List<LiveChannel> {
        val arr = jsonArray(text)
        val out = ArrayList<LiveChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("stream_id") ?: continue
            out += LiveChannel(
                id = id,
                num = o.int("num", i + 1),
                name = o.str("name") ?: "Channel $id",
                logo = o.str("stream_icon"),
                categoryId = o.str("category_id") ?: "",
                epgId = o.str("epg_channel_id"),
                hasArchive = o.int("tv_archive") == 1,
                archiveDays = o.int("tv_archive_duration"),
                url = "$base/live/$user/$pass/$id.ts",
            )
        }
        return out
    }

    override suspend fun epg(channel: LiveChannel): List<Program> {
        val table = jsonObject(Http.get(api("get_simple_data_table", "&stream_id=${channel.id}")))
        var list = parseListings(table, channel)
        val now = System.currentTimeMillis()
        if (list.none { now >= it.start && now < it.end }) {
            // Some providers' full table misses today's shows: add the short guide.
            val short = try {
                parseListings(jsonObject(Http.get(api("get_short_epg", "&stream_id=${channel.id}&limit=50"))), channel)
            } catch (e: Exception) { emptyList() }
            list = list + short
        }
        // Past shows on a catch-up channel can be played back (within its catch-up days).
        val days = if (channel.archiveDays > 0) channel.archiveDays else 7
        return list.sortedBy { it.start }.distinctBy { it.start }.map { p ->
            if (channel.hasArchive && p.end <= now && p.start >= now - days * 86_400_000L) p.copy(hasArchive = true) else p
        }
    }

    private fun parseListings(j: JSONObject?, channel: LiveChannel): List<Program> {
        val arr = j?.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Program>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = (o.str("start_timestamp")?.toLongOrNull() ?: continue) * 1000
            val end = (o.str("stop_timestamp")?.toLongOrNull() ?: continue) * 1000
            if (end <= start) continue
            out += Program(
                title = b64(o.str("title")) ?: "No title",
                description = b64(o.str("description")) ?: "",
                start = start,
                end = end,
                hasArchive = o.int("has_archive") == 1 || (channel.hasArchive && end < System.currentTimeMillis()),
            )
        }
        return out
    }

    private fun b64(s: String?): String? {
        if (s == null) return null
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim().ifEmpty { null }
        } catch (e: Exception) {
            s
        }
    }

    override fun catchupUrl(channel: LiveChannel, program: Program): String? {
        val now = System.currentTimeMillis()
        if (!channel.hasArchive || program.start > now) return null
        val minutes = ((program.end - program.start) / 60000).coerceAtLeast(1)
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        fmt.timeZone = timezone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        val start = fmt.format(Date(program.start))
        return "$base/timeshift/$user/$pass/$minutes/$start/${channel.id}.ts"
    }

    // ---------- Movies ----------

    override suspend fun vodCategories(): List<Category> =
        vodCats ?: parseCategories(Http.get(api("get_vod_categories"))).also { vodCats = it }

    private var allVodCache: List<VodItem>? = null
    private var allSeriesCache: List<SeriesItem>? = null

    // The full movie / show lists can be huge (tens of MB): read them piece by piece instead of
    // loading the whole text into memory, which crashed the app when searching (out of memory).
    override suspend fun allVod(): List<VodItem> =
        allVodCache ?: streamVod(api("get_vod_streams"), "").also { allVodCache = it }

    override suspend fun allSeries(): List<SeriesItem> =
        allSeriesCache ?: streamSeries(api("get_series"), "").also { allSeriesCache = it }

    private suspend fun streamVod(url: String, categoryId: String): List<VodItem> =
        streamObjects(url, setOf("stream_id", "name", "stream_icon", "rating", "category_id", "container_extension")) { o ->
            val id = o["stream_id"] ?: return@streamObjects null
            VodItem(
                id = id, name = o["name"] ?: "Movie", poster = o["stream_icon"], rating = o["rating"],
                categoryId = o["category_id"] ?: categoryId, ext = o["container_extension"] ?: "mp4",
            )
        }

    private suspend fun streamSeries(url: String, categoryId: String): List<SeriesItem> =
        streamObjects(url, setOf("series_id", "name", "cover", "plot", "rating", "category_id")) { o ->
            val id = o["series_id"] ?: return@streamObjects null
            SeriesItem(
                id = id, name = o["name"] ?: "Show", cover = o["cover"], plot = o["plot"]?.take(400),
                rating = o["rating"], categoryId = o["category_id"] ?: categoryId,
            )
        }

    /** Reads a JSON array of objects one at a time, keeping only the [fields] needed. */
    private suspend fun <T> streamObjects(url: String, fields: Set<String>, make: (Map<String, String>) -> T?): List<T> =
        Http.stream(url) { input ->
            val out = ArrayList<T>()
            android.util.JsonReader(java.io.InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.isLenient = true
                if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) return@stream out
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    r.beginObject()
                    val o = HashMap<String, String>()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if (name !in fields) { r.skipValue(); continue }
                        val v: String? = when (r.peek()) {
                            android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> r.nextString()
                            android.util.JsonToken.BOOLEAN -> r.nextBoolean().toString()
                            android.util.JsonToken.NULL -> { r.nextNull(); null }
                            else -> { r.skipValue(); null }
                        }
                        val t = v?.trim()
                        if (!t.isNullOrEmpty() && t != "null") o[name] = t
                    }
                    r.endObject()
                    make(o)?.let { out += it }
                }
            }
            out
        }

    override suspend fun vodItems(categoryId: String): List<VodItem> {
        vodByCat[categoryId]?.let { return it }
        val out = streamVod(api("get_vod_streams", "&category_id=${Http.enc(categoryId)}"), categoryId)
        vodByCat[categoryId] = out
        return out
    }

    private fun parseVod(text: String, categoryId: String): List<VodItem> {
        val arr = jsonArray(text)
        val out = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("stream_id") ?: continue
            out += VodItem(
                id = id,
                name = o.str("name") ?: "Movie",
                poster = o.str("stream_icon"),
                rating = o.str("rating"),
                categoryId = o.str("category_id") ?: categoryId,
                ext = o.str("container_extension") ?: "mp4",
            )
        }
        return out
    }

    override suspend fun vodDetails(item: VodItem): VodDetails? {
        val j = jsonObject(Http.get(api("get_vod_info", "&vod_id=${item.id}"))) ?: return null
        val info = j.optJSONObject("info") ?: return null
        return VodDetails(
            plot = info.str("plot") ?: info.str("description"),
            duration = info.str("duration"),
            genre = info.str("genre"),
            cast = info.str("cast") ?: info.str("actors"),
            year = info.str("releasedate")?.take(4) ?: info.str("year"),
        )
    }

    override fun vodUrl(item: VodItem): String = "$base/movie/$user/$pass/${item.id}.${item.ext}"

    // ---------- Series ----------

    override suspend fun seriesCategories(): List<Category> =
        seriesCats ?: parseCategories(Http.get(api("get_series_categories"))).also { seriesCats = it }

    override suspend fun seriesItems(categoryId: String): List<SeriesItem> {
        seriesByCat[categoryId]?.let { return it }
        val out = streamSeries(api("get_series", "&category_id=${Http.enc(categoryId)}"), categoryId)
        seriesByCat[categoryId] = out
        return out
    }

    private fun parseSeries(text: String, categoryId: String): List<SeriesItem> {
        val arr = jsonArray(text)
        val out = ArrayList<SeriesItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("series_id") ?: continue
            out += SeriesItem(
                id = id,
                name = o.str("name") ?: "Show",
                cover = o.str("cover"),
                plot = o.str("plot"),
                rating = o.str("rating"),
                categoryId = o.str("category_id") ?: categoryId,
            )
        }
        return out
    }

    override suspend fun episodes(series: SeriesItem): List<Episode> {
        val j = jsonObject(Http.get(api("get_series_info", "&series_id=${series.id}"))) ?: return emptyList()
        val eps = j.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        val keys = eps.keys()
        while (keys.hasNext()) {
            val seasonKey = keys.next()
            val arr = eps.optJSONArray(seasonKey) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id") ?: continue
                val info = o.optJSONObject("info")
                out += Episode(
                    id = id,
                    title = o.str("title") ?: "Episode ${i + 1}",
                    season = o.int("season", seasonKey.toIntOrNull() ?: 1),
                    episodeNum = o.int("episode_num", i + 1),
                    ext = o.str("container_extension") ?: "mp4",
                    plot = info?.str("plot"),
                    image = info?.str("movie_image"),
                )
            }
        }
        return out.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episodeNum }))
    }

    override fun episodeUrl(episode: Episode): String =
        episode.directUrl ?: "$base/series/$user/$pass/${episode.id}.${episode.ext}"

    override fun clearCache() {
        liveCats = null; allLive = null; liveByCat.clear()
        vodCats = null; vodByCat.clear()
        seriesCats = null; seriesByCat.clear()
        allVodCache = null; allSeriesCache = null
    }

    private fun parseCategories(text: String): List<Category> {
        val arr = jsonArray(text)
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("category_id") ?: continue
            out += Category(id, o.str("category_name") ?: "Group $id")
        }
        return out
    }

    companion object {
        /** The address as typed first, then other ports / http(s) for the same host. */
        fun candidateBases(b: String): List<String> {
            val out = LinkedHashSet<String>()
            out += b
            val u = try { java.net.URI(b) } catch (e: Exception) { return out.toList() }
            val host = u.host ?: return out.toList()
            val path = (u.rawPath ?: "").trimEnd('/')
            val scheme = u.scheme ?: "http"
            val other = if (scheme == "https") "http" else "https"
            if (u.port == -1) {
                for (p in listOf(8080, 80, 25461, 8000, 2095, 2082)) out += "http://$host:$p$path"
                out += "$other://$host$path"
            } else {
                out += "$other://$host:${u.port}$path"
                out += "$scheme://$host$path"
            }
            return out.toList()
        }

        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore("/player_api.php").substringBefore("/get.php")
            return s.trimEnd('/')
        }
    }
}
