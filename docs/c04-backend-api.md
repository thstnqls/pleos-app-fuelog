# C04 운행 요약 — 백엔드 인계 (2026-11-06)

## 구현 현황

Android는 앱 기록 구간의 고정 intervalId를 운행 ID로 사용한다. C04는 조회만 하며 운행을 생성하지 않는다. 종료 시 로컬 이력에 ID당 한 건을 저장한다. 모바일 버튼은 동일 ID를 키로 로컬 전송 대기함에 기록한다. HTTP 전송 작업자는 아직 없으므로 모바일 서버 확인함에 반영되었다고 표시하지 않는다.

거리 샘플이 2개 이상이면 수신 구간의 거리를 표시한다. 연료와 단가는 현재 수집되지 않아 계산 불가로 표시한다. 계산기는 60km/4L/1700원에서 15km/L, 6800원을 반환하며 0L·누락·주유·사용자 변경 경계는 계산에서 제외한다. 양 끝 연료 샘플은 같은 연속 기록 구간이어야 한다.

새 프로세스에서 종료되지 않은 구간을 복구하면 마지막 샘플(없으면 마지막 저장) 시각을 추정 종료 시각으로 사용하고 누락으로 표시한다. 정상 회전은 같은 프로세스에서 구간을 유지한다. 종료 시 C04로 강제 이동하지 않는다. 홈의 운행 요약으로 확인한다. 다음 정차 앱 진입에서 저장된 운행 요약 확인을 제안하며, 선택 전에는 화면을 강제로 이동하지 않는다.

## 필요한 API

| API | 역할 | 입력/응답 |
| --- | --- | --- |
| PUT /recording-intervals/{tripId} | 오프라인 운행 멱등 업로드 | 아래 JSON. 동일 설치+tripId 유일. 성공은 tripId, revision, syncedAt 반환 |
| GET /recording-intervals/{tripId}/summary | 권한 검사 후 서버 요약 조회 | 거리·연료·비용 각각 value/status/reason, priceSource, 품질, revision, syncedAt |
| PUT /mobile-inbox/trips/{tripId} | M06 확인함 항목 멱등 생성 | operationId=`mobile-inbox:{tripId}`, tripRevision. 응답 inboxItemId, tripId, deliveredAt. 운행 업로드 완료 후 호출 |
| GET /mobile/inbox | 로그인 사용자의 확인함 조회 | cursor 기반 페이지. 운행 소유권 검사 후 최소 요약과 tripId |
| PATCH /mobile/inbox/{itemId} | 모바일 읽음 처리 | readAt. 사용자별 처리 |

모든 API는 HTTPS. 차량은 설치 인증 + 서버 검증된 운행 사용자 증명, 모바일은 로그인 인증이 필요하다. 임의 subjectId를 신뢰하지 않는다. 미확정 구간은 개인 확인함에 넣지 않고 차량별 미귀속 상태로 보관한다. 타인의 최근 운행을 대신 반환하지 않는다.

## 업로드 JSON 예

```json
{
  "tripId": "stable-client-uuid",
  "revision": 1,
  "source": "manual_app",
  "userConsentId": "server-verified-consent-or-null",
  "startedAt": "2026-11-06T01:00:00Z",
  "endedAt": "2026-11-06T02:00:00Z",
  "endEstimated": false,
  "distanceKm": 60.0,
  "distanceValid": true,
  "startFuelLiters": 40.0,
  "endFuelLiters": 36.0,
  "fuelEndpointsValid": true,
  "missing": false,
  "refueled": false,
  "ownerChanged": false,
  "unitPrice": 1700,
  "currency": "KRW",
  "priceSource": "사용자가 확인한 주유 단가",
  "priceObservedAt": "2026-11-06T00:00:00Z",
  "priceValid": true
}
```

이 예는 계산 검증용이며 실제 앱에 예시 연료값을 주입하지 않는다. 현재 로컬 시각은 epoch milliseconds이며 HTTP 구현 시 UTC ISO-8601로 변환한다. 연료·단가 미수집 항목은 null과 unavailable reason으로 전송한다. 연료 샘플의 observedAt·source·단위·품질 및 주유 이벤트도 후속 수집 모델에 추가해야 한다.

## 멱등성 / 실패 처리 / 검수

- DB UNIQUE(installationId, tripId), 확인함 UNIQUE(recipientId, tripId). 동일 revision·동일 내용 재시도는 기존 결과 반환, 다른 내용은 409. 높은 revision만 조건부 갱신.
- 서버 수신 확인 전에는 앱이 synced=true로 바꾸면 안 된다. timeout 후 동일 tripId/revision으로 재시도. 401/403은 인증 갱신 또는 전환 보류, 429/5xx는 backoff.
- 서버는 소유자와 구간 경계를 검증하고 비용을 재계산한다. 주유·사용자 전환 경계가 있는 구간을 합산해 연비를 계산하지 않는다.
- 소모량>0, 유효 거리, 양 끝 연료값 및 단가 조건을 각각 검사. 연료 계산 실패에도 유효 거리 반환. 0L은 계산 불가이며 0원으로 표시하지 않는다.
- UI 재진입 10회에도 업로드 1건, 응답 유실 재전송에도 운행/확인함 각각 1건, 강제 종료 복구 표시, 다른 사용자 접근 거부를 통합 검증한다.

현재 검증은 계산 단위 테스트와 Android 빌드까지이며 서버 중복 방지·실기기 강제 종료 복구·M06 수신은 통합 검증 대상이다.
