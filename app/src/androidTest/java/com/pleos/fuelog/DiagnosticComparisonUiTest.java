package com.pleos.fuelog;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.pleos.fuelog.ui.DiagnosticReadingView;
import com.google.android.material.tabs.TabLayout;
import com.pleos.fuelog.diagnostics.DriveDiagnosticService;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DiagnosticComparisonUiTest {
    @Test public void driveTabContainsThirteenIndependentQueriesAndSurvivesRecreation() {
        try (ActivityScenario<DiagnosticActivity> scenario = ActivityScenario.launch(DiagnosticActivity.class)) {
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                TabLayout tabs = root.findViewWithTag("diagnostic.tabs");
                assertEquals(4, tabs.getTabCount());
                tabs.selectTab(tabs.getTabAt(2));
                assertEquals(View.GONE, root.findViewWithTag("page.fuel").getVisibility());
                assertEquals(View.GONE, root.findViewWithTag("page.ev").getVisibility());
                assertEquals(View.VISIBLE, root.findViewWithTag("page.drive").getVisibility());
                for (DriveDiagnosticService.Reading item : DriveDiagnosticService.Reading.values()) {
                    assertEquals(1, countTag(root, item.key));
                    assertEquals(1, countTag(root, "query." + item.api));
                    DiagnosticReadingView row = root.findViewWithTag(item.key);
                    assertEquals("Switching tabs must not query automatically", "미조회", row.getReadingState());
                }
                DiagnosticReadingView steering = root.findViewWithTag(DriveDiagnosticService.Reading.STEERING.key);
                steering.render("SDK", "수신", "-12.5 °", 1);
                tabs.selectTab(tabs.getTabAt(0));
                tabs.selectTab(tabs.getTabAt(2));
                assertEquals("Switching tabs must preserve completed readings", "수신", steering.getReadingState());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                TabLayout tabs = root.findViewWithTag("diagnostic.tabs");
                assertEquals(2, tabs.getSelectedTabPosition());
                DiagnosticReadingView steering = root.findViewWithTag(DriveDiagnosticService.Reading.STEERING.key);
                assertEquals("Recreation must clear sensor values", "미조회", steering.getReadingState());
            });
        }
    }

    @Test public void categoriesHaveIndependentRowsAndFuelHasFiveIndividualQueries() {
        try (ActivityScenario<DiagnosticActivity> scenario = ActivityScenario.launch(DiagnosticActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                View ev = root.findViewWithTag("energy.ev");
                View fuel = root.findViewWithTag("energy.fuel");
                assertNotNull(ev);
                assertNotNull(fuel);
                assertNotSame(ev, fuel);
                assertNotSame(ev.getParent(), fuel.getParent());
                View standardFuel = root.findViewWithTag("energy.fuel.aaos");
                assertNotNull(standardFuel);
                for (String key : new String[]{"aaos.fuel", "aaos.capacity", "aaos.fuel.percent", "aaos.fuel.instant"}) {
                    assertNotNull(standardFuel.findViewWithTag(key));
                    assertNull("SDK results must remain separate: " + key, fuel.findViewWithTag(key));
                }
                for (com.pleos.fuelog.diagnostics.FuelDiagnosticService.Reading item
                        : com.pleos.fuelog.diagnostics.FuelDiagnosticService.Reading.values()) {
                    assertEquals(1, countTag(root, "query." + item.api));
                    assertNotNull(fuel.findViewWithTag(item.key));
                    assertNull(ev.findViewWithTag(item.key));
                }
                for (String key : new String[]{"ev.soc", "ev.current", "ev.cumulative", "ev.instant", "ev.charge",
                        "fuel.percent", "fuel.current", "fuel.cumulative", "fuel.instant", "fuel.refill",
                        "aaos.fuel", "aaos.capacity", "aaos.fuel.percent", "aaos.fuel.instant",
                        "aaos.ev.energy", "aaos.ev.usable", "aaos.ev.nominal",
                        "distance.total", "aaos.odometer", "fuel.stream", "ev.stream", "distance.stream"}) {
                    assertEquals("Metric must have one independent row: " + key, 1, countTag(root, key));
                    assertTrue(root.findViewWithTag(key) instanceof DiagnosticReadingView);
                }
            });
        }
    }

    @Test public void summaryAndGlobalQueryFollowSelectedCategoryWithoutQueryingPersonalData() {
        try (ActivityScenario<DiagnosticActivity> scenario = ActivityScenario.launch(DiagnosticActivity.class)) {
            scenario.onActivity(activity -> {
                ViewGroup root = activity.findViewById(android.R.id.content);
                TabLayout tabs = root.findViewWithTag("diagnostic.tabs");
                DiagnosticReadingView fuel = root.findViewWithTag("fuel.percent");
                DiagnosticReadingView ev = root.findViewWithTag("ev.soc");
                fuel.render("SDK", "수신", "0 %", 1);
                ev.render("SDK", "미지원", "ERROR_NOT_SUPPORTED_PROPERTY", 1);
                tabs.selectTab(tabs.getTabAt(1));
                TextView summary = root.findViewWithTag("diagnostic.summary");
                TextView query = root.findViewWithTag("query.selected");
                assertTrue(summary.getText().toString().contains("수신 0"));
                assertTrue(summary.getText().toString().contains("미지원/설정 확인 1"));
                assertEquals("전기 전체 조회", query.getText().toString());
                tabs.selectTab(tabs.getTabAt(0));
                assertTrue(summary.getText().toString().contains("수신 1"));
                assertTrue(summary.getText().toString().contains("미지원/설정 확인 0"));
                assertEquals("연료 전체 조회", query.getText().toString());
                tabs.selectTab(tabs.getTabAt(3));
                assertEquals(View.GONE, query.getVisibility());
                for (String key : new String[]{"vehicle.vin", "location.last", "location.current"}) {
                    DiagnosticReadingView personal = root.findViewWithTag(key);
                    assertEquals("미조회", personal.getReadingState());
                }
                assertNotNull(root.findViewWithTag("query.location.current"));
                assertNotNull(root.findViewWithTag("query.location.last"));
                for (int i = 0; i < 4; i++) {
                    tabs.selectTab(tabs.getTabAt(i));
                    for (int page = 0; page < 4; page++) {
                        String tag = new String[]{"page.fuel", "page.ev", "page.drive", "page.info"}[page];
                        assertEquals(page == i ? View.VISIBLE : View.GONE, root.findViewWithTag(tag).getVisibility());
                    }
                }
            });
        }
    }

    @Test public void zeroIsAReadingAndFailuresNeverRetainAnOldValue() {
        try (ActivityScenario<DiagnosticActivity> scenario = ActivityScenario.launch(DiagnosticActivity.class)) {
            scenario.onActivity(activity -> {
                DiagnosticReadingView row = new DiagnosticReadingView(activity, "배터리 충전율",
                        "Pleos · getEvBatteryStateOfCharge");
                row.render("SDK", "수신", "0 % · 접근 READ", 1);
                assertEquals("수신", row.getReadingState());
                assertTrue(texts(row).contains("0 %"));
                row.render("SDK", "미지원", "ERROR_NOT_SUPPORTED_PROPERTY", 2);
                assertFalse(texts(row).contains("0 %"));
                assertTrue(texts(row).contains("ERROR_NOT_SUPPORTED_PROPERTY"));
                row.reset();
                assertEquals("미조회", row.getReadingState());
                assertFalse(texts(row).contains("ERROR_NOT_SUPPORTED_PROPERTY"));
                assertFalse(texts(row).contains("0 %"));
            });
        }
    }

    @Test public void longDiagnosticsWrapInsideANarrowReadingRow() {
        try (ActivityScenario<DiagnosticActivity> scenario = ActivityScenario.launch(DiagnosticActivity.class)) {
            scenario.onActivity(activity -> {
                DiagnosticReadingView row = new DiagnosticReadingView(activity, "현재 사용 가능 배터리 용량",
                        "AAOS · EV_CURRENT_BATTERY_CAPACITY · Wh");
                row.render("AAOS 0x1160030d", "권한 오류", "ERROR_SECURITY_PERMISSION", 1);
                int width = Math.round(280 * activity.getResources().getDisplayMetrics().density);
                row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, width, row.getMeasuredHeight());
                assertTrue(row.getMeasuredHeight() > 0);
                assertTextBounds(row);
            });
        }
    }

    private int countTag(View view, String tag) {
        int count = tag.equals(view.getTag()) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countTag(group.getChildAt(i), tag);
        }
        return count;
    }

    private List<String> texts(View view) {
        List<String> values = new ArrayList<>();
        if (view instanceof TextView) values.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) values.addAll(texts(group.getChildAt(i)));
        }
        return values;
    }

    private void assertTextBounds(View view) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < text.getLineCount(); line++) {
                assertTrue("Text exceeds its row", text.getLayout().getLineWidth(line) <= available + 2);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTextBounds(group.getChildAt(i));
        }
    }
}
