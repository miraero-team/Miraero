package com.miraero.driveapp

/** 외부 API 구현도 행사 정보를 받아 같은 Song 목록으로 반환한다. 호출은 UI 스레드 밖에서 한다. */
fun interface PlaylistRepository {
    fun recommend(event: EventCard): List<Song>
}

class LocalPlaylistRepository(private val events: List<EventCard>) : PlaylistRepository {
    override fun recommend(event: EventCard): List<Song> {
        val songs = if (event.dateOnly) events.filter { it.type == "FESTIVAL" }.flatMap { it.songs }
                    else event.songs
        return songs.filter { it.title.isNotBlank() && (it.clip.isNotBlank() || it.previewUrl.isNotBlank()) }
            .sortedBy { it.priority }.distinctBy { it.clip.ifBlank { it.previewUrl } }
    }
}
