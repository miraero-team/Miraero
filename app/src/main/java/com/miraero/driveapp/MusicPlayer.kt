package com.miraero.driveapp

import android.content.Context
import android.util.Log

// 재생 연결 지점. 지금은 상태만 바꾸고 로그를 남김.
// 음악 담당: play()에 MediaPlayer(res/raw 시연 음원) + AudioFocus(내비 음성 때 소리 줄이기)를 채우면 됨
object MusicPlayer {
    var nowPlaying: Song? = null
        private set

    fun play(context: Context, song: Song) {
        nowPlaying = song
        Log.d("PLEOS_TEST", "[음악] 재생: ${song.title} - ${song.artist}")
    }

    fun stop() {
        Log.d("PLEOS_TEST", "[음악] 멈춤: ${nowPlaying?.title}")
        nowPlaying = null
    }
}
