# 차량 데이터 진단 화면 · 연료·EV와 CAR_DRIVE

확인일: 2026-10-29. 제품 원장·운행 귀속 구현 전에 SDK 값과 지원 여부를 확인하는 개발용 화면이다.
진단 서비스 자체는 서버 업로드, 주유 확정, 거리 합산, QR/BLE/로그인을 수행하지 않는다.

2026-11-01 현재 실행 화면은 연료와 EV를 각각 조회한다. `Vehicle.getFuel()`의 연료 비율·연비 4종과
`Vehicle.getEvBattery()`의 충전율·전비 4종을 독립적으로 표시한다. AAOS 표준 API로 연료 잔량·탱크 용량과
배터리 잔량·현재 사용 가능 용량·공칭 용량을 별도로 읽는다. 차량 모델명으로 조회 항목을 제한하지 않는다.
상단 탭은 `연료`, `전기 · EV`, `주행 · 거리`, `차량 · 위치`의 4개이다. 탭 전환은 자동 조회를 시작하지 않으며,
현재 화면 세션의 수신 결과를 유지한다. 화면 재생성 시 선택 탭만 복원하고 센서 값은 지운다.

## 실행

1. Gradle Sync 후 Pleos Connect 에뮬레이터 또는 권한이 승인된 대상 차량을 선택한다.
2. `app` 실행 구성으로 실행하면 `DiagnosticActivity` 한 화면이 열린다.
3. 정차 상태와 UX 제한 확인이 끝나면 탭을 선택하고 상단 전체 조회 또는 개별 조회 버튼을 누른다.
   연료는 SDK 5개+AAOS 4개(계산 비율 포함), 전기는 SDK 5개+AAOS 3개,
   주행은 SDK 13개+AAOS 누적거리 1개 결과를 표시한다. 구독은 별도 시작한다.
4. 연료 비율·EV 충전율·거리 변경 구독을 각각 시작한 뒤 Virtual Console에서 해당 값을 바꿔 이벤트 수신을 확인한다.
5. `차량 · 위치` 탭에서 `현재 위도·경도 조회` 또는 `마지막 위치 조회`를 누른다. 위치 권한 승인 후 같은 버튼을 다시 누른다.
   현재 위치는 요청 이후 측정된 업데이트 1건을 최대 10초 기다리며 수신/종료 후 리스너와 SDK를 해제한다.
   VIN은 `VIN 조회 펼치기`에서 별도로 조회하며 끝 4자리만 표시한다.
   [위도·경도 API 사용법과 검증](pleos-location-api.md).

### Fuel.kt 전체 getter와 AAOS 표준 비교

Vehicle 2.3.2 `Fuel.kt`의 getter는 아래 5개이며 기존 코드에 모두 연결되어 있었다.
이번 정리에서는 `FuelDiagnosticService.Reading`으로 전체/개별 조회 경로를 통일하고 각 getter 버튼을 추가했다.

| SDK getter | 의미 | 단위 |
| --- | --- | --- |
| `getFuelPercent` | 연료 잔량 비율 | % |
| `getCurrentFuelEconomy` | 현재 운행 연비 | L/100km |
| `getCumulativeFuelEconomy` | 누적 연비 | L/100km |
| `getInstantaneousFuelEconomy` | 순간 연비 | L/100km |
| `getSinceChargeFuelEconomy` | 마지막 주유 이후 연비 | L/100km |

SDK 순간 연비의 주석은 향후 deprecated 예정이라고 안내한다. 현재 2.3.2의 해당 getter에는
`@Deprecated` 어노테이션이 없다. 이미 `@Deprecated`인 Odometer 순간 Trip 거리와 구분한다.

연료 탭 최상단의 `연료 · AAOS 표준` 카드에서 SDK와 독립적으로 다음을 읽는다.

| AAOS 결과 | 구현 | 단위/조건 |
| --- | --- | --- |
| 연료 잔량 | `FUEL_LEVEL` | mL |
| 탱크 용량 | `INFO_FUEL_CAPACITY` | mL |
| 연료 비율 · 계산값 | 위 두 값의 `잔량 / 용량 × 100` | %. 두 센서가 유효하고 용량>0, 0≤잔량≤용량일 때만 계산 |
| 표준 순간 연비 | `INSTANTANEOUS_FUEL_ECONOMY` | L/100km. 별도 주행거리 조회 권한과 실제 property 지원 필요 |

잔량·용량은 같은 조회 묶음에서 한 번씩 읽어 표시·계산에 함께 사용한다. 서로 다른 요청의 캐시 값이나
SDK 값을 섞지 않는다. 계산 비율은 부피 기준이며 OEM의 보정된 계기판 비율과 같다고 가정하지 않는다.
센서가 없거나 현재 사용 불가이면 `계산 불가`, 숫자/범위가 잘못되면 `값 오류`로 표시한다. 0%로 대체하지 않는다.
현재·누적·주유 이후 연비에 1:1 대응하는 표준 AAOS property는 공개 `VehiclePropertyIds` 목록에서
확인하지 못했으므로, 순간 연비를 그 자리에 복사하지 않고 SDK의 개별 결과를 유지한다.
[표준 순간 연비와 권한](https://developer.android.com/reference/android/car/VehiclePropertyIds#INSTANTANEOUS_FUEL_ECONOMY),
[연료 잔량](https://developer.android.com/reference/android/car/VehiclePropertyIds#FUEL_LEVEL),
[탱크 용량](https://developer.android.com/reference/android/car/VehiclePropertyIds#INFO_FUEL_CAPACITY).

### CAR_DRIVE 탭

`주행 · 거리` 탭에서 상단 `주행 전체 조회` 또는 각 행 아래 개별 조회 버튼을 누른다.
거리, 주행 시간, 주행 상태를 카드로 나누고 각 요청의 응답·미지원·권한 오류를 독립적으로 표시한다.
상단 요약과 전체 조회 버튼은 선택한 탭에만 적용된다. 기존 정차/UX 제한과 화면 종료 시 정리 규칙은 동일하다.

| 도메인 | API | 반환값/단위 |
| --- | --- | --- |
| DrivingMode | `getCurrentDrivingModeState` | `DrivingModeType?`, enum 이름 |
| Odometer | `getTripCumulativeDrivingDistance` | `Float?`, m |
| Odometer | `getTripCumulativeDrivingTime` | `Int?`, 분 |
| Odometer | `getTripCurrentDrivingDistance` | `Float?`, m |
| Odometer | `getTripCurrentDrivingTime` | `Int?`, 분 |
| Odometer | `getTripInstantaneousDrivingDistance` | `Float?`, m. SDK 2.3.2에서 deprecated이지만 요청한 진단 항목으로 제공 |
| Odometer | `getTripSinceRefillDrivingDistance` | `Float?`, m |
| Odometer | `getTripSinceRefillDrivingTime` | `Int?`, 분 |
| DrivingMode | `getCruiseControlTargetSpeed` | `Float?`, m/s |
| Brake | `getCurrentBrakePedalStatus` | `Boolean?`, true=눌림, false=떼짐 |
| DrivingMode | `getCruiseControlState` | `CruiseControl?`, enum 이름 |
| Odometer | `getCurrentDistanceDriven` | `Float?`, 총 누적거리 km |
| SteeringWheel | `getCurrentSteeringWheelAngle` | `Float?`, 전륜 조향각 °. 왼쪽 음수, 핸들 회전각과 구분 |

`DriveDiagnosticService`는 `Vehicle.initialize()` 후 얻은 네 도메인 객체를 사용한다.
각 capability의 READ/READ_WRITE를 확인하고 getter를 호출한다. 조향각 capability의 추가 bounds 콜백을
access 검사에 연결하고, 조향각 요청만 음수를 허용한다. 거리·시간 등 기존 수치 검증은 유지한다.
enum은 reflection 없이 `name()`으로 표시한다. 단위·서명은 로컬 Vehicle 2.3.2 sources.jar와 빌드로 확인했다.
권한은 기존 `pleos.car.permission.CAR_DRIVE`를 사용하며 별도 제어 권한은 추가하지 않는다.
[공식 Vehicle 권한 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission).

### Virtual Console에서 누적거리 반영 확인

1. 진단 화면이 정차 UX baseline 상태인지 확인한다. 주행 제한이 켜지면 현재 화면은 구독을 해제한다.
2. 앱에서 `누적거리 구독 시작`을 누른다. 변경값은 `실시간 구독 · 총 누적거리` 행에 표시된다.
3. `http://127.0.0.1:8088/`의 Emulation Controls → Motion → Odometer에서 현재 값과 다른 누적거리를 설정한다.
   공식 Console 가이드의 변경 단위는 1km이다. 주행 시각화 재생만으로 이 property가 자동 증가한다고 가정하지 않는다.
4. 실시간 구독 행의 값과 수신 시각을 확인한다. `누적거리 1회 조회`를 누르면 별도 1회 조회 행에서도 최신 값을 확인할 수 있다.
5. 화면 이탈 또는 주행 제한으로 구독이 해제되었다면 정차 후 구독을 다시 시작한다. 이전 구독은 자동 복원하지 않는다.
6. Trip 누적거리는 다른 통계 property이며, 현재 이미지에서 미지원이 확인되었다. 총 누적거리 테스트와 구분한다.

2026-11-01 관찰: 현재 에뮬레이터의 8088 포워딩과 Cold boot 설정은 정상이다.
SDK 직접 관찰에서 총 누적거리 조회 3회와 구독 이벤트 3개가 응답했으나, 10초 동안 값 변화는 없었다.
당시 `requiresDO=false`, UX `0x0`이었다. 브라우저 도구가 연결되지 않아 Console의 실제 조작 상태나
Odometer 변경 전후를 직접 검증하지는 못했다. 이 관찰만으로 Console 주행 시각화의 자동 누적 기능을 확정하지 않는다.
[거리·UX 관찰 기록](reviews/odometer-console-observation-2026-11-01.txt).

설치된 `Pleos_Connect_v2.1.0` AVD의 `config.ini`는 android-34 이미지를 참조한다.
이를 실행 대상으로 지원하기 위해 minSdk를 36에서 **34**로 변경했다. compile/target SDK는 36이다.
일반 휴대폰 에뮬레이터는 android.car와 Pleos 서비스를 제공하지 않으므로 이 차량 APK의 실행 대상이 아니다.
이 Pleos 이미지의 `pm list libraries`에는 필수 공유 라이브러리 이름 `android.car`가 없다.
Manifest에서 `android.car`를 optional로 선언하여 설치 시 필수 라이브러리 검사로 차단되지 않도록 했다.
실제 이미지에서는 AAOS Car/UX 클래스 호출과 baseline 상태 수신을 확인했다.

## 화면 읽는 법

- 연료·전기·주행/거리·차량/위치가 별도 탭이며, 연료 탭에서는 표준 AAOS 카드와 SDK 비교 카드를 구분한다.
- 수신한 숫자와 단위를 크게 표시하고 상태 배지, 오류 설명, API 이름·접근 권한·수신 시각을 분리한다.
  수신은 초록, 미지원/설정 확인은 주황, 오류는 빨강이며 상태 문구도 함께 표시한다.
- 조회 실패 시 이전 숫자는 지운다. 실제 값 0은 정상 수신 숫자로 표시한다.
- 상단 요약에서 수신·대기·미지원/설정 확인·기타 상태 건수를 확인한다. 각 property의 상세 상태는 해당 행에서 확인한다.
- SDK와 AAOS 표준 값은 서로 다른 행이다. 한쪽의 지원 여부로 다른 쪽을 숨기거나 수치를 합산하지 않는다.

## SDK·호출 서비스

| 서비스 | 실제 SDK 호출 | 단위/확인 사항 |
| --- | --- | --- |
| `EvBatteryDiagnosticService` | `checkEvBatteryStateOfChargeCapabilities` → `getEvBatteryStateOfCharge` | `Integer?`, 충전율 % |
| 동일 | 각 전비 capability → `getCurrentEvEfficiency`, `getCumulativeEvEfficiency`, `getInstantaneousEvEfficiency`, `getSinceChargeEvEfficiency` | `Float?`, km/kWh. 마지막 항목은 충전 이후 전비 |
| 동일 | `registerEvBatteryStateOfCharge` / `unregisterEvBatteryStateOfCharge` | 사용자 조작으로 시작/중지하는 충전율 % 변경 구독 |
| `AaosVehicleDiagnosticService.readEvBattery` | config/access 확인 → `EV_BATTERY_LEVEL`, `EV_CURRENT_BATTERY_CAPACITY`, `INFO_EV_BATTERY_CAPACITY` 조회 | 모두 Wh. 현재 잔량, 현재 사용 가능 용량, 신차 기준 공칭 용량을 서로 구분. SDK 충전율 %와 임의로 합산하지 않음 |
| `FuelDiagnosticService` | `checkFuelPercentCapabilities` → `getFuelPercent` | `Integer?`, % |
| 동일 | 각 연비 capability → `getCurrentFuelEconomy`, `getCumulativeFuelEconomy`, `getInstantaneousFuelEconomy`, `getSinceChargeFuelEconomy` | `Float?`, L/100km. `SinceCharge`라는 SDK 이름이지만 소스 설명은 주유 이후 연비이다. |
| 동일 | `registerFuelPercent` / `unregisterFuelPercent` | 사용자 조작으로 시작/중지하는 % 변경 구독 |
| `OdometerDiagnosticService` | `checkCurrentDistanceDrivenCapabilities` → `getCurrentDistanceDriven` | `Float?`, 누적 주행거리 km |
| 동일 | `getTripCumulativeDrivingDistance` 직접 호출 | `Float?`, Trip 누적 주행거리 m. capability 사전 조회를 생략하는 진단용 호출이며, getter의 값/null/실패 응답을 그대로 구분하여 표시한다. |
| 동일 | `registerDistanceDriven` / `unregisterDistanceDriven` | km. 다른 Trip 거리 리스너의 m 단위와 혼동하지 않는다. |
| `CarInfoDiagnosticService` | `checkVINInfoCapabilities` → `getVINInfo` | 문자열, 끝 4자리 마스킹. 차량 소유자 인증으로 사용하지 않는다. |
| `LocationDiagnosticService` / `PleosLocationClient` | 권한·`isSupported` → `initialize` → `getLastKnownLocation` 또는 `registerFusedLocationCallback` → 해제 / `release` | 위·경도 typed callback. 현재 위치 업데이트 1건과 마지막 저장 위치를 구분하며 원 timestamp·고도·방향·confidence도 전달. confidence를 정확도 m로 해석하지 않는다. |
| `AaosVehicleDiagnosticService` | property config/access 확인 → `getProperty` | FUEL_LEVEL / INFO_FUEL_CAPACITY: mL, PERF_ODOMETER: km. status와 부팅 후 센서 timestamp(ns) 확인. Pleos 값과 합산/이어붙이기하지 않는다. |
| 동일 | `Car.createCar` 생명주기, `CarUxRestrictionsManager` 현재값/리스너 | 서비스 준비·단절·UX 제한 변화를 화면에 반영 |

현재 UI는 탭에 맞춰 `readFuel`, `readEvBattery`, `readOdometer`를 호출한다.
전체 조회 버튼은 다른 탭이나 VIN·위치를 읽지 않는다. 구독 시작/중지와 선택 데이터 조회는 별도 조작이다.

Vehicle **2.3.2**, FusedLocation **1.1.3**을 version catalog로 고정했다.
공식 Maven 의존성을 사용하며 SDK 파일이나 비밀값을 저장소에 복사하지 않는다.
Vehicle sources.jar와 실제 AAR의 `javap` 결과로 Java 메서드·콜백 서명을 대조했다.
배포 AAR는 일부 내부 예외 타입이 난독화되어 sources.jar의 `VehicleException`을 직접 import할 수 없다.
따라서 public Exception 콜백에서 `ERROR_*` 형태의 안전한 코드만 추출하고 임의 예외 메시지는 노출하지 않는다.

NaviHelper/STT/TTS는 현재 진단 범위 밖이므로 의존성과 호출을 추가하지 않았다.
EV 배터리·전비는 기존 Vehicle 2.3.2 의존성 안에서 제공되는 `EvBattery` API를 사용한다.
Supabase는 차량 데이터 SDK가 아니며 기존 HTTP API 모듈은 그대로 보존했다.

## 상태·생명주기

- capability가 READ / READ_WRITE일 때만 get 또는 구독을 호출한다. NONE/WRITE는 읽기 불가로 표시한다.
  단, Trip 누적거리 버튼은 오류 발생 단계를 비교하기 위해 capability 사전 검사 없이 getter를 직접 호출한다.
  Vehicle 2.3.2의 getter 내부에도 capability 조회가 있으므로, 앱의 검사를 생략해도 SDK 내부 조회는 수행된다.
- null, 권한 오류, 호출 오류, 설정 없음, 읽기 불가, 시간 초과를 값 0과 구분한다.
- 1회 요청은 12초 제한을 두고 만료·재조회 이전의 늦은 응답은 무시한다.
- 구독도 첫 이벤트가 12초 내 없으면 시간 초과로 표시한다. 등록 요청 자체는 성공 확인이 아니며,
  변경이 없어서 이벤트가 없을 수도 있다. 중지 후 다시 시작해서 재검증한다.
- 화면 이탈/정지, 서비스 재연결, UX 제한 진입 시 세션을 무효화하고 구독 해제 및 SDK release를 호출한다.
  복귀 시 새 인스턴스와 미조회 화면으로 시작한다. 자동으로 개인 데이터나 선택 위치를 재조회하지 않는다.
- 진단 화면은 UX 제한이 baseline이고 DO 요구가 없을 때만 열어 준다. DO 메타데이터는 선언하지 않는다.
- 앱은 진단값을 파일·DB·서버에 저장하거나 로깅하지 않는다. 화면 캡처도 제한한다.
  Pleos SDK/플랫폼 자체의 진단 로그 정책은 별도로 확인해야 한다.

## 권한

Pleos 조회 권한: `CAR_ENERGY`, `CAR_DRIVE`, `CAR_INFO`, 선택 위치의 `FUSED_LOCATION`.
FusedLocation 권한은 dangerous이므로 런타임 요청 후 사용자가 조회를 다시 실행한다.
SDK의 바인딩용 권한과 package visibility 선언은 각 AAR Manifest에서 병합된다.

Pleos `CAR_DRIVE`는 normal 권한이다. 소비 앱은 `<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />`로
사용을 요청하며 런타임 팝업은 필요 없다. `<permission ... android:protectionLevel="normal" />`은 권한 자체의 정의이며
사용 요청을 대신하지 않는다. 다른 패키지가 소유한 같은 이름의 권한을 앱에서 재정의하면 설치 충돌도 발생할 수 있다.
[Android permission 정의](https://developer.android.com/guide/topics/manifest/permission-element),
[uses-permission 사용 요청](https://developer.android.com/guide/topics/manifest/uses-permission-element).

AAOS 비교용 권한: `android.car.permission.CAR_ENERGY`, `android.car.permission.CAR_INFO`, `android.car.permission.CAR_MILEAGE_3P`.
표준 순간 연비는 `CAR_MILEAGE_3P`를 요구한다. Pleos의 normal `CAR_ENERGY`와 AAOS의 dangerous 권한은 별개이다.
AAOS 권한 버튼은 **현재 이미지가 정의한 dangerous 권한만** 요청한다.
오래된 이미지에서 CAR_MILEAGE_3P가 정의되지 않았거나 Odometer가 privileged 권한만 허용하면
표준 거리 조회는 실패하거나 설정이 노출되지 않을 수 있다. 권한 부재를 값 0으로 취급하지 않는다.
Pleos 권한 선언만으로 플랫폼/심사 승인이 완료된 것은 아니며 실제 사용 승인은 별도이다.

## 검증 절차

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

`DiagnosticSessionTest`는 기기에서 재조회/화면 종료/구독 중지 뒤 늦은 응답 차단,
null·범위 오류 구분, 읽기 불가 capability에서 getter 미호출을 검증한다.
`DiagnosticComparisonUiTest`는 4개 탭, 연료/EV 및 SDK/AAOS 독립 행, 0과 실패 구분,
초기화 후 이전 값·오류 제거, 좁은 폭에서 긴 API/오류 문구 줄바꿈을 검증한다.
CAR_DRIVE 탭의 13개 행·버튼, 조회 없는 탭 전환과 선택 탭 복원도 확인한다.
탭별 요약·전체 조회 범위와 Fuel getter 5종 개별 버튼도 확인한다.
`FuelDiagnosticServiceTest`는 SDK 대역으로 Fuel getter 5개 연결, 한 getter 실패 후 다른 요청 완료,
0%·단위 유지와 개별 조회 시 다른 getter 미호출을 검증한다.
`FuelLevelPercentTest`는 단위 유지, 0%/100%, 용량 0·범위 초과·null·NaN·무한대의 계산 거부를 검증한다.
`PleosLocationClientTest` 7개는 실제 좌표를 수집하지 않고 SDK 대역으로 권한·null·좌표 범위·
캐시와 새 업데이트의 구분, 타임아웃 정리 및 취소/화면 종료 뒤 늦은 콜백 차단을 검증한다.
`DriveDiagnosticServiceTest`는 SDK 도메인 대역으로 13개 getter 연결, 한 요청 실패 후 다른 응답 완료,
단위·enum·false·음수 조향각을 검증한다. 이 테스트는 에뮬레이터의 실제 property 지원 보장이 아니다.
실제 API 검증은 아래 조건을 별도로 기록한다.

| 시나리오 | 확인할 결과 |
| --- | --- |
| 일반 조회 | 각 항목의 capability, 출처, 단위, 수신 시각 및 원값/오류 확인 |
| 연료/EV/거리 구독 시작을 두 번 누름 | 중복 리스너 없이 새 구독으로 교체 |
| 구독 중지 후 Console 값 변경 | 화면에 늦은 이벤트가 반영되지 않음 |
| 홈 이동/복귀·화면 재생성 | 구독/연결 정리, 민감값 지우고 새 미조회 화면 |
| 위치 권한 거부/허용/지원 서비스 없음 | 권한 및 미지원 상태 구분, null을 좌표 0으로 대체하지 않음 |
| UX 제한 진입/해제 | 제어·값 영역 숨김, 수집 해제, 복귀 후 명시적 재조회 |

에뮬레이터에서 받은 값은 실차 지원·센서 정확도·주유 거래의 증거가 아니다.

### 2026-11-01 Fuel.kt getter 확인·4개 탭·AAOS 표준 연료 조회

- `Fuel.kt`의 getter 5개가 모두 연결되어 있음을 확인하고 전체/개별 조회를 제공했다.
- 연료·전기·주행/거리·차량/위치의 4개 탭으로 정리했다. 상태 요약과 전체 조회는 해당 탭 범위에만 적용된다.
- 연료 탭 최상단에 AAOS 잔량·탱크 용량·계산 비율·표준 순간 연비를 SDK와 구분해 배치했다.
- 잘못된 CAR_DRIVE 권한 정의를 소비 앱의 uses-permission 요청으로 수정했다. 설치 앱에서 normal 권한 부여 확인.
- 실제 UX baseline `0x0` 상태에서 AAOS 잔량·탱크 용량·계산 비율 수신 확인. 순간 연비는 `설정 없음` 응답.
- 현재 이미지에는 `android.car.permission.CAR_MILEAGE_3P` 정의가 없다. 기존 `CAR_MILEAGE`는
  signature|privileged이다. 새 선언만으로 현재 이미지에 없는 권한/속성이 생기지는 않는다.
- 빌드·lint, 단위 테스트 20개, 기기 테스트 14개 통과(실제 AAOS 조회용 임시 probe 1개 포함).
  임시 probe는 저장소 밖에 보관하고 영구 회귀 테스트 13개를 유지한다.
- [센서 숫자를 제외한 실제 응답 기록](reviews/aaos-fuel-standard-runtime-results-2026-11-01.txt).

### 2026-11-01 연료·EV 통합 UI 검증

- 연료·EV 독립 카드와 통합/개별 조회, 상태 배지·큰 수치·API 상세, 좁은 폭의 줄바꿈을 적용했다.
- debug APK/테스트 APK 빌드, lint, 단위 테스트 16개와 기기 UI/세션 테스트 7개가 통과했다.
- 현재 사용자 10에 기존 데이터를 유지하며 변경 APK를 설치했다.
- 실제 `모두 조회` 검증은 에뮬레이터의 UX 제한 `0x50` 때문에 버튼이 비활성화되어 완료하지 못했다.
  연결 상태를 포함한 진단 결과로 제한을 확인했으며, 제한을 우회하지 않았다.
  Console 주행을 정지하고 차량을 정차 상태로 전환한 다음 제한 해제를 확인해 `모두 조회`를 실행한다.
- 아래 연료·EV 실제 응답 기록은 이전 정차 상태에서 수집한 기록이며, 통합 버튼의 새 실행 결과가 아니다.

### 2026-11-01 CAR_DRIVE 탭 추가 검증

- 기존 연료·EV 화면과 함께 고정 상단 탭을 추가하고, 요청한 주행 getter 13개의 전체/개별 조회를 연결했다.
- debug APK/테스트 APK 빌드, lint, 단위 테스트 16개, UI·세션·주행 서비스 기기 테스트 10개 통과.
- 기기 테스트에는 13개 getter의 SDK 인터페이스 대역 검증을 포함한다. 실제 차량 property 13개가
  모두 수신되었다는 의미는 아니며, 미지원 항목은 화면에서 개별 상태로 표시한다.
- emulator-5554의 현재 사용자 10에 변경 APK를 기존 데이터 유지 상태로 설치했다.

### 이번 실행 결과

- Pleos Connect v2.1.0 / API 34 / Vehicle Service 2.3.22에서 APK 설치 및 Activity 실행 성공,
  UX baseline `0x0` 수신.
- 연료 비율 및 현재/누적/순간/주유 이후 연비 요청 모두 `ERROR_NOT_SUPPORTED_PROPERTY` 응답.
  현 이미지에서 수치 수신은 확인하지 못했다. SDK 메서드가 존재하는 것과 런타임 property 지원은 다르다.
- Pleos 누적 주행거리 `0.0 km`, AAOS INFO_FUEL_CAPACITY `15000.0 mL` 수신.
  이는 해당 에뮬레이터가 반환한 값이며 실제 차량의 탱크 용량으로 해석하지 않는다.
- AAOS FUEL_LEVEL과 PERF_ODOMETER는 현재 앱 권한에서 config가 노출되지 않았다.
  미지원과 권한 필터링 중 어느 원인인지는 이번 실행만으로 확정하지 않는다.
- `DiagnosticSessionTest` 기기 테스트 4개 통과: 재조회 이전 응답 무시, 종료/구독 중지 뒤 응답 무시,
  null/범위 오류 구분, 읽기 불가 capability에서 getter 호출 차단.

### 2026-11-01 AAOS 연료 권한 복원 후 검증

- Manifest의 AAOS `CAR_ENERGY`와 `CAR_INFO` 선언을 복원했다. 같은 이름의 Pleos 권한과는 별도이다.
- 에뮬레이터 사용자 10에 앱을 업데이트하고 `android.car.permission.CAR_ENERGY` 런타임 권한을 부여했다.
  다른 기기나 새 설치에서는 `AAOS 조회 권한 요청` 버튼으로 권한 승인 후 조회한다.
- 실제 `AaosVehicleDiagnosticService.readAll()`과 `DiagnosticSession` 흐름을 이용한 기기 테스트에서
  AAOS `FUEL_LEVEL`과 `INFO_FUEL_CAPACITY` 모두 `수신` 상태를 확인했다. 실제 센서 숫자는 기록하지 않았다.
- 화면의 `AAOS 연료량·탱크용량·거리 비교` 또는 `일반 조회 전체` 버튼에서 표준 연료량(mL)과 탱크 용량(mL)을 확인할 수 있다.
- AAOS `PERF_ODOMETER`는 여전히 설정이 노출되지 않는다. 현재 API 34 이미지에는
  `CAR_MILEAGE_3P` 권한 정의가 없고 `CAR_MILEAGE`는 privileged이므로, 거리에는 기존에 수신이 확인된 Pleos `getCurrentDistanceDriven()`을 사용한다.
- 이번 수정은 표준 연료량 조회의 권한 문제를 해결한다. 미지원 Pleos 연비·Trip 통계를 제공하도록 바꾸는 수정은 아니다.
- 검증 기록: [AAOS 연료 권한 복원 이후 응답](reviews/aaos-fuel-after-permission-fix-2026-11-01.txt).

## 근거

### 2026-11-01 EV 화면 전환 및 실제 응답

- 화면 제목을 `fuelog EV 데이터 확인`으로 바꾸고, 연료 버튼/행을 EV 충전율·전비 조회로 교체했다.
  SDK 소스 및 실제 AAR 공개 서명으로 Java 콜백 타입과 단위를 확인했다.
- SDK 충전율은 `Integer?`, 전비는 `Float?`이다. 전비는 모두 km/kWh이며 L/100km를 재사용하지 않았다.
- 에뮬레이터의 실제 앱 서비스 흐름에서 SDK 충전율과 AAOS 배터리 잔량·현재 사용 가능 용량·공칭 용량 수신에 성공했다.
- 현재/누적/순간/충전 이후 전비 4개는 capability에서 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환했다.
  getter 직접 호출로도 현재 전비는 같은 코드, 나머지 3개는 하위 property config 부재 예외를 반환했다.
- EV 데이터가 수신되었다는 사실만으로 이 에뮬레이터가 EV 전용 모델이라고 확정하지 않는다.
- EV 9개 조회 응답 확인과 기존 세션 테스트 4개, 직접 getter 비교 테스트를 실행했다.
  빌드·단위 테스트·lint도 통과했다. 원 센서 값은 기록하지 않았다.
- [EV 기기 검증 기록](reviews/ev-api-runtime-results-2026-11-01.txt).
- [배터리 정보 확인 FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq),
  [AAOS EV 배터리 잔량](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_BATTERY_LEVEL),
  [현재 사용 가능 용량](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_CURRENT_BATTERY_CAPACITY),
  [공칭 용량](https://developer.android.com/reference/android/car/VehiclePropertyIds#INFO_EV_BATTERY_CAPACITY).

2026-10-29 본문 열람 및 로컬 SDK 대조:

- [SDK Overview](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/): 모듈 및 API 정책.
- [Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability).
- [FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions), [생명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release), [마지막 위치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access).
- [AAOS VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds), [Car](https://developer.android.com/reference/android/car/Car), [UX 제한](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager).
- 로컬 `Vehicle-2.3.2-sources.jar`: Fuel/Odometer/CarInfo/리스너/서비스 연결 구현. 실제 두 AAR의 classes.jar와 android-36 android.car.jar로 공개 서명을 대조했다.
