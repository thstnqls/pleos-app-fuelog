package com.pleos.fuelog.connection;

import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.ui.ConsentDialogs;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConsentDialogsAndroidTest {
    @Test public void consentExplainsScopeAndUsesLargeScrollableTextAndActions() {
        AlertDialog[] dialog = new AlertDialog[1];
        try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
            scenario.onActivity(activity -> {
                dialog[0] = ConsentDialogs.builder(activity, "차량 위치 읽기", ConsentDialogs.locationPermissionMessage())
                        .setPositiveButton("시스템 권한 확인", null).setNegativeButton("지금은 사용 안 함", null).create();
                ConsentDialogs.show(activity, dialog[0]);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                try {
                    View root = dialog[0].getWindow().getDecorView();
                    TextView body = root.findViewWithTag("consent.body");
                    float scale = activity.getResources().getDisplayMetrics().scaledDensity;
                    assertTrue(body.getTextSize() >= 26 * scale - 1);
                    assertTrue(body.getText().toString().contains("서버로 전송"));
                    assertTrue(body.getText().toString().contains("알 수 없는 정보"));
                    assertTrue(body.getText().toString().contains("거절해도"));
                    assertTrue(body.getParent() instanceof android.widget.ScrollView);
                    assertTrue(dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).getTextSize() >= 24 * scale - 1);
                    assertTrue(dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).getTextSize() >= 24 * scale - 1);
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("captureUi"))) {
                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                        root.draw(new android.graphics.Canvas(bitmap));
                        try (var output = new java.io.FileOutputStream(new java.io.File(activity.getCacheDir(), "consent-preview.png"))) {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                        }
                        bitmap.recycle();
                    }
                } catch (Exception failure) { throw new AssertionError(failure); }
                finally { dialog[0].dismiss(); }
            });
        }
    }
}
