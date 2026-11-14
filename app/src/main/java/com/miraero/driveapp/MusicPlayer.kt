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
    var loadingSong: Song? = null
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

    fun play(context: Context, song: Song) = play(context, listOf(song))

    fun play(context: Context, songs: List<Song>, index: Int = 0) {
        stop()
        val song = songs.getOrNull(index) ?: return
        val app = context.applicationContext.also { this.app = it }
        val resId = app.resources.getIdentifier(song.clip, "raw", app.packageName)
        val remote = song.previewUrl.isNotBlank()
        if (remote && runCatching {
                val uri = java.net.URI(song.previewUrl)
                !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null
            }.getOrDefault(true)) {
            Log.w("PLEOS_TEST", "[음악] 유효하지 않은 미리듣기 주소")
            return
        }
        if (!remote && resId == 0) {
            Log.d("PLEOS_TEST", "[음악] ${song.title}: 클립 없음 → 재생 안 함")
            return
        }
        // 15초만 잠깐 빌림(TRANSIENT): 라디오 같은 다른 음악은 멈췄다가 끝나면 이어짐.
        // 내비 음성이 끼어들면 시스템이 이 소리를 알아서 줄여 줌 (Android 8+ 자동 더킹)
        lateinit var req: AudioFocusRequest
        req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (focus !== req) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()                         // 다른 앱이 소리를 가져감
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (loadingSong != null) stop() else if (nowPlaying != null) player?.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> if (nowPlaying != null) player?.start()
                }
            }
            .build()
        val am = app.getSystemService(AudioManager::class.java).also { this.am = it }
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d("PLEOS_TEST", "[음악] 오디오 포커스 거절 → 재생 안 함")
            return
        }
        focus = req
        try {
            val media = if (remote) MediaPlayer() else
                MediaPlayer.create(app, resId, attrs, am.generateAudioSessionId()) ?: error("음원 파일을 열 수 없음")
            player = media
            media.setOnCompletionListener { if (player === media) play(app, songs, index + 1) }
            media.setOnErrorListener { _, _, _ -> if (player === media) stop(); true }
            fun start() {
                if (player !== media) return
                try {
                    media.start()
                    loadingSong = null
                    nowPlaying = song
                    Log.d("PLEOS_TEST", "[음악] 재생: ${song.title} - ${song.artist}")
                    onChange?.invoke()
                } catch (e: Exception) {
                    Log.w("PLEOS_TEST", "[음악] 재생 시작 실패: ${e.javaClass.simpleName}")
                    stop()
                }
            }
            if (remote) {
                loadingSong = song
                media.setAudioAttributes(attrs)
                media.setDataSource(song.previewUrl)
                media.setOnPreparedListener { start() }
                media.prepareAsync()
                onChange?.invoke()
            } else start()
        } catch (e: Exception) {
            Log.w("PLEOS_TEST", "[음악] 재생 실패: ${e.javaClass.simpleName}")
            stop()
        }
    }

    fun stop() {
        val previousPlayer = player
        player = null
        previousPlayer?.release()
        val previousFocus = focus
        focus = null
        previousFocus?.let { am?.abandonAudioFocusRequest(it) }
        if (nowPlaying == null && loadingSong == null) return
        Log.d("PLEOS_TEST", "[음악] 멈춤: ${nowPlaying?.title ?: loadingSong?.title}")
        nowPlaying = null
        loadingSong = null
        app?.let { NotificationManagerCompat.from(it).cancel(MainActivity.MUSIC_NOTI_ID) }
        onChange?.invoke()
    }
}
