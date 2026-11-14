# 연결 화면 및 사용자별 충전 알림 설정

연결 화면은 차량 별칭·연필 편집 모달, 인증 사용자 이메일, 연결·권한 상태 칩, 기록 수집/충전 알림 스위치를 표시한다. 설치 ID는 화면 하단에 작게 표시한다. 위치 보조 설명은 동의 버튼 옆 정보 버튼으로 연다. API 상태 박스와 연결 상세 버튼은 표시하지 않는다.

## 서버 저장

백엔드 `/Users/sonsubin/IdeaProjects/fuelog-backend`의 Flyway `V20__user_notification_settings.sql`이 `user_notification_settings` 테이블을 만든다. `user_id`가 기본키이며 `charge_complete_enabled`, `updated_at`을 저장한다. 기본값은 OFF다.

- `GET /installation/users/{userId}/notification-settings`: 현재 연결 사용자 설정 조회.
- `PUT /installation/users/{userId}/notification-settings`: `{ "chargeCompleteEnabled": true }` 저장.
- 응답: `{ "user_id": "사용자 UUID", "chargeCompleteEnabled": true }`.

두 API 모두 설치 Bearer로 인증하며 현재 페어링 사용자만 조회·변경할 수 있다. 저장은 설치 행 잠금과 같은 트랜잭션에서 처리한다. 다른 사용자 ID와 인증 누락을 거부한다.

Android는 로그인·페어링 복원 시 서버 설정을 조회하며 사용자별 값이 확인된 경우에만 충전 알림을 허용한다. 설정 저장 실패 시 마지막 서버 확인값으로 스위치를 되돌린다. 사용자 변경 후 이전 사용자의 응답은 폐기한다. OS 알림 권한은 별개이며 OFF로 저장하면 알림 권한이 있더라도 충전 알림을 보내지 않는다. 위치 보조 동의와 차량 기록 수집 설정은 기존 처리 방식을 유지한다.

## 적용 및 검증

서버 배포·재시작 시 Flyway V20을 적용해야 새 API를 사용할 수 있다. 운영 DB에는 이 작업에서 직접 SQL을 실행하지 않았다.

Android 빌드·단위 테스트·Lint, 연결 화면·연결 해제·알림 저장 및 사용자 격리 테스트를 검증했다. 백엔드에서는 사용자 설정 저장/복원, 타 사용자 접근 거부, 인증 누락 거부 및 기존 이메일 API 테스트를 검증했다. 실차와 OS 알림 권한 전체 흐름은 별도 확인이 필요하다.
