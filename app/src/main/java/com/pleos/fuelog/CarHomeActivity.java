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
import com.pleos.fuelog.api.ApiClient;
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
import com.pleos.fuelog.home.TripHistory;
import com.pleos.fuelog.home.RecordStatistics;
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
    private AlertDialog consentAlert;
    private AlertDialog tripSplitAlert;
    private String tripSplitOwner = "";
    private String selectedTripId = "", selectedTripOwner = "";
    private final int[] statisticsPeriod = {1, 1};
    private final LocalDate[] statisticsDate = {LocalDate.now(), LocalDate.now()};
    private boolean startupPermissionsInFlight;
    private boolean liveRecorderStarted;
    private ConnectionSettings settings;
    private boolean offerTripSummary;
    private boolean fuelSyncBusy;
    private int fuelSyncGeneration;
    private Call fuelSyncCall;
    private com.pleos.fuelog.location.PleosLocationClient tripPriceLocation;
    private String costPermissionTripId="";
    private String fuelSyncMessage = "";
    private JSONObject nearbyFuelResult;
    private String nearbyFuelOwner = "";
    private final String nearbyFuelLocationId = java.util.UUID.randomUUID().toString();
    private MaintenanceSummary maintenanceSummary;
    private MaintenancePlanEditor maintenanceEditor;
    private Call maintenanceCall;
    private int maintenanceGeneration;
    private String maintenanceMessage = "정비 정보를 확인 중입니다";
    private String maintenanceVehicleId = "";
    private HomeRecorder recorder;
    private VehicleOdometer vehicleOdometer;
    private String vehicleOdometerMessage = "차량 연결 확인 중";
    private long lastConnectionCheck;
    private final Runnable connectionTick = new Runnable() {
        public void run() {
            if (!started) return;
            if (connection.linked && online() && android.os.SystemClock.elapsedRealtime() - lastConnectionCheck >= 5000) {
                lastConnectionCheck = android.os.SystemClock.elapsedRealtime();
                connection.refreshIdentity(() -> {
                    if (!started) return;
                    if (!connection.linked && settings.pending().isEmpty()) {
                        startTab = 0;
                        page = CONNECT;
                        cancelMaintenance();
                        closeMaintenanceEditor();
                    }
                    if (allowed) render();
                });
            }
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
            selectedTripId = state.getString("selectedTripId", "");
            selectedTripOwner = state.getString("selectedTripOwner", "");
            for (int i = 0; i < 2; i++) {
                statisticsPeriod[i] = state.getInt("statisticsPeriod" + i, 1);
                statisticsDate[i] = LocalDate.ofEpochDay(state.getLong("statisticsDate" + i, LocalDate.now().toEpochDay()));
            }
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
                if (page == TRIP && !selectedTripId.isEmpty()) { selectedTripId = ""; closeTripSplit(); render(); }
                else if (page == HOME || page == CONNECT || !settings.pending().isEmpty()) finish();
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
        connection.onPersonalInvalid = () -> { cancelFuelSync(); closeMaintenanceEditor(); recorder.clearPersonalCache(); if (started && allowed) render(); };
        offerTripSummary = !recorder.trip.active && recorder.summary() != null;
        startLiveRecorder();
        connectionHandler.post(connectionTick);
        allowed = false;
        render();
        try {
            restrictions = new AaosVehicleDiagnosticService(this);

            restrictions.start((canUse, detail) -> runOnUiThread(() -> {
                if (!started || isFinishing() || isDestroyed()) return;
                boolean wasAllowed = allowed;
                allowed = canUse;
                if (canUse) startVehicleOdometer();
                else closeVehicleOdometer();
                if (!canUse) {
                    closeConsentAlert();
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
        out.putString("selectedTripId", selectedTripId);
        out.putString("selectedTripOwner", selectedTripOwner);
        for (int i = 0; i < 2; i++) {
            out.putInt("statisticsPeriod" + i, statisticsPeriod[i]);
            out.putLong("statisticsDate" + i, statisticsDate[i].toEpochDay());
        }
        super.onSaveInstanceState(out);
    }

    // 2. 화면 이동: 현재 연결·사용자 상태에 맞는 화면을 표시한다.
    private void navigate(int destination) {
        if (!allowed) return;
        selectedTripId = "";
        closeTripSplit();
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
        if (page == TRIP) {
            recorder.refreshTripHistory();
            recorder.refreshTripCost();
            requestServicePermission("android.car.permission.CAR_ENERGY", 102);
        }
    }

    private void render() {
        if (body == null) return;
        if(started && allowed && getIntent().hasExtra("chargeInterval") && !recorder.verifiedOwner().isEmpty()) {
            String id=getIntent().getStringExtra("chargeInterval");
            String subject=getIntent().getStringExtra("chargeOwner");
            getIntent().removeExtra("chargeInterval");getIntent().removeExtra("chargeOwner");
            if(recorder.verifiedOwner().equals(subject) && recorder.completedTrip(id)!=null) {
                page=TRIP;selectedTripId=id;selectedTripOwner=subject;
            }
        }
        if (!selectedTripOwner.equals(recorder.verifiedOwner())) selectedTripId = "";
        if (tripSplitAlert != null && (!allowed || page != TRIP || !tripSplitOwner.equals(recorder.verifiedOwner()))) closeTripSplit();
        if (consentAlert != null && (!connection.linked || !consentOwner.equals(recorder.verifiedOwner()))) closeConsentAlert();
        body.post(this::showStartupConsent);
        if (maintenanceEditor != null && (!allowed || !canEditMaintenance())) closeMaintenanceEditor();
        if (!connection.linked && page != SETTINGS) page = CONNECT;
        body.removeAllViews();
        navigation.removeAllViews();
        boolean navigationAllowed = allowed && settings.pending().isEmpty();
        headerNavigation.setVisibility(navigationAllowed && (connection.linked || page != CONNECT) ? View.VISIBLE : View.GONE);
        navigation.setVisibility(navigationAllowed && connection.linked ? View.VISIBLE : View.GONE);
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
        if (!connection.linked || !settings.pending().isEmpty()) return;
        for (int i = 0; i < navPages.length; i++) {
            final int destination = navPages[i];
            Button tab = headerAction(navLabels[i], () -> navigate(destination));
            boolean selected = page == destination;
            tab.setSelected(selected);
            tab.setTextSize(24);
            tab.setTextColor(color(selected ? R.color.car_on_accent : R.color.car_muted));
            tab.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.car_line)),
                    shape(selected ? R.color.car_accent : R.color.car_soft, 24, 0), null));
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, -2, 1);
            tabParams.setMargins(dp(5), dp(4), dp(5), dp(4));
            tab.setMinHeight(dp(64));
            tab.setPadding(dp(8), dp(8), dp(8), dp(8));
            navigation.addView(tab, tabParams);
        }
    }

    // 3. 차량 연결: QR 스캔 → 양쪽 확인 코드 비교 → 차량에서 최종 승인.
    private void renderConnect() {
        TabLayout tabs = new TabLayout(this);
        tabs.setTag("start.tabs");
        tabs.setBackgroundColor(color(R.color.car_panel));
        tabs.setTabTextColors(color(R.color.car_muted), color(R.color.car_on_accent));
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
                    : connection.vehicleAlias.isBlank() ? connection.vehicleName : connection.vehicleAlias);
            paragraph(body, "공동 정비와 차량 누적거리는 사용자 연결 없이 확인할 수 있습니다.");
            sectionLabel(body, "현재 차량 센서");
            paragraph(body, "차량에서 직접 읽는 현재 값입니다. 아래 정비 이력의 마지막 수신값과 구분해 확인하세요.");
            link(body, "현재 차량 거리 확인", () -> openDiagnostics(2));
            link(body, "현재 배터리 확인", () -> openDiagnostics(1));
            renderMaintenance();
            return;
        }
        heading(body, "휴대폰으로 차량을 연결하세요");
        paragraph(body, connection.vinStatus);
        if (!connection.receivedVin.isEmpty()) paragraph(body, "VIN 수신값 · " + connection.receivedVin);
        link(body, "VIN 다시 조회", () -> connection.recheckVin(() -> { if (started && allowed) render(); }));
        if (!connection.vehicleAlias.isBlank()) {
            subheading(body, connection.vehicleAlias);
            link(body, "차량 별칭 수정", this::editVehicleAlias);
        }
        if (!online()) { paragraph(body, "인터넷 연결이 필요합니다. 네트워크를 확인해 주세요."); return; }
        if (connection.linked) { link(body, "이미 연결된 기기 · 연결 설정", () -> navigate(SETTINGS)); return; }
        if (connection.vehicleAlias.isBlank()) {
            subheading(body, "차량 별칭을 먼저 지정해 주세요");
            paragraph(body, "새 차량 등록 시 차량 이름으로 사용합니다.");
            android.widget.EditText alias = new android.widget.EditText(this);
            alias.setTag("pairing.vehicleAlias");
            alias.setHint("차량 별칭 · 최대 100자");
            alias.setContentDescription("차량 별칭");
            alias.setTextSize(26);
            alias.setSingleLine(true);
            alias.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
            alias.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
            body.addView(alias, new LinearLayout.LayoutParams(-1, -2));
            Button issue = button("별칭 저장하고 QR 받기", true, () -> {
                try {
                    connection.setVehicleAlias(alias.getText().toString());
                    connection.retry(() -> { if (started && allowed) render(); });
                } catch (Exception invalid) { alias.setError("차량 별칭을 입력해 주세요 (1~100자)"); }
            });
            issue.setTag("pairing.issue");
            body.addView(issue, fullButton());
            return;
        }
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
        recorder.attachScreen(() -> { if (started && allowed) render(); });
        if(getSharedPreferences("recording_service",MODE_PRIVATE).getBoolean("enabled",true)) {
            try { androidx.core.content.ContextCompat.startForegroundService(this,new Intent(this,com.pleos.fuelog.home.RecordingService.class)); }
            catch(RuntimeException unavailable) { recorder.storageError="백그라운드 수집 시작 실패 · 연결·설정에서 다시 시작해 주세요"; }
        }
    }

    private void openDiagnostics(int category) {
        if (category == 1 && checkSelfPermission("android.car.permission.CAR_ENERGY") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestServicePermission("android.car.permission.CAR_ENERGY", 102); return;
        }
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
        tag(body, "연결된 차량 · " + (!connection.vehicleAlias.isBlank() ? connection.vehicleAlias : connection.home.isNull("vehicleAlias") ? "별칭 미설정" : connection.home.optString("vehicleAlias")));
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
        subheading(current, recorder.chargingInterval() ? "충전 관측 · 스위치 ON → OFF" : "이번 운행 · D → P");
        paragraph(current, recorder.chargeSwitchStatus() + " · 실제 충전·결제 완료와 구분합니다");
        if (trip.sampledAt == 0) paragraph(current, trip.active ? "차량 거리 수신 대기" : "D 전환 대기 · 자동 기록");
        else number(current, String.format(Locale.KOREA, "%.1f", trip.distance), " km");
        paragraph(current, "마지막 샘플 · 최근 수신 " + timeLabel(trip.sampledAt));
        JSONObject cost = connection.home.optJSONObject("estimatedCost");
        paragraph(current, "추정 비용 · " + (cost == null || cost.isNull("value") || !"available".equals(cost.optString("status")) ? "개인 홈 인증 후 제공"
                : metric(cost.optDouble("value"), " " + cost.optString("currency"))));
        paragraph(current, "소모량·비용 계산에는 연료 잔량과 단가 확인이 필요합니다");
        link(current, "이번 운행 요약", () -> navigate(TRIP));
        paragraph(current, "D에서 운행을 시작하고 P에서 종료 거리와 운행 요약을 자동 저장합니다. 수집 서비스가 실행 중이면 화면을 닫아도 기록을 유지합니다.");
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
        TripSummary summary = recorder.summary();
        if (selectedTripId.isEmpty()) {
            renderTripHistory();
            if (summary != null && (recorder.trip.active || summary.data.optBoolean("chargingOnly"))) {
                String currentId = summary.data.optString("intervalId");
                link(body, summary.data.optBoolean("chargingOnly") ? "충전 관측 요약" : "현재 기록 상세", () -> openTrip(currentId));
            }
            return;
        }
        if (summary == null || !selectedTripId.equals(summary.data.optString("intervalId"))) summary = recorder.completedTrip(selectedTripId);
        link(body, "운행 목록으로", () -> { selectedTripId = ""; closeTripSplit(); render(); });
        heading(body, summary != null && summary.data.optBoolean("chargingOnly") ? "충전 관측 요약" : "운행 상세");
        if (summary == null) {
            paragraph(body, "확인할 수 있는 운행 기록이 없습니다. 사용자를 확인하거나 기록을 시작해 주세요.");
            link(body, "연결 상태 확인", () -> navigate(SETTINGS)); return;
        }
        JSONObject data = summary.data;
        String id = data.optString("intervalId");
        long end = data.optLong("endedAt");
        boolean electric = data.optBoolean("evDetected") || !data.has("startFuelLiters");
        double amount = TripHistory.amount(data, recorder.serverSummary(id));
        detail(body, "추정 금액", metric(amount, "원"));
        final double total = amount;
        String splitOwner = recorder.verifiedOwner();
        Button split = button("인원별 N빵 계산", false, () -> showTripSplit(total, splitOwner));
        split.setTag("trip.detail.split");
        split.setEnabled(end > 0 && Double.isFinite(total) && total >= 0 && !data.optBoolean("chargingOnly"));
        body.addView(split, fullButton());
        paragraph(body, "앱 기록 구간 · " + timeLabel(data.optLong("startedAt")) + " → "
                + (end == 0 ? "기록 진행 중" : timeLabel(end)) + (data.optBoolean("endEstimated") ? " · 종료 시각 추정" : ""));
        LinearLayout distance = card(body);
        subheading(distance, "거리");
        paragraph(distance, data.optBoolean("chargingOnly") ? "정차 충전 관측 구간 · 운행 거리 계산 대상 아님" : data.optBoolean("distanceValid") ? metric(data.optDouble("distanceKm"), " km") : data.optLong("sampledAt") == 0 ? "차량 거리 수신 대기" : "첫 거리 수신 · 다음 샘플 대기");
        if (electric) {
            detail(body, "충전 스위치", !data.optBoolean("chargeSwitchKnown") ? "미확인" : data.optBoolean("chargeSwitchOn") ? "ON" : "OFF");
            detail(body, "추정 소모 전력량", Double.isFinite(summary.consumedKwh()) ? metric(summary.consumedKwh(), " kWh") : batterySummaryReason(data));
            detail(body, "추정 전비", Double.isFinite(summary.evEconomy()) ? metric(summary.evEconomy(), " km/kWh") : "유효한 거리와 소모 전력량 확인 필요");
            renderTripCost(summary);
            JSONArray charges = data.optJSONArray("chargeCandidates");
            if (charges != null) for (int i = 0; i < charges.length(); i++) {
                JSONObject charge = charges.optJSONObject(i);
                if (charge == null) continue;
                String chargeState = charge.optString("status");
                detail(body, chargeState.equals("CONFIRMED") ? "모바일 확인된 충전" : chargeState.equals("REJECTED") ? "기각된 충전 후보" : "충전 추정 · 사용자 확인 전",
                        metric(charge.optDouble("estimatedKwh", Double.NaN), " kWh"));
                renderLocation(charge.optString("id"));
                link(body, "충전 후보 상태 확인", () -> refreshChargeCandidate(charge.optString("id")));
                paragraph(body, "감지 " + timeLabel(charge.optLong("startAt")) + " → " + timeLabel(charge.optLong("endAt")));
            }
            paragraph(body, "배터리 증가는 충전·결제 완료를 의미하지 않습니다. 모바일에서 확인해 주세요.");
        } else {
            detail(body, "추정 소모량", metric(summary.liters(), " L"));
            detail(body, "추정 연비", metric(summary.economy(), " km/L"));
            detail(body, "추정 연료비", metric(summary.fuelCost(), "원"));
        }
        paragraph(body, electric ? "추정 운행 비용은 실제 충전 결제금액과 다를 수 있습니다." : "단가 출처 · " + data.optString("priceSource", "유효한 단가 없음"));
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
            if (!electric) detail(body, "비용", serverMetric(serverSummary, "estimatedCost", "원"));
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

    private void renderTripHistory() {
        heading(body, "운행 기록");
        if (recorder.verifiedOwner().isEmpty()) {
            paragraph(body, "사용자 확인 후 내 운행 목록을 볼 수 있습니다.");
            return;
        }
        paragraph(body, "이 단말의 완료된 운행 · 서버 요약 우선 표시 · 금액은 추정 비용");
        JSONArray history = tripStatisticsRecords();
        RecordStatistics statistics = renderStatistics(0, history, "주행거리", "km");
        if (statistics.count == 0) paragraph(body, "선택한 기간의 완료된 운행 기록이 없습니다.");
        for (int i = 0; i < history.length(); i++) {
            JSONObject data = history.optJSONObject(i);
            if (data == null || statistics.bucket(data.opt("statAt")) < 0) continue;
            TripSummary local = new TripSummary(data);
            JSONObject remote = recorder.serverSummary(data.optString("intervalId"));
            boolean electric = data.optBoolean("evDetected") || !data.has("startFuelLiters");
            LinearLayout entry = card(body);
            Object start = remote == null ? data.opt("startedAt") : remote.opt("startedAt");
            Object end = remote == null ? data.opt("endedAt") : remote.opt("endedAt");
            paragraph(entry, tripDate(start) + " → " + tripDate(end));
            try {
                long startMillis = AppTime.epochMillis(start), endMillis = AppTime.epochMillis(end);
                if (startMillis <= 0 || endMillis <= 0) throw new IllegalArgumentException();
                long seconds = java.time.Duration.between(java.time.Instant.ofEpochMilli(startMillis),
                        java.time.Instant.ofEpochMilli(endMillis)).getSeconds();
                paragraph(entry, seconds < 0 ? "소요 시간 확인 필요" : "소요 시간 · " + seconds / 60 + "분 " + seconds % 60 + "초");
            } catch (RuntimeException invalid) { paragraph(entry, "소요 시간 확인 필요"); }
            double distance = TripHistory.distance(data, remote);
            double economy = remote == null ? electric ? local.evEconomy() : local.economy()
                    : TripHistory.metric(remote, electric ? "energyEfficiencyKmPerKwh" : "fuelEfficiencyKmPerLiter");
            double amount = TripHistory.amount(data, remote);
            detail(entry, "거리", metric(distance, " km"));
            detail(entry, electric ? "전비" : "연비", metric(economy, electric ? " km/kWh" : " km/L"));
            detail(entry, "추정 금액", Double.isFinite(amount) ? String.format(Locale.KOREA, "%,.0f원", amount) : "계산 불가");
            paragraph(entry, remote == null ? "로컬 기록 · 서버 요약 대기" : "서버 요약 · " + timeLabel(remote.opt("syncedAt")));
            JSONObject quality = remote == null ? data : remote.optJSONObject("quality");
            if (quality != null && (quality.optBoolean("missing") || quality.optBoolean("endEstimated")))
                paragraph(entry, "일부 기록 누락 또는 종료 시각 추정");
            String tripId = data.optString("intervalId");
            entry.setTag("trip.history.card." + tripId);
            entry.setOnClickListener(v -> openTrip(tripId));
            entry.setFocusable(true);
            link(entry, "상세 내용 · N빵 계산", () -> openTrip(tripId));
        }
        Button refresh = button("운행 요약 새로 확인", false, () -> { recorder.refreshTripHistory(); render(); });
        refresh.setEnabled(online());
        body.addView(refresh, fullButton());
    }

    private void openTrip(String id) {
        if (!allowed || recorder.verifiedOwner().isEmpty()) return;
        selectedTripId = id; selectedTripOwner = recorder.verifiedOwner();
        render();
    }

    private JSONArray tripStatisticsRecords() {
        JSONArray history = recorder.tripHistory();
        for (int i = 0; i < history.length(); i++) {
            JSONObject data = history.optJSONObject(i);
            if (data == null) continue;
            JSONObject remote = recorder.serverSummary(data.optString("intervalId"));
            try {
                data.put("statAt", remote == null ? data.opt("endedAt") : remote.opt("endedAt"));
                putStatistic(data, "statValue", TripHistory.distance(data, remote));
                putStatistic(data, "statAmount", TripHistory.amount(data, remote));
            } catch (org.json.JSONException invalid) { /* The original recording remains unchanged. */ }
        }
        return history;
    }

    private void putStatistic(JSONObject data, String key, double value) throws org.json.JSONException {
        data.put(key, Double.isFinite(value) && value >= 0 ? value : JSONObject.NULL);
    }

    private RecordStatistics renderStatistics(int screen, JSONArray records, String name, String unit) {
        LinearLayout panel = card(body);
        subheading(panel, name + " 통계");
        LinearLayout tabs = row(); panel.addView(tabs);
        String[] labels = {"일", "월", "년"};
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            Button tab = button(labels[i], statisticsPeriod[screen] == i, () -> { statisticsPeriod[screen] = mode; render(); });
            tab.setTag("statistics." + screen + ".period." + i);
            tab.setSelected(statisticsPeriod[screen] == i);
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        int period = statisticsPeriod[screen];
        LocalDate date = statisticsDate[screen];
        String dateLabel = period == 0 ? date.toString() : period == 1 ? java.time.YearMonth.from(date).toString() : String.valueOf(date.getYear());
        subheading(panel, dateLabel);
        LinearLayout controls = row(); panel.addView(controls);
        controls.addView(button("이전", false, () -> moveStatistics(screen, -1)), new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("오늘 기준", false, () -> { statisticsDate[screen] = LocalDate.now(); render(); }), new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("다음", false, () -> moveStatistics(screen, 1)), new LinearLayout.LayoutParams(0, -2, 1));
        RecordStatistics statistics = new RecordStatistics(records, date, period, java.time.ZoneId.systemDefault());
        paragraph(panel, statistics.count + "건 · 합계 " + (statistics.count > 0 && statistics.missingValues == statistics.count
                ? "계산 불가" : metric(statistics.total, " " + unit)));
        paragraph(panel, "확인된 추정 금액 합계 · " + (statistics.count > 0 && statistics.missingAmounts == statistics.count
                ? "계산 불가" : statistics.amount.toPlainString() + "원"));
        if (statistics.missingValues > 0 || statistics.missingAmounts > 0)
            paragraph(panel, "측정값 미확인 " + statistics.missingValues + "건 · 금액 미확인 " + statistics.missingAmounts + "건은 합계에서 제외");
        paragraph(panel, screen == 0 ? "운행 종료일 기준 · 이 단말의 완료 기록" : "모바일 확인된 주유만 집계 · 주유량·금액은 서버 추정값");
        double max = 0;
        for (double value : statistics.values) max = Math.max(max, value);
        paragraph(panel, "세로축 " + unit + " · 가로축 " + (period == 0 ? "시간" : period == 1 ? "일" : "월")
                + " · 최대 " + metric(max, " " + unit) + " · 좌우로 스크롤");
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        LinearLayout chart = row(); chart.setGravity(Gravity.BOTTOM);
        chart.setTag("statistics." + screen + ".chart");
        scroll.addView(chart); panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < statistics.values.length; i++) {
            String bucket = period == 0 ? i + "시" : (i + 1) + (period == 1 ? "일" : "월");
            String value = statistics.measured[i] == 0 && statistics.counts[i] > 0 ? "미확인"
                    : String.format(Locale.KOREA, "%.1f", statistics.values[i]);
            LinearLayout bar = column(); bar.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            bar.setPadding(dp(4), 0, dp(4), dp(8));
            bar.setContentDescription(bucket + " " + value + " " + unit + ", " + statistics.counts[i] + "건, 측정 미확인 "
                    + (statistics.counts[i] - statistics.measured[i]) + "건");
            bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            TextView number = label(value, 18, false, false);
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            bar.addView(number);
            View fill = new View(this);
            fill.setBackground(shape(statistics.counts[i] > statistics.measured[i] ? R.color.car_warning_text : R.color.car_accent, 4, 0));
            bar.addView(fill, new LinearLayout.LayoutParams(dp(24), dp(statistics.values[i] > 0 && max > 0
                    ? Math.max(2, (int) (120 * statistics.values[i] / max)) : 1)));
            TextView caption = label(bucket, 18, false, true);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            bar.addView(caption);
            chart.addView(bar, new LinearLayout.LayoutParams(dp(84), dp(210)));
        }
        paragraph(panel, "주황색 막대는 측정값이 일부 없는 구간입니다. 일시 미확인 기록은 통계에서 제외합니다.");
        return statistics;
    }

    private void moveStatistics(int screen, int direction) {
        LocalDate date = statisticsDate[screen];
        statisticsDate[screen] = statisticsPeriod[screen] == 0 ? date.plusDays(direction)
                : statisticsPeriod[screen] == 1 ? date.plusMonths(direction) : date.plusYears(direction);
        render();
    }

    private String tripDate(Object timestamp) {
        try {
            long value = AppTime.epochMillis(timestamp);
            if (value <= 0) return "시간 확인 필요";
            return java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss z", Locale.KOREA)
                    .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(value));
        } catch (RuntimeException invalid) { return "시간 확인 필요"; }
    }

    private void showTripSplit(double amount, String owner) {
        if (!started || !allowed || owner.isEmpty() || !owner.equals(recorder.verifiedOwner()) || !Double.isFinite(amount) || amount < 0) return;
        closeTripSplit();
        LinearLayout content = column();
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        paragraph(content, "총 추정 금액 · " + TripHistory.won(amount).toPlainString() + "원");
        android.widget.NumberPicker people = new android.widget.NumberPicker(this);
        people.setMinValue(1); people.setMaxValue(99); people.setValue(2); people.setWrapSelectorWheel(false);
        people.setContentDescription("나눌 인원수");
        people.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.addView(people);
        TextView share = label("", 22, true, false);
        content.addView(share);
        Runnable update = () -> {
            java.math.BigDecimal[] parts = TripHistory.splitWon(amount, people.getValue());
            int extra = parts[1].intValueExact();
            share.setText(people.getValue() + "명 · 1인 " + parts[0].toPlainString() + "원"
                    + (extra == 0 ? "" : "\n" + extra + "명은 1원 추가"));
        };
        people.setOnValueChangedListener((picker, before, after) -> update.run());
        update.run();
        paragraph(content, "원 단위 반올림 후 나눕니다. 실제 결제·송금 기능은 없습니다.");
        tripSplitOwner = owner;
        tripSplitAlert = new AlertDialog.Builder(this).setTitle("인원별 N빵 계산").setView(content)
                .setPositiveButton("닫기", null).create();
        tripSplitAlert.show();
    }

    private void closeTripSplit() {
        if (tripSplitAlert != null) tripSplitAlert.dismiss();
        tripSplitAlert = null; tripSplitOwner = "";
    }

    private void renderLocation(String id) {
        JSONObject position = recorder.visibleLocation(id);
        if (position == null) { paragraph(body, "이 조회 세션의 위치 수신값 없음"); return; }
        subheading(body, position.optString("purpose").equals("CURRENT_REFERENCE") ? "현재 조회 위치"
                : position.optString("purpose").equals("TRIP_END") ? "운행 종료 확인 위치" : "차량 증가 감지 위치");
        detail(body, "위도", String.format(Locale.US, "%.6f", position.optDouble("latitude")));
        detail(body, "경도", String.format(Locale.US, "%.6f", position.optDouble("longitude")));
        paragraph(body, "차량 위치 측정 " + timeLabel(position.optLong("measuredAt")));
    }

    private void renderStationAddress(JSONObject station) {
        String address = station.optString("address", "").trim();
        paragraph(body, address.isEmpty() ? "주소 정보 없음" : "주소 · " + address);
    }

    private void renderTripCost(TripSummary summary) {
        if(recorder.visibleLocation(summary.data.optString("intervalId"))!=null) renderLocation(summary.data.optString("intervalId"));
        JSONObject cost=summary.data.optJSONObject("savedEvCost");
        if(TripSummary.validSavedCost(cost)) {
            detail(body,"총 추정 전력비",metric(summary.evCost(),"원"));
            paragraph(body,"LEGACY_SAVED_ESTIMATE".equals(cost.optString("basis")) ? "기존 저장 금액" : "완속 기준 · " + cost.optInt("sourceCount") + "개 사업자 평균 단가");
            paragraph(body,"요금 기준일 · " + cost.optString("tariffDate"));
            paragraph(body,"금액 저장일 · " + timeLabel(cost.optString("calculatedAt")));
            if("CONFIRMATION".equals(cost.optString("locationBasis"))) paragraph(body,"확인 시점 위치 · 운행일 요금 기준");
            paragraph(body,"저장된 금액은 변경할 수 없습니다.");
            return;
        }
        detail(body,"총 추정 전력비","미확인");
        String status=summary.data.optString("evCostStatus");
        paragraph(body,status.equals("CALCULATING") && recorder.tripCostBusy(summary.data.optString("intervalId")) ? "운행 저장 완료 · 금액 계산 중"
                : status.equals("COORDINATE_SYSTEM_UNVERIFIED") ? "차량 좌표계 검증 설정 필요 · 금액 미저장"
                : status.equals("INSUFFICIENT_HISTORICAL_SLOW_RATES") ? "운행일 기준 완속 요금·주변 사업자 3곳 자료 부족 · 금액 미저장"
                : status.equals("CALCULATION_UNAVAILABLE") ? "금액 확인 실패 · 기록은 저장되어 있습니다"
                : "위치 권한을 허용하고 금액을 확인해 주세요");
        if(Double.isFinite(summary.consumedKwh()) && summary.data.optLong("endedAt")>0 && !summary.data.optBoolean("chargingOnly")) {
            Button confirm=button(recorder.tripCostBusy(summary.data.optString("intervalId")) ? "금액 계산 중" : "금액 확인하기",false,()->lookupTripPrices(summary.data.optString("intervalId")));
            confirm.setTag("trip.cost.confirm");
            confirm.setEnabled(!recorder.tripCostBusy(summary.data.optString("intervalId")) && recorder.serverSummary(summary.data.optString("intervalId"))!=null);
            body.addView(confirm,fullButton());
            paragraph(body,"운행 동기화 후 확인할 수 있습니다. 운행일 요금으로 계산하며 확인한 날짜에 한 번 저장됩니다.");
        }
    }

    private void lookupTripPrices(String id) {
        TripSummary summary=recorder.completedTrip(id);
        if(!started || !allowed || page!=TRIP || summary==null || summary.data.optLong("endedAt")==0
                || !Double.isFinite(summary.consumedKwh()) || TripSummary.validSavedCost(summary.data.optJSONObject("savedEvCost"))) return;
        if(checkSelfPermission(com.pleos.fuelog.location.PleosLocationClient.PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            costPermissionTripId=id;
            requestServicePermission(com.pleos.fuelog.location.PleosLocationClient.PERMISSION,103);return;
        }
        recorder.confirmTripCost(id);
        render();
    }

    private String batterySummaryReason(JSONObject data) {
        if (!data.optBoolean("evDetected") && checkSelfPermission("android.car.permission.CAR_ENERGY") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "배터리 읽기 권한 필요 · 연결·설정에서 허용";
        if (data.optBoolean("evMissing") || data.optBoolean("missing")) return "수신 누락 · 연속된 배터리 측정값 필요";
        if (data.optBoolean("chargedDuringInterval")) return "운행 중 배터리 증가 · 소모량 계산 제외";
        if (!data.optBoolean("evDetected")) return "충전 스위치·배터리 수신 대기";
        if (!data.optBoolean("evEndpointsValid")) return "비충전 구간의 배터리 샘플 대기";
        return "배터리 감소량 없음";
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
        heading(body, "주유 기록");
        if (recorder.verifiedOwner().isEmpty()) {
            paragraph(body, "계정 귀속을 확인할 수 없습니다. 이번 운행 사용자를 먼저 확인해 주세요.");
            link(body, "연결 상태 확인", () -> navigate(SETTINGS)); return;
        }
        renderFuelHistory();
        renderNearbyFuelStations();
        JSONObject candidate = recorder.fuelCandidate();
        if (candidate == null) {
            paragraph(body, "현재 사용자에게 표시할 연료 증가 감지 기록이 없습니다.");
            paragraph(body, "연료 정보를 수신할 수 있는 동안 증가 여부를 확인합니다."); return;
        }
        String id = candidate.optString("id"), state = candidate.optString("status");
        sectionLabel(body, "최근 주유 감지 상세");
        renderLocation(id);
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
                renderStationAddress(station);
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

    private void renderFuelHistory() {
        JSONArray history = recorder.fuelHistory(), confirmed = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            JSONObject data = history.optJSONObject(i);
            if (data == null) continue;
            try {
                data.put("statAt", data.opt("endAt"));
                putStatistic(data, "statValue", data.optBoolean("capacityVerified") ? data.optDouble("estimatedLiters", Double.NaN) : Double.NaN);
                putStatistic(data, "statAmount", data.optDouble("estimatedAmount", Double.NaN));
                if ("CONFIRMED".equals(data.optString("status"))) confirmed.put(data);
            } catch (org.json.JSONException invalid) { /* Missing metadata never becomes a measured zero. */ }
        }
        RecordStatistics statistics = renderStatistics(1, confirmed, "주유량", "L");
        sectionLabel(body, "주유 내역");
        paragraph(body, "이 단말의 감지 기록 · 확인 전·기각 항목은 통계에서 제외");
        int visible = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject data = history.optJSONObject(i);
            if (data == null || statistics.bucket(data.opt("statAt")) < 0) continue;
            visible++;
            LinearLayout entry = card(body);
            subheading(entry, tripDate(data.opt("endAt")));
            String state = data.optString("status");
            tag(entry, "CONFIRMED".equals(state) ? "모바일 확인됨" : "REJECTED".equals(state) ? "기각됨 · 통계 제외" : "확인 전 · 통계 제외");
            JSONArray stations = data.optJSONArray("stationCandidates");
            paragraph(entry, stations == null || stations.length() == 0 ? "주유소 미확인"
                    : stations.length() == 1 ? "주유소 후보 · " + stations.optString(0) : "주유소 후보 " + stations.length() + "곳 · 미확정");
            detail(entry, "추정 주유량", metric(data.optDouble("statValue", Double.NaN), " L"));
            double amount = data.optDouble("statAmount", Double.NaN);
            detail(entry, "추정 금액", Double.isFinite(amount) && amount >= 0 ? TripHistory.won(amount).toPlainString() + "원" : "계산 불가");
            Button refresh = button("상태 새로 확인", false, () -> syncFuel(data));
            refresh.setEnabled(online() && !fuelSyncBusy);
            entry.addView(refresh, fullButton());
        }
        if (visible == 0) paragraph(body, "선택한 기간의 주유 내역이 없습니다.");
    }

    private void renderNearbyFuelStations() {
        subheading(body, "현재 위치 주변 오피넷 주유소");
        renderLocation(nearbyFuelLocationId);
        if (nearbyFuelResult != null && nearbyFuelOwner.equals(recorder.verifiedOwner())) {
            paragraph(body, "조회 " + timeLabel(nearbyFuelResult.optString("fetchedAt")) + " · 실제 주유처 미확정");
            JSONArray stations = nearbyFuelResult.optJSONArray("stations");
            if (stations != null) for (int i = 0; i < stations.length(); i++) {
                JSONObject station = stations.optJSONObject(i); if (station == null) continue;
                paragraph(body, station.optString("name", "주유소 이름 미확인") + " · " + metric(station.optDouble("distanceMeters", Double.NaN), " m"));
                renderStationAddress(station);
                if (FuelLocationEvidence.validPrice(station, System.currentTimeMillis()))
                    detail(body, "게시 단가", metric(station.optDouble("postedPriceWonPerLiter"), " 원/L"));
            }
            String status = nearbyFuelResult.optString("searchStatus");
            if (!status.equals("COMPLETED")) paragraph(body, status.equals("OPINET_NOT_CONFIGURED") ? "오피넷 서버 인증키 설정 필요"
                    : status.equals("COORDINATE_SYSTEM_UNVERIFIED") ? "차량 좌표계·KATEC 변환 검증 설정 필요"
                    : status.equals("NO_STATIONS") ? "주변 주유소 검색 결과 없음" : "주유소 조회 실패 · 다시 시도해 주세요");
        }
        Button lookup = button(fuelSyncBusy ? "주유소 조회 중" : "현재 위치로 주유소 이름·주소 조회", false, () -> {
            new AlertDialog.Builder(this).setTitle("조회할 유종")
                    .setItems(new String[]{"보통휘발유", "자동차용 경유", "고급휘발유", "차량용 LPG"}, (dialog, which) ->
                            lookupFuelStations(new String[]{"B027", "D047", "B034", "K015"}[which])).show();
        });
        lookup.setEnabled(!fuelSyncBusy); body.addView(lookup, fullButton());
        paragraph(body, "조회 위치는 새 차량 위치 1건입니다. 기존 주유 감지 위치·실제 주유소를 변경하지 않습니다.");
        if (!fuelSyncMessage.isEmpty()) paragraph(body, fuelSyncMessage);
    }

    private void lookupFuelStations(String fuelType) {
        if (!started || !allowed || page != FUEL || fuelSyncBusy || recorder.verifiedOwner().isEmpty()) return;
        if (checkSelfPermission(com.pleos.fuelog.location.PleosLocationClient.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestServicePermission(com.pleos.fuelog.location.PleosLocationClient.PERMISSION, 104); return;
        }
        String owner = recorder.verifiedOwner();
        fuelSyncBusy = true; int version = ++fuelSyncGeneration;
        if (tripPriceLocation != null) tripPriceLocation.close();
        tripPriceLocation = new com.pleos.fuelog.location.PleosLocationClient(this);
        tripPriceLocation.getCurrentLocation(new com.pleos.fuelog.location.PleosLocationClient.Callback() {
            private boolean current() { return started && allowed && page == FUEL && version == fuelSyncGeneration && owner.equals(recorder.verifiedOwner()); }
            public void onSuccess(com.pleos.fuelog.location.PleosLocationClient.Position position) {
                if (!current()) return;
                try {
                    recorder.rememberLocation(nearbyFuelLocationId, owner, "CURRENT_REFERENCE", position.getLatitude(), position.getLongitude(), position.getTimestampMillis());
                    JSONObject request = new JSONObject().put("latitude", position.getLatitude()).put("longitude", position.getLongitude())
                            .put("measuredAt", java.time.Instant.ofEpochMilli(position.getTimestampMillis()).toString())
                            .put("receivedAt", java.time.Instant.now().toString()).put("source", "PLEOS_CURRENT_UPDATE").put("fuelType", fuelType);
                    fuelSyncCall = connection.request("POST", "fuel-candidates/nearby-stations", request, "", new ResultCallback() {
                        public void onSuccess(ApiResponse response) { connectionHandler.post(() -> {
                            if (!current()) return;
                            try { nearbyFuelResult = new JSONObject(response.getBody()); nearbyFuelOwner = owner; fuelSyncMessage = "주유소 조회 완료"; }
                            catch (Exception invalid) { fuelSyncMessage = "주유소 응답 확인 실패"; }
                            fuelSyncBusy = false; render();
                        }); }
                        public void onHttpError(ApiResponse response) { fail("주유소 조회 실패 · HTTP " + response.getStatusCode()); }
                        public void onFailure(IOException error) { fail("주유소 서버 연결 실패"); }
                    });
                } catch (Exception invalid) { fail("차량 위치 확인 실패"); }
            }
            public void onError(com.pleos.fuelog.location.PleosLocationClient.ErrorCode code, Exception cause) { fail("차량 위치 수신 실패 · " + code.name()); }
            private void fail(String message) { connectionHandler.post(() -> { if (current()) { fuelSyncBusy = false; fuelSyncMessage = message; render(); } }); }
        });
        render();
    }

    private void syncFuel(JSONObject candidate) {
        if (!allowed || !online() || fuelSyncBusy || recorder.verifiedOwner().isEmpty()) return;
        final String subject = recorder.verifiedOwner(), id = candidate.optString("id");
        final int version = ++fuelSyncGeneration;
        fuelSyncBusy = true;
        try {
            JSONObject request = new JSONObject(candidate.toString());
            for (String key : new String[]{"statAt", "statValue", "statAmount"}) request.remove(key);
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
        if (tripPriceLocation != null) { tripPriceLocation.close(); tripPriceLocation = null; }
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
            LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
            for(String basis:new String[]{"MANUFACTURER","USER"}) {
                sectionLabel(body,basis.equals("MANUFACTURER")?"제조사 정비 항목":"사용자 설정 정비");
                boolean found=false;
                for(MaintenanceSummary.Item item:summary.items) {
                    if(!basis.equals(item.basisCode)) continue;
                    found=true;
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
                    if(item.lastDate==null && item.lastKm==null && !item.id.isEmpty() && canEditMaintenance()
                            && (!"MANUFACTURER".equals(item.basisCode) || item.manufacturerEvidenceVerified)) {
                        Button first=button("첫 정비 기록 등록",false,()->openMaintenanceEditor(false,item.id));
                        first.setEnabled(online());card.addView(first,fullButton());
                    }
                }
                if(!found) {
                    paragraph(body,basis.equals("MANUFACTURER")?"등록된 제조사 정비 기준이 없습니다.":"등록된 사용자 정비 항목이 없습니다.");
                    if(basis.equals("USER") && canEditMaintenance()) {
                        Button add=button("사용자 정비 등록",true,()->openMaintenanceEditor(true));
                        add.setEnabled(online());body.addView(add,fullButton());
                    }
                }
            }
        }
        Button refresh = button("정비 정보 새로 확인", false, this::loadMaintenance);
        refresh.setEnabled(online() && !connection.vehicleId.isEmpty());
        body.addView(refresh, fullButton());
        if (canEditMaintenance()) {
            Button manage = button(summary.items.isEmpty()?"첫 정비 주기 등록":"정비 항목 등록 · 관리", false, this::openMaintenanceEditor);
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

    private void openMaintenanceEditor() {openMaintenanceEditor(false);}

    private void openMaintenanceEditor(boolean create) {openMaintenanceEditor(create,"");}

    private void openMaintenanceEditor(boolean create,String itemId) {
        if (!started || !allowed || !online() || !canEditMaintenance()) return;
        closeMaintenanceEditor();
        String owner = recorder.verifiedOwner(), vehicle = connection.vehicleId;
        maintenanceEditor = new MaintenancePlanEditor(this, connection,
                () -> started && allowed && canEditMaintenance() && showingMaintenance()
                        && owner.equals(recorder.verifiedOwner()) && vehicle.equals(connection.vehicleId),
                this::loadMaintenance);
        if(!itemId.isEmpty()) maintenanceEditor.edit(itemId);
        else if(create) maintenanceEditor.create();else maintenanceEditor.show();
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
        boolean collection=getSharedPreferences("recording_service",MODE_PRIVATE).getBoolean("enabled",true);
        paragraph(body,collection?"화면을 닫아도 차량 기록 수집을 유지합니다":"차량 기록 수집이 중지되어 있습니다");
        link(body,collection?"차량 기록 수집 중지":"차량 기록 수집 시작",()-> {
            getSharedPreferences("recording_service",MODE_PRIVATE).edit().putBoolean("enabled",!collection).apply();
            if(collection) stopService(new Intent(this,com.pleos.fuelog.home.RecordingService.class));
            else {liveRecorderStarted=false;startLiveRecorder();}
            render();
        });
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            link(body,"충전 완료 알림 허용",()-> {if(started && allowed) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},107);});
        paragraph(body, connection.vinStatus);
        if (!connection.receivedVin.isEmpty()) paragraph(body, "VIN 수신값 · " + connection.receivedVin);
        link(body, "VIN 다시 조회", () -> connection.recheckVin(() -> { if (started && allowed) render(); }));
        detail(body, "차량 별칭", connection.vehicleAlias.isBlank() ? "미설정" : connection.vehicleAlias);
        link(body, "차량 별칭 수정", this::editVehicleAlias);
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
        boolean batteryAllowed = checkSelfPermission("android.car.permission.CAR_ENERGY") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        detail(body, "배터리 읽기 권한", batteryAllowed ? "허용됨" : "권한 필요");
        if (!batteryAllowed) link(body, "배터리 읽기 권한 허용", () -> {
            requestServicePermission("android.car.permission.CAR_ENERGY", 102);
        });
        detail(body, "거리", featureStatus("distance"));
        detail(body, "위치", featureStatus("location"));
        boolean locationAllowed = checkSelfPermission(com.pleos.fuelog.location.PleosLocationClient.PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        detail(body, "위치 읽기 권한", locationAllowed ? "허용됨" : "권한 필요");
        if (!locationAllowed) link(body, "위치 읽기 권한 허용", () -> {
            requestServicePermission(com.pleos.fuelog.location.PleosLocationClient.PERMISSION, 105);
        });
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 106) connection.recheckVin(() -> { if (started && allowed) render(); });
        if (requestCode == 105) { startupPermissionsInFlight = false; if (started && allowed) render(); }
        if (requestCode == 102 && started && allowed) render();
        if (requestCode == 104 && started && allowed) { fuelSyncMessage = "위치 권한 설정 후 주유소 조회를 다시 눌러 주세요"; render(); }
        if (requestCode == 103) {
            String tripId=costPermissionTripId;costPermissionTripId="";
            if(started && allowed && grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) lookupTripPrices(tripId);
        }
    }

    private void editVehicleAlias() {
        if (!started || !allowed || !settings.pending().isEmpty()) return;
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(connection.vehicleAlias);
        input.setHint("차량 별칭 · 최대 100자");
        input.setContentDescription("차량 별칭");
        input.setSingleLine(true);
        input.setTextSize(26);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("차량 별칭 수정")
                .setView(input).setNegativeButton("취소", null).setPositiveButton("저장", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!started || !allowed || !settings.pending().isEmpty()) { dialog.dismiss(); return; }
            try {
                if (!connection.vehicleId.isEmpty()) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    connection.renameVehicleAlias(input.getText().toString(), new ApiClient.ResultCallback() {
                        public void onSuccess(ApiClient.ApiResponse response) { dialog.dismiss(); if (started && allowed) render(); }
                        public void onHttpError(ApiClient.ApiResponse response) { fail("서버 저장 실패 · 오너 연결과 권한을 확인해 주세요 (HTTP " + response.getStatusCode() + ")"); }
                        public void onFailure(java.io.IOException failure) { fail("서버 저장 확인 실패 · 연결 상태를 확인하고 다시 시도해 주세요"); }
                        private void fail(String message) { if (dialog.isShowing()) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); input.setError(message); } }
                    });
                    return;
                }
                connection.setVehicleAlias(input.getText().toString());
                if (!connection.linked) connection.registrationReset();
                dialog.dismiss();
                if (!connection.linked) connection.retry(() -> { if (started && allowed) render(); });
                render();
            } catch (Exception invalid) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); input.setError("차량 별칭 저장 실패 · 1~100자로 입력하고 다시 시도해 주세요"); }
        }));
        dialog.show();
    }

    private void settingsChanged() {
        recorder.configureStationSearch(settings.snapshot.optBoolean("pleosCoordinateSystemVerified"),
                settings.snapshot.optString("fuelType"));
        if (started && allowed && page == SETTINGS) {
            if (settings.pending().isEmpty() && !connection.linked) {
                startTab = 0;
                navigate(CONNECT);
            } else render();
        }
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
        closeTripSplit();
        started = false;
        closeConsentAlert();
        closeMaintenanceEditor();
        closeVehicleOdometer();
        connection.apiStatus().changed = () -> {};
        if (apiAlert != null) { apiAlert.dismiss(); apiAlert = null; }
        connection.onPersonalInvalid = recorder::clearPersonalCache;
        cancelFuelSync();
        cancelMaintenance();
        if (liveRecorderStarted) recorder.detachScreen();
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

    private String consentOwner = "";

    private void requestServicePermission(String permission, int requestCode) {
        if (!started || !allowed || checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                || consentAlert != null || startupPermissionsInFlight) return;
        consentOwner = recorder.verifiedOwner();
        boolean battery = permission.equals("android.car.permission.CAR_ENERGY");
        boolean vin = permission.equals("pleos.car.permission.CAR_INFO");
        consentAlert = new AlertDialog.Builder(this).setTitle(vin ? "VIN 읽기 권한" : battery ? "배터리 읽기 권한" : "위치 읽기 권한")
                .setMessage((vin ? "같은 차량을 식별하고 기존 차량 UUID를 재사용하려면 VIN 읽기 권한이 필요합니다." : battery ? "배터리 잔량과 사용량을 기록하려면 배터리 읽기 권한이 필요합니다."
                        : "현재 차량 위치로 주변 주유소·충전소를 조회하려면 위치 권한이 필요합니다.")
                        + "\n\n권한 요청이 표시되지 않으면 앱 권한 설정에서 허용해 주세요.")
                .setPositiveButton("권한 허용", (dialog, which) -> {
                    if (!started || !allowed) return;
                    getSharedPreferences("startup_consent", MODE_PRIVATE).edit().putBoolean(permission, true).apply();
                    requestPermissions(new String[]{permission}, requestCode);
                })
                .setNeutralButton("앱 권한 설정", (dialog, which) -> {
                    if (started && allowed) startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                }).setNegativeButton("취소", null).create();
        consentAlert.setOnDismissListener(dialog -> consentAlert = null);
        consentAlert.show();
    }

    private void closeConsentAlert() {
        if (consentAlert != null) { consentAlert.dismiss(); consentAlert = null; }
    }

    private void showStartupConsent() {
        if (!started || !allowed || !connection.linked || isFinishing() || isDestroyed() || consentAlert != null
                || startupPermissionsInFlight || !settings.pending().isEmpty()) return;
        android.content.SharedPreferences preferences = getSharedPreferences("startup_consent", MODE_PRIVATE);
        consentOwner = recorder.verifiedOwner();
        String[] permissions = {com.pleos.fuelog.location.PleosLocationClient.PERMISSION, "android.car.permission.CAR_ENERGY"};
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !preferences.getBoolean(permission, false)) missing.add(permission);
        }
        if (!missing.isEmpty()) {
            consentAlert = new AlertDialog.Builder(this).setTitle("선택 접근 권한 안내")
                    .setMessage("위치: 주변 주유소·충전소 검색에 사용합니다.\n배터리 읽기: 차량 배터리 잔량과 사용량 기록에 필요합니다.\n\n허용하지 않아도 앱을 사용할 수 있으며 해당 기능만 제한됩니다. 설정에서 나중에 허용할 수 있습니다.")
                    .setPositiveButton("권한 확인", (dialog, which) -> {
                        if (!started || !allowed) return;
                        android.content.SharedPreferences.Editor editor = preferences.edit();
                        for (String permission : missing) editor.putBoolean(permission, true);
                        if (!editor.commit()) { paragraph(body, "권한 안내 저장 실패 · 다시 시도해 주세요"); return; }
                        startupPermissionsInFlight = true;
                        requestPermissions(missing.toArray(new String[0]), 105);
                    }).setNegativeButton("지금은 사용 안 함", (dialog, which) -> {
                        android.content.SharedPreferences.Editor editor = preferences.edit();
                        for (String permission : missing) editor.putBoolean(permission, true);
                        if (!editor.commit()) paragraph(body, "권한 안내 저장 실패 · 다시 시도해 주세요");
                    }).create();
        } else if (!consentOwner.isEmpty() && !recorder.locationAssistDecisionMade()) {
            final String owner = consentOwner;
            consentAlert = new AlertDialog.Builder(this).setTitle("차량 위치 보조 동의")
                    .setMessage("연료·배터리 증가 시 차량 위치로 주변 주유소·충전소 후보를 조회하고 기록에 연결합니다. 조회에 필요한 위치를 서버로 전송합니다.\n\n선택 동의이며, 거절해도 기본 기록을 사용할 수 있습니다. 연결·설정에서 언제든 변경할 수 있습니다.")
                    .setPositiveButton("동의", (dialog, which) -> saveLocationAssistDecision(owner, true))
                    .setNegativeButton("동의 안 함", (dialog, which) -> saveLocationAssistDecision(owner, false)).create();
        } else return;
        if (apiAlert != null) { apiAlert.dismiss(); apiAlert = null; }
        consentAlert.setCanceledOnTouchOutside(false);
        consentAlert.setCancelable(false);
        consentAlert.setOnDismissListener(dialog -> { consentAlert = null; body.post(this::showStartupConsent); });
        consentAlert.show();
    }

    private void saveLocationAssistDecision(String owner, boolean consent) {
        if (!started || !allowed || !owner.equals(recorder.verifiedOwner())) return;
        try { recorder.setLocationAssistConsent(consent); }
        catch (IOException error) { paragraph(body, "위치 동의 저장 실패 · 다시 시도해 주세요"); }
    }

    private void connectionRequired(String message) {
        if (!started || !allowed || isFinishing() || isDestroyed() || apiAlert != null || consentAlert != null) return;
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
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(shape(R.color.car_panel, 28, R.color.car_line));
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
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private void heading(LinearLayout parent, String value) { addText(parent, value, 42, true, false, 0, 0, 0, 24); }

    private void subheading(LinearLayout parent, String value) { addText(parent, value, 30, true, false, 0, 0, 0, 14); }

    private void paragraph(LinearLayout parent, String value) { addText(parent, value, 24, false, true, 0, 6, 0, 16); }

    private void tag(LinearLayout parent, String value) {
        TextView badge = label(value, 18, true, false);
        badge.setTextColor(color(R.color.car_on_dark));
        badge.setPadding(dp(12), dp(8), dp(12), dp(8));
        badge.setBackground(shape(R.color.car_dark, 24, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, 0, dp(10));
        parent.addView(badge, params);
    }

    private void sectionLabel(LinearLayout parent, String value) {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, dp(24));
        parent.addView(new View(this), space);
        divider(parent);
        addText(parent, value, 26, true, false, 0, 20, 0, 20);
    }

    private void number(LinearLayout parent, String amount, String unit) {
        TextView value = label(amount + unit, 72, true, false);
        SpannableString styled = new SpannableString(amount + unit);
        styled.setSpan(new RelativeSizeSpan(0.38f), amount.length(), styled.length(), 0);
        value.setText(styled);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(16), 0, dp(18));
        parent.addView(value, params);
    }

    private void detail(LinearLayout parent, String key, String value) {
        LinearLayout line = column();
        line.setPadding(dp(16), dp(12), dp(16), dp(12));
        line.setBackground(shape(R.color.car_soft, 20, 0));
        parent.addView(line, cardParams());
        TextView name = label(key, 22, false, true);
        line.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView reading = label(value, 32, true, false);
        reading.setPadding(0, dp(8), 0, 0);
        line.addView(reading, new LinearLayout.LayoutParams(-1, -2));
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
        view.setTextSize(Math.max(22, sp));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(8));
        return params;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(24);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setMinimumWidth(0);
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
        button.setTextSize(22);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setMinimumWidth(0);
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
