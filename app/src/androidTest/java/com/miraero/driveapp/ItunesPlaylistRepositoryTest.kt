package com.miraero.driveapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ItunesPlaylistRepositoryTest {
    private fun track(id: Int = 1) = JSONObject().apply {
        put("kind", "song")
        put("artistId", 409076743L)
        put("artistName", "IU")
        put("trackName", "곡 $id")
        put("previewUrl", "https://audio-ssl.itunes.apple.com/$id.m4a")
        put("trackViewUrl", "https://music.apple.com/us/album/test/1?i=$id")
    }

    @Test fun skipsArtistRecordsAndOtherArtistsAndKeepsThreeDistinctPlayableSongs() {
        val rows = JSONArray().put(JSONObject().put("wrapperType", "artist"))
            .put(track().put("artistId", 999)).put(track()).put(track(99).put("trackName", "곡 1"))
            .put(track(2)).put(track(3)).put(track(4))
        val songs = ItunesPlaylistRepository.parse(JSONObject().put("results", rows), 409076743L)
        assertEquals(listOf("곡 1", "곡 2", "곡 3"), songs.map { it.title })
        assertTrue(songs.all { it.artist == "IU" && it.clip.isEmpty() && it.storeUrl.isNotBlank() })
    }

    @Test fun rejectsMissingMetadataAndUnsafePreviewOrStoreLinks() {
        val rows = JSONArray().put(track().put("previewUrl", JSONObject.NULL))
            .put(track().put("trackName", ""))
            .put(track().put("artistName", ""))
            .put(track().put("kind", "music-video"))
        for (url in listOf("http://audio.example.com/a.mp3", "file:///a.mp3", "not a URL", "https://user@host/a")) {
            rows.put(track().put("previewUrl", url))
        }
        for (url in listOf("https://music.apple.com.evil.example/a", "https://evil.example/a", "https://user@music.apple.com/a")) {
            rows.put(track().put("trackViewUrl", url))
        }
        assertTrue(ItunesPlaylistRepository.parse(JSONObject().put("results", rows), 409076743L).isEmpty())
        assertFalse(ItunesPlaylistRepository.validStoreUrl("javascript:alert(1)"))
    }

    @Test fun emptyCatalogIsEmptyButBrokenResponseFails() {
        assertTrue(ItunesPlaylistRepository.parse(JSONObject().put("results", JSONArray()), 409076743L).isEmpty())
        assertThrows(org.json.JSONException::class.java) { ItunesPlaylistRepository.parse(JSONObject(), 409076743L) }
    }
}
