Minimum Android SDK	Android API 34 (Pleos Connect v2.1.0 에뮬레이터 기준)
Gradle 버전	8.0 이상
개발 언어	Java
Pleos Connect Emulator	버전 2.1.0
Pleos Connect Virtual Console	Pleos Connect Virtual Console를 지원하는 Pleos Connect Emulator 최소 버전: 2.1.0

## 코드 읽는 순서

[사용자 흐름별 코드 안내](docs/code-navigation.md)에서 앱 진입·차량 연결·사용자 확인·운행 기록·주유·정비·연결 해제의 메서드 순서를 확인할 수 있습니다.

## 시작 화면과 사용자 연결

시작 화면은 왼쪽 `QR 연결`과 오른쪽 `차량 기본정보`를 함께 표시합니다. 최초 페어링 사용자는 앱 재시작·새 운행에서도 유지되며,
사용자를 바꾸려면 연결·설정에서 `연결 끊기 · 사용자 변경` 후 같은 차량으로 다시 페어링합니다.
차량 기본정보의 공동 정비·누적거리는 사용자 연결 없이 조회합니다. 차량 등록 자체가 없는 신규 설치는 최초 등록이 필요합니다.
개인 운행·주유·비용은 인증된 사용자에게만 표시하며, 기록은 시작 당시 사용자에게 고정합니다.
백엔드도 `authenticated_user_id` 및 기록 시작의 `subjectId` 계약을 지원하는 버전으로 함께 업데이트해야 합니다.

## 차량 데이터 진단

`app`을 Pleos Connect 에뮬레이터에서 실행하면 한 Activity에서 연료 잔량·연비·EV 충전율·전비·누적거리,
AAOS 배터리 잔량·현재 사용 가능 용량·공칭 용량, 선택 VIN·현재/마지막 위치를 확인할 수 있습니다.
상단 `연료` / `전기 · EV` / `주행 · 거리` / `차량 · 위치` 탭으로 구분합니다.
전체 조회와 상태 요약은 선택한 탭에만 적용하며 값·상태·단위·API 출처를 따로 표시합니다.
연료 탭은 AAOS 잔량·탱크 용량·계산 비율·표준 순간 연비와 Pleos `Fuel.kt` getter 5종을 비교합니다.
SDK 5종은 전체/개별 조회할 수 있으며, 표준 속성도 차량의 실제 지원·권한이 있어야 수신됩니다.
연료 비율/EV 충전율/거리 구독은 직접 시작·중지하며 화면 이탈 시 해제됩니다.
상단 `주행 · 거리` 탭에서는 드라이브 모드, Trip 거리·시간, 크루즈 속도·상태,
브레이크, 총 누적거리, 전륜 조향각 13개 API를 전체 또는 개별 조회합니다.
실행 방법·SDK/단위·권한·테스트 항목은 [차량 데이터 진단 안내](docs/vehicle-diagnostics.md)를 참조하세요.
위도·경도 콜백 API와 화면 조회 방법은 [Pleos 위치 API](docs/pleos-location-api.md)에 정리되어 있습니다.

## 백엔드 API 연결

`local.properties`에 백엔드 **루트 주소**를 설정한 뒤 다시 빌드합니다. 경로에 `/api`를 붙이지 않습니다.

```properties
# 개발용 HTTP 주소. 배포 APK에는 HTTPS 주소를 사용합니다.
API_BASE_URL=http://sheetnews.ddns.net:3001/
```

`-PAPI_BASE_URL=...` 옵션이 있으면 우선합니다. release는 HTTPS만 허용합니다.
예시 화면 없이 실제 차량 연결 화면으로 시작합니다. 서버 미설정·API 실패 시 `연결 필요` 안내와 알림을 표시합니다.
신규 설치는 `POST /installations`로 ID/비밀값을 발급받고 `/pairing-requests` QR과 차량 화면 최종 확인을 사용합니다.
설치 Bearer와 Supabase 로그인 토큰은 별개이며 장치 기능에 모바일 JWT를 사용하지 않습니다.

[기능별 API 연결과 남은 서버 계약 확인 사항](docs/backend-integration.md)을 참조하세요.

## 검증

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Pleos 앱은 Supabase SDK·이메일 로그인·매직링크를 사용하지 않습니다. 사용자 동의는 모바일에서 승인하고,
장치에서는 설치 인증과 마지막 페어링 사용자 조회를 사용합니다. 구간 동의 QR과 후보 선택은 사용하지 않습니다.
설치 자격 증명과 운행 업로드 큐의 암호화 저장은 유지합니다.

`Pleos_Connect_v2.1.0` 이미지는 `android.car` 대신 `ai.pleos.playground.car` 공유 라이브러리를 제공합니다.
현재 Manifest는 두 라이브러리를 선택적으로 선언하며 해당 이미지에 debug APK 설치를 확인했습니다.
테스트 명령의 기기 번호는 `adb devices`에서 확인한 현재 Pleos 에뮬레이터 번호로 바꾸세요.
