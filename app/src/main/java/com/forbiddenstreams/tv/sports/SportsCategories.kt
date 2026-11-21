package com.forbiddenstreams.tv.sports

import com.forbiddenstreams.tv.data.Category
import com.forbiddenstreams.tv.data.LiveChannel
import com.forbiddenstreams.tv.data.Program
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * Nov 21: the user's playlist categories that carry games, besides the "USA | <league> Teams" groups:
 *   US| SPORTS NETWORKS · US| NBA NETWORK VIP · US| NBA PACKAGE · US| WNBA PACKAGE · US| NCAFF PAKAGE ·
 *   US| NCAAB PACKAGE · US| NHL PACKAGE · US| NFL NETWORK HULU · US|NFL PACKAGE · US| MLB PACKAGE ·
 *   US|FLO COLLEGE PPV · US| ABC / CBS / FOX / NBC / CW/MY NETWORK · ESPN+ PPV VIP · ESPN PLUS
 * Names are matched loosely (spacing, "|", spelling slips like PAKAGE / NCAFF).
 */
object SportsCategories {
    private fun words(name: String) = " " + name.lowercase(Locale.US).replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    private fun isPackage(w: String) = w.contains(" package ") || w.contains(" pakage ") || w.contains(" pkg ") || w.contains(" pass ")

    /** League package categories (game feeds, e.g. League Pass): the main place for home / away streams. */
    fun packageCats(cats: List<Category>, league: League): List<Category> = cats.filter { c ->
        val w = words(c.name)
        val pk = isPackage(w) || w.contains(" vip ") || w.contains(" hulu ") || w.contains(" ppv ")
        when (league) {
            League.NBA -> (w.contains(" nba ") && pk) || w.contains(" nba network ")
            League.WNBA -> w.contains(" wnba ") && pk
            League.NHL -> w.contains(" nhl ") && pk
            League.MLB -> w.contains(" mlb ") && pk
            League.NFL -> (w.contains(" nfl ") && pk) || w.contains(" nfl network ")
            League.NCAAF -> (w.contains(" ncaaf ") || w.contains(" ncaff ") || w.contains(" ncf ") || w.contains(" college football ")) && pk ||
                w.contains(" flo college ")
            League.NCAAB, League.NCAAW -> (w.contains(" ncaab ") || w.contains(" ncab ") || w.contains(" college basketball ")) && pk ||
                w.contains(" flo college ")
            League.NCAABASE -> w.contains(" flo college ")
            League.MLS -> (w.contains(" mls ") && pk)
        }
    }

    /** Network categories (national sports networks and the local ABC / CBS / FOX / NBC / CW / MyNetwork stations). */
    fun networkCats(cats: List<Category>): List<Category> = cats.filter { c ->
        val w = words(c.name)
        w.contains(" sports networks ") || w.contains(" sports network ") ||
            listOf(" abc ", " cbs ", " fox ", " nbc ", " cw ", " my network ", " mynetwork ").any { w.contains(it) } && !isPackage(w)
    }

    /** ESPN+ categories ("ESPN+ PPV VIP", "ESPN PLUS"). */
    fun espnPlusCats(cats: List<Category>): List<Category> = cats.filter { c -> words(c.name).contains(" espn plus ") }

    private fun channels(cats: List<Category>): List<LiveChannel> = cats.flatMap { Session.channelsIn(it.id) }.distinctBy { it.id }

    fun packageChannels(league: League): List<LiveChannel> = channels(packageCats(Session.liveCats.orEmpty(), league))
    fun networkChannelIds(): Set<String> = channels(networkCats(Session.liveCats.orEmpty())).map { it.id }.toSet()
    fun espnPlusChannels(): List<LiveChannel> = channels(espnPlusCats(Session.liveCats.orEmpty()))

    /** Picture quality from the name: 4K 4 · FHD/1080 3 · HD/720 2 · SD 1 · unknown 1 (VIP / "RAW" a little higher). */
    fun quality(name: String): Int {
        val n = " " + name.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ") + " "
        val base = when {
            n.contains(" 4K ") || n.contains(" UHD ") || n.contains(" 2160") -> 40
            n.contains(" FHD ") || n.contains(" 1080") || n.contains(" 60FPS ") || n.contains(" 50FPS ") -> 30
            n.contains(" HD ") || n.contains(" 720") -> 20
            n.contains(" SD ") || n.contains(" 480") || n.contains(" 576") -> 5
            else -> 12
        }
        return base + (if (n.contains(" VIP ") || n.contains(" RAW ")) 3 else 0) - (if (n.contains(" BACKUP ") || n.contains(" LOW ")) 6 else 0)
    }

    private fun norm(s: String) = " " + s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    private fun named(n: String, t: Team, college: Boolean): Int = when {
        t.displayName.isNotBlank() && n.contains(norm(t.displayName)) -> 4
        !college && t.nickname.length >= 3 && n.contains(norm(t.nickname)) -> 3
        college && t.location.length >= 3 && n.contains(norm(t.location)) -> 3
        t.abbreviation.length >= 3 && n.contains(norm(t.abbreviation)) -> 2
        else -> 0
    }

    /**
     * The best streams for one side of a game, best first: from the league's team group and its package
     * categories. A channel counts when its name names the team, or (package channels with plain names
     * like "NBA 07") when its guide at game time names the team. Ranked by: guide confirms it, the name
     * says this side ("Home" / "Away" / the team), then picture quality. Channels whose guide shows a
     * different game then are left out.
     */
    suspend fun sideFeeds(
        league: League, g: Game, home: Boolean, teamGroup: List<LiveChannel>,
        epg: suspend (LiveChannel) -> List<Program>,
    ): List<LiveChannel> = coroutineScope {
        val college = league == League.NCAAF || league == League.NCAAB || league == League.NCAAW || league == League.NCAABASE
        val me = if (home) g.home else g.away
        val them = if (home) g.away else g.home
        val pkg = packageChannels(league)
        val pool = (teamGroup + pkg).distinctBy { it.id }
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val scored = pool.map { c ->
            async {
                val n = norm(c.name)
                val mine = named(n, me, college)
                val theirs = named(n, them, college)
                val isPkg = pkg.any { it.id == c.id }
                // Team-group channels must name this team; package channels may be generic and checked by guide.
                if (mine == 0 && (!isPkg || theirs > 0 && !n.contains(" vs ") && !n.contains(" at "))) return@async null
                val guide = gate.acquire().let { try { GameMatcher.guideShowsGame(c, g, epg) } catch (e: Exception) { null } finally { gate.release() } }
                if (guide == false) return@async null
                if (mine == 0 && guide != true) return@async null
                var s = mine * 10 + quality(c.name)
                if (guide == true) s += 40
                val sideWord = if (home) " home " else " away "
                if (n.contains(sideWord)) s += 15
                if (n.contains(if (home) " away " else " home ")) s -= 15
                if (isPkg) s += 5
                c to s
            }
        }.awaitAll().filterNotNull()
        scored.sortedByDescending { it.second }.map { it.first }
    }
}
