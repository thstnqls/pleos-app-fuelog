package com.pleos.fuelog.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.R;
import com.pleos.fuelog.home.MaintenancePlanEditor;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MaintenanceDatePickerAndroidTest {
    @Test public void largeCalendarKeepsDateAndValidationAndFitsItsWindow() {
        try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
            MaintenancePlanEditor[] editor = new MaintenancePlanEditor[1];
            EditText[] input = new EditText[1];
            scenario.onActivity(activity -> {
                editor[0] = new MaintenancePlanEditor(activity, null, () -> true, () -> {});
                input[0] = new EditText(activity);
                input[0].setText("2026-01-10");
                show(activity, editor[0], input[0]);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                try {
                    MaterialDatePicker<Long> picker = picker(editor[0]);
                    assertEquals(Long.valueOf(LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()), picker.getSelection());
                    Field constraintsField = MaterialDatePicker.class.getDeclaredField("calendarConstraints");
                    constraintsField.setAccessible(true);
                    CalendarConstraints constraints = (CalendarConstraints) constraintsField.get(picker);
                    long today = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    assertTrue(constraints.getDateValidator().isValid(today));
                    assertFalse(constraints.getDateValidator().isValid(today + 86400000L));
                    View decor = picker.requireDialog().getWindow().getDecorView();
                    View confirm = decor.findViewById(com.google.android.material.R.id.confirm_button);
                    assertTrue(confirm.getHeight() >= 64 * activity.getResources().getDisplayMetrics().density);
                    android.graphics.Rect visible = new android.graphics.Rect();
                    assertTrue(confirm.getGlobalVisibleRect(visible));
                    assertEquals(confirm.getHeight(), visible.height());
                    assertTrue(decor.getWidth() <= activity.getWindowManager().getCurrentWindowMetrics().getBounds().width());
                    assertTrue(decor.getHeight() <= activity.getWindowManager().getCurrentWindowMetrics().getBounds().height());
                    Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                    decor.draw(new Canvas(bitmap));
                    try (FileOutputStream out = new FileOutputStream(new File(activity.getCacheDir(), "ui-calendar.png"))) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    bitmap.recycle();
                    assertTrue(confirm.performClick());
                    assertEquals("2026-01-10", input[0].getText().toString());
                    activity.getSupportFragmentManager().executePendingTransactions();
                    input[0].setText("invalid");
                    show(activity, editor[0], input[0]);
                    assertEquals(Long.valueOf(today), picker(editor[0]).getSelection());
                    picker(editor[0]).requireDialog().findViewById(com.google.android.material.R.id.cancel_button).performClick();
                    assertEquals("invalid", input[0].getText().toString());
                    editor[0].close();
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        }
    }

    private static void show(CarHomeActivity activity, MaintenancePlanEditor editor, EditText input) {
        try {
            Method show = MaintenancePlanEditor.class.getDeclaredMethod("showDatePicker", EditText.class);
            show.setAccessible(true);
            show.invoke(editor, input);
            activity.getSupportFragmentManager().executePendingTransactions();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @SuppressWarnings("unchecked")
    private static MaterialDatePicker<Long> picker(MaintenancePlanEditor editor) throws Exception {
        Field field = MaintenancePlanEditor.class.getDeclaredField("datePicker");
        field.setAccessible(true);
        return (MaterialDatePicker<Long>) field.get(editor);
    }
}
