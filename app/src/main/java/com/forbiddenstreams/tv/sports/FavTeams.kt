package com.forbiddenstreams.tv.sports

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.data.jsonObject
import com.forbiddenstreams.tv.data.str

data class TeamInfo(val league: League, val id: String, val name: String, val abbreviation: String, val logo: String?)

/** Every team in a league, from ESPN (cached for the session). */
object TeamList {
    private val cache = HashMap<League, List<TeamInfo>>()

    suspend fun teams(league: League): List<TeamInfo> {
        cache[league]?.let { return it }
        val j = jsonObject(Http.get("https://site.api.espn.com/apis/site/v2/sports/${league.espnPath}/teams?limit=1000"))
        val arr = j?.optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
        val out = ArrayList<TeamInfo>()
        if (arr != null) for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i)?.optJSONObject("team") ?: continue
            val logo = t.optJSONArray("logos")?.optJSONObject(0)?.str("href")
            out += TeamInfo(league, t.str("id") ?: continue, t.str("displayName") ?: continue, t.str("abbreviation") ?: "", logo)
        }
        val sorted = out.sortedBy { it.name }
        if (sorted.isNotEmpty()) cache[league] = sorted
        return sorted
    }
}

/** The user's favorite teams ("NBA:BOS"). Their games are listed first with a ⭐. */
object FavTeams {
    var version by mutableIntStateOf(0)
        private set

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)

    private fun key(league: League, abbreviation: String) = "${league.name}:${abbreviation.uppercase()}"

    fun all(context: Context): Set<String> = sp(context).getStringSet("fav_teams", emptySet())?.toSet() ?: emptySet()

    fun isFav(context: Context, league: League, abbreviation: String) = key(league, abbreviation) in all(context)

    /** Returns true if the team is now a favorite. */
    fun toggle(context: Context, league: League, abbreviation: String): Boolean {
        val s = all(context).toMutableSet()
        val k = key(league, abbreviation)
        val added = s.add(k)
        if (!added) s.remove(k)
        sp(context).edit().putStringSet("fav_teams", s).apply()
        version++
        return added
    }

    fun inGame(context: Context, league: League, game: Game): Boolean {
        val f = all(context)
        return key(league, game.home.abbreviation) in f || key(league, game.away.abbreviation) in f
    }
}

/** Sports list order and hidden leagues (Sports › hold OK on a league). Default: A to Z, all shown. */
object SportPrefs {
    var version by mutableIntStateOf(0)
        private set

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)

    fun order(context: Context): List<League> {
        val saved = sp(context).getString("sport_order", null)?.split(",")?.mapNotNull { n -> League.entries.firstOrNull { it.name == n } }.orEmpty()
        val rest = League.entries.filter { it !in saved }.sortedBy { it.label }
        return saved + rest
    }

    fun hidden(context: Context): Set<String> = sp(context).getStringSet("sport_hidden", emptySet())?.toSet() ?: emptySet()

    fun visible(context: Context): List<League> { val h = hidden(context); return order(context).filter { it.name !in h } }

    fun move(context: Context, league: League, dir: Int) {
        val list = order(context).toMutableList()
        val i = list.indexOf(league)
        val j = i + dir
        if (i < 0 || j !in list.indices) return
        list[i] = list[j]; list[j] = league
        sp(context).edit().putString("sport_order", list.joinToString(",") { it.name }).apply()
        version++
    }

    fun setHidden(context: Context, league: League, hide: Boolean) {
        val h = hidden(context).toMutableSet()
        if (hide) h += league.name else h -= league.name
        sp(context).edit().putStringSet("sport_hidden", h).apply()
        version++
    }

    fun reset(context: Context) {
        sp(context).edit().remove("sport_order").remove("sport_hidden").apply()
        version++
    }
}
