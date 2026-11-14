# Pleos 폼팩터 테스트 환경과 수행 절차

## 정차 상태인데 서브 화면이 제한되는 문제 수정

후속 재현에서 Fuelog는 가상 Display 12에서 실행 중이었지만 CarUxRestrictionsManagerService의 UX 제한 설정은 Display 0에만 있었다. 기본 화면은 정차(`DO=false`, `UxR=0`)였다. AAOS는 설정이 없는 디스플레이의 조회에 전체 제한을 반환한다.

디버그 앱을 Android 에뮬레이터(`ranchu`/`goldfish`)의 보조 화면에서 실행할 때만 기본 디스플레이 컨텍스트로 Car 서비스를 연결한다. 기본 화면의 차량 UX 제한을 그대로 구독하므로 주행 또는 상태 미확인 시 차단은 유지한다. 실제 차량 및 릴리스 앱의 디스플레이별 UX 제한 처리는 바꾸지 않는다.

`AaosUxRestrictionsAndroidTest`로 가상 화면 생성 후 기본 화면과 동일한 실시간 UX 상태를 받는지 확인했다. 주행 제한·상태 미확인 차단과 정차 복구도 검증했다. 빌드·단위 테스트·Lint를 통과하고 에뮬레이터에 디버그 APK를 업데이트했다.

근거: [AAOS CarUxRestrictionsManagerService](https://android.googlesource.com/platform/packages/services/Car/+/master/service/src/com/android/car/CarUxRestrictionsManagerService.java)의 디스플레이별 제한 조회 및 설정이 없을 때 전체 제한 반환 동작.

2026-11-14 확인. 현재 작업 트리(HEAD a67ab4b 및 미커밋 변경 포함)의 화면 테스트 준비 범위이며 전체 정책 감사가 아니다. 차량·국가별 출시 적합성은 판정하지 않는다.

## 확인한 현재 환경

- AVD: `Pleos_Connect_v2.1.0`, serial `emulator-5554`.
- `ai.pleos.connectwebconsole/.FormFactorConsoleActivity` 설치 확인.
- Homebrew는 `/opt/homebrew/bin/brew`에 있다. `go`는 PATH 및 일반 설치 경로에서 찾지 못했다.
- 8095·8096 LISTEN 프로세스 없음. Bridge 미실행 상태.
- 실행 프로세스에 `-qt-hide-window`가 있어 현재는 내장/숨김 창 실행 상태다. 공식 테스트용 별도 창으로 재실행해야 한다. `-grpc-use-token`은 이미 사용 중이다.
- CarHomeActivity는 Manifest에 screenOrientation을 고정하지 않으며 화면 폭 600dp를 기준으로 행/열을 나눈다. 이것만으로 가상 디스플레이 대응을 통과했다고 판단하지 않는다.
- Emulator 버전 CLI 확인은 Qt neon 오류로 실패했다. 설치 버전 확인 및 Bridge README의 Emulator 34.1.1 이상 조건 충족 여부는 미확인이다. Pleos 이미지 버전(2.1.0)과 Android Emulator 도구 버전은 서로 다르다.

## 환경 구성

1. Go 1.22 이상 설치. 이 Mac은 Homebrew가 있으므로 `brew install go` 후 `go version`으로 확인할 수 있다. 공식 macOS 설치 패키지를 사용해도 된다.
2. Android Studio Settings > Tools > Emulator에서 `Launch in the Running Devices tool window`를 해제한다. IDE 버전에 따라 표시 위치/문구는 달라질 수 있다. 현재 AVD를 정상 종료하고 Device Manager에서 별도 창으로 재실행한다. Wipe Data/앱 삭제는 하지 않는다.
3. 공식 ZIP을 내려받아 압축을 해제하고 `host-display-bridge` 디렉터리에서 실행한다.

```sh
go run . --avd "Pleos_Connect_v2.1.0"
```

`listening on http://127.0.0.1:8096`와 해당 AVD의 gRPC target 로그를 확인하고 터미널을 유지한다. Bridge는 macOS arm64도 지원한다. 최초 실행은 Go 모듈 다운로드를 위해 네트워크가 필요하다.

ADB 명령에 사용할 PATH는 터미널마다 설정한다.

```sh
export PATH="/Users/sonsubin/Library/Android/sdk/platform-tools:$PATH"
adb devices
adb -s emulator-5554 emu avd name
adb -s emulator-5554 shell am start --user current -n ai.pleos.connectwebconsole/.FormFactorConsoleActivity
adb -s emulator-5554 forward tcp:8095 tcp:8095
```

공식 가이드는 에뮬레이터의 FormFactorConsole 앱 실행을 안내한다. ZIP README에 따르면 호스트 브라우저 UI는 `http://127.0.0.1:8095`, Bridge API는 8096이다. 브라우저 접속 실패 시 FormFactorConsole이 먼저 실행됐는지 확인한다. 기존 Energy Console의 8088과는 별도이다.

백엔드 URL이 localhost:8080인 현재 디버그 구성은 AVD/ADB 재시작 뒤 아래 연결도 복구한다.

```sh
adb -s emulator-5554 reverse tcp:8080 tcp:8080
```

## 수행 순서

1. FormFactorConsole 상단 UNAVAILABLE이면 Bridge 및 8096 로그를 확인한 뒤 Refresh. DISCONNECTED/ DISPLAY2 없음은 가상 디스플레이 생성 전 준비 상태다.
2. Display preset을 선택하고 Portrait/Landscape를 선택한다. 실제 표시되는 dp/DPI/px를 테스트 결과에 적는다. 문서 예시인 12.9인치 Portrait, 14.6인치 Landscape부터 확인하고 콘솔의 나머지 preset도 두 방향으로 반복한다.
3. 가상 디스플레이 생성 후 새 DISPLAY2 창의 앱 목록에서 Fuelog를 실행한다. 목록이 없으면 앱 목록 실행을 선택한다.
4. 아래 표의 화면을 순회하고 크기·방향 전환 및 백그라운드 복귀 후에도 다시 확인한다.
5. 다음 preset 전에는 기록과 입력을 저장하고 가상 디스플레이를 제거한다. 제거 시 그 디스플레이 앱은 강제 종료된다.

| 화면 | 프로젝트별 확인 항목 |
| --- | --- |
| 홈/상단 탭 | 차량명·거리·금액 가독성, 탭 글자 잘림, 가로 공간·여백 |
| 운행 | 통계 막대/스크롤, 상세·N빵, 충전 이력 미노출 |
| 충전 | 충전량·금액 탭, 일·월·년, 미확인 값 표시, 후보 상세 |
| 정비 | 목록·금액, 추가/수정 입력, 키보드 표시 후 라벨·저장 버튼 도달, Driving view 가림 여부 |
| 연결·설정 | 별칭 편집, QR 크기·정렬, 권한 대화상자, 연결 상태 복원 |
| 공통 | 긴 텍스트·빈 목록·데이터 많은 목록, 버튼 줄바꿈/터치, 스크롤 끝, window 크기 변경 후 화면·선택 기간 유지 |

P단에서 화면 검증을 수행하고 앱을 함께 배치하는 실제 1/2-window 전환도 별도로 확인한다. 가상 폼팩터 테스트와 실제 window 전환은 관련되지만 동일한 검증으로 간주하지 않는다. 화면 경계와 키보드 테스트는 과거 정비 입력 영역 문제 재발 확인에 특히 중요하다.

## 종료/문제 해결

- 가상 디스플레이 제거 → Bridge 터미널 Ctrl+C → `adb -s emulator-5554 forward --remove tcp:8095`.
- DISPLAY2 창이 안 보임: 내장/Headless 실행 여부 확인.
- AVD 탐색 실패: 실제 AVD 이름·부팅 완료 여부 확인. ZIP README의 `--endpoint-file` 방식도 가능하다.
- gRPC token 없음: 정상 종료 후 별도 창 실행 명령에 `-grpc 8554 -grpc-use-token`을 사용한다. 토큰을 로그/문서에 복사하지 않는다.
- 기존 앱/연결 데이터를 보존하려면 업데이트 설치(`adb install -r`)를 사용한다. 이 프로젝트에서는 연결된 기기에 Gradle connectedDebugAndroidTest를 실행하면 UTP가 앱을 제거한 이력이 있어 수동 폼팩터 확인에는 쓰지 않는다.

## 근거와 실행 범위

- 공식 [폼 팩터 테스트 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide): 준비, Bridge, FormFactorConsole, 테스트 및 종료 본문 확인. 페이지 이미지 자체는 열람하지 않았다.
- 공식 배포 [Host Display Bridge 1.0.0 ZIP](https://nexus-playground.pleos.ai/repository/raw-releases/host-display-bridge/1.0.0/host-display-bridge.zip): README/go.mod 확인. 8095 UI/8096 Bridge와 macOS 지원·gRPC 인증 조건 확인.
- 공식 [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model): window, 방향·크기 변경, 상태 복원 본문 확인.
- 공식 [Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator): 현재 이미지·SDK 환경 본문 확인.
- Go [설치 안내](https://go.dev/doc/install): macOS 설치 및 go version 확인 본문 확인.

이번 작업은 환경 진단·수행 안내 작성까지다. Go 설치, AVD 재시작, Bridge 실행 및 실제 DISPLAY2 테스트는 수행하지 않았다. 앱 소스와 기존 미커밋 변경은 수정하지 않았다.

## Bridge 연결 오류 복구

후속 점검에서 Go와 Bridge는 실행 중이었으나 health/display 요청이 gRPC Unauthenticated로 실패했다. Bridge 실행 뒤 AVD가 재시작되어 인증 정보가 오래된 상태였다. 기존 Bridge에 SIGINT를 보내 정상 종료하고 공식 ZIP 소스를 `/private/tmp/pleos-formfactor-tools/host-display-bridge`에 풀어 `env -u EMULATOR_GRPC_TOKEN go run . --avd Pleos_Connect_v2.1.0`으로 현재 AVD를 자동 탐색해 재실행했다. 복구 도중 AVD PID도 변경되어 고정 endpoint-file 대신 자동 탐색을 사용했다. `/health`의 ok=true, `/api/window-policy/display`의 ok=true/available=true/created=false를 확인했고 8095 포워딩을 복구했다. Bridge는 이번 세션에서 실행 상태로 유지한다.

현재 AVD 명령에는 여전히 `-qt-hide-window`가 있다. 실제 DISPLAY2 별도 창 생성은 아직 검증하지 않았다. AVD를 다시 시작하면 Bridge도 Ctrl+C 후 재실행해 최신 인증 정보를 읽게 한다. 토큰 값은 보고서에 저장하지 않는다.
