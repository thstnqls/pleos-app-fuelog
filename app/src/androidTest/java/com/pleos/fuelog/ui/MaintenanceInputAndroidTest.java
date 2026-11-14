package com.pleos.fuelog.ui;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.textfield.TextInputLayout;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.home.MaintenancePlanEditor;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MaintenanceInputAndroidTest {
    @Test public void labelsStayOutsideEmptyFocusedAndFilledFields() {
        try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
            scenario.onActivity(activity -> {
                try (MaintenancePlanEditor editor = new MaintenancePlanEditor(activity, null, () -> false, () -> {})) {
                    Method field = MaintenancePlanEditor.class.getDeclaredMethod("field", LinearLayout.class, String.class, String.class, int.class);
                    field.setAccessible(true);
                    for (String label : new String[]{"항목명", "거리 주기 (km)", "기간 주기 (개월)", "마지막 교체일",
                            "교체 당시 누적거리 (km)", "시작일 · 첫 정비 완료일", "실제 교체일 · YYYY-MM-DD",
                            "교체 당시 누적거리 km · 선택", "교체 당시 누적거리 km · 필수"}) {
                        LinearLayout fields = new LinearLayout(activity);
                        fields.setOrientation(LinearLayout.VERTICAL);
                        int type = label.contains("일") ? InputType.TYPE_CLASS_DATETIME : InputType.TYPE_CLASS_TEXT;
                        EditText input = (EditText) field.invoke(editor, fields, label, "", type);
                        TextView caption = (TextView) fields.getChildAt(0);
                        TextInputLayout box = (TextInputLayout) fields.getChildAt(1);
                        assertFalse(box.isHintEnabled());
                        assertNull(input.getHint());
                        assertEquals(input.getId(), caption.getLabelFor());
                        assertEquals(label, input.getContentDescription().toString());
                        for (int width : new int[]{280, 480, 768}) {
                            for (String value : new String[]{"", "2026-11-14"}) {
                                input.requestFocus(); input.setText(value);
                                fields.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                                fields.layout(0, 0, width, fields.getMeasuredHeight());
                                assertTrue(caption.getHeight() > 0);
                                assertTrue(caption.getBottom() <= box.getTop());
                                assertNull(input.getHint());
                            }
                        }
                    }
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }

    @Test public void datesFormatTypingPasteCorrectionAndBackspace() {
        try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
            scenario.onActivity(activity -> {
                EditText input = new EditText(activity);
                input.addTextChangedListener(new DateInputFormatter(input));
                input.setText(""); input.setSelection(0);
                for (char digit : "20261114".toCharArray()) {
                    input.getText().insert(input.getSelectionStart(), String.valueOf(digit));
                }
                assertEquals("2026-11-14", input.getText().toString());
                assertEquals(10, input.getSelectionStart());
                input.setText("2026-01-02");
                assertEquals("2026-01-02", input.getText().toString());
                input.setText("20260102");
                assertEquals("2026-01-02", input.getText().toString());
                input.setSelection(5);
                input.getText().delete(4, 5);
                assertEquals("2020-10-2", input.getText().toString());
                assertEquals(3, input.getSelectionStart());
                input.setText("20261114");
                input.setSelection(6);
                input.getText().replace(5, 6, "0");
                assertEquals("2026-01-14", input.getText().toString());
                input.getText().clear();
                assertEquals("", input.getText().toString());
                input.setText("20260230");
                assertEquals("2026-02-30", input.getText().toString());
                // Formatting must not turn an invalid date into a valid, silently changed date.
                assertThrows(java.time.format.DateTimeParseException.class,
                        () -> java.time.LocalDate.parse(input.getText().toString()));
            });
        }
    }
}
