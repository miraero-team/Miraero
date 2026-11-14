package com.miraero.driveapp

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Intent
import android.media.MediaPlayer
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** 실제 iTunes 네트워크·Pleos MediaPlayer 검증. 연결된 에뮬레이터에서 실행한다. */
@LargeTest
class ItunesPlaybackTest {
    @Test fun loadsIuCatalogAndPlaysPreviewThenStopsOnDrivingAndBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val intent = Intent(context, MainActivity::class.java).putExtra("demoEvent", "kspo_concert")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            fun gear(activity: MainActivity, value: VehicleGear) {
                val listener = MainActivity::class.java.getDeclaredField("gearListener")
                    .apply { isAccessible = true }.get(activity) as GearSelectionListener
                listener.onSelected(value)
            }
            fun await(message: String, condition: (MainActivity) -> Boolean) {
                val deadline = System.currentTimeMillis() + 25_000
                while (System.currentTimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity { ready = condition(it) }
                    if (ready) return
                    Thread.sleep(100)
                }
                fail(message)
            }
            try {
                scenario.onActivity { gear(it, VehicleGear.GEAR_PARK) }
                await("iTunes 음악·스토어 카드 로드 실패") { activity ->
                    activity.findViewById<LinearLayout>(R.id.eventSongs).childCount > 0 &&
                        activity.findViewById<View>(R.id.itunesStoreGroup).visibility == View.VISIBLE
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<TextView>(R.id.songsTitle).text.contains("iTunes"))
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.btnPlaylist).visibility)
                    val songs = activity.findViewById<LinearLayout>(R.id.eventSongs)
                    songs.getChildAt(songs.childCount - 1).performClick()
                }
                await("외부 AAC 미리듣기 실제 재생 실패") {
                    val media = MusicPlayer::class.java.getDeclaredField("player")
                        .apply { isAccessible = true }.get(MusicPlayer) as? MediaPlayer
                    MusicPlayer.nowPlaying?.previewUrl?.isNotBlank() == true &&
                        media?.isPlaying == true && media.currentPosition > 0
                }
                scenario.onActivity { activity ->
                    val song = MusicPlayer.nowPlaying!!
                    assertTrue(song.storeUrl.startsWith("https://music.apple.com/"))
                    gear(activity, VehicleGear.GEAR_DRIVE)
                    assertNull(MusicPlayer.nowPlaying)
                    assertNull(MusicPlayer.loadingSong)
                    gear(activity, VehicleGear.GEAR_PARK)
                    assertTrue(activity.findViewById<TextView>(R.id.itunesStoreTitle).text.contains(song.title))
                    activity.findViewById<LinearLayout>(R.id.eventSongs).getChildAt(0).performClick()
                }
                await("두 번째 미리듣기 재생 실패") { MusicPlayer.nowPlaying != null }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertNull(MusicPlayer.nowPlaying)
                assertNull(MusicPlayer.loadingSong)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { MusicPlayer.stop() }
            }
        }
    }
}
