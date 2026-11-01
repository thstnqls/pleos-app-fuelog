Minimum Android SDK	Android API 34 (Pleos Connect v2.1.0 에뮬레이터 기준)
Gradle 버전	8.0 이상
개발 언어	Java
Pleos Connect Emulator	버전 2.1.0
Pleos Connect Virtual Console	Pleos Connect Virtual Console를 지원하는 Pleos Connect Emulator 최소 버전: 2.1.0

## 차량 데이터 진단

`app`을 Pleos Connect 에뮬레이터에서 실행하면 한 Activity에서 연료 잔량·연비·EV 충전율·전비·누적거리,
AAOS 배터리 잔량·현재 사용 가능 용량·공칭 용량, 선택 VIN·현재/마지막 위치를 확인할 수 있습니다.
상단 `연료` / `전기 · EV` / `주행 · 거리` / `차량 · 위치` 탭으로 구분합니다.
전체 조회와 상태 요약은 선택한 탭에만 적용하며 값·상태·단위·API 출처를 따로 표시합니다.
연료 탭은 AAOS 잔량·탱크 용량·계산 비율·표준 순간 연비와 Pleos `Fuel.kt` getter 5종을 비교합니다.
SDK 5종은 전체/개별 조회할 수 있으며, 표준 속성도 차량의 실제 지원·권한이 있어야 수신됩니다.
연료 비율/EV 충전율/거리 구독은 직접 시작·중지하며 화면 이탈 시 해제됩니다.
상단 `주행 · 거리` 탭에서는 드라이브 모드, Trip 거리·시간, 크루즈 속도·상태,
브레이크, 총 누적거리, 전륜 조향각 13개 API를 전체 또는 개별 조회합니다.
실행 방법·SDK/단위·권한·테스트 항목은 [차량 데이터 진단 안내](docs/vehicle-diagnostics.md)를 참조하세요.
위도·경도 콜백 API와 화면 조회 방법은 [Pleos 위치 API](docs/pleos-location-api.md)에 정리되어 있습니다.

## 백엔드 API 연결

루트 `local.properties`에 아래 값을 추가하고 Gradle Sync를 실행합니다.
기존 `sdk.dir` 설정은 유지하세요. `local.properties.example`에도 입력 예시가 있습니다.

```properties
API_BASE_URL=https://your-domain.example/api/v1/
```

`-PAPI_BASE_URL=...` 빌드 옵션이 있으면 해당 값이 우선합니다. URL 미설정 시
빌드는 가능하지만 `ApiModule.create()` 호출은 명확한 설정 오류를 반환합니다.
URL 변경 후 다시 빌드해야 합니다. DB 계정이나 비밀 키는 앱 설정에 넣지 않습니다.

`com.pleos.fuelog.api` 패키지는 OkHttp 기반의 비동기 API 모듈입니다.
클라이언트 인스턴스를 생성해 재사용하고, 백엔드 명세에 맞게 상대 경로와 JSON을 전달합니다.
아래 `records` 경로와 JSON은 사용 예시이며 실제 백엔드 명세가 아닙니다.

```java
ApiClient api = ApiModule.create();
String token = null; // 인증을 사용하면 로그인 API가 반환한 access token 전달

ApiClient.ResultCallback callback = new ApiClient.ResultCallback() {
    @Override public void onSuccess(ApiClient.ApiResponse response) {
        String json = response.getBody(); // 백엔드 명세에 맞게 파싱, 204 응답은 빈 문자열
        // 화면 변경은 Activity.runOnUiThread(...) 등으로 메인 스레드에 전달
    }

    @Override public void onHttpError(ApiClient.ApiResponse response) {
        int status = response.getStatusCode(); // 401, 404, 422, 500 등
        String errorBody = response.getBody();
    }

    @Override public void onFailure(java.io.IOException error) {
        // 네트워크 단절, 타임아웃, 요청 취소 등
    }
};

// 각 호출은 독립적인 예시입니다. 생성 결과가 필요한 수정/삭제는 응답 후 실행하세요.
api.create("records", "{\"value\":100}", token, callback);           // POST
okhttp3.Call read = api.read("records", java.util.Collections.singletonMap("page", "0"),
        token, callback);                                           // GET
api.read("records/1", null, token, callback);                        // GET 단건
api.update("records/1", "{\"value\":200}", token, callback);          // PUT
api.patch("records/1", "{\"value\":300}", token, callback);           // PATCH
api.delete("records/1", token, callback);                            // DELETE
// 화면 종료 등으로 더 이상 결과가 필요 없으면 read.cancel() 호출
```

경로 앞에 `/`를 붙이지 않습니다. 예를 들어 base URL이 `/api/v1/`로 끝나면
`records/1` 요청은 `/api/v1/records/1`로 전송됩니다. GET 쿼리 값은 자동 인코딩됩니다.
모든 콜백은 백그라운드 스레드에서 실행되고 응답 리소스는 모듈에서 닫습니다.
HTTP 2xx는 `onSuccess`, 나머지 상태는 `onHttpError`, 통신 실패는 `onFailure`로 전달합니다.
반환되는 `Call`로 요청을 취소할 수 있으며, 취소도 `onFailure`로 전달됩니다.
리다이렉트와 자동 연결 재시도는 사용하지 않습니다. 인증 갱신과 업무상 재시도는 호출부에서 처리합니다.

에뮬레이터에서 개발 PC 서버에 접근할 때는 debug 빌드에서
`http://10.0.2.2:8080/api/v1/` 등을 사용할 수 있습니다. 포트와 경로는 서버에 맞게 변경하세요.
release 빌드는 HTTPS만 허용합니다. 서버 API·DTO가 확정되면 이 모듈 위에 기능별
Repository와 응답 모델을 추가할 수 있습니다.

## Supabase Auth · 이메일 매직링크

Java/OkHttp에서 Supabase Auth REST API를 사용합니다. 클라우드 프로젝트에 연결하므로
Supabase CLI나 로컬 Supabase 서버를 설치할 필요가 없습니다.

Supabase Dashboard → Authentication → URL Configuration에서 아래 **동일한 값**을
Site URL과 Redirect URLs에 각각 등록합니다. 마지막 `/`도 포함하세요.

```text
com.pleos.fuelog://login-callback/
```

`local.properties`에 다음 값을 추가하고 다시 빌드합니다. `sdk.dir`와 기존 API 설정은 유지하세요.
Gradle `-P` 옵션으로도 주입할 수 있습니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_PUBLIC_KEY
```

프로젝트 URL과 publishable 키는 Supabase의 프로젝트 설정에서 복사합니다.
legacy `anon` JWT도 지원합니다. `secret`/`service_role` 키는 앱에 넣지 않습니다.
이 값들은 APK에 포함되는 공개 설정입니다. `API_BASE_URL`은 업무 백엔드 주소이며 별도 설정입니다.

Authentication의 Email 제공자를 활성화하고 가입을 허용하세요. 처음 이용하는 이메일은
매직링크 요청 시 계정을 생성합니다. **Magic Link와 Confirm signup 이메일 템플릿은
기본 `{{ .ConfirmationURL }}` 링크를 사용**하세요. 이 구현은 Supabase `/verify`를 거쳐
`?code=...`로 앱에 복귀하는 PKCE 흐름입니다. 별도 웹 서버의 `/auth/confirm`이나
`token_hash`로 바로 연결하는 이메일 템플릿은 현재 구현 대상이 아닙니다.
기본 메일 발송 서비스의 수신자 제한/발송 제한에 걸리면 테스트 계정 및 SMTP 설정을 확인하세요.

정차 상태에서 진단 화면의 **Fuelog 로그인 → 로그인 링크 받기**로 테스트합니다.
로그인을 요청한 **동일한 기기/에뮬레이터**에서 최근 메일 링크를 열어야 합니다.
PC 메일에서 연 링크는 에뮬레이터 앱으로 자동 전달되지 않습니다.
재요청하면 이전 PKCE verifier가 대체되며, 로컬 인증 대기 시간은 1시간입니다.
이메일 링크 클릭만으로는 로그인되지 않고, 저장된 verifier와 서버의 일회용 코드를 교환해야 합니다.
앱 재실행 후에도 verifier와 세션은 복원되며, 토큰은 Android Keystore 기반 AES-GCM으로
암호화해 `noBackupFilesDir`에 저장합니다. 로그·화면 상태·백업에는 토큰을 넣지 않습니다.
오류/취소 링크는 오류 안내를 표시합니다. 링크의 access token/refresh token은 직접 수용하지 않습니다.

인증된 업무 API는 다음과 같이 생성합니다. 앱에서 인스턴스를 재사용하세요.

```java
ApiClient api = ApiModule.createAuthenticated(context);
// interceptor가 현재 로그인 토큰을 붙입니다. 요청별 token 인수는 null로 전달합니다.
api.read("records", null, null, callback);
```

요청 전 만료 60초 이내인 세션은 갱신하고 회전된 refresh token을 먼저 저장합니다.
갱신은 직렬화하며, 미로그인/갱신 실패 시 업무 API를 전송하지 않습니다.
401 응답을 받은 업무 요청을 자동 재실행하지 않습니다. 로그아웃은 이 기기의 토큰과 대기 중인
로그인 정보를 지우고 서버의 현재 세션 종료를 요청합니다. 서버 연결 실패 시 로컬 로그아웃은 유지됩니다.
Auth 콜백은 백그라운드 스레드에서 실행됩니다. `accessTokenBlocking()`은 API interceptor용이며
메인 스레드나 Auth 콜백 안에서 호출하지 않습니다.

검증 명령:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pleos.fuelog.auth.AuthAndroidTest
```

단위 테스트는 PKCE 교환·잘못된 링크·재사용·만료·프로젝트 변경·토큰 회전·로그아웃 실패를 검증합니다.
기기 테스트는 Manifest 딥링크 수신과 Keystore 암호화 저장/복원/변조 거부를 검증합니다.
실제 메일 발송과 로그인 완료는 위 프로젝트 설정 후 별도로 확인해야 합니다.

`Pleos_Connect_v2.1.0` 이미지는 `android.car` 대신 `ai.pleos.playground.car` 공유 라이브러리를 제공합니다.
현재 Manifest는 두 라이브러리를 선택적으로 선언하며 해당 이미지에 debug APK 설치를 확인했습니다.
테스트 명령의 기기 번호는 `adb devices`에서 확인한 현재 Pleos 에뮬레이터 번호로 바꾸세요.

참고: [Native Mobile Deep Linking](https://supabase.com/docs/guides/auth/native-mobile-deep-linking),
[Redirect URLs](https://supabase.com/docs/guides/auth/redirect-urls),
[Auth REST 명세](https://github.com/supabase/auth/blob/master/openapi.yaml).
