package com.forbiddenstreams.tv.data

import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
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

    val base: String = normalizeServer(server)

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

    /** Checks the account. Returns the server's time zone (used for catch-up links). */
    suspend fun login(): String? {
        val text = Http.get(api())
        val j = jsonObject(text) ?: throw IOException("That doesn't look like an Xtream Codes server. Check the server address.")
        val ui = j.optJSONObject("user_info") ?: throw IOException("That doesn't look like an Xtream Codes server. Check the server address.")
        if (ui.int("auth") != 1) throw IOException("Login failed. Check your username and password.")
        val status = ui.str("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Your account status is: $status")
        }
        timezone = j.optJSONObject("server_info")?.str("timezone")
        return timezone
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
        if (list.isEmpty()) {
            val short = jsonObject(Http.get(api("get_short_epg", "&stream_id=${channel.id}&limit=30")))
            list = parseListings(short, channel)
        }
        return list.sortedBy { it.start }.distinctBy { it.start }
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

    override suspend fun vodItems(categoryId: String): List<VodItem> {
        vodByCat[categoryId]?.let { return it }
        val arr = jsonArray(Http.get(api("get_vod_streams", "&category_id=${Http.enc(categoryId)}")))
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
        vodByCat[categoryId] = out
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
        val arr = jsonArray(Http.get(api("get_series", "&category_id=${Http.enc(categoryId)}")))
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
        seriesByCat[categoryId] = out
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
        return out.sortedWith(compareBy({ it.season }, { it.episodeNum }))
    }

    override fun episodeUrl(episode: Episode): String =
        episode.directUrl ?: "$base/series/$user/$pass/${episode.id}.${episode.ext}"

    override fun clearCache() {
        liveCats = null; allLive = null; liveByCat.clear()
        vodCats = null; vodByCat.clear()
        seriesCats = null; seriesByCat.clear()
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
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore("/player_api.php").substringBefore("/get.php")
            return s.trimEnd('/')
        }
    }
}
