# 화면 종료 후 수집 유지·정차 충전 알림

2026-11-12, 현재 작업 트리 변경 포함. 이번 변경 범위만 검토한다. 일반 Pleos 차량 관리 앱, Android min28/target36, Pleos SDK 2.3.2 기준이며 실제 양산 차량·지역별 동작은 미검증이다.

## 반영 순서와 결과

1. `RecordingService`가 앱 화면에서 시작되는 포그라운드 서비스로 차량 관측을 소유한다. Activity는 화면 리스너만 연결·해제하며 서비스의 AAOS 연결을 닫지 않는다. 연결·설정과 서비스 알림의 수집 중지로 중단할 수 있다.
2. 기존 암호화 EV 기록과 내구성 전송 큐를 재사용한다. 화면 이탈은 관측 공백을 만들지 않는다. 서비스/프로세스가 실제 중단되면 진행 구간은 마지막 관측 시각·추정 종료·누락으로 복구하며, 새 배터리 끝점과 이어 붙이지 않는다.
3. 양의 충전 증가와 스위치 OFF를 관측해 로컬 저장한 후보만 완료 확인 알림 대상으로 삼는다. 실제 결제 완료로 확정하지 않는다. 최근 10분 이내 후보를 현재 사용자에게 한 번 알리고, 알림 발행 상태를 암호화 저장한다.
4. 완료 알림은 UX baseline과 최근 P단 관측이 모두 유효할 때 표시한다. UX 제한 진입 콜백에서는 즉시 취소하며 P 미확인·사용자 불명/변경에서도 취소한다. 60초 timeout과 탭 시 자동 취소를 적용한다. 탭은 해당 사용자·구간 ID를 검증한 후 충전 관측 상세로 이동하며 주행 제한 중에는 UI 진입을 보류한다.
5. 위치 보강·단가 조회는 Activity가 연결된 동안만 수행한다. 백그라운드에서 새 위치를 조회하지 않는다. 충전량 원본·후보 전송은 유지한다.

## Android·Pleos 근거와 남은 확인

- [Pleos 앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), 포그라운드·백그라운드 전환 절: 지속 작업의 실행·중단·재개 조건과 상태 복원. 본문 확인, 2026-11-12. 이번 변경에서 UI와 수집 수명 분리로 적용한다.
- [성능·리소스 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt), 메모리·CPU 절: 기존 3초 관측 주기를 재사용한다. 실차 CPU/RAM·시동/절전 복귀 성능은 미측정이다.
- [Android 포그라운드 서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), Special use: 승인된 차량 API 기반 차계부 관측을 subtype으로 설명하고 `specialUse` 유형·권한을 선언했다. Google Play 설명을 Pleos 심사 승인으로 간주하지 않는다. Pleos 배포 환경에서 서비스 유형과 차량 권한의 허용 여부는 별도 출시 검증 사항이다.
- [서비스 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): 사용자에게 보이는 Activity에서 시작한다. 부팅 리시버·강제 Activity 실행·절전 방지 wake lock은 추가하지 않는다.
- [포그라운드 서비스](https://developer.android.com/develop/background-work/services/fgs): 수집 실행 알림은 서비스 요구사항이다. 충전 완료 알림만 주행 시 취소하며 서비스 실행 알림까지 강제로 숨기지 않는다.

## 확인 방법

- 앱/테스트 APK 빌드, lint.
- 실제 서비스 시작 → Activity CREATED(onStop) → 수집기 활성 유지 → 명시적 서비스 종료 → 수집 정리.
- 충전 ON·증가·OFF → 화면 리스너 분리 중에도 기록 보존 → 완료 후보 생성 → 알림 발행 표시 저장 → 재생성 후 중복 없음.
- 기존 거리·EV 비용, 오프라인 큐와 사용자 격리 회귀.
- 실차 주행 제한 전환 시 OS 알림 표시/취소 지연, 알림 권한 거부, 장시간 수집 CPU/RAM, Android 시스템 강제 종료·시동 복귀는 출시 전 검증이 남아 있다. START_STICKY는 재시작 요청일 뿐 강제 종료·전원 OFF 동안의 수집 보장이 아니다.


## 이번 작업 검증 결과

- 앱 및 AndroidTest APK 빌드, lint 통과.
- 전체 JVM 단위 테스트 통과(첫 시도는 병행 변경 중 VIN 메서드 참조 누락으로 컴파일 실패했고, 해당 변경이 반영된 뒤 재검증했다).
- 에뮬레이터 기록·암호화 복구·충전 알림 발행 상태 중복 방지·전송 큐·사용자 격리 7개 통합 테스트 통과.
- 별도 실제 서비스 생명주기 테스트 1개 통과: Activity를 CREATED로 전환한 뒤에도 수집 활성 유지, 명시적 서비스 중지 후 비활성 확인. 첫 시도는 테스트 프로세스 종료로 결과를 얻지 못했으며 최신 APK 설치 후 재실행에서 통과했다.
- 실제 충전 ON/OFF 센서 전환과 주행 시 OS 알림 취소의 시각적 실차 검증은 미수행이다. 모델 주입 시험을 실차 센서 검증으로 간주하지 않는다.
