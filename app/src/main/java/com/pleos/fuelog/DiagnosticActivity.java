package com.pleos.fuelog;

import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pleos.fuelog.diagnostics.AaosVehicleDiagnosticService;
import com.pleos.fuelog.diagnostics.CarInfoDiagnosticService;
import com.pleos.fuelog.diagnostics.DiagnosticSession;
import com.pleos.fuelog.diagnostics.DriveDiagnosticService;
import com.pleos.fuelog.diagnostics.DriveDiagnosticService.Reading;
import com.pleos.fuelog.diagnostics.EvBatteryDiagnosticService;
import com.pleos.fuelog.diagnostics.FuelDiagnosticService;
import com.pleos.fuelog.diagnostics.LocationDiagnosticService;
import com.pleos.fuelog.diagnostics.OdometerDiagnosticService;
import com.pleos.fuelog.ui.DiagnosticReadingView;
import com.pleos.fuelog.ui.ConsentDialogs;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.pleos.playground.vehicle.Vehicle;

/** One parked-only screen for SDK validation; service classes own all SDK calls. */
public final class DiagnosticActivity extends AppCompatActivity {
    private static final String LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION";
    private static final int TAB_FUEL = 0, TAB_EV = 1, TAB_DRIVE = 2, TAB_INFO = 3;
    private final Map<String, DiagnosticReadingView> rows = new LinkedHashMap<>();
    private final List<Button> actions = new ArrayList<>();
    private TextView connection;
    private TextView summary;
    private Button primaryQuery;
    private LinearLayout readings;
    private LinearLayout fuelPage;
    private LinearLayout evPage;
    private LinearLayout drivePage;
    private LinearLayout infoPage;
    private int activeTab;
    private String requestedLocationKey = "location.current";
    private boolean started;
    private boolean allowed;
    private DiagnosticSession session;
    private Vehicle vehicle;
    private FuelDiagnosticService fuel;
    private EvBatteryDiagnosticService evBattery;
    private OdometerDiagnosticService odometer;
    private DriveDiagnosticService drive;
    private CarInfoDiagnosticService carInfo;
    private LocationDiagnosticService location;
    private AaosVehicleDiagnosticService aaos;
    private androidx.appcompat.app.AlertDialog locationConsent;

    // 1. 진단 화면 진입: 탭 복원 → 차량 서비스 연결 → 정차 상태 확인.
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.diagnostic_title);
        // Sensor values and optional location/VIN stay in memory and out of screenshots/backups.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        activeTab = savedInstanceState == null ? getIntent().getIntExtra("diagnostic.category", TAB_FUEL)
                : savedInstanceState.getInt("diagnostic.category", TAB_FUEL);
        if (savedInstanceState != null && "location.last".equals(savedInstanceState.getString("location.permission.key"))) {
            requestedLocationKey = "location.last";
        }
        if (activeTab < TAB_FUEL || activeTab > TAB_INFO) activeTab = TAB_FUEL;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        com.google.android.material.button.MaterialButton back =
                new com.google.android.material.button.MaterialButton(this, null,
                        R.attr.borderlessButtonStyle);
        back.setText("←  차량 화면으로");
        back.setAllCaps(false);
        back.setTextSize(24);
        back.setMinHeight(dp(72));
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        back.setContentDescription("차량 화면으로 돌아가기");
        back.setOnClickListener(view -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
        TabLayout tabs = new TabLayout(this);
        tabs.setTag("diagnostic.tabs");
        tabs.setTabMode(TabLayout.MODE_AUTO);
        tabs.addTab(tabs.newTab().setText("연료"));
        tabs.addTab(tabs.newTab().setText("전기 · EV"));
        tabs.addTab(tabs.newTab().setText("주행 · 거리"));
        tabs.addTab(tabs.newTab().setText("차량 · 위치"));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView heading = text(content, "차량 데이터 확인", 26);
        heading.setTypeface(null, Typeface.BOLD);
        text(content, getString(R.string.diagnostic_intro), 14);
        LinearLayout status = card(content, "연결 상태", "정차 중 기능별 지원 상태와 실제 값을 확인하세요.");
        connection = text(status, "연결 대기", 16);
        summary = text(status, "아직 조회하지 않았습니다.", 14);
        summary.setTag("diagnostic.summary");
        LinearLayout primaryActions = actionRow(status);
        primaryQuery = action(primaryActions, "연료 전체 조회", this::readSelectedPage);
        primaryQuery.setTag("query.selected");
        action(primaryActions, "AAOS 권한 요청", this::requestAaosPermissions);
        LinearLayout connectionActions = actionRow(status);
        Button reconnect = button(connectionActions, "연결 재시도", this::reconnect);
        reconnect.setContentDescription("차량 서비스 연결과 UX 제한 다시 확인");
        readings = new LinearLayout(this);
        readings.setOrientation(LinearLayout.VERTICAL);
        content.addView(readings);
        fuelPage = page("page.fuel");
        buildFuelCard(fuelPage);
        evPage = page("page.ev");
        buildEvCard(evPage);
        drivePage = page("page.drive");
        buildDrivePage();
        buildDistanceCard(drivePage);
        infoPage = page("page.info");
        buildOptionalCard(infoPage);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                activeTab = tab.getPosition();
                showSelectedPage();
                scroll.scrollTo(0, 0);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        tabs.selectTab(tabs.getTabAt(activeTab));
        showSelectedPage();
        setContentView(root);
        setActionsEnabled(false);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        reconnect();
    }

    private void reconnect() {
        if (!started) return;
        closeMeasurements();
        if (aaos != null) aaos.close();
        aaos = new AaosVehicleDiagnosticService(this);
        aaos.start((canUse, detail) -> {
            if (!started) return;
            connection.setText(detail);
            allowed = canUse;
            if (canUse && session == null) openMeasurements();
            if (!canUse) closeMeasurements();
            setActionsEnabled(canUse && session != null);
        });
    }

    private void openMeasurements() {
        resetRows();
        session = new DiagnosticSession((key, source, state, value, time) -> {
            DiagnosticReadingView row = rows.get(key);
            if (row == null) { addRow(readings, key, key, source); row = rows.get(key); }
            row.render(source, state, value, time);
            updateSummary();
        });
        try {
            vehicle = new Vehicle(this);
            vehicle.initialize();
            fuel = new FuelDiagnosticService(vehicle.getFuel(), session);
            evBattery = new EvBatteryDiagnosticService(vehicle.getEvBattery(), session);
            odometer = new OdometerDiagnosticService(vehicle.getOdometer(), session);
            drive = new DriveDiagnosticService(vehicle.getDrivingMode(), vehicle.getOdometer(),
                    vehicle.getBrake(), vehicle.getSteeringWheel(), session);
            carInfo = new CarInfoDiagnosticService(vehicle.getCarInfo(), session);
            location = new LocationDiagnosticService(this, session);
            connection.append("\nVehicle 초기화 요청됨 · 조회 응답으로 기능별 연결 확인");
        } catch (RuntimeException error) {
            closeMeasurements();
            connection.append("\nVehicle 초기화 오류: " + error.getClass().getSimpleName());
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("diagnostic.category", activeTab);
        outState.putString("location.permission.key", requestedLocationKey);
        super.onSaveInstanceState(outState);
    }

    // 2. 진단 대상 선택: 탭을 바꾸어도 자동 조회하지 않는다.
    private void showSelectedPage() {
        fuelPage.setVisibility(activeTab == TAB_FUEL ? View.VISIBLE : View.GONE);
        evPage.setVisibility(activeTab == TAB_EV ? View.VISIBLE : View.GONE);
        drivePage.setVisibility(activeTab == TAB_DRIVE ? View.VISIBLE : View.GONE);
        infoPage.setVisibility(activeTab == TAB_INFO ? View.VISIBLE : View.GONE);
        primaryQuery.setVisibility(activeTab == TAB_INFO ? View.GONE : View.VISIBLE);
        primaryQuery.setText(activeTab == TAB_DRIVE ? "주행 전체 조회"
                : activeTab == TAB_EV ? "전기 전체 조회" : "연료 전체 조회");
        updateSummary();
    }

    // 3. 연료 조회: AAOS 표준값과 Pleos SDK 값을 따로 비교한다.
    private void buildFuelCard(LinearLayout parent) {
        LinearLayout standard = card(parent, "연료 · AAOS 표준", "표준 API로 직접 읽은 값과 잔량 비율 계산값입니다.");
        standard.setTag("energy.fuel.aaos");
        action(standard, "AAOS 연료 4개 조회", () -> aaos.readFuel(session));
        LinearLayout standardActions = actionRow(standard);
        action(standardActions, "잔량·비율 조회", () -> aaos.readFuelLevels(session));
        action(standardActions, "순간 연비 조회", () -> aaos.readFuelEconomy(session));
        addRow(standard, "aaos.fuel.percent", "연료 잔량 비율 · 계산값",
                "AAOS · FUEL_LEVEL / INFO_FUEL_CAPACITY × 100 · %");
        addRow(standard, "aaos.fuel", "연료 잔량", "AAOS · FUEL_LEVEL · mL");
        addRow(standard, "aaos.capacity", "연료 탱크 용량", "AAOS · INFO_FUEL_CAPACITY · mL");
        addRow(standard, "aaos.fuel.instant", "순간 연비 · 표준 API",
                "AAOS · INSTANTANEOUS_FUEL_ECONOMY · L/100km");
        text(standard, "잔량 비율은 부피 기준 계산값으로 차량 계기판의 보정된 비율과 다를 수 있습니다. 순간 연비는 AAOS 주행거리 조회 권한과 해당 속성 지원이 필요합니다.", 13);

        LinearLayout body = card(parent, "연료 · Pleos SDK 비교", "연료 비율 % · 연비 L/100km\n현재·누적·주유 이후 연비는 SDK 조회 결과입니다.");
        body.setTag("energy.fuel");
        action(body, "Pleos 연료 5개 조회", () -> fuel.readAll());
        LinearLayout updates = actionRow(body);
        action(updates, "연료 비율 구독 시작", () -> fuel.startUpdates());
        action(updates, "구독 중지", () -> fuel.stopUpdates());
        addFuelReading(body, FuelDiagnosticService.Reading.PERCENT);
        addFuelReading(body, FuelDiagnosticService.Reading.CURRENT);
        addFuelReading(body, FuelDiagnosticService.Reading.CUMULATIVE);
        addFuelReading(body, FuelDiagnosticService.Reading.INSTANT);
        addFuelReading(body, FuelDiagnosticService.Reading.REFILL);
        addRow(body, "fuel.stream", "연료 비율 실시간 구독", "Pleos · registerFuelPercent · %");
    }

    private void addFuelReading(LinearLayout parent, FuelDiagnosticService.Reading item) {
        addRow(parent, item.key, item.label, "Pleos · " + item.api + " · " + item.unit);
        Button query = action(parent, item.label + " 조회", () -> fuel.read(item));
        query.setTag("query." + item.api);
    }

    private void readFuel() {
        fuel.readAll();
        aaos.readFuel(session);
    }

    // 4. EV 조회: 충전율·전비·배터리 용량을 확인한다.
    private void buildEvCard(LinearLayout parent) {
        LinearLayout body = card(parent, "전기 · EV", "충전율 % · 전비 km/kWh · 배터리 용량 Wh");
        body.setTag("energy.ev");
        action(body, "전기 정보 조회", this::readEv);
        LinearLayout updates = actionRow(body);
        action(updates, "충전율 구독 시작", () -> evBattery.startUpdates());
        action(updates, "구독 중지", () -> evBattery.stopUpdates());
        addRow(body, "ev.soc", "배터리 충전율", "Pleos · getEvBatteryStateOfCharge");
        addRow(body, "aaos.ev.energy", "배터리 잔량", "AAOS · EV_BATTERY_LEVEL · Wh");
        addRow(body, "aaos.ev.usable", "현재 사용 가능 용량", "AAOS · EV_CURRENT_BATTERY_CAPACITY · Wh");
        addRow(body, "aaos.ev.nominal", "공칭 배터리 용량", "AAOS · INFO_EV_BATTERY_CAPACITY · Wh");
        addRow(body, "ev.current", "현재 운행 전비", "Pleos · getCurrentEvEfficiency · km/kWh");
        addRow(body, "ev.cumulative", "누적 전비", "Pleos · getCumulativeEvEfficiency · km/kWh");
        addRow(body, "ev.instant", "순간 전비", "Pleos · getInstantaneousEvEfficiency · km/kWh");
        addRow(body, "ev.charge", "충전 이후 전비", "Pleos · getSinceChargeEvEfficiency · km/kWh");
        addRow(body, "ev.stream", "충전율 실시간 구독", "Pleos · registerEvBatteryStateOfCharge · %");
    }

    private void readEv() {
        evBattery.readAll();
        aaos.readEvBattery(session);
    }

    // 5. 주행 조회: 주행 상태·거리·시간과 누적거리 구독을 확인한다.
    private void buildDrivePage() {
        LinearLayout state = card(drivePage, "주행 상태", "CAR_DRIVE · 브레이크 true=눌림, false=떼짐\n조향각은 전륜 기준이며 왼쪽은 음수입니다.");
        addDriveRows(state, Reading.MODE, Reading.CRUISE_SPEED, Reading.BRAKE,
                Reading.CRUISE_STATE, Reading.STEERING);
        LinearLayout distance = card(drivePage, "거리", "총 누적거리는 km, Trip 통계 거리는 m입니다. 각각 별도 API로 조회합니다.");
        addDriveRows(distance, Reading.ODOMETER, Reading.TRIP_CUMULATIVE_DISTANCE,
                Reading.TRIP_CURRENT_DISTANCE, Reading.TRIP_INSTANT_DISTANCE, Reading.TRIP_REFILL_DISTANCE);
        LinearLayout time = card(drivePage, "주행 시간", "SDK 원값 · 분 단위");
        addDriveRows(time, Reading.TRIP_CUMULATIVE_TIME, Reading.TRIP_CURRENT_TIME, Reading.TRIP_REFILL_TIME);
    }

    private void addDriveRows(LinearLayout parent, Reading... items) {
        for (Reading item : items) {
            addRow(parent, item.key, item.label, "Pleos · " + item.api + (item.unit.isEmpty() ? "" : " · " + item.unit));
            Button query = action(parent, item.label + " 조회", () -> drive.read(item));
            query.setTag("query." + item.api);
        }
    }

    private void buildDistanceCard(LinearLayout parent) {
        LinearLayout body = card(parent, "주행거리", "1회 조회와 실시간 구독을 각각 표시합니다.");
        LinearLayout queries = actionRow(body);
        action(queries, "누적거리 1회 조회", () -> odometer.read());
        action(queries, "Trip 통계 조회", () -> odometer.readTripCumulativeDrive());
        LinearLayout updates = actionRow(body);
        action(updates, "누적거리 구독 시작", () -> odometer.startUpdates());
        action(updates, "구독 중지", () -> odometer.stopUpdates());
        text(body, "콘솔 Motion → Odometer에서 값을 변경해 비교하세요. 주행 제한·화면 이탈 후에는 구독을 다시 시작하세요.", 13);
        addRow(body, "distance.total", "마지막 1회 조회 · 총 누적거리", "Pleos · getCurrentDistanceDriven · km");
        addRow(body, "distance.stream", "실시간 구독 · 총 누적거리", "Pleos · registerDistanceDriven · km");
        addRow(body, "cumulative.total", "Trip 누적거리 · 별도 통계", "Pleos · getTripCumulativeDrivingDistance · m");
        addRow(body, "aaos.odometer", "표준 API 총 누적거리", "AAOS · PERF_ODOMETER · km");
    }

    // 6. 차량·위치 조회: 사용자가 선택한 VIN 또는 위치만 요청한다.
    private void buildOptionalCard(LinearLayout parent) {
        LinearLayout position = card(parent, "위치 · 위도/경도", "조회 버튼을 누른 경우에만 위치를 요청합니다. 위치 권한 승인 후 조회 버튼을 다시 누르세요.");
        LinearLayout queries = actionRow(position);
        action(queries, "현재 위도·경도 조회", () -> readLocation(true)).setTag("query.location.current");
        action(queries, "마지막 위치 조회", () -> readLocation(false)).setTag("query.location.last");
        addRow(position, "location.current", "현재 위치 · 새 업데이트 1건", "Pleos · registerFusedLocationCallback → 1건 수신 후 해제");
        addRow(position, "location.last", "마지막 저장 위치", "Pleos · getLastKnownLocation · 새 측정이 아님");
        text(position, "현재 위치는 요청 이후 측정된 업데이트를 최대 10초 기다립니다. 데이터가 없으면 차량의 위치 공유 설정과 위치 공급 상태를 확인하세요.", 13);
        LinearLayout body = card(parent, "선택 차량 정보", "VIN은 별도 버튼으로만 조회합니다.");
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        Button toggle = button(body, "VIN 조회 펼치기", null);
        toggle.setOnClickListener(view -> {
            boolean open = fields.getVisibility() != View.VISIBLE;
            fields.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? "선택 정보 접기" : "VIN 조회 펼치기");
        });
        fields.setVisibility(View.GONE);
        body.addView(fields);
        action(fields, "VIN 조회 · 끝 4자리", () -> carInfo.readVin());
        addRow(fields, "vehicle.vin", "VIN · 수신값", "Pleos · getVINInfo");
    }

    private void readLocation(boolean current) {
        requestedLocationKey = current ? "location.current" : "location.last";
        if (checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            try {
                getPackageManager().getPermissionInfo(LOCATION_PERMISSION, 0);
            } catch (PackageManager.NameNotFoundException error) {
                session.begin(requestedLocationKey, "Pleos FusedLocation", "", false)
                        .status("미지원", "이 이미지에 FUSED_LOCATION 권한 정의 없음");
                return;
            }
            if (locationConsent != null) return;
            locationConsent = ConsentDialogs.builder(this, "차량 위치 읽기",
                    "사용 목적\n진단 화면에서 차량의 현재 또는 마지막 수신 위치와 측정 시각을 확인합니다. 이 진단 결과는 서버로 전송하지 않습니다.\n\n"
                    + "다음 시스템 권한 창 안내\nPleos 시스템에서 이 위치 권한을 ‘알 수 없는 정보’로 표시할 수 있습니다. 이 요청은 차량 위치를 읽기 위한 권한입니다.\n\n"
                    + "선택 권한\n거절하면 위치 조회만 사용할 수 없습니다. 다른 진단 항목에는 영향을 주지 않습니다.")
                    .setPositiveButton("시스템 권한 확인", (dialog, which) -> {
                        if (started && allowed) requestPermissions(new String[]{LOCATION_PERMISSION}, 101);
                    }).setNegativeButton("지금은 사용 안 함", null).create();
            locationConsent.setOnDismissListener(dialog -> locationConsent = null);
            ConsentDialogs.show(this, locationConsent);
            return;
        }
        if (current) location.readCurrentLocation(); else location.readLastLocation();
    }

    // 공통 조회·권한: 선택 탭 범위만 조회하고 승인 후에는 재조회를 기다린다.
    private void readSelectedPage() {
        switch (activeTab) {
            case TAB_FUEL: readFuel(); break;
            case TAB_EV: readEv(); break;
            case TAB_DRIVE:
                drive.readAll();
                aaos.readOdometer(session);
                break;
            // VIN/location always require their individual explicit actions.
        }
    }

    private void requestAaosPermissions() {
        List<String> pending = new ArrayList<>();
        for (String permission : new String[]{"android.car.permission.CAR_ENERGY",
                "android.car.permission.CAR_MILEAGE_3P"}) {
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) continue;
            try {
                PermissionInfo info = getPackageManager().getPermissionInfo(permission, 0);
                if (info.getProtection() == PermissionInfo.PROTECTION_DANGEROUS) pending.add(permission);
            } catch (PackageManager.NameNotFoundException ignored) { /* absent on this image */ }
        }
        if (pending.isEmpty()) {
            connection.append("\n요청할 런타임 권한 없음 · 조회 결과에서 권한/지원 확인");
        } else {
            requestPermissions(pending.toArray(new String[0]), 100);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        // Permission dialogs may stop/restart this Activity. Never resume an old request automatically.
        if (started && allowed && session != null) {
            connection.append("\n권한 처리 완료 · 해당 조회 버튼을 다시 눌러 확인하세요");
            if (code == 101) {
                boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
                session.begin(requestedLocationKey, "Pleos FusedLocation", "", false)
                        .status(granted ? "권한 허용" : "권한 거부",
                                granted ? "위치 조회 버튼을 다시 눌러 확인" : "위치 접근을 허용하지 않아 조회하지 않음");
            }
        }
    }

    // 7. 진단 종료: 요청을 무효화한 뒤 구독·서비스와 화면의 값을 정리한다.
    @Override protected void onStop() {
        started = false;
        allowed = false;
        closeMeasurements();
        if (aaos != null) { aaos.close(); aaos = null; }
        setActionsEnabled(false);
        super.onStop();
    }

    private void closeMeasurements() {
        if (locationConsent != null) { locationConsent.dismiss(); locationConsent = null; }
        // Invalidate first so callbacks queued by a previous screen cannot repopulate it.
        if (session != null) session.close();
        if (fuel != null) fuel.stopUpdates();
        if (evBattery != null) evBattery.stopUpdates();
        if (odometer != null) odometer.stopUpdates();
        if (location != null) {
            try { location.close(); } catch (RuntimeException ignored) { /* service gone */ }
        }
        if (vehicle != null) {
            try { vehicle.release(); } catch (RuntimeException ignored) { /* service gone */ }
        }
        session = null; vehicle = null; fuel = null; evBattery = null; odometer = null; drive = null; carInfo = null; location = null;
        resetRows();
    }

    // 공통 결과 표시: 조회 상태 요약과 화면 구성 요소.
    private void setActionsEnabled(boolean enabled) {
        for (Button button : actions) button.setEnabled(enabled);
        readings.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void resetRows() {
        rows.values().forEach(DiagnosticReadingView::reset);
        updateSummary();
    }

    private void updateSummary() {
        int received = 0, waiting = 0, unsupported = 0, other = 0;
        for (Map.Entry<String, DiagnosticReadingView> entry : rows.entrySet()) {
            if (!belongsToSelectedPage(entry.getKey())) continue;
            DiagnosticReadingView row = entry.getValue();
            String state = row.getReadingState();
            if (state.equals("미조회")) continue;
            if (state.equals("수신")) received++;
            else if (state.equals("대기")) waiting++;
            else if (state.equals("미지원") || state.equals("읽기 불가") || state.equals("설정 없음")) unsupported++;
            else other++;
        }
        summary.setText(received + waiting + unsupported + other == 0 ? "아직 조회하지 않았습니다."
                : "수신 " + received + "  ·  대기 " + waiting + "  ·  미지원/설정 확인 " + unsupported
                + (other == 0 ? "" : "  ·  기타 상태 " + other));
    }

    private boolean belongsToSelectedPage(String key) {
        switch (activeTab) {
            case TAB_FUEL: return key.startsWith("fuel.") || key.equals("aaos.fuel")
                    || key.startsWith("aaos.fuel.") || key.equals("aaos.capacity");
            case TAB_EV: return key.startsWith("ev.") || key.startsWith("aaos.ev.");
            case TAB_DRIVE: return key.startsWith("drive.") || key.startsWith("distance.")
                    || key.startsWith("cumulative.") || key.equals("aaos.odometer");
            case TAB_INFO: return key.startsWith("vehicle.") || key.startsWith("location.");
            default: return false;
        }
    }

    private LinearLayout page(String tag) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setTag(tag);
        readings.addView(page);
        return page;
    }

    private void addRow(LinearLayout parent, String key, String label, String api) {
        View divider = new View(this);
        divider.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                MaterialColors.getColor(parent, R.attr.colorOnSurface), 25));
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        DiagnosticReadingView view = new DiagnosticReadingView(this, label, api);
        view.setTag(key);
        parent.addView(view);
        rows.put(key, view);
    }

    private LinearLayout card(LinearLayout parent, String title, String subtitle) {
        MaterialCardView frame = new MaterialCardView(this);
        frame.setRadius(dp(28));
        frame.setCardElevation(0);
        frame.setStrokeWidth(dp(1));
        frame.setStrokeColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                MaterialColors.getColor(frame, R.attr.colorOnSurface), 35));
        LinearLayout.LayoutParams params;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(0, -2, 1);
            if (parent.getChildCount() > 0) params.setMarginStart(dp(12));
        } else {
            params = new LinearLayout.LayoutParams(-1, -2);
        }
        params.topMargin = dp(12);
        parent.addView(frame, params);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(16));
        frame.addView(body, new android.widget.FrameLayout.LayoutParams(-1, -2));
        TextView heading = text(body, title, 22);
        heading.setTypeface(null, Typeface.BOLD);
        TextView description = text(body, subtitle, 13);
        description.setAlpha(0.72f);
        return body;
    }

    private LinearLayout actionRow(LinearLayout parent) {
        LinearLayout row = new com.pleos.fuelog.ui.AdaptiveRow(this, 240);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        parent.addView(row);
        return row;
    }

    private TextView text(LinearLayout parent, String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(Math.max(22, sp + 4));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(this, null,
                        R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setMinHeight(dp(64));
        button.setCornerRadius(dp(20));
        button.setTextSize(24);
        button.setLetterSpacing(0);
        button.setMinimumWidth(0);
        if (action != null) button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(0, -2, 1);
            if (parent.getChildCount() > 0) params.setMarginStart(dp(8));
        } else {
            params = new LinearLayout.LayoutParams(-1, -2);
        }
        parent.addView(button, params);
        return button;
    }

    private Button action(LinearLayout parent, String label, Runnable action) {
        Button button = button(parent, label, () -> {
            if (started && allowed && session != null) action.run();
        });
        actions.add(button);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
