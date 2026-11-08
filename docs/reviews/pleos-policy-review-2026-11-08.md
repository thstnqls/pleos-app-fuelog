# Clean Mobility UI 변경 검토

검토일: 2026-11-08. 코드 기준: HEAD `170d3cb`와 작업 중인 미커밋 변경 포함.
이번 검토는 CarHomeActivity의 색상·공통 카드·페어링·상태 피드백 변경에 한정하며 전체 정책 감사가 아니다.
기존 사용자 연결·기록 관련 미커밋 변경은 유지했다.

## 구현 범위

- 주간: #F4F6F8 배경, 화이트 카드, 에메랄드 CTA, 20dp 곡률, 1dp 보더, 20dp 내부 여백, 14dp 카드 간격.
- 야간: #111B27 배경, #1E293B 카드, 민트 강조. 시스템 DayNight 설정을 따른다.
- QR·6자리 확인 코드: 주야간 공통 슬레이트 카드, 민트 테두리·코드, 0.25em 자간. 코드 비교와 명시적 차량 승인은 유지한다.
- 연결 상태: 중립·주의·오류 인라인 배너. 자동 오류 팝업을 제거하고 사용자가 요청하는 상세 보기와 연결 해제 확인은 유지한다.
- 차량용 기존 글자 크기와 58~68dp 버튼 영역을 유지했다. 모바일 11~15sp 규격으로 축소하지 않았다.
- CTA는 #00C473 위 #142B25 글자를 사용한다. 보조 텍스트는 밝은 배경 대비를 위해 모바일 #6B7684보다 어두운 #606C7B로 조정했다.
- 데이터가 없는 차트, 새로운 인증 흐름, 새 의존성은 추가하지 않았다.

국가·양산 차량·출시 카테고리는 미확인. Manifest의 DO 미선언 및 현재 UX 제한 시 전체 조작 차단에 따라 정차 전용으로 검토했다.
compile/target SDK 36, min SDK 28. Vehicle 2.3.2, Location 1.1.3. 실행 환경은 Pleos Connect v2.1.0 AVD / Android 14.

## 정책 적용 및 열람 기록

아래 본문은 모두 2026-11-08 열람했다. 이미지·SVG의 치수나 팔레트 전체 수치를 검증한 것으로 간주하지 않는다.

| 항목 | 근거 및 관련 절 | 판단 |
| --- | --- | --- |
| 정책 목록 | [앱 설계 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro), 9개 하위 설계 정책 및 디자인 가이드 목록 | 변경 관련 항목만 검토 |
| P01 주행 안전 | [주행 안전](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline), 주행 규제 상태에 따른 앱 동작 | 정적 관찰 범위 적합, 실차 검증 필요 |
| P02 지역 제한 | [지역별 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy), DO 미선언·QR 로그인 | 정차 전용 유지, 플랫폼 차단 실차 확인 필요 |
| S01 구현 | [주행 규제 개발](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), CarUxRestrictions 연동 | 기존 현재값 조회·리스너 및 제한 시 화면 비움 유지 |
| D01 대비·가독성 | [UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro), 색상 및 대비·글꼴 및 가독성 | 대비를 고려한 색상 적용, 물리적 크기 실측 필요 |
| D02 레이아웃 | [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout), Application window | 기존 600dp 기준 열 전환 유지, 모든 창 크기 검증 필요 |
| D04 색상 | [Colors](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors), Basic·Static·System Color | 주야간·고정 페어링·시맨틱 역할 분리 |
| D05 글자 | [Typography](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography), Operation guideline | 기존 차량 글자 크기 유지 |
| D06 버튼 | [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button), States | Ripple·Disabled 색상 적용, SVG의 공식 물리 치수 미확인 |

P03/P05~P09 전체 카테고리·차량 API·보안·성능·오디오 검토는 이번 변경 범위 밖이다. 디자인 변경으로 기존 구현 전체의 준수를 확정하지 않는다.

## 남은 검증

### UI-01 · P2 · 차량에서의 크기와 대비 실측

판단: **검증 필요**. D01의 대비 4.5:1 및 물리 글자 높이 기준은 실제 디스플레이·주야간 조명·시야 거리에서 확인해야 한다.
근거: `app/src/main/java/com/pleos/fuelog/CarHomeActivity.java`의 `label`, `button`, `headerAction`, `renderConnect`; `app/src/main/res/values{,-night}/car_colors.xml`.
발생 조건·영향: 작은 앱 창·글자 확대·야간에서 긴 차량명, 확인 코드, CTA가 잘리거나 알아보기 어려울 수 있다.
최소 수정안: 대상 차량 측정 후 해당 공통 함수의 글자·패딩·줄바꿈만 보정한다. 규제 제한을 완화하지 않는다.
완료 조건: 1/2 window, portrait/landscape, 주야간, 글자 확대에서 핵심 정보와 조작이 유지되고 실측 기준 충족.

### UI-02 · P2 · 규제 전환 시 페어링 화면 검증

판단: **검증 필요**. P01/P02/S01의 제한 반영은 기존 구현을 유지했으나 이번 실행은 주행 전환 시험이 아니다.
근거: `CarHomeActivity.onStart`, `render`, `AaosVehicleDiagnosticService.start/onRestrictions`.
발생 조건·영향: QR·확인 코드 표시 중 제한 진입이나 서비스 연결 실패 시 조작이 남으면 정차 전용 의도에 어긋난다.
최소 수정안: 우선 기존 차단 동작을 차량에서 검증하고, 실패 시 `allowed` 갱신 및 화면/대화상자 정리 지점만 보정한다.
완료 조건: 제한 활성 시작·정차→주행·복귀·서비스 연결 실패에서 QR·코드·승인 조작이 숨겨지고 지연 응답으로 다시 표시되지 않음.

## 검증 결과

- `:app:assembleDebug`, `:app:testDebugUnitTest` (38개), `:app:lintDebug` 통과. lint 오류 없음; 기존 저장·의존성 관련 경고는 남아 있다.
- `CarDesignAndroidTest` 1개 통과: 미요청 대기 상태, HTTP 503 오류 배너, 자동 팝업 없음, 확인 코드 및 승인 버튼 유지.
- Pleos AVD에서 가상 차량명과 가상 코드 `123456`으로 확인 카드 렌더링을 시각 확인했다. 실제 연결·개인정보를 미리보기로 사용하지 않았다.
- 실제 백엔드 페어링 완료, 전체 화면의 모든 상태, 야간 화면의 실측, 주행 규제 전환, 성능 측정은 미실행.

수정 순서: UI-01 대상 차량·창 크기 확인 → UI-02 규제 전환 확인 → 필요한 경우 공통 UI 치수 보정. 빌드 성공은 정책 심사 통과를 뜻하지 않는다.
