package com.forbiddenstreams.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/** A movie from The Movie Database (TMDB). */
data class TmdbMovie(
    val id: Int,
    val title: String,
    val year: String?,
    val poster: String?,
    val backdrop: String?,
    val overview: String,
    val rating: Double,
)

/**
 * The Movie Database (themoviedb.org): In Theaters and genre lists for the Home screen.
 * Needs the user's own free TMDB key (v3 "API Key" or v4 "Read Access Token"), saved in Settings.
 */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3/"
    const val IMG = "https://image.tmdb.org/t/p/"

    /** Genre rows on Home, in this order (TMDB genre ids). */
    val GENRES = listOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80,
        "Documentary" to 99, "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36,
        "Horror" to 27, "Music" to 10402, "Mystery" to 9648, "Romance" to 10749,
        "Science Fiction" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37,
    )

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)

    fun key(context: Context): String? = sp(context).getString("tmdb_key", null)?.trim()?.takeIf { it.isNotEmpty() }
    fun setKey(context: Context, key: String?) {
        sp(context).edit().putString("tmdb_key", key?.trim()).apply()
        cache.clear()
    }

    private val cache = HashMap<String, List<TmdbMovie>>()
    private val lock = Mutex()

    /** [path] like "movie/now_playing"; [query] like "region=US". Cached for the app session. */
    suspend fun movies(context: Context, path: String, query: String = "", pages: Int = 1): List<TmdbMovie> {
        val key = key(context) ?: throw IOException("No TMDB key")
        val ck = "$path?$query#$pages"
        lock.withLock { cache[ck] }?.let { return it }
        val out = ArrayList<TmdbMovie>()
        for (page in 1..pages) {
            val bearer = key.length > 60 // v4 read token
            val url = BASE + path + "?language=en-US&page=$page" + (if (query.isNotEmpty()) "&$query" else "") +
                (if (bearer) "" else "&api_key=$key")
            val text = withContext(Dispatchers.IO) {
                val b = Request.Builder().url(url).header("Accept", "application/json")
                if (bearer) b.header("Authorization", "Bearer $key")
                Http.client.newCall(b.build()).execute().use { r ->
                    if (r.code == 401) throw IOException("TMDB didn't accept the key. Check it in Settings.")
                    if (!r.isSuccessful) throw IOException("TMDB answered HTTP ${r.code}")
                    r.body?.string() ?: ""
                }
            }
            val arr = jsonObject(text)?.optJSONArray("results") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += TmdbMovie(
                    id = o.optInt("id"),
                    title = o.str("title") ?: continue,
                    year = o.str("release_date")?.take(4),
                    poster = o.str("poster_path")?.let { IMG + "w342" + it },
                    backdrop = o.str("backdrop_path")?.let { IMG + "w780" + it },
                    overview = o.str("overview") ?: "",
                    rating = o.optDouble("vote_average", 0.0),
                )
            }
        }
        val list = out.distinctBy { it.id }
        lock.withLock { cache[ck] = list }
        return list
    }
}

/**
 * Finds a TMDB movie in the provider's movie list, by title (and year when both have one).
 * Provider names are cleaned of tags like "EN - ", "[4K]", "(2024)", "HD".
 */
object MovieMatcher {
    private var index: Map<String, List<VodItem>>? = null
    private var newest: List<VodItem> = emptyList()
    private val lock = Mutex()

    private val yearRx = Regex("(19|20)\\d{2}")
    private val tagRx = Regex("^\\s*(\\[[^\\]]{1,12}\\]|\\|[^|]{1,12}\\||[A-Z0-9]{2,4}\\s*[-:|])\\s*")
    private val junkRx = Regex("\\b(4k|uhd|fhd|hd|sd|hevc|x265|1080p|720p|2160p|multi|sub|dub)\\b")

    fun key(title: String): String {
        var t = title
        repeat(3) { t = t.replace(tagRx, "") }
        t = t.lowercase(Locale.US).replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(yearRx, " ").replace(junkRx, " ").replace("&", "and")
        return t.filter { it.isLetterOrDigit() }
    }

    private fun yearOf(name: String): String? = yearRx.findAll(name).lastOrNull()?.value

    val ready: Boolean get() = index != null

    /** Loads the provider's movie list once (in the background). */
    suspend fun load(): Boolean = lock.withLock {
        if (index != null) return true
        val src = Session.source ?: return false
        val all = try { src.allVod() } catch (e: Throwable) { return false }
        index = all.groupBy { key(it.name) }
        newest = all.filter { it.added > 0 }.sortedByDescending { it.added }.distinctBy { key(it.name) }
        true
    }

    fun find(m: TmdbMovie): VodItem? {
        val cands = index?.get(key(m.title)) ?: return null
        return cands.firstOrNull { m.year != null && yearOf(it.name) == m.year } ?: cands.first()
    }

    /** Movies the provider added most recently ("New in HD"). */
    fun newest(limit: Int = 40): List<VodItem> = newest.take(limit)

    fun reset() { index = null; newest = emptyList() }
}
