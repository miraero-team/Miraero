package com.miraero.driveapp

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class PlaylistRepositoryTest {
    private fun event(type: String, songs: List<Song>, live: Boolean = false) = EventCard(
        "event", "행사", type, "장소", 37.5, 127.0,
        LocalDateTime.of(2026, 11, 14, 0, 0), LocalDateTime.of(2026, 11, 15, 0, 0),
        emptyList(), "", "", songs, dateOnly = live
    )

    @Test fun festivalFallbackDoesNotUseUnrelatedConcertSongs() {
        val festivalSong = Song(4, "축제곡", "가수", "분위기", "festival")
        val concertSong = Song(1, "공연곡", "가수", "대표곡", "concert")
        val source = LocalPlaylistRepository(listOf(
            event("CONCERT", listOf(concertSong)), event("FESTIVAL", listOf(festivalSong))
        ))
        assertEquals(listOf(festivalSong), source.recommend(event("FESTIVAL", emptyList(), live = true)))
        assertEquals(listOf(concertSong), source.recommend(event("CONCERT", listOf(concertSong))))
    }

    @Test fun acceptsRemotePreviewsAndRemovesUnplayableOrDuplicateTracks() {
        val preview = Song(1, "외부 추천곡", "가수", "추천", previewUrl = "https://example.com/preview.mp3")
        val local = Song(4, "로컬곡", "가수", "추천", "local")
        val source: PlaylistRepository = LocalPlaylistRepository(emptyList())
        val result = source.recommend(event("CONCERT", listOf(local, preview, local,
            Song(1, "미리듣기 없음", "가수", "추천"))))
        assertEquals(listOf(preview, local), result)
    }
}
