# Roady · 로디

<img width="2172" height="724" alt="image" src="https://github.com/user-attachments/assets/687ad7cd-8180-4c6b-ba2d-8f8414152878" />

**행사로 달라지는 길, 내 차에 맞게 준비하고 음악으로 즐기다.**

> 경로 주변 행사 안내, 도착 배터리 예측, 충전소 경유, 행사장 통과 음악을 제공하는 Pleos 차량 앱

- **공모전**: 2026 현대자동차그룹 차량용 앱 개발 공모전
- **팀**: Miraero
- **플랫폼**: Pleos Connect

## 주요 기능

- **행사·배터리 안내**
  - 경로 주변 행사와 내비게이션 정체 정보 확인
  - 배터리·남은 거리·에어컨 상태로 도착 잔량 추정
  - 도착 예상 잔량이 10% 미만이면 충전소 경유 제안

- **충전소 경유**
  - 행사장 주변을 제외하고 충전기 상태·우회 거리 기준으로 선정
  - 선택한 충전소를 순정 내비게이션 경유지로 추가

- **행사장 통과 음악**
  - 행사장 통과 시점에 맞춰 15초 하이라이트 재생
  - 경기장에서는 스코어 한 줄 표시

- **P단 정차 카드**
  - 현재 위치·목적지 반경 2km의 오늘 열리는 축제 조회
  - 추천곡 미리듣기 및 행사장 길 안내

## 차량 특화 요소

- **Vehicle SDK**: 배터리·에어컨·기어 상태 조회 및 구독
- **NaviHelper**: 길 안내 감지, 위치·목적지·정체 정보 수신, 경유지 추가
- **화면 전환**: 주행 중 간단한 안내, P단에서 상세 정보 제공
- **알림 제한**: 같은 알림은 15분 이내 반복하지 않음

## 사용 흐름

1. 순정 내비게이션에서 목적지 설정
2. 주변 행사 확인 및 도착 배터리 계산
3. 배터리 부족 시 충전소 경유 제안
4. 행사장 통과 시 음악 재생
5. 정차 후 축제·추천곡 확인

## 기술 스택

- Kotlin / Android Views / Material Components
- Pleos Vehicle SDK `2.3.2`
- Pleos NaviHelper `3.0.5`
- Android Car API / 오디오 포커스 / 알림
- Gradle Kotlin DSL

## 외부 API

- **전국문화축제표준데이터**: 축제 일정·장소·좌표
- **한국환경공단 전기차 충전소 정보**: 충전소 위치·충전기 상태
- **iTunes Search API**: 추천곡·미리듣기

## 현재 구현 범위

- **실제 연동**: 차량 상태, 순정 내비, 축제·충전소 조회, 추천곡 미리듣기
- **샘플 시연**: 행사 기반 정체 판단, 행사장 4곳의 통과 음악, 경기 스코어
- **기본값 사용**: 배터리 용량·전비·공조 소모율
- **API 실패 대응**: 샘플 데이터로 대체
- **에뮬레이터 대응**: Virtual Console 차속을 이용한 시연 주행 모드

## 실행 방법

1. 저장소를 클론하고 Android Studio에서 열기
2. Android SDK 37 및 Pleos 개발 환경 준비
3. 루트 `local.properties`에 API 키 설정

   ```properties
   DATA_GO_KR_KEY=YOUR_CHARGING_API_KEY
   FESTIVAL_API_KEY=YOUR_FESTIVAL_API_KEY
   ```

4. Gradle Sync 후 Pleos 에뮬레이터에서 실행
5. 순정 내비 길 안내와 Virtual Console로 시연

- **빌드**: `./gradlew assembleDebug`
- **Windows 빌드**: `gradlew.bat assembleDebug`
- **APK 경로**: `app/build/outputs/apk/debug/app-debug.apk`
- **주의**: API 키와 `local.properties`는 커밋하지 않기

## 향후 계획

- 실제 전비 반영
- 행사 없는 경로의 배터리 부족 안내
- 백그라운드 동작 개선
- 실제 공연·경기 일정 및 스코어 연동
- AI 추천곡 및 음성 안내 확장
