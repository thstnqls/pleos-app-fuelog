# fuelog Pleos 정책 첫 점검

검토일: 2026-10-25 KST. 현재 작업 트리(기존 미커밋 변경 포함)를 정적으로 검토했다.
대상은 `ROW_1_LEFT` 문 위치를 한 번 읽고 재조회하는 Java 데모다.
Vehicle SDK 2.3.2, minSdk 34, compile/targetSdk 36이다. README의 minSdk 36 설명은 실제 설정과 다르다.
출시 국가·차량·플랫폼 버전·카테고리·주행 중 사용 의도는 미확인이다.
앱 이름만으로 연비/주유 기록 서비스의 요구사항을 가정하지 않았다.

## 공식 자료 열람

출처와 세부 절은 [정책 지도](../../.agents/skills/pleos-policy-review/references/policies.md)에 기록했다.
intro, P01–P09, D01과 주행 규제·오디오·suspend·보안·디자인 세부 페이지를 열람했다.
모두 위 검토일 기준이다. Capability는 검색 발췌만 확인했고 직접 본문 추출은 실패했다.
버튼 치수 이미지 직접 열람도 실패했다. 오디오 매트릭스·색상표의 전체 이미지 값과
비공개 사양·실차 결과는 미확인이다. 최신 SDK 문서와 2.3.2의 시그니처 일치 여부도 미검증이다.

## 정책 적용 표

| 정책 | 판단 | 코드 근거 / 남은 확인 |
| --- | --- | --- |
| P01 주행 안전 | 검증 필요 | MainActivity에 주행 제한 연동 없음. 주행 사용 의도부터 확정. |
| P02 지역별 제한 | 검증 필요 | Manifest에 DO 선언 없음. 정차 전용이면 기본 차단 동작과 양립. |
| P03 카테고리 | 검증 필요 | 제품 카테고리·배포 범위 미정. 데모를 Mobility Services로 자동 분류하지 않음. |
| P04 화면·생명주기 | 검증 필요 | 유연한 단일 레이아웃·insets·onDestroy 해제는 존재. 복귀 시 최신값 갱신 및 window 실측 필요. |
| P05 하드웨어·네트워크 | 관찰 범위 적합 | Vehicle SDK 경유, 직접 하드웨어 접근 없음. 앱 자체 네트워크 기능 없음. |
| P06 SDK 정책 | 검증 필요 | Vehicle 사용. 동등한 AAOS API 가용성과 대상 차량 권한 확인 전 교체 판단 유보. |
| P07 API 가용성 | 문제 있음 | getCurrentDoorPosition 전 capability·area 접근 수준 확인 없음(F01). |
| P08 보안·개인정보 | 검증 필요 | 계정/외부 분석/저장 없음. 예외 원문·차량값 로그와 백업 설정 검토(F04/F05). |
| P09 성능·리소스 | 검증 필요 | 지속 폴링 없음. CPU/RAM·cold start 미측정. 오디오 항목은 재생 기능 없어 적용 제외. |
| D01 디자인 | 검증 필요 | loading·비활성 버튼·20/24sp·64dp 버튼은 존재. 실제 터치 치수·mm 높이·대비·양 방향 미측정. |

## 발견 사항과 수정안

### F01 · P1 · 조건부 위험: 차량별 문 위치 지원을 확인하지 않고 조회

- 근거: `app/src/main/java/com/pleos/fuelog/data/vehicle/VehicleSdkClient.java:48`에서 초기화 직후 고정 area를 조회한다. 지원/접근 수준 확인은 없다. 실패 콜백과 null 표시는 이미 있다.
- 정책: [API 호환성 및 가용성 — SDK API 호환성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability), [문 위치 예제 — 지원 범위 확인](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app). 차량별 지원 확인 지침이다.
- 발생 조건·영향: 읽기 미지원 또는 대상 area가 없는 차량에서는 핵심 조회 기능을 사용할 수 없다. 현재는 일반 오류/null과 지원 여부를 구분할 수 없다. 실제 대상 차량에서의 장애는 아직 재현하지 않았다.
- 수정안: `VehicleSdkClient`에서 `checkDoorPositionCapabilities`를 이용해 읽기 접근 및 area를 확인한 뒤 조회하도록 한다. `NONE/WRITE`만 허용된 상태는 읽기 미지원으로, 조회 실패·연결 오류는 별도 상태로 전달한다. 범위가 없으면 열림/닫힘 해석을 하지 않는다. 2.3.2의 메서드·반환형은 구현 전에 확인한다.
- 완료 조건: 지원+읽기 가능 시 조회, 미지원/읽기 불가 시 재조회 비활성·이유 표시, area 없음·null·일시 오류를 각각 표시하고 재연결 후 재평가한다. capability 실패를 영구 미지원으로 고정하지 않는다.

### F02 · P1(주행 사용 목표일 때) · 검증 필요: 주행 사용 범위가 정해지지 않음

- 근거: `app/src/main/AndroidManifest.xml:16`에 DO 선언이 없고 `MainActivity.java:23` 이후 주행 제한 상태 연동도 없다.
- 정책: [지역별 정책 — 주행 상황에 따른 앱 사용 여부](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy), [주행 규제 구현 — CarUxRestrictions 연동](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), [앱 카테고리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category). 플랫폼 동작·조건부 의무다.
- 발생 조건·영향: 주행 중 사용을 목표로 하면 DO 미선언 기본 정책상 앱이 차단된다. 정차 전용 데모라면 이것은 위반 근거가 아니다. 문 상태 조회를 물리적 도어 제어와 같은 제한으로 단정할 수도 없다.
- 수정안: 정차 전용이면 현재 정책을 유지하고 주행 차단/정차 복귀를 검증한다. 주행 사용이 필요하면 카테고리·조회 화면의 허용 범위를 확인한 뒤 제한 전용 상태를 설계한다. `CarUxRestrictionsManager` 초기값·변경 콜백·연결 실패를 반영하고, 조회 콜백이 도착해도 금지된 버튼을 다시 활성화하지 않도록 `refreshButton` 활성 조건을 한곳에서 계산한다. DO 선언은 그 이후 검토한다.
- 완료 조건: 주행 상태에서 시작, 조회 도중 제한 진입, 백그라운드 복귀, 제한 해제 및 지역별 활성 비트 조합에서 허용 범위대로 동작한다. 불명확한 읽기 화면의 적용 범위는 Pleos 기술 지원 확인 과제로 남긴다.

### F03 · P2 · 조건부 위험: 화면 복귀 시 과거 문 위치가 유지될 수 있음

- 근거: `MainActivity.java:44`와 버튼에서만 조회한다. onStart/onResume 갱신·수신 시각 표시는 없고, client는 `MainActivity.java:86`의 onDestroy에서 해제한다. 지속 폴링/구독은 없다.
- 정책: [앱 구조 및 동작 모델 — 포그라운드·백그라운드 전환](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model). 상태 복원·리소스 정리 설계 원칙이다.
- 발생 조건·영향: 다른 앱 뒤로 갔다 돌아오는 동안 문 상태가 바뀌면 과거 값이 계속 표시될 수 있다. 한 번 조회한 결과라는 데모 의도를 명확히 표시하면 영향이 줄어든다.
- 수정안: 마지막 조회 시각을 표시하거나 foreground 진입 시 1회 재조회한다. 중복 요청과 이전 요청의 지연 응답은 요청 식별자로 걸러낸다. 반복 구독이 필요해질 때만 lifecycle에 맞춰 등록·해제한다. onStop에서 무조건 SDK를 해제하는 변경은 근거 없이 하지 않는다.
- 완료 조건: 창 전환 중 문 상태 변경 후 복귀하면 새 값 또는 과거 조회임을 표시한다. 재생성·지연 응답에서 파괴된 화면 갱신이나 오래된 값 덮어쓰기가 없다.

### F04 · P2 · 조건부 위험: 예외 원문과 차량값 로그의 출시 처리 미정

- 근거: `MainActivity.java:65`는 문 원시값을 로그로 남기고 `MainActivity.java:75`는 예외 메시지를 UI와 Logcat에 전달한다. 현재 예외에 민감정보가 실제 포함됐다는 증거는 없다.
- 정책: [개인정보 설계 — 개인정보 최소 수집](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy), [앱 보안 — 로그 데이터 관리](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide). 민감정보가 포함될 때 마스킹/비활성화가 필요하다.
- 수정안: 제품 UI에는 미지원·연결 실패·일시 불가 등 짧은 메시지를 매핑한다. 진단 로그는 필요한 오류 코드만 남기고 debug/release 경로를 구분한다. 예외 원문·식별자·차량값의 필요성과 민감도를 검토한다.
- 완료 조건: release 로그와 오류 UI에 토큰·사용자/차량 식별자·불필요한 상세 정보가 노출되지 않으며 개발 시 원인 진단은 가능하다.

### F05 · P2 · 권고 미반영: 기본 백업 설정 유지

- 근거: `app/src/main/AndroidManifest.xml:7`의 `allowBackup=true`. 현재 앱에 민감정보를 영속 저장하는 코드가 없어 유출을 확정할 수 없다.
- 정책: [앱 보안 — 자동 백업 비활성화](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide). 비활성화/민감 파일 제외는 권고이며 non-GAS AAOS에서는 Google 자동 백업이 동작하지 않는다는 조건이 있다.
- 수정안: 백업이 불필요하면 비활성화한다. 다른 Android 환경에 백업 기능이 필요하면 `backup_rules.xml`과 `data_extraction_rules.xml`의 클라우드·기기 전송 범위를 실제 저장 파일에 맞춰 정의한다.
- 완료 조건: 백업 지원 환경에서 대상 파일 목록을 확인해 향후 인증정보·차량 기록이 의도 없이 포함되지 않도록 한다.

## 진행 순서와 검증 한계

1. 제품 사용 범위·카테고리·대상 차량/지역을 정하고 F01의 기능 가용성을 확인한다.
2. 주행 사용이 필요하면 F02를 설계하고, F03의 복귀·신선도 처리와 함께 검증한다.
3. 출시 전 F04/F05, 실제 window·대비·치수 및 CPU/RAM을 확인한다.
4. README의 minSdk 36과 Gradle의 34 불일치는 개발 환경 문서 개선 사항이다. 정책 위반으로 보지 않으며 SDK 최소 요구·대상 이미지 확인 후 정정한다.

이 점검에서는 앱 소스·의존성·Manifest를 변경하지 않았다. 에뮬레이터/실차 실행,
SDK 2.3.2 시그니처 검증, Gradle 빌드·lint, 성능·디자인 측정은 수행하지 않았다.
확정적인 의무 위반이나 실제 심사 실패가 확인됐다는 의미는 아니다.
