package com.forbiddenstreams.tv.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val context: Context = context.applicationContext

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("forbidden_streams", Context.MODE_PRIVATE)

    fun loadLogin(): LoginConfig? = when (sp.getString("login_type", null)) {
        "xtream" -> LoginConfig.Xtream(
            server = sp.getString("server", "") ?: "",
            username = sp.getString("username", "") ?: "",
            password = sp.getString("password", "") ?: "",
            timezone = sp.getString("timezone", null),
        )
        "m3u" -> LoginConfig.M3u(
            url = sp.getString("m3u_url", "") ?: "",
            epgUrl = sp.getString("epg_url", null),
        )
        else -> null
    }

    fun saveLogin(c: LoginConfig) {
        val e = sp.edit()
        when (c) {
            is LoginConfig.Xtream -> e.putString("login_type", "xtream")
                .putString("server", c.server)
                .putString("username", c.username)
                .putString("password", c.password)
                .putString("timezone", c.timezone)
            is LoginConfig.M3u -> e.putString("login_type", "m3u")
                .putString("m3u_url", c.url)
                .putString("epg_url", c.epgUrl)
        }
        e.apply()
    }

    fun clearLogin() {
        sp.edit().remove("login_type").remove("password").apply()
    }

    /** Last values typed on the login screen, so re-login is quick. */
    fun lastField(key: String): String = sp.getString("last_$key", "") ?: ""
    fun setLastField(key: String, value: String) = sp.edit().putString("last_$key", value).apply()

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("favorites", v).apply()

    fun toggleFavorite(id: String): Boolean {
        val f = favorites.toMutableSet()
        val added = if (f.contains(id)) { f.remove(id); false } else { f.add(id); true }
        favorites = f
        return added
    }

    /** Most recent first. */
    var history: List<String>
        get() = (sp.getString("history", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("history", v.take(50).joinToString(",")).apply()

    fun addHistory(id: String) {
        history = listOf(id) + history.filter { it != id }
    }

    /** Category whose guide was open last, so the app can go back to it. */
    var lastGroup: String
        get() = sp.getString("last_group", "all") ?: "all"
        set(v) = sp.edit().putString("last_group", v).apply()

    var remindersJson: String
        get() = sp.getString("reminders", "[]") ?: "[]"
        set(v) = sp.edit().putString("reminders", v).apply()

    var recordingsJson: String
        get() = sp.getString("recordings", "[]") ?: "[]"
        set(v) = sp.edit().putString("recordings", v).apply()
}
