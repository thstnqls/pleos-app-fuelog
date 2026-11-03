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
    private int page = HOME;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (state != null) page = state.getInt("page", HOME);
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
        allowed = false;
        render();
        restrictions = new AaosVehicleDiagnosticService(this);
        restrictions.start((canUse, detail) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean wasAllowed = allowed;
            allowed = canUse;
            if (!canUse || !wasAllowed) page = HOME;
            status.setText(canUse ? "우리 차 · 정차 상태" : "주행 제한");
            status.setBackground(shape(canUse ? R.color.car_accent_light : R.color.car_soft, 4, 0));
            render();
        }));
    }

    @Override protected void onStop() {
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
        page = destination;
        render();
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

    private void renderHome() {
        tag(body, "이번 운행  ·  화면 시안");
        heading(body, "운행 사용자를 확인해 주세요");
        paragraph(body, "휴대폰을 연결해 이번 운행의 계정을 선택하세요.");
        primary(body, "사용자 확인  →", () -> navigate(USER));
        sectionLabel(body, "최근 운행과 확인할 항목");
        LinearLayout grid = grid(body);
        LinearLayout trip = card(grid);
        tag(trip, "최근 운행  ·  예시 데이터");
        number(trip, "60.0", " km");
        detail(trip, "추정 연료비", "6,800원");
        detail(trip, "소모 연료", "4.0L");
        link(trip, "운행 요약 보기", () -> navigate(TRIP));
        LinearLayout side = column();
        grid.addView(side, gridCell());
        sectionLabel(side, "확인할 항목");
        subheading(side, "주유 기록 1건");
        paragraph(side, "휴대폰에서 금액 확인 · 예시");
        link(side, "주유 요약", () -> navigate(FUEL));
        sectionLabel(side, "다음 정비");
        subheading(side, "엔진오일");
        paragraph(side, "설정한 주기까지 1,200km · 예시");
        link(side, "정비 요약", () -> navigate(MAINTENANCE));
    }

    private void renderUser() {
        heading(body, "이번 운행은 누가 기록하나요?");
        sectionLabel(body, "연결 상태");
        LinearLayout card = section(body);
        subheading(card, "연결된 휴대폰 없음");
        paragraph(card, "인증된 휴대폰과 차량 멤버십을 확인한 뒤 사용할 수 있어요.");
        Button confirm = button("이번 운행에 사용", true, null);
        confirm.setEnabled(false);
        body.addView(confirm, fullButton());
        link(body, "휴대폰 연결", () -> navigate(CONNECT));
    }

    private void renderTrip() {
        heading(body, "최근 운행");
        tag(body, "비공개  /  추정  /  예시 데이터");
        LinearLayout grid = grid(body);
        LinearLayout distance = section(grid);
        number(distance, "60.0", " km");
        paragraph(distance, "10월 31일 · 10:00–11:05");
        LinearLayout detail = section(grid);
        detail(detail, "추정 연료비", "6,800원");
        detail(detail, "소모 연료", "4.0L");
        detail(detail, "추정 연비", "15.0km/L");
        paragraph(body, "실제 운행 기록은 아직 연결되지 않았습니다. 상세 확인은 모바일 앱에서 제공할 예정입니다.");
    }

    private void renderFuel() {
        heading(body, "주유 기록 확인이 필요해요");
        tag(body, "자동 감지  /  추정  /  예시 데이터");
        LinearLayout grid = grid(body);
        LinearLayout amount = section(grid);
        number(amount, "20.0", " L");
        paragraph(amount, "추정 금액 34,000원");
        LinearLayout station = section(grid);
        subheading(station, "주유소 후보");
        paragraph(station, "예시 주유소");
        paragraph(station, "휘발유 · 게시 단가 1,700원/L");
        paragraph(body, "실제 주유소와 결제 금액은 모바일에서 확인해야 합니다.");
    }

    private void renderMaintenance() {
        heading(body, "차량 관리");
        LinearLayout grid = grid(body);
        LinearLayout distance = section(grid);
        paragraph(distance, "최근 수신 누적거리 · 예시 데이터");
        number(distance, "50,800", " km");
        paragraph(distance, "10월 31일 11:05 기준");
        LinearLayout service = section(grid);
        subheading(service, "엔진오일");
        paragraph(service, "다음 정비 52,000km");
        paragraph(service, "사용자가 설정한 주기 · 예시 데이터");
        paragraph(body, "정비 기록과 주기 변경은 모바일 앱에서 제공합니다.");
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
        LinearLayout qr = section(body);
        qr.setGravity(Gravity.CENTER);
        qr.setMinimumHeight(dp(180));
        subheading(qr, "연결 QR 준비 중");
        paragraph(qr, "발급된 코드가 없습니다");
        paragraph(body, "fuelog 모바일 앱에서 차량 등록을 선택하세요. 연결 기능은 아직 준비 중입니다.");
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
