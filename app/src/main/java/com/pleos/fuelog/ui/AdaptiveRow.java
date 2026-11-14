package com.pleos.fuelog.ui;

import android.content.Context;
import android.widget.LinearLayout;

/** Equal-width cells that stack when their actual container becomes too narrow. */
public final class AdaptiveRow extends LinearLayout {
    private final int minimumCellWidth;

    public AdaptiveRow(Context context, int minimumCellWidthDp) {
        super(context);
        minimumCellWidth = Math.round(minimumCellWidthDp * getResources().getDisplayMetrics().density);
        setBaselineAligned(false);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int required = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() == GONE) continue;
            LayoutParams cell = (LayoutParams) getChildAt(i).getLayoutParams();
            required += minimumCellWidth + cell.leftMargin + cell.rightMargin;
        }
        boolean stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && available < required;
        setOrientation(stacked ? VERTICAL : HORIZONTAL);
        for (int i = 0; i < getChildCount(); i++) {
            LayoutParams cell = (LayoutParams) getChildAt(i).getLayoutParams();
            cell.width = stacked ? LayoutParams.MATCH_PARENT : 0;
            cell.weight = stacked ? 0 : 1;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
