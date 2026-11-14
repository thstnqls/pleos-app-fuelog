package com.pleos.fuelog.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.pleos.fuelog.CarHomeActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WindowInsetsAndroidTest {
    @Test public void keyboardAndSystemBarsShareSpaceWithoutCoveringContent() {
        try (ActivityScenario<CarHomeActivity> scenario = ActivityScenario.launch(CarHomeActivity.class)) {
            scenario.onActivity(activity -> {
                View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                ScrollView scroll = root.findViewWithTag("history.scroll");
                for (int[] size : new int[][]{{840, 1184}, {1184, 840}, {480, 700}}) {
                    for (int keyboard : new int[]{0, 300, 0}) {
                        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48))
                                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard)).build();
                        ViewCompat.dispatchApplyWindowInsets(root, insets);
                        assertEquals(Math.max(48, keyboard), root.getPaddingBottom());
                        root.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
                        root.layout(0, 0, size[0], size[1]);
                        assertTrue(scroll.getHeight() > 0);
                        assertTrue(scroll.getBottom() <= size[1] - Math.max(48, keyboard));
                    }
                }
                ViewCompat.requestApplyInsets(root);
            });
        }
    }
}
