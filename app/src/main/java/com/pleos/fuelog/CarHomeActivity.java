package com.pleos.fuelog;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import com.pleos.fuelog.home.TripState;
import com.pleos.fuelog.home.TripSummary;
import com.pleos.fuelog.home.TripUserSession;
import com.pleos.fuelog.time.AppTime;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import okhttp3.Call;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parked-only screens backed by live API responses and local recording data. */
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
    private ConnectionSession connection;
    private final Handler connectionHandler = new Handler(Looper.getMainLooper());
    private boolean started;
    private AlertDialog apiAlert;
    private final Set<String> shownApiAlerts = new HashSet<>();
    private boolean liveRecorderStarted;
    private ConnectionSettings settings;
    private boolean offerTripSummary;
    private boolean fuelSyncBusy;
    private int fuelSyncGeneration;
    private Call fuelSyncCall;
    private String fuelSyncMessage = "";
    private MaintenanceSummary maintenanceSummary;
    private Call maintenanceCall;
    private int maintenanceGeneration;
    private String maintenanceMessage = "정비 정보를 확인 중입니다";
    private TripUserSession tripUser;
    private HomeRecorder recorder;
    private final Runnable connectionTick = new Runnable() {
        public void run() {
            if (!started) return;
            if (allowed && page == CONNECT) {
                connection.refresh(online(), () -> {
                    if (!started || !allowed) return;
                    if (connection.linked) { page = connection.state.equals("ALREADY_LINKED") ? SETTINGS : USER; startLiveRecorder(); }
                    render();
                });
                if (connection.linked) page = connection.state.equals("ALREADY_LINKED") ? SETTINGS : USER;
                if (connection.state.equals("WAITING") || connection.state.equals("AWAITING_CONFIRMATION")) render();
            }
            if (allowed && page == USER && connection.linked) {
                if (tripUser.personalChallenge) tripUser.pollPersonal(() -> { if (started && allowed && page == USER) render(); });
                else tripUser.pollEvents(recorder.serverIntervalId(), () -> { if (started && allowed && page == USER) render(); });
                render();
            }
            connectionHandler.postDelayed(this, 1000);
        }
    };

    // 1. 앱 진입·복귀: 저장된 연결을 복원하고 화면 이용 제한을 확인한다.
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (state != null) page = state.getInt("page", HOME);
        connection = ConnectionSession.get(this);
        if (!connection.linked) page = CONNECT;
        tripUser = new TripUserSession(connection);
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
        connection.apiStatus().changed = () -> connectionHandler.post(() -> {
            if (started && allowed) { render(); showApiFailure(); }
        });
        connection.onPersonalInvalid = () -> { recorder.clearPersonalCache(); tripUser.cancel(); if (started && allowed) render(); };
        offerTripSummary = !recorder.trip.active && recorder.summary() != null;
        startLiveRecorder();
        connectionHandler.post(connectionTick);
        allowed = false;
        render();
        try {
            restrictions = new AaosVehicleDiagnosticService(this);
            restrictions.start((canUse, detail) -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                boolean wasAllowed = allowed;
                allowed = canUse;
                if (!canUse) {
                    tripUser.cancel(); cancelFuelSync(); cancelMaintenance();
                    if (apiAlert != null) { apiAlert.dismiss(); apiAlert = null; }
                }
                if ((!canUse || !wasAllowed)) page = !settings.pending().isEmpty() ? SETTINGS : connection.linked ? HOME : CONNECT;
                status.setText(canUse ? "우리 차 · 정차 상태" : "주행 제한");
                status.setBackground(shape(canUse ? R.color.car_accent_light : R.color.car_soft, 4, 0));
                render();
            }));
        } catch (LinkageError error) {
            restrictions = null; allowed = false;
            status.setText("차량 UX 서비스 없음");
            render();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", allowed ? page : HOME);
        super.onSaveInstanceState(out);
    }

    // 2. 화면 이동: 현재 연결·사용자 상태에 맞는 화면을 표시한다.
    private void navigate(int destination) {
        if (!allowed) return;
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
        if (page == SETTINGS) settings.refresh(this::settingsChanged);
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
        renderApiStatus();
        switch (page) {
            case USER: renderUser(); break;
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
        heading(body, "휴대폰으로 차량을 연결하세요");
        if (!online()) { paragraph(body, "인터넷 연결이 필요합니다. 네트워크를 확인해 주세요."); return; }
        if (connection.linked) { link(body, "이미 연결된 기기 · 연결 설정", () -> navigate(SETTINGS)); return; }
        if (connection.state.equals("AWAITING_CONFIRMATION") && connection.remaining() > 0) {
            subheading(body, "휴대폰과 확인 코드를 비교하세요");
            number(body, connection.confirmationCode, "");
            detail(body, "연결할 차량", connection.selectedVehicleName);
            paragraph(body, "휴대폰에 표시된 차량과 확인 코드가 같은지 확인한 뒤 연결하세요.");
            primary(body, "코드가 같아요 · 차량 연결", () -> connection.confirmPairing(() -> {
                if (!started || !allowed) return;
                if (connection.linked) { page = USER; startLiveRecorder(); }
                render();
            }));
            return;
        }
        if (connection.state.equals("WAITING") && connection.remaining() > 0) {
            LinearLayout layout = grid(body);
            LinearLayout qr = section(layout);
            showQr(qr, connection.payload, "모바일 fuelog 앱으로 스캔할 차량 연결 QR");
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

    private void startLiveRecorder() {
        if (liveRecorderStarted || !connection.linked) return;
        liveRecorderStarted = true;
        recorder.resume(() -> { if (started && allowed) render(); });
    }

    // 4. 사용자 확인: 동의 QR → 인증된 후보 선택 → 이번 운행 사용자 확정.
    private void renderUser() {
        heading(body, "이번 운행은 누가 기록하나요?");
        paragraph(body, tripUser.message);
        if (!online()) paragraph(body, "오프라인 · 사용자 전환을 보류합니다");
        for (TripUserSession.Candidate candidate : tripUser.candidates) {
            Button choice = button((candidate.id.equals(tripUser.selected) ? "✓ " : "") + candidate.name
                    + " · 차량 멤버", false, () -> {
                if (!allowed || tripUser.busy || !candidate.valid()) return;
                tripUser.selected = candidate.id;
                render();
            });
            choice.setEnabled(candidate.valid() && !tripUser.busy && online());
            body.addView(choice, fullButton());
            paragraph(body, "확인 만료까지 " + Math.max(0, (candidate.deadline - SystemClock.elapsedRealtime()) / 1000) + "초");
        }
        Button confirm = button(tripUser.busy ? "확인 중" : "이번 운행에 사용", true, () -> {
            if (!allowed || !online()) return;
            tripUser.confirm(recorder.serverIntervalId(), "verify:" + recorder.intervalId + ":" + tripUser.selected, (subject, name, expiry, candidateId) -> {
                if (!allowed || !started || !online() || page != USER) throw new IllegalStateException();
                recorder.assignUser(subject, name, expiry, candidateId);
                page = HOME;
            }, () -> { if (started && allowed) render(); });
            render();
        });
        confirm.setEnabled(online() && !tripUser.busy && tripUser.selection() != null);
        body.addView(confirm, fullButton());
        if (!recorder.trip.active) paragraph(body, "홈에서 기록을 시작한 뒤 현재 구간의 사용자를 확인해 주세요.");
        if (tripUser.challengeValid() && !tripUser.qrPayload.isEmpty()) showQr(body, tripUser.qrPayload, "사용자 인증 QR");
        link(body, "이번 구간 동의 QR", () -> {
            if (recorder.serverIntervalId().isEmpty()) {
                connectionRequired("기록 구간 연결이 필요합니다. 홈에서 기록을 시작하고 서버의 구간 생성 응답을 확인해 주세요."); return;
            }
            if (connection.installationId.isEmpty()) {
                connectionRequired("모바일 동의에 필요한 설치 ID 연결이 필요합니다. 현재 설치 인증에는 서버 발급 설치 ID가 없습니다."); return;
            }
            tripUser.challenge(recorder.serverIntervalId(), false, () -> { if (started && allowed && page == USER) render(); });
        });
        link(body, "개인 홈 인증 QR", () -> {
            if (connection.installationId.isEmpty()) {
                connectionRequired("개인 홈 인증에 필요한 서버 설치 ID가 없습니다. 설치 등록 API 연결을 확인해 주세요."); return;
            }
            tripUser.challenge("", true, () -> { if (started && allowed && page == USER) render(); });
        });
        if (!connection.personalToken().isEmpty()) link(body, "개인 홈 세션 해제", this::releasePersonalSession);
        link(body, "인증된 후보 새로 확인", this::loadUserCandidates);
        link(body, "휴대폰으로 연결", () -> new AlertDialog.Builder(this)
                .setTitle("휴대폰으로 연결")
                .setMessage("휴대폰 fuelog 앱에서 연결된 차량을 열고 이번 운행 사용에 동의해 주세요.")
                .setPositiveButton("후보 확인", (dialog, which) -> loadUserCandidates()).show());
    }

    private void loadUserCandidates() {
        if (!allowed || !started || !online() || tripUser.busy) return;
        if (recorder.serverIntervalId().isEmpty()) {
            tripUser.message = "연결 필요 · 홈에서 기록 시작 후 서버 구간이 생성되어야 후보를 조회할 수 있습니다";
            render(); return;
        }
        tripUser.load(recorder.serverIntervalId(), () -> { if (started && allowed && page == USER) render(); });
        render();
    }

    private void releasePersonalSession() {
        connection.request("DELETE", "trip-user/session", null, "", true, new ResultCallback() {
            public void onSuccess(ApiResponse response) { runOnUiThread(() -> {
                connection.clearPersonal(); recorder.clearPersonalCache(); if (started && allowed) render();
            }); }
            public void onHttpError(ApiResponse response) { }
            public void onFailure(IOException error) { runOnUiThread(() -> {
                tripUser.message = "세션 해제 실패 · 다시 시도해 주세요"; if (started && allowed) render();
            }); }
        });
    }

    // 5. 운행 기록: 홈에서 시작·종료하고 요약을 모바일 확인함으로 전달한다.
    private void renderHome() {
        if (offerTripSummary) {
            link(body, "저장된 운행 요약 확인", () -> { offerTripSummary = false; navigate(TRIP); });
        }
        TripState trip = recorder.trip;
        connection.personalToken();
        tag(body, "연결된 차량 · " + (connection.home.isNull("vehicleAlias") ? "별칭 미설정" : connection.home.optString("vehicleAlias")));
        heading(body, recorder.owner.isEmpty() ? "이번 운행 사용자를 확인하세요" : recorder.displayUser());
        paragraph(body, "현재 사용자 · " + recorder.displayUser());
        link(body, "사용자 확인", () -> navigate(USER));
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
            link(body, "사용자 확인", () -> navigate(USER)); return;
        }
        JSONObject data = summary.data;
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
        JSONObject serverSummary = recorder.serverSummary(id);
        if (serverSummary != null) {
            sectionLabel(body, "서버 운행 요약");
            detail(body, "거리", serverMetric(serverSummary, "distanceKm", " km"));
            detail(body, "소모량", serverMetric(serverSummary, "fuelConsumedLiters", " L"));
            detail(body, "연비", serverMetric(serverSummary, "fuelEfficiencyKmPerLiter", " km/L"));
            detail(body, "비용", serverMetric(serverSummary, "estimatedCost", "원"));
        }
        paragraph(body, recorder.syncMessage());
        Button mobile = button(recorder.mobileDelivered(id) ? "모바일 전달 완료" : recorder.mobileQueued(id) ? "모바일 전달 대기 중" : "모바일에서 상세 확인", true, () -> {
            try { recorder.enqueueMobile(id); render(); }
            catch (Exception e) { paragraph(body, "모바일 전달 요청을 저장하지 못했습니다. 다시 시도해 주세요."); }
        });
        mobile.setEnabled(end != 0 && !data.optString("candidateId").isEmpty() && !recorder.mobileQueued(id));
        body.addView(mobile, fullButton());
        paragraph(body, "검증된 사용자 운행만 전달할 수 있습니다. 요약 업로드 후 모바일 확인함으로 전송합니다.");
    }

    // 6. 주유 확인: 연료 증가 후보를 확인하고 모바일 처리 상태를 갱신한다.
    private void renderFuel() {
        heading(body, "주유 기록 확인이 필요해요");
        if (recorder.verifiedOwner().isEmpty()) {
            paragraph(body, "계정 귀속을 확인할 수 없습니다. 이번 운행 사용자를 먼저 확인해 주세요.");
            link(body, "사용자 확인", () -> navigate(USER)); return;
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
            try { recorder.queueFuel(id); render(); }
            catch (Exception e) { paragraph(body, "전달 요청 저장 실패 · 다시 시도해 주세요"); }
        });
        mobile.setEnabled(state.equals("CANDIDATE") && !candidate.optBoolean("queued"));
        body.addView(mobile, fullButton());
        link(body, "주유소 후보 조회", () -> searchStations(id));
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

    private void searchStations(String id) {
        if (!allowed || !online() || fuelSyncBusy) return;
        fuelSyncBusy = true;
        int version = ++fuelSyncGeneration;
        try {
            fuelSyncCall = connection.request("POST", "fuel-candidates/" + UUID.fromString(id) + "/station-search",
                    new JSONObject().put("locationPermission", false), "", false, new ResultCallback() {
                public void onSuccess(ApiResponse response) { runOnUiThread(() -> {
                    if (version != fuelSyncGeneration || !started || !allowed || page != FUEL) return;
                    fuelSyncBusy = false;
                    try {
                        JSONObject result = new JSONObject(response.getBody());
                        JSONArray stations = result.optJSONArray("stationCandidates");
                        fuelSyncMessage = stations == null || stations.length() == 0 ? "조회된 주유소 후보가 없습니다"
                                : "주유소 후보 " + stations.length() + "곳 · 모바일에서 확인해 주세요";
                    } catch (Exception error) { connection.apiStatus().invalid("POST", "fuel-candidates/{id}/station-search"); fuelSyncMessage = "연결 필요 · 주유소 응답을 확인하지 못했습니다"; }
                    render();
                }); }
                public void onHttpError(ApiResponse response) { failed("주유소 조회 실패 · HTTP " + response.getStatusCode()); }
                public void onFailure(IOException error) { failed("주유소 조회 서버 연결 실패"); }
                private void failed(String message) { runOnUiThread(() -> {
                    if (version != fuelSyncGeneration || !started || !allowed || page != FUEL) return;
                    fuelSyncBusy = false; fuelSyncMessage = message; render();
                }); }
            });
        } catch (Exception error) { fuelSyncBusy = false; fuelSyncMessage = "주유소 조회를 시작하지 못했습니다"; }
    }

    private void cancelFuelSync() {
        fuelSyncGeneration++; fuelSyncBusy = false;
        if (fuelSyncCall != null) fuelSyncCall.cancel();
        fuelSyncMessage = "";
    }

    // 7. 정비 확인: 서버 요약을 읽고 날짜·거리 기준의 예정 정보를 표시한다.
    private void renderMaintenance() {
        heading(body, "차량 관리");
        MaintenanceSummary summary = maintenanceSummary;
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
                LocalDate today = LocalDate.now();
                for (MaintenanceSummary.Item item : summary.items) {
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
        primary(body, "모바일에서 기록", () -> new AlertDialog.Builder(this)
                .setTitle("모바일에서 기록")
                .setMessage("휴대폰 fuelog 앱의 차계부에서 정비 기록을 등록하거나 정비 주기를 설정해 주세요.")
                .setPositiveButton("확인", null).show());
        paragraph(body, "정비 완료는 모바일에서 확인한 기록만 반영합니다. 예정일과 거리는 고장 진단 결과가 아닙니다.");
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
            maintenanceCall = connection.readVehicle("maintenance/summary", new ResultCallback() {
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

    private boolean maintenanceVisible(int generation) {
        return generation == maintenanceGeneration && started && allowed
                && page == MAINTENANCE && connection.linked && !isFinishing() && !isDestroyed();
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
            link(body, "휴대폰에서 추천 설정", () -> new AlertDialog.Builder(this)
                    .setMessage("휴대폰 fuelog의 M04에서 자동 사용자 추천 설정을 변경한 뒤 상태를 새로 확인해 주세요.")
                    .setPositiveButton("확인", null).show());
        }
    }

    private void settingsChanged() {
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

    // 9. 화면 종료: 요청·구독을 정리하고 기록 수집을 중단한다.
    @Override protected void onStop() {
        started = false;
        connection.apiStatus().changed = () -> {};
        if (apiAlert != null) { apiAlert.dismiss(); apiAlert = null; }
        connection.onPersonalInvalid = recorder::clearPersonalCache;
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

    // 공통 연결 안내: 현재 화면에서 요청하는 API의 실제 응답 상태를 표시한다.
    private boolean online() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private String[][] pageApis() {
        switch (page) {
            case CONNECT:
                if (connection.usesPairingApi()) return new String[][]{
                        {"설치 ID 발급", "POST", "installations"}, {"설치 연결 복원", "GET", "installations/me"},
                        {"차량 QR 발급", "POST", "pairing-requests"}, {"QR 승인 조회", "GET", "pairing-requests/{id}"},
                        {"차량에서 연결 확인", "POST", "pairing-requests/{id}/confirm"}};
                return new String[][]{{"차량 QR 발급", "POST", "connection/requests"}, {"QR 승인 조회", "POST", "connection/requests/status"}};
            case USER: return new String[][]{{"사용자 인증 QR", "POST", "trip-user/challenges"}, {"개인 홈 승인", "POST", "trip-user/session"},
                    {"이번 구간 후보", "POST", "trip-user/candidates"}, {"사용자 검증", "POST", "trip-user/verify"}};
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
        int unverified = 0;
        for (String[] api : pageApis()) if (!connection.apiStatus().get(api[1], api[2]).connected) unverified++;
        if (!connection.hasServer()) {
            tag(body, "연결 필요 · 백엔드 주소 미설정");
            link(body, "연결 필요 사유 확인", () -> connectionRequired("백엔드 주소가 설정되지 않았거나 주소 설정을 읽지 못했습니다. 서버 주소 설정 후 앱을 다시 빌드해 주세요."));
        } else if (unverified > 0) {
            tag(body, "연결 필요 · " + unverified + "개 기능 응답 미확인");
            link(body, "API 연결 상태 확인", this::showApiDetails);
        } else tag(body, "API 연결 확인됨");
        connectionHandler.post(this::showApiFailure);
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

    private void showApiFailure() {
        if (!started || !allowed || isFinishing() || isDestroyed() || apiAlert != null) return;
        if (!connection.hasServer()) {
            if (shownApiAlerts.add("configuration")) connectionRequired("백엔드 주소 연결이 필요합니다. 서버 주소 설정을 확인해 주세요.");
            return;
        }
        for (String[] api : pageApis()) {
            ApiStatus.Entry entry = connection.apiStatus().get(api[1], api[2]);
            if (entry.failed && shownApiAlerts.add(page + ":" + entry.key + ":" + entry.detail)) {
                connectionRequired(api[0] + "\n" + entry.detail + "\n\n연결이 확인될 때까지 실제 데이터를 표시할 수 없습니다. 저장된 기록은 유지합니다.");
                return;
            }
        }
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
