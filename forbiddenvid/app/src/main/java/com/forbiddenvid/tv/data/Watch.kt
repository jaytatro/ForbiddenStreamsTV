package com.forbiddenvid.tv.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** Something you started watching: where you stopped, so you can carry on. */
data class WatchEntry(
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val videoId: String,
    val episodeLabel: String,
    val position: Long,
    val duration: Long,
    val updated: Long,
) {
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    /** Worth offering "Resume": past 30 s and not at the end. */
    val resumable: Boolean get() = position > 30_000 && (duration <= 0 || position < duration * 0.95)

    fun toMeta() = Meta(id = metaId, type = type, name = name, poster = poster, background = background)

    fun toJson(): JSONObject = JSONObject()
        .put("metaId", metaId).put("type", type).put("name", name).put("poster", poster ?: "")
        .put("background", background ?: "").put("videoId", videoId).put("episodeLabel", episodeLabel)
        .put("position", position).put("duration", duration).put("updated", updated)

    companion object {
        fun parse(o: JSONObject): WatchEntry? {
            return WatchEntry(
                metaId = o.str("metaId") ?: return null,
                type = o.str("type") ?: "movie",
                name = o.str("name") ?: "",
                poster = o.str("poster"),
                background = o.str("background"),
                videoId = o.str("videoId") ?: return null,
                episodeLabel = o.str("episodeLabel") ?: "",
                position = o.optLong("position"),
                duration = o.optLong("duration"),
                updated = o.optLong("updated"),
            )
        }
    }
}

object Watch {
    private const val PREFS = "watch"
    private const val KEY = "entries"
    private const val MAX = 200

    /** Newest first. One entry per video (episode / movie). */
    val entries = mutableStateListOf<WatchEntry>()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        entries.clear()
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { WatchEntry.parse(it) }?.let { entries += it }
        } catch (e: Exception) { }
    }

    private fun save() {
        val arr = JSONArray()
        entries.take(MAX).forEach { arr.put(it.toJson()) }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(videoId: String): WatchEntry? = entries.firstOrNull { it.videoId == videoId }

    fun update(e: WatchEntry) {
        entries.removeAll { it.videoId == e.videoId }
        entries.add(0, e)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        save()
    }

    /** "Continue watching" row: one card per title (latest episode), unfinished ones only. */
    fun continueRow(): List<WatchEntry> {
        val seen = HashSet<String>()
        return entries.filter { it.resumable && seen.add(it.metaId) }.take(30)
    }

    fun removeTitle(metaId: String) { entries.removeAll { it.metaId == metaId }; save() }
}
