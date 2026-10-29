package com.forbiddenstreams.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Http {
    const val DEFAULT_USER_AGENT = "ForbiddenStreams/1.0 (Linux; Android TV)"

    /** Some providers only answer certain apps; login picks one that works and it's saved. */
    @Volatile var userAgent: String = DEFAULT_USER_AGENT

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", chain.request().header("User-Agent") ?: userAgent).build())
        }
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server answered HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    suspend fun <T> stream(url: String, block: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server answered HTTP ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            body.byteStream().use(block)
        }
    }

    /** Quick probe used while logging in: short timeouts, never throws. Returns (HTTP code, body) or null. */
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(7, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    suspend fun probe(url: String, ua: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            probeClient.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r ->
                r.code to (r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            null
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

fun jsonArray(text: String): JSONArray = try {
    JSONArray(text.trim())
} catch (e: Exception) {
    JSONArray()
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

fun JSONObject.int(key: String, def: Int = 0): Int {
    val v = str(key) ?: return def
    return v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt() ?: def
}
