package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import java.util.function.IntConsumer;

/** Supplies the available width before RecyclerView measures or creates thumbnail holders. */
public final class ReaderPreviewRecyclerView extends RecyclerView {
    private IntConsumer viewportWidthListener;
    private int previewHeight;

    public ReaderPreviewRecyclerView(Context context) { super(context); }
    public ReaderPreviewRecyclerView(Context context, AttributeSet attrs) { super(context, attrs); }
    public ReaderPreviewRecyclerView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); }

    public void setViewportWidthListener(@Nullable IntConsumer listener) { viewportWidthListener = listener; }

    public void setPreviewHeight(int height) { previewHeight = height; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (viewportWidthListener != null && width > 0) viewportWidthListener.accept(width);
        // Every thumbnail has the same height. A wrap-content auto-measure would otherwise
        // lay out the adapter during both measurement and layout when the popup is shown.
        if (previewHeight > 0) {
            int height = previewHeight;
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
                height = Math.min(height, MeasureSpec.getSize(heightSpec));
            heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
