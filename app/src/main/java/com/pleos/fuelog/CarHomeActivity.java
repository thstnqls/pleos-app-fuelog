package com.pleos.fuelog;

import ai.pleos.playground.vehicle.Vehicle;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.tabs.TabLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.pleos.fuelog.api.ApiClient.ApiResponse;
import com.pleos.fuelog.api.ApiClient.ResultCallback;
import com.pleos.fuelog.api.ApiStatus;
import com.pleos.fuelog.connection.ConnectionSession;
import com.pleos.fuelog.connection.ConnectionSettings;
import com.pleos.fuelog.diagnostics.AaosVehicleDiagnosticService;
import com.pleos.fuelog.home.HomeRecorder;
import com.pleos.fuelog.home.MaintenanceSummary;
import com.pleos.fuelog.home.MaintenancePlanEditor;
import com.pleos.fuelog.home.TripState;
import com.pleos.fuelog.home.TripSummary;
import com.pleos.fuelog.home.FuelLocationEvidence;
import com.pleos.fuelog.home.VehicleOdometer;
import com.pleos.fuelog.time.AppTime;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import okhttp3.Call;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parked-only screens backed by live API responses and local recording data. */
public final class CarHomeActivity extends AppCompatActivity {
    private static final int HOME = 0, TRIP = 2, FUEL = 3;
    private static final int MAINTENANCE = 4, SETTINGS = 5, CONNECT = 6;
    private final int[] navPages = {HOME, TRIP, FUEL, MAINTENANCE};
    private final String[] navLabels = {"홈", "운행", "주유", "정비"};
    private LinearLayout body;
    private LinearLayout navigation;
    private TextView status;
    private Button headerNavigation;
    private AaosVehicleDiagnosticService restrictions;
    private boolean allowed;
    private int page = CONNECT;
    private int startTab;
    private boolean basicInfoLoaded;
    private ConnectionSession connection;
    private final Handler connectionHandler = new Handler(Looper.getMainLooper());
    private boolean started;
    private AlertDialog apiAlert;
    private boolean liveRecorderStarted;
    private ConnectionSettings settings;
    private boolean offerTripSummary;
    private boolean fuelSyncBusy;
    private int fuelSyncGeneration;
    private Call fuelSyncCall;
    private String fuelSyncMessage = "";
    private MaintenanceSummary maintenanceSummary;
    private MaintenancePlanEditor maintenanceEditor;
    private Call maintenanceCall;
    private int maintenanceGeneration;
    private String maintenanceMessage = "정비 정보를 확인 중입니다";
    private String maintenanceVehicleId = "";
    private HomeRecorder recorder;
    private VehicleOdometer vehicleOdometer;
    private String vehicleOdometerMessage = "차량 연결 확인 중";
    private final Runnable connectionTick = new Runnable() {
        public void run() {
            if (!started) return;
            if (allowed && page == CONNECT) {
                connection.refresh(online(), () -> {
                    if (!started || !allowed) return;
                    if (connection.linked) {
                        page = HOME;
                        startLiveRecorder();
                        connection.refreshIdentity(() -> {
                            if (started && allowed) render();
                        });
                    }
                    render();
                    if (showingMaintenance() && (!basicInfoLoaded || !maintenanceVehicleId.equals(connection.vehicleId))
                            && !connection.vehicleId.isEmpty()) {
                        basicInfoLoaded = true;
                        loadMaintenance();
                    }
                });
                if (connection.linked) {
                    page = HOME;
                    startLiveRecorder();
                    render();
                }
                if (connection.state.equals("WAITING") || connection.state.equals("AWAITING_CONFIRMATION")) render();
            }
            connectionHandler.postDelayed(this, 1000);
        }
    };

    // 1. 앱 진입·복귀: 저장된 연결을 복원하고 화면 이용 제한을 확인한다.
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BuildConfig.DEBUG) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (state != null) {
            page = state.getInt("page", HOME);
            startTab = state.getInt("startTab", 0);
        }
        if (page == 1) page = HOME;
        connection = ConnectionSession.get(this);
        if (!connection.linked) page = CONNECT;
        recorder = HomeRecorder.get(this);
        connection.onPersonalInvalid = recorder::clearPersonalCache;
        settings = new ConnectionSettings(this, connection, recorder);
        if (!settings.pending().isEmpty()) {
            try { recorder.lockPersonalSession(); } catch (Exception ignored) { }
            page = SETTINGS;
        }
        buildShell();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (page == HOME) finish();
                else navigate(HOME);
            }
        });
        render();
    }

    private void buildShell() {
        LinearLayout root = column();
        root.setBackgroundColor(color(R.color.car_background));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        boolean compact = compact();
        LinearLayout header = compact ? column() : row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(color(R.color.car_panel));
        header.setPadding(dp(compact ? 20 : 36), dp(18), dp(compact ? 20 : 32), dp(18));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout top = compact ? row() : header;
        if (compact) header.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView brand = label("fuelog", 28, true, false);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        status = label("정차 상태 확인 중", 18, true, false);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackground(shape(R.color.car_soft, 24, 0));
        if (!compact) header.addView(status);
        headerNavigation = headerAction("연결 상태  →", () -> navigate(SETTINGS));
        top.addView(headerNavigation);
        if (compact) {
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
            statusParams.setMargins(0, dp(10), 0, 0);
            header.addView(status, statusParams);
        }
        divider(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column();
        body.setPadding(dp(compact ? 20 : 36), dp(compact ? 24 : 32),
                dp(compact ? 20 : 36), dp(36));
        scroll.addView(body);

        divider(root);
        navigation = row();
        navigation.setPadding(dp(compact ? 8 : 28), dp(8), dp(compact ? 8 : 28), dp(8));
        navigation.setBackgroundColor(color(R.color.car_panel));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        connection.apiStatus().changed = () -> connectionHandler.post(() -> {
            if (started && allowed) render();
        });
        connection.onPersonalInvalid = () -> { closeMaintenanceEditor(); recorder.clearPersonalCache(); if (started && allowed) render(); };
        offerTripSummary = !recorder.trip.active && recorder.summary() != null;
        startLiveRecorder();
        connectionHandler.post(connectionTick);
        allowed = false;
        render();
        try {
            restrictions = new AaosVehicleDiagnosticService(this);
            recorder.setEvSource(restrictions);
            restrictions.start((canUse, detail) -> runOnUiThread(() -> {
                if (!started || isFinishing() || isDestroyed()) return;
                boolean wasAllowed = allowed;
                allowed = canUse;
                if (canUse) startVehicleOdometer();
                else closeVehicleOdometer();
                if (!canUse) {
                    closeMaintenanceEditor();
                    cancelFuelSync(); cancelMaintenance();
                    if (apiAlert != null) { apiAlert.dismiss(); apiAlert = null; }
                }
                if ((!canUse || !wasAllowed)) page = !settings.pending().isEmpty() ? SETTINGS : connection.linked ? HOME : CONNECT;
                status.setTextColor(color(canUse ? R.color.car_link : R.color.car_warning_text));
                status.setText(canUse ? "우리 차 · 정차 상태" : "주행 제한");
                status.setBackground(shape(canUse ? R.color.car_accent_light : R.color.car_warning_bg, 24, 0));
                render();
                if (canUse && !wasAllowed && showingMaintenance()) {
                    basicInfoLoaded = !connection.vehicleId.isEmpty();
                    loadMaintenance();
                }
            }));
        } catch (LinkageError error) {
            restrictions = null; allowed = false;
            status.setText("차량 UX 서비스 없음");
            render();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", allowed ? page : HOME);
        out.putInt("startTab", startTab);
        super.onSaveInstanceState(out);
    }

    // 2. 화면 이동: 현재 연결·사용자 상태에 맞는 화면을 표시한다.
    private void navigate(int destination) {
        if (!allowed) return;
        closeMaintenanceEditor();
        if (!settings.pending().isEmpty()) destination = SETTINGS;
        if (destination != FUEL) cancelFuelSync();
        if (destination != MAINTENANCE) cancelMaintenance();
        page = !connection.linked && destination != SETTINGS ? CONNECT : destination;
        if (page == MAINTENANCE) {
            maintenanceSummary = null;
            maintenanceMessage = "정비 정보를 확인 중입니다";
        }
        render();
        if (showingMaintenance()) loadMaintenance();
        if (page == SETTINGS) settings.refresh(this::settingsChanged);
    }

    private void render() {
        if (body == null) return;
        if (maintenanceEditor != null && (!allowed || !canEditMaintenance())) closeMaintenanceEditor();
        if (!connection.linked && page != SETTINGS) page = CONNECT;
        body.removeAllViews();
        navigation.removeAllViews();
        headerNavigation.setText(page == HOME ? "연결 상태  →" : "←  홈");
        headerNavigation.setOnClickListener(v -> navigate(page == HOME ? SETTINGS : HOME));
        if (!allowed) {
            heading(body, "정차 후 확인할 수 있어요");
            paragraph(body, "차량의 이용 제한 상태를 확인 중이거나 화면 조작이 제한되었습니다.");
            paragraph(body, "정차 후 다시 앱을 열어 주세요.");
            return;
        }
        renderApiStatus();
        switch (page) {
            case TRIP: renderTrip(); break;
            case FUEL: renderFuel(); break;
            case MAINTENANCE: renderMaintenance(); break;
            case SETTINGS: renderSettings(); break;
            case CONNECT: renderConnect(); break;
            default: renderHome();
        }
        if (!connection.linked) return;
        for (int i = 0; i < navPages.length; i++) {
            final int destination = navPages[i];
            Button tab = headerAction(navLabels[i], () -> navigate(destination));
            if (page == destination) {
                tab.setTextColor(color(R.color.car_link));
                tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            LinearLayout item = column();
            navigation.addView(item, new LinearLayout.LayoutParams(0, dp(68), 1));
            item.addView(tab, new LinearLayout.LayoutParams(-1, 0, 1));
            View indicator = new View(this);
            indicator.setBackgroundColor(color(page == destination ? R.color.car_accent_mid : R.color.car_panel));
            item.addView(indicator, new LinearLayout.LayoutParams(-1, dp(5)));
        }
    }

    // 3. 차량 연결: QR 스캔 → 양쪽 확인 코드 비교 → 차량에서 최종 승인.
    private void renderConnect() {
        TabLayout tabs = new TabLayout(this);
        tabs.setTag("start.tabs");
        tabs.setBackgroundColor(color(R.color.car_panel));
        tabs.setTabTextColors(color(R.color.car_muted), color(R.color.car_link));
        tabs.setSelectedTabIndicatorColor(color(R.color.car_accent));
        tabs.addTab(tabs.newTab().setText("QR 연결"));
        tabs.addTab(tabs.newTab().setText("차량 기본정보"));
        tabs.selectTab(tabs.getTabAt(startTab));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                startTab = tab.getPosition();
                if (startTab == 1) {
                    startVehicleOdometer();
                    basicInfoLoaded = !connection.vehicleId.isEmpty();
                    loadMaintenance();
                }
                else { cancelMaintenance(); render(); }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        body.addView(tabs);
        if (startTab == 1) {
            heading(body, "차량 기본정보");
            paragraph(body, connection.vehicleId.isEmpty() ? "등록된 차량을 확인하면 공동 정비 정보를 볼 수 있습니다. 최초 차량 등록은 QR 연결에서 진행해 주세요."
                    : connection.vehicleName);
            paragraph(body, "공동 정비와 차량 누적거리는 사용자 연결 없이 확인할 수 있습니다.");
            sectionLabel(body, "현재 차량 센서");
            paragraph(body, "차량에서 직접 읽는 현재 값입니다. 아래 정비 이력의 마지막 수신값과 구분해 확인하세요.");
            link(body, "현재 차량 거리 확인", () -> openDiagnostics(2));
            link(body, "현재 배터리 확인", () -> openDiagnostics(1));
            renderMaintenance();
            return;
        }
        heading(body, "휴대폰으로 차량을 연결하세요");
        if (!online()) { paragraph(body, "인터넷 연결이 필요합니다. 네트워크를 확인해 주세요."); return; }
        if (connection.linked) { link(body, "이미 연결된 기기 · 연결 설정", () -> navigate(SETTINGS)); return; }
        if (connection.state.equals("AWAITING_CONFIRMATION") && connection.remaining() > 0) {
            LinearLayout pairing = pairingCard(body);
            pairingText(pairing, "휴대폰에 확인 코드를 입력해 주세요", 24, R.color.car_on_dark);
            TextView code = label(connection.confirmationCode, 40, true, false);
            code.setTextColor(color(R.color.car_mint));
            code.setLetterSpacing(0.25f);
            code.setGravity(Gravity.CENTER);
            code.setPadding(dp(12), dp(20), dp(12), dp(20));
            code.setBackground(shape(R.color.car_dark_box, 12, R.color.car_mint));
            pairing.addView(code, new LinearLayout.LayoutParams(-1, -2));
            pairingText(pairing, "연결할 차량 · " + connection.selectedVehicleName, 22, R.color.car_on_dark);
            pairingText(pairing, "휴대폰에서 코드를 입력하면 자동으로 화면이 넘어갑니다.", 20, R.color.car_muted_dark);
            pairingText(pairing, "남은 시간 " + connection.remaining() + "초 · 입력 대기 중...", 20, R.color.car_mint);
            primary(pairing, "차량에서 바로 승인", () -> connection.confirmPairing(() -> {
                if (!started || !allowed) return;
                if (connection.linked) {
                    page = HOME;
                    startLiveRecorder();
                    connection.refreshIdentity(() -> {
                        if (started && allowed) render();
                    });
                }
                render();
            }));
            Button retry = button("QR 다시 찍기", false, () -> connection.retry(() -> {
                if (started && allowed) render();
            }));
            retry.setTag("pairing.retry");
            pairing.addView(retry, fullButton());
            return;
        }
        if (connection.state.equals("WAITING") && connection.remaining() > 0) {
            LinearLayout layout = grid(body);
            LinearLayout qr = pairingCard(layout);
            showQr(qr, connection.payload, "모바일 fuelog 앱으로 스캔할 차량 연결 QR");
            pairingText(qr, "남은 시간 " + connection.remaining() + "초 · 연결 대기 중", 20, R.color.car_mint);
            LinearLayout steps = section(layout);
            paragraph(steps, "1. 휴대폰 fuelog 앱 열기\n2. 차량 등록에서 QR 스캔\n3. 로그인 후 차량 확인");
            tag(steps, "휴대폰 승인 후 차량에서 최종 확인");
        } else {
            paragraph(body, connection.state.equals("EXPIRED") || connection.remaining() == 0 ? "연결 코드가 만료되었습니다" :
                    connection.message.isEmpty() ? "연결 QR 준비 중" : connection.message);
            primary(body, "코드 다시 받기", () -> connection.retry(() -> { if (started && allowed) render(); }));
        }
    }

    private void startLiveRecorder() {
        if (liveRecorderStarted || !connection.linked) return;
        liveRecorderStarted = true;
        recorder.resume(() -> { if (started && allowed) render(); });
    }

    private void openDiagnostics(int category) {
        if (started && allowed) startActivity(new Intent(this, DiagnosticActivity.class)
                .putExtra("diagnostic.category", category));
    }

    private void startVehicleOdometer() {
        if (!started || !allowed) return;
        try {
            if (vehicleOdometer == null) {
                Vehicle vehicle = new Vehicle(this);
                vehicle.initialize();
                vehicleOdometer = new VehicleOdometer(vehicle.getOdometer(), () -> {
                    if (started && allowed) render();
                });
                vehicleOdometer.read();
            }
            if (page == CONNECT && startTab == 1) vehicleOdometer.startUpdates();
        } catch (RuntimeException error) { vehicleOdometerMessage = "차량 거리 연결 실패 · 앱을 다시 열어 주세요"; }
    }

    private void closeVehicleOdometer() {
        if (vehicleOdometer != null) vehicleOdometer.close();
        vehicleOdometer = null;
        vehicleOdometerMessage = "차량 연결 확인 중";
    }

    private void renderVehicleOdometer() {
        LinearLayout current = section(body);
        current.setTag("vehicle.odometer");
        subheading(current, "차량 총 누적거리");
        VehicleOdometer reading = vehicleOdometer;
        detail(current, "마지막 수신값", reading == null || reading.km == null ? "수신값 없음" : metric(reading.km, " km"));
        paragraph(current, reading == null ? vehicleOdometerMessage : reading.readStatus + " · " + reading.subscriptionStatus);
        if (reading != null && reading.observedAt > 0) paragraph(current, "차량에서 수신 · " + timeLabel(reading.observedAt));
        /*Button refresh = button("누적거리 1회 조회", false, () -> { if (vehicleOdometer != null) vehicleOdometer.read(); });*/
        /*refresh.setTag("vehicle.odometer.read");
        refresh.setEnabled(reading != null);
        current.addView(refresh, fullButton());*/
        /*Button subscribe = button(reading != null && reading.subscribed() ? "누적거리 구독 중지" : "누적거리 구독 시작", false, () -> {
            if (vehicleOdometer == null) return;
            if (vehicleOdometer.subscribed()) vehicleOdometer.stopUpdates();
            else vehicleOdometer.startUpdates();
        });
        subscribe.setTag("vehicle.odometer.subscribe");
        subscribe.setEnabled(reading != null);
        current.addView(subscribe, fullButton());*/
        paragraph(current, "사용자 연결·운행 기록과 관계없이 차량에서 직접 읽습니다. 앱 화면을 벗어나면 구독이 중지됩니다.");
    }

    // 5. 운행 기록: 홈에서 시작·종료하고 요약을 모바일 확인함으로 전달한다.
    private void renderHome() {
        if (offerTripSummary) {
            link(body, "저장된 운행 요약 확인", () -> { offerTripSummary = false; navigate(TRIP); });
        }
        TripState trip = recorder.trip;
        tag(body, "연결된 차량 · " + (connection.home.isNull("vehicleAlias") ? "별칭 미설정" : connection.home.optString("vehicleAlias")));
        heading(body, recorder.verifiedOwner().isEmpty() ? "페어링 사용자 확인이 필요합니다" : recorder.displayUser());
        paragraph(body, "현재 사용자 · " + recorder.displayUser());
        link(body, "연결 상태 확인", () -> navigate(SETTINGS));
        renderVehicleOdometer();
        renderMaintenanceCycles();
        JSONObject homeDistance = connection.home.optJSONObject("distanceStatus");
        if (!connection.home.isNull("distanceKm") && homeDistance != null
                && ("available".equals(homeDistance.optString("state")) || "stale".equals(homeDistance.optString("state"))))
            detail(body, "개인 홈 거리" + ("stale".equals(homeDistance.optString("state")) ? " · 이전 수신값" : ""),
                    metric(connection.home.optDouble("distanceKm"), " km"));
        LinearLayout layout = grid(body);
        LinearLayout current = card(layout);
        tag(current, connection.linked ? trip.status(System.currentTimeMillis()) : "연결 필요");
        subheading(current, "이번 운행 · 앱 기록 구간");
        if (trip.sampledAt == 0) paragraph(current, "거리 수신 대기");
        else number(current, String.format(Locale.KOREA, "%.1f", trip.distance), " km");
        paragraph(current, "마지막 샘플 · 최근 수신 " + timeLabel(trip.sampledAt));
        JSONObject cost = connection.home.optJSONObject("estimatedCost");
        paragraph(current, "추정 비용 · " + (cost == null || cost.isNull("value") || !"available".equals(cost.optString("status")) ? "개인 홈 인증 후 제공"
                : metric(cost.optDouble("value"), " " + cost.optString("currency"))));
        paragraph(current, "소모량·비용 계산에는 연료 잔량과 단가 확인이 필요합니다");
        link(current, "이번 운행 요약", () -> navigate(TRIP));
        primary(current, trip.active ? "기록 종료" : "기록 시작", () -> {
            if (allowed && connection.linked) recorder.toggle();
        });
        paragraph(current, "직접 시작·종료한 앱 기록 구간입니다. 화면을 벗어나면 수집이 중단되어 일부 누락으로 표시됩니다.");
        LinearLayout alerts = section(layout);
        subheading(alerts, "확인할 항목");
        paragraph(alerts, connection.home.isNull("fuelReviewCount") ? "필요한 알림 · 개인 홈 인증 후 조회"
                : "주유 확인 " + connection.home.optInt("fuelReviewCount") + "건 · 정비 예정 " + connection.home.optInt("maintenanceDueCount") + "건");
        link(alerts, "주유 확인", () -> navigate(FUEL));
        link(alerts, "정비 예정", () -> navigate(MAINTENANCE));
        paragraph(body, (online() ? recorder.syncMessage() : "오프라인 · 로컬 기록")
                + " · 로컬 저장 " + timeLabel(trip.savedAt));
        if (!compact()) {
            LinearLayout footer = grid(body);
            link(section(footer), "운행 요약", () -> navigate(TRIP));
            link(section(footer), "연결 상태", () -> navigate(SETTINGS));
        } else link(body, "운행 요약 및 상세", () -> navigate(TRIP));
    }

    private void renderTrip() {
        heading(body, "이번 운행");
        TripSummary summary = recorder.summary();
        if (summary == null) {
            paragraph(body, "확인할 수 있는 운행 기록이 없습니다. 사용자를 확인하거나 기록을 시작해 주세요.");
            link(body, "연결 상태 확인", () -> navigate(SETTINGS)); return;
        }
        JSONObject data = summary.data;
        String id = data.optString("intervalId");
        long end = data.optLong("endedAt");
        boolean electric = data.optBoolean("evDetected") || !data.has("startFuelLiters");
        paragraph(body, "앱 기록 구간 · " + timeLabel(data.optLong("startedAt")) + " → "
                + (end == 0 ? "기록 진행 중" : timeLabel(end)) + (data.optBoolean("endEstimated") ? " · 종료 시각 추정" : ""));
        LinearLayout distance = card(body);
        subheading(distance, "거리");
        paragraph(distance, data.optBoolean("distanceValid") ? metric(data.optDouble("distanceKm"), " km") : "유효한 거리 샘플 부족");
        if (electric) {
            detail(body, "추정 소모 전력량", metric(summary.consumedKwh(), " kWh"));
            detail(body, "추정 전비", metric(summary.evEconomy(), " km/kWh"));
            detail(body, "추정 전력비", Double.isFinite(summary.evCost()) ? metric(summary.evCost(), "원") : "충전 단가 확인 필요");
            JSONArray charges = data.optJSONArray("chargeCandidates");
            if (charges != null) for (int i = 0; i < charges.length(); i++) {
                JSONObject charge = charges.optJSONObject(i);
                if (charge == null) continue;
                String chargeState = charge.optString("status");
                detail(body, chargeState.equals("CONFIRMED") ? "모바일 확인된 충전" : chargeState.equals("REJECTED") ? "기각된 충전 후보" : "충전 추정 · 사용자 확인 전",
                        metric(charge.optDouble("estimatedKwh", Double.NaN), " kWh"));
                renderChargeEstimate(charge);
                link(body, "충전 후보 상태 확인", () -> refreshChargeCandidate(charge.optString("id")));
                paragraph(body, "감지 " + timeLabel(charge.optLong("startAt")) + " → " + timeLabel(charge.optLong("endAt")));
            }
            paragraph(body, "배터리 증가는 충전·결제 완료를 의미하지 않습니다. 모바일에서 확인해 주세요.");
        } else {
            detail(body, "추정 소모량", metric(summary.liters(), " L"));
            detail(body, "추정 연비", metric(summary.economy(), " km/L"));
            detail(body, "추정 연료비", metric(summary.fuelCost(), "원"));
        }
        paragraph(body, electric ? "충전비는 감지 위치 주변 사업자의 일반 회원 참고요금 평균입니다. 실제 결제금액과 다를 수 있습니다." : "단가 출처 · " + data.optString("priceSource", "유효한 단가 없음"));
        sectionLabel(body, "기록 품질");
        paragraph(body, (data.optBoolean("missing") || data.optBoolean("evMissing")) ? "일부 누락 · 수신한 구간의 거리만 표시" : "감지된 누락 없음");
        paragraph(body, "최근 수신 " + timeLabel(data.optLong("sampledAt")));
        paragraph(body, (data.optBoolean("synced") ? "서버 동기화 완료" : "로컬 저장 · 서버 동기화 대기")
                + " · " + timeLabel(data.optLong("savedAt")));
        if (!recorder.storageError.isEmpty()) paragraph(body, recorder.storageError);
        JSONObject serverSummary = recorder.serverSummary(id);
        if (serverSummary != null) {
            sectionLabel(body, "서버 운행 요약");
            detail(body, "거리", serverMetric(serverSummary, "distanceKm", " km"));
            detail(body, "소모량", serverMetric(serverSummary, electric ? "energyConsumedKwh" : "fuelConsumedLiters", electric ? " kWh" : " L"));
            detail(body, electric ? "전비" : "연비", serverMetric(serverSummary, electric ? "energyEfficiencyKmPerKwh" : "fuelEfficiencyKmPerLiter", electric ? " km/kWh" : " km/L"));
            detail(body, "비용", serverMetric(serverSummary, "estimatedCost", "원"));
        }
        paragraph(body, recorder.syncMessage());
        Button mobile = button(recorder.mobileDelivered(id) ? "모바일 전달 완료" : recorder.mobileQueued(id) ? "모바일 전달 대기 중" : "모바일에서 상세 확인", true, () -> {
            try { recorder.enqueueMobile(id); render(); }
            catch (Exception e) { paragraph(body, "모바일 전달 요청을 저장하지 못했습니다. 다시 시도해 주세요."); }
        });
        mobile.setEnabled(end != 0 && !data.optString("owner").isEmpty() && !recorder.mobileQueued(id));
        body.addView(mobile, fullButton());
        paragraph(body, "검증된 사용자 운행만 전달할 수 있습니다. 요약 업로드 후 모바일 확인함으로 전송합니다.");
    }

    private void renderChargeEstimate(JSONObject charge) {
        JSONArray scenarios = charge.optJSONArray("scenarios");
        if (scenarios == null || scenarios.length() == 0) {
            String state = charge.optString("estimateStatus");
            paragraph(body, state.equals("COORDINATE_SYSTEM_UNVERIFIED") ? "위치 좌표계 검증 필요 · 충전량만 보존"
                    : state.equals("INSUFFICIENT_COMPARABLE_STATIONS") ? "주변 비교 사업자 또는 유효 단가 부족 · 충전량만 보존"
                    : "감지 위치·충전 단가 확인 대기 · 충전량만 보존");
            return;
        }
        if (charge.optLong("syncedEndAt") < charge.optLong("endAt")) {
            paragraph(body, "증가한 충전량의 추정 금액 갱신 대기"); return;
        }
        if (scenarios.length() > 1) paragraph(body, "충전기 출력 미확정 · 출력 구간별 참고 금액");
        for (int i = 0; i < scenarios.length(); i++) {
            JSONObject scenario = scenarios.optJSONObject(i);
            if (scenario == null) continue;
            JSONArray stations = scenario.optJSONArray("stations");
            double price = scenario.optDouble("averageWonPerKwh", Double.NaN);
            double amount = scenario.optDouble("estimatedAmount", Double.NaN);
            if (stations == null || stations.length() < 2 || stations.length() > 3
                    || !Double.isFinite(price) || price <= 0 || !Double.isFinite(amount) || amount < 0) continue;
            detail(body, chargeBandLabel(scenario.optString("powerBand")) + " 추정 충전비", metric(amount, "원"));
            paragraph(body, stations.length() + "개 사업자 평균 " + metric(price, " 원/kWh") + " · 일반 회원 참고요금");
            for (int j = 0; j < stations.length(); j++) {
                JSONObject station = stations.optJSONObject(j);
                if (station != null) paragraph(body, station.optString("name") + " · "
                        + metric(station.optDouble("distanceMeters", Double.NaN), " m") + " · "
                        + metric(station.optDouble("wonPerKwh", Double.NaN), " 원/kWh")
                        + " · 요금표 확인 " + timeLabel(station.optString("verifiedAt")));
            }
        }
        paragraph(body, "카드 할인·로밍·충전 손실은 반영하지 않은 참고 추정입니다.");
    }

    private String chargeBandLabel(String band) {
        switch (band) {
            case "SLOW": return "완속(30kW 미만)";
            case "MEDIUM": return "중속(30~50kW 미만)";
            case "FAST_50": return "급속(50~100kW 미만)";
            case "FAST_100": return "급속(100~200kW 미만)";
            case "ULTRA": return "초급속(200kW 이상)";
            default: return "출력 미확인";
        }
    }

    private void refreshChargeCandidate(String id) {
        if (!allowed || !online() || fuelSyncBusy || recorder.verifiedOwner().isEmpty()) return;
        fuelSyncBusy = true;
        int version = ++fuelSyncGeneration;
        try {
            fuelSyncCall = connection.request("GET", "charge-candidates/" + UUID.fromString(id), null, "", new ResultCallback() {
                public void onSuccess(ApiResponse response) { runOnUiThread(() -> {
                    if (version != fuelSyncGeneration || !started || !allowed || page != TRIP) return;
                    fuelSyncBusy = false;
                    try {
                        JSONObject result = new JSONObject(response.getBody());
                        if (!id.equals(result.getString("id")) || !recorder.verifiedOwner().equals(result.getString("owner"))) throw new IllegalArgumentException();
                        recorder.applyChargeResponse(result);
                    } catch (Exception error) { recorder.storageError = "충전 후보 응답 확인 실패"; }
                    render();
                }); }
                public void onHttpError(ApiResponse response) { failed(); }
                public void onFailure(IOException error) { failed(); }
                private void failed() { runOnUiThread(() -> {
                    if (version != fuelSyncGeneration) return;
                    fuelSyncBusy = false; recorder.storageError = "충전 후보 서버 연결 필요";
                    if (started && allowed) render();
                }); }
            });
        } catch (Exception error) { fuelSyncBusy = false; recorder.storageError = "충전 후보 조회 실패"; }
    }

    // 6. 주유 확인: 연료 증가 후보를 확인하고 모바일 처리 상태를 갱신한다.
    private void renderFuel() {
        heading(body, "주유 기록 확인이 필요해요");
        if (recorder.verifiedOwner().isEmpty()) {
            paragraph(body, "계정 귀속을 확인할 수 없습니다. 이번 운행 사용자를 먼저 확인해 주세요.");
            link(body, "연결 상태 확인", () -> navigate(SETTINGS)); return;
        }
        JSONObject candidate = recorder.fuelCandidate();
        if (candidate == null) {
            paragraph(body, "현재 사용자에게 표시할 연료 증가 감지 기록이 없습니다.");
            paragraph(body, "연료 정보를 수신할 수 있는 동안 증가 여부를 확인합니다."); return;
        }
        String id = candidate.optString("id"), state = candidate.optString("status");
        tag(body, state.equals("CONFIRMED") ? "모바일 확인됨" : state.equals("REJECTED") ? "모바일에서 기각됨" : "후보 · 사용자 확인 전");
        JSONArray stations = candidate.optJSONArray("stationCandidates");
        subheading(body, stations == null || stations.length() == 0 ? "주유소 미확인" : stations.length() > 1
                ? "주유소 후보 " + stations.length() + "곳 · 미확정" : "주유소 후보 · " + stations.optString(0));
        paragraph(body, "감지 구간 " + timeLabel(candidate.optLong("startAt")) + " → " + timeLabel(candidate.optLong("endAt")));
        boolean capacity = candidate.optBoolean("capacityVerified");
        JSONArray postedStations = candidate.optJSONArray("stations");
        if (postedStations != null) {
            subheading(body, "인근 주유소 후보");
            for (int i = 0; i < Math.min(3, postedStations.length()); i++) {
                JSONObject station = postedStations.optJSONObject(i);
                if (station == null) continue;
                paragraph(body, station.optString("name", "주유소 미확인") + " · " + station.optString("fuelType")
                        + " · " + metric(station.optDouble("distanceMeters", Double.NaN), " m"));
                if (FuelLocationEvidence.validPrice(station, System.currentTimeMillis())) {
                    detail(body, "게시 단가", metric(station.optDouble("postedPriceWonPerLiter"), " 원/L"));
                    paragraph(body, "가격 기준 " + timeLabel(station.optString("pricePostedAt")));
                } else paragraph(body, "유효한 게시가격 없음");
            }
        }
        double liters = candidate.optDouble("estimatedLiters", Double.NaN);
        if (capacity && Double.isFinite(liters) && liters > 0) {
            detail(body, "추정 주유량", metric(liters, " L"));
            double amount = liters * selectedFuelUnitPrice(candidate);
            detail(body, "추정 금액", Double.isFinite(amount) && amount > 0
                    ? metric(amount, "원") : "단가 확인 필요");
            paragraph(body, "단가 기준 " + timeLabel(candidate.optLong("priceAt")));
        } else paragraph(body, "잔량 증가 감지 · 연료 용량 미확인");
        paragraph(body, "연료 증가는 주유 완료나 결제 완료를 의미하지 않습니다. 장소·양·금액은 모바일에서 확인해 주세요.");
        paragraph(body, "마지막 서버 확인 " + timeLabel(candidate.optLong("checkedAt")));
        if (!online()) paragraph(body, "오프라인 · 로컬에 저장된 상태입니다");
        if (!fuelSyncMessage.isEmpty()) paragraph(body, fuelSyncMessage);
        Button mobile = button(candidate.optBoolean("queued") ? "모바일 전달 요청 저장됨" : "모바일에서 확인", true, () -> {
            if (!allowed) return;
            try { recorder.queueFuel(id); render(); }
            catch (Exception e) { paragraph(body, "전달 요청 저장 실패 · 다시 시도해 주세요"); }
        });
        mobile.setEnabled(state.equals("CANDIDATE") && !candidate.optBoolean("queued"));
        body.addView(mobile, fullButton());
        paragraph(body, "감지 위치가 검증된 후보만 자동 검색합니다. 위치가 없으면 주유소 미확인으로 보존합니다.");
        Button refresh = button(fuelSyncBusy ? "확인 중" : "모바일 상태 새로 확인", false, () -> syncFuel(candidate));
        refresh.setEnabled(online() && !fuelSyncBusy);
        body.addView(refresh, fullButton());
        paragraph(body, "전달 요청은 서버 연결 후 모바일 확인함에 반영됩니다. 휴대폰 앱이 자동 실행되지는 않습니다.");
    }

    private void syncFuel(JSONObject candidate) {
        if (!allowed || !online() || fuelSyncBusy || recorder.verifiedOwner().isEmpty()) return;
        final String subject = recorder.verifiedOwner(), id = candidate.optString("id");
        final int version = ++fuelSyncGeneration;
        fuelSyncBusy = true;
        try {
            JSONObject request = new JSONObject(candidate.toString());
            String interval = recorder.sync == null ? "" : recorder.sync.serverId(candidate.getString("intervalId"));
            if (interval.isEmpty()) throw new IllegalStateException("Server interval pending");
            request.put("intervalId", interval).put("queued", candidate.optBoolean("queued"));
            fuelSyncCall = connection.requestUser("fuel-candidates/sync", request.toString(), new ResultCallback() {
                public void onSuccess(ApiResponse result) {
                    runOnUiThread(() -> {
                        if (version != fuelSyncGeneration || !started || !allowed || page != FUEL
                                || !subject.equals(recorder.verifiedOwner())) return;
                        fuelSyncBusy = false;
                        try { recorder.updateFuel(new JSONObject(result.getBody()), id, subject);
                            fuelSyncMessage = "모바일 확인 상태를 갱신했습니다"; }
                        catch (Exception e) { connection.apiStatus().invalid("POST", "fuel-candidates/sync"); fuelSyncMessage = "연결 필요 · 주유 응답을 확인하지 못했습니다"; }
                        render();
                    });
                }
                public void onHttpError(ApiResponse result) { fail(); }
                public void onFailure(IOException error) { fail(); }
                private void fail() { runOnUiThread(() -> {
                    if (version != fuelSyncGeneration || !started || !allowed) return;
                    fuelSyncBusy = false; fuelSyncMessage = "서버 연결 실패 · 마지막 저장 상태를 표시합니다"; render();
                }); }
            });
        } catch (Exception e) { fuelSyncBusy = false; fuelSyncMessage = "서버 설정을 확인해 주세요"; }
        render();
    }

    private double selectedFuelUnitPrice(JSONObject candidate) {
        JSONArray stations = candidate.optJSONArray("stations");
        if (stations != null) for (int i = 0; i < stations.length(); i++) {
            JSONObject station = stations.optJSONObject(i);
            if (station != null && candidate.optString("selectedStationId").equals(station.optString("stationId"))
                    && candidate.optString("fuelType").equals(station.optString("fuelType"))
                    && FuelLocationEvidence.validPrice(station, System.currentTimeMillis())) return station.optDouble("postedPriceWonPerLiter", Double.NaN);
        }
        return Double.NaN;
    }


    private void cancelFuelSync() {
        fuelSyncGeneration++; fuelSyncBusy = false;
        if (fuelSyncCall != null) fuelSyncCall.cancel();
        fuelSyncMessage = "";
    }

    // 7. 정비 확인: 서버 요약을 읽고 날짜·거리 기준의 예정 정보를 표시한다.
    private void renderMaintenance() {
        heading(body, "차량 관리");
        renderVehicleOdometer();
        renderMaintenanceCycles();
    }

    private void renderMaintenanceCycles() {
        if (!maintenanceVehicleId.equals(connection.vehicleId)) maintenanceSummary = null;
        sectionLabel(body, "차량 공용 정비 주기");
        MaintenanceSummary summary = maintenanceSummary;
        if (summary == null) {
            paragraph(body, maintenanceMessage);
        } else {
            detail(body, "서버에 저장된 누적거리", maintenanceDistance(summary.odometerKm));
            paragraph(body, "거리 갱신 · " + timeLabel(summary.odometerKm == null ? 0 : summary.odometerObservedAt));
            long now = System.currentTimeMillis();
            if (!summary.hasRecentOdometer(now)) paragraph(body, "최신 거리 확인 필요");
            if (summary.items.isEmpty()) {
                paragraph(body, "공동 관리 정비 항목이 없습니다. 모바일에서 항목과 주기를 설정해 주세요.");
            } else {
                LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
                for (MaintenanceSummary.Item item : summary.items) {
                    LinearLayout card = section(body);
                    subheading(card, item.name);
                    String cycle = item.intervalKm == null ? "" : maintenanceDistance(item.intervalKm);
                    if (item.intervalMonths != null) cycle += (cycle.isEmpty() ? "" : " 또는 ") + item.intervalMonths + "개월";
                    paragraph(card, cycle.isEmpty() ? "정비 주기 확인 필요" : "정비 주기 · " + cycle);
                    paragraph(card, "마지막 정비 · " + maintenanceDate(item.lastDate)
                            + " · " + maintenanceDistance(item.lastKm));
                    paragraph(card, "다음 예정 · " + maintenanceDate(item.nextDate)
                            + " · " + maintenanceDistance(item.nextKm));
                    paragraph(card, "주기 출처 · " + item.basis);
                    paragraph(card, item.replacementDday(today));
                    if (item.nextKm != null) {
                        Double remaining = item.kmUntil(summary, now);
                        paragraph(card, remaining == null ? "남은 거리 · 최신 거리 확인 필요"
                                : remaining > 0 ? "남은 거리 " + maintenanceDistance(remaining)
                                : "예정 거리 도달");
                    }
                    paragraph(card, item.status(summary, today, now));
                }
            }
        }
        Button refresh = button("정비 정보 새로 확인", false, this::loadMaintenance);
        refresh.setEnabled(online() && !connection.vehicleId.isEmpty());
        body.addView(refresh, fullButton());
        if (canEditMaintenance()) {
            Button manage = button("교체주기 관리", false, this::openMaintenanceEditor);
            manage.setTag("maintenance.manage"); manage.setEnabled(online());
            body.addView(manage, fullButton());
        }
        if (page == MAINTENANCE) primary(body, "모바일에서 기록", () -> new AlertDialog.Builder(this)
                .setTitle("모바일에서 기록")
                .setMessage("휴대폰 fuelog 앱의 차계부에서 정비 기록을 등록하거나 정비 주기를 설정해 주세요.")
                .setPositiveButton("확인", null).show());
        paragraph(body, "교체주기는 차량 공용 정보이며 오너 계정에서 변경할 수 있습니다. 확인된 마지막 교체 기록으로 다음 예정을 계산합니다.");
    }

    private boolean canEditMaintenance() {
        return maintenanceSummary != null && maintenanceSummary.canEdit
                && maintenanceVehicleId.equals(connection.vehicleId)
                && !recorder.verifiedOwner().isEmpty()
                && recorder.verifiedOwner().equals(maintenanceSummary.editorSubjectId)
                && settings.pending().isEmpty();
    }

    private void openMaintenanceEditor() {
        if (!started || !allowed || !online() || !canEditMaintenance()) return;
        closeMaintenanceEditor();
        String owner = recorder.verifiedOwner(), vehicle = connection.vehicleId;
        maintenanceEditor = new MaintenancePlanEditor(this, connection,
                () -> started && allowed && canEditMaintenance() && showingMaintenance()
                        && owner.equals(recorder.verifiedOwner()) && vehicle.equals(connection.vehicleId),
                this::loadMaintenance);
        maintenanceEditor.show();
    }

    private void closeMaintenanceEditor() {
        if (maintenanceEditor != null) maintenanceEditor.close();
        maintenanceEditor = null;
    }

    private void loadMaintenance() {
        cancelMaintenance();
        if (!started || !allowed || !showingMaintenance()) return;
        maintenanceSummary = null;
        if (connection.vehicleId.isEmpty()) {
            maintenanceMessage = "차량 등록 정보를 확인한 뒤 정비 정보를 조회할 수 있습니다";
            render(); return;
        }
        if (!online()) {
            maintenanceMessage = "오프라인 · 정비 정보를 확인할 수 없습니다";
            render(); return;
        }
        maintenanceMessage = "정비 정보를 확인 중입니다";
        render();
        final int generation = maintenanceGeneration;
        maintenanceVehicleId = connection.vehicleId;
        try {
            maintenanceCall = connection.readMaintenance(new ResultCallback() {
                public void onSuccess(ApiResponse result) {
                    runOnUiThread(() -> {
                        if (!maintenanceVisible(generation)) return;
                        maintenanceCall = null;
                        try {
                            maintenanceSummary = new MaintenanceSummary(
                                    new JSONObject(result.getBody()));
                            maintenanceMessage = "";
                        } catch (Exception error) {
                            maintenanceSummary = null;
                            connection.apiStatus().invalid("GET", "maintenance/summary");
                            maintenanceMessage = "연결 필요 · 정비 응답을 확인하지 못했습니다";
                        }
                        render();
                    });
                }
                public void onHttpError(ApiResponse result) { fail(); }
                public void onFailure(IOException error) { fail(); }
                private void fail() { runOnUiThread(() -> {
                    if (!maintenanceVisible(generation)) return;
                    maintenanceCall = null;
                    maintenanceMessage = "정비 정보를 불러오지 못했습니다. 다시 시도해 주세요";
                    render();
                }); }
            });
        } catch (Exception error) {
            maintenanceMessage = "연결 서버 설정을 확인해 주세요";
            render();
        }
    }

    private boolean showingMaintenance() {
        return page == HOME || page == MAINTENANCE || page == CONNECT && startTab == 1;
    }

    private boolean maintenanceVisible(int generation) {
        return generation == maintenanceGeneration && started && allowed
                && showingMaintenance() && maintenanceVehicleId.equals(connection.vehicleId) && !isFinishing() && !isDestroyed();
    }

    private void cancelMaintenance() {
        maintenanceGeneration++;
        if (maintenanceCall != null) maintenanceCall.cancel();
        maintenanceCall = null;
    }

    // 8. 연결·설정: 제공 상태 확인 → 사용자 해제 또는 차량 등록 초기화.
    private void renderSettings() {
        heading(body, "연결·설정");
        boolean pending = !settings.pending().isEmpty();
        detail(body, "설치 등록 방식", connection.usesIssuedInstallation() ? "서버 발급 설치 ID 사용" : "이전 설치 인증 보존 중");
        if (!connection.installationId.isEmpty()) detail(body, "설치 ID", connection.installationId);
        paragraph(body, recorder.syncMessage());
        paragraph(body, "서버 수신 sequence · " + (connection.syncStatus.isNull("lastAcceptedSequence") ? "연결 필요 · 수신값 없음" : connection.syncStatus.optLong("lastAcceptedSequence"))
                + " · 사용자 큐 확인 " + (connection.queueStatus.length() == 0 ? "미확인" : "서버 응답 수신"));
        detail(body, "인증된 사용자", recorder.displayUser());
        paragraph(body, "연결을 끊기 전까지 이 사용자의 기록으로 저장합니다. 사용자 변경은 연결 끊기 후 다시 페어링하세요.");
        detail(body, "휴대폰 연결", pending ? "로컬 잠금 · 서버 해제 대기" : connection.linked ? "차량 등록됨" : "등록 확인 필요");
        detail(body, "연결 기기 별칭", pending || recorder.verifiedOwner().isEmpty() ? "사용자 확인 필요" : settings.snapshot.optString("deviceAlias", "미확인"));
        detail(body, "기록 상태", recorder.trip.status(System.currentTimeMillis()));
        detail(body, "동기화 상태", settings.snapshot.optString("syncStatus").equals("SYNCED") ? "서버 동기화 완료" : "미전송 기록 보관 · 동기화 확인 필요");
        paragraph(body, "마지막 동기화 " + timeLabel(settings.snapshot.opt("lastSyncedAt")));
        sectionLabel(body, "데이터 제공 상태");
        detail(body, "연료", featureStatus("fuel"));
        detail(body, "거리", featureStatus("distance"));
        detail(body, "위치", featureStatus("location"));
        paragraph(body, settings.message);
        paragraph(body, "차량 위치 보조 · " + (recorder.locationAssistConsent() ? "동의함" : "사용 안 함")
                + " · 연료·배터리 증가 시 주변 주유소·충전소 후보를 찾는 데 사용합니다.");
        link(body, recorder.locationAssistConsent() ? "차량 위치 보조 동의 철회" : "차량 위치 보조에 동의", () -> {
            try { recorder.setLocationAssistConsent(!recorder.locationAssistConsent()); render(); }
            catch (IOException error) { paragraph(body, "위치 동의 저장 실패"); }
        });
        if (pending) {
            paragraph(body, "원격 연결은 아직 해제 완료로 확인되지 않았습니다.");
            Button retry = button(settings.busy ? "처리 중" : "서버 해제 다시 확인", true, () -> settings.retry(this::settingsChanged));
            retry.setEnabled(!settings.busy); body.addView(retry, fullButton());
        } else {
            link(body, "연결 상태 새로 확인", () -> settings.refresh(this::settingsChanged));
            link(body, "연결 끊기 · 사용자 변경", () -> disconnect("USER"));
            link(body, "차량 등록 초기화", () -> disconnect("VEHICLE"));
            if (!connection.linked) link(body, "차량 다시 등록", () -> navigate(CONNECT));
        }
        if (!pending && connection.linked && !recorder.verifiedOwner().isEmpty()
                && settings.snapshot.optBoolean("recommendationRegistered")
                && settings.snapshot.optBoolean("recommendationConsented")
                && settings.snapshot.optBoolean("recommendationVerified")) {
            paragraph(body, "자동 사용자 추천 · " + (settings.snapshot.optBoolean("recommendationEnabled") ? "사용 중" : "사용 안 함"));
            link(body, "휴대폰에서 추천 설정", () -> new AlertDialog.Builder(this)
                    .setMessage("휴대폰 fuelog의 M04에서 자동 사용자 추천 설정을 변경한 뒤 상태를 새로 확인해 주세요.")
                    .setPositiveButton("확인", null).show());
        }
    }

    private void settingsChanged() {
        recorder.configureStationSearch(settings.snapshot.optBoolean("pleosCoordinateSystemVerified"),
                settings.snapshot.optString("fuelType"));
        if (started && allowed && page == SETTINGS) render();
    }

    private String featureStatus(String feature) {
        JSONObject features = settings.snapshot.optJSONObject("features");
        String value = features == null ? "UNKNOWN" : features.optString(feature);
        switch (value) {
            case "AVAILABLE": return "제공 중";
            case "UNSUPPORTED": return "차량에서 지원하지 않음";
            case "PERMISSION_REQUIRED": return "권한 필요 · 시스템 설정에서 허용해 주세요";
            case "TEMPORARY_FAILURE": return "일시적으로 수신 불가 · 잠시 후 다시 확인해 주세요";
            default: return "미확인 · 연결 후 상태를 확인해 주세요";
        }
    }

    private void disconnect(String kind) {
        if (!allowed || settings.busy) return;
        new AlertDialog.Builder(this)
                .setTitle(kind.equals("VEHICLE") ? "차량 등록 초기화" : "연결 끊기 · 사용자 변경")
                .setMessage(kind.equals("VEHICLE")
                        ? "개인 화면을 잠그고 관리자 모바일 재인증을 요청합니다. 미전송 기록은 원래 소유자로 보관하며 삭제하지 않습니다. 휴대폰 M14에서 처리 방식을 확인한 뒤 초기화됩니다."
                        : "기록을 종료하고 현재 사용자와의 연결을 끊습니다. 기존 기록은 원래 사용자에게 보관하며, 다른 사용자는 새 페어링 QR로 연결할 수 있습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("계속", (dialog, which) -> {
                    if (!started || !allowed) return;
                    cancelFuelSync(); cancelMaintenance(); maintenanceSummary = null;
                    offerTripSummary = false;
                    settings.begin(kind, this::settingsChanged);
                }).show();
    }

    // 9. 화면 종료: 요청·구독을 정리하고 기록 수집을 중단한다.
    @Override protected void onStop() {
        started = false;
        closeMaintenanceEditor();
        closeVehicleOdometer();
        connection.apiStatus().changed = () -> {};
        if (apiAlert != null) { apiAlert.dismiss(); apiAlert = null; }
        connection.onPersonalInvalid = recorder::clearPersonalCache;
        cancelFuelSync();
        cancelMaintenance();
        if (liveRecorderStarted) recorder.pause();
        liveRecorderStarted = false;
        connectionHandler.removeCallbacks(connectionTick);
        allowed = false;
        if (restrictions != null) { restrictions.close(); restrictions = null; }
        super.onStop();
    }

    // 공통 연결 안내: 현재 화면에서 요청하는 API의 실제 응답 상태를 표시한다.
    private boolean online() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private String[][] pageApis() {
        switch (page) {
            case CONNECT:
                if (startTab == 1) return new String[][]{{"차량 등록 정보", "GET", "installations/me"}, {"공동 정비 정보", "GET", "maintenance/summary"}};
                if (connection.usesPairingApi()) return new String[][]{
                        {"설치 ID 발급", "POST", "installations"}, {"설치 연결 복원", "GET", "installations/me"},
                        {"차량 QR 발급", "POST", "pairing-requests"}, {"QR 승인 조회", "GET", "pairing-requests/{id}"},
                        {"차량에서 연결 확인", "POST", "pairing-requests/{id}/confirm"}};
                return new String[][]{{"차량 QR 발급", "POST", "connection/requests"}, {"QR 승인 조회", "POST", "connection/requests/status"}};
            case TRIP: return new String[][]{{"운행 요약 저장", "PUT", "recording-intervals/{id}"}, {"운행 요약 조회", "GET", "recording-intervals/{id}/summary"},
                    {"모바일 확인함 전달", "PUT", "mobile-inbox/trips/{id}"}};
            case FUEL: return new String[][]{{"주유 후보 동기화", "POST", "fuel-candidates/sync"}, {"주유소 후보 조회", "POST", "fuel-candidates/{id}/station-search"}};
            case MAINTENANCE: return new String[][]{{"정비 요약", "GET", "maintenance/summary"}};
            case SETTINGS: return new String[][]{{"연결 설정", "POST", "connection/settings"}, {"사용자별 큐 확인", "GET", "installation/sync-status"},
                    {"SDK 기능 보고", "PUT", "installation/capabilities"}, {"차량 누적거리 전송", "PUT", "vehicle-odometer/samples/{id}"},
                    {"연결 해제", "POST", "connection/disconnect"}};
            default: return new String[][]{{"차량 홈", "GET", "vehicle/home"}, {"기록 시작", "POST", "recording-intervals"},
                    {"운행 샘플 전송", "POST", "recording-intervals/{id}/samples"}, {"기록 종료", "POST", "recording-intervals/{id}/finish"},
                    {"서버 수신 상태", "GET", "sync/status"}};
        }
    }

    private void renderApiStatus() {
        if (!connection.hasServer()) {
            banner(body, "연결 필요 · 백엔드 주소 미설정", R.color.car_warning_bg,
                    R.color.car_warning_border, R.color.car_warning_text);
            link(body, "연결 필요 사유 확인", () -> connectionRequired("백엔드 주소가 설정되지 않았거나 주소 설정을 읽지 못했습니다. 서버 주소 설정 후 앱을 다시 빌드해 주세요."));
            return;
        }
        int unverified = 0;
        for (String[] api : pageApis()) {
            ApiStatus.Entry entry = connection.apiStatus().get(api[1], api[2]);
            if (entry.failed) {
                banner(body, api[0] + " · 연결 오류 · 저장된 기록은 유지됩니다",
                        R.color.car_error_bg, R.color.car_error_border, R.color.car_error_text);
                link(body, "연결 상세 확인", this::showApiDetails);
                return;
            }
            if (!entry.connected) unverified++;
        }
        banner(body, unverified > 0 ? "연결 확인 대기 · " + unverified + "개 기능 응답 미확인" : "API 연결 확인됨",
                R.color.car_info_bg, R.color.car_info_border, R.color.car_info_text);
        if (unverified > 0) link(body, "연결 상세 확인", this::showApiDetails);
    }

    private void showApiDetails() {
        StringBuilder text = new StringBuilder();
        for (String[] api : pageApis()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(api[0]).append("\n").append(connection.apiStatus().get(api[1], api[2]).detail);
        }
        text.append("\n\n아직 요청하지 않은 기능은 실행 후 연결 여부가 확인됩니다. 예시 데이터는 표시하지 않습니다.");
        connectionRequired(text.toString());
    }

    private void connectionRequired(String message) {
        if (!started || !allowed || isFinishing() || isDestroyed() || apiAlert != null) return;
        apiAlert = new AlertDialog.Builder(this).setTitle("연결 필요")
                .setMessage(message).setPositiveButton("확인", null).create();
        apiAlert.setOnDismissListener(dialog -> apiAlert = null);
        apiAlert.show();
    }

    // 공통 표시: QR·시각·수치와 화면 구성 요소.
    private void showQr(LinearLayout parent, String payload, String description) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    payload, BarcodeFormat.QR_CODE, 320, 320);
            Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) bitmap.setPixel(x, y,
                    matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap); image.setContentDescription(description);
            parent.addView(image, new LinearLayout.LayoutParams(dp(240), dp(240)));
        } catch (Exception error) { paragraph(parent, "QR 표시 실패 · 코드를 다시 받아 주세요"); }
    }

    private String timeLabel(Object timestamp) {
        return AppTime.label(timestamp);
    }

    private String serverMetric(JSONObject summary, String key, String unit) {
        JSONObject value = summary.optJSONObject(key);
        return value == null || value.isNull("value") || !"available".equals(value.optString("status"))
                ? "계산 불가" : metric(value.optDouble("value"), unit);
    }

    private String metric(double value, String unit) {
        return Double.isFinite(value) ? String.format(Locale.KOREA, "%,.1f%s", value, unit) : "계산 불가";
    }

    private String maintenanceDate(LocalDate date) {
        return date == null ? "기록 없음" : date.toString();
    }

    private String maintenanceDistance(Double km) {
        return km == null ? "기록 없음" : String.format(Locale.KOREA, "%,.0f km", km);
    }

    private LinearLayout grid(LinearLayout parent) {
        LinearLayout grid = getResources().getConfiguration().screenWidthDp >= 600 ? row() : column();
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return grid;
    }

    private LinearLayout.LayoutParams gridCell() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
        params.setMargins(0, 0, wide ? dp(14) : 0, dp(14));
        return params;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(shape(R.color.car_panel, 20, R.color.car_line));
        parent.addView(card, parent.getOrientation() == LinearLayout.HORIZONTAL ? gridCell() : cardParams());
        return card;
    }

    private LinearLayout section(LinearLayout parent) {
        return card(parent);
    }

    private LinearLayout pairingCard(LinearLayout parent) {
        LinearLayout pairing = card(parent);
        pairing.setBackground(shape(R.color.car_dark, 20, R.color.car_mint));
        return pairing;
    }

    private void pairingText(LinearLayout parent, String text, int sp, int foreground) {
        TextView view = label(text, sp, false, false);
        view.setTextColor(color(foreground));
        view.setPadding(0, dp(12), 0, dp(12));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void banner(LinearLayout parent, String text, int background, int border, int foreground) {
        TextView view = label(text, 18, true, false);
        view.setTextColor(color(foreground));
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        view.setBackground(shape(background, 12, border));
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(view, cardParams());
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(14));
        return params;
    }

    private void heading(LinearLayout parent, String value) { addText(parent, value, 38, true, false, 0, 0, 0, 18); }

    private void subheading(LinearLayout parent, String value) { addText(parent, value, 26, true, false, 0, 0, 0, 10); }

    private void paragraph(LinearLayout parent, String value) { addText(parent, value, 20, false, true, 0, 5, 0, 12); }

    private void tag(LinearLayout parent, String value) {
        TextView badge = label(value, 18, true, false);
        badge.setTextColor(color(R.color.car_on_dark));
        badge.setPadding(dp(12), dp(8), dp(12), dp(8));
        badge.setBackground(shape(R.color.car_dark, 24, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, 0, dp(14));
        parent.addView(badge, params);
    }

    private void sectionLabel(LinearLayout parent, String value) {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, dp(24));
        parent.addView(new View(this), space);
        divider(parent);
        addText(parent, value, 19, true, true, 0, 18, 0, 20);
    }

    private void number(LinearLayout parent, String amount, String unit) {
        TextView value = label(amount + unit, 64, true, false);
        SpannableString styled = new SpannableString(amount + unit);
        styled.setSpan(new RelativeSizeSpan(0.38f), amount.length(), styled.length(), 0);
        value.setText(styled);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(16), 0, dp(18));
        parent.addView(value, params);
    }

    private void detail(LinearLayout parent, String key, String value) {
        LinearLayout line = row();
        line.setPadding(0, dp(18), 0, dp(18));
        parent.addView(line);
        TextView name = label(key, 20, false, true);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(label(value, 22, true, false));
        divider(parent);
    }

    private void addText(LinearLayout parent, String value, int sp, boolean bold, boolean muted,
                         int left, int top, int right, int bottom) {
        TextView text = label(value, sp, bold, muted);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        parent.addView(text, params);
    }

    private TextView label(String value, int sp, boolean bold, boolean muted) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setLineSpacing(dp(3), 1f);
        view.setTextSize(compact() ? sp * 0.9f : sp);
        view.setTextColor(color(muted ? R.color.car_muted : R.color.car_foreground));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void primary(LinearLayout parent, String title, Runnable action) {
        LinearLayout.LayoutParams params = fullButton();
        parent.addView(button(title, true, action), params);
    }

    private void link(LinearLayout parent, String title, Runnable action) {
        Button button = headerAction(title + "  →", action);
        button.setTextColor(color(R.color.car_link));
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        parent.addView(button, fullButton());
    }

    private LinearLayout.LayoutParams fullButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(68));
        params.setMargins(0, dp(8), 0, dp(16));
        return params;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(compact() ? 20 : 22);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(64));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{color(R.color.car_muted), color(primary ? R.color.car_on_accent : R.color.car_foreground)}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, shape(R.color.car_soft, 12, R.color.car_line));
        background.addState(new int[]{}, shape(primary ? R.color.car_accent : R.color.car_panel, 12,
                primary ? 0 : R.color.car_line));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.car_line)), background, null));
        if (action != null) button.setOnClickListener(v -> action.run());
        return button;
    }

    private Button headerAction(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(compact() ? 18 : 20);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setTextColor(color(R.color.car_foreground));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.car_line)),
                shape(R.color.car_panel, 17, R.color.car_line), null));
        button.setMinHeight(dp(58));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fill));
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), color(stroke));
        return drawable;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(color(R.color.car_line));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }

    private int color(int resource) { return getColor(resource); }

    private boolean compact() { return getResources().getConfiguration().screenWidthDp < 600; }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
