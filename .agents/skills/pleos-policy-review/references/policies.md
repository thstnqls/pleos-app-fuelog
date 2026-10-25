# Pleos 정책 및 세부 가이드 지도

확인일: 2026-10-25 (KST). 공식 한국어 문서 기준의 요약이며 최신 원문을 대체하지 않는다.
아래 URL은 오탈자처럼 보여도 실제 공식 경로를 유지한다(`driving-restiction-policy`).

출발점: [앱 설계 가이드 intro](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro).
본문의 설계 정책 9개 전체와 연결된 디자인·주행 규제·오디오·보안 상세 문서를 확인했다.
디자인 라이브러리 전체를 확인한 것은 아니며, UI 검토 시 사용 컴포넌트의 하위 문서를 추가로 연다.

## 설계 정책

### P01 주행 안전

[주행 안전을 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
— 주행 규제 상태에 따른 앱 동작, 주의 분산 방지 UX 설계 원칙.

`CarUxRestrictions`에 맞춰 기능과 UI를 제한하고 규제 상태의 영상 시작·재개를 막는다.
간결한 정보, 얕은 메뉴, 큰 터치 영역, 음성·프리셋 중심 입력을 검토한다.
구현 근거는 S01, 오디오가 있으면 S02, UI 치수는 D01/D06을 추가 확인한다.

### P02 지역별 주행 제한

[지역별 주행 규제 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)
— 주행 상황에 따른 앱 사용 여부, 지역별 기능 허용 범위.

DO 미선언 앱은 주차 시 사용 가능, 주행 시 사용 불가라는 기본 동작이 있다.
북미·유럽·그 외의 키보드·검색·미디어 설정 허용 범위가 다르다. 표의 검색은 웹 검색이 아닌 시스템 통합 검색이다.
도어·시트·차량 설정, 영상, QR 로그인, 긴 글은 주행 제한을 검토해야 한다.
실제 제한은 플랫폼/양산 차량에 따라 다를 수 있으므로 표를 앱에 하드코딩하지 않고 S01을 따른다.
InfoLINK 지역별 키보드 예외는 공식 문의가 필요한 조건이며 임의로 허용하지 않는다.

### P03 카테고리

[앱 카테고리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)
— 앱 카테고리 정의, 카테고리별 제약조건.

기획 단계에 카테고리를 명확히 한다. Music/Audio, Maps/Navigation, Mobility Services의
일부 기능이 주행 중 제한적으로 허용될 수 있지만 예시는 포괄적 허용이 아니다.
주유소·충전소·차량 관리가 예시에 있어도 입력·설정·상세 화면까지 자동 허용되는 것은 아니다.

### P04 화면·생명주기

[앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)
— 멀티 윈도우, 화면 방향·크기, 포그라운드·백그라운드 전환.

Driving view를 가리거나 종료할 수 없다. 하나/두 앱 window 및 portrait/landscape에 대응한다.
화면에서 사라져도 앱이 종료되지 않을 수 있다. 복귀·프로세스 재생성의 상태 복원,
백그라운드 UI 갱신·센서·폴링 정리, 지속 작업의 실행 조건을 확인한다. D02/D03도 참조한다.

### P05 하드웨어·통신

[하드웨어 접근 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture)
— 차량 하드웨어 접근 정책, 주행 중 네트워크 환경 대응.

물리 제어·센서 직접 접근 대신 승인된 플랫폼 API를 사용한다. 모바일용 전화·SMS·센서 등의
지원 여부를 확인하고 필요 없는 기능은 제거하거나 선택적으로 동작시킨다.
네트워크 기능에는 오프라인 안내, 캐싱, 타임아웃·재시도 등 권장 대응을 검토한다.

### P06 SDK 선택·마이그레이션

[SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)
— AAOS Alignment, 하위 호환성, API 선택 기준.

동등한 AAOS 표준 API가 있으면 표준 사용을 권장하고 특화 영역에 Pleos SDK를 사용한다.
SDK API가 표준으로 편입될 때 최소 2개 메이저 버전의 하위 호환 지원 정책을 설명한다.
표준 연료·속도 데이터와 제조사 특화 제어의 접근 경로를 각각 검토한다.
이 정책 자체가 특정 API의 무조건적 폐기 또는 즉시 마이그레이션을 뜻하지 않는다.

### P07 기능 가용성

[API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability)
— SDK API 호환성, AAOS Vehicle Property 가용성.

Vehicle은 도메인별 `checkXXXCapabilities`, FusedLocation은 `isSupported`로 확인한다.
NaviHelper는 내비 탑재 여부가 조건이다. AAOS property는 `VehiclePropertyIds`와
`CarPropertyManager`로 실제 지원·접근 상태를 확인하며 모델명 분기로 대체하지 않는다.
제공되지 않는 특수 property는 직접 구현 전 기술 지원 검토가 필요하다. 문 조회는 S05/S06 참조.

### P08 보안·개인정보

[보안 및 개인정보 보호 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy)
— 계정/세션, 개인정보 최소 수집, 차량 외부 데이터.

운전자·소유자 변경에 따른 로그아웃/계정 전환, 토큰 보호, 수집 목적·동의, 로그 마스킹을 검토한다.
분석 이벤트의 차량·사용자 식별자를 가명화/익명화하며 주행 행동 데이터의 마케팅 사용을 구분한다.
등록 상세 보안 기준은 S04를 읽는다.

### P09 성능·오디오

[성능 및 리소스 효율](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt)
— 앱 시작, 메모리·CPU·배터리, 오디오·미디어.

최소 초기화·지연 로딩 및 불필요한 상주·폴링 축소를 검토한다.
CPU 기준은 주행 내비 **15% 이하**, 주행 비내비 **5% 미만**, 정차 모든 앱 **25% 이하**,
RAM **400 MB 이하**다. 측정 환경과 실제 수치를 기록한다.
오디오 재생 기능이 있으면 포커스 획득·상실 대응과 S02/S03을 검토한다.

## 연결된 구현·보안 세부 문서

### S01 주행 규제 구현

[주행 규제 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
— CarUxRestrictions 연동, UX 규제 항목과 매핑.

`CarUxRestrictionsManager.registerListener()`와 변경 콜백으로 제한을 반영한다.
DO 선언 앱도 활성 제한을 직접 적용한다. 플랫폼이 지역별 비트를 제공한다.
연동 시 초기 현재값, 리스너 수명, 시스템 연결 및 API 접근 조건은 해당 AAOS 명세까지 확인한다.
공개되지 않은 UX 규제 Figma 사양서를 읽은 것으로 간주하지 않는다.

### S02 오디오

[오디오 포커스 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)
— 요청·변경·duck·다중 홀더·검증 시나리오.

재생 전 `AudioAttributes`와 `AudioFocusRequest`를 사용하고 거부·상실·재획득·duck을 처리한다.
Pleos 상호작용 매트릭스를 기준으로 하며 다중 홀더에서는 R > E > C 순서다.
Gleo·통화·내비·긴급음 시나리오를 검증한다. 일반 앱에 시스템 오디오 중재 로직 전체를 임의 구현하지 않는다.
이미지 매트릭스 전체 셀은 미전사 상태이므로 구체 조합을 구현할 때 원본 이미지를 확인한다.

### S03 Suspend / Resume

[Suspend 모드 오디오 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)
— 진입 시 처리, Resume 시 처리.

일반 오디오는 suspend 전에 중단하고 포커스를 반납하며 상태를 저장한다.
suspend 중 새 재생/포커스 요청을 하지 않는다. resume 후 현재 상태를 재평가하고 필요 시
재요청한다. 자동 재생은 OEM/사용자 정책 조건이며 예외를 일반 앱에 확대하지 않는다.

### S04 앱 등록 보안

[앱 보안 가이드](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide)
— 민감 데이터, 암호화, 통신, 컴포넌트·권한.

저장 위치와 관계없이 민감정보 암호화, 로그 마스킹/비활성화, Keystore 키 보호,
URL 내 인증정보 배제, 암호화 통신과 TLS 최소 1.2, 필요한 권한과 컴포넌트만 노출하는 기준을 확인한다.
배포 인증서·디버깅 비활성화, 승인된 API만 사용, 비밀정보 하드코딩 금지,
SDK 취약점·보안 패치 검토도 출시 시 확인한다. 웹브라우저 앱은 별도 추가 요구사항이 있다.
백업 비활성화와 민감 파일 백업 제외는 권고다. non-GAS AAOS에서 Google 자동 백업은 동작하지 않는다.
WebView·외부 입력·원격 명령·앱 간 전송은 기능이 존재할 때 해당 절을 추가 대조한다.

### S05 Capability / S06 문 위치 예제

[Capability](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/check-capability)
— 접근 수준·area 범위. 공식 검색 결과의 예제는 확인했지만 한국어·영어 페이지 직접 열람은
본문 추출에 실패했다. 시그니처 확정 근거로 단독 사용하지 말고 SDK와 S06을 대조한다.

[Vehicle SDK Sample App](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
— Door Position, 권한, 문제 해결.

문 위치는 `checkDoorPositionCapabilities`로 읽기 접근·area·범위를 확인하고 조회한다.
초기화 반환은 서비스 연결 완료 증거가 아니다. 읽기/구독용 `CAR_DOORS`와
변경용 `CONTROL_CAR_DOORS`를 구분하며 일반 dangerous 권한 팝업 흐름을 가정하지 않는다.
범위 0..1을 모든 차량에 적용하지 않는다. 최신 문서의 시그니처가 Vehicle 2.3.2와 같은지는
실제 의존성 명세/클래스에서 재확인한 뒤 패치를 작성한다.

## 디자인 세부 문서

### D01 기본 원칙

[UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)
— 즉시성, 대비, 가독성, 알림, 주야간·확장성.

한 손 조작과 간결한 정보, 대비 **4.5:1 이상**, 핵심 글자 높이 **5 mm 이상**,
보조 **3 mm 이상**, 텍스트 **3줄 이하**를 검토한다. 영어 단일 메시지는 **120자 또는 24단어 이하**,
한 줄 **80자 이하**이며 이 제한을 한국어에 임의 환산하지 않는다.
입력 반응 **0.25초** 기준과 지연 시 상태 표시를 확인한다. 이 기준을 SDK 응답 시간 보장으로 확대하지 않는다.
운전자 관련 알림·광고 제한, 주야간 대비, 다양한 화면과 언어 대응을 검토한다.

### D02–D06 레이아웃·기초·버튼

| ID | 확인한 세부 문서 | 검토 용도 / 열람 한계 |
| --- | --- | --- |
| D02 | [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout) | 시스템 레이어와 앱 영역, 1/2 window, 양 방향 대응. 모든 디스플레이 해상도를 추정하지 않는다. |
| D03 | [Window Policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window) | window 전환·배치 상세. 치수/동작을 주장하기 전 본문·도해 재확인. |
| D04 | [Colors](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors) | 색상 토큰·의미·주야간. 이미지 토큰 수치 전체는 전사하지 않음. |
| D05 | [Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography) / [Iconography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/iconography) | 글자 위계·시인성·아이콘. sp/px와 D01의 mm 기준을 같은 단위로 간주하지 않는다. |
| D06 | [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button) | Pressed/Disabled/Loading 상태. `basic_size.svg` 직접 열람은 실패하여 공식 터치 치수는 미확인. 모바일 48dp나 현재 64dp를 Pleos 합격 기준으로 대입하지 않는다. |

## 열람 범위의 한계

위 페이지들의 텍스트를 확인했으며 S05는 검색 발췌까지만 확인했다. 이미지로만 제공되는 버튼 치수·색상표·오디오 매트릭스의
전체 값, 다운로드 디자인 자료, 비공개 사양 및 실차 적용 결과는 확인하지 않았다.
이 목록은 설계 가이드와 관련 상세 근거의 지도이며 앱 등록·테스트·SDK 문서 전체의 복제본이 아니다.
실제 기능/출시 요구에 맞춰 필요한 링크를 추가하고 검토 기록에 열람 여부를 남긴다.
