# C03 이번 운행 사용자 확인 API (2026-11-04)

> 2026-11-08 변경: 신규 앱은 최초 페어링 사용자를 유지하며 구간 동의 QR·후보 선택을 사용하지 않는다.
> 사용자 변경은 연결 끊기 후 재페어링한다. 아래는 과거 계약이며 최신 내용은 [백엔드 연결 안내](backend-integration.md#2026-11-08-사용자-유지차량-기본정보-계약)를 따른다.

Android에 후보 선택·검증 요청과 구간 종료 동의를 구현했다. 아래는 제안 계약이며 서버와 M04 구현은 이 저장소에 없다. 현재 후보 조회는 C03의 새로 확인 버튼으로 수행한다. 푸시/새 후보 자동 진입은 후속 연동 대상이다.

| API | 인증/입력 | 응답/책임 |
| --- | --- | --- |
| POST trip-user/candidates | 설치 Bearer, intervalId | intervalId, candidates 배열. 각 항목 candidateId, displayName, vehicleMember=true, mobileConsented=true, remainingSeconds(1~300). 서버가 같은 설치·차량·구간에 모바일 동의한 유효 후보만 반환 |
| POST trip-user/verify | 설치 Bearer, intervalId, candidateId, operationId | intervalId, candidateId, subjectId, vehicleMember, mobileConsented, expiresAtEpochMillis. 후보와 동일한 구간·차량·설치 소유권 재검사 후 일회용 증명 소비. subjectId는 클라이언트 입력으로 받지 않음 |
| POST mobile/trip-user/consent | 모바일 로그인, vehicleId, installationId, intervalId, challengeId, proof, operationId | M04의 명시적 동의를 저장. 차량 멤버십 및 현재 구간 검증. 1회용 challenge 소비 후 후보 생성 |
| POST trip-user/challenges | 설치 인증, intervalId | 모바일 전달용 일회용 challengeId와 앱 페이로드, expiresAt. 장기 비밀을 URL query/path에 포함하지 않음 |
| POST trip-user/revoke | 모바일 로그인 또는 설치에 한정한 권한, consentId | 후보 및 향후 사용 권한 폐기. 이미 기록한 구간 소유권은 변경하지 않음 |
| GET trip-user/events | 설치 인증, cursor | 후보 변경 이벤트와 revision만 전달. 개인정보 없이 C03 재조회 유도. 주행 제한이면 화면 전환 및 사용자 적용 보류 |

## 검증과 경합

- verify는 기존 기록 소유권을 변경하는 API가 아니다. 응답은 해당 구간의 신규 기록에 사용할 검증 결과다. 실제 샘플 업로드 시에도 서버가 구간과 사용자 증명 결합을 확인해야 한다.
- 다른 candidateId, 차량, 설치, intervalId 조합은 403. 만료는 410, 소모한 증명을 다른 operationId로 재전송하면 409. 동일 operationId+동일 body 재시도만 기존 결과 반환. 동일 키에 다른 body는 409.
- 후보가 하나여도 모바일 동의와 차량 화면의 명시적 선택/확인 둘 다 필요. 연결 시각과 신호 세기는 소유자 결정에 사용하지 않는다.
- 멤버십 해제는 조회뿐 아니라 verify와 기록 업로드에서도 재검사. 오프라인과 서버 오류 시 전환 금지. 기존 소유자와 기록을 재귀속하지 않는다.
- 주행 제한 또는 화면 이탈 시 클라이언트는 진행 요청을 취소하고 늦게 온 결과를 버린다. 서버는 verify 응답만으로 기존 구간 소유자를 변경하지 않아야 한다.

## 앱의 구간 처리

진행 중 사용자를 바꾸려면 정차 상태에서 기존 구간 종료에 동의해야 한다. 구간 id·소유자·거리·누락 상태를 로컬 이력에 보존한 뒤 새 구간 id로 후보를 다시 조회한다. 새 구간에 대한 모바일 동의를 받고 C03 확인 후 C02에서 기록 시작을 누른다. 기존 구간의 모바일 증명을 새 구간에 재사용하지 않는다. 미확정 소유자는 빈 값으로 별도 보관한다.

후보 목록/선택은 화면 이탈·주행 제한 시 폐기한다. 사용자 이름은 검증 응답 후 표시하며 확인 유효기간이 끝나면 사용자 확인 필요로 표시한다. 개인 비용/개인 상세 캐시는 아직 도입하지 않았다. 새 사용자의 개인 API 연동 시 세션 버전별 캐시와 오래된 응답 폐기 필요.

## 남은 통합 검증

서버가 없는 상태이므로 타인 ID/재사용 증명/만료 토큰의 실제 거부는 미검증이다. 모바일 M04, challenges, events, revoke는 계약만 정의했고 클라이언트 연동은 candidates/verify만 구현했다. 실제 차량에서 정차→주행 중 verify 응답, 회전/백그라운드 응답, 멤버십 해제 경합, 구간 저장 실패, 오프라인 전환 보류를 검증해야 한다.
