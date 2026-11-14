package com.miraero.driveapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import androidx.core.app.NotificationManagerCompat

// 공연장 통과 하이라이트 재생. res/raw의 15초 클립을 그대로 틀어요 (미리 하이라이트만 잘라 둔 파일)
object MusicPlayer {
    var nowPlaying: Song? = null
        private set

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var am: AudioManager? = null
    private var app: Context? = null

    // 재생이 시작·끝날 때 화면을 다시 그리라고 알려줌 (MainActivity가 채움)
    var onChange: (() -> Unit)? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun play(context: Context, song: Song) {
        stop()
        val app = context.applicationContext.also { this.app = it }
        val resId = app.resources.getIdentifier(song.clip, "raw", app.packageName)
        if (resId == 0) {
            Log.d("PLEOS_TEST", "[음악] ${song.title}: 클립 없음 → 재생 안 함")
            return
        }
        // 15초만 잠깐 빌림(TRANSIENT): 라디오 같은 다른 음악은 멈췄다가 끝나면 이어짐.
        // 내비 음성이 끼어들면 시스템이 이 소리를 알아서 줄여 줌 (Android 8+ 자동 더킹)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()                         // 다른 앱이 소리를 가져감
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()      // 잠깐 양보
                    AudioManager.AUDIOFOCUS_GAIN -> player?.start()                // 돌려받음
                }
            }
            .build()
        val am = app.getSystemService(AudioManager::class.java).also { this.am = it }
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d("PLEOS_TEST", "[음악] 오디오 포커스 거절 → 재생 안 함")
            return
        }
        focus = req
        player = MediaPlayer.create(app, resId, attrs, am.generateAudioSessionId()).apply {
            setOnCompletionListener { this@MusicPlayer.stop() }   // 15초 끝. 그냥 stop()은 MediaPlayer.stop()이 불림
            start()
        }
        nowPlaying = song
        Log.d("PLEOS_TEST", "[음악] 재생: ${song.title} - ${song.artist}")
    }

    fun stop() {
        player?.release()
        player = null
        focus?.let { am?.abandonAudioFocusRequest(it) }
        focus = null
        if (nowPlaying == null) return
        Log.d("PLEOS_TEST", "[음악] 멈춤: ${nowPlaying?.title}")
        nowPlaying = null
        app?.let { NotificationManagerCompat.from(it).cancel(MainActivity.MUSIC_NOTI_ID) }
        onChange?.invoke()
    }
}
