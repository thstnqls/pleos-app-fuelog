# C07 연결·설정 — 백엔드 인계 (2026-11-06)

## 현재 구현

사용자 연결 해제와 차량 등록 초기화를 별도 액션으로 제공한다. 요청 종류와 고정 operationId를 로컬에 먼저 기록하고 개인 화면을 잠근다. 진행 중 운행은 원래 소유자의 이력으로 보존한 후 세션 표시명·사용자 ID·만료를 정리한다. 인증 저장소의 접근/갱신 토큰과 로그인 진행 상태도 삭제한다. 미전송 운행/주유 기록은 삭제하거나 새 사용자에게 귀속하지 않는다.

후보 조회, 주유/정비 요청을 취소하고 메모리 캐시를 비운다. 운행 요약은 로컬 잠금 상태에서 열리지 않으며 주유·개인 표시도 사용자 확인을 요구한다. 잠금과 해제 요청은 재시작 후 유지한다. 서버 실패는 로컬 잠금과 구분하여 표시하고 같은 operationId로 수동 재시도한다. 자동 백그라운드 재시도는 미구현이다. 관련 로컬 기록/해제 상태는 클라우드 백업과 기기 이전에서 제외한다.

서버 완료 후에도 개인 화면 잠금은 유지하며 새로운 C03 사용자 증명 성공 시 해제한다. 설치 자격 증명은 서버 재시도와 재등록용으로 보존하며 사용자 접근 토큰과 구분한다.

## 1. POST /connection/settings

설치 Bearer 인증. 요청은 공통 관리 envelope `{kind:"", operationId:""}`이며 조회에만 사용한다. 응답:

```json
{
  "deviceAlias": "내 휴대폰",
  "syncStatus": "PENDING",
  "lastSyncedAt": 1793926800000,
  "features": {
    "fuel": "AVAILABLE",
    "distance": "PERMISSION_REQUIRED",
    "location": "TEMPORARY_FAILURE"
  },
  "recommendationRegistered": true,
  "recommendationConsented": true,
  "recommendationVerified": true,
  "recommendationEnabled": false
}
```

기능별 enum: AVAILABLE / UNSUPPORTED / PERMISSION_REQUIRED / TEMPORARY_FAILURE / UNKNOWN. 일반 UI는 오류 코드를 출력하지 않고 원인·해결 행동으로 변환한다. 서버가 실제 기기 가용성을 모르면 UNKNOWN을 반환해야 한다. 현재 앱의 로컬 SDK 상태 업로드 연결은 미구현이며 아래 보고 API가 필요하다. 시간은 epoch milliseconds. syncStatus=SYNCED는 서버가 미전송 큐 완료를 확인한 경우만 반환한다.

등록·동의·기능 검증이 모두 true이고 사용자 확인까지 완료된 경우에만 추천 설정 항목을 노출한다. 추천은 사용자 확정/운행 소유자 자동 변경을 의미하지 않는다.

## 2. POST /connection/disconnect

설치 Bearer 인증. 요청:

```json
{"kind":"USER","operationId":"persisted-uuid"}
```

kind는 USER 또는 VEHICLE. 응답:

```json
{"operationId":"persisted-uuid","status":"PENDING"}
```

status=COMPLETED만 해제 완료로 취급한다. 차량 초기화 승인 대기는 AWAITING_ADMIN 등을 반환할 수 있다. 동일 operationId 반복 호출은 기존 작업을 진행/조회하며 대상 사용자·차량·등록 버전을 처음 요청 때 고정해야 한다. 새 현재 사용자 세션을 매번 해제 대상으로 삼으면 안 된다.

USER: 해당 설치의 기존 운행 사용자 동의/세션과 관련 개인 접근 권한을 원격 폐기한다. 차량 등록과 기록 소유권은 유지한다. 별도 Supabase 세션도 사용했다면 서버가 설치에 연결된 세션을 추적해 폐기해야 한다. 앱은 개인 토큰을 이미 삭제했으므로 재시도 인증에는 설치 자격 증명을 사용한다.

VEHICLE: 서버가 관리자 재인증과 미전송 기록 처리 동의를 확인하기 전에는 절대 COMPLETED를 반환하지 않는다. 단순 설치 인증이나 클라이언트 kind만으로 등록을 삭제하면 안 된다. 완료 시 앱은 C01 재등록 요청 키를 새로 만들고 기존 QR를 폐기한다. 설치 자격 증명을 그대로 쓰는 재등록 계약이다. 자격 증명까지 폐기할 경우 별도 재프로비저닝 API를 합의해야 한다.

원자적으로 처리할 항목: 등록 버전에 연결된 후보·모바일 동의 폐기, 신규 수집 귀속 차단, 미전송 기존 소유자 기록 보존 정책, reset operation 완료 기록. 재시도에 같은 완료 결과를 반환한다. DB UNIQUE(installationId, operationId), 같은 키에 다른 kind는 409.

## 추가 필요 API

| API | 역할 / 요구사항 |
| --- | --- |
| GET /mobile/connection-operations | M14에서 관리자 승인 대기 목록 조회. 서버가 관리자에게만 노출 |
| POST /mobile/connection-operations/{operationId}/approve | 최근 관리자 재인증 증명, registrationVersion, pendingRecordsPolicy=RETAIN_ORIGINAL_OWNER. 일회용 증명·만료·관리자 권한 재검사 |
| POST /mobile/connection-operations/{operationId}/reject | 요청 기각. 차량은 로컬 잠금을 유지하고 처리 안내. 현재 차량 UI에서 요청 취소/복구는 미구현 |
| PUT /mobile/vehicles/{vehicleId}/recommendation | M04 동의 변경. 등록·기능 검증 확인 후 변경. 설정 조회에 반영 |
| PUT /installation/capabilities | 차량 앱의 기능별 지원/권한/일시 실패와 observedAt 보고. 오래된 결과는 UNKNOWN. 추후 SDK 진단 상태를 이 API에 연결 |
| GET /installation/sync-status | 원래 소유자별 미전송 큐 상태와 마지막 승인 시각. 다른 사용자의 데이터/금액은 노출하지 않음 |

## 오류 및 검증

401/403: 원격 처리 완료로 표시하지 않고 로컬 잠금 유지. 409: operation 대상/버전 재확인. 429/5xx/timeout: 같은 키로 재시도. COMPLETED 응답은 operationId 일치 필수. 서버와 모바일이 없는 현재 상태에서는 관리자 재인증 및 원격 폐기를 실제 검증하지 못했다.

필수 통합 검증: 오프라인 사용자 해제→뒤로가기/프로세스 재시작에서 개인 상세 숨김, 같은 키 재시도 한 건, 늦게 도착한 개인 API 응답 폐기, 관리자 아닌 초기화 승인 거부, 미전송 기록의 소유자 보존, 초기화 완료→C01, M04 추천 동의 해제 반영. 현재 Android 빌드/기존 단위 테스트를 수행하며 기기 재시작·서버/모바일 왕복 검증은 후속 대상이다.
