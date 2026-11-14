package com.pleos.fuelog.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AdaptiveRowAndroidTest {
    @Test public void cellsReflowWithinTheirContainerAndKeepContentOnResize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AdaptiveRow row = new AdaptiveRow(InstrumentationRegistry.getInstrumentation().getTargetContext(), 400);
            for (int i = 0; i < 2; i++) {
                TextView cell = new TextView(row.getContext());
                cell.setText("입력 중인 값 " + i);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMargins(4, 0, 4, 12);
                row.addView(cell, params);
            }
            float density = row.getResources().getDisplayMetrics().density;
            // 840px portrait content, narrow split window, then landscape and back.
            for (int width : new int[]{768, 320, 1112, 1600, 768}) {
                int pixels = Math.round(width * density);
                row.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, pixels, row.getMeasuredHeight());
                assertEquals(width < 808 ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL, row.getOrientation());
                for (int i = 0; i < row.getChildCount(); i++) {
                    View cell = row.getChildAt(i);
                    assertTrue(cell.getWidth() > 0);
                    assertTrue(cell.getRight() <= pixels);
                    assertTrue(cell.getBottom() <= row.getHeight());
                    assertEquals("입력 중인 값 " + i, ((TextView) cell).getText().toString());
                }
                assertEquals(width < 808, row.getChildAt(1).getTop() > row.getChildAt(0).getTop());
            }
        });
    }
}
