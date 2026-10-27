package com.forbiddenstreams.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** A movie or episode that was started but not finished. */
data class RecentItem(
    val key: String,        // "m:<id>" for movies, "e:<id>" for episodes
    val kind: String,       // "movie" or "episode"
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/**
 * "Recently Watched" for Movies and TV Shows. Saved on the box.
 * Anything with 5 minutes or less left is treated as finished and dropped.
 */
object Recents {
    private const val KEY = "recent_vod"
    private const val FINISHED_MS = 5 * 60_000L
    private const val MAX = 60

    /** Bumped on every change so screens showing the list refresh. */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)

    fun all(context: Context, kind: String? = null): List<RecentItem> {
        val arr = try { JSONArray(prefs(context).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<RecentItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = RecentItem(
                key = o.optString("key"), kind = o.optString("kind"), title = o.optString("title"),
                subtitle = o.optString("sub").ifEmpty { null }, image = o.optString("img").ifEmpty { null },
                url = o.optString("url"), positionMs = o.optLong("pos"), durationMs = o.optLong("dur"),
                updatedAt = o.optLong("ts"),
            )
            if (kind == null || r.kind == kind) out += r
        }
        return out.sortedByDescending { it.updatedAt }
    }

    private fun write(context: Context, list: List<RecentItem>) {
        val arr = JSONArray()
        list.sortedByDescending { it.updatedAt }.take(MAX).forEach {
            arr.put(JSONObject().put("key", it.key).put("kind", it.kind).put("title", it.title)
                .put("sub", it.subtitle ?: "").put("img", it.image ?: "").put("url", it.url)
                .put("pos", it.positionMs).put("dur", it.durationMs).put("ts", it.updatedAt))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        version++
    }

    /** Saves where playback is; removes the item once 5 minutes or less are left. */
    fun save(context: Context, item: RecentItem) {
        val rest = all(context).filter { it.key != item.key }
        val finished = item.durationMs > 0 && item.durationMs - item.positionMs <= FINISHED_MS
        val tooEarly = item.positionMs < 15_000
        when {
            finished -> write(context, rest)
            tooEarly -> Unit // under 15 seconds watched: nothing worth saving yet
            else -> write(context, rest + item)
        }
    }

    fun remove(context: Context, key: String) = write(context, all(context).filter { it.key != key })

    fun get(context: Context, key: String): RecentItem? = all(context).firstOrNull { it.key == key }
}
