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
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
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
import android.os.Looper
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var vehicle: Vehicle
    private lateinit var naviHelper: NaviHelper

    // ★ 알림 관련 이름들
    companion object {
        const val CHANNEL_ID = "stageroad_alert_v2"
        const val ACTION_CHARGE_YES = "com.miraero.driveapp.CHARGE_YES"
        const val ACTION_CHARGE_NO = "com.miraero.driveapp.CHARGE_NO"
        const val NOTI_ID = 1001
        const val MUSIC_CHANNEL_ID = "stageroad_music_v2"   // 채널 설정은 처음 만들 때 고정 → 바꾸면 이름도 바꿈
        const val ACTION_MUSIC_STOP = "com.miraero.driveapp.MUSIC_STOP"
        const val MUSIC_NOTI_ID = 1002
        // 통과 몇 분 전에 재생을 시작할지. 클립이 15초라 7.5초 전에 시작 → 하이라이트 가운데가 공연장 앞
        const val CLIP_SEC = 15.0
        const val HIGHLIGHT_LEAD_MIN = CLIP_SEC / 2 / 60   // 0.125분
        const val SCORE_NOTI_ID = 1003

        // 시연 주행 (디버그 빌드에서만). 에뮬레이터 내비 위치가 안 움직여서 차 속도로 남은 거리를 줄임
        // adb shell am broadcast -a com.miraero.driveapp.DEMO_DRIVE --es id kspo_concert --ef km 1.2
        //   (--ef kmh 50 을 붙이면 차량 속도 대신 고정 속도. 생략하면 Virtual Console 속도를 씀)
        // adb shell am broadcast -a com.miraero.driveapp.DEMO_DRIVE_STOP
        const val ACTION_DEMO_DRIVE = "com.miraero.driveapp.DEMO_DRIVE"
        const val ACTION_DEMO_DRIVE_STOP = "com.miraero.driveapp.DEMO_DRIVE_STOP"
        const val DEMO_DEFAULT_KM = 1.2

        var naviRef: NaviHelper? = null

        fun addChargerWaypoint() {
            // 충전소는 DataHub가 골라줌 (API 있으면 실데이터, 없으면 성남시청 샘플)
            DataHub.findCharger { c ->
                naviRef?.addWaypoint(
                    RequestWaypointInfo(
                        latitude = c.lat,
                        longitude = c.lon,
                        poiName = c.name,
                        address = c.address,
                        poiId = "",
                        poiSubId = "",
                        waypointIndex = WaypointIndex.FIRST
                    )
                )
            }
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
    private var curAddress = ""
    private var destLat = 0.0
    private var destLon = 0.0
    private var congestedRoads: List<String> = emptyList()

    private var routeReceived = false
    private var destReceived = false
    // 경로 재계산 중 남은거리 0이 오면 3초마다 다시 물어봄 (최대 10번)
    private val retryHandler by lazy { Handler(mainLooper) }
    private var destRetries = 0
    private val retryDest = Runnable {
        if (remainKm <= 0.0 && destRetries < 10) {
            destRetries++
            Log.d("PLEOS_TEST", "남은거리 0 → 목적지 다시 요청 ($destRetries)")
            naviHelper.getDestinationInfo()
        }
    }

    // ===== 화면 상태 =====
    private var parked = false                   // 기어 P
    private var cardClosed = false               // 정차 카드 [닫기] 누름
    private var shownEvent: EventCard? = null    // 경로에 걸린 행사 1등
    private lateinit var playlistRepository: PlaylistRepository
    private var playlistEventId: String? = null
    private var playlistSongs: List<Song> = emptyList()
    private var playlistStatus = ""
    private var playlistRequest = 0
    private var demoEvent: EventCard? = null     // 확인용으로 고정한 행사
    private var cardEvent: EventCard? = null     // 지금 정차 카드에 보이는 행사 (길 안내·저장 버튼용)
    private val savedEventIds = mutableSetOf<String>()   // 북마크·나중에 듣기 (앱 실행 중에만 기억)
    private var songRowsKey = ""
    // 주행 화면 위 안내 배너 (마지막 헤드업 알림 내용)
    private var bannerTitle: String? = null
    private var bannerText = ""
    private var bannerShort = false
    private var bannerDismissed = false
    private var driveEvent: EventCard? = null    // 주행 화면 버튼이 쓰는 행사                 // 곡 목록·재생 상태가 같으면 다시 안 그림 (애니메이션 끊김 방지)
    private var arrivePercent: Double? = null    // 도착 배터리
    private var festivals: List<EventCard> = emptyList()
    private var festivalDate: LocalDate? = null
    private var festivalLoading = false
    private var festivalStatus = "축제 정보를 불러오는 중이에요"
    private var lastFestivalScreenLog = ""
    private val festivalExecutor = Executors.newSingleThreadExecutor()

    override fun onResume() {
        super.onResume()
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        if (festivalLoading || festivalDate == today) return
        if (BuildConfig.FESTIVAL_API_KEY.isBlank()) {
            festivalStatus = "축제 API 키를 설정하면 주변 축제를 볼 수 있어요"
            render()
            return
        }
        festivalLoading = true
        festivalStatus = "축제 정보를 불러오는 중이에요"
        render()
        festivalExecutor.execute {
            val result = runCatching { FestivalRepository.fetch(BuildConfig.FESTIVAL_API_KEY, today) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                festivalLoading = false
                result.onSuccess {
                    festivals = it
                    festivalDate = today
                    festivalStatus = ""
                    if (BuildConfig.DEBUG) Log.d("STAGEROAD_FESTIVAL", "조회 완료: 기준일=$today, 오늘 축제=${it.size}개")
                }.onFailure {
                    festivalStatus = "축제 정보를 불러오지 못했어요. 앱을 다시 열면 재시도해요"
                    Log.w("STAGEROAD", "축제 조회 실패: ${it.javaClass.simpleName}")
                }
                render()
            }
        }
    }

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
    // 배터리·에어컨: 바뀔 때마다 차가 알려줌 (20초 반복 대신)
    private var soc: Int? = null
    private var acOn = false
    private val hvacAllSeats = SeatArea(   // 이 에뮬레이터는 공조를 차 전체 한 구역으로 관리
        SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
        SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT
    )
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            if (value == null || value == soc) return
            soc = value
            tryCheckEnergy()
        }
        override fun onFailed(e: Exception) { Log.e("PLEOS_TEST", "배터리 실패: ${e.message}") }
    }
    private val acListener = object : HvacAcStateListener {
        override fun onHvacAcStateUpdated(value: Boolean?) {
            if ((value == true) == acOn) return
            acOn = value == true
            tryCheckEnergy()
        }
        override fun onFailed(e: Exception) { Log.e("PLEOS_TEST", "에어컨 실패: ${e.message}") }
    }

    // ===== 시연 주행 · 경기 스코어 =====
    private val uiHandler = Handler(Looper.getMainLooper())
    private var scoreLine: String? = null          // 경기장 앞을 지날 때 한 줄
    private val clearScore = Runnable { scoreLine = null; render() }
    private val demoDrive = DemoDrive(
        leadMin = HIGHLIGHT_LEAD_MIN,
        onChange = { runOnUiThread { render() } },
        onPass = { passVenue(it) }
    )

    // 차 속도: Pleos Vehicle SDK에 속도 API가 없어서 Android Car API로 읽음 (Virtual Console 속도가 들어옴)
    private var car: Car? = null
    private var carProps: CarPropertyManager? = null
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            val kmh = mps * 3.6
            if ((demoDrive.carSpeedKmh ?: -1.0).toInt() != kmh.toInt()) Log.d("PLEOS_TEST", "차 속도 = ${kmh.toInt()}km/h")
            demoDrive.carSpeedKmh = kmh
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.e("PLEOS_TEST", "차 속도 읽기 실패")
        }
    }

    private fun startSpeed() {
        if (carProps != null) return
        if (checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            Log.d("PLEOS_TEST", "차 속도 권한 없음 → 시연 주행은 --ef kmh 로 속도 지정")
            return
        }
        try {
            val c = Car.createCar(this) ?: return
            val props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            car = c; carProps = props
            Log.d("PLEOS_TEST", "차 속도 연결")
        } catch (e: Throwable) {
            Log.e("PLEOS_TEST", "차 속도 연결 실패: ${e.message}")
        }
    }

    private val naviListener = object : NaviHelperEventListener {

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            super.onCurrentLocationInfo(currentLocationInfo)
            curLat = currentLocationInfo.latitude
            curLon = currentLocationInfo.longitude
            DataHub.lastLat = curLat
            DataHub.lastLon = curLon
            DataHub.warmUpChargers()
            curAddress = currentLocationInfo.address.orEmpty()
            Log.d("PLEOS_TEST", "현재 위치 = ${currentLocationInfo.latitude}, ${currentLocationInfo.longitude} (${currentLocationInfo.address})")
            tryCheckEnergy()   // 위치가 바뀌면 행사장 통과 시각도 다시 계산
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
                DataHub.destLat = destLat
                DataHub.destLon = destLon
            }
            Log.d("PLEOS_TEST", "목적지=$destName, 남은거리=${"%.1f".format(remainKm)}km, 남은시간=${remainMin}분")
            // 0km면 3초 뒤 다시 요청, 제대로 오면 횟수 초기화
            retryHandler.removeCallbacks(retryDest)
            if (remainKm <= 0.0) retryHandler.postDelayed(retryDest, 3000) else destRetries = 0
            destReceived = true
            tryCheckEnergy()
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            super.onTBTInfo(tbtInfoList)
            Log.d("PLEOS_TEST", "다음 회전 = ${tbtInfoList.firstOrNull()}")
            // 회전 안내가 다시 오면 경로가 살아난 것 → 목적지 다시 확인
            if (remainKm <= 0.0 && tbtInfoList.isNotEmpty()) {
                destRetries = 0
                retryHandler.removeCallbacks(retryDest)
                retryHandler.post(retryDest)
            }
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

        playlistRepository = LocalPlaylistRepository(EventRepository.load(this))
        // 행사 카드: DataHub가 API → 실패하면 샘플 순서로 가져옴
        DataHub.loadEvents(this) { list ->
            events = list
            DataHub.chargerSource = EvChargerApiSource   // 충전소: 공공데이터 API (실패하면 샘플)
            // 화면 확인·촬영용. adb shell am start -S -n com.miraero.driveapp/.MainActivity --es demoEvent kspo_concert
            demoEvent = events.firstOrNull { it.id == intent.getStringExtra("demoEvent") }
            if (demoEvent != null) shownEvent = demoEvent
            Log.d("PLEOS_TEST", "행사 카드 ${events.size}장 로드")
            render()
            tryCheckEnergy()
        }


        setupNotification()   // ★ 알림 채널 + 권한

        vehicle = Vehicle(this)
        vehicle.initialize()
        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getGear().getCurrentGearSelectionState(
            onComplete = { gearListener.onSelected(it) },
            onFailed = { gearListener.onFailed(it) }
        )
        if (isDebuggable) androidx.core.content.ContextCompat.registerReceiver(
            this, demoPassReceiver,
            android.content.IntentFilter("com.miraero.driveapp.DEMO_PASS").apply {
                addAction(ACTION_DEMO_DRIVE); addAction(ACTION_DEMO_DRIVE_STOP)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener)
        vehicle.getHVAC().registerHvacAcState(hvacAllSeats, acListener)
        // 등록 직후 첫 값은 한 번 직접 읽어 둠
        vehicle.getEvBattery().getEvBatteryStateOfCharge(
            onComplete = { socListener.onEvBatteryStateOfChargeUpdated(it) },
            onFailed = { socListener.onFailed(it) }
        )
        vehicle.getHVAC().getHvacAcState(
            area = hvacAllSeats,
            onComplete = { acListener.onHvacAcStateUpdated(it) },
            onFailed = { acListener.onFailed(it) }
        )

        MusicPlayer.onChange = { runOnUiThread { render() } }
        findViewById<View>(R.id.btnPlaylist).setOnClickListener { openPlaylist() }
        // 시연: 주행 화면 제목을 길게 누르면 지금 경로의 행사(없으면 첫 행사)로 시연 주행 시작/중지 (디버그 빌드만)
        if (isDebuggable) findViewById<View>(R.id.driveTitle).setOnLongClickListener {
            if (demoDrive.running) demoDrive.stop()
            else (shownEvent ?: events.firstOrNull())?.let { demoDrive.start(it, DEMO_DEFAULT_KM, null) }
            true
        }
        startSpeed()
        findViewById<View>(R.id.btnMusicStop).setOnClickListener { MusicPlayer.stop() }
        findViewById<View>(R.id.btnBookmark).setOnClickListener { toggleSaved() }
        findViewById<View>(R.id.btnLater).setOnClickListener { toggleSaved() }
        findViewById<View>(R.id.btnRoute).setOnClickListener { cardEvent?.let { routeTo(it) } }
        findViewById<View>(R.id.driveBannerClose).setOnClickListener { bannerDismissed = true; render() }
        findViewById<View>(R.id.btnDriveInfo).setOnClickListener { cardClosed = false; render() }
        findViewById<View>(R.id.btnDrivePrimary).setOnClickListener {
            val e = driveEvent
            if (remainKm <= 0.0 && e != null) routeTo(e)   // 안내 중이 아니면: 행사장으로 길 안내
            else { cardClosed = false; render() }          // 안내 중이면: 행사 카드 다시 열기
        }
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
    }



    // ★ 알림 채널 만들기 + 알림 권한 요청
    private fun setupNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "스테이지로드 안내", NotificationManager.IMPORTANCE_HIGH  // HIGH = 헤드업
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MUSIC_CHANNEL_ID, "공연장 통과 음악", NotificationManager.IMPORTANCE_DEFAULT)  // 헤드업은 HIGH만. DEFAULT는 목록에만 (LOW는 Pleos 알림 목록에서 숨겨짐)
        )

        // 알림 + 차 속도(시연 주행용) 권한을 한 번에 요청
        val need = listOfNotNull(
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 },
            Car.PERMISSION_SPEED
        ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startSpeed()   // 속도 권한을 방금 받았으면 연결
    }

    // 경로·목적지 둘 다 있고, 실제로 안내 중일 때 한 번만 계산
    private fun tryCheckEnergy() {
        runOnUiThread { render() } // 목적지·배터리가 없어도 현재 위치 주변 축제는 표시.
        if (!routeReceived || !destReceived) return
        if (remainKm <= 0.0) {
            Log.d("PLEOS_TEST", "안내 중인 경로 없음 → 대기")
            return
        }
        val s = soc ?: return   // 배터리 값을 아직 모름
        decide(s, acOn)
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
        checkPassing(matches)
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

    // 메인2: 행사장 앞 통과 직전에 곡 재생 + 일반 알림(헤드업 아님: 음악은 운전과 무관)
    private var playedEventId: String? = null

    private fun checkPassing(matches: List<EventMatcher.Match>) {
        val m = matches.filter { it.event.songs.any { s -> s.title.isNotBlank() } }
            .minByOrNull { it.distToNowKm } ?: return
        val min = EventMatcher.minutesToPass(m.distToNowKm, remainKm, remainMin) ?: return
        Log.d("PLEOS_TEST", "${m.event.venue} 통과까지 ${"%.1f".format(min)}분")
        if (min > HIGHLIGHT_LEAD_MIN || m.event.id == playedEventId) return

        passVenue(m.event)
    }

    // 내비 콜백 스레드에서도 불리므로 재생·화면은 메인 스레드에서 (MediaPlayer는 메인에서 다루는 게 안전)
    private fun passVenue(event: EventCard) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { passVenue(event) }; return }
        // 경기장: 진행 중인 경기 스코어 한 줄 + 응원가 (클립이 있으면)
        if (event.type == "BASEBALL" && event.liveScore != null) {
            playedEventId = event.id
            showScore(event)
        }
        val song = event.songs.filter { it.title.isNotBlank() }.minByOrNull { it.priority } ?: return
        playedEventId = event.id
        MusicPlayer.play(this, song)
        if (MusicPlayer.nowPlaying == null) return   // 클립이 없거나 오디오 포커스를 못 받음
        showMusicNotice(event, song)
        render()
    }

    // 시연용: 에뮬레이터 내비는 위치가 움직이지 않아 통과 장면을 명령으로 바로 일으킴 (음악·스코어가 화면에 뜸)
    // adb shell am broadcast -a com.miraero.driveapp.DEMO_PASS --es id setec_expo
    // 디버그 빌드에서만 등록 (출시본에선 다른 앱이 보낼 수 없게)
    private val demoPassReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == ACTION_DEMO_DRIVE_STOP) { demoDrive.stop(); return }
            val event = events.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
            if (intent.action == ACTION_DEMO_DRIVE) {
                val km = intent.getFloatExtra("km", DEMO_DEFAULT_KM.toFloat()).toDouble()
                val kmh = if (intent.hasExtra("kmh")) intent.getFloatExtra("kmh", 0f).toDouble() else null
                demoDrive.start(event, km, kmh)
                return
            }
            Log.d("PLEOS_TEST", "[시연] ${event.venue} 통과")
            passVenue(event)
        }
    }
    private val isDebuggable get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun showScore(event: EventCard) {
        scoreLine = "지나가는 ${event.venue}  ·  지금 ${event.liveScore}"
        uiHandler.removeCallbacks(clearScore)
        uiHandler.postDelayed(clearScore, 30_000)   // 30초 보여 주고 지움
        render()
        val notice = NotificationCompat.Builder(this, MUSIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("지나가는 ${event.venue}")
            .setContentText("${event.name} · 지금 ${event.liveScore}")
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) NotificationManagerCompat.from(this).notify(SCORE_NOTI_ID, notice)
        Log.d("PLEOS_TEST", "경기 스코어: ${event.venue} ${event.liveScore}")
    }

    private fun showMusicNotice(event: EventCard, song: Song) {
        val notice = NotificationCompat.Builder(this, MUSIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("곧 지나는 ${event.venue}에서 오늘 ${event.name}")
            .setContentText("${song.title} - ${song.artist}")
            .setSilent(true)   // 음악이 나오는 중이라 알림음 없음
            .addAction(0, "멈춤", actionIntent(ACTION_MUSIC_STOP, 3))
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) NotificationManagerCompat.from(this).notify(MUSIC_NOTI_ID, notice)
    }

    // ⑤ 주행 중 상태 / ⑥ 정차 카드 중 하나만 보여줌
    private fun render() {
        val showCard = parked && !cardClosed
        findViewById<View>(R.id.driveGroup).visibility = if (showCard) View.GONE else View.VISIBLE
        findViewById<View>(R.id.parkGroup).visibility = if (showCard) View.VISIBLE else View.GONE

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val useDestination = remainKm > 0.0 && destLat != 0.0
        val lat = if (useDestination) destLat else curLat
        val lon = if (useDestination) destLon else curLon
        val area = if (useDestination) "목적지" else "현재 위치"
        val searchName = if (useDestination) destName.ifBlank { "목적지" }
                         else curAddress.ifBlank { "현재 위치(주소 확인 중)" }
        val nearby = EventMatcher.nearbyFestivals(festivals, lat, lon, today)
        val event = demoEvent ?: nearby.firstOrNull() ?: shownEvent
        loadPlaylist(event)
        val look = event?.let { typeLook(it.type) }

        // ⑤ 주행 중: 문장 두 줄
        val guiding = remainKm > 0.0
        text(R.id.driveTitle, if (guiding) destName.ifBlank { "목적지" } else "내비에서 목적지를 설정해 주세요")
        val jamMin = (jamHours * 60).toInt()
        renderDrive(guiding, event, jamMin)
        text(R.id.driveNowPlaying, MusicPlayer.nowPlaying?.let { "지금 재생  ${it.title} — ${it.artist}" } ?: "")
        text(R.id.driveDemo, demoDrive.event?.let { "${it.venue}까지 ${"%.1f".format(demoDrive.remainKm)}km" } ?: "")
        text(R.id.driveScore, scoreLine ?: "")

        // ⑥ 정차 카드
        val hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        text(R.id.eventType, look?.label ?: "")
        text(R.id.eventName, event?.name ?: "주변 행사 안내")
        // 검색 상태 안내는 보여 줄 행사가 없을 때만 (카드 글자 줄이기)
        text(R.id.festivalStatus, if (event != null) "" else if (festivalStatus.isNotBlank()) festivalStatus
            else if (lat == 0.0 && lon == 0.0) "위치를 받으면 반경 2km의 축제를 찾아요"
            else if (nearby.isEmpty()) "검색 위치: $searchName\n오늘 $area 반경 2km에 등록된 축제가 없어요"
            else "검색 위치: $searchName\n오늘 $area 반경 2km · 축제 ${nearby.size}개 중 가장 가까운 축제")
        val md = java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd(E)", java.util.Locale.KOREAN)
        text(R.id.eventTime, if (event == null || look == null) "" else when {
            event.dateOnly -> if (event.start.toLocalDate() == event.end.toLocalDate()) event.start.format(md)
                              else "${event.start.format(md)} – ${event.end.format(md)}"
            look.startOnly -> "${event.start.format(hhmm)} ${look.timeLabel}"
            else -> "${event.start.format(hhmm)}–${event.end.format(hhmm)} ${look.timeLabel}"
        })
        text(R.id.eventVenue, event?.venue.orEmpty())
        findViewById<View>(R.id.eventMetaDivider).visibility = if (event == null) View.GONE else View.VISIBLE
        renderCardExtras(event, look)
        text(R.id.eventSummary, if (event?.dateOnly == true) event.summary else "")   // 샘플 행사 설명은 제목 반복이라 숨김, 실제 축제 설명만 표시
        text(R.id.eventCrowd, when (event?.crowd) {
            "HIGH" -> "혼잡 높음"; "MEDIUM" -> "혼잡 보통"; "LOW" -> "혼잡 낮음"; else -> ""
        })
        text(R.id.eventNext, event?.let { e ->
            e.nextShow?.let { "${if (e.type == "BASEBALL") "다음 경기" else "다음 일정"}  $it" }
        } ?: "")
        // 곡 제목이 비어 있으면 목록을 숨김 (음악 담당이 events.json을 채우면 보임)
        val songs = playlistSongs
        findViewById<View>(R.id.songsGroup).visibility = if (songs.isEmpty() && playlistStatus.isBlank()) View.GONE else View.VISIBLE
        val songsLabel = if (event?.dateOnly == true) {
            if (songs.any { it.previewUrl.isNotBlank() }) "축제 분위기 플레이리스트 · 미리듣기"
            else "축제 분위기 플레이리스트 · 15초 미리듣기"
        } else look?.songsTitle ?: ""
        text(R.id.songsTitle, if (songs.isEmpty() || songsLabel.isBlank()) songsLabel else "$songsLabel  (${songs.size}곡)")
        text(R.id.playlistStatus, playlistStatus)
        renderSongRows(songs)
        val playing = MusicPlayer.nowPlaying != null || MusicPlayer.loadingSong != null
        findViewById<View>(R.id.parkNowPlaying).visibility = View.GONE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPlaylist).apply {
            text = if (playing) "재생 멈추기" else "플레이리스트 재생"
            setIconResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
            visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.btnLater).visibility = if (event == null) View.GONE else View.VISIBLE
        if (BuildConfig.DEBUG) {
            val state = "화면=${if (showCard) "정차 상세" else "주행 간단"}, 기준=$area, 검색위치=$searchName, 반경=2km, " +
                "주변=${nearby.size}개, 표시=${event?.name ?: "없음"}, 실데이터=${event?.dateOnly == true}, 상태=$festivalStatus"
            if (state != lastFestivalScreenLog) {
                Log.d("STAGEROAD_FESTIVAL", state)
                lastFestivalScreenLog = state
            }
        }
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
            if (id == R.id.eventSummary) {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            } else if (id != R.id.festivalStatus) {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            text = value
            visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        }
    }

    // [플레이리스트 열기]: 음악 담당이 채울 자리 (기획서: 음원 플랫폼 연동)
    private fun openPlaylist() {
        if (MusicPlayer.nowPlaying != null || MusicPlayer.loadingSong != null) { MusicPlayer.stop(); return }
        if (parked) MusicPlayer.play(this, playlistSongs)
    }

    // ===== 정차 카드: 이미지 타일 · 거리 · 저장 =====
    private fun renderCardExtras(event: EventCard?, look: TypeLook?) {
        cardEvent = event
        findViewById<View>(R.id.eventImage).visibility = if (event == null) View.GONE else View.VISIBLE
        findViewById<android.widget.ImageView>(R.id.eventImageIcon).setImageResource(
            if (event?.type == "FESTIVAL" || event?.type == "EXPO") R.drawable.ic_star else R.drawable.ic_music_note
        )
        findViewById<TextView>(R.id.eventImageLabel).text = ""   // 종류는 위 칩에 이미 있음

        // 현재 위치 → 행사장 직선거리. 소요 시간은 도심 평균 25km/h로 어림
        val row = findViewById<View>(R.id.distanceRow)
        if (event == null || (curLat == 0.0 && curLon == 0.0)) {
            row.visibility = View.GONE
        } else {
            val km = Geo.distanceKm(curLat, curLon, event.lat, event.lon)
            val min = kotlin.math.max(1, kotlin.math.ceil(km / 25.0 * 60).toInt())
            findViewById<TextView>(R.id.distanceText).text =
                "현재 위치에서 ${"%.1f".format(km)} km   |   약 ${min}분 소요"
            row.visibility = View.VISIBLE
        }

        val saved = event != null && event.id in savedEventIds
        findViewById<android.widget.ImageButton>(R.id.btnBookmark).apply {
            setImageResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (saved) R.color.stage_blue else R.color.stage_text))
            visibility = if (event == null) View.INVISIBLE else View.VISIBLE
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnLater).apply {
            text = if (saved) "저장했어요" else "나중에 듣기"
            setIconResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            val c = getColor(if (saved) R.color.stage_blue_dark else R.color.stage_text)
            setTextColor(c); iconTint = android.content.res.ColorStateList.valueOf(c)
        }
    }

    private fun toggleSaved() {
        val id = cardEvent?.id ?: return
        if (!savedEventIds.add(id)) savedEventIds.remove(id)
        Log.d("PLEOS_TEST", "저장 ${if (id in savedEventIds) "추가" else "취소"}: ${cardEvent?.name}")
        render()
    }

    // [길 안내]: 행사장을 내비 목적지로 설정
    private fun routeTo(e: EventCard) {
        naviHelper.requestRoute(
            RouteInfo(
                latitude = e.lat,
                longitude = e.lon,
                poiId = "",
                poiName = e.name,
                poiSubId = "0",
                address = e.venue
            )
        )
        Log.d("PLEOS_TEST", "길 안내 요청: ${e.name} (${e.lat}, ${e.lon})")
    }

    // ===== 주행 화면: 배너 · 남은 시간 · 행사 타일 · 정차 중 버튼 =====
    private fun renderDrive(guiding: Boolean, event: EventCard?, jamMin: Int) {
        driveEvent = event
        // 남은 시간: 숫자 크게, "분"은 작게
        findViewById<TextView>(R.id.driveMinutes).apply {
            if (guiding) {
                val sp = android.text.SpannableString("${remainMin}분")
                sp.setSpan(android.text.style.RelativeSizeSpan(0.55f), sp.length - 1, sp.length, 0)
                text = sp; visibility = View.VISIBLE
            } else visibility = View.GONE
        }
        text(R.id.driveKm, if (guiding) "·  ${"%.1f".format(remainKm)} km" else "")
        val battery = if (guiding) arrivePercent?.let { "도착 시 배터리 ${"%.0f".format(it)}%" }.orEmpty() else ""
        val jam = if (!guiding || event == null) "" else
            if (event.dateOnly) "주변 축제: ${event.name}" else "${event.name}로 약 ${jamMin}분 정체"
        text(R.id.driveBattery, battery)
        text(R.id.driveJam, jam)
        findViewById<View>(R.id.driveMetaDivider).visibility = if (battery.isNotBlank() && jam.isNotBlank()) View.VISIBLE else View.GONE
        text(R.id.driveDetail, "")

        // 오른쪽 타일: 행사 종류 아이콘 + 행사명
        findViewById<View>(R.id.driveHero).visibility = if (event == null) View.GONE else View.VISIBLE
        findViewById<android.widget.ImageView>(R.id.driveHeroIcon).setImageResource(
            if (event?.type == "FESTIVAL" || event?.type == "EXPO") R.drawable.ic_star else R.drawable.ic_music_note
        )
        findViewById<TextView>(R.id.driveHeroLabel).text = event?.name.orEmpty()

        // 배너: 마지막 안내. 배터리 부족이면 주황
        val showBanner = bannerTitle != null && !bannerDismissed && guiding
        findViewById<View>(R.id.driveBanner).apply {
            visibility = if (showBanner) View.VISIBLE else View.GONE
            setBackgroundResource(if (bannerShort) R.drawable.bg_banner_warn else R.drawable.bg_info_strip)
        }
        findViewById<android.widget.ImageView>(R.id.driveBannerIcon).imageTintList =
            android.content.res.ColorStateList.valueOf(if (bannerShort) 0xFFE8590C.toInt() else getColor(R.color.stage_blue))
        findViewById<TextView>(R.id.driveBannerTitle).text = bannerTitle.orEmpty()
        findViewById<TextView>(R.id.driveBannerText).apply {
            text = bannerText.replace("\n", "  ")
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // 버튼은 정차(P) 중에만 (주행 중엔 화면 조작 최소화)
        findViewById<View>(R.id.driveActions).visibility = if (parked && event != null) View.VISIBLE else View.GONE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDrivePrimary).apply {
            text = if (!guiding) "경로 안내 시작" else "행사 정보 보기"
            setIconResource(if (!guiding) R.drawable.ic_navigation else R.drawable.ic_star)
        }
        findViewById<View>(R.id.btnDriveInfo).visibility = if (guiding) View.GONE else View.VISIBLE
        findViewById<View>(R.id.driveHint).visibility = if (parked) View.GONE else View.VISIBLE
    }

    // ===== 곡 목록: 둥근 행 + 썸네일 + ▶ / 재생 중 이퀄라이저 =====
    private fun renderSongRows(songs: List<Song>) {
        val playing = MusicPlayer.nowPlaying
        val loading = MusicPlayer.loadingSong
        val key = songs.joinToString("|") { it.title + it.clip + it.previewUrl } + "#" + playing?.title + "#" + loading?.title
        if (key == songRowsKey) return
        songRowsKey = key

        val list = findViewById<android.widget.LinearLayout>(R.id.eventSongs)
        list.removeAllViews()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val thumbColors = intArrayOf(0xFF2563EB.toInt(), 0xFF3B82F6.toInt(), 0xFF1D4ED8.toInt(), 0xFF60A5FA.toInt())

        songs.forEachIndexed { index, song ->
            val isPlaying = playing == song
            val isLoading = loading == song
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(if (isPlaying || isLoading) R.drawable.bg_song_row_playing else R.drawable.bg_song_row)
                setPadding(px(14), px(12), px(20), px(12))
                minimumHeight = px(84)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = px(12) }
                isClickable = true
                contentDescription = "${song.title}, ${song.artist}, ${if (isPlaying) "재생 중, 누르면 멈춤" else "재생"}"
                setOnClickListener {
                    if (isPlaying || isLoading) MusicPlayer.stop()
                    else if (parked) MusicPlayer.play(this@MainActivity, songs, index)
                }
            }
            // 썸네일: 실제 앨범 커버 대신 파란 타일 + 음표 (저작권)
            row.addView(android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(px(60), px(60))
                background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_thumb)?.mutate()
                backgroundTintList = android.content.res.ColorStateList.valueOf(thumbColors[index % thumbColors.size])
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_music_note)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    layoutParams = android.widget.FrameLayout.LayoutParams(px(32), px(32), android.view.Gravity.CENTER)
                })
            })
            row.addView(TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = px(20) }
                text = "${song.title}  –  ${song.artist}"
                textSize = 24f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(if (isPlaying) R.color.stage_blue_dark else R.color.stage_text))
                if (isPlaying) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            // 오른쪽: 재생 중 → 이퀄라이저 / 준비 중 → 로딩 / 평소 → ▶
            row.addView(when {
                isPlaying -> EqualizerView(this, getColor(R.color.stage_blue)).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(px(34), px(30))
                }
                isLoading -> android.widget.ProgressBar(this).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.stage_blue))
                    layoutParams = android.widget.LinearLayout.LayoutParams(px(34), px(34))
                }
                else -> android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_play)
                    imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.stage_text))
                    layoutParams = android.widget.LinearLayout.LayoutParams(px(36), px(36))
                }
            })
            list.addView(row)
        }
    }

    private fun loadPlaylist(event: EventCard?) {
        if (isDestroyed || isFinishing || festivalExecutor.isShutdown) return
        if (event?.id == playlistEventId || !::playlistRepository.isInitialized) return
        playlistEventId = event?.id
        val request = ++playlistRequest
        playlistSongs = emptyList()
        playlistStatus = if (event == null) "" else "추천 음악을 불러오는 중이에요"
        if (event == null) return
        festivalExecutor.execute {
            val result = runCatching { playlistRepository.recommend(event) }
            runOnUiThread {
                if (isDestroyed || isFinishing || playlistRequest != request) return@runOnUiThread
                playlistSongs = result.getOrDefault(emptyList())
                playlistStatus = when {
                    result.isFailure -> "추천 음악을 불러오지 못했어요"
                    playlistSongs.isEmpty() -> "이 행사에 추천할 음악이 아직 없어요"
                    else -> ""
                }
                render()
            }
        }
    }

    // ★ 헤드업 알림 띄우기 (내비 위에도 뜸)
    private fun showAlert(title: String, text: String, askCharge: Boolean) {
        bannerTitle = title; bannerText = text; bannerShort = askCharge; bannerDismissed = false
        render()
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
        festivalExecutor.shutdownNow()
        MusicPlayer.onChange = null
        retryHandler.removeCallbacks(retryDest)
        demoDrive.stop()
        uiHandler.removeCallbacks(clearScore)
        carProps?.unregisterCallback(speedCallback)
        car?.disconnect()
        vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
        vehicle.getHVAC().unregisterHvacAcState(hvacAllSeats, acListener)
        vehicle.getGear().unregisterGearSelection(gearListener)
        if (isDebuggable) unregisterReceiver(demoPassReceiver)
        naviHelper.removeListener(naviListener)
        naviRef = null
        naviHelper.release()
        vehicle.release()
        super.onDestroy()
    }
}
