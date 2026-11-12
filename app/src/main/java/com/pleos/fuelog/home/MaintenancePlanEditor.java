package com.pleos.fuelog.home;

import android.text.InputType;
import android.graphics.Typeface;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.pleos.fuelog.R;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Button;
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
    private android.app.DatePickerDialog datePicker;
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
            if(items.length()==0) { form(new JSONObject()); return; }
            String[] choices = new String[items.length() + 1];
            choices[0] = "새 교체주기 등록";
            for (int i = 0; i < items.length(); i++) choices[i + 1] = items.getJSONObject(i).getString("name");
            dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_Fuelog_Dialog).setTitle("차량 교체주기 관리")
                    .setItems(choices, (d, which) -> {
                        if (!active()) return;
                        try { form(which == 0 ? new JSONObject() : items.getJSONObject(which - 1)); }
                        catch (Exception invalid) { error("정비 항목을 확인하지 못했습니다"); }
                    }).setNegativeButton("닫기", null).show();
        });
    }

    public void edit(String id) {
        if(!active()) return;
        request("GET","maintenance/plans",null,"",response->{
            JSONArray items=response.getJSONArray("items");
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.getJSONObject(i);
                if(id.equals(item.optString("id"))) {form(item);return;}
            }
            error("정비 항목을 새로 확인해 주세요");
        });
    }

    public void create() {
        try {form(new JSONObject());} catch(Exception invalid) {error("정비 등록 화면을 열지 못했습니다");}
    }

    private void form(JSONObject item) throws Exception {
        if (!active()) return;
        if("MANUFACTURER".equals(item.optString("basis")) && (item.optJSONObject("evidence")==null || !item.getJSONObject("evidence").optBoolean("verified"))) {
            dialog=new MaterialAlertDialogBuilder(activity,R.style.ThemeOverlay_Fuelog_Dialog).setTitle("제조사 근거 확인 필요")
                    .setMessage("기존 등록 항목은 목록에서 확인할 수 있습니다. 제조사 자료가 검증되기 전에는 이 항목을 변경할 수 없습니다.")
                    .setPositiveButton("확인",null).show();return;
        }
        String id = item.optString("id", UUID.randomUUID().toString());
        long revision = item.optLong("revision");
        String operation = "vehicle-maintenance:" + UUID.randomUUID();
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        fields.setPadding(pad, pad, pad, pad);
        heading(fields,"정비 항목");
        EditText name = field(fields, "항목명", text(item, "name"), InputType.TYPE_CLASS_TEXT);
        heading(fields,"교체주기");
        EditText km = field(fields, "거리 주기 (km)", text(item, "intervalKm"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText months = field(fields, "기간 주기 (개월)", text(item, "intervalMonths"), InputType.TYPE_CLASS_NUMBER);
        heading(fields,"마지막 정비 기록 · 선택");
        EditText date = field(fields, "마지막 교체일", text(item, "lastServiceDate"), InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        EditText lastKm = field(fields, "교체 당시 누적거리 (km)", text(item, "lastServiceKm"), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView note = new TextView(activity);
        note.setTextSize(20);
        note.setTextColor(activity.getColor(R.color.car_muted));
        note.setText("거리 또는 기간 중 하나 이상을 입력해 주세요. 마지막 교체일과 기간을 입력하면 남은 일수를 보여드립니다.");
        fields.addView(note);
        boolean manufacturer="MANUFACTURER".equals(item.optString("basis"));
        if(manufacturer) {
            name.setEnabled(false);km.setEnabled(false);months.setEnabled(false);
            note.setText("제조사 권장 주기는 유지됩니다. 마지막 교체 기록을 입력하거나 교체 완료를 확인해 주세요.");
        }
        if (revision > 0) {
            Button complete = new Button(activity);
            complete.setText("교체 완료 확인");
            complete.setTextSize(24);
            complete.setMinHeight((int) (72 * activity.getResources().getDisplayMetrics().density));
            complete.setTag("maintenance.complete");
            complete.setOnClickListener(v -> { if (active() && call == null) completion(item); });
            fields.addView(complete);
        }
        TextView failure = new TextView(activity);
        failure.setTextSize(22);
        formError = failure;
        fields.addView(failure);
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_Fuelog_Dialog).setTitle(revision>0 ? "정비 주기 수정" : "사용자 정비 등록").setView(scroll)
                .setNegativeButton("취소", null).setPositiveButton("저장", null);
        if (revision > 0) builder.setNeutralButton("주기 삭제", (d, which) -> {
            if (!active()) return;
            formError = null;
            dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_Fuelog_Dialog).setTitle("교체주기를 삭제할까요?")
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
                if(manufacturer) body.getJSONObject("plan").put("basis","MANUFACTURER")
                        .put("evidenceId",item.getJSONObject("evidence").getString("id"));
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

    private void completion(JSONObject item) {
        if (dialog != null) dialog.dismiss();
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        fields.setPadding(pad, pad, pad, pad);
        TextView note = new TextView(activity);
        note.setTextSize(22);
        note.setText("실제로 교체한 경우에만 완료를 확인해 주세요. 다음 예정은 입력한 교체일·거리부터 계산하며, 완료 확인 이력은 수정·삭제할 수 없습니다.");
        fields.addView(note);
        EditText date = field(fields, "실제 교체일 · YYYY-MM-DD", "",
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        EditText km = field(fields, item.isNull("intervalKm") ? "교체 당시 누적거리 km · 선택" : "교체 당시 누적거리 km · 필수", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TextView failure = new TextView(activity);
        failure.setTextSize(22);
        formError = failure;
        fields.addView(failure);
        ScrollView scroll = new ScrollView(activity); scroll.addView(fields);
        String key = UUID.randomUUID().toString();
        JSONObject[] submitted = {null};
        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_Fuelog_Dialog).setTitle(item.optString("name") + " · 교체 완료")
                .setView(scroll).setNegativeButton("취소", null).setPositiveButton("완료 확인", null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!active() || call != null) return;
            try {
                if (submitted[0] == null) submitted[0] = completionBody(date.getText().toString(), km.getText().toString(), item);
                // A lost response must retry the same body and key.
                date.setEnabled(false); km.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                dialog.setCancelable(false);
                failure.setText("완료 확인 중");
                request("POST", "maintenance/plans/" + item.getString("id") + "/complete", submitted[0],
                        Long.toString(item.getLong("revision")), key, result -> completed());
            } catch (Exception invalid) { failure.setText("실제 교체일과 누적거리를 확인해 주세요. 기존 교체 기록보다 이전 값은 입력할 수 없습니다."); }
        }));
        dialog.show();
    }

    public static JSONObject completionBody(String date, String km, JSONObject item) throws Exception {
        LocalDate serviceDate = LocalDate.parse(date.trim());
        if (serviceDate.getYear() < 1900 || serviceDate.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))
                || !item.isNull("lastServiceDate") && serviceDate.isBefore(LocalDate.parse(item.getString("lastServiceDate"))))
            throw new IllegalArgumentException();
        BigDecimal distance = distance(km);
        if (!item.isNull("intervalKm") && distance == null
                || distance != null && !item.isNull("lastServiceKm") && distance.compareTo(new BigDecimal(item.get("lastServiceKm").toString())) < 0)
            throw new IllegalArgumentException();
        JSONObject body = new JSONObject().put("confirmed", true).put("serviceDate", serviceDate.toString());
        if (distance != null) body.put("odometerKm", distance);
        return body;
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
    private int dp(int value) {return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void heading(LinearLayout parent,String text) {
        TextView title=new TextView(activity);title.setText(text);title.setTextSize(24);
        title.setTypeface(null,Typeface.BOLD);title.setTextColor(activity.getColor(R.color.car_foreground));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.setMargins(0,dp(12),0,dp(16));parent.addView(title,layout);
    }
    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextInputLayout box=new TextInputLayout(activity);
        box.setHint(label);box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        box.setBoxCornerRadii(dp(12),dp(12),dp(12),dp(12));
        box.setBoxStrokeColor(activity.getColor(R.color.car_link));
        box.setBoxBackgroundColor(activity.getColor(R.color.car_panel));
        box.setHintTextAppearance(R.style.TextAppearance_Fuelog_FormHint);
        TextInputEditText input=new TextInputEditText(box.getContext());input.setContentDescription(label);
        input.setInputType(type);input.setSingleLine(true);input.setText(value);input.setTextSize(26);
        input.setTextColor(activity.getColor(R.color.car_foreground));
        input.setPadding(dp(16),dp(20),dp(16),dp(16));input.setMinHeight(dp(76));
        input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        if((type & InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_DATETIME) {
            input.setHint("YYYY-MM-DD");
            box.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);box.setEndIconDrawable(android.R.drawable.ic_menu_my_calendar);
            box.setEndIconContentDescription("교체일 달력 열기");
            box.setEndIconOnClickListener(v->{
                if(!active()) return;
                LocalDate selected;
                try {selected=LocalDate.parse(input.getText().toString());}catch(Exception invalid){selected=LocalDate.now(ZoneId.of("Asia/Seoul"));}
                datePicker=new android.app.DatePickerDialog(activity,(view,year,month,day)->
                        input.setText(LocalDate.of(year,month+1,day).toString()),selected.getYear(),selected.getMonthValue()-1,selected.getDayOfMonth());
                datePicker.getDatePicker().setMaxDate(System.currentTimeMillis());datePicker.show();
            });
        }
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.setMargins(0,dp(4),0,dp(20));
        parent.addView(box,layout);return input;
    }
    private static String text(JSONObject item, String key) { return item.isNull(key) ? "" : item.optString(key, ""); }
    private interface Success { void accept(JSONObject result) throws Exception; }
    private void request(String method, String path, JSONObject body, String revision, Success success) {
        request(method, path, body, revision, "", success);
    }
    private void request(String method, String path, JSONObject body, String revision, String idempotencyKey, Success success) {
        if (!active() || call != null) return;
        try {
            call = connection.requestMaintenance(method, path, body, revision, idempotencyKey, new ApiClient.ResultCallback() {
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
        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_Fuelog_Dialog).setTitle("정비 정보 확인").setMessage(message)
                .setPositiveButton("목록 다시 확인", (d, which) -> show()).setNegativeButton("닫기", null).show();
    }
    private void completed() { close(); saved.run(); }
    private boolean active() { return !closed && allowed.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed(); }
    @Override public void close() { closed = true; if(datePicker!=null) datePicker.dismiss();datePicker=null; if (call != null) call.cancel(); call = null; if (dialog != null) dialog.dismiss(); dialog = null; }
}
