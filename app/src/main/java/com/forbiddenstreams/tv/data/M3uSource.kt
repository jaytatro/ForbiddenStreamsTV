package com.forbiddenstreams.tv.data

import android.util.Xml
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val catchup: String?,
    val catchupDays: Int,
    val catchupSource: String?,
) {
    val kind: Kind
        get() = when {
            url.contains("/movie/", true) -> Kind.MOVIE
            url.contains("/series/", true) -> Kind.SERIES
            else -> Kind.LIVE
        }

    enum class Kind { LIVE, MOVIE, SERIES }
}

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(input: InputStream): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        var pending: String? = null
        var groupLine: String? = null
        input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> { pending = line; groupLine = null }
                line.startsWith("#EXTGRP:", true) -> { groupLine = line.substringAfter(':').trim() }
                line.startsWith("#") -> Unit
                pending != null -> {
                    out += entry(pending!!, groupLine, line)
                    pending = null
                }
            }
        }
        return out
    }

    private fun entry(info: String, groupLine: String?, url: String): M3uEntry {
        val attrs = attr.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val lastQuote = info.lastIndexOf('"')
        val comma = info.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
        val name = (if (comma >= 0) info.substring(comma + 1) else "").trim()
            .ifEmpty { attrs["tvg-name"] ?: "Channel" }
        return M3uEntry(
            name = name,
            url = url,
            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: groupLine ?: "Other",
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            catchup = attrs["catchup"] ?: attrs["catchup-type"],
            catchupDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0,
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
        )
    }
}

object XmltvParser {
    private val full = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val bare = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun time(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            full.parse(s.trim())?.time
        } catch (e: Exception) {
            try { bare.parse(s.trim().take(14))?.time } catch (e2: Exception) { null }
        }
    }

    /** Returns programmes per lower-cased channel id, only inside [from, to]. */
    fun parse(input: InputStream, from: Long, to: Long): Map<String, List<Program>> {
        val out = HashMap<String, MutableList<Program>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var ev = p.eventType
        var ch: String? = null
        var start = 0L
        var end = 0L
        var title = ""
        var desc = ""
        var inProg = false
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "programme" -> {
                        val s = time(p.getAttributeValue(null, "start"))
                        val e = time(p.getAttributeValue(null, "stop"))
                        inProg = s != null && e != null && e > from && s < to
                        if (inProg) {
                            ch = p.getAttributeValue(null, "channel")?.lowercase()
                            start = s!!; end = e!!; title = ""; desc = ""
                        }
                    }
                    "title" -> { if (inProg && title.isEmpty()) title = p.nextText() ?: "" }
                    "desc" -> { if (inProg && desc.isEmpty()) desc = p.nextText() ?: "" }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                val c = ch
                if (inProg && c != null) {
                    out.getOrPut(c) { ArrayList() } += Program(title.ifBlank { "No title" }, desc, start, end, false)
                }
                inProg = false
            }
            ev = p.next()
        }
        out.values.forEach { it.sortBy { prog -> prog.start } }
        return out
    }
}

class M3uSource(private val m3uUrl: String, private val epgUrl: String?) : ContentSource {
    private val lock = Mutex()
    private val epgLock = Mutex()
    private var entries: List<M3uEntry>? = null
    private var guide: Map<String, List<Program>>? = null

    suspend fun load(): List<M3uEntry> = lock.withLock {
        entries ?: Http.stream(m3uUrl) { M3uParser.parse(it) }.also { entries = it }
    }

    private fun id(e: M3uEntry) = "m" + Integer.toHexString(e.url.hashCode())

    private fun categoriesOf(kind: M3uEntry.Kind, list: List<M3uEntry>): List<Category> =
        list.filter { it.kind == kind }.map { it.group }.distinct().map { Category(it, it) }

    override suspend fun liveCategories() = categoriesOf(M3uEntry.Kind.LIVE, load())

    private fun toChannel(e: M3uEntry, num: Int) = LiveChannel(
        id = id(e),
        num = num,
        name = e.name,
        logo = e.logo,
        categoryId = e.group,
        epgId = e.tvgId,
        hasArchive = !e.catchup.isNullOrBlank() || e.catchupDays > 0 || e.catchupSource != null,
        archiveDays = e.catchupDays,
        url = e.url,
    )

    override suspend fun allLiveChannels(): List<LiveChannel> =
        load().filter { it.kind == M3uEntry.Kind.LIVE }.mapIndexed { i, e -> toChannel(e, i + 1) }

    override suspend fun liveChannels(categoryId: String) =
        allLiveChannels().filter { it.categoryId == categoryId }

    private suspend fun loadGuide(): Map<String, List<Program>> = epgLock.withLock {
        guide?.let { return@withLock it }
        val url = epgUrl?.takeIf { it.isNotBlank() } ?: return@withLock emptyMap<String, List<Program>>().also { guide = it }
        val now = System.currentTimeMillis()
        val g = try {
            Http.stream(url) { raw ->
                val buffered = BufferedInputStream(raw)
                buffered.mark(4)
                val b1 = buffered.read(); val b2 = buffered.read()
                buffered.reset()
                val input = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
                XmltvParser.parse(input, now - 8L * 86_400_000, now + 3L * 86_400_000)
            }
        } catch (e: Exception) {
            emptyMap()
        }
        guide = g
        g
    }

    override suspend fun epg(channel: LiveChannel): List<Program> {
        val g = loadGuide()
        val list = channel.epgId?.lowercase()?.let { g[it] } ?: g[channel.name.lowercase()] ?: emptyList()
        if (!channel.hasArchive) return list
        val now = System.currentTimeMillis()
        return list.map { if (it.end < now) it.copy(hasArchive = true) else it }
    }

    override fun catchupUrl(channel: LiveChannel, program: Program): String? {
        val now = System.currentTimeMillis()
        if (!channel.hasArchive || program.start > now) return null
        val e = entries?.firstOrNull { id(it) == channel.id } ?: return null
        val s = program.start / 1000
        val en = program.end / 1000
        val src = e.catchupSource
        if (src != null) {
            val utc = SimpleDateFormat("yyyy MM dd HH mm ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(program.start)).split(' ')
            val filled = src
                .replace("{utc}", "$s").replace("\${start}", "$s").replace("{start}", "$s")
                .replace("{utcend}", "$en").replace("\${end}", "$en").replace("{end}", "$en")
                .replace("{duration}", "${en - s}").replace("{lutc}", "${now / 1000}")
                .replace("\${timestamp}", "${now / 1000}").replace("{offset}", "${now / 1000 - s}")
                .replace("{Y}", utc[0]).replace("{m}", utc[1]).replace("{d}", utc[2])
                .replace("{H}", utc[3]).replace("{M}", utc[4]).replace("{S}", utc[5])
            return if (e.catchup.equals("append", true)) e.url + filled else filled
        }
        // Xtream-style stream link: build a timeshift link from it.
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""").find(e.url) ?: return null
        val (host, u, p, sid) = m.destructured
        val minutes = ((program.end - program.start) / 60000).coerceAtLeast(1)
        val start = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(program.start))
        return "$host/timeshift/$u/$p/$minutes/$start/$sid.ts"
    }

    override suspend fun vodCategories() = categoriesOf(M3uEntry.Kind.MOVIE, load())

    override suspend fun vodItems(categoryId: String) = load()
        .filter { it.kind == M3uEntry.Kind.MOVIE && it.group == categoryId }
        .map { VodItem(id(it), it.name, it.logo, null, it.group, "", it.url) }

    override suspend fun vodDetails(item: VodItem): VodDetails? = null

    override fun vodUrl(item: VodItem) = item.directUrl ?: ""

    override suspend fun seriesCategories() = categoriesOf(M3uEntry.Kind.SERIES, load())

    override suspend fun seriesItems(categoryId: String) = load()
        .filter { it.kind == M3uEntry.Kind.SERIES && it.group == categoryId }
        .map { SeriesItem(id(it), it.name, it.logo, null, null, it.group, it.url) }

    override suspend fun episodes(series: SeriesItem): List<Episode> =
        listOf(Episode(series.id, series.name, 1, 1, "", null, series.cover, series.directUrl))

    override fun episodeUrl(episode: Episode) = episode.directUrl ?: ""

    override fun clearCache() {
        entries = null
        guide = null
    }
}
