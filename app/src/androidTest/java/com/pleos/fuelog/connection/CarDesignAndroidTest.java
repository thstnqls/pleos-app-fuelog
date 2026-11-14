package com.pleos.fuelog.connection;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.CarHomeActivity;
import com.pleos.fuelog.R;
import com.pleos.fuelog.api.ApiClient;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CarDesignAndroidTest {
    @Test public void connectionStatesStayInlineAndConfirmationKeepsItsCode() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "design-test-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    ConnectionSession session = new ConnectionSession(isolated, new ApiClient("https://example.invalid/"));
                    LinearLayout body = new LinearLayout(activity);
                    body.setOrientation(LinearLayout.VERTICAL);
                    body.setBackgroundColor(activity.getColor(R.color.car_background));
                    Object previousConnection = field("connection").get(activity);
                    Object previousBody = field("body").get(activity);
                    int previousPage = field("page").getInt(activity);
                    try {
                        field("connection").set(activity, session);
                        field("body").set(activity, body);
                        field("page").setInt(activity, 6);
                        invoke(activity, "renderApiStatus");
                        assertTrue(texts(body).contains("연결 확인 대기"));
                        body.removeAllViews();
                        long attempt = session.apiStatus().begin("POST", "installations");
                        session.apiStatus().complete("POST", "installations", attempt, 503);
                        invoke(activity, "renderApiStatus");
                        assertTrue(texts(body).contains("연결 오류"));
                        assertTrue(texts(body).contains("저장된 기록은 유지됩니다"));
                        assertNull(field("apiAlert").get(activity));
                        body.removeAllViews();
                        session.vehicleAlias = "테스트 차량";
                        session.receivedVin = "invalid";
                        session.vinStatus = "공통 식별키 invalid 사용 · 차량별 고유값 아님";
                        session.state = "AWAITING_CONFIRMATION";
                        session.confirmationCode = "123456";
                        session.selectedVehicleName = "테스트 차량";
                        Field deadline = ConnectionSession.class.getDeclaredField("deadline");
                        deadline.setAccessible(true);
                        deadline.setLong(session, SystemClock.elapsedRealtime() + 120000);
                        invoke(activity, "renderConnect");
                        assertTrue(texts(body).contains("123456"));
                        assertTrue(texts(body).contains("차량에서 바로 승인"));
                        assertTrue(texts(body).contains("VIN 수신 완료"));
                        assertFalse(texts(body).contains("invalid"));
                        assertFalse(texts(body).contains("차량별 고유값"));
                        assertFalse(texts(body).contains("연결 상세 확인"));
                        assertNotNull(body.findViewWithTag("start.qr"));
                        assertNotNull(body.findViewWithTag("start.vehicle"));
                        assertNull(body.findViewWithTag("start.tabs"));
                        snapshot(activity, body, "fuelog-pairing-preview.png");
                        View left = body.findViewWithTag("start.qr"), right = body.findViewWithTag("start.vehicle");
                        if (((LinearLayout) left.getParent()).getOrientation() == LinearLayout.HORIZONTAL) {
                            assertTrue(left.getLeft() < right.getLeft());
                            assertEquals(left.getTop(), right.getTop());
                        } else {
                            assertTrue(left.getTop() < right.getTop());
                            assertEquals(left.getLeft(), right.getLeft());
                        }
                        session.vehicleAlias = "";
                        session.receivedVin = "1HGCM82633A004352";
                        body.removeAllViews(); invoke(activity, "renderConnect");
                        assertFalse(texts(body).contains(session.receivedVin));
                        View form = body.findViewWithTag("pairing.aliasForm");
                        assertNotNull(form);
                        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, form.getLayoutParams().width);
                        assertNotNull(form.findViewWithTag("pairing.issue"));
                        Object previousCall = field("maintenanceCall").get(activity);
                        try {
                            field("maintenanceCall").set(activity, new okhttp3.OkHttpClient().newCall(
                                    new okhttp3.Request.Builder().url("https://example.invalid/").build()));
                            body.removeAllViews(); invoke(activity, "renderConnect");
                            android.widget.Button refresh = body.findViewWithTag("maintenance.refresh");
                            assertEquals("정비 정보 갱신", refresh.getText().toString());
                            assertFalse(refresh.isEnabled());
                            assertEquals("정비 정보 갱신 중", refresh.getContentDescription().toString());
                            assertTrue(refresh.getCompoundDrawablesRelative()[0] instanceof android.graphics.drawable.AnimatedVectorDrawable);
                        } finally { field("maintenanceCall").set(activity, previousCall); }
                        session.receivedVin = "";
                        assertEquals("VIN 미수신", invokeResult(activity, "vinReceiptStatus"));
                    } finally {
                        field("connection").set(activity, previousConnection);
                        field("body").set(activity, previousBody);
                        field("page").setInt(activity, previousPage);
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private static Field field(String name) throws Exception {
        Field field = CarHomeActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invokeResult(CarHomeActivity activity, String name) throws Exception {
        Method method = CarHomeActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(activity);
    }

    private static void invoke(CarHomeActivity activity, String name) throws Exception {
        Method method = CarHomeActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static String texts(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            text.append(texts(((ViewGroup) view).getChildAt(i))).append('\n');
        return text.toString();
    }

    private static void snapshot(Context context, View view, String name) throws Exception {
        int width = Math.round(context.getResources().getConfiguration().screenWidthDp * context.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }
}
