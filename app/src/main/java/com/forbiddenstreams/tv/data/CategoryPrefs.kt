package com.forbiddenstreams.tv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The user's own order for live TV categories, and which ones are hidden.
 * Set in Settings › Categories. New categories from the provider go to the end.
 */
object CategoryPrefs {
    /** Bumped on every change so screens refresh. */
    var version by mutableIntStateOf(0)
        private set

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)

    fun order(context: Context): List<String> =
        (sp(context).getString("cat_order", "") ?: "").split('\u0001').filter { it.isNotEmpty() }

    fun hidden(context: Context): Set<String> =
        sp(context).getStringSet("cat_hidden", emptySet())?.toSet() ?: emptySet()

    /** All categories in the user's order (hidden ones included). */
    fun ordered(context: Context, cats: List<Category>): List<Category> {
        val pos = order(context).withIndex().associate { (i, id) -> id to i }
        return cats.withIndex()
            .sortedWith(compareBy({ pos[it.value.id] ?: (100_000 + it.index) }))
            .map { it.value }
    }

    /** What the Channels tab shows: user's order, hidden and parental-locked ones removed. */
    fun visible(context: Context, cats: List<Category>): List<Category> {
        val h = hidden(context) + locked(context)
        return ordered(context, cats).filter { it.id !in h }
    }

    /** Hidden or locked: left out of the main screens (and All / Search). */
    fun excluded(context: Context): Set<String> = hidden(context) + locked(context)

    // ---- Parental lock ----

    fun locked(context: Context): Set<String> =
        sp(context).getStringSet("cat_locked", emptySet())?.toSet() ?: emptySet()

    fun toggleLocked(context: Context, id: String) {
        val l = locked(context).toMutableSet()
        if (!l.add(id)) l.remove(id)
        sp(context).edit().putStringSet("cat_locked", l).apply()
        version++
    }

    fun pin(context: Context): String? = sp(context).getString("parental_pin", null)?.takeIf { it.length == 4 }

    fun setPin(context: Context, pin: String?) {
        sp(context).edit().apply { if (pin == null) remove("parental_pin") else putString("parental_pin", pin) }.apply()
        version++
    }

    fun move(context: Context, cats: List<Category>, id: String, step: Int) {
        val list = ordered(context, cats).map { it.id }.toMutableList()
        val i = list.indexOf(id)
        val j = i + step
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        sp(context).edit().putString("cat_order", list.joinToString("\u0001")).apply()
        version++
    }

    fun toggleHidden(context: Context, id: String) {
        val h = hidden(context).toMutableSet()
        if (!h.add(id)) h.remove(id)
        sp(context).edit().putStringSet("cat_hidden", h).apply()
        version++
    }

    fun reset(context: Context) {
        sp(context).edit().remove("cat_order").remove("cat_hidden").apply()
        version++
    }
}
