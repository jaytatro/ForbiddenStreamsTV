package com.forbiddenstreams.tv.sports

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The user's own order of sports (Nov 13): set in the Sports tab by holding OK on a sport and moving it
 * ▲▼. Home's live-games rows follow the same order (favorite teams' sports always on top there).
 */
object SportOrder {
    private const val KEY = "sports_order"
    var version by mutableIntStateOf(0)

    fun get(context: Context): List<League> {
        val saved = context.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(",")?.mapNotNull { n -> SPORTS_ORDER.firstOrNull { it.name == n } }.orEmpty().distinct()
        return saved + SPORTS_ORDER.filter { it !in saved }
    }

    fun set(context: Context, order: List<League>) {
        context.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE).edit()
            .putString(KEY, order.joinToString(",") { it.name }).apply()
        version++
    }
}
