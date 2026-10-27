package com.forbiddenstreams.tv.data

import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The logged-in provider plus shared caches. */
object Session {
    var source: ContentSource? = null
        private set

    private val epgCache = ConcurrentHashMap<String, Pair<Long, List<Program>>>()
    private val epgLimiter = Semaphore(6)

    fun restore(context: Context): Boolean {
        val c = Prefs(context).loadLogin() ?: return false
        source = build(c)
        return true
    }

    private fun build(c: LoginConfig): ContentSource = when (c) {
        is LoginConfig.Xtream -> XtreamSource(c.server, c.username, c.password, c.timezone)
        is LoginConfig.M3u -> M3uSource(c.url, c.epgUrl)
    }

    suspend fun login(context: Context, c: LoginConfig) {
        val prefs = Prefs(context)
        when (c) {
            is LoginConfig.Xtream -> {
                val s = XtreamSource(c.server, c.username, c.password, null)
                val tz = s.login()
                prefs.saveLogin(c.copy(server = s.base, timezone = tz))
                source = s
            }
            is LoginConfig.M3u -> {
                val s = M3uSource(c.url, c.epgUrl)
                if (s.load().isEmpty()) throw IOException("That playlist is empty or isn't an M3U playlist.")
                prefs.saveLogin(c)
                source = s
            }
        }
        epgCache.clear()
    }

    fun logout(context: Context) {
        Prefs(context).clearLogin()
        source = null
        epgCache.clear()
    }

    fun refresh() {
        source?.clearCache()
        epgCache.clear()
    }

    /** EPG for one channel, cached for 15 minutes, max 6 requests at once. */
    suspend fun epg(channel: LiveChannel): List<Program> {
        val now = System.currentTimeMillis()
        epgCache[channel.id]?.let { (time, list) -> if (now - time < 15 * 60_000) return list }
        val src = source ?: return emptyList()
        val list = try {
            epgLimiter.withPermit { src.epg(channel) }
        } catch (e: Exception) {
            emptyList()
        }
        epgCache[channel.id] = now to list
        return list
    }

    fun cachedEpg(channel: LiveChannel): List<Program>? = epgCache[channel.id]?.second
}
