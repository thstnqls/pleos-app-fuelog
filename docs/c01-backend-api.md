# C01 차량 연결 API 계약 (제안)

현재 Android 구현은 아래 계약을 사용하는 클라이언트다. 서버와 모바일 M02는 이 저장소에 없으며 아직 통합 검증하지 않았다. API_BASE_URL 설정이 필요하다. 성공 시 기존 홈(C03), ALREADY_LINKED 응답은 설정(C07)으로 이동한다. 기존 홈의 기록은 여전히 시안이다.

## 현재 클라이언트 호출

모든 요청은 HTTPS JSON POST. Authorization: Bearer 헤더에는 앱 설치별로 생성한 256비트 이상의 무작위 설치 자격 증명을 보낸다. 이는 사용자 로그인 토큰이 아니다. 서버는 최초 요청에서 해시로 설치를 식별하고 이후 소유권을 검사해야 한다. UUID나 requestId만으로 권한을 부여하지 않는다. 기기 증명이 필요하면 별도 프로비저닝 계약을 추가한다. 로컬 자격 증명과 멱등 키는 백업 제외 앱 내부 파일에 저장한다.

| API | 요청 body | 역할 |
| --- | --- | --- |
| `connection/requests` | `operationId`, `requestId` (최초 빈 값) | 미등록 설치 조회 및 요청 발급. 동일 operationId 재시도는 같은 결과. 새 operationId는 이전 요청을 원자적으로 폐기 |
| `connection/requests/status` | `operationId`, `requestId` | 해당 설치의 요청 상태 조회. 현재 1초 주기, 동시 요청 1개 |

성공 응답 필수: `requestId`, `status`. 상태는 WAITING / EXPIRED / REVOKED / COMPLETED / ALREADY_LINKED. WAITING에는 `remainingSeconds` (1~120), `nonce` 필수. 서버 시간 기준으로 만료 계산하고 응답은 Cache-Control: no-store. 만료·폐기는 정상 상태 응답으로 반환한다. 인증 오류 401/403, 충돌 409, 제한 429, 서버 오류 5xx에는 민감정보 없는 오류 코드 제공.

QR은 URL이 아닌 JSON 앱 페이로드:

```json
{"type":"fuelog.vehicle-registration","version":1,"requestId":"opaque-id","nonce":"single-use-random-proof"}
```

이름, VIN, 설치 자격 증명, 장기 토큰은 포함하지 않는다. nonce와 QR 원문은 로그/분석 이벤트에서 삭제한다. 모바일 앱 내부 스캐너에서만 처리한다. 브라우저 링크로 변환하지 않는다.

## 추가로 필요한 모바일/설정 API

| API 제안 | 인증/입력 | 서버 처리 |
| --- | --- | --- |
| `POST mobile/connection/inspect` | 사용자 로그인 + QR envelope를 body로 전송 | 만료/재사용 검사, 차량 확인에 필요한 최소 정보 제공 |
| `POST mobile/connection/approve` | 사용자 로그인, requestId, nonce, vehicleId, idempotencyKey | 차량 소유권/멤버십 검사 후 기존 차량과 설치 연결. 새 차량 생성은 별도 확인된 차량 식별 절차로만 수행 |
| `POST connection/reset` | 설치 인증 + 재인증된 사용자 권한 | 연결과 대기 요청만 해제. 운행/주유/정비 이력 삭제 금지. 성공 후 C01 재진입 |

설정의 연결 초기화는 아직 서버 계약과 재인증 흐름이 없어 UI에 추가하지 않았다.

## 트랜잭션 및 주행 제한

- nonce는 요청·설치·확인한 차량에 결합하며 서버에 해시로 저장한다. 서로 다른 차량 승인 교차 사용 거부.
- 승인 시 만료 확인, 단일 사용 소비, 설치 연결을 하나의 트랜잭션으로 처리한다. installation의 활성 연결 유일성, 차량 식별자 유일성, 멱등 키 제약 필요.
- 재발급과 승인 경합도 같은 잠금/조건부 갱신으로 직렬화한다. 이전 QR은 재발급 커밋 즉시 무효.
- 현재 앱은 주행 제한/백그라운드에서 QR 표시·상태 조회·완료 화면 이동을 보류한다. 서버 승인을 막는 기능은 아직 없다. 출시 전 주행 상태를 서버에 전달하는 짧은 유효기간의 승인 허가(lease) API와 만료 시 승인 보류 처리가 필요하다. 단순 클라이언트 boolean은 신뢰 근거가 될 수 없다. 해당 계약 확정 전 주행 제한 완료 기준은 미충족이다.
- 회전 시 프로세스 세션 재사용, 프로세스 재시작 시 저장된 operationId로 복구한다. 실패 재시도에서 새 키를 만들지 않는다. 앱은 연결 실패 시 기존 기록을 삭제하지 않는다.

## 통합 검증 항목

120초 이후 승인 거부, 소비한 nonce 재사용 거부, 재발급 직후 이전 QR 거부, 다른 설치/차량 조합 거부, 승인 동시 전송 시 연결 1개, 응답 유실 후 동일 키 재시도, 화면 회전/프로세스 재시작, 오프라인에서 QR 숨김, 주행 제한 중 승인 보류, 기존 등록 기기 C07 이동, 실패/초기화 후 과거 기록 보존을 서버 및 모바일과 함께 검증한다.

## QR 발급 전 차량 별칭 (2026-11-11)

차량 앱은 1~100자의 별칭을 먼저 저장해야 QR을 요청·표시한다. 공백 입력은 거부하고 앞뒤 공백은 제거한다. 별칭은 설치 인증 저장소에 보관한다.

차량의 `POST /pairing-requests` 본문은 `{ "vehicle_name": "출퇴근 차량" }`이다. 백엔드는 해당 이름을 요청의 `new_vehicle_name`에 보관하고, 모바일에서 신규 차량 등록을 선택하면 차량에서 지정한 이름을 우선한다. 최종 차량 승인 시 `vehicles.name`으로 저장한다. 기존 차량 선택 시에는 기존 차량 이름을 유지한다. 구버전 클라이언트의 본문 없는 발급은 계속 지원하며, 본문을 보내는 경우 빈 이름과 100자 초과는 거부한다. 스키마 추가는 필요하지 않으며 백엔드 배포가 선행되어야 한다.

### 차량 별칭 유지·수정

로컬 `vehicleAlias`는 연결 해제·등록 초기화 후에도 유지한다. 설치 인증과 함께 no-backup 암호화 저장소에 보관하며, 앱 데이터 삭제·재설치 시 사라진다. 저장된 별칭이 있으면 별칭 입력을 생략하고 서버 상태 확인 후 QR을 발급한다. QR 화면과 연결·설정의 '차량 별칭 수정'에서 변경하고 재저장할 수 있다. 연결 전 수정하면 새 별칭으로 QR을 다시 요청한다. 기존 차량은 설치 Bearer로 `PUT /vehicle/name`에 `{vehicle_name}`을 보내 `vehicles.name`을 변경하고 성공 응답의 이름을 로컬에도 저장한다. 서버가 거부하거나 통신이 실패하면 로컬 이름을 먼저 변경하지 않는다. 오너 인증이 필요하다.


모바일 `feature/login_main`의 이름 수정은 JWT로 `PUT /mobile/vehicles/{vehicleId}/name`을 호출하며 OWNER만 가능하다. 모바일 연결 상태 조회의 `vehicleName`과 차량 설치 조회의 `vehicle_name`으로 오래된 이름 캐시를 갱신한다. 완료 페어링 조회도 현재 `vehicles.name`을 반환한다. 새 API 배포가 필요하며 기존 DB 이름은 별칭 수정 후 변경된다.

## VIN 기반 차량 UUID 재사용 (2026-11-12)

앱 업데이트는 기존 설치 인증을 유지한다. 재설치·데이터 삭제로 설치 ID가 새로 만들어진 경우에는 VIN으로 기존 차량을 찾는다. 차량 앱은 Pleos `checkVINInfoCapabilities` 및 `getVINInfo`로 값을 읽고 17자리 VIN 형식을 확인한다. 원문은 메모리와 화면에만 보관하며 QR 페이로드·로그·로컬 저장에 넣지 않는다. 조회 권한 또는 지원 상태가 부족하면 VIN 확인 불가를 표시하고 'VIN 확인·권한 설정'에서 재시도할 수 있다. VIN이 없으면 중복 식별은 보장되지 않으며 기존 설치/모바일의 차량 선택 방식으로 연결한다.

- `POST /pairing-requests`의 선택 필드 `vin`으로 현재 설치의 VIN을 제출한다.
- 이미 연결된 차량은 설치 헤더 인증으로 `PUT /vehicle/vin`에 `{vin}`을 보내 기존 UUID와 VIN을 먼저 연결한다. 현재 확인된 관리자만 가능하다.
- `vehicles.vin_hash`는 정규화 VIN의 SHA-256이며 UNIQUE다. `vehicle_installations.vin_hash`는 설치가 제출한 식별값이다. 원본 VIN 컬럼은 없다.
- 기존 VIN이 발견돼도 자동 로그인하지 않는다. 모바일 관리자 권한 및 QR 확인 완료 후 같은 UUID를 사용하고 이전 설치의 페어링·사용자 접근을 해제한다. VIN 자체는 소유권 또는 실기기 증명이 아니다.
- 동일 VIN의 동시 등록은 UNIQUE 제약으로 중복 생성을 막는다. 다른 VIN이나 기존 UUID와 충돌하면 거부하며 자동 병합하지 않는다. VIN이 아직 없는 기존 차량은 정상 연결 상태에서 먼저 VIN을 등록해야 재설치 후 찾을 수 있다.

배포 전 백엔드의 `V19__vehicle_vin_identity.sql`을 적용해야 한다. 단위/서버 재설치 시나리오 검증과 실차 VIN 읽기·실제 재설치 검증을 구분한다. 공식 API 페이지는 목록과 제목만 노출되어 서명은 프로젝트 SDK 및 기존 진단 구현으로 확인했다: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/get-v-i-n-info


VIN 조회는 기존 `CarInfoDiagnosticService.readVin`을 공통으로 사용한다. 연결 식별은 `readForIdentity`에서 같은 진단 세션의 capability 검사·오류 구분·12초 제한을 재사용한다. 사용자 요청에 따라 진단·QR·연결 설정 화면에 SDK 수신 문자열 전체를 표시한다. 별도 5초 VIN 조회 구현과 OS 권한 사전 차단은 제거했다.

### VIN 실제 응답 확인 (2026-11-12)

연결된 Pleos Connect v2.1.0 에뮬레이터에서 기존 `CarInfoDiagnosticService.readVin`으로 확인한 결과: 수신 성공, null 아님, 문자열 길이 9, trim 후 9, 표준 VIN 형식 검증 실패. VIN 원문·끝자리는 기록하지 않았다. 따라서 이 환경의 UUID 식별 미적용은 조회 실패가 아니라 입력 형식 불일치다. 화면은 수신한 길이와 형식 불일치를 구분해 표시한다. 유효한 17자리 VIN을 제공하는 차량/테스트 환경에서 식별 연동을 검증해야 한다.


### getVINInfo 호출 정의 대조 (2026-11-12)

공식 getVINInfo URL은 열렸으나 상세 본문이 텍스트로 노출되지 않았다. 프로젝트에 고정된 Vehicle 2.3.2 sources.jar의 CarInfo.kt/CarInfoImpl.kt와 대조했다. getVINInfo는 INFO_VIN/global/default 값을 nullable String 성공 콜백 또는 Exception 실패 콜백으로 전달하며 CAR_INFO는 normal 권한이다. 현재 VIN 조회는 checkVINInfoCapabilities에서 READ 또는 READ_WRITE가 확인될 때만 getVINInfo를 호출하고 응답을 그대로 표시한다. 일반 권한의 런타임 권한 요청 UI는 제거했다. SDK가 제공하지 않은 VIN을 만들어 내거나 형식 검증 실패값을 차량 매칭에 사용하지 않는다.

### SDK 9자리 식별값 임시 지원 (2026-11-12)

현재 API의 9자리 응답으로 진행하기로 한 사용자 결정에 따라 앱 정규화·서버 DTO·서비스 검증에서 9자리 인쇄 가능한 ASCII 식별값을 추가로 허용한다. 앞뒤 공백 제거 및 대문자 정규화 후 해시로 매칭한다. 17자리 표준 VIN도 계속 지원한다. 9자리 값의 차량별 유일성은 미확인이므로 표준 VIN과 동일한 식별 신뢰도를 주장하지 않는다. 동일값 충돌 시 관리자 권한 검사는 유지하며, 차량 서비스가 표준 VIN을 제공하면 임시 지원 정책을 재검토한다. 기존 V19 해시 컬럼을 사용하므로 추가 스키마 변경은 없다. 서버 배포는 필요하다.


### 무효 응답의 식별자 등록 차단

사용자 확인 결과 9자리로 보이던 값은 실제 VIN이 아닌 invalid 표시값이었다. invalid·invaild·undefined·unknown·null·none 등 표시값은 대소문자와 감싼 기호를 무시해 앱·서버에서 식별자로 사용하지 못하도록 차단한다. 화면에는 원문을 계속 표시하되 VIN 미제공 상태로 구분한다. capability 선확인을 복원하며 읽기 불가일 때는 getter를 호출하지 않는다. 유효한 9자리 SDK 식별값 지원은 남아 있으나 무효 표시값은 길이와 무관하게 허용하지 않는다.

capability 선확인 복원 후 Pleos 에뮬레이터 실측: `access=READ`, 성공 콜백 호출, 수신 길이 9, 무효 표시값 판별 true, 정규화 결과 없음. 조회 권한은 있으나 차량 서비스가 유효한 식별값을 제공하지 않는 상태다. 읽기 가능과 유효한 VIN 제공을 별도로 판단한다. 진단 테스트 3개 통과.
