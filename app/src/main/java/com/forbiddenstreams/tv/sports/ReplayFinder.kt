package com.forbiddenstreams.tv.sports

import com.forbiddenstreams.tv.data.Session
import com.forbiddenstreams.tv.data.VodItem
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * Nov 19 ("search the playlist for playback for sports teams"): a finished game's replay among the
 * playlist's own videos (providers put full-game replays in their movie lists, e.g. "NBA | Lakers vs
 * Warriors 11/17"). It must name both teams; a date in the name must be the game's date (or the day
 * after, for late games); with no date, it must have been added within 5 days after the game.
 */
object ReplayFinder {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, VodItem>()
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val BAD = listOf("highlight", "preview", "press conference", "interview", "documentary", "season review", "top 10", "best of")

    private fun norm(s: String) = " " + Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.US).replace(Regex("[^a-z0-9/.\\-]+"), " ") + " "

    private fun teamIn(n: String, raw: String, t: Team, league: League): Boolean {
        fun has(x: String) = x.length >= 3 && n.contains(norm(x))
        if (has(t.displayName)) return true
        val college = league == League.NCAAF || league == League.NCAAB || league == League.NCAAW || league == League.NCAABASE
        if (!college && has(t.nickname)) return true
        if ((college || league == League.MLS) && has(t.location)) return true
        return t.abbreviation.length >= 2 && Regex("(^|[^A-Za-z0-9])" + Regex.escape(t.abbreviation.uppercase(Locale.US)) + "($|[^A-Za-z0-9])").containsMatchIn(raw)
    }

    /** Dates written in the name, as (month 0–11, day). */
    private fun datesIn(n: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(st|nd|rd|th)?\\b").findAll(n).forEach {
            out += MONTHS.indexOf(it.groupValues[1]) to it.groupValues[2].toInt()
        }
        Regex("\\b(\\d{1,2})(st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)").findAll(n).forEach {
            out += MONTHS.indexOf(it.groupValues[3]) to it.groupValues[1].toInt()
        }
        Regex("\\b(20\\d\\d)[-./](\\d{1,2})[-./](\\d{1,2})\\b").findAll(n).forEach {
            out += (it.groupValues[2].toInt() - 1) to it.groupValues[3].toInt()
        }
        Regex("\\b(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\b").findAll(n).forEach {
            val a = it.groupValues[1].toInt(); val b = it.groupValues[2].toInt()
            if (a in 1..12 && b in 1..31) out += (a - 1) to b
        }
        return out.filter { it.first in 0..11 && it.second in 1..31 }
    }

    suspend fun find(league: League, g: Game): VodItem? {
        val key = league.name + ":" + g.id
        cache[key]?.let { return it }
        val src = Session.source ?: return null
        val all = try { src.allVod() } catch (e: Throwable) { return null }
        val catNames = try { src.vodCategories().associate { it.id to it.name.lowercase(Locale.US) } } catch (e: Exception) { emptyMap() }
        val days = (0..1).map { d -> Calendar.getInstance().apply { timeInMillis = g.start + d * 86_400_000L }.let { it.get(Calendar.MONTH) to it.get(Calendar.DAY_OF_MONTH) } }
        val best = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            all.asSequence().mapNotNull { v ->
                val n = norm(v.name)
                if (!teamIn(n, v.name, g.away, league) || !teamIn(n, v.name, g.home, league)) return@mapNotNull null
                if (BAD.any { n.contains(it) }) return@mapNotNull null
                var score = 0
                val dates = datesIn(n)
                if (dates.isNotEmpty()) {
                    if (dates.none { it in days }) return@mapNotNull null
                    score += 40
                } else {
                    val added = v.added * 1000
                    if (added <= 0 || added < g.start - 6 * 3_600_000L || added > g.start + 5 * 86_400_000L) return@mapNotNull null
                    score += 20
                }
                val cat = catNames[v.categoryId].orEmpty()
                if (cat.contains("replay") || cat.contains("full game") || cat.contains(league.label.lowercase(Locale.US)) || cat.contains("sport")) score += 15
                if (n.contains(" replay ") || n.contains(" full game ")) score += 10
                v to score
            }.maxByOrNull { it.second }?.first
        }
        return best?.also { cache[key] = it }
    }
}
