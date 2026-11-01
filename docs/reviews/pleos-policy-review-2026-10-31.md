# Pleos 정책 검토 — 부팅 직후 백그라운드 자동 시작

검토일: 2026-10-31 (KST). 코드 기준: `edf40196dc0b2321f643804a63631512a7b08d0a` 및 현재 미커밋 변경 포함. 기존 변경은 보존했으며 이 보고서만 추가했다.

검토 범위는 ‘차량 시동 → Pleos/Android 기동 → 사용자가 앱 아이콘을 누르지 않아도 fuelog의 백그라운드 작업 시작’이다. 전체 앱 정책 감사나 구현 작업은 아니다. ‘and os’는 차량의 Android Automotive OS(AAOS)로 해석했다.

## 판단

**확인한 공개 정책에서 부팅 후 백그라운드 시작 자체를 일괄 금지하는 조항은 찾지 못했다. 필요한 백그라운드 작업은 조건부로 설계할 수 있지만, 일반 App Market 앱이 매 시동마다 즉시 자동 시작·상주할 수 있다는 보장은 확인되지 않았다. 따라서 ‘정책상 무조건 문제없음’으로 확정할 수는 없다.**

[앱 구조 및 동작 모델의 ‘포그라운드·백그라운드 전환 및 동작 설계’](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)는 백그라운드 지속 작업의 실행 조건과 중단·재개 정책을 정의하고 불필요한 상주·폴링을 줄이도록 한다. 이는 백그라운드 작업을 전제로 한 설계 원칙이며, 부팅 자동 실행에 대한 명시적 승인이나 실행 시점 보장은 아니다.

| 의도한 동작 | 이번 판단 |
| --- | --- |
| 화면을 열지 않고 필요한 작업을 예약·재개 | 검토 가능한 방향. 실제 실행 조건, 권한, 개인정보 동의와 OEM 동작 검증 필요 |
| 부팅 직후 앱 화면·로그인·팝업을 자동 표시 | 별도 검토 대상. 백그라운드 실행과 다르며 Android Activity 시작 제한과 주행 UX 제한 적용 |
| 매 시동마다 즉시 실행하고 전체 운행을 끊김 없이 수집 | 공개 근거로 보장 불가. 전원 복귀, 프로세스 종료, 서비스 연결 및 API 가용성 검증 필요 |
| 사용자 동의 없이 위치·계정에 연결된 운행 이력을 자동 수집·전송 | 해당 조건으로 구현하면 개인정보 정책과 충돌 |
| Android 15 이상에서 부팅 수신기로 `dataSync` FGS 시작(target 35 이상) | Android 플랫폼 제한에 걸림. 현재 target 36도 해당 |

이 표의 조건부 항목은 현재 앱의 위반을 확인했다는 뜻이 아니다. 자동 시작은 아직 구현되지 않았다.

## 대상 환경과 현재 구현

- 국가·양산 차량·Connect-S/L/W 및 배포 승인 카테고리는 미확인. 기획은 정차 중심 MVP와 Mobility Services 차량 관리를 제안한다. 카테고리 승인은 확인하지 않았다.
- `app/build.gradle:20`, `:25`, `:26`: compile 36 / min 34 / target 36. `gradle/libs.versions.toml:10`부터 Vehicle 2.3.2 / FusedLocation 1.1.3 고정.
- `docs/vehicle-diagnostics.md:93`에 과거 Pleos Connect v2.1.0 / API 34 / Vehicle Service 2.3.22 에뮬레이터 관찰이 있다. 이번에 재실행한 결과가 아니며 양산 차량 근거도 아니다. target 36이 실제 OS 36을 뜻하지 않는다.
- `app/src/main/AndroidManifest.xml:5`~`:50`: 차량·인터넷 권한 및 Activity를 선언한다. 앱 소스 Manifest에 `RECEIVE_BOOT_COMPLETED`, 부팅 receiver, Android Service 선언은 없다. `app/src/debug/AndroidManifest.xml`도 확인했다. 의존성의 병합 Manifest 전체를 이번에 새로 생성·검증하지는 않았다.
- `DiagnosticActivity.java:116`의 `onStart()`에서 연결한다. `:208`의 `onStop()` → `closeMeasurements()`에서 구독 해제·SDK release한다. `diagnostics/*DiagnosticService.java`라는 이름의 클래스들은 Android `Service` 컴포넌트가 아닌 일반 도우미 클래스다.
- 현재 진단값은 화면에서 조회하며 파일·DB·서버 저장을 하지 않는다고 `docs/vehicle-diagnostics.md:55`에 명시한다. 인증/HTTP 모듈은 별도로 존재한다. 인증 저장은 `auth/EncryptedAuthStore.java:27`, `:54`에서 no-backup 내부 파일과 Keystore 기반 AES-GCM을 사용한다. 전체 인증·통신 보안 감사는 이번 범위 밖이다.
- `docs/fuelog-product-plan-2026-11-15.md:179`, `:310`과 `docs/fuelog-development-schedule-2026-10-29.md:65`는 시동 자동 시작 보장을 미검증·제외 범위로 둔다.

## 정책 적용 표

공식 intro의 하위 목록을 다시 확인했다. 설계 정책 9개와 디자인 가이드가 노출되며, 이번에는 다음 영향 항목과 연결 문서만 검토했다.

| 항목 | 상태 | 판단 근거 |
| --- | --- | --- |
| P04 화면·생명주기 | 검증 필요 | 필요한 백그라운드 작업을 허용하는 설계와 부팅 자동 실행 보장은 구분해야 함 |
| P09 성능·리소스 | 검증 필요 | 불필요한 상주·폴링 최소화, 시작 부하 및 CPU/RAM 실측 필요 |
| P07 API 가용성 | 검증 필요 | OS 기동 완료가 차량 SDK 준비 완료를 뜻하지 않음. 실제 지원·접근·연결 확인 필요 |
| P08 및 등록 개인정보·보안 | 검증 필요 | 자동 수집 범위·동의·철회·계정 귀속 및 승인 권한 확인 필요 |
| P01/P02 주행 안전·규제 | 검증 필요 | 자동 화면 표시를 넣으면 제한 적용. UI 차단을 센서 읽기 일괄 금지로 확대하지 않음 |
| 등록 기능·성능 안정성 | 검증 필요 | Vehicle SDK 퍼미션은 사전 협의·승인 조건. 실제 승인 증거 없음 |
| 오디오 | 적용 제외 | 요청 기능에 재생 없음. 오디오 자동 재생 예외를 자동 수집의 허용 근거로 사용하지 않음 |
| 화면 치수·색상 등 | 적용 제외 | 새로운 UI를 설계하지 않는 검토이며 화면 디자인 전체는 평가하지 않음 |

## 우선순위별 발견 및 수정안

### F01 / P1 / 검증 필요 — 자동 시작 경로와 차량 권한 승인 미확인

- 정책: [P04 — 백그라운드 동작 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [앱 기능 및 성능 안정성 — SDK·API 사용 심사 / Vehicle SDK 퍼미션 심사 기준](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability). 전자는 설계 원칙, 후자의 사전 협의·승인은 명시 조건이다.
- 근거: `AndroidManifest.xml:6`~`:9`의 Pleos 권한 선언, `DiagnosticActivity.java:116` 및 `:208`. 현재 자동 실행 구현과 승인 문서가 관찰되지 않는다. 승인 부재를 확정한 것은 아니다.
- 조건·영향: 일반 배포 앱의 부팅 실행 경로 또는 연료·거리 권한이 허용되지 않으면 자동 운행 수집 목표를 달성할 수 없다.
- 최소 조치: 먼저 Pleos App Market/기술 지원에 일반 앱의 부팅 receiver 사용 가능 여부, 필요한 별도 승인·OEM 조건, 연료·거리 권한 승인 범위를 확인한다. 승인과 지원이 확인된 후 `startup/BootReceiver.java`와 수집 실행 주체를 설계한다. Receiver는 조건 확인·작업 예약만 하고 UI나 장시간 SDK 작업을 직접 실행하지 않는다. 실패하면 다음 허용된 실행 시점에 복구하고 누락 구간을 기록한다.
- 완료 조건: 대상 플랫폼·OS·사용자 프로필·권한 승인 내역을 기록하고, 실제 재부팅에서 UI 없이 시작 가능한 경로를 입증한다. SDK 연결 지연/거부 시 반복 재시작이나 ANR이 없어야 한다.

### F02 / P1 / 조건부 위험 — 부팅 → `dataSync` FGS 설계는 최신 Android에서 차단

- 정책/플랫폼: [Android 백그라운드 FGS 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android 15 동작 변경 — BOOT_COMPLETED receiver의 FGS 시작 제한](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed). 이는 Android 플랫폼 동작이며 Pleos의 부팅 실행 일괄 금지 정책이 아니다.
- 근거: `app/build.gradle:26` target 36. FGS 자체는 아직 구현하지 않았다.
- 조건·영향: Android 15 이상에서 target 35 이상 앱이 부팅 수신기로 `dataSync` FGS를 시작하면 예외가 발생한다. API 34 에뮬레이터 성공으로 배제할 수 없다.
- 최소 조치: 전송 작업은 `sync/UploadWorker.java` 등 지연 가능한 WorkManager 작업으로 분리하는 방향을 검토한다. [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)는 작업을 재부팅 후 재예약하지만 시동 순간 실행·연속 센서 수집을 보장하지 않는다. 지속 수집은 실제 기능에 맞는 FGS 유형·권한·시작 경로를 별도로 확정한다. 차량 센서 수집이라는 이유만으로 임의 유형을 선택하거나 제한을 우회하지 않는다.
- [FGS](https://developer.android.com/develop/background-work/services/fgs)는 Activity 화면 없이 실행할 수 있지만 사용자에게 인지되는 작업과 알림이 전제된다. ‘앱 화면 없음’과 ‘사용자가 전혀 모르게 무기한 상주’는 동일한 요구가 아니다.
- 완료 조건: 지원 OS별 실제 부팅 테스트에서 금지된 FGS 시작 예외 없음. 예약 작업의 지연과 수집 공백을 제품에서 올바르게 처리한다.

### F03 / P1 / 검증 필요 — 시동과 OS cold boot는 일대일이 아님

- 근거/플랫폼: [AAOS Power management — Car power state machine](https://source.android.com/docs/automotive/power/power#car-power-state-machine). AAOS는 suspend-to-RAM에서 resume할 수 있다. `docs/fuelog-product-plan-2026-11-15.md:310`도 자동 시작 보장을 별도 과제로 둔다.
- 조건·영향: 시동 시 cold boot가 아닌 절전 복귀라면 `BOOT_COMPLETED`만으로 모든 운행 시작을 포착할 수 없다. 부팅 완료 직후에도 Vehicle Service·네트워크·사용자 저장소는 준비되지 않았을 수 있다.
- 최소 조치: `collection/CollectionCoordinator.java` 같은 별도 실행 주체에 ‘대기 → 권한/동의/세션 및 서비스 가용성 확인 → 수집 → 중단/복구’ 상태를 둔다. cold boot와 resume를 각각 처리할 수 있는 공개·승인된 이벤트가 대상 플랫폼에 있는지 먼저 확인한다. 구체 전원 API의 권한·시그니처를 이번에 확인하지 않았으므로 `CarPowerManager`를 일반 앱에서 바로 호출하라는 구현 제안은 하지 않는다.
- 실패 동작: 연결 실패·미지원·일시 불가를 구분하고 유한 재시도 후 대기한다. 다음 실행 때 관측 공백을 보존하며 값을 0이나 정상 운행으로 채우지 않는다.
- 완료 조건: cold boot, 짧은 시동 Off/On, 장시간 주차 후 복귀, 프로세스 종료, 사용자 전환, SDK 재연결에서 중복 수집 및 잘못된 운행 구간 확정이 없다. 지원하지 못하는 경우 자동 시작 보장을 표시하지 않는다.

### F04 / P1 / 조건부 위험 — 자동 시작이 개인정보 수집 동의를 대신하지 않음

- 정책: [P08 — 계정/세션·최소 수집](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy), [사용자 개인정보 및 보안 — 개인정보 활용·동의·철회](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security), [앱 보안 가이드 — 민감 데이터·통신·권한](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide). 명시적 동의와 데이터 보호는 요구사항이다.
- 근거: 기획 `:113`, `:119`는 사용자 미확정·재시작 시 귀속 제한을 둔다. 현재 `DiagnosticActivity.java:179`는 위치를 명시적 요청과 권한 확인 후 조회한다. 자동 수집 동의 UI·저장 상태는 별도 확인되지 않았다.
- 조건·영향: 과거 로그인 토큰만으로 현재 운전자를 단정하거나 위치·운행 이력을 동의 없이 수집·전송하면 개인정보 요구와 충돌한다. 현재 코드에서 그런 자동 수집을 확인한 것은 아니다.
- 최소 조치: 정차 중 초기 설정에서 자동 수집 데이터·목적·보유기간·전송 대상을 고지하고 명시적 동의를 저장한다. 시작 시 동의 유효성·권한·세션을 확인하고, 철회/로그아웃 시 수집과 예약 작업을 중지한다. 이전 사용자 데이터는 생성 당시 세션에 묶고 새 사용자에게 재귀속하지 않는다. 필요한 민감 큐는 암호화하고 로그에 남기지 않는다. 자동 시작 설정과 해제 기능을 제공하는 것은 권고다.
- 위치: [Pleos FusedLocation 권한 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)는 `pleos.car.permission.FUSED_LOCATION`을 dangerous로 정의하지만 Android의 foreground/background 위치 정책 동작을 별도로 보장하지 않는다. Android 표준 위치 FGS 조건을 이 SDK의 동작 보장으로 대입하지 않는다. 백그라운드 위치가 필요하면 별도 확인한다.
- 완료 조건: 미동의·권한 거부·위치 차단·철회·운전자 변경·오프라인 상태에서 허용하지 않은 데이터 수집/업로드가 없고 이전 계정 데이터가 노출되지 않는다.

### F05 / P2 / 검증 필요 — 백그라운드 리소스와 UI 규제

- 정책: [P09 — 메모리·CPU·배터리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt), [P01](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline), [P02](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy), [주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide).
- 근거: `DiagnosticActivity.java:208`은 현재 화면 이탈 시 수집을 정리한다. `AaosVehicleDiagnosticService.java:55`~`:69`는 UX 현재값·변경을 확인한다. 자동 시작 후 상시 수집의 CPU/RAM 측정은 없다.
- 조건·영향: 항상 유지하는 폴링·타이머·연결 재시도는 자원을 점유할 수 있다. 자동 로그인 화면이나 팝업을 추가하면 운전자 주의 분산 및 [Android 백그라운드 Activity 시작 제한](https://developer.android.com/guide/components/activities/background-starts)도 검토해야 한다.
- 최소 조치: UI와 수집 생명주기를 분리하되 필요한 수집만 실행한다. 이벤트 구독을 우선 검토하고 중단 시 해제, 연결 재시도 상한 및 네트워크 조건을 둔다. UI를 열 때 UX 제한을 다시 확인하며 DO 선언만으로 제한을 우회하지 않는다. DO 미선언 UI의 주행 차단은 그 자체로 위반이 아니며 차량 상태 읽기 전체의 금지 근거도 아니다.
- 완료 조건: 대상 기기·빌드·측정 구간을 기록하여 비내비 앱 주행 CPU 5% 미만, 정차 CPU 25% 이하, RAM 400 MB 이하를 확인한다. 부팅 부하·다른 앱 사용·절전 진입을 함께 시험한다. 금지된 UI는 주행 시작·변경·복귀 때 노출되지 않는다.

## 권장 진행 순서와 공식 문의안

1. 차량/플랫폼/실제 Android 버전과 배포 형태를 확정하고 Vehicle 권한 승인 현황을 확인한다.
2. 아래 질문에 대한 Pleos 답변으로 자동 시작 경로와 지속 수집 범위를 확정한다.
3. 초기 고지·동의·중지 설정과 사용자 귀속을 설계한다.
4. 승인된 실행 경로, 수집 실행 주체, 지연 업로드를 분리하여 구현하고 cold boot/resume 실차 검증을 한다.

문의 초안(외부 전송하지 않음):

> 일반 Pleos App Market 배포 예정인 차량 관리 앱입니다. 사용자가 사전에 동의한 연료·누적 주행거리 수집을 위해 Activity 표시 없이 동작하려 합니다. 일반 서드파티 앱이 BOOT_COMPLETED receiver로 작업을 시작하는 것이 허용되는지, 별도 자동 실행 승인이나 OEM 설정이 필요한지 확인 부탁드립니다. 매 시동의 suspend/resume 복귀 시에도 사용할 수 있는 공개·승인된 시작 경로가 있나요? 지원되는 FGS 유형·권한·사용자 알림 요건과 주행 중 Vehicle 데이터 구독 허용 범위, CAR_ENERGY/CAR_DRIVE 권한 사전 승인 절차도 안내 부탁드립니다. 대상 Connect 플랫폼·차량·Android 버전은 확정 후 함께 전달하겠습니다.

질문 중 별도 자동 실행 승인이 필요한지는 **미확인**이다. 문서가 명시적으로 승인 필요라고 한 것은 Vehicle SDK 퍼미션이며, 이를 자동 실행 전체의 승인 의무로 확대하지 않는다.

## 열람 기록

아래 모든 문서는 2026-10-31에 열람했다. 본문 텍스트 기준이며 이미지·다운로드·로그인 자료는 확인하지 않았다. API 시그니처 변경은 제안하지 않았다.

| 제목·직접 URL | 관련 절·접근 결과 |
| --- | --- |
| [Pleos Connect 앱 설계 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro) | 현재 하위 정책 9개 및 디자인 가이드 목록, 본문 확인 |
| [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model) | 앱 실행·window 전환, 포그라운드·백그라운드 설계, 본문 확인 |
| [성능 및 리소스 효율을 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt) | 앱 시작·메모리/CPU/배터리, 본문 확인 |
| [API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability) | SDK 및 property 가용성, 본문 확인 |
| [보안 및 개인정보 보호를 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy) | 계정/세션·최소 수집·외부 전송, 본문 확인 |
| [앱 보안 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide) | 민감 데이터·암호화·통신·컴포넌트/권한, 본문 확인 |
| [주행 안전을 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline) | 주행 규제 상태·주의 분산 UX, 본문 확인 |
| [지역별 주행 규제 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy) | DO 미선언 기본 동작·지역별 기능 제한, 본문 확인 |
| [주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide) | CarUxRestrictions 연동·활성 비트, 본문 확인. 비공개 Figma 사양서는 미확인 |
| [FusedLocation Declaring permission](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions) | dangerous 권한·위치 차단·Android 위치 정책 미보장, 본문 확인 |
| [FAQ — 앱 실행 환경 및 플랫폼 리소스](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq) | 플랫폼 식별·지역·에뮬레이터 한계, 본문 확인. 자동 시작 명시 근거는 발견하지 못함 |
| [App Review Guidelines](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide) | 성능·개인정보·안전 및 심사 한계, 본문 확인 |
| [앱 기능 및 성능 안정성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability) | 기능/성능·Vehicle SDK 퍼미션 심사 기준, 본문 확인 |
| [사용자 개인정보 및 보안](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security) | 고지·명시 동의·철회·보안 우회 금지, 본문 확인 |
| [Android FGS background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | 부팅 예외 및 유형/while-in-use 별도 제한, 본문 확인 |
| [Android Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) | 유형 선언·권한, dataSync와 표준 location 제약, 본문 확인 |
| [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15) | BOOT_COMPLETED FGS 제한, 본문 확인 |
| [Android Foreground services overview](https://developer.android.com/develop/background-work/services/fgs) | 사용자 인지 가능한 작업·알림, 본문 확인 |
| [Android Task scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent) | WorkManager 재부팅 재예약·제약·재시도, 본문 확인. 기존 `/persistent-work` URL은 접근 실패 후 공식 리디렉션 경로로 확인 |
| [Android Activity security](https://developer.android.com/guide/components/activities/background-starts) | 백그라운드 Activity 시작 제한·예외, 본문 확인 |
| [AOSP Power management](https://source.android.com/docs/automotive/power/power) | Car power state machine 및 suspend/resume, 본문 확인. 이미지 도해는 미확인 |

수행: 관련 소스·기획·기존 실행 기록 확인, 공식 문서 재열람, Android OS/target 조건 대조. 미수행: 빌드·lint·기기 테스트·cold boot/resume 시험·양산 차량 성능 측정·권한 승인 확인·Pleos 문의 전송. 코드 변경이 없는 검토이므로 빌드 성공이나 실차 정책 통과를 주장하지 않는다.
