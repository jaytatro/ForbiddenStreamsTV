package com.forbiddenstreams.tv.sports

import com.forbiddenstreams.tv.data.Category
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Program

data class GameChannels(
    val away: LiveChannel?,
    val home: LiveChannel?,
    /** Shown on the card when no channel was found. */
    val note: String?,
) {
    val options: List<LiveChannel> get() = listOfNotNull(away, home).distinctBy { it.id }
}

/**
 * Matches ESPN games to the provider's team channels (rules agreed in the app spec):
 * - Only the four "USA | <League> Teams" groups are used.
 * - NFL: the game's network (CBS/FOX) + either team; tie-break on EPG title.
 * - NBA/NHL/MLB: team full name, nickname, abbreviation or unique city.
 */
object GameMatcher {

    fun groupKey(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun findGroup(categories: List<Category>, league: League): Category? {
        val want = groupKey(league.groupName)
        categories.firstOrNull { groupKey(it.name) == want }?.let { return it }
        // Otherwise a "<league> Teams" group with a slightly different name (not "NBA" for "WNBA").
        return categories.firstOrNull { c ->
            val n = " " + c.name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
            n.contains(" team") && league.keywords.any { k -> n.contains(" $k ") }
        }
    }

    private val nonWord = Regex("[^a-z0-9]+")
    private val normCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Lower-case words with spaces around them; cached because it runs on every channel name. */
    private fun norm(s: String): String = normCache.getOrPut(s) { " " + s.lowercase().replace(nonWord, " ").trim() + " " }

    private fun hasWord(haystack: String, word: String): Boolean {
        val w = norm(word).trim()
        return w.isNotEmpty() && haystack.contains(" $w ")
    }

    private val excluded = listOf("strike zone", "big inning", "redzone", "red zone")

    /** 0 = no match; higher is stronger. */
    private fun score(channel: LiveChannel, team: Team, cityUnique: Boolean): Int {
        val n = norm(channel.name)
        if (excluded.any { n.contains(" $it ") }) return 0
        return when {
            hasWord(n, team.displayName) -> 4
            team.nickname.isNotBlank() && hasWord(n, team.nickname) -> 3
            team.abbreviation.length >= 3 && hasWord(n, team.abbreviation) -> 2
            cityUnique && team.location.isNotBlank() && hasWord(n, team.location) -> 1
            else -> 0
        }
    }

    private fun channelsFor(team: Team, channels: List<LiveChannel>): List<LiveChannel> {
        val cityHits = channels.count { hasWord(norm(it.name), team.location) }
        val scored = channels.map { it to score(it, team, cityHits == 1) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }
    }

    /**
     * Channels showing the game's TV network (ESPN, ABC, NBC, Prime Video...), for games
     * that aren't on a team channel, like Monday/Thursday/Sunday night NFL.
     */
    fun networkChannels(game: Game, all: List<LiveChannel>, exclude: Set<String>): List<Pair<String, LiveChannel>> {
        val out = ArrayList<Pair<String, LiveChannel>>()
        val seen = HashSet<String>(exclude)
        for (net in game.networks) {
            val keys = networkNames(net)
            if (keys.isEmpty()) continue
            val hits = all.filter { c ->
                val n = norm(c.name)
                c.id !in seen && keys.any { k -> n.contains(" $k ") } && excluded.none { n.contains(" $it ") }
            }.sortedWith(compareBy(
                { if (norm(it.name).let { n -> n.startsWith(" us ") || n.startsWith(" usa ") }) 0 else 1 },
                { it.name.length },
            )).take(4)
            hits.forEach { seen += it.id; out += net to it }
            if (out.size >= 8) break
        }
        return out
    }

    private val streamWords = listOf("prime", "amazon", "tnf", "thursday night", "netflix", "peacock", "event", "events", "ppv",
        "espn plus", "espn+", "nfl plus", "nfl+", "game pass", "apple", "mls season", "paramount", "max", "youtube", "sunday ticket", "dazn")

    /**
     * Event channels for a game (Nov 13: Prime Video / Netflix games had nowhere to play): providers put
     * streaming-only games on one-off channels like "NFL 07 | Bills @ Dolphins" or "Amazon Prime TNF: Bills".
     * A channel counts if its name has both teams, or one team plus a streaming / event word.
     */
    fun eventChannels(game: Game, all: List<LiveChannel>, exclude: Set<String>): List<Pair<String, LiveChannel>> {
        fun names(t: Team) = listOf(t.nickname, t.displayName).filter { it.isNotBlank() }.map { norm(it).trim() }.distinct()
        val a = names(game.away); val h = names(game.home)
        if (a.isEmpty() || h.isEmpty()) return emptyList()
        val words = streamWords.map { norm(it).trim() }
        return all.asSequence().filter { c -> c.id !in exclude }.mapNotNull { c ->
            val n = norm(c.name)
            if (excluded.any { n.contains(" $it ") }) return@mapNotNull null
            val ha = a.any { n.contains(" $it ") }
            val hh = h.any { n.contains(" $it ") }
            val stream = words.any { n.contains(" $it ") }
            when {
                ha && hh -> 0 to c
                (ha || hh) && stream -> 1 to c
                else -> null
            }
        }.sortedWith(compareBy({ it.first }, { it.second.name.length })).take(6).map { (_, c) -> "Event" to c }.toList()
    }

    /**
     * The names a TV network goes by on channel lists. ESPN shortens some ("NFL Net", "NFLN",
     * "CBSSN"), so those are spelled out too (Nov 8: NFL Network wasn't found for Bengals–Falcons).
     */
    private fun networkNames(net: String): List<String> {
        val base = norm(net.replace("+", " plus").replace("Prime Video", "Prime")).trim()
        val u = net.uppercase().replace(" ", "").replace("+", "PLUS")
        val extra = when {
            u.startsWith("NFLNET") || u == "NFLN" || u == "NFLNETWORK" -> listOf("nfl network", "nfl net", "nfln", "nfl hd")
            u == "NFLPLUS" -> listOf("nfl plus", "nfl network")
            u.startsWith("NBATV") -> listOf("nba tv", "nbatv")
            u.startsWith("MLBN") || u.startsWith("MLBNET") -> listOf("mlb network", "mlbn")
            u.startsWith("NHLN") || u.startsWith("NHLNET") -> listOf("nhl network")
            u == "CBSSN" || u.startsWith("CBSSPORTSNET") -> listOf("cbs sports network", "cbssn", "cbs sports")
            u == "BTN" || u.startsWith("BIGTEN") -> listOf("big ten network", "btn", "big ten", "b1g")
            u == "ACCN" || u.startsWith("ACCNETWORK") -> listOf("acc network", "accn")
            u == "SECN" || u.startsWith("SECNETWORK") -> listOf("sec network", "secn")
            u == "FS1" -> listOf("fs1", "fox sports 1")
            u == "FS2" -> listOf("fs2", "fox sports 2")
            u.startsWith("ESPNPLUS") -> listOf("espn plus", "espn+")
            u.startsWith("PRIME") || u.startsWith("AMAZON") -> listOf("prime", "prime video", "amazon prime", "amazon", "tnf", "thursday night football", "thursday night")
            u.startsWith("PEACOCK") -> listOf("peacock")
            u.startsWith("NETFLIX") -> listOf("netflix")
            u.startsWith("TRUTV") -> listOf("trutv", "tru tv")
            else -> emptyList()
        }
        return (listOf(base) + extra.map { norm(it).trim() }).filter { it.isNotEmpty() }.distinct()
    }

    suspend fun match(
        game: Game,
        league: League,
        channels: List<LiveChannel>,
        epg: suspend (LiveChannel) -> List<Program>,
    ): GameChannels {
        if (channels.isEmpty()) return GameChannels(null, null, "Team channels group not found")
        if (league == League.NFL) return matchNfl(game, channels, epg)
        val away = channelsFor(game.away, channels).firstOrNull()
        val home = channelsFor(game.home, channels).firstOrNull()
        return GameChannels(away, home, if (away == null && home == null) "No team channel found" else null)
    }

    private suspend fun matchNfl(
        game: Game,
        channels: List<LiveChannel>,
        epg: suspend (LiveChannel) -> List<Program>,
    ): GameChannels {
        val network = game.networks.firstOrNull { n -> n.uppercase().let { it.contains("CBS") || it.contains("FOX") } }
            ?.uppercase()?.let { if (it.contains("CBS")) "CBS" else "FOX" }
        if (network == null) {
            val on = game.networks.joinToString(", ").ifEmpty { "TV" }
            return GameChannels(null, null, "On $on · no team channel")
        }
        val onNetwork = channels.filter { hasWord(norm(it.name), network) }
        suspend fun pick(team: Team): LiveChannel? {
            val cands = channelsFor(team, onNetwork)
            if (cands.size <= 1) return cands.firstOrNull()
            // Tie-break: the affiliate whose guide names this matchup at game time.
            for (c in cands) {
                val prog = epg(c).firstOrNull { game.start in it.start until it.end } ?: continue
                val t = norm(prog.title)
                if (hasWord(t, game.home.nickname) && hasWord(t, game.away.nickname)) return c
            }
            return cands.first()
        }
        val away = pick(game.away)
        val home = pick(game.home)
        return GameChannels(away, home, if (away == null && home == null) "No $network team channel found" else null)
    }
}
