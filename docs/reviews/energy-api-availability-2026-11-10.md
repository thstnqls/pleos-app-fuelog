# 에너지·충전 API 실제 수신 확인 (2026-11-10)

## 검증 범위

사용자가 제시한 Pleos 읽기 API 12종과 AAOS 표준 충전·배터리·연료 속성을 실제 조회했다. 전체 정책 감사나 차계부 기능 연결 작업은 아니다. 정차용 디버그 진단이며 차량 상태를 바꾸는 API, VHAL 쓰기, 센서 값 주입은 하지 않았다.

- 코드 기준: HEAD 51684b1 및 미커밋 변경. 기존 작업 보존.
- 환경: emulator-5554, Pleos_Connect_v2.1.0, Android API 34, 사용자 10.
- Vehicle SDK 2.3.2. Vehicle Service versionCode 74. 현재 dumpsys의 versionName은 미확인.
- 측정 시각: 2026-11-10 20:26, 20:29 KST. 국가·양산 차량 모델 미확인.
- [첫 원본](energy-api-readings-2026-11-10.json), [포트 권한·구독 포함 최종 원본](energy-api-readings-with-ports-2026-11-10.json).
- 재현: EnergyAvailabilityAndroidTest.actualPleosAndAaosEnergyReadings. 실제 SDK 연결 응답을 기다린 후 각 capability와 getter를 독립 호출했다.

## Pleos 12종

| API | 실제 결과 |
|---|---|
| getCumulativeEvEfficiency | 미지원 |
| getCumulativeFuelEconomy | 미지원 |
| getCurrentEvEfficiency | 미지원 |
| getEvBatteryStateOfCharge | 90 %, READ |
| getFuelPercent | 미지원 |
| getInstantaneousEvEfficiency | 미지원 |
| getCurrentFuelEconomy | 미지원 |
| getInstantaneousFuelEconomy | 미지원 |
| getSinceChargeEvEfficiency | 미지원 |
| getSinceChargeFuelEconomy | 미지원 |
| getEvChargeCurrentDrawLimit | 12.5 A, READ_WRITE |
| getEvChargeSwitchState | false, READ_WRITE |

미지원 9종은 Pleos CAR_ENERGY 권한이 허용된 상태에서 capability 오류 ERROR_NOT_SUPPORTED_PROPERTY와 getter의 property 부재/미지원 응답을 확인했다. 앱의 사전 검사만 제거해서 해결할 문제로 판단하지 않는다. READ_WRITE capability를 받았지만 이번 진단은 읽기만 수행했다.

## AAOS 실제 수신값

CAR_ENERGY 런타임 권한 허용 전에는 에너지 속성이 SecurityException이었다. 허용 후 다음 값은 STATUS_AVAILABLE(0)로 반환됐다.

| 속성 | 값 |
|---|---|
| EV_CHARGE_STATE | 2 / STATE_FULLY_CHARGED |
| EV_CHARGE_SWITCH | false |
| EV_CHARGE_CURRENT_DRAW_LIMIT | 12.5 A |
| EV_CHARGE_PERCENT_LIMIT | 50 % |
| EV_CHARGE_TIME_REMAINING | 0 s |
| EV_BATTERY_INSTANTANEOUS_CHARGE_RATE | 0 mW |
| EV_BATTERY_LEVEL | 70,900 Wh |
| EV_CURRENT_BATTERY_CAPACITY | 77,400 Wh |
| INFO_EV_BATTERY_CAPACITY | 77,400 Wh |
| FUEL_LEVEL | 15,000 mL |
| INFO_FUEL_CAPACITY | 15,000 mL |
| INFO_FUEL_TYPE | [10] / ELECTRIC |
| EV_CHARGE_PORT_CONNECTED | false |
| EV_CHARGE_PORT_OPEN | false |

포트 연결·열림 두 속성은 별도의 normal 권한 CAR_ENERGY_PORTS가 필요했다. 이를 app/src/debug/AndroidManifest.xml에만 선언해 실제 수신을 확인했다. release 권한은 변경하지 않았다.

INSTANTANEOUS_EV_EFFICIENCY, INSTANTANEOUS_FUEL_ECONOMY는 API 34 플랫폼에 Java 상수가 없었으며 compile SDK의 공식 ID로 직접 요청해도 config 미노출 및 unsupported 응답이었다. 표준 PERF_ODOMETER는 CAR_MILEAGE 권한 오류였다. Pleos 총 누적거리 조회 성공과는 별개다.

EV_CHARGE_STATE 구독 등록과 최초 콜백 수신도 성공했고 status=0/value=2를 받았다. ONCHANGE 측정 시각은 마지막 변화 시각일 수 있다. 충전 중(1)→완료(2) 전환을 실제로 발생시키지는 않았다.

## 충전 상태 적용 방안

실제 진행 상태는 AAOS EV_CHARGE_STATE를 우선한다. 0=알 수 없음, 1=충전 중, 2=설정한 목표 도달, 3=충전 안 함, 4=오류로 구분한다. 목표가 100%가 아닐 수 있으므로 2를 무조건 배터리 100%라고 표시하지 않는다.

고정 SDK의 EvBatteryImpl.getEvChargeSwitchState는 EV_CHARGE_SWITCH property를 읽는다. 이 스위치 Boolean만으로 실제 충전 진행을 확정하지 않는다. 케이블 연결 상태는 별도 보조 정보로 유지하고 오류·null을 false로 바꾸지 않는다.

SDK SOC 90%와 AAOS 70,900/77,400 Wh 비율은 일치하지 않는다. 서로 다른 측정 경로를 혼합·평균내지 않고 출처와 시각을 유지해야 한다. 이 EV 에뮬레이터의 연료 속성 반환도 연료차의 실차 지원을 보장하지 않는다.

## 한정 정책 검토

| 범위·판단 | 근거와 수정안 |
|---|---|
| P06 SDK API 정책: 관찰 범위 적합 | 표준 충전 상태를 AAOS로 조회 가능함을 확인. Pleos SOC는 출처를 분리한다. |
| P07 API 가용성: 기능 장애 확인 | 전비 4종·연료 5종 vendor property가 이 이미지에서 미지원. 실차 capability/getter 재검증 필요. |
| P1 핵심 충전 상태 연결: 수정안 도출 | 현재 AaosVehicleDiagnosticService.java:119, :132는 배터리 Wh·용량만 읽으며 충전 상태는 아직 앱 기록 흐름에 연결하지 않는다. 다음 구현에서는 상태 읽기·구독, 권한 거부·알 수 없음·재연결을 반영하고 충전 중→완료 전환을 검증해야 한다. 이번 요청은 가용성 확인까지다. |

## 공식 자료 열람

2026-11-10 열람. intro/P06/P07 및 Android 문서 본문 접근 성공. Pleos getter 웹 페이지는 텍스트 본문을 읽을 수 없어 프로젝트 SDK 2.3.2 sources.jar의 EvBattery.kt/EvBatteryImpl.kt 시그니처·단위·property 매핑과 대조했다. 다른 전체 정책은 이번 범위 밖이다.

- [설계 가이드 intro](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro)
- [P06 SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api): AAOS Alignment, API 선택.
- [P07 API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability): 차량별 지원, capability·CarPropertyManager 확인.
- [Android VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds#EV_CHARGE_STATE): 상태/스위치 의미와 권한, 충전 목표.
- [Android EvChargeState](https://developer.android.com/reference/android/car/hardware/property/EvChargeState): 상태 코드.
- [Pleos getEvChargeSwitchState](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/get-ev-charge-switch-state): 본문 텍스트 접근 불가.

실차, 충전 진행 상태 전환, 충전 제어, 차계부 확정·결제 검증은 수행하지 않았다.

