# 차량 데이터 진단 화면

확인일: 2026-10-29. 제품 원장·운행 귀속 구현 전에 SDK 값과 지원 여부를 확인하는 개발용 화면이다.
진단 서비스 자체는 서버 업로드, 주유 확정, 거리 합산, QR/BLE/로그인을 수행하지 않는다.

## 실행

1. Gradle Sync 후 Pleos Connect 에뮬레이터 또는 권한이 승인된 대상 차량을 선택한다.
2. `app` 실행 구성으로 실행하면 `DiagnosticActivity` 한 화면이 열린다.
3. 정차 상태와 UX 제한 확인이 끝나면 `일반 조회 전체` 또는 기능별 조회 버튼을 누른다.
4. 연료·거리 변경 구독을 시작한 뒤 Virtual Console에서 해당 값을 바꿔 이벤트 수신을 확인한다.
5. VIN은 별도 버튼으로 조회하고 끝 4자리만 표시한다. 위치는 별도 동의/런타임 권한 처리 후
   버튼을 다시 눌러 마지막 위치를 한 번 조회한다. 연속 위치 구독은 하지 않는다.

설치된 `Pleos_Connect_v2.1.0` AVD의 `config.ini`는 android-34 이미지를 참조한다.
이를 실행 대상으로 지원하기 위해 minSdk를 36에서 **34**로 변경했다. compile/target SDK는 36이다.
일반 휴대폰 에뮬레이터는 android.car와 Pleos 서비스를 제공하지 않으므로 이 차량 APK의 실행 대상이 아니다.
이 Pleos 이미지의 `pm list libraries`에는 필수 공유 라이브러리 이름 `android.car`가 없다.
Manifest에서 `android.car`를 optional로 선언하여 설치 시 필수 라이브러리 검사로 차단되지 않도록 했다.
실제 이미지에서는 AAOS Car/UX 클래스 호출과 baseline 상태 수신을 확인했다.

## SDK·호출 서비스

| 서비스 | 실제 SDK 호출 | 단위/확인 사항 |
| --- | --- | --- |
| `FuelDiagnosticService` | `checkFuelPercentCapabilities` → `getFuelPercent` | `Integer?`, % |
| 동일 | 각 연비 capability → `getCurrentFuelEconomy`, `getCumulativeFuelEconomy`, `getInstantaneousFuelEconomy`, `getSinceChargeFuelEconomy` | `Float?`, L/100km. `SinceCharge`라는 SDK 이름이지만 소스 설명은 주유 이후 연비이다. |
| 동일 | `registerFuelPercent` / `unregisterFuelPercent` | 사용자 조작으로 시작/중지하는 % 변경 구독 |
| `OdometerDiagnosticService` | `checkCurrentDistanceDrivenCapabilities` → `getCurrentDistanceDriven` | `Float?`, 누적 주행거리 km |
| 동일 | `getTripCumulativeDrivingDistance` 직접 호출 | `Float?`, Trip 누적 주행거리 m. capability 사전 조회를 생략하는 진단용 호출이며, getter의 값/null/실패 응답을 그대로 구분하여 표시한다. |
| 동일 | `registerDistanceDriven` / `unregisterDistanceDriven` | km. 다른 Trip 거리 리스너의 m 단위와 혼동하지 않는다. |
| `CarInfoDiagnosticService` | `checkVINInfoCapabilities` → `getVINInfo` | 문자열, 끝 4자리 마스킹. 차량 소유자 인증으로 사용하지 않는다. |
| `LocationDiagnosticService` | `isSupported` → `initialize` → `getLastKnownLocation` → `release` | `LocationData?`. 위·경도, 고도, 방향, confidence 원값, timestampMillis 및 경과 시간. confidence를 임의로 정확도 m로 해석하지 않는다. |
| `AaosVehicleDiagnosticService` | property config/access 확인 → `getProperty` | FUEL_LEVEL / INFO_FUEL_CAPACITY: mL, PERF_ODOMETER: km. status와 부팅 후 센서 timestamp(ns) 확인. Pleos 값과 합산/이어붙이기하지 않는다. |
| 동일 | `Car.createCar` 생명주기, `CarUxRestrictionsManager` 현재값/리스너 | 서비스 준비·단절·UX 제한 변화를 화면에 반영 |

Vehicle **2.3.2**, FusedLocation **1.1.3**을 version catalog로 고정했다.
공식 Maven 의존성을 사용하며 SDK 파일이나 비밀값을 저장소에 복사하지 않는다.
Vehicle sources.jar와 실제 AAR의 `javap` 결과로 Java 메서드·콜백 서명을 대조했다.
배포 AAR는 일부 내부 예외 타입이 난독화되어 sources.jar의 `VehicleException`을 직접 import할 수 없다.
따라서 public Exception 콜백에서 `ERROR_*` 형태의 안전한 코드만 추출하고 임의 예외 메시지는 노출하지 않는다.

NaviHelper/STT/TTS와 EV 배터리는 현재 기획의 후속 기능이므로 의존성과 호출을 추가하지 않았다.
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

AAOS 비교용 권한: `android.car.permission.CAR_ENERGY`, `android.car.permission.CAR_INFO`.
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
실제 API 검증은 아래 조건을 별도로 기록한다.

| 시나리오 | 확인할 결과 |
| --- | --- |
| 일반 조회 | 각 항목의 capability, 출처, 단위, 수신 시각 및 원값/오류 확인 |
| 연료/거리 구독 시작을 두 번 누름 | 중복 리스너 없이 새 구독으로 교체 |
| 구독 중지 후 Console 값 변경 | 화면에 늦은 이벤트가 반영되지 않음 |
| 홈 이동/복귀·화면 재생성 | 구독/연결 정리, 민감값 지우고 새 미조회 화면 |
| 위치 권한 거부/허용/지원 서비스 없음 | 권한 및 미지원 상태 구분, null을 좌표 0으로 대체하지 않음 |
| UX 제한 진입/해제 | 제어·값 영역 숨김, 수집 해제, 복귀 후 명시적 재조회 |

에뮬레이터에서 받은 값은 실차 지원·센서 정확도·주유 거래의 증거가 아니다.

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

2026-10-29 본문 열람 및 로컬 SDK 대조:

- [SDK Overview](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/): 모듈 및 API 정책.
- [Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability).
- [FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [설치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions), [생명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release), [마지막 위치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access).
- [AAOS VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds), [Car](https://developer.android.com/reference/android/car/Car), [UX 제한](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager).
- 로컬 `Vehicle-2.3.2-sources.jar`: Fuel/Odometer/CarInfo/리스너/서비스 연결 구현. 실제 두 AAR의 classes.jar와 android-36 android.car.jar로 공개 서명을 대조했다.
