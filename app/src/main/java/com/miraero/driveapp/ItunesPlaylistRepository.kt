package com.miraero.driveapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ItunesPlaylistRepository(private val events: List<EventCard>) : PlaylistRepository {
    private val local = LocalPlaylistRepository(events)
    private val cache = mutableMapOf<Long, List<Song>>()

    override fun recommend(event: EventCard): List<Song> {
        val ids = if (event.dateOnly) events.filter { it.type == "FESTIVAL" }.flatMap { it.musicArtistIds }
                  else event.musicArtistIds
        if (ids.isEmpty()) return local.recommend(event)
        return ids.filter { it > 0 }.distinct().flatMap { id ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            cache.getOrPut(id) { fetch(id) }
        }.distinctBy { it.artist to it.title }.map { song ->
            song.copy(priority = if (event.type == "CONCERT") 1 else 4,
                reason = if (event.type == "CONCERT") "공연 아티스트의 곡" else "축제 분위기 음악 · 출연진 정보 아님")
        }
    }

    private fun fetch(artistId: Long): List<Song> {
        // shortcut: 한국 스토어 음악 검색이 비어 있어 미국 카탈로그 사용, 한국 지원 확인 시 전환.
        val connection = URL("https://itunes.apple.com/lookup?id=$artistId&entity=song&limit=10&country=US")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            check(connection.responseCode == 200) { "iTunes 조회 HTTP 오류" }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            parse(json, artistId)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun parse(json: JSONObject, artistId: Long): List<Song> {
            val rows = json.getJSONArray("results")
            return (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                fun field(key: String) = row.optString(key).trim().takeUnless { it == "null" }.orEmpty()
                if (field("kind") != "song" || row.optLong("artistId") != artistId) return@mapNotNull null
                val title = field("trackName")
                val artist = field("artistName")
                val preview = field("previewUrl")
                val store = field("trackViewUrl")
                if (title.isBlank() || artist.isBlank() || !validUrl(preview) || !validStoreUrl(store)) return@mapNotNull null
                Song(1, title, artist, "", previewUrl = preview, storeUrl = store)
            }.distinctBy { it.title }.take(3)
        }

        private fun validUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)

        internal fun validStoreUrl(value: String): Boolean = validUrl(value) &&
            URI(value).host.lowercase() in setOf("music.apple.com", "itunes.apple.com")
    }
}
