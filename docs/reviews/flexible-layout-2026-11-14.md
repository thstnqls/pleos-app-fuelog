# 가변 화면 및 840×1184 입력 영역 수정

## 시스템 키패드 잘림 원인

에뮬레이터의 `dumpsys window windows`에서 Display 16은 840×1184px, 160dpi였으며, `ai.umos.inputmethod`의 InputMethod 윈도우가 폭 1554px를 요청했다. 키보드 프레임은 화면 폭인 840px로 제한되어 있었다. 키보드 자체의 내부 배치와 요청 폭은 Fuelog 앱에서 변경할 수 없으며 Pleos 시스템 IME의 작은 디스플레이 대응이 필요하다. 앱 수정으로 시스템 키패드 전체 표시가 해결됐다고 판정하지 않는다.

## 앱 변경

- 공통 화면에서 시스템 바·디스플레이 컷아웃·IME 여백의 최댓값을 반영하고 adjustResize를 명시했다. 키보드가 표시되면 본문 스크롤 영역이 가용 높이로 줄어든다.
- 연결 화면, 홈 카드, 제조사 정비 카드, 권한 상태, 통계 이동 버튼, 진단 버튼은 실제 컨테이너 폭에 따라 행/열을 전환한다. 디스플레이 전체 폭만으로 중첩 카드의 열 수를 정하던 제약을 없앴다.
- 960dp 미만에서는 상단을 간결한 배치로 표시한다. 연결 별칭 입력 폼은 부모 폭을 채운다.
- N빵 대화상자 본문을 스크롤할 수 있게 했다.

## 검증

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` 통과.
- ADB 수동 instrumentation으로 `AdaptiveRowAndroidTest`, `WindowInsetsAndroidTest`, `CarDesignAndroidTest` 3개 통과. 앱 삭제 없이 `adb install -r`로 업데이트했다.
- 레이아웃 측정 테스트: 840×1184, 1184×840, 480×700 창과 키보드 여백의 표시/해제. 카드 컨테이너는 320/768/1112/1600dp 폭을 왕복하며 행/열 전환과 내용 보존 검증.
- 위 크기 검증은 자동 측정 테스트다. 모든 폼팩터의 실제 키패드 육안 검증 또는 실차 검증을 뜻하지 않는다.
- 기존 `StartScreenAndroidTest`의 정비 입력·저장·완료·재시도 흐름도 통과(1개). 첫 실행은 예외 로그 없이 프로세스가 종료되어 재실행했으며, 재실행은 정상 통과했다.
