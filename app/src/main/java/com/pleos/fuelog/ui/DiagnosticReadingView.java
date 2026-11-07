package com.pleos.fuelog.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.pleos.fuelog.R;

import java.text.DateFormat;
import java.util.Date;

/** One metric with a readable value, explicit state, and original diagnostic details. */
public final class DiagnosticReadingView extends LinearLayout {
    private final TextView badge;
    private final TextView reading;
    private final TextView detail;
    private final TextView metadata;
    private final String api;
    private final int foreground;
    private String state = "미조회";

    public DiagnosticReadingView(Context context) {
        this(context, "", "");
    }

    public DiagnosticReadingView(Context context, String label, String api) {
        super(context);
        this.api = api;
        setOrientation(VERTICAL);
        setPadding(0, dp(12), 0, dp(12));
        foreground = MaterialColors.getColor(this, R.attr.colorOnSurface);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 15);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        badge = text("", 12);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
        badgeParams.setMarginStart(dp(8));
        header.addView(badge, badgeParams);
        addView(header);

        reading = text("—", 24);
        reading.setTypeface(null, Typeface.BOLD);
        addView(reading);
        detail = text("", 12);
        addView(detail);
        metadata = text(api, 11);
        int surface = MaterialColors.getColor(this, R.attr.colorSurface);
        metadata.setTextColor(ColorUtils.blendARGB(surface, foreground, 0.68f));
        addView(metadata);
        reset();
    }

    public String getReadingState() { return state; }

    public void reset() {
        renderBadge("미조회");
        reading.setText("—");
        reading.setTextColor(foreground);
        detail.setText("");
        detail.setVisibility(GONE);
        metadata.setText(api);
    }

    public void render(String source, String state, String value, long receivedAt) {
        renderBadge(state);
        int accessAt = value.indexOf(" · 접근 ");
        String payload = accessAt < 0 ? value : value.substring(0, accessAt);
        String access = accessAt < 0 ? "" : value.substring(accessAt);
        boolean received = state.equals("수신");
        reading.setText(received ? payload : state.equals("대기") ? "조회 중…" : "—");
        reading.setTextColor(foreground);
        detail.setText(received ? "" : payload);
        detail.setVisibility(received ? GONE : VISIBLE);
        metadata.setText(getContext().getString(R.string.diagnostic_reading_metadata,
                api, source, access, DateFormat.getTimeInstance().format(new Date(receivedAt))));
    }

    private void renderBadge(String state) {
        this.state = state;
        badge.setText(state);
        int textColor;
        int backgroundColor;
        if (state.equals("수신")) {
            textColor = ContextCompat.getColor(getContext(), R.color.diagnostic_success);
            backgroundColor = ContextCompat.getColor(getContext(), R.color.diagnostic_success_background);
        } else if (state.equals("미지원") || state.equals("읽기 불가") || state.equals("설정 없음")
                || state.equals("대기") || state.equals("값 없음") || state.equals("계산 불가")) {
            textColor = ContextCompat.getColor(getContext(), R.color.diagnostic_caution);
            backgroundColor = ContextCompat.getColor(getContext(), R.color.diagnostic_caution_background);
        } else if (state.contains("오류") || state.equals("시간 초과") || state.equals("현재 사용 불가")) {
            textColor = ContextCompat.getColor(getContext(), R.color.diagnostic_error);
            backgroundColor = ContextCompat.getColor(getContext(), R.color.diagnostic_error_background);
        } else {
            textColor = foreground;
            int surface = MaterialColors.getColor(this, R.attr.colorSurface);
            backgroundColor = ColorUtils.blendARGB(surface, foreground, 0.08f);
        }
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(backgroundColor);
        badge.setBackground(background);
        badge.setTextColor(textColor);
        detail.setTextColor(textColor);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(foreground);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
