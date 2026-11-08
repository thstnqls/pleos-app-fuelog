# EV C04 / F01 변경 검토 — 2026-11-08

기준: 현재 HEAD + 사용자의 기존 미커밋 변경을 보존한 작업 트리. 전체 앱 감사가 아닌 이번 변경 범위 검토다. 대상은 국내 차량용 앱, compile/target SDK 36, minSdk 28, Vehicle 2.3.2 기반 기존 API 사용이다. 실제 차량·펌웨어·심사 카테고리와 주행 중 프로세스 지속 여부는 미확인이다.

## 정책 적용과 근거

2026-11-08 아래 공식 페이지의 본문 텍스트를 열람했다. 이미지·비공개 사양·실차 동작은 미확인이다.

| 범위 | 판단 | 근거·관련 절 |
| --- | --- | --- |
| 정책 목록 | 확인 | [intro](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro): 9개 정책 및 디자인 가이드 링크 |
| 주행 UX | 관찰 범위 적합 / 실차 검증 필요 | [주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide): CarUxRestrictions 연동. 기존 UI 제한 유지, 기어로 UI 접근 허용을 대체하지 않음. 센서 수집으로 화면 전환·팝업을 띄우지 않음 |
| 가용성 | 검증 필요 | [API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability): capability, AAOS 실제 설정·접근 확인. 새 AAOS 읽기는 config/access/status를 확인하고 SOC fallback은 SDK capability를 확인 |
| 생명주기 | 관찰 범위 적합 / 제품 범위 제한 | [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model): 포그라운드/백그라운드. onStop 수집·위치·통신 중단, 재진입 시 이전 배터리 끝점을 연결하지 않음 |
| 개인정보 | 새 저장 경로 관찰 범위 적합 / 서버 검증 필요 | [보안·개인정보](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy), [앱 보안 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide): 목적 동의·최소 수집·암호화·HTTPS. 새 EV 저장은 기존 AES-GCM 저장소 사용, 위치는 메모리 한정·인증 본문 전송 |
| AAOS 의미·권한 | 명세 확인 / 실차 미확인 | [VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds): GEAR_SELECTION/CAR_POWERTRAIN, EV_BATTERY_LEVEL(Wh), EV_CURRENT_BATTERY_CAPACITY(현재 사용 가능 Wh), INFO_EV_BATTERY_CAPACITY(공칭 Wh) |

## 남은 장애 요인

### P1 — 주행·충전 중 앱이 화면에서 사라지면 끝점 수집 불가

판단: 조건부 기능 제한. `HomeRecorder.pause`, `CarHomeActivity.onStop`은 기존 정책대로 전경 수집을 중단한다. DO 미선언 환경의 플랫폼 앱 차단이나 프로세스 종료를 우회하지 않았다. D/R 기준·P 비교는 앱 기록 구간 중 관측된 값에만 적용된다.

수정 방향: 실차에서 D/R→P를 수신하는지 먼저 검증하고, 백그라운드 기록이 제품 요구이면 승인된 수집 수명과 실행 구조를 별도 설계한다. DO 선언만 추가하지 않는다. 완료 기준은 화면 이탈/제한 전환/강제 종료 시 누락 표시와 잘못된 충전 후보 미생성이다.

### P1 — 서버·모바일 차계부 및 F01 오피넷 서비스 미포함

판단: 연동 검증 필요. 이 저장소에 서버/모바일 구현이 없다. `RecordingPayloads.upload`는 EV 후보를 운행 업로드에 포함하고 `FuelStationEnrichment`는 위치 증거 계약을 호출한다. 서버가 EV 필드를 수용·중복 없이 저장하는지는 미확인이다. F01 좌표계 검증은 기본 false이며 운영 검증 없이 검색하지 않는다.

수정 방향과 완료 조건은 [서버 계약](../ev-recording-f01.md)의 목록을 따른다. 후보 소유권, 좌표계·KATEC 변환, PRICE/TRADE_DT/TRADE_TM, 1→3km 검색, 멱등성, TTL, 모바일 확인을 서버 통합 테스트로 확인해야 한다. 서버로 전송한 위치의 동의 철회 삭제는 서버 계약이 필요하다.

### P2 — 배터리 증가와 충전·실결제량의 차이

판단: 조건부 측정 한계. `EvRecording.sample`은 요청대로 양수 증가를 후보로 저장하지만 회생제동·온도·SOC 보정을 충전과 구별할 충전기 증거가 없다. 화면은 충전 추정·사용자 확인 전으로 표시한다. 충전기 계량값·결제 증거 없이 확정 기록으로 승격하지 않는다.

### P2 — 전기차 단가 미제공

판단: 기능 입력 미확인. 제공된 오피넷 PDF 12~13쪽은 주유소 유종별 원/L이다. 전기 원/kWh와 혼용하지 않는다. EV 단가 API/사용자 확인 계약이 연결될 때까지 금액을 비워 둔다. SOC만 제공되고 용량도 미지원이면 kWh 계산은 불가하며 이를 0으로 기록하지 않는다.

## 검증

단위 테스트: Wh 환산, %와 용량 환산, D/R 최초 기준 유지, P 증가 후보 ID 유지·값 역행 방지, 복구·이탈·출처 변경 단절, EV 업로드 후보 유지, 6초 안정 조건, 위치 범위·시각·좌표계 차단, 오래된/0/미래 가격 차단. 기존 단위 테스트 포함 실행.

빌드·lint·기기 테스트 소스 컴파일 통과. 실차 권한/AAOS 속성·UX 전환, SDK null/지연 콜백 통합 동작, 위치 권한 거부/이동 중 응답, 오피넷 장애/429, 실제 서버 차계부·모바일 중복 방지, CPU/RAM·물리 UI 치수는 미실행이다. 빌드 성공을 정책 심사 통과로 해석하지 않는다.
