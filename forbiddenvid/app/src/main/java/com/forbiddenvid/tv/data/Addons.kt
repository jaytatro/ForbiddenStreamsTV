package com.forbiddenvid.tv.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * The add-ons the user installed. The app ships with none.
 *
 * Add-ons use the open Stremio add-on format: a web address that answers
 *   manifest.json                         — name, what it offers
 *   catalog/<type>/<id>[/<extra>].json    — rows of titles
 *   meta/<type>/<id>.json                 — details, episodes
 *   stream/<type>/<videoId>.json          — playable links
 */
object Addons {
    private const val PREFS = "addons"
    private const val KEY = "list"

    /** Installed add-ons in the user's order (Compose watches this). */
    val list = mutableStateListOf<Addon>()
    private lateinit var appContext: Context

    val enabled: List<Addon> get() = list.filter { it.enabled }

    fun init(context: Context) {
        appContext = context.applicationContext
        list.clear()
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.str("url") ?: continue
                val m = jsonObject(o.optString("manifest")) ?: continue
                list += Addon.parse(url, baseOf(url), m, o.optBoolean("enabled", true))
            }
        } catch (e: Exception) { }
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { a -> arr.put(JSONObject().put("url", a.manifestUrl).put("enabled", a.enabled).put("manifest", a.raw)) }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Turns what the user typed / pasted into the manifest address. */
    fun normalize(input: String): String {
        var u = input.trim().replace(" ", "")
        if (u.startsWith("stremio://", ignoreCase = true)) u = "https://" + u.substring("stremio://".length)
        if (!u.contains("://")) u = "https://$u"
        val path = u.substringBefore('?')
        if (!path.endsWith(".json")) u = path.trimEnd('/') + "/manifest.json" + (if (u.contains('?')) "?" + u.substringAfter('?') else "")
        return u
    }

    fun baseOf(manifestUrl: String): String {
        val path = manifestUrl.substringBefore('?')
        return path.substringBeforeLast('/')
    }

    /** Reads the add-on's manifest (without installing it). */
    suspend fun fetch(input: String): Addon {
        val url = normalize(input)
        val j = try {
            Http.getJson(url)
        } catch (e: Exception) {
            throw IOException("Couldn't read an add-on at that address (${e.message})")
        }
        if (j.str("id") == null || j.str("name") == null) throw IOException("That address isn't an add-on")
        return Addon.parse(url, baseOf(url), j, enabled = true)
    }

    /** Installs (or updates) an add-on. Returns it. */
    suspend fun install(input: String): Addon {
        val a = fetch(input)
        withContext(Dispatchers.Main) {
            val i = list.indexOfFirst { it.manifestUrl == a.manifestUrl || it.id == a.id }
            if (i >= 0) list[i] = a else list += a
            save()
        }
        return a
    }

    fun remove(a: Addon) { list.removeAll { it.manifestUrl == a.manifestUrl }; save() }

    fun setEnabled(a: Addon, on: Boolean) {
        val i = list.indexOfFirst { it.manifestUrl == a.manifestUrl }
        if (i >= 0) { list[i] = list[i].copy(enabled = on); save() }
    }

    fun move(a: Addon, by: Int) {
        val i = list.indexOfFirst { it.manifestUrl == a.manifestUrl }
        val j = i + by
        if (i < 0 || j < 0 || j >= list.size) return
        val x = list.removeAt(i)
        list.add(j, x)
        save()
    }

    /** Re-reads every manifest in the background so add-ons pick up their own changes. */
    suspend fun refreshAll() = coroutineScope {
        list.toList().forEach { a ->
            launch {
                try {
                    val j = Http.getJson(a.manifestUrl)
                    if (j.str("id") == null) return@launch
                    val fresh = Addon.parse(a.manifestUrl, a.base, j, a.enabled)
                    withContext(Dispatchers.Main) {
                        val i = list.indexOfFirst { it.manifestUrl == a.manifestUrl }
                        if (i >= 0 && list[i].raw != fresh.raw) { list[i] = fresh.copy(enabled = list[i].enabled); save() }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** One page of a catalog row. [skip] = how many already shown. */
    suspend fun catalog(a: Addon, c: Catalog, skip: Int = 0, search: String? = null, genre: String? = null): List<Meta> {
        val extras = ArrayList<String>()
        if (search != null) extras += "search=${enc(search)}"
        if (genre != null) extras += "genre=${enc(genre)}"
        if (skip > 0) extras += "skip=$skip"
        val url = "${a.base}/catalog/${enc(c.type)}/${enc(c.id)}" + (if (extras.isEmpty()) "" else "/" + extras.joinToString("&")) + ".json"
        val j = Http.getJson(url)
        return j.optJSONArray("metas")?.objects()?.mapNotNull { Meta.parse(it, c.type) } ?: emptyList()
    }

    /** Full details (episodes for series): asks the add-ons that offer details for this id, first answer wins. */
    suspend fun meta(preview: Meta): Meta {
        val candidates = enabled.filter { it.handles("meta", preview.type, preview.id) }
        for (a in candidates) {
            val m = try {
                withTimeoutOrNull(20_000) {
                    Http.getJson("${a.base}/meta/${enc(preview.type)}/${enc(preview.id)}.json").optJSONObject("meta")?.let { Meta.parse(it, preview.type) }
                }
            } catch (e: Exception) { null }
            if (m != null) return m.copy(
                poster = m.poster ?: preview.poster,
                background = m.background ?: preview.background,
                description = m.description ?: preview.description,
            )
        }
        return preview
    }

    /**
     * Asks every add-on that offers links for this video, all at once.
     * [onResult] is called (on the main thread) as each add-on answers.
     */
    suspend fun streams(type: String, videoId: String, onResult: (Addon, List<Stream>, String?) -> Unit) = coroutineScope {
        enabled.filter { it.handles("stream", type, videoId) }.map { a ->
            async {
                val (found, err) = try {
                    val r = withTimeoutOrNull(30_000) {
                        Http.getJson("${a.base}/stream/${enc(type)}/${enc(videoId)}.json")
                            .optJSONArray("streams")?.objects()?.map { Stream.parse(a.name, it) } ?: emptyList()
                    }
                    if (r == null) emptyList<Stream>() to "took too long" else r to null
                } catch (e: Exception) {
                    emptyList<Stream>() to (e.message ?: "error")
                }
                withContext(Dispatchers.Main) { onResult(a, found, err) }
            }
        }.awaitAll()
        Unit
    }

    /** Every enabled add-on's catalogs that can be shown as a row, optionally only one type. */
    fun rows(type: String? = null): List<Pair<Addon, Catalog>> =
        enabled.flatMap { a -> a.catalogs.filter { !it.needsExtra && (type == null || it.type == type) }.map { a to it } }

    fun searchRows(): List<Pair<Addon, Catalog>> =
        enabled.flatMap { a -> a.catalogs.filter { it.searchable }.map { a to it } }

    /** Types offered by the enabled add-ons' rows, movies and series first. */
    fun rowTypes(): List<String> {
        val t = rows().map { it.second.type }.distinct()
        return t.sortedBy { when (it) { "movie" -> 0; "series" -> 1; else -> 2 } }
    }
}
