package com.forbiddenstreams.tv.sports

import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.data.jsonObject
import com.forbiddenstreams.tv.data.str
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class League(val label: String, val icon: String, val espnPath: String, val groupName: String) {
    NFL("NFL", "🏈", "football/nfl", "USA | NFL Teams"),
    NBA("NBA", "🏀", "basketball/nba", "USA | NBA Teams"),
    NHL("NHL", "🏒", "hockey/nhl", "USA | NHL Teams"),
    MLB("MLB", "⚾", "baseball/mlb", "USA | MLB Teams"),
}

data class Team(
    val displayName: String,
    val nickname: String,
    val location: String,
    val abbreviation: String,
    val logo: String?,
    val score: String?,
    val winner: Boolean,
)

data class Game(
    val id: String,
    val start: Long,
    /** "pre", "in" or "post" */
    val state: String,
    val detail: String,
    val home: Team,
    val away: Team,
    val venue: String?,
    val networks: List<String>,
) {
    val isLive get() = state == "in"
    val isFinal get() = state == "post"
}

object Espn {
    private fun dayKey(d: Date) = SimpleDateFormat("yyyyMMdd", Locale.US).format(d)

    fun dayKey(millis: Long) = dayKey(Date(millis))

    private fun shiftDays(days: Int): Date = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }.time

    /**
     * Games from [fromDays] to [toDays] around today, grouped by local day key (yyyyMMdd).
     * ESPN rejects date ranges ("Failed to get events endpoint"), so each day is fetched
     * on its own, all at the same time.
     */
    suspend fun week(league: League, fromDays: Int = -3, toDays: Int = 3): Map<String, List<Game>> =
        days(league, (fromDays..toDays).toList()).groupBy { dayKey(it.start) }

    private suspend fun days(league: League, offsets: List<Int>): List<Game> = coroutineScope {
        val results = offsets.map { d ->
            async { runCatching { fetch(league, "dates=${dayKey(shiftDays(d))}") } }
        }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }.sortedBy { it.start }
    }

    /** Next game start time after the given window, or null if none found. */
    suspend fun nextGameAfter(league: League, afterDays: Int = 3): Long? {
        val now = System.currentTimeMillis()
        try {
            days(league, ((afterDays + 1)..(afterDays + 14)).toList()).firstOrNull { it.start > now }?.let { return it.start }
        } catch (e: Exception) { /* fall through to the calendar */ }
        // Off-season: look through the league calendar for the next game day.
        val j = jsonObject(Http.get(url(league, ""))) ?: return null
        var best: Long? = null
        fun scan(any: Any?) {
            when (any) {
                is JSONArray -> for (i in 0 until any.length()) scan(any.opt(i))
                is JSONObject -> { val k = any.keys(); while (k.hasNext()) scan(any.opt(k.next())) }
                is String -> parseTime(any)?.let { t -> if (t > now && (best == null || t < best!!)) best = t }
            }
        }
        scan(j.optJSONArray("leagues")?.optJSONObject(0)?.opt("calendar"))
        return best
    }

    private fun url(league: League, query: String) =
        "https://site.api.espn.com/apis/site/v2/sports/${league.espnPath}/scoreboard" + if (query.isEmpty()) "" else "?$query"

    private suspend fun fetch(league: League, query: String): List<Game> = withContext(Dispatchers.Default) { fetchNow(league, query) }

    private suspend fun fetchNow(league: League, query: String): List<Game> {
        val j = jsonObject(Http.get(url(league, query))) ?: return emptyList()
        val events = j.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            parseGame(ev)?.let { out += it }
        }
        return out.sortedBy { it.start }
    }

    private fun parseGame(ev: JSONObject): Game? {
        val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = comp.optJSONArray("competitors") ?: return null
        var home: Team? = null
        var away: Team? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.optJSONObject(i) ?: continue
            val t = c.optJSONObject("team") ?: continue
            val team = Team(
                displayName = t.str("displayName") ?: t.str("name") ?: "?",
                nickname = t.str("name") ?: t.str("shortDisplayName") ?: "",
                location = t.str("location") ?: "",
                abbreviation = t.str("abbreviation") ?: "",
                logo = t.str("logo"),
                score = c.str("score"),
                winner = c.optBoolean("winner", false),
            )
            if (c.str("homeAway") == "home") home = team else away = team
        }
        val status = (comp.optJSONObject("status") ?: ev.optJSONObject("status"))?.optJSONObject("type")
        val networks = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.let { b ->
            for (i in 0 until b.length()) {
                val names = b.optJSONObject(i)?.optJSONArray("names") ?: continue
                for (k in 0 until names.length()) names.optString(k).takeIf { it.isNotBlank() }?.let { networks += it }
            }
        }
        comp.optJSONArray("geoBroadcasts")?.let { b ->
            for (i in 0 until b.length()) {
                b.optJSONObject(i)?.optJSONObject("media")?.str("shortName")?.let { networks += it }
            }
        }
        val venueObj = comp.optJSONObject("venue")
        val city = venueObj?.optJSONObject("address")?.let { a ->
            listOfNotNull(a.str("city"), a.str("state")).joinToString(", ").ifEmpty { null }
        }
        val venue = venueObj?.str("fullName")?.let { name -> if (city != null) "$name · $city" else name }
        return Game(
            id = ev.str("id") ?: return null,
            start = parseTime(ev.str("date") ?: comp.str("date") ?: return null) ?: return null,
            state = status?.str("state") ?: "pre",
            detail = status?.str("shortDetail") ?: status?.str("detail") ?: "",
            home = home ?: return null,
            away = away ?: return null,
            venue = venue,
            networks = networks.toList(),
        )
    }

    private val formats = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    private fun parseTime(s: String): Long? {
        for (f in formats) {
            try {
                val fmt = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                return fmt.parse(s)?.time
            } catch (e: Exception) { /* try next */ }
        }
        return null
    }
}
