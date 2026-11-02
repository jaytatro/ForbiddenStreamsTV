package com.forbiddenstreams.tv.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures and details for Home, from the provider itself (no TMDB, no key): wide
 * background picture, date, length, episode name and story.
 */
object ProviderArt {
    data class Art(
        val backdrop: String?,
        val title: String?,
        val date: String?,
        val runtimeMin: Int?,
        val overview: String?,
        val episodeName: String? = null,
    )

    private val cache = ConcurrentHashMap<String, Art>()

    suspend fun movie(vodId: String, name: String): Art? {
        cache["m:$vodId"]?.let { return it }
        val src = Session.source ?: return null
        val d = try { src.vodDetails(VodItem(vodId, name, null, null, "", "mp4")) } catch (e: Exception) { null } ?: return null
        return Art(d.backdrop, null, d.releaseDate, d.minutes, d.plot).also { cache["m:$vodId"] = it }
    }

    suspend fun episode(showName: String, episodeId: String?, season: Int?, number: Int?): Art? {
        val key = "e:$showName:$episodeId:$season:$number"
        cache[key]?.let { return it }
        val src = Session.source ?: return null
        MovieMatcher.loadSeries()
        val s = MovieMatcher.findSeries(showName) ?: return null
        val info = try { src.seriesInfo(s) } catch (e: Exception) { null }
        val eps = try { src.episodes(s) } catch (e: Exception) { emptyList() }
        val ep = eps.firstOrNull { it.id == episodeId } ?: eps.firstOrNull { it.season == season && it.episodeNum == number }
        return Art(
            backdrop = info?.backdrop ?: s.backdrop ?: ep?.image,
            title = null,
            date = ep?.airDate ?: info?.releaseDate,
            runtimeMin = ep?.minutes,
            overview = ep?.plot?.takeIf { it.isNotBlank() } ?: info?.plot ?: s.plot,
            episodeName = ep?.title?.takeIf { t -> !t.contains(Regex("S\\d+\\s*E\\d+", RegexOption.IGNORE_CASE)) },
        ).also { cache[key] = it }
    }

    suspend fun show(s: SeriesItem): Art? {
        cache["s:${s.id}"]?.let { return it }
        val src = Session.source ?: return null
        val info = try { src.seriesInfo(s) } catch (e: Exception) { null }
        return Art(info?.backdrop ?: s.backdrop, null, info?.releaseDate, null, info?.plot ?: s.plot).also { cache["s:${s.id}"] = it }
    }
}

/**
 * The provider's movie and show lists, loaded once: newest movies and shows, and finding a
 * show by name. Provider names are cleaned of tags like "EN - ", "[4K]", "(2024)", "HD".
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

    fun yearOf(name: String): String? = yearRx.findAll(name).lastOrNull()?.value

    /** "EN - The Batman (2022) [4K]" → "The Batman" (for searching TMDB). */
    fun cleanTitle(name: String): String {
        var t = name
        repeat(3) { t = t.replace(tagRx, "") }
        t = t.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
            .replace(Regex("(?i)\\b(4k|uhd|fhd|hd|sd|hevc|x265|1080p|720p|2160p|multi)\\b"), " ")
        return t.replace(Regex("\\s+"), " ").trim(' ', '-', ':', '|')
    }

    private var seriesIndex: Map<String, List<SeriesItem>>? = null
    private var newestSeries: List<SeriesItem> = emptyList()

    /** Shows the provider added or updated most recently ("New TV Shows"). */
    fun newestSeries(limit: Int = 30): List<SeriesItem> = newestSeries.take(limit)

    /** Loads the provider's TV show list once. */
    suspend fun loadSeries(): Boolean = lock.withLock {
        if (seriesIndex != null) return true
        val src = Session.source ?: return false
        val all = try { src.allSeries() } catch (e: Throwable) { return false }
        seriesIndex = all.groupBy { key(it.name) }
        newestSeries = newestOf(all.map { Triple(it, releaseOf(it.releaseDate, it.name), it.lastModified) }).distinctBy { key(it.name) }
        true
    }

    fun findSeries(name: String): SeriesItem? = seriesIndex?.get(key(name))?.firstOrNull()

    val ready: Boolean get() = index != null

    /** Loads the provider's movie list once (in the background). */
    suspend fun load(): Boolean = lock.withLock {
        if (index != null) return true
        val src = Session.source ?: return false
        val all = try { src.allVod() } catch (e: Throwable) { return false }
        index = all.groupBy { key(it.name) }
        newest = newestOf(all.map { Triple(it, releaseOf(it.releaseDate, it.name), it.added) }).distinctBy { key(it.name) }
        true
    }

    /** Movies the provider added most recently ("New in HD"). */
    fun newest(limit: Int = 40): List<VodItem> = newest.take(limit)

    /** "2026-03-14" (or just the year from the name, "… (2026)") → "2026-03-14" / "2026". */
    private fun releaseOf(date: String?, name: String): String? =
        date?.trim()?.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) } ?: yearOf(name)

    /**
     * Titles that came out THIS year (the current year, so it moves on by itself every January),
     * newest month first; titles with only a year go after, newest added first. Early in a year,
     * when there are few, last year's newest fill in.
     */
    private fun <T> newestOf(items: List<Triple<T, String?, Long>>): List<T> {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        fun pick(y: Int) = items.filter { it.second?.take(4) == y.toString() }
            .sortedWith(compareByDescending<Triple<T, String?, Long>> { it.second?.takeIf { d -> d.length >= 7 } ?: "" }.thenByDescending { it.third })
        val now = pick(year)
        val out = if (now.size >= 15) now else now + pick(year - 1)
        return out.map { it.first }.ifEmpty { items.sortedByDescending { it.third }.map { it.first } }
    }

    fun reset() { index = null; newest = emptyList(); seriesIndex = null }
}
