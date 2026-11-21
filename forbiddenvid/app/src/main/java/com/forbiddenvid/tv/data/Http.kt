package com.forbiddenvid.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "ForbiddenVid/1.0 (Linux; Android TV)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(r.newBuilder().header("User-Agent", r.header("User-Agent") ?: USER_AGENT).build())
        }
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server answered HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    suspend fun getJson(url: String): JSONObject =
        jsonObject(get(url)) ?: throw IOException("Not a valid add-on answer")
}

fun jsonObject(text: String): JSONObject? = try {
    JSONObject(text.trim())
} catch (e: Exception) {
    null
}

/** Returns the string value, or null when missing, blank or the literal "null". */
fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "").trim()
    return if (v.isEmpty() || v == "null") null else v
}

fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}

/** A JSON array of strings (or a single string) as a list. */
fun JSONObject.strings(key: String): List<String> {
    val v = opt(key) ?: return emptyList()
    return when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() && s != "null" } }
        is String -> listOf(v).filter { it.isNotBlank() }
        else -> emptyList()
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
