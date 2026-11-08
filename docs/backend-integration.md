# 장치 API 연결

기준: 제공받은 C01~C07 계약 및 2026-11-07에 내려받은
[배포 Swagger](http://sheetnews.ddns.net:3001/swagger-ui/index.html)의 `/v3/api-docs`.
Swagger에는 C01~C04와 기존 페어링/일부 모바일 API만 있다. C05~C07은 제공된 계약 기준으로 연결했으며 배포 서버 검증이 필요하다.


## 2026-11-08 사용자 유지·차량 기본정보 계약

- 시작 화면은 QR 연결과 차량 기본정보 탭으로 분리한다. 공동 정비는 설치 인증과 차량 등록만 필요하며 사용자 연결을 요구하지 않는다.
- `GET installations/me`는 차량 식별 `vehicle_id`/`vehicle_name`과 사용자 연결 `paired`/`authenticated_user_id`/`authenticated_user_name`을 구분한다.
- 페어링 사용자는 추가 구간 QR 없이 유지한다. 운행 시작 본문의 `subjectId`는 조회한 인증 사용자이며 서버가 일치 여부를 검사한 뒤 구간에 고정한다.
- USER 연결 끊기는 개인 접근과 완료된 페어링을 폐기하지만 차량 등록·공동 정비·기존 기록은 유지한다. 같은 차량으로 새 사용자가 재페어링한다.
- 새 운행 요약은 `userConsentId=null`이며 서버 구간의 고정 소유자를 사용한다. 개인 요약 열람은 현재 사용자와 소유자가 같아야 한다.
- 아래의 구간 QR·candidates·verify·짧은 사용자 세션 설명은 이전 클라이언트 호환용이다. 신규 차량 앱에서는 호출하지 않는다.

## 주소·인증

- `API_BASE_URL`은 서버 루트다. 예: debug `http://sheetnews.ddns.net:3001/`. `/api`를 추가하지 않는다. release에는 HTTPS 주소가 필요하다.
- 신규 설치는 `POST /installations`(인증·본문 없음)로 `installation_id`와 `installation_secret`을 발급받는다. 이 값은 차량 ID가 아닌 설치 ID/인증 정보다. Keystore AES-GCM으로 암호화해 백업 제외 디렉터리에 원자적으로 저장한다. 발급받은 값이 있으면 재발급하지 않는다.
- 이전 버전에서 앱이 생성한 자격 증명만 있는 설치는 기존 연결·미전송 기록을 보존하기 위해 `/connection/requests` 흐름을 유지한다. 신규 설치의 `/installations` 흐름과 인증 헤더·QR 형식을 섞지 않는다.
- 장치 요청의 `Authorization`에는 설치 비밀값만 넣는다. 개인 홈·개인 구간 시작·세션 해제에는 별도 `X-Trip-User-Session: Bearer <scopedToken>`을 사용한다.
- 검증 이후 샘플·요약 조회·모바일 전달에는 `X-Trip-User-Candidate: <candidateId>`를 사용한다. 여기에 `Bearer`는 붙이지 않는다.
- Pleos 앱에는 Supabase 로그인 클라이언트가 없다. 모바일 JWT 로그인은 별도 모바일 앱에서 처리한다. 설치 인증과 운행 큐는 공통 `EncryptedJsonStore`로 보관하며 기존 키·파일 형식을 유지한다.

## 기능별 연결

| 화면/동작 | 요청 | 응답에서 사용하는 값 |
|---|---|---|
| 신규 설치 ID·비밀값 발급 | POST `installations` | installation_id, installation_secret |
| 신규 설치 연결 복원 | GET `installations/me`, `pairing-requests/current` | paired, 현재 QR 상태 |
| 신규 차량 연결 QR | POST `pairing-requests`, GET `pairing-requests/{id}` | id, expires_at, qr; 모바일 승인 후 confirmation_code |
| 차량 화면 최종 확인 | POST `pairing-requests/{id}/confirm` | status=completed일 때만 연결 완료 |
| 이전 앱 생성 자격 증명의 QR | POST `connection/requests`, `connection/requests/status` | requestId, status, remainingSeconds, nonce |
| 홈, 15초 갱신 | GET `vehicle/home` | vehicleAlias, estimatedCost, fuelReviewCount, maintenanceDueCount; 개인 세션 없으면 비공개 |
| 사용자 확인 QR | POST `trip-user/challenges` | challengeId, type, version, proof, expiresAt |
| 개인 홈 승인 확인 | POST `trip-user/session` | status=ACTIVE의 scopedToken, expiresAt |
| 개인 홈 세션 해제 | DELETE `trip-user/session` | 성공 시 개인 세션과 캐시 폐기 |
| 기록 시작 | POST `recording-intervals` | id, localIntervalId. 로컬 ID와 서버 id를 별도 보관 |
| 운행 샘플 | POST `recording-intervals/{id}/samples` | intervalId, batchId, acceptedCount, lastAcceptedSequence |
| 기록 종료 | POST `recording-intervals/{id}/finish` | id, status=finished |
| 이번 구간 사용자 선택 | POST `trip-user/candidates`, `trip-user/verify` | candidates, candidateId, subjectId, expiresAtEpochMillis, 동의·차량 멤버 상태 |
| 사용자 화면 변경 감지 | GET `trip-user/events?cursor=...` | nextCursor, events. 변경 시 후보를 다시 조회 |
| 요약 저장·조회 | PUT `recording-intervals/{tripId}`, GET `recording-intervals/{tripId}/summary` | tripId, revision, syncedAt; Metric/Money의 value와 status |
| 모바일에서 상세 확인 | PUT `mobile-inbox/trips/{tripId}` | tripId, deliveredAt. 수신 확인 후 전달 완료 표시 |
| 주유 확인함 전달 | POST `fuel-candidates/sync` | id, owner, status, revision, 추정량·단가·주유소 메타데이터 |
| 주유소 후보 조회 | POST `fuel-candidates/{id}/station-search` | stationCandidates. 현재 위치를 보내지 않는 조회 |
| 정비 | GET `maintenance/summary` | revision, odometer, 공동 정비 항목(최대 2개) |
| 원본 누적거리 | PUT `vehicle-odometer/samples/{sampleId}` | sampleId, receivedAt |
| 설정·연결 해제 | POST `connection/settings`, `connection/disconnect` | 기능·추천 상태, operationId, COMPLETED/AWAITING_ADMIN |
| SDK 기능 보고 | PUT `installation/capabilities` | 성공 여부. 연료·거리의 실제 수신 여부를 보고; 위치는 UNKNOWN |
| 서버 수신 상태 | GET `sync/status` | lastAcceptedSequence, lastSyncedAt |
| 사용자별 큐 확인 | GET `installation/sync-status` | 별도 상태로 보관. 로컬 업로드 큐나 서버 수신 sequence와 합치지 않음 |

홈·차량 기본정보·정비의 `차량 총 누적거리`는 사용자 연결이나 운행 기록 시작 없이 차량 API에서 직접 읽는다. 화면 이용이 허용되면 1회 조회하고, `누적거리 구독 시작`으로 변경값을 실시간 반영한다. 구독은 명시적 중지·앱 화면 이탈·차량 UX 제한 시 해제한다. 서버 정비 요약의 `서버에 저장된 누적거리`와 구분하며, 직접 조회·구독 자체가 운행 기록이나 서버 정비 이력을 생성하지는 않는다.

`trip-user/revoke`는 consentId를 가진 호출자를 위한 API다. 현재 장치 후보/검증 DTO는 consentId를 제공하지 않아 장치 화면에서 candidateId를 대신 보내지 않는다. 장치의 사용자 해제는 C07을 사용한다. 모바일은 본인의 consentId와 Supabase JWT로 revoke를 호출해야 한다.

## 기록·재시도

1. 홈에서 수동 기록을 시작한다. 시작 요청이 성공하면 서버 id를 얻는다.
2. 현재 구간의 동의 QR을 표시하고 모바일이 동의한 후 후보를 조회한다. 후보가 한 명이어도 verify를 거친다. 사용자를 바꿀 때는 먼저 현재 기록을 종료하고 새 기록을 시작한다.
3. 거리 샘플은 SDK 누적거리 차이만 전송한다. 첫 샘플·수신 실패·역행은 missingReason을 전송한다. 원본 누적거리는 별도 C06 경로에 보낸다.
4. 종료 시 샘플 수신 → finish → 요약 PUT → 요약 GET 순서를 유지한다. 모바일 전달은 이 뒤에 처리한다. 서버가 돌려준 id/배치/sequence/revision을 확인하기 전에는 큐에서 삭제하지 않는다.
5. operationId, localIntervalId, batchId, sequence, revision, sampleId와 요청 본문은 재시도 때 유지한다. 실패한 구간 뒤의 요청은 보류하면서 다른 구간은 전송할 수 있다. C05 실패는 운행 업로드를 막지 않는다.
6. 업로드 큐는 Keystore AES-GCM으로 암호화하고 백업에서 제외한다. 손상 시 덮어쓰거나 삭제하지 않고 기록 시작을 보류한다. 앱 회전 시 같은 수집기/큐를 재사용한다.
7. 401/403이면 개인 홈 캐시와 사용자 화면을 잠근다. 원래 소유자의 기록과 미전송 요청은 보존한다. 충돌·만료 응답도 성공으로 처리하거나 익명으로 바꿔 재전송하지 않는다.
8. 시작 시 scopedToken 유무는 고정한다. 뒤늦은 개인 홈 승인은 이미 시작한 구간을 바꾸지 않는다. C03에서 확인한 candidateId만 이후 샘플 및 해당 구간 요약에 사용한다.

## 모바일 측 연결

이 저장소는 차량 장치 앱이다. 별도 모바일 앱에는 다음 흐름이 필요하다.

- 신규 설치 QR → Supabase 로그인 → `pairing-requests/preview` → `pairing-requests/{id}/intent`(기존 차량 또는 새 차량 이름) → 차량 화면에서 확인 코드 비교 후 `confirm`.
- 이전 앱 생성 자격 증명의 QR → `mobile/connection/inspect` → 기존 차량 선택 → `mobile/connection/approve`.
- 개인 홈 QR → `mobile/trip-user/approve`.
- 현재 구간 QR → `mobile/trip-user/consent`; vehicleId, installationId, intervalId와 QR의 challengeId/proof/operationId가 필요하다.
- `mobile/inbox` 조회 및 읽음 처리, 주유 후보 상세/확인/기각.
- 차량 초기화 승인/기각은 `mobile/connection-operations`와 관리자 최근 재인증으로 처리한다.
- 모바일 정비 CRUD와 추천 동의 설정은 차량 화면에서 실행하지 않는다.

`/installations`·`/pairing-requests`에는 설치 헤더를 사용하고 Bearer/새 연결 QR과 혼합하지 않는다. 사용 중단된 `/connection/reset`은 호출하지 않는다.

## 서버 확인이 필요한 부분

- 신규 설치는 `/installations` 응답의 ID를 C02 개인 홈 challenge 및 모바일 C03 consent에 사용한다. 이전 앱 생성 자격 증명에는 설치 ID가 없을 수 있다. 해당 설치의 공식 ID 조회/이관 경로가 필요하며, 없으면 사용자 QR 발급을 보류하고 `연결 필요` 알림을 표시한다. 기존 설치 비밀값을 바꾸거나 임의 ID를 생성하지 않는다.
- 현재 구간 QR은 서버가 반환한 type/version/challengeId/proof와 서버 intervalId 및 설치 ID를 담는다. 설치 ID 또는 서버 구간 ID가 없으면 발급을 보류한다.
- C05~C07 DTO는 배포 Swagger에서 확인할 수 없다. 주유소 조회는 `locationPermission=false`와 `stationCandidates` 응답을 전제로 한다. 서버의 실제 타입/필드와 대조가 필요하다.
- SDK가 현재 제공하는 연료 비율만으로 리터·단가를 만들지 않는다. 검증된 연료 끝점·단가가 없으면 fuelEndpointsValid/priceValid=false이며 소모량·연비·비용은 계산 불가다.
- 수집은 전경 화면에 한정되어 있다. 화면 이탈/프로세스 종료의 공백은 누락 또는 추정 종료로 보존한다.
- 실제 사용자 모바일 승인, 장시간 오프라인 복구, 실차 센서와 배포 HTTPS 연결은 별도 통합 검증이 필요하다.

## 검증

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

단위 테스트 46개와 APK 빌드·lint가 통과했다. 추가 기기 테스트 2개는 Pleos Connect API 34 에뮬레이터에서 실행했다. MockWebServer로 설치 발급 → QR → 차량 확인 → 재시작 연결 복원 순서와 헤더를 검증했고 암호화 큐 복구·손상 파일 보존을 확인했다.

## 화면의 API 연결 상태

목업 화면·가상 사용자·고정 거리/금액을 제거했다. 화면 상단의 연결 상태는 실제 API 요청 결과로만 갱신한다. 아직 호출하지 않은 기능은 `연결 확인 전`이다. HTTP 오류·통신 실패·응답 형식 오류가 발생하면 현재 화면에서 `연결 필요` 알림을 표시한다. 동일 오류는 반복 폴링마다 알림을 띄우지 않으며 `API 연결 상태 확인`에서 다시 확인할 수 있다. 주행 제한 상태에서는 알림도 표시하지 않는다. 상태 기록에는 토큰·QR proof·개인 응답 본문을 저장하지 않는다.

단위 테스트는 인증 헤더 분리, 루트 경로, 증가 거리·누락, UTC/후보 매핑, 서로 다른 구간의 재시도 순서와 잘못된 샘플 수신 확인 거부를 검증한다. 일반 Android 이미지에서 차량 UX 클래스가 없으면 제한 화면을 표시한다. Pleos 기기의 승인/수집 종단 간 검증은 미실시다.
