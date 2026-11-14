package com.pleos.fuelog.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.pleos.fuelog.R;

/** Readable app-owned consent; the OS still grants or denies the actual permission. */
public final class ConsentDialogs {
    private ConsentDialogs() {}
    public static AlertDialog.Builder builder(Activity activity, String title, String message) {
        TextView heading = new TextView(activity);
        heading.setText(title); heading.setTextSize(32); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(activity.getColor(R.color.car_foreground));
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        heading.setPadding(dp(activity,24), dp(activity,24), dp(activity,24), dp(activity,16));
        TextView body = new TextView(activity);
        body.setTag("consent.body"); body.setText(message); body.setTextSize(26);
        body.setTextColor(activity.getColor(R.color.car_foreground)); body.setLineSpacing(dp(activity,6), 1.1f);
        body.setPadding(dp(activity,24), 0, dp(activity,24), dp(activity,24));
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        return new AlertDialog.Builder(activity).setCustomTitle(heading).setView(scroll);
    }
    public static void show(Activity activity, AlertDialog dialog) {
        dialog.show();
        int width = Math.min(dp(activity,900), activity.getResources().getDisplayMetrics().widthPixels - dp(activity,32));
        dialog.getWindow().setLayout(width, -2);
        TextView body = dialog.getWindow().getDecorView().findViewWithTag("consent.body");
        body.measure(View.MeasureSpec.makeMeasureSpec(width - dp(activity,48), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        View scroll = (View) body.getParent();
        scroll.getLayoutParams().height = Math.min(body.getMeasuredHeight(), (int)(activity.getResources().getDisplayMetrics().heightPixels * .5));
        scroll.requestLayout();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setTextSize(24); button.setAllCaps(false); button.setMinHeight(dp(activity,72));
            button.setPadding(dp(activity,20), dp(activity,12), dp(activity,20), dp(activity,12));
        }
    }
    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    public static String locationPermissionMessage() {
        return "사용 목적\n현재 차량 위치로 주변 주유소·충전소와 참고 요금을 찾습니다.\n\n"
                + "사용하는 정보\n차량의 현재 위치와 측정 시각을 조회합니다. 주변 시설을 검색할 때 위치를 서버로 전송합니다.\n\n"
                + "다음 시스템 권한 창 안내\nPleos 시스템에서 이 위치 권한을 ‘알 수 없는 정보’로 표시할 수 있습니다. 이 요청은 차량 위치를 읽기 위한 권한입니다.\n\n"
                + "선택 권한\n거절해도 기본 기록과 정비 조회는 사용할 수 있습니다. 위치 기반 검색·요금 조회는 제한됩니다.";
    }
    public static String batteryPermissionMessage() {
        return "사용 목적\n배터리 잔량과 사용 가능한 용량으로 운행 전력 사용량과 충전량을 계산합니다.\n\n"
                + "허용 범위\n배터리 정보를 읽습니다. 충전을 시작·중지하거나 차량 설정을 바꾸는 권한이 아닙니다.\n\n"
                + "선택 권한\n거절해도 앱을 사용할 수 있지만 배터리 기반 충전량·전력비 계산은 제한됩니다.";
    }
    public static String notificationPermissionMessage() {
        return "알림 내용\n충전 중 배터리 증가 기록이 저장되면 충전 기록을 확인할 수 있도록 알려드립니다.\n\n"
                + "선택 권한\n거절해도 운행·충전 기록은 저장되며, 충전 완료 알림만 표시하지 않습니다.\n\n"
                + "변경 방법\n연결 탭에서 충전 알림을 끌 수 있습니다. OS 알림 권한은 시스템 앱 설정에서 변경할 수 있습니다.";
    }
    public static String locationAssistMessage() {
        return "사용 목적\n연료·배터리 증가를 감지하면 현재 차량 위치로 주변 주유소·충전소 후보를 찾아 기록에 연결합니다.\n\n"
                + "서버로 보내는 정보\n주변 시설 검색에 필요한 차량 위치와 측정 시각을 서버로 전송합니다. 연속 이동 경로는 수집하지 않습니다.\n\n"
                + "동의하지 않으면\n기본 운행·충전 관측 기록은 유지하며, 위치를 이용한 주변 시설 자동 검색은 사용하지 않습니다.\n\n"
                + "선택 동의\n연결 탭에서 언제든 동의를 철회할 수 있습니다. 위치 읽기 권한과는 별도의 선택입니다.";
    }
}
