# 사용자 흐름별 코드 안내

2026-11-07 기준. `app/src/main/java`의 Java 소스와 Manifest·빌드 설정·관련 테스트를 확인했다.
차량 한 대에서 전경 화면을 사용하는 현재 구조를 기준으로 정리했다.
화면·기록·연결·인증 클래스의 번호 주석은 각 클래스 안에서 읽는 순서다.

## 제품 화면

시작점은 Manifest의 런처인 `CarHomeActivity`다.

| 사용자 과정 | 화면 메서드 | 이어서 읽을 코드 |
| --- | --- | --- |
| 앱 실행·복귀 | `onCreate`, `onStart` | `ConnectionSession.get`, `HomeRecorder.get` |
| 차량 연결 QR 확인·승인 | `renderConnect` | `ConnectionSession.refresh` → `issueInstallation` → `refreshPairing` → `confirmPairing` |
| 이번 운행 기록 시작 | `renderHome` | `HomeRecorder.toggle` → `RecordingSync.start` |
| 모바일 동의·사용자 확인 | `renderUser`, `loadUserCandidates` | `TripUserSession.challenge` → `load` → `selection` → `confirm` → `HomeRecorder.assignUser` |
| 개인 홈 인증 | `renderUser` | `TripUserSession.challenge` → `pollPersonal` → `ConnectionSession.activatePersonal` |
| 운행 중 거리·연료 수집 | `startLiveRecorder` | `HomeRecorder.resume` → `tick` → `read` / `readFuel` |
| 운행 기록 종료 | `renderHome` | `HomeRecorder.toggle` → `endForSwitch` → `RecordingSync.finish` |
| 운행 요약·모바일 전달 | `renderTrip` | `HomeRecorder.summary`, `serverSummary`, `enqueueMobile` → `RecordingSync.deliver` |
| 주유 후보 확인·전달 | `renderFuel`, `syncFuel`, `searchStations` | `HomeRecorder.fuelCandidate`, `queueFuel`, `updateFuel` → `FuelCandidates` |
| 정비 예정 확인 | `renderMaintenance`, `loadMaintenance` | `MaintenanceSummary`, `MaintenanceSummary.Item.status` |
| 사용자 연결 해제·차량 초기화 | `renderSettings`, `disconnect` | `ConnectionSettings.begin` → `retry` → `HomeRecorder.lockPersonalSession` → 서버 완료 확인 |
| 화면 이탈 | `onStop` | `HomeRecorder.pause`, `TripUserSession.cancel`, 진행 요청 취소 |

사용자 확인에는 서버에서 생성된 기록 구간이 필요하다. 홈에서 기록을 시작한 뒤 이번 운행 사용자를 확인한다.
개인 홈 인증과 구간 사용자 확인은 백엔드의 scopedToken/candidateId를 사용한다. Supabase 로그인은 별도 모바일 앱에서 처리한다.

## 진단 화면

| 과정 | 읽을 메서드 |
| --- | --- |
| 진단 화면 진입·연결 | `DiagnosticActivity.onCreate` → `onStart` → `reconnect` → `openMeasurements` |
| 진단 탭 선택·조회 | `showSelectedPage` → `readSelectedPage` → 각 진단 서비스의 `read` / `readAll` |
| 위치·VIN 선택 조회 | `buildOptionalCard`, `readLocation` → `PleosLocationClient` / `CarInfoDiagnosticService` |
| 진단 종료 | `DiagnosticActivity.onStop` → `closeMeasurements` |

공통 진단 요청의 타임아웃·응답 무효화는 `DiagnosticSession`, capability 검사는 `PleosReadSupport`에 있다.
제품 기록의 재시도·순서 보장은 `RecordingSync`, 전송 데이터 검증은 `RecordingPayloads`에 있다.

## 정리한 중복·미사용 코드

- Vehicle·FusedLocation 의존성은 기존 version catalog 선언만 남겼다. SDK 버전은 바꾸지 않았다.
- 차량 연결과 사용자 인증의 QR 생성은 `CarHomeActivity.showQr` 하나를 사용한다.
- 이전 차량 등록 응답의 상태·ID·TTL·nonce 검증은 `ConnectionResponse.validate`에서 수행한다.
  요청 ID 일치 검사는 요청 문맥이 있는 `ConnectionSession`에 남겼다.
- 호출처가 없는 AAOS 통합 조회 메서드 `readAll`, `readEvAll`, `readEnergyAll`을 제거했다.
  화면에서 사용하는 연료·배터리·거리별 조회는 유지했다.
- 사용하지 않는 진단 문자열 2개와 Android Studio 기본 예제 테스트 2개를 제거했다.
- 주요 클래스 9개의 메서드를 흐름별로 재배치하고, 기록 상태 모델 `TripState`에도 단계 주석을 추가했다.

기존 미커밋 구현, 저장 키·파일 형식, 이전 설치 인증 복원, 재시도 키와 기록 보존 처리는 유지했다.
SDK 수신이 제한된다는 이유로 진단 기능을 제거하지 않았다.
차량 API와 사용자 동의 API는 유지했다. Pleos에서 사용하지 않는 Supabase 로그인 화면·딥링크·토큰 갱신·빌드 설정은 제거했다. 설치·운행 큐의 암호화 저장은 `storage/EncryptedJsonStore`로 분리하고 기존 키를 유지했다.

## 검증

- 변경 전 빌드·단위 테스트·lint 통과.
- 변경 후 Pleos Connect 에뮬레이터의 기기 회귀 테스트 25개 통과.
- 최종 debug APK·테스트 APK 빌드, 단위 테스트 48개, lint 통과. lint는 오류 0개이며
  기존 경고 중 미사용 문자열 경고 2개가 제거됐다. 나머지 경고는 동기 저장·도구 버전·번역·커스텀 뷰 등에 관한 항목이다.
- 실차 센서 정확도, 실제 백엔드·모바일 승인, 모바일 앱의 로그인은 이번 정리에서 검증하지 않았다.
- Supabase 로그인 제거 후 단위 테스트 37개, 설치·큐·암호화·딥링크 관련 기기 테스트 4개와 APK 빌드·lint가 통과했다. 설치 정보와 운행 큐의 기존 암호화 키는 유지하고, 앱 시작 시 이전 로그인 캐시만 제거한다.
