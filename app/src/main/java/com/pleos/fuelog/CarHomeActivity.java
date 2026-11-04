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
    private com.pleos.fuelog.home.TripUserSession tripUser;
    private String userOperation = java.util.UUID.randomUUID().toString();
    private com.pleos.fuelog.home.HomeRecorder recorder;
    private final Runnable connectionTick = new Runnable() {
        public void run() {
            if (!started) return;
            if (allowed && page == CONNECT) {
                connection.refresh(online(), () -> {
                    if (!started || !allowed) return;
                    if (connection.linked) page = connection.state.equals("ALREADY_LINKED") ? SETTINGS : USER;
                    render();
                });
                if (connection.linked) page = connection.state.equals("ALREADY_LINKED") ? SETTINGS : USER;
                render();
            }
            if (allowed && page == USER && !tripUser.busy) render();
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
        if (!connection.linked) page = CONNECT;
        tripUser = new com.pleos.fuelog.home.TripUserSession(connection);
        recorder = new com.pleos.fuelog.home.HomeRecorder(this);
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
        recorder.resume(() -> { if (started && allowed && page == HOME) render(); });
        connectionHandler.post(connectionTick);
        allowed = false;
        render();
        restrictions = new AaosVehicleDiagnosticService(this);
        restrictions.start((canUse, detail) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean wasAllowed = allowed;
            allowed = canUse;
            if (!canUse) tripUser.cancel();
            if (!canUse || !wasAllowed) page = connection.linked ? HOME : CONNECT;
            status.setText(canUse ? "우리 차 · 정차 상태" : "주행 제한");
            status.setBackground(shape(canUse ? R.color.car_accent_light : R.color.car_soft, 4, 0));
            render();
        }));
    }

    @Override protected void onStop() {
        started = false;
        recorder.pause();
        tripUser.cancel();
        connectionHandler.removeCallbacks(connectionTick);
        allowed = false;
        if (restrictions != null) { restrictions.close(); restrictions = null; }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", allowed ? page : HOME);
        super.onSaveInstanceState(out);
    }

    private void navigate(int destination) {
        if (!allowed) return;
        if (destination != USER) tripUser.cancel();
        page = !connection.linked && destination != SETTINGS ? CONNECT : destination;
        render();
        if (page == USER) loadUserCandidates();
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

    private String timeLabel(long timestamp) {
        return timestamp == 0 ? "아직 없음" : new java.text.SimpleDateFormat("MM.dd HH:mm:ss", java.util.Locale.KOREA)
                .format(new java.util.Date(timestamp));
    }

    private void renderHome() {
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

    private void renderTrip() {
        heading(body, "운행 요약");
        paragraph(body, "개인 운행 내역은 휴대폰에서 사용자를 확인한 뒤 제공됩니다.");
        link(body, "사용자 확인", () -> navigate(USER));
    }

    private void renderFuel() {
        heading(body, "주유 확인");
        paragraph(body, "주유 내역과 지출은 휴대폰에서 사용자를 확인한 뒤 제공됩니다.");
        link(body, "사용자 확인", () -> navigate(USER));
    }

    private void renderMaintenance() {
        heading(body, "정비 예정");
        paragraph(body, "정비 알림은 휴대폰에서 사용자를 확인한 뒤 제공됩니다.");
        link(body, "사용자 확인", () -> navigate(USER));
    }

    private void renderSettings() {
        heading(body, "연결·설정");
        LinearLayout card = section(body);
        detail(card, "휴대폰 연결", "연결 필요");
        detail(card, "기록 동기화", "미연결");
        detail(card, "연료·거리 데이터", "진단 화면에서 확인");
        link(body, "차량 연결 안내", () -> navigate(CONNECT));
        link(body, "이번 사용자 확인", () -> navigate(USER));
        link(body, "차량 데이터 진단", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
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
