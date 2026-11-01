# Pleos 위도·경도 조회 API

구현: `com.pleos.fuelog.location.PleosLocationClient` (FusedLocation SDK 1.1.3).
메인 스레드에서 호출하며 성공/실패 콜백도 메인 스레드로 전달한다.

## 권한

```xml
<uses-permission android:name="pleos.car.permission.FUSED_LOCATION" />
```

이 권한은 dangerous이므로 앱에서 런타임 승인을 받아야 한다. 클라이언트도 조회 전에 권한을 검사하며,
허용되지 않았으면 SDK를 초기화하지 않고 `PERMISSION_DENIED`를 반환한다.
Google 위치 SDK의 권한 동작을 대신 적용하거나 백그라운드 위치 권한을 추가하지 않는다.
[공식 권한 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions).

## 호출 예시

아래 코드는 Activity의 메인 스레드에서, 위치 권한을 이미 승인받은 뒤 실행한다.
`locationClient`는 화면 필드로 보관하며 화면을 다시 시작할 때 새 인스턴스를 만든다.

```java
PleosLocationClient locationClient = new PleosLocationClient(this);

PleosLocationClient.Request pending = locationClient.getCurrentLocation(
        new PleosLocationClient.Callback() {
            @Override public void onSuccess(PleosLocationClient.Position position) {
                double latitude = position.getLatitude();
                double longitude = position.getLongitude();
                long measuredAtMillis = position.getTimestampMillis();
                // 화면에 표시하거나 호출한 기능에 전달한다. 좌표를 로그로 출력하지 않는다.
            }

            @Override public void onError(PleosLocationClient.ErrorCode code, Exception cause) {
                // PERMISSION_DENIED, UNSUPPORTED, NO_DATA, INVALID_DATA,
                // TIMEOUT, SDK_ERROR, CLOSED를 구분해 안내한다.
            }
        });

// 필요 없어진 요청만 취소한다. 취소 후 콜백은 전달하지 않는다.
// pending.cancel();

// Activity.onStop() 등 소유 화면이 종료되는 시점에 호출한다.
// locationClient.close();
```

- `getCurrentLocation(callback)`: 업데이트 리스너를 등록하고 요청 시각 이후에 측정된 유효한 위치 1건을 반환한다.
  SDK timestamp가 요청 이전이거나 기기 시각보다 5초 넘게 미래인 이벤트는 받지 않는다.
  새 GNSS 측정을 강제로 시작하는 SDK API가 아니며, 10초 동안 해당 업데이트가 없으면 `TIMEOUT`이다.
- `getLastKnownLocation(callback)`: SDK의 `getLastKnownLocation()`으로 마지막 저장 위치를 반환한다.
  오래된 timestamp도 원값 그대로 전달하며 `Position.isFromUpdate()`는 false이다. 새 측정으로 표시하지 않는다.
- `Position`: 위도·경도·고도·방향·confidence 원값·측정 timestamp와 업데이트 출처를 제공한다.
  confidence를 미터 단위 정확도로 변환하지 않는다.
- 위도 -90~90, 경도 -180~180의 유한한 숫자만 성공 처리한다. 음수와 실제 0 좌표는 정상이다.
  null이나 잘못된 좌표를 `(0, 0)`으로 대체하지 않는다.
- 한 클라이언트에는 요청 1개만 유지한다. 재조회 시 이전 요청을 취소하며 늦은 응답은 버린다.
- 성공·오류·시간 초과·취소 시 리스너를 해제하고 SDK를 release한다. 화면 종료도 같은 정리를 수행한다.
  닫힌 클라이언트는 재사용할 수 없으며 새 인스턴스를 만든다.

SDK 초기화 전 `isSupported()`를 확인한다. 연결 대기와 비동기 콜백은 SDK를 통해 처리하며,
임의 sleep으로 연결 시간을 가정하지 않는다.
[위치 조회와 업데이트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access),
[리스너·SDK 해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release).

## 앱에서 확인

1. 정차 상태에서 `차량 · 위치` 탭을 연다.
2. `현재 위도·경도 조회`를 누르고 권한 요청이 나오면 허용한다.
3. 권한 처리 후 같은 버튼을 다시 누르면 새 업데이트를 최대 10초 기다린다.
4. 저장된 마지막 위치를 확인하려면 `마지막 위치 조회`를 누른다.

차량의 위치 공유 설정으로 데이터 접근이 차단된 경우 SDK가 null이나 오류를 반환할 수 있다.
`showLocationAccessBlockedPopup()`은 차단 안내용이며 권한을 허용하는 API가 아니다.
현재 앱은 자동으로 설정을 바꾸지 않고 데이터 없음/오류 안내를 표시한다.

## 검증

`PleosLocationClientTest` 7개는 SDK 대역으로 권한·서비스 지원 검사, 오래된 캐시 제외,
음수·0 좌표, null·범위 오류, timeout, 리스너 해제, 재조회·close 뒤 늦은 응답 차단을 확인한다.
기기 전체 회귀 테스트 20개와 빌드·lint·단위 테스트가 통과했다.
현재 Pleos 이미지에서 위치 서비스 패키지 및 dangerous 권한 정의를 확인했다.
사용자 권한 승인 후 실제 좌표가 공급되는지 여부는 위 앱 조작으로 확인한다. 실좌표를 검증 기록에 남기지 않는다.
