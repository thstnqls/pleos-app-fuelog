package com.pleos.fuelog;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Bundle;
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
import com.pleos.fuelog.auth.AuthActivity;
import com.pleos.fuelog.diagnostics.CarInfoDiagnosticService;
import com.pleos.fuelog.diagnostics.DiagnosticSession;
import com.pleos.fuelog.diagnostics.FuelDiagnosticService;
import com.pleos.fuelog.diagnostics.LocationDiagnosticService;
import com.pleos.fuelog.diagnostics.OdometerDiagnosticService;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.pleos.playground.vehicle.Vehicle;

/** One parked-only screen for SDK validation; service classes own all SDK calls. */
public final class DiagnosticActivity extends AppCompatActivity {
    private static final String LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION";
    private final Map<String, TextView> rows = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final List<Button> actions = new ArrayList<>();
    private TextView connection;
    private Button login;
    private LinearLayout readings;
    private boolean started;
    private boolean allowed;
    private DiagnosticSession session;
    private Vehicle vehicle;
    private FuelDiagnosticService fuel;
    private OdometerDiagnosticService odometer;
    private CarInfoDiagnosticService carInfo;
    private LocationDiagnosticService location;
    private AaosVehicleDiagnosticService aaos;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.diagnostic_title);
        // Sensor values and optional location/VIN stay in memory and out of screenshots/backups.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        text(content, getString(R.string.diagnostic_intro), 18);
        connection = text(content, "연결 대기", 18);
        Button reconnect = button(content, "서비스 연결 재시도", this::reconnect);
        reconnect.setContentDescription("차량 서비스 연결과 UX 제한 다시 확인");
        login = button(content, "Fuelog 로그인", () -> {
            if (started && allowed) startActivity(new Intent(this, AuthActivity.class));
        });
        login.setEnabled(false);
        action(content, "일반 조회 전체 (연료·거리·AAOS)", () -> {
            fuel.readAll(); odometer.read(); aaos.readAll(session);
        });
        readings = new LinearLayout(this);
        readings.setOrientation(LinearLayout.VERTICAL);
        content.addView(readings);
        text(readings, "연료 · Pleos Vehicle SDK", 22);
        action(readings, "Pleos 연료 5개 항목 조회", () -> fuel.readAll());
        action(readings, "연료 % 변경 구독 시작", () -> fuel.startUpdates());
        action(readings, "연료 % 구독 중지", () -> fuel.stopUpdates());
        addRow("fuel.percent", "연료 잔량 (%)");
        addRow("fuel.current", "현재 운행 연비 (L/100km)");
        addRow("fuel.cumulative", "누적 연비 (L/100km)");
        addRow("fuel.instant", "순간 연비 (L/100km)");
        addRow("fuel.refill", "주유 이후 연비 (L/100km)");
        addRow("fuel.stream", "연료 % 변경 구독");
        text(readings, "주행거리 · Pleos Vehicle SDK", 22);
        action(readings, "Pleos 누적 주행거리 조회", () -> odometer.read());
        action(readings, "pleos 차량의 누적 주행 거리를 가져옵니다.", () -> odometer.readTripCumulativeDrive());
        action(readings, "누적거리 변경 구독 시작", () -> odometer.startUpdates());
        action(readings, "누적거리 구독 중지", () -> odometer.stopUpdates());
        addRow("distance.total", "누적 주행거리 (km)");
        addRow("cumulative.total", "Trip 누적 주행거리 (m)");
        addRow("distance.stream", "누적거리 변경 구독 (km)");
        text(readings, "AAOS 표준 API 비교", 22);
        action(readings, "AAOS 조회 권한 요청", this::requestAaosPermissions);
        action(readings, "AAOS 연료량·탱크용량·거리 비교", this::readAaos);
        addRow("aaos.fuel", "AAOS FUEL_LEVEL (mL)");
        addRow("aaos.capacity", "AAOS INFO_FUEL_CAPACITY (mL)");
        addRow("aaos.odometer", "AAOS PERF_ODOMETER (km)");
        text(readings, "선택 항목 · 차량 식별 / 위치", 22);
        action(readings, "VIN 조회 (끝 4자리만 표시)", () -> carInfo.readVin());
        addRow("vehicle.vin", "선택 조회 · VIN");
        action(readings, "마지막 위치 1회 조회 (선택)", this::readLocation);
        addRow("location.last", "선택 조회 · 마지막 위치");
        setContentView(scroll);
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
            login.setEnabled(canUse);
            if (canUse && session == null) openMeasurements();
            if (!canUse) closeMeasurements();
            setActionsEnabled(canUse && session != null);
        });
    }

    private void openMeasurements() {
        resetRows();
        session = new DiagnosticSession((key, source, state, value, time) -> {
            TextView row = rows.get(key);
            if (row == null) { addRow(key, key); row = rows.get(key); }
            row.setText(getString(R.string.diagnostic_reading, labels.get(key), state, source, value,
                    DateFormat.getTimeInstance().format(new Date(time))));
        });
        try {
            vehicle = new Vehicle(this);
            vehicle.initialize();
            fuel = new FuelDiagnosticService(vehicle.getFuel(), session);
            odometer = new OdometerDiagnosticService(vehicle.getOdometer(), session);
            carInfo = new CarInfoDiagnosticService(vehicle.getCarInfo(), session);
            location = new LocationDiagnosticService(this, session);
            connection.append("\nVehicle 초기화 요청됨 · 조회 응답으로 기능별 연결 확인");
        } catch (RuntimeException error) {
            closeMeasurements();
            connection.append("\nVehicle 초기화 오류: " + error.getClass().getSimpleName());
        }
    }

    private void readAaos() { aaos.readAll(session); }

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

    private void readLocation() {
        if (checkSelfPermission(LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            try {
                getPackageManager().getPermissionInfo(LOCATION_PERMISSION, 0);
            } catch (PackageManager.NameNotFoundException error) {
                session.begin("location.last", "Pleos FusedLocation", "", false)
                        .status("미지원", "이 이미지에 FUSED_LOCATION 권한 정의 없음");
                return;
            }
            requestPermissions(new String[]{LOCATION_PERMISSION}, 101);
            return;
        }
        location.readLastLocation();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        // Permission dialogs may stop/restart this Activity. Never resume an old request automatically.
        if (started && allowed && session != null) {
            connection.append("\n권한 처리 완료 · 해당 조회 버튼을 다시 눌러 확인하세요");
            if (code == 101) {
                boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
                session.begin("location.last", "Pleos FusedLocation", "", false)
                        .status(granted ? "권한 허용" : "권한 거부",
                                granted ? "위치 조회 버튼을 다시 눌러 확인" : "위치 접근을 허용하지 않아 조회하지 않음");
            }
        }
    }

    @Override protected void onStop() {
        started = false;
        allowed = false;
        login.setEnabled(false);
        closeMeasurements();
        if (aaos != null) { aaos.close(); aaos = null; }
        setActionsEnabled(false);
        super.onStop();
    }

    private void closeMeasurements() {
        // Invalidate first so callbacks queued by a previous screen cannot repopulate it.
        if (session != null) session.close();
        if (fuel != null) fuel.stopUpdates();
        if (odometer != null) odometer.stopUpdates();
        if (location != null) {
            try { location.close(); } catch (RuntimeException ignored) { /* service gone */ }
        }
        if (vehicle != null) {
            try { vehicle.release(); } catch (RuntimeException ignored) { /* service gone */ }
        }
        session = null; vehicle = null; fuel = null; odometer = null; carInfo = null; location = null;
        resetRows();
    }

    private void setActionsEnabled(boolean enabled) {
        for (Button button : actions) button.setEnabled(enabled);
        readings.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void resetRows() {
        rows.forEach((key, view) -> view.setText(getString(R.string.diagnostic_idle, labels.get(key))));
    }

    private void addRow(String key, String label) {
        labels.put(key, label);
        TextView view = text(readings, getString(R.string.diagnostic_idle, label), 18);
        view.setPadding(0, dp(16), 0, dp(16));
        rows.put(key, view);
    }

    private TextView text(LinearLayout parent, String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(56));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private void action(LinearLayout parent, String label, Runnable action) {
        actions.add(button(parent, label, () -> {
            if (started && allowed && session != null) action.run();
        }));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
