package com.pleos.fuelog;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.pleos.fuelog.diagnostics.AaosVehicleDiagnosticService;

/** Parked-only product screen preview. No example value is a vehicle reading. */
public final class CarHomeActivity extends AppCompatActivity {
    private static final int HOME = 0, USER = 1, TRIP = 2, FUEL = 3;
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
    private com.pleos.fuelog.connection.ConnectionSession connection;
    private final android.os.Handler connectionHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean started;
    private String mockUser = "김드라이버";
    private boolean mockRecording, mockMobileQueued, mockConnected = true;
    private boolean liveRecorderStarted;
    private String previewMessage = "서버 응답 확인 전 · 화면 확인용 데이터";
    private boolean preview() { return connection.isPreview(); }
    private void startLiveRecorder() {
        if (liveRecorderStarted || preview()) return;
        liveRecorderStarted = true;
        recorder.resume(() -> { if (started && allowed && (page == HOME || page == FUEL)) render(); });
    }
    private void checkPreviewServer() {
        if (!preview() || !online() || !connection.hasServer()) {
            previewMessage = "서버 URL 또는 네트워크 미확인 · 목업 유지";
            render(); return;
        }
        previewMessage = "서버 응답 확인 중 · 목업 유지";
        connection.retry(() -> {
            if (!started || isDestroyed()) return;
            if (!preview()) {
                mockUser = ""; mockMobileQueued = false;
                tripUser.cancel(); cancelFuelSync(); cancelMaintenance();
                page = !settings.pending().isEmpty() ? SETTINGS : connection.linked ? USER : CONNECT;
                startLiveRecorder();
            } else previewMessage = "유효한 서버 응답 미확인 · 목업 유지";
            render();
        });
        render();
    }
    private com.pleos.fuelog.connection.ConnectionSettings settings;
    private boolean offerTripSummary;
    private boolean fuelSyncBusy;
    private int fuelSyncGeneration;
    private okhttp3.Call fuelSyncCall;
    private String fuelSyncMessage = "";
    private com.pleos.fuelog.home.MaintenanceSummary maintenanceSummary;
    private okhttp3.Call maintenanceCall;
    private int maintenanceGeneration;
    private String maintenanceMessage = "정비 정보를 확인 중입니다";
    private com.pleos.fuelog.home.TripUserSession tripUser;
    private String userOperation = java.util.UUID.randomUUID().toString();
    private com.pleos.fuelog.home.HomeRecorder recorder;
    private final Runnable connectionTick = new Runnable() {
        public void run() {
            if (!started) return;
            if (!preview() && allowed && page == CONNECT) {
                connection.refresh(online(), () -> {
                    if (!started || !allowed) return;
                    if (connection.linked) page = connection.state.equals("ALREADY_LINKED") ? SETTINGS : USER;
                    render();
                });
                if (connection.linked) page = connection.state.equals("ALREADY_LINKED") ? SETTINGS : USER;
                render();
            }
            if (!preview() && allowed && page == USER && !tripUser.busy) render();
            connectionHandler.postDelayed(this, 1000);
        }
    };
    private boolean online() {
        android.net.ConnectivityManager manager = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (state != null) page = state.getInt("page", HOME);
        connection = com.pleos.fuelog.connection.ConnectionSession.get(this);
        if (preview()) page = state == null ? HOME : page;
        else if (!connection.linked) page = CONNECT;
        tripUser = new com.pleos.fuelog.home.TripUserSession(connection);
        recorder = new com.pleos.fuelog.home.HomeRecorder(this);
        settings = new com.pleos.fuelog.connection.ConnectionSettings(this, connection, recorder);
        if (!preview() && !settings.pending().isEmpty()) {
            try { recorder.lockPersonalSession(); } catch (Exception ignored) { }
            page = SETTINGS;
        }
        if (state != null) {
            mockUser = state.getString("mockUser", "김드라이버");
            mockRecording = state.getBoolean("mockRecording");
            mockConnected = state.getBoolean("mockConnected", true);
            mockMobileQueued = state.getBoolean("mockMobileQueued");
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
        header.setPadding(dp(compact ? 20 : 36), dp(18), dp(compact ? 20 : 32), dp(18));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout top = compact ? row() : header;
        if (compact) header.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView brand = label("fuelog", 28, true, false);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        status = label("정차 상태 확인 중", 18, true, false);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackground(shape(R.color.car_soft, 4, 0));
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
        offerTripSummary = !recorder.trip.active && recorder.summary() != null;
        startLiveRecorder();
        if (preview() && connection.hasServer()) checkPreviewServer();
        connectionHandler.post(connectionTick);
        allowed = false;
        render();
        restrictions = new AaosVehicleDiagnosticService(this);
        restrictions.start((canUse, detail) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean wasAllowed = allowed;
            allowed = canUse;
            if (!canUse) { tripUser.cancel(); cancelFuelSync(); cancelMaintenance(); }
            if (!preview() && (!canUse || !wasAllowed)) page = !settings.pending().isEmpty() ? SETTINGS : connection.linked ? HOME : CONNECT;
            status.setText(canUse ? "우리 차 · 정차 상태" : "주행 제한");
            status.setBackground(shape(canUse ? R.color.car_accent_light : R.color.car_soft, 4, 0));
            render();
        }));
    }

    @Override protected void onStop() {
        started = false;
        cancelFuelSync();
        cancelMaintenance();
        if (liveRecorderStarted) recorder.pause();
        liveRecorderStarted = false;
        tripUser.cancel();
        connectionHandler.removeCallbacks(connectionTick);
        allowed = false;
        if (restrictions != null) { restrictions.close(); restrictions = null; }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("mockUser", mockUser);
        out.putBoolean("mockRecording", mockRecording);
        out.putBoolean("mockConnected", mockConnected);
        out.putBoolean("mockMobileQueued", mockMobileQueued);
        out.putInt("page", allowed ? page : HOME);
        super.onSaveInstanceState(out);
    }

    private void navigate(int destination) {
        if (!allowed) return;
        if (preview()) { page = destination; render(); return; }
        if (!settings.pending().isEmpty()) destination = SETTINGS;
        if (destination != FUEL) cancelFuelSync();
        if (destination != MAINTENANCE) cancelMaintenance();
        if (destination != USER) tripUser.cancel();
        page = !connection.linked && destination != SETTINGS ? CONNECT : destination;
        if (page == MAINTENANCE) {
            maintenanceSummary = null;
            maintenanceMessage = "정비 정보를 확인 중입니다";
        }
        render();
        if (page == USER) loadUserCandidates();
        if (page == MAINTENANCE) loadMaintenance();
    }

    private void render() {
        if (body == null) return;
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
        if (preview()) {
            tag(body, "목업 미리보기 · 실제 연결·기록 아님");
            renderPreview();
        } else switch (page) {
            case USER: renderUser(); break;
            case TRIP: renderTrip(); break;
            case FUEL: renderFuel(); break;
            case MAINTENANCE: renderMaintenance(); break;
            case SETTINGS: renderSettings(); break;
            case CONNECT: renderConnect(); break;
            default: renderHome();
        }
        if (!preview() && !connection.linked) return;
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

    private void mockMobile() {
        mockMobileQueued = true;
        render();
    }
    private void renderPreview() {
        switch (page) {
            case CONNECT:
                heading(body, "휴대폰으로 차량을 연결하세요");
                LinearLayout qr = section(body);
                subheading(qr, "차량 연결 QR · 목업");
                try {
                    com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(
                            "{\"type\":\"fuelog.preview\",\"mock\":true}", com.google.zxing.BarcodeFormat.QR_CODE, 240, 240);
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888);
                    for (int y = 0; y < 240; y++) for (int x = 0; x < 240; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
                    android.widget.ImageView image = new android.widget.ImageView(this);
                    image.setImageBitmap(bitmap); image.setContentDescription("연결 기능이 없는 목업 QR");
                    qr.addView(image, new LinearLayout.LayoutParams(dp(240), dp(240)));
                } catch (Exception ignored) { }
                paragraph(qr, "남은 시간 120초 · 연결 대기 예시");
                paragraph(body, "1. 모바일 앱 열기  2. QR 스캔  3. 차량 확인");
                primary(body, "목업 연결 완료", () -> { mockConnected = true; navigate(USER); });
                break;
            case USER:
                heading(body, "이번 운행은 누가 기록하나요?");
                paragraph(body, "인증된 휴대폰 후보 · 목업");
                for (String name : new String[]{"김드라이버", "이사용자"}) {
                    primary(body, (name.equals(mockUser) ? "✓ " : "") + name + " · 차량 멤버", () -> { mockUser = name; render(); });
                }
                paragraph(body, "확인 만료까지 120초 · 예시");
                Button confirm = button("이번 운행에 사용", true, () -> navigate(HOME));
                confirm.setEnabled(!mockUser.isEmpty()); body.addView(confirm, fullButton());
                link(body, "휴대폰으로 연결", () -> navigate(CONNECT));
                break;
            case TRIP:
                heading(body, "이번 운행");
                paragraph(body, "오늘 10:00 → 11:05 · 앱 기록 구간 예시");
                number(body, "60.0", " km");
                detail(body, "추정 소모량", "4.0 L");
                detail(body, "추정 연비", "15.0 km/L");
                detail(body, "추정 연료비", "6,800원");
                paragraph(body, "단가 1,700원/L · 사용자 확인 단가 예시 · 오늘 09:00 기준");
                paragraph(body, "기록 품질 · 누락 없음 / 로컬 저장 · 모두 예시");
                primary(body, mockMobileQueued ? "목업 전달 요청됨" : "모바일에서 상세 확인", this::mockMobile);
                break;
            case FUEL:
                heading(body, "주유 기록 확인이 필요해요");
                if (mockUser.isEmpty()) { paragraph(body, "이번 운행 사용자를 먼저 확인해 주세요"); link(body, "사용자 확인", () -> navigate(USER)); break; }
                subheading(body, "주유소 후보 2곳 · 미확정");
                paragraph(body, "감지 구간 · 오늘 10:40~10:45 / 사용자 확인 전");
                detail(body, "추정 주유량", "20.0 L"); detail(body, "추정 금액", "34,000원");
                paragraph(body, "단가 1,700원/L · 오늘 10:30 기준 예시");
                paragraph(body, "잔량 증가 감지이며 주유·결제 완료를 뜻하지 않습니다.");
                primary(body, mockMobileQueued ? "목업 전달 요청됨" : "모바일에서 확인", this::mockMobile);
                break;
            case MAINTENANCE:
                heading(body, "차량 관리");
                detail(body, "최근 수신 누적거리", "50,800 km");
                for (String item : new String[]{"엔진오일", "타이어 점검"}) {
                    LinearLayout service = section(body); subheading(service, item);
                    paragraph(service, "다음 정비 52,000 km · 남은 거리 1,200 km");
                    paragraph(service, "사용자 설정 · 정비 주기 예시");
                }
                primary(body, "모바일에서 상세 확인", this::mockMobile);
                break;
            case SETTINGS:
                heading(body, "연결·설정");
                detail(body, "휴대폰 연결", mockConnected ? "연결됨 · 목업 휴대폰" : "미연결 · 목업");
                detail(body, "현재 사용자", mockUser.isEmpty() ? "미확정" : mockUser);
                detail(body, "기록·동기화", "로컬 저장 · 서버 동기화 대기 예시");
                detail(body, "연료 / 거리 / 위치", "제공 중 / 제공 중 / 권한 필요 · 예시");
                link(body, "이번 사용자 연결 해제", () -> { mockUser = ""; mockMobileQueued = false; render(); });
                link(body, "차량 등록 초기화", () -> { mockConnected = false; mockUser = ""; mockMobileQueued = false; navigate(CONNECT); });
                link(body, "차량 연결 화면 확인", () -> navigate(CONNECT));
                paragraph(body, previewMessage);
                primary(body, "설정된 서버 응답 확인", this::checkPreviewServer);
                break;
            default:
                tag(body, "우리 차 · 목업 차량");
                heading(body, mockUser.isEmpty() ? "이번 운행 사용자를 확인하세요" : mockUser + "님의 이번 운행");
                link(body, "사용자 확인", () -> navigate(USER));
                LinearLayout layout = grid(body);
                LinearLayout trip = card(layout);
                tag(trip, mockRecording ? "기록 중 · 예시" : "기록 준비 · 예시");
                number(trip, "60.0", " km");
                paragraph(trip, mockUser.isEmpty() ? "추정 비용 · 사용자 확인 필요" : "추정 비용 6,800원");
                primary(trip, mockRecording ? "기록 종료" : "기록 시작", () -> { mockRecording = !mockRecording; render(); });
                link(trip, "이번 운행 요약", () -> navigate(TRIP));
                LinearLayout alerts = section(layout);
                subheading(alerts, "확인할 항목");
                link(alerts, "주유 확인 1건", () -> navigate(FUEL));
                link(alerts, "정비 예정 2건", () -> navigate(MAINTENANCE));
                paragraph(body, "최근 수신·로컬 저장 오늘 11:05 · 예시");
                link(body, "연결·설정", () -> navigate(SETTINGS));
        }
    }

    private String timeLabel(long timestamp) {
        return timestamp == 0 ? "아직 없음" : new java.text.SimpleDateFormat("MM.dd HH:mm:ss", java.util.Locale.KOREA)
                .format(new java.util.Date(timestamp));
    }

    private void renderHome() {
        if (offerTripSummary) {
            link(body, "저장된 운행 요약 확인", () -> { offerTripSummary = false; navigate(TRIP); });
        }
        com.pleos.fuelog.home.TripState trip = recorder.trip;
        tag(body, "연결된 차량 · 별칭 미설정");
        heading(body, recorder.owner.isEmpty() ? "이번 운행 사용자를 확인하세요" : recorder.displayUser());
        paragraph(body, "현재 사용자 · " + recorder.displayUser());
        link(body, "사용자 확인", () -> navigate(USER));
        LinearLayout layout = grid(body);
        LinearLayout current = card(layout);
        tag(current, connection.linked ? trip.status(System.currentTimeMillis()) : "연결 필요");
        subheading(current, "이번 운행 · 앱 기록 구간");
        if (trip.sampledAt == 0) paragraph(current, "거리 수신 대기");
        else number(current, String.format(java.util.Locale.KOREA, "%.1f", trip.distance), " km");
        paragraph(current, "마지막 샘플 · 최근 수신 " + timeLabel(trip.sampledAt));
        paragraph(current, "추정 비용 · 사용자 확인 후 제공");
        paragraph(current, "연료 데이터 미연결 · 거리 기록은 별도로 제공됩니다");
        link(current, "이번 운행 요약", () -> navigate(TRIP));
        primary(current, trip.active ? "기록 종료" : "기록 시작", () -> {
            if (allowed && connection.linked) recorder.toggle();
        });
        paragraph(current, "직접 시작·종료한 앱 기록 구간입니다. 화면을 벗어나면 수집이 중단되어 일부 누락으로 표시됩니다.");
        LinearLayout alerts = section(layout);
        subheading(alerts, "확인할 항목");
        paragraph(alerts, "필요한 알림 · 사용자 확인 후 조회");
        link(alerts, "주유 확인", () -> navigate(FUEL));
        link(alerts, "정비 예정", () -> navigate(MAINTENANCE));
        paragraph(body, (online() ? "서버 동기화 대기" : "오프라인 · 로컬 기록")
                + " · 로컬 저장 " + timeLabel(trip.savedAt));
        if (!compact()) {
            LinearLayout footer = grid(body);
            link(section(footer), "운행 요약", () -> navigate(TRIP));
            link(section(footer), "연결 상태", () -> navigate(SETTINGS));
        } else link(body, "운행 요약 및 상세", () -> navigate(TRIP));
    }

    private void renderUser() {
        heading(body, "이번 운행은 누가 기록하나요?");
        paragraph(body, tripUser.message);
        if (!online()) paragraph(body, "오프라인 · 사용자 전환을 보류합니다");
        for (com.pleos.fuelog.home.TripUserSession.Candidate candidate : tripUser.candidates) {
            Button choice = button((candidate.id.equals(tripUser.selected) ? "✓ " : "") + candidate.name
                    + " · 차량 멤버", false, () -> {
                if (!allowed || tripUser.busy || !candidate.valid()) return;
                tripUser.selected = candidate.id;
                userOperation = java.util.UUID.randomUUID().toString(); render();
            });
            choice.setEnabled(candidate.valid() && !tripUser.busy && online());
            body.addView(choice, fullButton());
            paragraph(body, "확인 만료까지 " + Math.max(0, (candidate.deadline - android.os.SystemClock.elapsedRealtime()) / 1000) + "초");
        }
        Button confirm = button(tripUser.busy ? "확인 중" : "이번 운행에 사용", true, () -> {
            if (!allowed || !online()) return;
            if (recorder.trip.active) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("현재 구간을 종료하고 사용자를 확인할까요?")
                        .setMessage("기존 기록 소유자는 유지됩니다. 종료 후 새 구간에 대한 모바일 동의가 필요합니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("구간 종료 및 새 구간 준비", (dialog, which) -> {
                            if (!allowed || !started || !online()) return;
                            try { recorder.endForSwitch(); loadUserCandidates(); }
                            catch (Exception e) { tripUser.message = "기록 저장 실패 · 사용자 전환 보류"; render(); }
                        }).show();
            } else {
                tripUser.confirm(recorder.intervalId, userOperation, (subject, name, expiry) -> {
                    if (!allowed || !started || !online() || page != USER) throw new IllegalStateException();
                    recorder.assignUser(subject, name, expiry);
                    page = HOME;
                }, () -> { if (started && allowed) render(); });
                render();
            }
        });
        confirm.setEnabled(online() && !tripUser.busy && tripUser.selection() != null);
        body.addView(confirm, fullButton());
        link(body, "인증된 후보 새로 확인", this::loadUserCandidates);
        link(body, "휴대폰으로 연결", () -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("휴대폰으로 연결")
                .setMessage("휴대폰 fuelog 앱에서 연결된 차량을 열고 이번 운행 사용에 동의해 주세요.")
                .setPositiveButton("후보 확인", (dialog, which) -> loadUserCandidates()).show());
    }

    private void loadUserCandidates() {
        if (!allowed || !started || !online() || tripUser.busy) return;
        tripUser.load(recorder.intervalId, () -> { if (started && allowed && page == USER) render(); });
        render();
    }

    private String metric(double value, String unit) {
        return Double.isFinite(value) ? String.format(java.util.Locale.KOREA, "%,.1f%s", value, unit) : "계산 불가";
    }
    private void renderTrip() {
        heading(body, "이번 운행");
        com.pleos.fuelog.home.TripSummary summary = recorder.summary();
        if (summary == null) {
            paragraph(body, "확인할 수 있는 운행 기록이 없습니다. 사용자를 확인하거나 기록을 시작해 주세요.");
            link(body, "사용자 확인", () -> navigate(USER)); return;
        }
        org.json.JSONObject data = summary.data;
        String id = data.optString("intervalId");
        long end = data.optLong("endedAt");
        paragraph(body, "앱 기록 구간 · " + timeLabel(data.optLong("startedAt")) + " → "
                + (end == 0 ? "기록 진행 중" : timeLabel(end)) + (data.optBoolean("endEstimated") ? " · 종료 시각 추정" : ""));
        LinearLayout distance = card(body);
        subheading(distance, "거리");
        paragraph(distance, data.optBoolean("distanceValid") ? metric(data.optDouble("distanceKm"), " km") : "유효한 거리 샘플 부족");
        detail(body, "추정 소모량", metric(summary.liters(), " L"));
        detail(body, "추정 연비", metric(summary.economy(), " km/L"));
        detail(body, "추정 연료비", metric(summary.fuelCost(), "원"));
        paragraph(body, "단가 출처 · " + data.optString("priceSource", "유효한 단가 없음"));
        paragraph(body, "양 끝 연료 샘플과 단가가 유효한 항목만 계산합니다.");
        sectionLabel(body, "기록 품질");
        paragraph(body, data.optBoolean("missing") ? "일부 누락 · 수신한 구간의 거리만 표시" : "감지된 누락 없음");
        paragraph(body, "최근 수신 " + timeLabel(data.optLong("sampledAt")));
        paragraph(body, (data.optBoolean("synced") ? "서버 동기화 완료" : "로컬 저장 · 서버 동기화 대기")
                + " · " + timeLabel(data.optLong("savedAt")));
        if (!recorder.storageError.isEmpty()) paragraph(body, recorder.storageError);
        Button mobile = button(recorder.mobileQueued(id) ? "모바일 전달 대기 중" : "모바일에서 상세 확인", true, () -> {
            try { recorder.enqueueMobile(id); render(); }
            catch (Exception e) { paragraph(body, "모바일 전달 요청을 저장하지 못했습니다. 다시 시도해 주세요."); }
        });
        mobile.setEnabled(end != 0 && !recorder.mobileQueued(id));
        body.addView(mobile, fullButton());
        paragraph(body, "전달 요청을 기기에 저장합니다. 서버 연동 후 모바일 확인함에 반영되며 휴대폰 앱이 자동 실행되지는 않습니다.");
    }

    private void cancelFuelSync() {
        fuelSyncGeneration++; fuelSyncBusy = false;
        if (fuelSyncCall != null) fuelSyncCall.cancel();
        fuelSyncMessage = "";
    }
    private void syncFuel(org.json.JSONObject candidate) {
        if (!allowed || !online() || fuelSyncBusy || recorder.verifiedOwner().isEmpty()) return;
        final String subject = recorder.verifiedOwner(), id = candidate.optString("id");
        final int version = ++fuelSyncGeneration;
        fuelSyncBusy = true;
        try {
            fuelSyncCall = connection.requestUser("fuel-candidates/sync", candidate.toString(), new com.pleos.fuelog.api.ApiClient.ResultCallback() {
                public void onSuccess(com.pleos.fuelog.api.ApiClient.ApiResponse result) {
                    runOnUiThread(() -> {
                        if (version != fuelSyncGeneration || !started || !allowed || page != FUEL
                                || !subject.equals(recorder.verifiedOwner())) return;
                        fuelSyncBusy = false;
                        try { recorder.updateFuel(new org.json.JSONObject(result.getBody()), id, subject);
                            fuelSyncMessage = "모바일 확인 상태를 갱신했습니다"; }
                        catch (Exception e) { fuelSyncMessage = "상태를 확인하지 못했습니다. 기존 기록을 유지합니다"; }
                        render();
                    });
                }
                public void onHttpError(com.pleos.fuelog.api.ApiClient.ApiResponse result) { fail(); }
                public void onFailure(java.io.IOException error) { fail(); }
                private void fail() { runOnUiThread(() -> {
                    if (version != fuelSyncGeneration || !started || !allowed) return;
                    fuelSyncBusy = false; fuelSyncMessage = "서버 연결 실패 · 마지막 저장 상태를 표시합니다"; render();
                }); }
            });
        } catch (Exception e) { fuelSyncBusy = false; fuelSyncMessage = "서버 설정을 확인해 주세요"; }
        render();
    }
    private void renderFuel() {
        heading(body, "주유 기록 확인이 필요해요");
        if (recorder.verifiedOwner().isEmpty()) {
            paragraph(body, "계정 귀속을 확인할 수 없습니다. 이번 운행 사용자를 먼저 확인해 주세요.");
            link(body, "사용자 확인", () -> navigate(USER)); return;
        }
        org.json.JSONObject candidate = recorder.fuelCandidate();
        if (candidate == null) {
            paragraph(body, "현재 사용자에게 표시할 연료 증가 감지 기록이 없습니다.");
            paragraph(body, "연료 정보를 수신할 수 있는 동안 증가 여부를 확인합니다."); return;
        }
        String id = candidate.optString("id"), state = candidate.optString("status");
        tag(body, state.equals("CONFIRMED") ? "모바일 확인됨" : state.equals("REJECTED") ? "모바일에서 기각됨" : "후보 · 사용자 확인 전");
        org.json.JSONArray stations = candidate.optJSONArray("stationCandidates");
        subheading(body, stations == null || stations.length() == 0 ? "주유소 미확인" : stations.length() > 1
                ? "주유소 후보 " + stations.length() + "곳 · 미확정" : "주유소 후보 · " + stations.optString(0));
        paragraph(body, "감지 구간 " + timeLabel(candidate.optLong("startAt")) + " → " + timeLabel(candidate.optLong("endAt")));
        boolean capacity = candidate.optBoolean("capacityVerified");
        double liters = candidate.optDouble("estimatedLiters", Double.NaN);
        if (capacity && Double.isFinite(liters) && liters > 0) {
            detail(body, "추정 주유량", metric(liters, " L"));
            double amount = candidate.optDouble("estimatedAmount", Double.NaN);
            detail(body, "추정 금액", Double.isFinite(amount) && amount >= 0 && candidate.optLong("priceAt") > 0
                    ? metric(amount, "원") : "단가 확인 필요");
            paragraph(body, "단가 기준 " + timeLabel(candidate.optLong("priceAt")));
        } else paragraph(body, "잔량 증가 감지 · 연료 용량 미확인");
        paragraph(body, "연료 증가는 주유 완료나 결제 완료를 의미하지 않습니다. 장소·양·금액은 모바일에서 확인해 주세요.");
        paragraph(body, "마지막 서버 확인 " + timeLabel(candidate.optLong("checkedAt")));
        if (!online()) paragraph(body, "오프라인 · 로컬에 저장된 상태입니다");
        if (!fuelSyncMessage.isEmpty()) paragraph(body, fuelSyncMessage);
        Button mobile = button(candidate.optBoolean("queued") ? "모바일 전달 요청 저장됨" : "모바일에서 확인", true, () -> {
            if (!allowed) return;
            try { recorder.queueFuel(id); syncFuel(recorder.fuelCandidate()); render(); }
            catch (Exception e) { paragraph(body, "전달 요청 저장 실패 · 다시 시도해 주세요"); }
        });
        mobile.setEnabled(state.equals("CANDIDATE") && !candidate.optBoolean("queued"));
        body.addView(mobile, fullButton());
        Button refresh = button(fuelSyncBusy ? "확인 중" : "모바일 상태 새로 확인", false, () -> syncFuel(candidate));
        refresh.setEnabled(online() && !fuelSyncBusy);
        body.addView(refresh, fullButton());
        paragraph(body, "전달 요청은 서버 연결 후 모바일 확인함에 반영됩니다. 휴대폰 앱이 자동 실행되지는 않습니다.");
    }

    private void cancelMaintenance() {
        maintenanceGeneration++;
        if (maintenanceCall != null) maintenanceCall.cancel();
        maintenanceCall = null;
    }

    private void loadMaintenance() {
        cancelMaintenance();
        if (!started || !allowed || page != MAINTENANCE) return;
        maintenanceSummary = null;
        if (!online()) {
            maintenanceMessage = "오프라인 · 정비 정보를 확인할 수 없습니다";
            render(); return;
        }
        maintenanceMessage = "정비 정보를 확인 중입니다";
        render();
        final int generation = maintenanceGeneration;
        try {
            maintenanceCall = connection.readVehicle("maintenance/summary", new com.pleos.fuelog.api.ApiClient.ResultCallback() {
                public void onSuccess(com.pleos.fuelog.api.ApiClient.ApiResponse result) {
                    runOnUiThread(() -> {
                        if (!maintenanceVisible(generation)) return;
                        maintenanceCall = null;
                        try {
                            maintenanceSummary = new com.pleos.fuelog.home.MaintenanceSummary(
                                    new org.json.JSONObject(result.getBody()));
                            maintenanceMessage = "";
                        } catch (Exception error) {
                            maintenanceSummary = null;
                            maintenanceMessage = "정비 응답을 확인하지 못했습니다";
                        }
                        render();
                    });
                }
                public void onHttpError(com.pleos.fuelog.api.ApiClient.ApiResponse result) { fail(); }
                public void onFailure(java.io.IOException error) { fail(); }
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

    private boolean maintenanceVisible(int generation) {
        return generation == maintenanceGeneration && started && allowed
                && page == MAINTENANCE && connection.linked && !isFinishing() && !isDestroyed();
    }

    private String maintenanceDate(java.time.LocalDate date) {
        return date == null ? "기록 없음" : date.toString();
    }

    private String maintenanceDistance(Double km) {
        return km == null ? "기록 없음" : String.format(java.util.Locale.KOREA, "%,.0f km", km);
    }

    private void renderMaintenance() {
        heading(body, "차량 관리");
        com.pleos.fuelog.home.MaintenanceSummary summary = maintenanceSummary;
        if (summary == null) {
            paragraph(body, maintenanceMessage);
        } else {
            detail(body, "마지막 유효 누적거리", maintenanceDistance(summary.odometerKm));
            paragraph(body, "거리 갱신 · " + timeLabel(summary.odometerKm == null ? 0 : summary.odometerObservedAt));
            long now = System.currentTimeMillis();
            if (!summary.hasRecentOdometer(now)) paragraph(body, "최신 거리 확인 필요");
            if (summary.items.isEmpty()) {
                paragraph(body, "공동 관리 정비 항목이 없습니다. 모바일에서 항목과 주기를 설정해 주세요.");
            } else {
                java.time.LocalDate today = java.time.LocalDate.now();
                for (com.pleos.fuelog.home.MaintenanceSummary.Item item : summary.items) {
                    LinearLayout card = section(body);
                    subheading(card, item.name);
                    paragraph(card, "마지막 정비 · " + maintenanceDate(item.lastDate)
                            + " · " + maintenanceDistance(item.lastKm));
                    paragraph(card, "다음 예정 · " + maintenanceDate(item.nextDate)
                            + " · " + maintenanceDistance(item.nextKm));
                    paragraph(card, "주기 출처 · " + item.basis);
                    if (item.nextDate != null) {
                        long days = item.daysUntil(today);
                        paragraph(card, days > 0 ? "남은 기간 " + days + "일" : days == 0
                                ? "예정일 오늘" : "예정일 " + (-days) + "일 지남");
                    }
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
        refresh.setEnabled(online() && connection.linked);
        body.addView(refresh, fullButton());
        primary(body, "모바일에서 기록", () -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("모바일에서 기록")
                .setMessage("휴대폰 fuelog 앱의 차계부에서 정비 기록을 등록하거나 정비 주기를 설정해 주세요.")
                .setPositiveButton("확인", null).show());
        paragraph(body, "정비 완료는 모바일에서 확인한 기록만 반영합니다. 예정일과 거리는 고장 진단 결과가 아닙니다.");
    }

    private String featureStatus(String feature) {
        org.json.JSONObject features = settings.snapshot.optJSONObject("features");
        String value = features == null ? "UNKNOWN" : features.optString(feature);
        switch (value) {
            case "AVAILABLE": return "제공 중";
            case "UNSUPPORTED": return "차량에서 지원하지 않음";
            case "PERMISSION_REQUIRED": return "권한 필요 · 시스템 설정에서 허용해 주세요";
            case "TEMPORARY_FAILURE": return "일시적으로 수신 불가 · 잠시 후 다시 확인해 주세요";
            default: return "미확인 · 연결 후 상태를 확인해 주세요";
        }
    }
    private void settingsChanged() {
        if (started && allowed && page == SETTINGS) render();
    }
    private void disconnect(String kind) {
        if (!allowed || settings.busy) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(kind.equals("VEHICLE") ? "차량 등록 초기화" : "이번 사용자 연결 해제")
                .setMessage(kind.equals("VEHICLE")
                        ? "개인 화면을 잠그고 관리자 모바일 재인증을 요청합니다. 미전송 기록은 원래 소유자로 보관하며 삭제하지 않습니다. 휴대폰 M14에서 처리 방식을 확인한 뒤 초기화됩니다."
                        : "개인 화면과 접근 정보를 정리합니다. 미전송 기록은 기존 소유자로 보관합니다. 서버 연결 해제가 끝날 때까지 대기 상태로 표시됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("계속", (dialog, which) -> {
                    if (!started || !allowed) return;
                    tripUser.cancel(); cancelFuelSync(); cancelMaintenance(); maintenanceSummary = null;
                    offerTripSummary = false;
                    settings.begin(kind, this::settingsChanged);
                }).show();
    }
    private void renderSettings() {
        heading(body, "연결·설정");
        boolean pending = !settings.pending().isEmpty();
        detail(body, "휴대폰 연결", pending ? "로컬 잠금 · 서버 해제 대기" : connection.linked ? "차량 등록됨" : "등록 확인 필요");
        detail(body, "연결 기기 별칭", pending || recorder.verifiedOwner().isEmpty() ? "사용자 확인 필요" : settings.snapshot.optString("deviceAlias", "미확인"));
        detail(body, "기록 상태", recorder.trip.status(System.currentTimeMillis()));
        detail(body, "동기화 상태", settings.snapshot.optString("syncStatus").equals("SYNCED") ? "서버 동기화 완료" : "미전송 기록 보관 · 동기화 확인 필요");
        paragraph(body, "마지막 동기화 " + timeLabel(settings.snapshot.optLong("lastSyncedAt")));
        sectionLabel(body, "데이터 제공 상태");
        detail(body, "연료", featureStatus("fuel"));
        detail(body, "거리", featureStatus("distance"));
        detail(body, "위치", featureStatus("location"));
        paragraph(body, settings.message);
        if (pending) {
            paragraph(body, "원격 연결은 아직 해제 완료로 확인되지 않았습니다.");
            Button retry = button(settings.busy ? "처리 중" : "서버 해제 다시 확인", true, () -> settings.retry(this::settingsChanged));
            retry.setEnabled(!settings.busy); body.addView(retry, fullButton());
        } else {
            link(body, "연결 상태 새로 확인", () -> settings.refresh(this::settingsChanged));
            link(body, "이번 사용자 연결 해제", () -> disconnect("USER"));
            link(body, "차량 등록 초기화", () -> disconnect("VEHICLE"));
            if (!connection.linked) link(body, "차량 다시 등록", () -> navigate(CONNECT));
            else link(body, "이번 사용자 확인", () -> navigate(USER));
        }
        if (!pending && connection.linked && !recorder.verifiedOwner().isEmpty()
                && settings.snapshot.optBoolean("recommendationRegistered")
                && settings.snapshot.optBoolean("recommendationConsented")
                && settings.snapshot.optBoolean("recommendationVerified")) {
            paragraph(body, "자동 사용자 추천 · " + (settings.snapshot.optBoolean("recommendationEnabled") ? "사용 중" : "사용 안 함"));
            link(body, "휴대폰에서 추천 설정", () -> new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("휴대폰 fuelog의 M04에서 자동 사용자 추천 설정을 변경한 뒤 상태를 새로 확인해 주세요.")
                    .setPositiveButton("확인", null).show());
        }
    }

    private void renderConnect() {
        heading(body, "휴대폰으로 차량을 연결하세요");
        if (!online()) { paragraph(body, "인터넷 연결이 필요합니다. 네트워크를 확인해 주세요."); return; }
        if (connection.linked) { link(body, "이미 연결된 기기 · 연결 설정", () -> navigate(SETTINGS)); return; }
        if (connection.state.equals("WAITING") && connection.remaining() > 0) {
            LinearLayout layout = grid(body);
            LinearLayout qr = section(layout);
            try {
                com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(
                        connection.payload, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888);
                for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
                android.widget.ImageView image = new android.widget.ImageView(this);
                image.setImageBitmap(bitmap);
                image.setContentDescription("모바일 fuelog 앱으로 스캔할 차량 연결 QR");
                qr.addView(image, new LinearLayout.LayoutParams(dp(240), dp(240)));
            } catch (Exception e) { paragraph(qr, "QR 표시 실패 · 코드를 다시 받아 주세요"); }
            paragraph(qr, "남은 시간 " + connection.remaining() + "초 · 연결 대기 중");
            LinearLayout steps = section(layout);
            paragraph(steps, "1. 휴대폰 fuelog 앱 열기\n2. 차량 등록에서 QR 스캔\n3. 로그인 후 차량 확인");
            paragraph(steps, "요청 " + connection.requestId);
        } else {
            paragraph(body, connection.state.equals("EXPIRED") ? "연결 코드가 만료되었습니다" :
                    connection.message.isEmpty() ? "연결 QR 준비 중" : connection.message);
            primary(body, "코드 다시 받기", () -> connection.retry(() -> { if (started && allowed) render(); }));
        }
    }

    private LinearLayout grid(LinearLayout parent) {
        LinearLayout grid = getResources().getConfiguration().screenWidthDp >= 600 ? row() : column();
        parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return grid;
    }

    private LinearLayout.LayoutParams gridCell() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
        params.setMargins(0, 0, dp(24), dp(20));
        return params;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(30), dp(28), dp(30), dp(26));
        card.setBackground(shape(R.color.car_accent_light, 6, 0));
        parent.addView(card, parent.getOrientation() == LinearLayout.HORIZONTAL ? gridCell() : cardParams());
        return card;
    }

    private LinearLayout section(LinearLayout parent) {
        LinearLayout section = column();
        divider(section);
        section.setPadding(0, dp(22), dp(20), dp(18));
        parent.addView(section, parent.getOrientation() == LinearLayout.HORIZONTAL ? gridCell() : cardParams());
        return section;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(28));
        return params;
    }

    private void heading(LinearLayout parent, String value) { addText(parent, value, 38, true, false, 0, 0, 0, 18); }
    private void subheading(LinearLayout parent, String value) { addText(parent, value, 26, true, false, 0, 0, 0, 10); }
    private void paragraph(LinearLayout parent, String value) { addText(parent, value, 20, false, true, 0, 5, 0, 12); }
    private void tag(LinearLayout parent, String value) { addText(parent, value, 18, true, true, 0, 0, 0, 14); }

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
        view.setTextSize(compact() ? sp * 0.9f : sp);
        view.setTextColor(color(muted ? R.color.car_muted : R.color.car_foreground));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void primary(LinearLayout parent, String title, Runnable action) {
        LinearLayout.LayoutParams params = fullButton();
        if (!compact()) params.width = dp(400);
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
        button.setMinHeight(dp(64));
        button.setTextColor(color(primary ? R.color.car_on_accent : R.color.car_foreground));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.car_line)),
                shape(primary ? R.color.car_accent : R.color.car_panel, 4,
                        primary ? 0 : R.color.car_line), null));
        if (action != null) button.setOnClickListener(v -> action.run());
        return button;
    }

    private Button headerAction(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(compact() ? 18 : 20);
        button.setAllCaps(false);
        button.setTextColor(color(R.color.car_foreground));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.car_line)),
                null, shape(R.color.car_panel, 4, 0)));
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
