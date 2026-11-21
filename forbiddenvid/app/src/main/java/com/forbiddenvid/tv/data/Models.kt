package com.forbiddenvid.tv.data

import org.json.JSONObject

/** One row of titles an add-on offers (e.g. "Popular movies"). */
data class Catalog(
    val type: String,
    val id: String,
    val name: String,
    val extras: List<Extra>,
) {
    /** Catalogs that need something we don't have (a genre, a search word…) can't be shown as a plain row. */
    val needsExtra: Boolean get() = extras.any { it.isRequired && it.name != "skip" }
    val searchable: Boolean get() = extras.any { it.name == "search" }
    val onlySearch: Boolean get() = extras.any { it.name == "search" && it.isRequired }
}

data class Extra(val name: String, val isRequired: Boolean, val options: List<String>)

data class Resource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

/** An installed add-on (its manifest). */
data class Addon(
    val manifestUrl: String,
    val base: String,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val logo: String?,
    val types: List<String>,
    val catalogs: List<Catalog>,
    val resources: List<Resource>,
    val idPrefixes: List<String>?,
    val enabled: Boolean,
    val raw: String,
) {
    /** True if this add-on answers [resource] requests for this type and id. */
    fun handles(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = r.idPrefixes ?: idPrefixes
        if (prefixes != null && prefixes.isNotEmpty() && prefixes.none { id.startsWith(it) }) return false
        return true
    }

    companion object {
        fun parse(manifestUrl: String, base: String, j: JSONObject, enabled: Boolean): Addon {
            val types = j.strings("types")
            val catalogs = j.optJSONArray("catalogs")?.objects()?.mapNotNull { c ->
                val type = c.str("type") ?: return@mapNotNull null
                val id = c.str("id") ?: return@mapNotNull null
                val extras = ArrayList<Extra>()
                c.optJSONArray("extra")?.objects()?.forEach { e ->
                    val n = e.str("name") ?: return@forEach
                    extras += Extra(n, e.optBoolean("isRequired", false), e.strings("options"))
                }
                // Older manifests: extraSupported / extraRequired.
                val req = c.strings("extraRequired")
                c.strings("extraSupported").forEach { n -> if (extras.none { it.name == n }) extras += Extra(n, n in req, emptyList()) }
                Catalog(type, id, c.str("name") ?: id, extras)
            } ?: emptyList()
            val resources = ArrayList<Resource>()
            j.optJSONArray("resources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (val r = arr.opt(i)) {
                        is String -> resources += Resource(r, null, null)
                        is JSONObject -> r.str("name")?.let { n ->
                            resources += Resource(n, r.strings("types").ifEmpty { null }, if (r.has("idPrefixes")) r.strings("idPrefixes") else null)
                        }
                    }
                }
            }
            return Addon(
                manifestUrl = manifestUrl,
                base = base,
                id = j.str("id") ?: manifestUrl,
                name = j.str("name") ?: "Add-on",
                version = j.str("version") ?: "",
                description = j.str("description") ?: "",
                logo = j.str("logo") ?: j.str("icon"),
                types = types,
                catalogs = catalogs,
                resources = resources,
                idPrefixes = if (j.has("idPrefixes")) j.strings("idPrefixes") else null,
                enabled = enabled,
                raw = j.toString(),
            )
        }
    }
}

/** A movie, series or other title (from a catalog row or the details request). */
data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
) {
    val isSeriesLike: Boolean get() = videos.size > 1 || type == "series" || type == "anime"

    companion object {
        fun parse(j: JSONObject, fallbackType: String): Meta? {
            val id = j.str("id") ?: j.str("imdb_id") ?: return null
            val videos = j.optJSONArray("videos")?.objects()?.mapNotNull { Video.parse(it) } ?: emptyList()
            return Meta(
                id = id,
                type = j.str("type") ?: fallbackType,
                name = j.str("name") ?: j.str("title") ?: id,
                poster = j.str("poster"),
                posterShape = j.str("posterShape"),
                background = j.str("background"),
                logo = j.str("logo"),
                description = j.str("description") ?: j.str("overview"),
                releaseInfo = j.str("releaseInfo") ?: j.str("year") ?: j.str("released")?.take(4),
                rating = j.str("imdbRating"),
                runtime = j.str("runtime"),
                genres = j.strings("genres").ifEmpty { j.strings("genre") },
                cast = j.strings("cast"),
                videos = videos.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
            )
        }
    }
}

/** An episode (or a part of a title). */
data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
) {
    val label: String
        get() = if (season != null && episode != null) "S${season}E$episode" else ""

    companion object {
        fun parse(j: JSONObject): Video? {
            val id = j.str("id") ?: return null
            val ep = j.intOrNull("episode") ?: j.intOrNull("number")
            return Video(
                id = id,
                title = j.str("title") ?: j.str("name") ?: (ep?.let { "Episode $it" } ?: id),
                season = j.intOrNull("season"),
                episode = ep,
                released = j.str("released")?.take(10),
                thumbnail = j.str("thumbnail"),
                overview = j.str("overview") ?: j.str("description"),
            )
        }
    }
}

/** A playable link an add-on returned. Only direct links ([url]) play in the app. */
data class Stream(
    val addonName: String,
    val url: String?,
    val name: String,
    val title: String,
    val headers: Map<String, String>,
    val unsupported: String?,
) {
    companion object {
        fun parse(addonName: String, j: JSONObject): Stream {
            val headers = LinkedHashMap<String, String>()
            j.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                h.keys().forEach { k -> h.str(k)?.let { headers[k] = it } }
            }
            val url = j.str("url")
            val why = when {
                url != null -> null
                j.has("infoHash") -> "Torrent links aren't supported"
                j.has("ytId") -> "YouTube links aren't supported"
                j.has("externalUrl") -> "Opens outside the app — not supported"
                else -> "No link"
            }
            return Stream(
                addonName = addonName,
                url = url,
                name = j.str("name") ?: addonName,
                title = j.str("title") ?: j.str("description") ?: "",
                headers = headers,
                unsupported = why,
            )
        }
    }
}
