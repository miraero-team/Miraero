package com.miraero.driveapp

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.view.View
import android.widget.TextView
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var vehicle: Vehicle
    private lateinit var naviHelper: NaviHelper

    // ★ 알림 관련 이름들
    companion object {
        const val CHANNEL_ID = "stageroad_alert_v2"
        const val ACTION_CHARGE_YES = "com.miraero.driveapp.CHARGE_YES"
        const val ACTION_CHARGE_NO = "com.miraero.driveapp.CHARGE_NO"
        const val NOTI_ID = 1001

        var naviRef: NaviHelper? = null

        fun addChargerWaypoint() {
            // 시연용: 성남시청 충전소 / 실서비스: 충전소 공공 API로 경로 근처 + 행사장에서 먼 충전소
            naviRef?.addWaypoint(
                RequestWaypointInfo(
                    latitude = 37.4200,
                    longitude = 127.1267,
                    poiName = "성남시청 전기차 충전소",
                    address = "경기 성남시 중원구 성남대로 997",
                    poiId = "",
                    poiSubId = "",
                    waypointIndex = WaypointIndex.FIRST
                )
            )
        }
    }

    // ===== 내비에서 받아오는 값 =====
    private var destName = ""
    private var remainKm = 0.0
    private var remainMin = 0
    private var jamHours = 0.0

    // 행사 매칭에 쓰는 값 (내비에서 실시간으로 채워짐)
    private var events: List<EventCard> = emptyList()
    private var curLat = 0.0
    private var curLon = 0.0
    private var destLat = 0.0
    private var destLon = 0.0
    private var congestedRoads: List<String> = emptyList()

    private var routeReceived = false
    private var destReceived = false

    // ===== 화면 상태 =====
    private var parked = false                   // 기어 P
    private var cardClosed = false               // 정차 카드 [닫기] 누름
    private var shownEvent: EventCard? = null    // 경로에 걸린 행사 1등
    private var demoEvent: EventCard? = null     // 확인용으로 고정한 행사
    private var arrivePercent: Double? = null    // 도착 배터리

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            Log.d("PLEOS_TEST", "기어 = $gear")
            val nowParked = gear == VehicleGear.GEAR_PARK
            if (nowParked && !parked) cardClosed = false   // 새로 정차하면 카드 다시 보여줌
            parked = nowParked
            runOnUiThread { render() }
        }
        override fun onFailed(e: Exception) {
            Log.e("PLEOS_TEST", "기어 실패: ${e.message}")
        }
    }
    // 20초마다 다시 판단
    private val loopHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recheckLoop = object : Runnable {
        override fun run() {
            tryCheckEnergy()
            loopHandler.postDelayed(this, 20_000)
        }
    }

    private val naviListener = object : NaviHelperEventListener {

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            super.onCurrentLocationInfo(currentLocationInfo)
            curLat = currentLocationInfo.latitude
            curLon = currentLocationInfo.longitude
            Log.d("PLEOS_TEST", "현재 위치 = ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude} (${currentLocationInfo.address})")
        }

        // 경로 상태 → 정체 시간 (경로가 바뀌면 내비가 자동으로 다시 보내줌)
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            super.onRouteStateInfo(routeStateInfo)
            val summary = routeStateInfo.trafficStatus?.summary
            jamHours = (summary?.totalCongestionTime ?: 0) / 3600.0
            congestedRoads = routeStateInfo.trafficStatus?.congestionItemList
                ?.map { it.roadName }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
            Log.d("PLEOS_TEST", "모드=${routeStateInfo.naviMode}, 정체시간=${summary?.totalCongestionTime}초, 정체구간 수=${routeStateInfo.trafficStatus?.congestionItemList?.size}")
            routeReceived = true

            // ★ 경로는 생겼는데 목적지를 아직 모르면 다시 요청
            if (remainKm <= 0.0) {
                naviHelper.getDestinationInfo()
            }
            tryCheckEnergy()
        }

        // 목적지 → 남은 거리·시간
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            super.onDestinationInfo(destinationInfo)

            // 목적지 이름: 빈 값(경로 재계산 중)은 무시하고, 진짜 바뀔 때만 알림 규칙 초기화
            val newName = destinationInfo.name ?: ""
            if (newName.isNotBlank()) {
                if (newName != destName) AlertManager.reset()
                destName = newName
            }

            remainKm = destinationInfo.remainDistance / 1000.0
            remainMin = destinationInfo.remainTime / 60
            // 경로 재계산 중엔 좌표가 0으로 옴 → 무시 (목적지까지 13,183km 계산 버그)
            if (destinationInfo.latitude != 0.0) {
                destLat = destinationInfo.latitude
                destLon = destinationInfo.longitude
            }
            Log.d("PLEOS_TEST", "목적지=$destName, 남은거리=${"%.1f".format(remainKm)}km, 남은시간=${remainMin}분")
            destReceived = true
            tryCheckEnergy()
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            super.onTBTInfo(tbtInfoList)
            Log.d("PLEOS_TEST", "다음 회전 = ${tbtInfoList.firstOrNull()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        events = EventRepository.load(this)
        // ponytail: 화면 확인·촬영용. adb shell am start -S -n com.miraero.driveapp/.MainActivity --es demoEvent kspo_concert
        demoEvent = events.firstOrNull { it.id == intent.getStringExtra("demoEvent") }
        shownEvent = demoEvent
        Log.d("PLEOS_TEST", "행사 카드 ${events.size}장 로드")


        setupNotification()   // ★ 알림 채널 + 권한

        vehicle = Vehicle(this)
        vehicle.initialize()
        vehicle.getGear().registerGearSelection(gearListener)

        findViewById<View>(R.id.btnPlaylist).setOnClickListener { openPlaylist() }
        findViewById<View>(R.id.btnClose).setOnClickListener {
            cardClosed = true
            render()
        }
        render()

        naviHelper = NaviHelper(this)
        naviRef = naviHelper
        naviHelper.addListener(naviListener)
        // 내비 서비스 연결이 끝난 뒤에 요청해야 응답이 옴
        naviHelper.initialize(
            {
                Log.d("PLEOS_TEST", "내비 연결 성공")
                naviHelper.getRouteStateInfo()
                naviHelper.getDestinationInfo()
                naviHelper.getCurrentLocationInfo()
                naviHelper.getTBTInfo()
            },
            { e -> Log.e("PLEOS_TEST", "내비 연결 실패: ${e.message}") }
        )
        loopHandler.postDelayed(recheckLoop, 20_000)
    }



    // ★ 알림 채널 만들기 + 알림 권한 요청
    private fun setupNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "스테이지로드 안내", NotificationManager.IMPORTANCE_HIGH  // HIGH = 헤드업
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // 경로·목적지 둘 다 있고, 실제로 안내 중일 때 한 번만 계산
    private fun tryCheckEnergy() {
        if (!routeReceived || !destReceived) return
        if (remainKm <= 0.0) {
            Log.d("PLEOS_TEST", "안내 중인 경로 없음 → 대기")
            return
        }
        checkEnergy()
    }

    private fun checkEnergy() {
        vehicle.getEvBattery().getEvBatteryStateOfCharge(
            onComplete = { soc ->
                val socValue = soc ?: return@getEvBatteryStateOfCharge
                readAcAndDecide(socValue)
            },
            onFailed = { e -> Log.e("PLEOS_TEST", "배터리 실패: ${e.message}") }
        )
    }

    private fun readAcAndDecide(soc: Int) {
        val hvacAllSeats = SeatArea(
            SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
            SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT
        )
        vehicle.getHVAC().getHvacAcState(
            area = hvacAllSeats,
            onComplete = { ac -> decide(soc, ac == true) },
            onFailed = { e ->
                Log.e("PLEOS_TEST", "에어컨 실패: ${e.message}")
                decide(soc, false)
            }
        )
    }

    private fun decide(soc: Int, acOn: Boolean) {
        if (destLat == 0.0) return   // 목적지 좌표를 아직 모름
        val r = EnergyPlanner.check(soc, remainKm, jamHours, acOn)
        val jamMin = (jamHours * 60).toInt()

        // ★ 내 경로에 걸리는 행사 찾기
        val now = java.time.LocalDateTime.now()
        val arrival = now.plusMinutes(remainMin.toLong())
        val matches = EventMatcher.findMatches(events, curLat, curLon, destLat, destLon, congestedRoads, now, arrival)
        matches.forEach {
            Log.d("PLEOS_TEST", "행사 매칭: ${it.event.name} (점수 ${it.score}, 목적지까지 ${"%.1f".format(it.distToDestKm)}km, 정체도로 ${it.matchedRoads})")
        }
        val best = matches.firstOrNull()
        shownEvent = demoEvent ?: best?.event
        arrivePercent = r.arrivePercent
        runOnUiThread { render() }
        if (best == null) {
            Log.d("PLEOS_TEST", "경로에 걸린 행사 없음 → 알림 안 보냄")
            return
        }
        val eventPart = "오늘 ${best.event.name} 영향으로 "

        Log.d("PLEOS_TEST", "배터리=$soc%, 에어컨=$acOn, 남은거리=${"%.1f".format(remainKm)}km, 소요=${remainMin}분, 정체=${jamMin}분, 행사=${best?.event?.name ?: "없음"}, 도착시=${"%.1f".format(r.arrivePercent)}%")

        val title: String
        val text: String
        if (r.isShort) {
            title = "배터리가 부족할 수 있어요"
            text = "도착 시 배터리 ${"%.0f".format(r.arrivePercent)}% · ${destName}까지 ${remainMin}분\n" +
                    "${eventPart}약 ${jamMin}분 정체 예상. 충전소에 들를까요?"
        } else {
            title = "${destName}까지 ${remainMin}분"
            text = "${eventPart}약 ${jamMin}분 정체가 예상되지만, 도착해도 배터리 ${"%.0f".format(r.arrivePercent)}%로 충분해요."
        }

        if (AlertManager.shouldAlert(r.isShort, r.arrivePercent)) {
            runOnUiThread { showAlert(title, text, r.isShort) }
        }
    }

    // ⑤ 주행 중 상태 / ⑥ 정차 카드 중 하나만 보여줌
    private fun render() {
        val showCard = parked && !cardClosed
        findViewById<View>(R.id.driveGroup).visibility = if (showCard) View.GONE else View.VISIBLE
        findViewById<View>(R.id.parkGroup).visibility = if (showCard) View.VISIBLE else View.GONE

        val event = shownEvent
        val look = event?.let { typeLook(it.type) }

        // ⑤ 주행 중: 문장 두 줄
        val guiding = remainKm > 0.0
        text(R.id.driveTitle, if (guiding) "${destName}까지 ${remainMin}분" else "내비에서 목적지를 설정해 주세요")
        val jamMin = (jamHours * 60).toInt()
        text(R.id.driveDetail, if (!guiding) "" else listOfNotNull(
            arrivePercent?.let { "도착 시 배터리 ${"%.0f".format(it)}%" },
            event?.let { "${it.name}로 약 ${jamMin}분 정체" }
        ).joinToString("  ·  "))

        // ⑥ 정차 카드
        val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        text(R.id.eventType, look?.label ?: "")
        look?.let { findViewById<TextView>(R.id.eventType).setTextColor(it.color) }
        text(R.id.eventName, event?.name ?: "오늘 경로 근처에는 행사가 없어요")
        text(R.id.eventTime, if (event == null || look == null) "" else {
            val time = if (look.startOnly) "${event.start.format(hhmm)} ${look.timeLabel}"
                       else "${event.start.format(hhmm)}–${event.end.format(hhmm)} ${look.timeLabel}"
            "$time  ·  ${event.venue}"
        })
        text(R.id.eventCrowd, when (event?.crowd) {
            "HIGH" -> "주변 혼잡 예상: 높음"; "MEDIUM" -> "주변 혼잡 예상: 보통"; "LOW" -> "주변 혼잡 예상: 낮음"; else -> ""
        })
        // 곡 제목이 비어 있으면 목록을 숨김 (음악 담당이 events.json을 채우면 보임)
        val songs = event?.songs?.filter { it.title.isNotBlank() }?.sortedBy { it.priority }.orEmpty()
        findViewById<View>(R.id.songsGroup).visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        text(R.id.songsTitle, look?.songsTitle ?: "")
        text(R.id.eventSongs, songs.joinToString("\n") { "${it.title}  —  ${it.artist}" })
        findViewById<TextView>(R.id.btnPlaylist).text = look?.button ?: ""
        findViewById<View>(R.id.btnPlaylist).visibility = if (event == null) View.GONE else View.VISIBLE
    }

    // 행사 종류별 표시 (events.json의 type)
    private data class TypeLook(
        val label: String, val color: Int,
        val timeLabel: String, val startOnly: Boolean,   // 시작 시각만 보여줄지
        val songsTitle: String, val button: String
    )

    private fun typeLook(type: String) = when (type) {
        // 밝은 배경에서 읽히도록 진한 색
        "CONCERT" -> TypeLook("공연", 0xFFB4436C.toInt(), "시작", true, "아티스트 대표곡", "대표곡 듣기")
        "BASEBALL" -> TypeLook("프로야구", 0xFF2E7D4F.toInt(), "경기 시작", true, "응원가", "응원가 듣기")
        "FESTIVAL" -> TypeLook("축제", 0xFFB26A00.toInt(), "운영", false, "축제 음악", "축제 음악 듣기")
        "EXPO" -> TypeLook("박람회", 0xFF1F6FA8.toInt(), "관람", false, "가는 길에 들을 음악", "음악 듣기")
        else -> TypeLook("행사", 0xFFB26A00.toInt(), "", false, "추천 음악", "음악 듣기")
    }

    // 빈 글자면 칸 자체를 숨김
    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).apply {
            text = value
            visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        }
    }

    // [플레이리스트 열기]: 음악 담당이 채울 자리 (기획서: 음원 플랫폼 연동)
    private fun openPlaylist() {
        Log.d("PLEOS_TEST", "플레이리스트 열기: ${shownEvent?.name}")
    }

    // ★ 헤드업 알림 띄우기 (내비 위에도 뜸)
    private fun showAlert(title: String, text: String, askCharge: Boolean) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)   // ★ 내비 관련 알림
            .setDefaults(NotificationCompat.DEFAULT_ALL)            // ★ 소리로 주의 끌기
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)

        if (askCharge) {
            builder.addAction(0, "충전소 들르기", actionIntent(ACTION_CHARGE_YES, 1))
            builder.addAction(0, "그냥 가기", actionIntent(ACTION_CHARGE_NO, 2))
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(NOTI_ID, builder.build())
            Log.d("PLEOS_TEST", "알림 표시: $title")
        } else {
            Log.e("PLEOS_TEST", "알림 권한 없음")
        }
    }

    // 알림 버튼 → MainActivity로 신호 보내기
    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ChargeActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(          // getActivity → getBroadcast
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        loopHandler.removeCallbacks(recheckLoop)
        vehicle.getGear().unregisterGearSelection(gearListener)
        naviHelper.removeListener(naviListener)
        naviRef = null
        naviHelper.release()
        vehicle.release()
        super.onDestroy()
    }
}