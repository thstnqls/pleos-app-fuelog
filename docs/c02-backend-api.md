# C02 차량 홈 API 목록 (2026-11-04)

> 2026-11-08 변경: 신규 앱은 최초 페어링 사용자를 유지하며 구간 동의 QR·후보 선택을 사용하지 않는다.
> 사용자 변경은 연결 끊기 후 재페어링한다. 아래는 과거 계약이며 최신 내용은 [백엔드 연결 안내](backend-integration.md#2026-11-08-사용자-유지차량-기본정보-계약)를 따른다.

현재 화면은 사용자 미확정 상태를 기본으로 구현했다. 모바일 증명·개인 홈 API는 미연동이며 이름 선택으로 인증 상태를 바꾸는 경로는 없다. 개인 비용과 알림 건수는 0으로 추정하지 않고 확인 후 조회로 표시한다. 차량 별칭은 서버 미연동 상태를 표시한다.

## 필요한 API (제안)

| API | 입력 / 인증 | 응답 및 책임 |
| --- | --- | --- |
| GET /vehicle/home | 설치 인증 + 검증된 운행 사용자 세션 | vehicleAlias, userSessionVersion, recordingStatus, lastSampleAt, distanceKm, costStatus, estimatedCost, syncStatus, fuelReviewCount, maintenanceDueCount, generatedAt. 각 데이터에 observedAt 및 available/stale/unsupported/error 상태 제공 |
| POST /trip-user/challenges | 설치 인증, installationId, operationId | 일회용 모바일 확인 요청, expiresAt. QR 비밀은 URL에 넣지 않음 |
| POST /mobile/trip-user/approve | 모바일 로그인, challengeId, proof, vehicleId | 멤버십 확인 후 해당 설치와 차량에 한정된 짧은 사용자 세션 발급. 이름만으로 승인 불가 |
| POST /trip-user/session | 설치 인증, challengeId | 승인된 사용자 표시명, subjectId, sessionVersion, expiresAt, scopedToken. 미승인/만료는 개인 데이터 접근 불가 |
| DELETE /trip-user/session | 설치 및 현재 사용자 세션 | 사용자 전환/로그아웃. 세션 폐기 후 개인 홈 캐시 즉시 삭제 |
| POST /recording-intervals | 설치 인증, localIntervalId, startedAt, source=manual_app | 앱 기록 구간 생성. localIntervalId로 멱등 처리. 시동 구간으로 해석 금지 |
| POST /recording-intervals/{id}/samples | 설치 인증, batchId, sequence, observedAt, distanceKm, missingReason | 오프라인 배치 멱등 업로드. 연료 값은 선택 사항. 거리 수신 성공을 연료 오류로 취소하지 않음 |
| POST /recording-intervals/{id}/finish | 설치 인증, operationId, endedAt, lastSequence | 종료 멱등 처리. 누락 구간과 종료 시각 보존 |
| GET /sync/status | 설치 인증 | lastAcceptedSequence, lastSyncedAt, pendingCount, retryAfter |

## 응답/접근 계약

- 사용자 세션이 없으면 개인 운행·지출·알림은 null/needs_user_proof. 타인의 마지막 데이터를 대신 반환하지 않는다.
- 홈은 거리·연료·정비·동기화 각각의 상태를 분리한다. 연료 unsupported/error이면 비용 계산만 unavailable 처리한다.
- 401/403은 사용자 세션과 개인 캐시를 즉시 폐기한다. 새 사용자 증명은 세션 버전을 증가시키며 이전 버전의 진행 중 응답도 버린다.
- 서버의 사용자 전환 이벤트 또는 다음 조회에서 sessionVersion 변경을 감지해야 한다. 기기 내 전환 시작 시 즉시 캐시를 지운 뒤 증명을 기다린다.
- 기록 시작 당시 사용자 미확정 데이터는 차량 로컬 구간으로만 유지한다. 나중에 로그인했다는 이유로 과거 익명 구간을 사용자에게 자동 귀속하지 않는다.
- 모든 시각은 UTC ISO-8601, 거리는 km, 비용은 currency와 함께 반환. 오래된 값은 observedAt 기준으로 최근 수신 표시. 서버 시간과 클라이언트 시계 차이를 고려한다.

## 구현 범위와 후속 연동

Android는 누적거리 API를 3초 간격으로 조회하며 첫 정상 샘플 이후 차이를 합산한다. 음수/비정상값·누적거리 초기화·화면 이탈은 일부 누락으로 처리하고, 재개 시 누락 구간을 거리로 추정하지 않는다. 서버 오프라인과 무관하게 로컬 상태를 저장한다. 자동 시작 및 백그라운드 수집은 제공하지 않는다.

현재 로컬 저장은 최신 앱 구간 하나이며 업로드 큐/다중 구간 이력은 아직 없다. 연료·비용·정비·알림은 미연동 상태다. 사용자 증명 전용 API가 없으므로 실제 사용자 전환 및 개인 카드 제공은 후속 연동 대상이며, 현재 화면은 항상 개인 데이터 비공개 상태다. 모바일 증명 도입 시 subjectId별 캐시 분리와 전환 즉시 제거 테스트가 필요하다.

검증: 거리 누적, 화면 이탈 후 구간 연결 금지, 비정상/리셋 샘플, 최신 샘플 기반 기록 상태, 종료 후 샘플 무시 단위 테스트. 실차 권한/거리 지원 여부와 모바일 세션 전환, 서버 오프라인 재전송은 통합 검증 필요.
