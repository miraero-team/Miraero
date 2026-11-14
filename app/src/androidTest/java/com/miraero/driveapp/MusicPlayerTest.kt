package com.miraero.driveapp

import android.media.MediaPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class MusicPlayerTest {
    @Test fun insecureOrMalformedPreviewUrlsDoNotStartPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (url in listOf("http://example.com/preview.mp3", "file:///etc/passwd", "not a URL")) {
            MusicPlayer.play(context, Song(4, "외부 곡", "테스트", "테스트", previewUrl = url))
            assertNull(MusicPlayer.nowPlaying)
            assertNull(MusicPlayer.loadingSong)
        }
    }

    @Test fun missingClipLeavesNoPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MusicPlayer.play(context, Song(4, "없는 곡", "테스트", "테스트", "missing_clip"))
        assertNull(MusicPlayer.nowPlaying)
    }

    @Test fun selectedPlaylistTrackReallyPlaysAndEmptyPlaylistStopsIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val songs = listOf(
                    Song(4, "첫 곡", "테스트", "테스트", "gumi_ramen_1"),
                    Song(4, "선택한 곡", "테스트", "테스트", "gumi_ramen_2")
                )
                try {
                    MusicPlayer.play(activity, songs, 1)
                    assertEquals(songs[1], MusicPlayer.nowPlaying)
                    val media = MusicPlayer::class.java.getDeclaredField("player")
                        .apply { isAccessible = true }.get(MusicPlayer) as MediaPlayer
                    assertTrue(media.isPlaying)
                    MusicPlayer.play(activity, emptyList())
                    assertNull(MusicPlayer.nowPlaying)
                } finally {
                    MusicPlayer.stop()
                }
            }
        }
    }
}
