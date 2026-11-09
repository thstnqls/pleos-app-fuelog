# EV 기록 및 F01 차량 앱 연결 (2026-11-08)

EV 후보 저장·주변 충전소 참고요금 계산의 최신 계약은 [EV 충전 연동](ev-charging-integration.md)을 따른다. 아래 F01은 내연기관 주유소용 계약이다.

## 전기차 C04

현재 사용자를 확인하고 앱에서 기록을 시작한 구간에 적용한다. 화면이 보이는 동안 3초 주기로 확인하며 백그라운드 서비스나 시동 기반 자동 시작은 추가하지 않았다. D/R의 최초 유효 배터리를 기준으로 유지하고 P에서 비교한다. P 구간의 추가 증가도 같은 후보 ID로 갱신한다. 다음 D/R 구간은 새 기준을 잡는다. P만 관측한 경우 과거 기준값을 추측하지 않는다.

AAOS `GEAR_SELECTION`과 `EV_BATTERY_LEVEL`을 우선 사용한다. Wh 미수신이면 Pleos SOC capability 확인 후 %와 AAOS 완충 용량으로 환산한다. 용량은 `EV_CURRENT_BATTERY_CAPACITY` 우선, 미지원 시 `INFO_EV_BATTERY_CAPACITY`를 사용한다. 서로 다른 계산 출처/용량, 앱 이탈, 시간 초과, 권한/연결 실패 사이의 값을 연결하지 않는다. N/알 수 없는 기어를 거친 구간도 보수적으로 연결하지 않는다.

- Wh → kWh: Wh / 1000
- SOC → kWh: SOC / 100 × 완충 Wh / 1000
- 증가: P kWh − 기준 kWh. 양수일 때만 `CANDIDATE`로 저장한다.
- 소모량: D/R 기준 kWh − P kWh. 0 이하/누락/증가 포함 구간은 전비 계산 불가.
- 전비: 유효 거리 / 유효 소모 kWh
- 비용 함수: 유효 소모 kWh × 검증된 `chargeUnitPriceWonPerKwh`. 현재 단가 수집 API가 없어 실기록에서는 금액 미표시.

배터리 증가는 회생제동·온도·SOC 보정으로도 발생할 수 있다. 충전/결제 완료로 확정하지 않는다. 이는 배터리에 저장된 에너지의 변화이며 충전기 계량 전력량이나 충전 손실 포함 결제량을 보장하지 않는다.

새 EV 기록은 `noBackupFilesDir/ev-recordings.bin`에 AES-GCM으로 저장한다. 기존 운행 SharedPreferences에는 새 배터리 필드를 추가하지 않는다. 복구 시 후보는 보존하고 이전 실시간 기준값은 재사용하지 않는다. 기존 수동 종료·모바일 전달 흐름을 유지한다.

### 서버 확장 필요

기존 `PUT recording-intervals/{id}`에 아래 필드를 보낸다. 충전 후보는 별도 `POST /charge-candidates/sync`로 저장하며, EV 요약은 선택적인 `ev` 객체로 업로드한다. fuelog-backend에 EV 후보 API와 요금표를 구현했다. 모바일 화면 연결과 실차 전체 흐름은 미검증이다.

```json
{
  "evDetected": true,
  "evEndpointsValid": true,
  "evMissing": false,
  "consumedKwh": 4.0,
  "chargedDuringInterval": false,
  "chargeCandidates": [
    {"id": "UUID", "status": "CANDIDATE", "startAt": 1000, "endAt": 2000,
     "basis": "AAOS_WH", "estimatedKwh": 12.0}
  ]
}
```

운행 ID가 부모 키이며 후보 시간은 epoch milliseconds이다. 후보를 가진 구간은 `chargedDuringInterval=true`이다. 가격 없는 후보를 0원 결제로 저장하지 않는다. 원격 성공 응답은 기존 업로드 계약을 따른다.

## F01 (내연기관 주유용)

오피넷의 원/L를 전기차 원/kWh에 적용하지 않는다. PDF 12~13쪽의 `aroundAll.do`, `detailById.do`를 확인했다. KATEC 좌표, 유종 코드, 상세 조회 가격 기준 일시를 요구하며 전기차 충전 요금 API가 아니다.

C07의 차량 위치 보조 동의는 기본 꺼짐이다. 기존 FUSED_LOCATION 권한이 없으면 래퍼가 실패 처리하고 권한 팝업을 자동 실행하지 않는다. 인증된 `connection/settings` 응답에서 다음 메타데이터가 필요하다.

```json
{"pleosCoordinateSystemVerified": false, "fuelType": "B027"}
```

`pleosCoordinateSystemVerified=true`는 서버/운영자가 대상 SDK·기기 좌표계를 검증한 경우에만 제공해야 한다. 차량 앱에서 임의로 활성화하는 설정은 없다. 미확인 또는 유종 불명이면 검색하지 않는다. 서버 설정 새로 확인 전에는 보수적으로 검색 비활성 상태다.

최초 % 증가 후보 생성 시 차량의 새 위치 업데이트를 한 번 요청한다. 10초 제한·측정 시각·범위·이동 여부를 확인한다. 위치는 메모리에만 보유하며 후보 종료/앱 이탈/사용자 전환/동의 철회/최종 성공·실패에 폐기하고, 최대 24시간 후 폐기한다. 원래 주유 후보는 유지한다. 증가가 6초간 새 최고값 없이 유지되면 안정으로 간주하고 후보를 먼저 동기화한다. 서버 수신 확인 후 위치 증거를 전송한다.

`POST fuel-candidates/{id}/station-search` 인증된 HTTPS 본문:

```json
{
  "candidateId": "UUID", "latitude": 37.0, "longitude": 127.0,
  "measuredAt": "ISO-8601", "receivedAt": "ISO-8601",
  "source": "PLEOS_CURRENT_UPDATE", "fuelType": "B027",
  "locationOperationId": "station-search:UUID"
}
```

응답은 사용자 요청의 계약대로 `candidateId`, 증가한 `revision`, `searchStatus`, `locationMeasuredAt`, `stations[]`를 사용한다. C05는 상위 3개 후보와 후보별 유종·거리·유효 가격·가격 기준 시각을 표시한다. `VALID`라도 현재 시점 기준 24시간 이상 오래된 가격, 0/누락 가격, 불명 유종, 미래 일시는 사용하지 않는다. 선택된 주유소 ID와 유효 가격 근거가 서버에서 돌아온 경우만 추정 L × 게시 원/L를 표시한다. 선택 전 통합 금액을 만들지 않는다. 자동 검색은 최초 포함 최대 3회 시도하며 늦은 결과의 후보 ID·revision·현재 사용자·상태를 확인한다.

### 서버에 남은 필수 작업

- 설치/후보 소유권·유종·좌표·신선도 재검증, 검증된 좌표계 → KATEC 변환
- `aroundAll.do` 1km → 결과 없으면 3km 한 번, 거리순 상위 3곳
- `detailById.do` 같은 유종의 PRICE/TRADE_DT/TRADE_TM, 가격 기준 시각·조회 시각 보존
- 후보/위치 작업 ID 멱등성, 캐시·429 대응·유한 재시도
- 원시 좌표 목적 제한, 성공/실패 후 24시간 내 삭제 및 동의 철회/후보 삭제 처리
- 모바일 주유소 선택·확인·기각과 revision 일치

현재 차량 앱이 종료되면 위치 작업을 재개하지 않으며 위치 없는 후보를 보존한다. 서버에 이미 전송된 좌표의 삭제는 서버·모바일 동의 철회 계약의 구현이 필요하다. 이 저장소의 로컬 동의 철회만으로 서버 삭제 완료를 주장할 수 없다.

## C04 수집·전력비 연결 수정 (2026-11-09)

운행 기록기도 Pleos Vehicle.initialize()를 호출한다. 차량 기본정보의 독립 거리 표시와 이번 운행 거리 수집은 별도이므로 기록 시작 후 유효한 누적거리 샘플 두 건이 필요하다. 연결·설정에서 AAOS CAR_ENERGY 권한을 요청할 수 있으며, 운행 요약에는 권한 부족·D/R 시작 배터리 대기·P 종료 배터리 대기·수신 누락을 구분해 표시한다. 배터리 권한이 있어도 기기 property 지원이 필요하다.

전력비는 기록 시작 시 동일 사용자의 최근 CONFIRMED 충전 후보에 있는 주변 사업자 참고요금 평균을 고정해 사용한다. 해당 후보 ID와 시각을 표시하며 현재 위치의 요금이나 실제 결제 단가로 표현하지 않는다. 하나의 출력 구간이면 소모 kWh × 평균 원/kWh를 로컬·서버에서 계산한다. 여러 출력 구간이면 차량에서는 구간별 참고 금액을 표시하고 서버에 단일 금액을 보내지 않는다. 후보 미확인·가격 없음·배터리 누락에서는 금액을 생성하지 않는다. 이전 버전 기록에는 사용자별 가격 근거가 없어 새 기록부터 적용된다.

현재 수집은 앱 화면이 활성화된 동안의 수동 기록이다. 백그라운드 연속 수집과 기존 누락 데이터 복원은 제공하지 않는다.
