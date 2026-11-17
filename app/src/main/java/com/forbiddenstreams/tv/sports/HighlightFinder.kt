package com.forbiddenstreams.tv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Nov 16 ("make sure to grab the right highlights for each game"): instead of the first YouTube result,
 * every result is checked — both teams in the title, put up after this game was played (not last season's
 * meeting), a highlights video (not a preview, prediction, reaction, full game or Short), preferring the
 * league's / networks' / teams' own channels and a title with the game's date.
 */
object HighlightFinder {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Other good matches for a picked video, tried in order if it won't play inside the app (embedding off). */
    private val backups = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    fun cached(league: League, g: Game): String? = cache[league.name + ":" + g.id]
    fun backupsFor(id: String): List<String> = backups[id].orEmpty()

    private data class Video(val id: String, val title: String, val channel: String, val published: String?, val length: String?, val pos: Int)

    private val OFFICIAL = listOf(
        "nfl", "nba", "nhl", "mlb", "wnba", "mls", "espn", "espn fc", "house of highlights", "cbs sports", "cbs sports golazo",
        "fox sports", "fox soccer", "nbc sports", "sportsnet", "tsn", "nba on tnt", "tnt sports", "bleacher report", "premier league",
        "uefa", "liga mx", "espn college football", "espn college basketball", "big ten network", "sec network", "acc digital network",
        "ncaa march madness", "ncaa", "nfl network", "mlb network", "nhl network", "nba tv", "nbc sports soccer", "prime video sports",
        "peacock", "abc news", "espn deportes", "tudn", "univision deportes", "telemundo deportes",
    )
    private val BAD = listOf(
        "preview", "prediction", "predictions", "picks", "odds", "betting", "reaction", "reacts", "full game", "full match",
        "#shorts", "live stream", "livestream", "watch party", "press conference", "postgame", "post game", "pregame", "pre-game",
        "podcast", "fantasy", "madden", "nba 2k", "nhl 25 ", "nhl 26 ", "fifa", "ea fc", "simulation", "mock draft", "every goal of", "top 10",
        "season highlights", "career highlights", "mixtape", "recap show",
    )

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.US)

    private fun unescape(s: String) = s.replace("\\u0026", "&").replace("\\\"", "\"").replace("\\u0027", "'").replace("\\/", "/")

    private fun teamIn(title: String, rawTitle: String, t: Team, league: League): Boolean {
        val n = norm(title)
        fun has(x: String) = x.length >= 3 && Regex("(^|[^a-z0-9])" + Regex.escape(norm(x)) + "($|[^a-z0-9])").containsMatchIn(n)
        if (has(t.displayName)) return true
        val college = league == League.NCAAF || league == League.NCAAB || league == League.NCAAW || league == League.NCAABASE
        if (!college && t.nickname.isNotBlank() && has(t.nickname)) return true
        if ((college || league == League.MLS) && t.location.isNotBlank() && has(t.location)) return true
        if (t.abbreviation.length >= 3 && Regex("(^|[^A-Za-z0-9])" + Regex.escape(t.abbreviation.uppercase(Locale.US)) + "($|[^A-Za-z0-9])").containsMatchIn(rawTitle)) return true
        return false
    }

    /** How long ago YouTube says it was put up, as a range in ms (min..max), or null if unknown. */
    private fun ageRange(p: String?): LongRange? {
        val m = Regex("(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago").find(p?.lowercase(Locale.US) ?: return null) ?: return null
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2]) {
            "second" -> 1_000L; "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
        }
        return (n * unit)..((n + 1) * unit)
    }

    private fun seconds(len: String?): Int? {
        val parts = len?.split(":")?.mapNotNull { it.toIntOrNull() } ?: return null
        return parts.fold(0) { acc, v -> acc * 60 + v }
    }

    private fun parse(html: String): List<Video> {
        val out = ArrayList<Video>()
        val parts = html.split("\"videoRenderer\":{\"videoId\":\"")
        for ((i, raw) in parts.drop(1).withIndex()) {
            val chunk = raw.take(8000)
            val id = chunk.take(11)
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id) || out.any { it.id == id }) continue
            val title = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(chunk)?.groupValues?.get(1)
                ?: Regex("\"title\":\\{\"accessibility\".{0,400}?\"simpleText\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(chunk)?.groupValues?.get(1) ?: continue
            val channel = Regex("\"(?:longBylineText|ownerText)\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(chunk)?.groupValues?.get(1).orEmpty()
            val published = Regex("\"publishedTimeText\":\\{\"simpleText\":\"([^\"]*)\"").find(chunk)?.groupValues?.get(1)
            val length = Regex("\"lengthText\".{0,400}?\"simpleText\":\"([0-9:]+)\"").find(chunk)?.groupValues?.get(1)
            out += Video(id, unescape(title), unescape(channel), published, length, i)
        }
        return out
    }

    private fun search(q: String): List<Video> = try {
        // sp=EgIQAQ== → videos only (no Shorts shelves, channels or playlists).
        val url = "https://www.youtube.com/results?hl=en&gl=US&sp=EgIQAQ%3D%3D&search_query=" + java.net.URLEncoder.encode(q, "UTF-8")
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
            .build()
        com.forbiddenstreams.tv.data.Http.client.newCall(req).execute().use { r -> parse(r.body?.string().orEmpty()) }
    } catch (e: Exception) { emptyList() }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** True if the title names a date that isn't the game's (e.g. last season's "Nov 12" meeting). */
    private fun otherDateIn(title: String, start: Long): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        val m = cal.get(java.util.Calendar.MONTH); val d = cal.get(java.util.Calendar.DAY_OF_MONTH); val y = cal.get(java.util.Calendar.YEAR)
        val t = norm(title)
        Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b").findAll(t).forEach {
            val mm = MONTHS.indexOf(it.groupValues[1]); val dd = it.groupValues[2].toInt()
            if (mm >= 0 && dd in 1..31 && (mm != m || dd != d)) return true
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").findAll(t).forEach {
            val mm = it.groupValues[1].toInt() - 1; val dd = it.groupValues[2].toInt()
            if (mm in 0..11 && dd in 1..31 && (mm != m || dd != d)) return true
        }
        Regex("\\b(20\\d\\d)\\b").findAll(t).forEach {
            val yy = it.groupValues[1].toInt()
            if (yy != y && yy != y - 1 && yy != y + 1) return true
        }
        return false
    }

    /**
     * Opens each top match's YouTube page for its exact upload time: it must be put up after this game started
     * and within 36 h. Matches whose description has the final score go first.
     */
    private suspend fun verify(list: List<Pair<Video, Int>>, g: Game, earliest: Long, latest: Long): List<String> = withContext(Dispatchers.IO) {
        val score = listOfNotNull(g.away.score, g.home.score).takeIf { it.size == 2 }
        coroutineScope {
            list.map { (v, sc) ->
                async {
                    val page = try {
                        val req = okhttp3.Request.Builder().url("https://www.youtube.com/watch?v=${v.id}&hl=en&gl=US")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                            .header("Accept-Language", "en-US,en;q=0.9").header("Cookie", "CONSENT=YES+1; SOCS=CAI").build()
                        com.forbiddenstreams.tv.data.Http.client.newCall(req).execute().use { it.body?.string().orEmpty() }
                    } catch (e: Exception) { "" }
                    val iso = Regex("\"(?:uploadDate|publishDate)\":\"([^\"]+)\"").find(page)?.groupValues?.get(1)
                    val at = iso?.let { parseIso(it) }
                    when {
                        at == null -> v.id to sc                                   // couldn't check: keep its search-page score
                        at.second && (at.first < earliest - 86_400_000L || at.first > latest) -> null   // date only
                        !at.second && (at.first < earliest || at.first > latest) -> null
                        else -> {
                            val desc = Regex("\"shortDescription\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(page)?.groupValues?.get(1).orEmpty()
                            val hasScore = score != null && Regex("(^|[^0-9])(${score[0]}\\s*[-–]\\s*${score[1]}|${score[1]}\\s*[-–]\\s*${score[0]})([^0-9]|$)")
                                .containsMatchIn(v.title + " " + desc)
                            v.id to (sc + 25 + if (hasScore) 20 else 0)
                        }
                    }
                }
            }.mapNotNull { it.await() }.sortedByDescending { it.second }.map { it.first }
        }
    }

    /** ISO time → (ms, dateOnly). */
    private fun parseIso(s: String): Pair<Long, Boolean>? = try {
        if (s.length <= 10) {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles") }
            f.parse(s)!!.time to true
        } else {
            val t = s.replace(Regex("\\.\\d+"), "").replace("Z", "+00:00").replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(t)!!.time to false
        }
    } catch (e: Exception) { null }

    fun query(league: League, g: Game): String {
        val date = java.text.SimpleDateFormat("MMMM d, yyyy", Locale.US).format(java.util.Date(g.start))
        return "${g.away.displayName} vs ${g.home.displayName} highlights $date"
    }

    /** The YouTube id of this game's highlights, or null if no result is clearly this game. */
    suspend fun find(league: League, g: Game): String? {
        val key = league.name + ":" + g.id
        cache[key]?.let { return it }
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("MMMM d", Locale.US).format(java.util.Date(g.start))
        val q1 = query(league, g)
        val short = { t: Team -> if (league == League.NCAAF || league == League.NCAAB || league == League.NCAAW || league == League.NCAABASE || t.nickname.isBlank()) t.location.ifBlank { t.displayName } else t.nickname }
        val q2 = "${league.label} highlights ${short(g.away)} vs ${short(g.home)} $day"
        val videos = withContext(Dispatchers.IO) {
            coroutineScope { listOf(async { search(q1) }, async { search(q2) }).map { it.await() } }
        }.flatten().distinctBy { it.id }

        // Nov 17: posted from the game's start to 36 h after (same game, not the day before/after in a series).
        val earliest = g.start
        val latest = g.start + 36 * 3_600_000L
        val monthName = java.text.SimpleDateFormat("MMM", Locale.US).format(java.util.Date(g.start)).lowercase(Locale.US)
        val dayNum = java.text.SimpleDateFormat("d", Locale.US).format(java.util.Date(g.start))
        val numeric = java.text.SimpleDateFormat("M/d", Locale.US).format(java.util.Date(g.start))

        val best = videos.mapNotNull { v ->
            if (!teamIn(v.title, v.title, g.away, league) || !teamIn(v.title, v.title, g.home, league)) return@mapNotNull null
            val t = norm(v.title)
            if (BAD.any { t.contains(it) }) return@mapNotNull null
            if (otherDateIn(v.title, g.start)) return@mapNotNull null
            val age = ageRange(v.published)
            if (age != null) {
                val putUpLatest = now - age.first
                val putUpEarliest = now - age.last
                if (putUpLatest < earliest || putUpEarliest > latest) return@mapNotNull null
            }
            val secs = seconds(v.length)
            if (secs != null && (secs < 60 || secs > 40 * 60)) return@mapNotNull null
            var score = 0
            if (t.contains("highlight")) score += 30
            val ch = norm(v.channel)
            if (OFFICIAL.any { ch == it || ch.startsWith("$it ") }) score += 30
            if (teamIn(v.channel, v.channel, g.away, league) || teamIn(v.channel, v.channel, g.home, league)) score += 15
            if ((t.contains(monthName) && Regex("(^|[^0-9])$dayNum([^0-9]|$)").containsMatchIn(t)) || t.contains(numeric)) score += 20
            if (age != null) score += 20 else score -= 15
            if (secs != null && secs in 120..20 * 60) score += 10
            score -= v.pos
            v to score
        }.filter { it.second >= 20 }.sortedByDescending { it.second }.take(6)
            .let { verify(it, g, earliest, latest) }
        val pick = best.firstOrNull() ?: return null
        backups[pick] = best.drop(1).take(4)
        cache[key] = pick
        return pick
    }
}
