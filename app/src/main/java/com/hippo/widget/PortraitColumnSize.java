package com.hippo.widget;

import android.content.res.Configuration;
import android.content.res.Resources;

/** Converts a portrait column count into a fixed width, reused when the page gets wider. */
public final class PortraitColumnSize {
    private int columns;
    private int width;

    public void setColumns(int columns) {
        if (columns < 0 || columns > 8) throw new IllegalArgumentException("Invalid portrait columns");
        this.columns = columns;
        width = 0;
    }

    public int getColumns() {
        return columns;
    }

    public int resolve(Resources resources, int layoutWidth, int horizontalPadding) {
        if (columns == 0 || layoutWidth <= horizontalPadding) return 0;
        if (width == 0) {
            Configuration config = resources.getConfiguration();
            float density = resources.getDisplayMetrics().density;
            int windowWidth = Math.round(config.screenWidthDp * density);
            int windowHeight = Math.round(config.screenHeightDp * density);
            int portraitWidth = layoutWidth;
            if (windowWidth > windowHeight && windowHeight > 0) {
                // smallestScreenWidthDp remains stable across activity recreation on rotation.
                // Landscape height can be shorter because of system bars, so prefer this
                // portrait baseline and keep the same page margins.
                int portraitWindowWidth = config.smallestScreenWidthDp > 0
                        ? Math.round(config.smallestScreenWidthDp * density) : windowHeight;
                int outerMargins = Math.max(0, windowWidth - layoutWidth);
                portraitWidth = Math.min(layoutWidth, Math.max(1, portraitWindowWidth - outerMargins));
            }
            width = Math.max(1, Math.round(Math.max(1, portraitWidth - horizontalPadding)
                    / (float) columns));
        }
        return width;
    }
}
