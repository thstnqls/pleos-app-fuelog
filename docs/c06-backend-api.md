# C06 정비 요약 — 백엔드 인계 (2026-11-06)

## Android 구현 범위

C06은 연결된 차량 설치 자격으로 `GET maintenance/summary`를 호출한다. 응답에 포함된 공동 관리 항목 가운데 앞의 2개만 표시하고, 개인 메모·영수증·결제액은 파싱하거나 표시하지 않는다. 화면 진입 및 새로 확인 버튼에서 조회하며 화면 이탈·주행 제한 진입 시 요청을 취소한다. 오프라인·응답 오류는 마지막 성공값을 서버 최신값으로 오인하지 않도록 별도 안내한다.

거리의 최근성 기준은 초기 제품값 **7일**이다. 7일보다 오래된 누적거리는 마지막 유효값과 측정 시각으로 보여 주되 남은 거리 계산에는 쓰지 않는다. 날짜와 거리는 서로 다른 단위이므로 미래의 선도달 조건을 예측하지 않는다. 어느 하나가 이미 도래하면 해당 조건을 안내하고, 아직 둘 다 도래하지 않았으면 '날짜 또는 거리 중 먼저 도달하면 정비 시기'라고 표시한다.

현재 저장소에는 정비 데이터 서버와 휴대폰 M11/M12 앱이 없다. 따라서 실제 정비 등록·수정·삭제와 재계산 완료는 아래 API 구현 후 통합 검증이 필요하다. 차량 화면은 정비 기록을 생성하지 않는다.

## 차량 앱 조회 API

| API | 인증 | 역할 |
| --- | --- | --- |
| `GET /maintenance/summary` | 차량 설치 자격 | 설치에 연결된 차량의 마지막 유효 누적거리와 공동 관리 예정 항목 최대 2개 조회. 서버가 우선순위 정렬과 접근 권한 검사 수행 |
| `PUT /vehicle-odometer/samples/{sampleId}` | 차량 설치 자격 | 검증된 차량 API 원본 누적거리 샘플의 멱등 업로드. 차량·설치 연결 확인, 단위 km·측정 시각·품질 검증. C06의 현재 앱 코드에는 업로더가 없음 |

`GET /maintenance/summary` 응답 예. 값은 계약 설명용이며 앱에 예시값을 주입하지 않는다.

```json
{
  "revision": 8,
  "odometer": { "km": 48200.5, "observedAt": 1793940000000 },
  "items": [
    {
      "id": "maintenance-item-id",
      "name": "엔진오일",
      "lastServiceDate": "2026-01-10",
      "lastServiceKm": 42000,
      "nextDueDate": "2027-01-10",
      "nextDueKm": 52000,
      "basis": "USER"
    }
  ]
}
```

`observedAt`은 Unix epoch milliseconds, 정비 날짜는 `YYYY-MM-DD`, 거리는 km다. 기록이 없는 값은 `null`이다. `basis`는 `USER` 또는 근거가 확인된 `MANUFACTURER`만 사용한다. 제조사 기준의 문서·차종·연식·적용 범위는 서버에 별도로 저장하고 검증되지 않은 공통 주기를 제조사 기준으로 표시하지 않는다. 응답에는 비용, 개인 메모, 정비소, 영수증 URL을 포함하지 않는다. `items`는 반드시 배열이며 빈 배열이 가능하다.

## 휴대폰 M11/M12 CRUD API

| API | 역할 |
| --- | --- |
| `GET /mobile/vehicles/{vehicleId}/maintenance-events` | 권한에 맞는 정비 이력 조회. 공동 항목과 개인 비용·메모 접근을 분리 |
| `POST /mobile/vehicles/{vehicleId}/maintenance-events` | 사용자가 확인한 날짜·항목을 등록. `Idempotency-Key`로 중복 생성 방지 |
| `PATCH /mobile/vehicles/{vehicleId}/maintenance-events/{eventId}` | 과거 날짜·누적거리·항목·공개 범위 수정. `If-Match` 또는 revision으로 동시 수정 보호 |
| `DELETE /mobile/vehicles/{vehicleId}/maintenance-events/{eventId}` | 기록 논리 삭제와 감사 이력 보존. 영향받는 예정 재계산 |
| `GET /mobile/vehicles/{vehicleId}/maintenance-plans` | 항목별 사용자 주기·제조사 근거·공개 범위 조회 |
| `PUT /mobile/vehicles/{vehicleId}/maintenance-plans/{itemId}` | 항목별 거리·기간 주기 생성/수정. 공동 관리 여부 설정 |
| `DELETE /mobile/vehicles/{vehicleId}/maintenance-plans/{itemId}` | 주기 비활성화. 과거 정비 이력은 유지 |

서버는 정비 이벤트 생성·수정·삭제 또는 주기 변경과 **해당 항목의 다음 예정 재계산을 같은 트랜잭션**에서 처리한다. 마지막 정비 기준은 유효한 확정 기록만 선택한다. 거리 또는 날짜가 없는 기록은 해당 축의 계산만 생략한다. 예정 거리 = 마지막 정비 누적거리 + 주기 km, 예정일 = 마지막 정비일 + 주기 기간이다. 방문 추정 이벤트는 확정 기록이 아니며 예정 기준으로 승격하지 않는다. 변경 후 summary revision을 증가시켜 다음 C06 조회에 새 결과가 나오게 한다.

## 실패·검수

- 설치가 다른 차량 ID를 지정해 조회할 수 없도록 차량 ID는 설치 자격에서 서버가 결정한다. 연결 해제 시 즉시 거부한다.
- M11 이력의 공개 범위가 공동이 아니면 차량 응답에서 제외한다. 개인 비용·메모 공개 여부는 정비 항목 공개 여부와 별도로 검사한다.
- 중복 POST, 응답 유실 재시도, 동시 수정, 과거 기록의 날짜·거리 변경과 삭제에서 정비 건수 및 다음 예정이 일치해야 한다.
- 마지막 거리 없음/7일 초과, 날짜만 있는 주기, 거리만 있는 주기, 둘 다 도래한 경우, 오프라인, 주행 제한 진입에서 문구와 요청 취소를 확인한다.
