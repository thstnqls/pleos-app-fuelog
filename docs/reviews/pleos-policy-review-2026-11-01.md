# 연료·주행거리 API 가용성 조사

검토일: 2026-11-01 (KST). HEAD `edf40196dc0b2321f643804a63631512a7b08d0a`와 기존 미커밋 변경을 포함한 작업 트리를 기준으로 한다.
이번 검토는 연료·거리 API의 `ERROR_NOT_SUPPORTED_PROPERTY` 원인 조사이다. 전체 정책 감사나 실차 지원 검증은 아니다.
앱 제품 코드와 기존 미커밋 변경은 수정하지 않았다. 일시적인 기기 진단 테스트만 추가·실행한 뒤 정리했다.

## 결론

1. 현재 에뮬레이터에서 Pleos 총 누적거리 `getCurrentDistanceDriven()`은 capability `READ`와 getter 값 수신에 성공했다. 주행거리 전체가 미지원인 것은 아니다.
2. Pleos 연료 5개 항목과 Trip 누적거리는 capability에서 모두 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환했다. 화면과 앱의 사전 검사를 거치지 않는 SDK 직접 호출로 재현했다.
3. 연료 비율·누적/순간/주유 이후 연비 getter의 실제 실패 메시지는 하위 `CarPropertyManager`의 property 설정 목록에 요청 ID가 없다는 `IllegalArgumentException`이다. 단순히 앱의 사전 검사를 제거해서 해결되는 문제가 아니다.
4. 별도로 AAOS 표준 연료·거리 조회에는 앱 권한 문제가 있다. `FUEL_LEVEL`과 AAOS `PERF_ODOMETER` 직접 조회는 `SecurityException`을 반환했다. Pleos의 미지원 응답과 구분해야 한다.
5. 현재 이미지가 왜 특정 연료·Trip property를 제공하지 않는지(이미지의 구현 범위, VHAL/config 누락, 서비스 매핑 문제)는 공개 문서와 이번 관찰만으로 확정할 수 없다. 현재 환경이 해당 경로를 제공하지 않는다는 사실과 그 내부 원인은 구분한다.

## 환경·검증 범위

| 항목 | 확인 결과 |
| --- | --- |
| 대상 | `emulator-5554`, 현재 앱 사용자 10 |
| AVD | `Pleos_Connect_v2.1.0`, 로컬 config가 `system-images/android-34/pleos_car_emulator/arm64-v8a/` 참조 |
| Vehicle SDK | 프로젝트 version catalog의 `2.3.2` |
| Vehicle Service | 설치 패키지 `ai.pleos.playground.service.vehicle`, `versionName=2.3.22`, `versionCode=74` |
| 앱 | minSdk 34, target/compileSdk 36. 설치된 앱도 minSdk/targetSdk 일치 |
| Pleos 권한 | `CAR_ENERGY`, `CAR_DRIVE` 모두 실제 앱에서 `PERMISSION_GRANTED=0` |
| 국가·실차 모델 | 미확인. 에뮬레이터를 특정 양산 차량의 보증으로 사용하지 않음 |
| 사용 의도 | 기존 정차용 진단 화면의 연료·거리 조회. 새 주행 중 기능을 검토한 것이 아님 |

공식 Sample App도 SDK 2.3.2 / Service 2.3.22 / Pleos Connect v2.1 Android 14 조합을 기준으로 한다.
버전 숫자가 서로 다르다는 사실만으로 호환성 오류라고 판단할 근거는 없다.
공식 Sample App은 API 목록이 공개 메서드 탐색용임을 설명하며, 실제 기능 지원은 capability 응답으로 확인하도록 한다.
[공식 Sample App 가이드](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app), [공식 샘플 저장소](https://github.com/Pleos-Playground/pleos-sample-vehicle-app).

## 기기에서 재현한 결과

대상 앱 컨텍스트와 권한으로 Vehicle을 초기화하고, 각 API의 capability와 getter를 독립적으로 호출했다.
`PleosReadSupport`나 Activity의 UI 검사는 사용하지 않았다. SDK가 내부에서 수행하는 검사는 그대로 둔다.
센서의 실제 값, VIN, 위치는 기록하지 않았고 값 존재 여부·접근 수준·오류만 기록했다.

| API | SDK가 요청하는 property | capability | getter 직접 호출 |
| --- | --- | --- | --- |
| `getFuelPercent()` | `FUEL_PERCENT` | `ERROR_NOT_SUPPORTED_PROPERTY` | property 설정 목록에 ID가 없다는 예외 |
| `getCurrentFuelEconomy()` | `TRIP_FUEL_ECONOMY`, `CURRENT` 통계 | 동일 | `ERROR_NOT_SUPPORTED_PROPERTY` |
| `getCumulativeFuelEconomy()` | `CUMULATIVE_FUEL_ECONOMY` | 동일 | property 설정 목록에 ID가 없다는 예외 |
| `getInstantaneousFuelEconomy()` | `INSTANTANEOUS_FUEL_ECONOMY` | 동일 | property 설정 목록에 ID가 없다는 예외 |
| `getSinceChargeFuelEconomy()` | `SINCE_REFUEL_FUEL_ECONOMY` | 동일 | property 설정 목록에 ID가 없다는 예외 |
| `getCurrentDistanceDriven()` | `PERF_ODOMETER` | `READ` | `VALUE_PRESENT`, 값 수신 성공 |
| `getTripCumulativeDrivingDistance()` | `TRIP_DRIVING_DISTANCE`, `CUMULATIVE` 통계 | `ERROR_NOT_SUPPORTED_PROPERTY` | `ERROR_NOT_SUPPORTED_PROPERTY` |

직접 getter 실패에서 확인한 메시지의 핵심은 다음과 같다.

> property ID is not in carPropertyConfig list, and so it is not supported

외부 콜백의 타입은 일반 `Exception`일 수 있다. 단순 getter의 위 메시지와 capability의 심볼 오류가 표현 방식은 달라도 동일 property의 가용성 문제를 가리킨다.
property별 숫자 ID는 이번 증거 파일에서 마스킹했으므로 정확한 vendor ID를 추정하지 않는다.
전체 수집 결과는 [진단 응답 기록](vehicle-api-availability-2026-11-01.txt)에 남겼다.

## 왜 앱의 사전 검사를 빼도 같은 오류가 나는가

SDK 2.3.2의 배포 `sources.jar`에서 다음 경로를 확인했다.

- `FuelImpl.getCurrentFuelEconomy()` → `getTripFuelEconomyValue()` → 내부 capability 조회 → `TRIP_FUEL_ECONOMY` 값 조회.
- `OdometerImpl.getTripCumulativeDrivingDistance()` → `getTripDrivingDistanceValue()` → `requestTripDrivingDistanceCapability()` → `TRIP_DRIVING_DISTANCE` 값 조회.
- `getFuelPercent()`, 누적/순간/주유 이후 연비 getter는 `serviceManager.getProperty()`를 직접 호출하지만, 이번 실행에서는 하위 property config 부재 예외를 받았다.
- `getCurrentDistanceDriven()`은 `PERF_ODOMETER`를 읽으며 Trip 통계와 다른 경로다.

따라서 앱의 capability 검사 생략은 오류 발생 단계를 비교하는 진단에는 유용하지만, 서비스나 하위 플랫폼에 없는 property를 생성하지 않는다.
SDK 소스 근거는 로컬 Gradle 캐시의 `Vehicle-2.3.2-sources.jar` 내 `api/implement/FuelImpl.kt`, `api/implement/OdometerImpl.kt`, `service/VehicleServiceManager.kt`이다.
현재 앱 호출 근거: `FuelDiagnosticService.java:16`, `OdometerDiagnosticService.java:16`, `OdometerDiagnosticService.java:21`, `PleosReadSupport.java:18`.

## AAOS 비교 조회에서 별도로 발견한 문제

| 항목 | 실제 관찰 | 해석 |
| --- | --- | --- |
| `android.car.permission.CAR_ENERGY` | 권한 정의 있음, protection=1(dangerous), 앱 권한=-1 | 앱 Manifest의 해당 선언이 주석 처리되어 있음 |
| `android.car.permission.CAR_MILEAGE` | 권한 정의 있음, signature/privileged, 앱 권한=-1 | 일반 런타임 승인만으로 부여할 수 있는 권한이 아님 |
| `android.car.permission.CAR_MILEAGE_3P` | PackageManager가 `NameNotFoundException` 반환 | 현재 API 34 이미지에는 정의가 없음. 선언만으로 새 권한이 생기지 않음 |
| AAOS `FUEL_LEVEL`, `0x11600307` | config 미노출, 직접 getter `SecurityException` | 이번 관찰에서는 앱의 접근 권한 부족이 확인됨. 실제 센서 지원까지 판단하지 않음 |
| AAOS `INFO_FUEL_CAPACITY`, `0x11600104` | config access=1, getter status=0, 값 존재 | 탱크 용량 조회 성공이 잔량·연비 지원까지 보증하지 않음 |
| AAOS `PERF_ODOMETER`, `0x11600204` | config 미노출, 직접 getter `SecurityException` | Pleos 총 누적거리 조회 성공과 별개인 표준 API 권한 문제 |

코드 근거: `app/src/main/AndroidManifest.xml:10`은 AAOS `CAR_ENERGY`가 주석 처리되어 있고, 11행에는 Pleos `CAR_ENERGY`가 중복 선언되어 있다.
`AaosVehicleDiagnosticService.java:78`은 표준 연료·거리 property를, `DiagnosticActivity.requestAaosPermissions()`는 정의된 dangerous 권한만 요청한다.
Pleos와 Android의 `CAR_ENERGY`는 서로 다른 권한이다. Pleos 권한 부여가 AAOS 앱 접근 권한을 부여하지 않는다.

## 우선순위별 발견·최소 수정안

| ID·우선순위 | 판단·발생 조건·영향 | 최소 수정안 | 완료 조건 |
| --- | --- | --- | --- |
| F1 · P1 · Pleos 연료 property 가용성 | 기능 장애 확인. 연료 5개 항목을 현재 AVD에서 요청할 때 capability 미지원 및 직접 getter 실패. P07 플랫폼 가용성에 해당하며 정책 위반을 뜻하지 않음 | `FuelDiagnosticService`의 미지원 상태를 유지하고 0으로 대체하지 않음. 지원 문의에는 이번 응답표·AVD·SDK·Service 버전을 첨부. 지원되는 이미지/config 여부를 확인 | 대상 환경에서 capability READ/READ_WRITE와 getter 실제 응답을 각각 확인. 서비스가 요구하는 config/매핑이 제공되어야 함 |
| F2 · P1 · Trip 통계 미지원 | 기능 장애 확인. Trip 누적거리 getter 내부 검사도 실패. `OdometerDiagnosticService.java:21` | 총 누적거리에는 현재 성공한 `read()`/`getCurrentDistanceDriven()` 사용. Trip 통계를 차량 총 누적거리로 대체 표시하지 않음. 진단 비교가 끝나면 사전 검사 복원을 검토 | 총 누적거리와 Trip 항목을 분리해 재현하고 각각 지원 여부 기록 |
| F3 · P1 · AAOS 연료 권한 누락 | 기능 장애 확인. 표준 연료 조회가 SecurityException. Manifest 10~11행과 기기 권한 응답 근거 | Manifest의 `android.car.permission.CAR_ENERGY` 선언 복원, Pleos 중복 선언 정리. 재설치 후 기존 `AAOS 조회 권한 요청` 버튼으로 dangerous 권한 승인. 해당 수정이 Pleos vendor property를 지원하게 만드는 것은 아님 | 권한 GRANTED 확인 후 `FUEL_LEVEL` config/getter 재조회. 실제 값을 받을 때만 용량과 단위를 확인해 잔량 비율 계산 |
| F4 · P2 · AAOS 거리 권한 세대 차이 | 조건부 위험/플랫폼 한계 확인. 현재 이미지에 CAR_MILEAGE_3P 정의가 없고 기존 CAR_MILEAGE는 privileged | 현재 성공하는 Pleos Odometer 경로 유지. 표준 API로 전환할 때 이미지의 실제 권한 정의·승인 가능성 확인. 일반 권한 대화상자로 privileged 권한을 부여하려 하지 않음 | 대상 이미지에서 필요한 거리 권한이 정의·부여되고 표준 getter 성공 |

제안하는 AAOS 연료 Manifest 변경의 핵심은 다음 한 줄을 실제 선언으로 복원하는 것이다. 이번 조사에서는 적용하지 않았다.

```xml
<uses-permission android:name="android.car.permission.CAR_ENERGY" />
```

기기와 SDK 버전이 맞아도 property 지원은 별도이다. 공식 문서는 모델별 capability 확인을 요구하고, 오류 표에서 미지원·권한·연결 실패를 별도 코드로 정의한다.
[API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability), [오류 코드](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle-error-code).

## 적용 문서와 접근 결과

모두 2026-11-01 열람했다. 현재 설계 가이드 intro의 하위 목록을 확인하고 관련 범위만 검토했다.

| 문서·절 | 접근 결과·이번 조사에서의 의미 |
| --- | --- |
| [설계 가이드 intro](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro) | 본문과 현재 하위 정책 목록 확인. 전체 정책 감사는 수행하지 않음 |
| [P06 SDK API 정책 · 선택 기준](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api) | 본문 확인. 표준 연료 데이터의 AAOS 우선 권장. 기존 Pleos API의 즉시 폐기를 뜻하지 않음 |
| [P07 API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability) | 본문 확인. Vehicle은 기능별 capability로 실제 지원 확인. 이번 결과는 기능 장애/환경 제약이며 의무 위반 판정이 아님 |
| [Vehicle 권한 · 도메인별 권한/누락](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission) | 본문 확인. Fuel은 CAR_ENERGY, Odometer는 CAR_DRIVE |
| [Vehicle 오류 코드 · ErrorCode 목록](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle-error-code) | 본문 확인. PROPERTY/AREA/SECURITY_PERMISSION/SERVICE_CONNECTION 오류 분리 |
| [SDK 릴리스 노트 · Vehicle 2.3.2](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note) | 본문 확인. 공개 API 추가와 모델 변경은 설명하지만 이번 이미지의 모든 연료·Trip property 지원을 보장하지 않음. 검토한 노트에는 이번 현상의 특정 수정 방법이 명시되지 않음 |
| [Emulator 릴리스 노트 · v2.1.0](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note) | 본문 확인. Virtual Console과 SDK 지원 범위 확대. 모든 property 구현 목록은 제공하지 않음 |
| [Sample App 사용 가이드 · 기준 환경/문제 해결](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app) | 본문 확인. 현재와 같은 기준 버전. API 목록·SDK 초기화·실제 지원은 별도 |
| [공식 샘플 저장소 README](https://github.com/Pleos-Playground/pleos-sample-vehicle-app) | 본문 확인. 카탈로그는 public API 탐색용이며 capability를 통한 실제 가용성 확인 필요. 이슈 목록/API 접근은 실패하여 특정 알려진 이슈 유무는 확정하지 않음 |
| [Virtual Console · Motion/Energy](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide) | 본문 확인. Motion에 Odometer 0~1,000,000km 조작 명시. Energy에는 배터리/잔여거리 항목이 명시됨. 연료·Trip 항목이 목록에 없다는 사실만으로 모든 환경의 미지원을 단정하지 않음 |
| [Android VehiclePropertyIds · FUEL_LEVEL/PERF_ODOMETER](https://developer.android.com/reference/android/car/VehiclePropertyIds) | 본문 확인. 연료량 mL와 필요 권한, 거리 km 및 최신 거리 권한 확인. 현재 API 34 이미지의 실제 정의와 별도로 대조 |
| Fuel/Odometer 개별 생성 API Reference | get-fuel-percent, get-trip-cumulative-driving-distance 페이지는 읽을 수 있는 본문이 추출되지 않음. 시그니처·단위·내부 호출은 로컬 SDK 2.3.2 소스와 기기 결과로 확인 |

## 한계·후속 확인

- 읽기 전용 기기 진단 14개 SDK 응답과 AAOS 3개 property 비교를 수행했다. 권한 정의 조회도 별도로 실행했다. 테스트 통과는 모든 응답을 수집했다는 뜻이며, 미지원 getter가 성공했다는 뜻이 아니다.
- 센서 값을 변경하는 set API, 주행 시뮬레이션, 연료/Trip 구독, 실차 테스트, 다른 이미지와의 A/B 비교는 수행하지 않았다.
- Service APK의 읽기와 `cmd car_service help`는 기기의 권한/빌드 제한으로 거부되었다. root 전환이나 보안 설정 변경으로 우회하지 않았다. 실제 Service 내부 매핑/VHAL 설정은 미확인이다.
- 배터리 중심 Virtual Console이나 연료 미지원 응답만으로 에뮬레이터를 EV 전용이라고 확정하지 않았다.
- 이번 조사에서 앱 재설치나 minSdk/targetSdk 변경이 연료·Trip property 부재를 해결한다는 근거는 발견하지 못했다.
- 오디오·로그인·네트워크·일반 UI 디자인 정책은 이 오류 원인과 직접 관련이 없어 적용 제외했다. 출시 심사나 전체 정책 준수를 판정하지 않았다.
- Pleos 지원 문의에 포함할 핵심: AVD v2.1.0/API 34/arm64, SDK 2.3.2, Service 2.3.22, Pleos 권한 GRANTED, 위 property별 capability/getter 결과, 하위 carPropertyConfig 목록 부재 예외. 현재 이미지에서 연료·Trip 데이터가 지원되는지와 필요한 공식 이미지/config/서비스 수정 여부를 문의할 수 있다. 외부 문의는 전송하지 않았다.

## 후속 수정·재검증: CarPropertyManager 표준 연료 조회

사용자의 후속 요청에 따라 Manifest의 `android.car.permission.CAR_ENERGY`와
`android.car.permission.CAR_INFO` 선언을 복원했다. 각각 주석/중복 Pleos 선언으로 바뀌어 있던 상태였다.
이 절 이전의 결과는 수정 전 조사 기록이다.

에뮬레이터 사용자 10에 업데이트 APK를 설치하고 AAOS `CAR_ENERGY` 런타임 권한을 부여한 뒤,
앱의 `AaosVehicleDiagnosticService.readAll()` → `DiagnosticSession`을 그대로 사용하여 재검증했다.
`FUEL_LEVEL`과 `INFO_FUEL_CAPACITY` 모두 실제 `수신`에 성공했으며, AAOS `PERF_ODOMETER`는 `설정 없음`이었다.
F3의 표준 연료 권한 문제는 현재 에뮬레이터에서 수정·검증 완료다. F1/F2의 Pleos 연료·Trip property 가용성 문제와는 별개다.

검증: `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` 성공,
임시 기기 테스트 `fuelAndCapacityWithGrantedPermissions` 통과. 테스트 코드는 조사 후 정리했다.
[수정 후 실제 응답 기록](aaos-fuel-after-permission-fix-2026-11-01.txt).

## 후속 질문: 주행 중 진단 화면 조작

확인일: 2026-11-01. 현재 정차용 진단 화면의 주행 중 조작 가능 여부만 검토했다.
설계 가이드 intro의 현재 하위 목록과 다음 3개 문서의 본문을 열람했으며 앱 코드는 변경하지 않았다.

| 적용 항목 | 판단·근거 |
| --- | --- |
| [P02 지역별 주행 규제 · DO 미선언 시 기본 동작](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy) | 현재 Manifest에는 DO 선언이 없다. 공식 기본 동작은 Parking 사용 가능, Drive 사용 불가다. 현재 차단이 곧 정책 위반을 의미하지 않는다 |
| [P03 앱 카테고리 · 제한적으로 허용되는 기능](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category) | 일부 Music/Audio, Maps/Navigation, Mobility Services 기능은 조건부로 허용될 수 있다. 차량 관리 예시만으로 현재 진단 화면 전체의 조작을 허용한다고 판단하지 않는다 |
| [S01 주행 규제 개발 · CarUxRestrictions 연동](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide) | DO 선언 앱도 플랫폼의 활성 제한 비트와 기능별 제약을 반영해야 한다. DO 메타데이터만 추가하는 방식은 충분하지 않다 |

현재 구현은 `AaosVehicleDiagnosticService.onRestrictions()`에서 DO 요구가 없고 UX 제한이 baseline인 경우에만 허용한다.
`DiagnosticActivity.reconnect()`의 상태 콜백은 허용이 해제되면 `closeMeasurements()`와 `setActionsEnabled(false)`를 실행한다.
그 결과 버튼 비활성화, 데이터 영역 숨김, 구독 해제와 SDK release까지 수행한다. 이 전체 중단은 현재 진단 화면의 보수적인 구현 선택이며,
정책이 모든 차량 데이터 읽기를 주행 중 일괄 금지한다고 해석한 것은 아니다.

주행 중 사용이 제품 요구라면, 최소 수정안은 `onRestrictions()`의 전체 허용/차단 판단에 앞서 주행 중 제공할 간결한 표시·최소 조작을 정의하고,
주행용 화면과 정차용 진단/로그인/입력을 분리한 뒤 DO 적용 적합성과 기능별 활성 제한을 검토하는 것이다.
차량 데이터 수집의 생명주기도 화면 조작 제한과 별도로 설계해야 한다. 현재 질문만으로 이러한 제품 변경을 수행하지 않았다.
완료 조건은 초기 제한 활성, 정차→주행→정차 전환, 기능별 버튼/입력/표시 제한 및 구독 정리를 대상 플랫폼에서 검증하는 것이다.
국가·배포 카테고리·주행용 UX가 미확정이므로 주행 중 조작 허용이나 심사 통과를 확정하지 않는다. 이번에는 정적 코드·문서 확인만 수행했다.
