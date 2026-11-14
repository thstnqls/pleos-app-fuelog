# FUELOG(풀록)

**우수한친구들** — 차량 운행·충전 기록과 공용 정비 정보를 확인하는 Pleos Connect 차계부 앱입니다.

## APK

[우수한친구들_FUELOG_release.apk](apk/우수한친구들_FUELOG_release.apk) · 서명된 release 버전

## 실행 방법

```sh
adb install -r 'apk/우수한친구들_FUELOG_release.apk'
```

1. Pleos Connect 에뮬레이터에서 **풀록**을 실행합니다.
2. 정차 상태에서 차량 별칭을 입력하고, 로그인한 모바일 앱으로 차량 QR을 스캔해 연결합니다.
3. 필요한 위치·배터리·알림 권한을 선택하고 운행·충전 기록과 정비 정보를 확인합니다.

백엔드는 `http://sheetnews.ddns.net:3001/`에 배포되어 있습니다. 별도 서버 설치 없이 인터넷 연결과 모바일 앱이 필요합니다.

## 개발·테스트 환경

- Pleos Connect Emulator **2.1.0**, Android 14 / API 34
- 기본 화면: **2560 × 1440 px, 160 dpi**
- 폼팩터 보조 화면: **1700 × 1185 px, 200 dpi**
- Java 17, Gradle Wrapper 8.11.1, compileSdk·targetSdk 36, minSdk 28

소스 빌드 시 `local.properties`에 Android SDK 경로(`sdk.dir`)를 설정합니다.

```sh
./gradlew :app:assembleDebug -PAPI_BASE_URL=http://sheetnews.ddns.net:3001/
```
