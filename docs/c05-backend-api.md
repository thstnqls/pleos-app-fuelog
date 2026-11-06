# C05 주유 감지 요약 — 백엔드 인계 (2026-11-06)

## Android 구현 및 제한

앱 화면이 활성화된 동안 Pleos getFuelPercent를 3초 간격으로 조회한다. 거리 조회와 분리하여 연료 오류가 거리 기록에 영향을 주지 않는다. 증가 후보는 로컬 저장하며 주유·결제 완료로 해석하지 않는다. 자동 팝업이나 강제 화면 이동은 없다.

현재 임시 감지 기준은 3%p 이상 증가다. 동일 증가 구간에서는 고정 UUID 후보를 갱신한다. 고점보다 2%p 초과 하락, 60초 초과 샘플 공백, 운행 구간·사용자 변경은 비교 기준을 초기화한다. 이 임계치는 제품 확정값이 아니며 실차 흔들림/센서 보정 검증이 필요하다. 장시간 앱 미실행 중 주유는 감지하지 못한다.

용량 미확인 상태에서는 L/원을 표시하지 않는다. 위치·오피넷은 아직 미연동이며 주유소 미확인으로 표시한다. 서버의 검증된 용량·단가 메타데이터가 있어야 추정값을 표시한다. 서버 상태 갱신은 수동 새로 확인 또는 모바일 전달 버튼 시 실행하며, 실시간 push는 미연동이다.

2026-11-06 추가 기획 [F01](fuelog-screen-requirements-2026-10-31.md): 위치 입력은 Pleos 차량의 주유 감지 시점에서 얻는다. 현재 아래 `stationCandidates` 문자열과 단일 `estimatedAmount` 예시는 F01 적용 전 계약이다. F01 구현 시에는 후보별 주유소 ID·유종·거리·게시 단가·가격 기준 시각을 구조화하고, M07에서 주유소를 선택하기 전에는 여러 후보 중 한 가격으로 단일 추정 금액을 만들지 않는다.

계정이 미확정/만료되면 사용자 확인 화면으로 유도한다. 후보는 감지 당시의 owner와 intervalId에 고정하며 새 로그인으로 이전 미귀속 후보를 자동 귀속하지 않는다. 현재 사용자와 일치하는 최신 후보 한 건만 표시한다. 차량에는 장소·금액 편집/결제/확정/기각 액션이 없다.

## 현재 클라이언트가 호출하는 API

### POST /fuel-candidates/sync

설치 Bearer 인증. C01 설치 인증을 재사용한다. 서버는 설치의 검증된 현재 운행 사용자 동의와 후보 소유권을 확인해야 하며 body.owner를 신뢰하면 안 된다. 같은 후보 UUID의 업로드, 최신 서버 상태 조회, queued=true일 때 모바일 확인함 생성을 멱등 처리한다.

요청 예:

```json
{
  "id": "stable-candidate-uuid",
  "owner": "verified-subject-id",
  "intervalId": "trip-uuid",
  "startAt": 1793926800000,
  "endAt": 1793926860000,
  "increasePercent": 10,
  "status": "CANDIDATE",
  "revision": 0,
  "queued": true
}
```

시각은 epoch milliseconds. queued는 모바일 확인함 전달 의도이며 확인 완료를 뜻하지 않는다. status/revision 및 표시 메타데이터는 캐시여서 재전송될 수 있으므로 서버 상태 변경 명령으로 사용하지 않는다. 증가량/시간 구간은 동일 id에서 갱신될 수 있다. 예전 샘플(endAt 이전)의 재전송은 최신 감지값을 덮어쓰지 않는다.

응답 예:

```json
{
  "id": "stable-candidate-uuid",
  "owner": "verified-subject-id",
  "status": "CANDIDATE",
  "revision": 1,
  "capacityVerified": true,
  "estimatedLiters": 5.0,
  "estimatedAmount": 8500,
  "priceAt": 1793926800000,
  "stationCandidates": ["예시 주유소 A", "예시 주유소 B"]
}
```

status 허용값: CANDIDATE / CONFIRMED / REJECTED. revision은 단조 증가해야 한다. 앱은 id·owner를 대조하고 이전/동일 revision 응답을 무시한다. 모바일 변경 후에는 더 높은 revision을 반환한다. capacityVerified=false이면 estimatedLiters/estimatedAmount는 생략 또는 null. 금액을 제공하면 유효한 단가 기준 시각(priceAt)을 반드시 제공한다. 단일 주유소도 후보로 표시하고 다수 후보는 상호를 확정 표시하지 않는다.

## 모바일/서버에 추가로 필요한 API

| API | 역할 | 필수 조건 |
| --- | --- | --- |
| GET /mobile/fuel-candidates/{id} | M07 상세 조회 | 모바일 로그인, 차량 멤버십 및 후보 소유권 검사 |
| PATCH /mobile/fuel-candidates/{id} | 모바일 확인/기각·수동 보정 | operationId, expectedRevision, action=CONFIRM/REJECT, 보정 장소/양/금액. 조건부 갱신 및 감사 이력 |
| GET /mobile/inbox | 주유 확인 항목 조회 | kind=FUEL_CANDIDATE, candidateId. 현재 사용자만 반환 |
| POST /fuel-candidates/{id}/station-search | F01 차량 감지 위치와 오피넷으로 후보별 게시가격 산출 (서버 내부 작업으로 대체 가능) | 설치 인증·후보 귀속·차량 측정 시각·좌표계·유종 검증. 실패해도 원본 증가 감지/추정량 보존. 장소는 미확인으로 유지 |
| GET /vehicle/events | 모바일 변경 이벤트 통지 (선택) | candidateId, revision만 전달. 차량 정차 시 재조회, 팝업 강제 표시 금지 |

## DB/권한/실패 규칙

- UNIQUE(installationId, candidateId), 확인함 UNIQUE(recipientId, kind, candidateId). 동일 요청 재시도는 각 한 건으로 유지한다.
- 감지 증거 갱신과 모바일 확인 상태를 분리한다. 반복 감지/차량 sync로 CONFIRMED 또는 REJECTED가 CANDIDATE로 되돌아가면 안 된다.
- 서버는 멤버십 해제/소유권 불일치를 403, 만료 인증은 401, 모바일 revision 경합은 409로 반환한다. 차량은 실패 시 기존 상태를 유지하되 마지막 서버 확인 시각을 표시한다.
- 권한·네트워크 실패 시 미확정 신규 후보는 로컬 별도 보관. 이전 사용자 데이터를 현재 화면에 반환하지 않는다.
- 오피넷 실패/위치 권한 없음은 주유량 보존 + stationCandidates=[]로 처리한다. 용량 없으면 percent 증가만 보존한다. 탱크 용량과 연료 비율을 이용한 L 환산은 추정치이며 실제 결제량으로 확정하지 않는다.
- 앱에서 주행 제한/이탈 시 진행 중 sync를 취소하며 늦은 응답을 버린다. 서버 상태는 다음 정차 조회 때 확인한다.

## 검증 및 남은 작업

단위 테스트: 반복 감지/프로세스 재생성 시 같은 ID 유지, 사용자·샘플 공백 경계, 다른 owner 응답 거부, 오래된 revision 상태 되돌림 방지. Android 빌드도 검증한다.

실차 센서 노이즈와 실제 주유 감지 정확도, 모바일 확인/기각 왕복, 서버 중복 처리, 오피넷 실패·용량 검증은 통합 검증 대상이다. 현재 자동 재전송 작업자는 없으며 오프라인 전달은 로컬 queued 상태를 보존하고 정차 후 새로 확인 버튼으로 재전송한다. 서버가 없는 상태에서는 M07에 전달 완료되었다고 간주하지 않는다.
