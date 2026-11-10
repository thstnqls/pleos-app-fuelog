package com.pleos.fuelog.home;

import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.pleos.fuelog.api.ApiClient;
import com.pleos.fuelog.connection.ConnectionSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-only foreground editor. The backend rechecks the actor and vehicle on every write. */
public final class MaintenancePlanEditor implements AutoCloseable {
    private final AppCompatActivity activity;
    private final ConnectionSession connection;
    private final BooleanSupplier allowed;
    private final Runnable saved;
    private AlertDialog dialog;
    private okhttp3.Call call;
    private TextView formError;
    private boolean closed;

    public MaintenancePlanEditor(AppCompatActivity activity, ConnectionSession connection, BooleanSupplier allowed, Runnable saved) {
        this.activity = activity; this.connection = connection; this.allowed = allowed; this.saved = saved;
    }

    public void show() {
        if (!active()) return;
        request("GET", "maintenance/plans", null, "", response -> {
            formError = null;
            JSONArray items = response.getJSONArray("items");
            String[] choices = new String[items.length() + 1];
            choices[0] = "새 교체주기 등록";
            for (int i = 0; i < items.length(); i++) choices[i + 1] = items.getJSONObject(i).getString("name");
            dialog = new AlertDialog.Builder(activity).setTitle("차량 교체주기 관리")
                    .setItems(choices, (d, which) -> {
                        if (!active()) return;
                        try { form(which == 0 ? new JSONObject() : items.getJSONObject(which - 1)); }
                        catch (Exception invalid) { error("정비 항목을 확인하지 못했습니다"); }
                    }).setNegativeButton("닫기", null).show();
        });
    }

    private void form(JSONObject item) throws Exception {
        if (!active()) return;
        String id = item.optString("id", UUID.randomUUID().toString());
        long revision = item.optLong("revision");
        String operation = "vehicle-maintenance:" + UUID.randomUUID();
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, pad, pad, pad);
        EditText name = field(fields, "정비 항목", text(item, "name"), InputType.TYPE_CLASS_TEXT);
        EditText km = field(fields, "교체주기 km · 선택", text(item, "intervalKm"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText months = field(fields, "교체주기 개월 · 선택", text(item, "intervalMonths"), InputType.TYPE_CLASS_NUMBER);
        EditText date = field(fields, "마지막 교체일 · YYYY-MM-DD · 선택", text(item, "lastServiceDate"), InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        EditText lastKm = field(fields, "마지막 교체 당시 누적거리 km · 선택", text(item, "lastServiceKm"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView note = new TextView(activity);
        note.setText("거리·기간 중 하나 이상을 입력해 주세요. D-day는 마지막 교체일과 기간 주기로 계산합니다. 과거 교체 이력 보정은 모바일에서 진행하며, 저장한 주기는 차량 설정으로 표시됩니다.");
        fields.addView(note);
        TextView failure = new TextView(activity);
        formError = failure;
        fields.addView(failure);
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("차량 교체주기 설정").setView(scroll)
                .setNegativeButton("취소", null).setPositiveButton("저장", null);
        if (revision > 0) builder.setNeutralButton("주기 삭제", (d, which) -> {
            if (!active()) return;
            formError = null;
            dialog = new AlertDialog.Builder(activity).setTitle("교체주기를 삭제할까요?")
                    .setMessage("기존 정비 이력은 보존합니다.").setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (confirm, button) -> request("DELETE", "maintenance/plans/" + id, null,
                            Long.toString(revision), result -> completed())).show();
        });
        dialog = builder.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!active() || call != null) return;
            try {
                JSONObject body = body(name.getText().toString(), km.getText().toString(), months.getText().toString(),
                        date.getText().toString(), lastKm.getText().toString(), operation);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                dialog.setCancelable(false);
                failure.setText("저장 중");
                request("PUT", "maintenance/plans/" + id, body, Long.toString(revision), result -> completed());
            } catch (Exception invalid) { failure.setText("항목명·양수 주기(km 또는 1~1200개월)·날짜 형식을 확인해 주세요."); }
        }));
        dialog.show();
    }

    public static JSONObject body(String name, String km, String months, String date, String lastKm, String operation) throws Exception {
        name = name.trim();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException();
        BigDecimal interval = distance(km), previous = distance(lastKm);
        Integer period = months.trim().isEmpty() ? null : Integer.valueOf(months.trim());
        if (interval != null && interval.signum() == 0 || period != null && (period < 1 || period > 1200)
                || interval == null && period == null) throw new IllegalArgumentException();
        LocalDate serviceDate = date.trim().isEmpty() ? null : LocalDate.parse(date.trim());
        if (serviceDate != null && (serviceDate.getYear() < 1900 || serviceDate.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))))
            throw new IllegalArgumentException();
        JSONObject plan = new JSONObject().put("name", name).put("shared", true).put("basis", "USER");
        if (interval != null) plan.put("intervalKm", interval);
        if (period != null) plan.put("intervalMonths", period);
        JSONObject result = new JSONObject().put("plan", plan).put("operationId", operation);
        if (serviceDate != null) result.put("lastServiceDate", serviceDate.toString());
        if (previous != null) result.put("lastServiceKm", previous);
        return result;
    }

    private static BigDecimal distance(String text) {
        if (text.trim().isEmpty()) return null;
        BigDecimal value = new BigDecimal(text.trim()).stripTrailingZeros();
        if (value.signum() < 0 || value.scale() > 3 || value.precision() - value.scale() > 9) throw new IllegalArgumentException();
        return value;
    }
    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextView title = new TextView(activity); title.setText(label); parent.addView(title);
        EditText input = new EditText(activity); input.setContentDescription(label); input.setInputType(type);
        input.setSingleLine(true); input.setText(value); input.setTextSize(20);
        input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setHint(label);
        input.setMinHeight((int) (64 * activity.getResources().getDisplayMetrics().density));
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private static String text(JSONObject item, String key) { return item.isNull(key) ? "" : item.optString(key, ""); }
    private interface Success { void accept(JSONObject result) throws Exception; }
    private void request(String method, String path, JSONObject body, String revision, Success success) {
        if (!active() || call != null) return;
        try {
            call = connection.requestMaintenance(method, path, body, revision, new ApiClient.ResultCallback() {
                public void onSuccess(ApiClient.ApiResponse response) { activity.runOnUiThread(() -> {
                    call = null;
                    if (!active()) return;
                    try { success.accept(new JSONObject(response.getBody())); }
                    catch (Exception invalid) { error("정비 응답을 확인하지 못했습니다"); }
                }); }
                public void onHttpError(ApiClient.ApiResponse response) { activity.runOnUiThread(() -> {
                    call = null; if (!active()) return;
                    if (response.getStatusCode() == 401 || response.getStatusCode() == 403) { close(); saved.run(); return; }
                    error(response.getStatusCode() == 412 || response.getStatusCode() == 409
                            ? "다른 변경이 있습니다. 목록을 새로 확인해 주세요." : "저장하지 못했습니다. 입력값과 연결 상태를 확인해 주세요.");
                }); }
                public void onFailure(IOException failure) { activity.runOnUiThread(() -> { call = null; if (active()) error("서버 연결 실패 · 저장 여부를 새로 확인해 주세요."); }); }
            });
        } catch (Exception unavailable) { call = null; error("정비 서버에 연결할 수 없습니다"); }
    }
    private void error(String message) {
        if (!active()) return;
        if (formError != null && dialog != null && dialog.isShowing()) {
            formError.setText(message);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
            dialog.setCancelable(true);
            return;
        }
        if (dialog != null) dialog.dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("정비 정보 확인").setMessage(message)
                .setPositiveButton("목록 다시 확인", (d, which) -> show()).setNegativeButton("닫기", null).show();
    }
    private void completed() { close(); saved.run(); }
    private boolean active() { return !closed && allowed.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed(); }
    @Override public void close() { closed = true; if (call != null) call.cancel(); call = null; if (dialog != null) dialog.dismiss(); dialog = null; }
}
