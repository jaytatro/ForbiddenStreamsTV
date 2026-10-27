package com.forbiddenstreams.tv.data

data class Category(val id: String, val name: String)

data class LiveChannel(
    val id: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val epgId: String?,
    val hasArchive: Boolean,
    val archiveDays: Int,
    val url: String,
)

/** Times are epoch milliseconds. */
data class Program(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    val hasArchive: Boolean,
) {
    fun isLive(now: Long = System.currentTimeMillis()) = now in start until end
}

data class VodItem(
    val id: String,
    val name: String,
    val poster: String?,
    val rating: String?,
    val categoryId: String,
    val ext: String,
    val directUrl: String? = null,
)

data class VodDetails(
    val plot: String?,
    val duration: String?,
    val genre: String?,
    val cast: String?,
    val year: String?,
)

data class SeriesItem(
    val id: String,
    val name: String,
    val cover: String?,
    val plot: String?,
    val rating: String?,
    val categoryId: String,
    val directUrl: String? = null,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val ext: String,
    val plot: String?,
    val image: String?,
    val directUrl: String? = null,
)

sealed class LoginConfig {
    data class Xtream(
        val server: String,
        val username: String,
        val password: String,
        val timezone: String? = null,
    ) : LoginConfig()

    data class M3u(val url: String, val epgUrl: String?) : LoginConfig()
}

interface ContentSource {
    suspend fun liveCategories(): List<Category>
    suspend fun liveChannels(categoryId: String): List<LiveChannel>
    suspend fun allLiveChannels(): List<LiveChannel>
    suspend fun epg(channel: LiveChannel): List<Program>
    fun catchupUrl(channel: LiveChannel, program: Program): String?

    suspend fun vodCategories(): List<Category>
    suspend fun vodItems(categoryId: String): List<VodItem>
    suspend fun vodDetails(item: VodItem): VodDetails?
    fun vodUrl(item: VodItem): String

    /** Every movie, for search. */
    suspend fun allVod(): List<VodItem> = vodCategories().flatMap { vodItems(it.id) }.distinctBy { it.id }

    suspend fun seriesCategories(): List<Category>
    suspend fun seriesItems(categoryId: String): List<SeriesItem>
    suspend fun episodes(series: SeriesItem): List<Episode>
    fun episodeUrl(episode: Episode): String

    /** Every show, for search. */
    suspend fun allSeries(): List<SeriesItem> = seriesCategories().flatMap { seriesItems(it.id) }.distinctBy { it.id }

    fun clearCache()
}
