# 최초 동의·서비스 권한 요청 변경 검토

2026-11-10, 현재 HEAD 및 기존 미커밋 변경을 포함해 이번 동의 흐름만 검토했다. 전체 정책 감사는 아니다. 대상은 Pleos Connect 정차 화면이며 Vehicle 2.3.2, FusedLocation 1.1.3, compile/target SDK 36이다. 양산 차량·출시 국가·최종 카테고리는 미확인이다.

## 결론과 적용

- P08 관찰 범위 적합: 위치 수집 목적·서버 전송을 설명하고 선택 동의/거절을 저장한다. 차량 위치 보조 동의는 인증 사용자 확인 후 받는다. 다른 사용자에게 동의를 승계하지 않는다. 설정에서 변경·철회 가능하다.
- S01 관찰 범위 적합: 최초 모달은 휴대폰 차량 연결 완료(`connection.linked`) 및 기존 CarUxRestrictions의 `allowed` 확인 뒤 표시한다. 연결 전에는 QR 연결 화면을 먼저 표시한다. 제한 진입·onStop 시 앱 모달을 닫으며 제한 중 권한 요청을 시작하지 않는다. OS 소유 권한 창의 주행 전환 동작은 실차 검증 필요다.
- P07 검증 필요: 권한 허용은 차량 API 지원을 보장하지 않는다. 기존 Capability/property/위치 isSupported 및 오류 처리를 유지한다.
- 배터리 읽기는 현재 AAOS 에너지 API를 쓰기 위해 필요한 dangerous 권한이다. 앱 전체 이용이나 Pleos 정책상 필수 동의라고 해석하지 않는다. 거부 시 배터리 기능을 제한한다.

구현 근거: `CarHomeActivity.showStartupConsent`, `requestServicePermission`, `openDiagnostics`, `navigate`, `onRequestPermissionsResult`, `onStop`; `HomeRecorder.setLocationAssistConsent`, `locationAssistDecisionMade`. 장치 권한 안내 이력은 startup_consent SharedPreferences에 저장한다. 허용 여부의 진실은 OS checkSelfPermission이며 저장된 안내 이력으로 권한을 추정하지 않는다. 위치 보조는 기존 recording preferences의 사용자별 현재 동의 상태를 사용한다. 사용자 교체 후에는 새 동의를 받는다.

## 발견과 최소 수정

P1 / 기능 장애 확인: 기존 위치 보조 거절은 owner를 비워 미선택과 구분할 수 없었다. 거절한 사용자도 현재 owner로 보관하여 재시작 시 다시 묻지 않도록 수정했다. 완료 조건: 동의/거절 후 재생성 시 결정 유지, 다른 사용자에게 미승계.

P2 / 조건부 위험: OS에서 권한 철회 또는 재요청 제한 시 저장된 동의만으로 서비스가 동작할 수 없다. 서비스 버튼마다 실제 권한을 확인하고 요청 모달 및 앱 권한 설정 진입을 제공한다. 완료 조건: 허용된 권한은 재요청하지 않고 거부 상태에서만 안내한다.

## 공식 근거

아래 문서는 2026-11-10 웹 본문 열람에 성공했다. 이미지·로그인 자료·실차 검증은 수행하지 않았다.

| 문서·관련 절 | URL | 적용 |
| --- | --- | --- |
| 앱 설계 가이드·하위 정책 목록 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro | 현재 목록 확인 |
| 보안 및 개인정보 보호 설계·개인정보 최소 수집/계정 관리 | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy | 목적 설명·사용자 분리 |
| 앱 보안 가이드·민감 데이터 관리 | https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide | 기존 데이터·인증 저장 경로 보존 |
| API 호환성 및 가용성·Pleos SDK/AAOS | https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability | 허용과 지원 여부 구분 |
| 주행 규제 개발 가이드·CarUxRestrictions 연동 | https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide | 기존 플랫폼 제한 적용 |
| Android Car·PERMISSION_ENERGY | https://developer.android.com/reference/android/car/Car#PERMISSION_ENERGY | 에너지 정보 접근에 필요한 dangerous 권한 |

## 검증

lintDebug 통과. RecordingUserAndroidTest 에뮬레이터 테스트에서 허용·거절 재생성 보존 및 사용자 변경 시 미승계를 확인했다. 기존 미커밋 EnergyAvailabilityAndroidTest의 Files.writeString 컴파일 오류 때문에 임시 Gradle init script로 해당 파일만 제외했다. 원본 파일은 수정하지 않았다.

실차의 시스템 권한 UI, 영구 거부/OS 철회, 주행 전환 및 창 크기별 모달 실측은 미검증이다. 빌드·테스트 성공은 심사 통과나 실차 정책 준수 보장이 아니다.
