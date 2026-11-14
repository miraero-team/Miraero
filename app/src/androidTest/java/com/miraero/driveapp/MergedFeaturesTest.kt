package com.miraero.driveapp

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class MergedFeaturesTest {
    @Test fun scoreNextScheduleAndPlaylistRemainConnected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = EventRepository.load(context).first { it.id == "jamsil_baseball" }
        assertFalse(event.liveScore.isNullOrBlank())
        assertFalse(event.nextShow.isNullOrBlank())
        val intent = Intent(context, MainActivity::class.java).putExtra("demoEvent", event.id)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            try {
                var loaded = false
                for (attempt in 0 until 100) {
                    scenario.onActivity { a ->
                        loaded = a.findViewById<TextView>(R.id.eventName).text.toString() == event.name &&
                            a.findViewById<LinearLayout>(R.id.eventSongs).childCount == event.songs.size
                    }
                    if (loaded) break
                    Thread.sleep(100)
                }
                assertTrue("행사·플레이리스트 연결 실패", loaded)
                scenario.onActivity { a ->
                    assertTrue(a.findViewById<TextView>(R.id.eventNext).text.contains(event.nextShow!!))
                    assertNotNull(a.findViewById<View>(R.id.driveDemo))
                    assertNotNull(a.findViewById<View>(R.id.festivalStatus))
                    assertNotNull(a.findViewById<View>(R.id.btnMusicStop))
                }
                context.sendBroadcast(Intent("com.miraero.driveapp.DEMO_PASS")
                    .setPackage(context.packageName).putExtra("id", event.id))
                var scoreShown = false
                for (attempt in 0 until 50) {
                    scenario.onActivity { a ->
                        scoreShown = a.findViewById<TextView>(R.id.driveScore).text.contains(event.liveScore!!)
                    }
                    if (scoreShown) break
                    Thread.sleep(100)
                }
                assertTrue("경기장 통과 스코어 연결 실패", scoreShown)
            } finally {
                scenario.onActivity {
                    MusicPlayer.stop()
                    NotificationManagerCompat.from(it).cancel(MainActivity.SCORE_NOTI_ID)
                }
            }
        }
    }
}
