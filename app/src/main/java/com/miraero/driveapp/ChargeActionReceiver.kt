package com.miraero.driveapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class ChargeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MainActivity.ACTION_CHARGE_YES -> {
                Log.d("PLEOS_TEST", "알림: 충전소 경유 선택 → 경유지 추가")
                AlertManager.onAccepted()
                MainActivity.addChargerWaypoint()
            }
            MainActivity.ACTION_CHARGE_NO -> {
                Log.d("PLEOS_TEST", "알림: 그냥 가기 선택")
                AlertManager.onDismissed()
            }
            MainActivity.ACTION_MUSIC_STOP -> {
                MusicPlayer.stop()   // 알림 지우기·화면 갱신까지 MusicPlayer가 함
                return
            }
        }
        NotificationManagerCompat.from(context).cancel(MainActivity.NOTI_ID)
    }
}