package com.forbiddenstreams.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** A show from TVmaze's US schedule. */
data class MazeShow(val id: Int, val name: String, val premiered: String?, val lastAired: String, val image: String?, val summary: String?)

/**
 * TVmaze (tvmaze.com): free, no account, no key. "New TV Shows" on Home comes from its US
 * schedule (TV networks + US streaming) for the last week: shows that premiered this year first,
 * newest first, then other shows with new episodes. Only English-language, US shows.
 */
object TvMaze {
    private const val BASE = "https://api.tvmaze.com/"
    private val lock = Mutex()
    private var cached: Pair<Long, List<MazeShow>>? = null

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(Request.Builder().url(BASE + path).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (e: Exception) { null }
    }

    private fun isUs(show: JSONObject): Boolean {
        if (show.optString("language") != "English") return false
        val net = show.optJSONObject("network")?.optJSONObject("country")?.optString("code")
        val web = show.optJSONObject("webChannel")
        val webCountry = web?.optJSONObject("country")?.optString("code")
        return net == "US" || (net == null && web != null && (webCountry == null || webCountry == "" || webCountry == "US"))
    }

    /** New and currently airing US shows (cached for 6 hours). */
    suspend fun newShows(): List<MazeShow> = lock.withLock {
        cached?.let { (t, list) -> if (System.currentTimeMillis() - t < 6 * 3_600_000L && list.isNotEmpty()) return list }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val shows = LinkedHashMap<Int, MazeShow>()
        for (d in 0 until 7) {
            val day = fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) }.time)
            for (path in listOf("schedule?country=US&date=$day", "schedule/web?date=$day")) {
                val arr = get(path)?.let { try { JSONArray(it) } catch (e: Exception) { null } } ?: continue
                for (i in 0 until arr.length()) {
                    val ep = arr.optJSONObject(i) ?: continue
                    val show = ep.optJSONObject("show") ?: ep.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
                    if (!isUs(show)) continue
                    val id = show.optInt("id")
                    val aired = ep.optString("airdate")
                    val prev = shows[id]
                    if (prev == null || aired > prev.lastAired) {
                        shows[id] = MazeShow(
                            id = id,
                            name = show.optString("name"),
                            premiered = show.optString("premiered").takeIf { it.length >= 4 },
                            lastAired = aired,
                            image = show.optJSONObject("image")?.optString("original")?.takeIf { it.startsWith("http") },
                            summary = show.optString("summary").replace(Regex("<[^>]+>"), "").takeIf { it.isNotBlank() },
                        )
                    }
                }
                delay(150) // TVmaze allows about 20 requests per 10 seconds
            }
        }
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        val (newThisYear, others) = shows.values.partition { it.premiered?.startsWith(year) == true }
        val list = newThisYear.sortedByDescending { it.premiered } + others.sortedByDescending { it.lastAired }
        cached = System.currentTimeMillis() to list
        list
    }
}
