# EV 후보와 주변 충전요금 추정

2026-11-11 C04 운행 비용 변경: P단에서 기록을 먼저 저장하고 기존 위치 권한이 있을 때만 주변 3개 사업자의 **완속 기준 평균**으로 총 비용 한 건을 저장한다. 나중에 확인하면 운행일 요금을 적용하고 실제 확인 시각을 별도 저장하며, 최초 저장 후 재계산하지 않는다. 화면에는 충전소 3곳을 나열하지 않는다. [EV 기록 문서](ev-recording-f01.md#c04-완속-평균-비용-최초-저장-2026-11-11)를 따른다. 아래 출력별 시나리오는 충전 증가 후보 API의 계약이다.

2026-11-08 구현. 차량 앱의 배터리 증가를 확인 전 차계부 후보로 보존한다. 공공데이터 API는 위치·운영사·출력용이며 요금 필드는 없다. 사용자 제공 한국환경공단 OpenAPI 활용가이드 v1.25의 getChargerInfo/JSON 명세로 구현했고 실제 키로 10건 조회(resultCode=00)를 확인했다.

## 설정과 호출 구조

서버 작업 디렉터리의 `.env`, `.env.local`을 Spring properties로 읽는다. `.env.local`이 우선한다. 프로세스 환경변수로도 설정할 수 있다. 인증키를 앱·응답·로그에 보내지 않는다.

```properties
DATA_ENDPOINT=https://apis.data.go.kr/B552584/EvCharger
DATA_KEY=인증키
```

DATA_ENDPOINT는 공식 도메인의 기본 경로 또는 getChargerInfo 전체 경로를 사용한다. 서버는 HTTPS로 호출하고 RAW/URL-encoded 키를 모두 처리한다. getChargerInfo를 9999건 단위, 최대 100페이지·5분으로 제한해 읽는다. 최초 5초 후 및 12시간마다 갱신한다. 전체 조회가 성공했을 때만 DB 카탈로그를 트랜잭션으로 교체한다. 일부 페이지 실패 시 기존 스냅샷을 보존하고, 24시간 지난 카탈로그는 추정에 사용하지 않는다. 요청 실패 메시지에는 키가 포함될 수 있어 기록하지 않는다.

Pleos 좌표계는 API 이름만으로 확정하지 않는다. 대상 SDK/기기의 위경도가 WGS84임을 운영자가 검증한 뒤에만 아래 값을 설정한다. 설정 전에는 후보·충전량을 보존하고 `COORDINATE_SYSTEM_UNVERIFIED`를 반환한다.

```properties
PLEOS_COORDINATE_SYSTEM=WGS84
```

인증키 또는 이 설정을 소스에 넣지 않는다. 기존 Flyway V1~V13은 변경하지 않고 V14로 EV 테이블, V15로 참고요금표를 추가했다. 운영 DB에는 서버 재시작 시 마이그레이션이 적용되며 이번 작업에서 배포·운영 DB 변경은 수행하지 않았다.

## 초기 요금표와 사업자 선정

실제 이용횟수 상위 순위로 단정하지 않는다. 현대자동차그룹 2026-01 보도자료는 채비를 환경부 제외 급속 충전소 수 1위, GS차지비를 완속 충전소 수 1위로 명시한다. 이 인프라 규모와 공공 충전망을 근거로 PI(GS차지비), CV(채비), ME(기후에너지환경부), KP(한국전력)를 초기 대상으로 선택했다. 사업자 코드는 제공된 가이드의 기관 아이디 표와 대조했다.

| 사업자 | <30kW | 30~<50 | 50~<100 | 100~<200 | ≥200 |
| --- | --- | --- | --- | --- | --- |
| GS차지비 | 295 | 307 | 325 | 345 | 345 |
| 채비 | 289 | 315 | 410 | 430 | 430 |
| 기후에너지환경부 | 295 | 307.2 | 325.6 | 348.4 | 393.1 |
| 한국전력 | 295 | 307.2 | 325.6 | 348.4 | 393.1 |

단위는 원/kWh, 일반 회원 참고요금이다. 로밍/구독/신용카드 할인·특수 현장요금·실제 결제요금으로 확정하지 않는다. tariff version은 `reference-2026-11-08`이며 원문 갱신일과 확인일을 구분한다. 원문 갱신일이 없는 채비는 NULL로 두고 확인일을 실제 갱신일로 추측하지 않는다. 최초 확인 유효기간은 30일이며 만료 시 자동 계산에서 제외한다. 새 버전 행을 추가하고 원문과 대조해 갱신한다. 기존 후보에는 적용 당시의 버전·단가·출처·확인일 스냅샷을 보존한다.

근거:
- https://www.hyundaimotorgroup.com/ko/news/CONT0000000000196566
- https://www.gschargev.co.kr/pricing.html (2026-08-01 기준, 2026-11-08 확인)
- https://chaevi.com/kr/service/price.php (2026-11-08 확인)
- https://ev.or.kr/nportal/evcarInfo/initEvcarChargePriceV2.do (환경부 갱신일 2026-07-31 / 한국전력 2026-09-10, 2026-11-08 확인)
- https://www.data.go.kr/data/15076352/openapi.do

## 평균과 급속 구분

차량 위치는 최초 증가 시 1회 받은 Pleos 업데이트만 사용한다. 사용자별 C07 동의·기존 위치 권한이 필요하며 모바일 GPS나 마지막 위치로 대체하지 않는다. 감지 뒤 이동이 확인되거나 10초 내 위치를 받지 못하면 후보만 남긴다. 증가가 6초 이상 유지되면 보강한다. 원시 좌표는 차량 메모리에서만 유지하고 최대 2분 안에 버리며 서버 요청 본문에서만 소비한다. 원시 좌표를 DB·로그·전송 큐에 저장하지 않는다.

충전 후보 추정은 저장된 카탈로그에서 충전 당시 위치 주변 1km의 완속(SLOW, 커넥터 정격 30kW 미만) 충전소를 찾고, 없으면 3km로 넓힌다. 사업자당 가장 가까운 한 곳, 최대 세 사업자의 참고 요금을 평균한다. 한 사업자만 확인되어도 완속 참고금액을 제공한다. 단가는 충전 시작 시점에 유효·확인된 요금만 사용한다. 위치·요금이 없으면 금액 미확인으로 남기며 현재 위치로 과거 기록을 보충하지 않는다.

기존 범용 요금 비교 API는 SLOW <30, MEDIUM 30~<50, FAST_50 50~<100, FAST_100 100~<200, ULTRA ≥200 구간을 유지한다. 충전 후보의 `/estimate`는 SLOW 시나리오만 반환하며 실제 사용 충전기를 확정하지 않는다.

공용/동시 충전기의 커넥터당 요금 구간을 입증할 수 없으므로 method=단독만 사용한다. 삭제·이용제한·미확인 출력은 제외한다. GS 과금형 3kW와 채비 휴게소 등 확인된 별도 요금 대상도 일반 표에서 제외한다. 그 외 현장 프로모션·특수 요금은 모두 구별할 수 없으므로 화면과 계약에 참고 추정임을 명시한다.

`평균 단가 = 단가 합 / 사업자 수`, `추정 원 = 배터리 증가 kWh × 평균 원/kWh`. 평균은 소수 6자리, 금액은 원 단위 HALF_UP. 충전 손실을 임의 비율로 더하지 않는다. 실제 충전기 계량량·결제량과 다를 수 있다.

## 차량 API

설치 Bearer가 필요하다. 모든 응답은 Cache-Control=no-store.

- `POST /charge-candidates/sync`: `{id,intervalId,owner,startAt,endAt,estimatedKwh,basis}`. 시각은 epoch ms, 양은 0 초과 1000 이하. intervalId는 해당 설치의 서버 운행 ID. 서버에 고정된 운행 소유자·차량 멤버십·시간 구간을 검증한다. 같은 id/시각은 같은 후보이며 다른 소유자/구간에 재사용할 수 없다.
- `GET /charge-candidates/{id}`: 현재 검증 사용자에게 귀속된 후보만 반환.
- `POST /charge-candidates/{id}/estimate`: `{candidateId,locationOperationId,latitude,longitude,measuredAt,receivedAt,source}`. 시각은 ISO-8601. operationId=`charge-estimate:{id}`, source=PLEOS_CURRENT_UPDATE. 최초 증가 시각~10초 안의 위치이고 수신 후 2분 이내여야 한다. 같은 작업 재전송은 기존 계산을 반환한다. CONFIRMED/REJECTED 후보는 수정하지 않는다.

응답 View는 `{id,intervalId,owner,status,revision,startAt,endAt,estimatedKwh,basis,estimateStatus,scenarios[],correctedKwh,correctedAmount,correctedPlace}`.

scenarios 항목은 `{powerBand,basis,averageWonPerKwh,estimatedAmount,stations[]}`. stations는 후보 ID·상호·사업자·거리·출력·단가·버전·출처 URL·원문 갱신일·확인일만 포함하고 사용자 좌표를 포함하지 않는다. 새로운 증가 관측은 같은 후보의 kWh·revision을 갱신하되 고정한 단가 스냅샷으로 금액만 다시 계산한다.

estimateStatus는 LOCATION_UNAVAILABLE / COORDINATE_SYSTEM_UNVERIFIED / INSUFFICIENT_COMPARABLE_STATIONS / REFERENCE_ESTIMATE / POWER_BAND_SELECTION_REQUIRED.

## 모바일 API

Supabase 로그인 JWT와 후보 소유자·현재 차량 멤버십이 필요하다.

- `GET /mobile/charge-candidates`: 내 충전 확인함 최신 100건.
- `GET /mobile/charge-candidates/{id}`: 후보 상세.
- `PATCH /mobile/charge-candidates/{id}`: `{operationId,expectedRevision,action:CONFIRM|REJECT,kwh?,amount?,place?}`. 사용자 보정값은 원래 감지/참고 추정과 별도 보존하며 동일 operationId·동일 본문은 같은 응답. revision 충돌은 409.

별도 모바일 화면 구현은 이 저장소에 없다. 기존 `/mobile/inbox`는 운행·주유 확인함 계약을 유지하고, 충전 확인함은 위 전용 목록으로 제공한다.

## C04 운행 업로드

기존 `PUT /recording-intervals/{id}`에 선택적인 `ev:{detected,endpointsValid,missing,chargedDuringInterval,consumedKwh}`를 추가한다. 거리와 독립적으로 EV 끝점·누락·사용자 변경을 검사한다. `GET .../summary`는 `energyConsumedKwh`, `energyEfficiencyKmPerKwh` metric을 추가 반환한다. 충전 후보의 수명과 재시도는 별도 API에서 처리한다. 과거 연료 DTO 생성자·기존 클라이언트 호환성을 유지한다.

## 차량 충전 기록 그래프

충전 탭에서 충전량·금액을 전환하고 기존 운행 그래프와 같은 일·월·년 구간을 선택한다. 충전 시작일로 집계하며 기각된 후보와 중복 ID는 제외한다. 충전량은 모바일 확인 보정값이 있으면 우선한다. 금액 그래프는 확인된 완속 단가 스냅샷 × 충전량을 원 단위 HALF_UP으로 계산하며 실제 결제금액과 구분한다. 미확인 금액은 0원으로 집계하지 않고 누락 건수를 표시한다.
