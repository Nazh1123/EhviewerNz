package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hippo.ehviewer.R;

/** Makes room for rating text before children are measured, without a second visible layout. */
public final class GalleryRatingLayout extends LinearLayout {
    private View stars;
    private TextView text;
    private int defaultStart;
    private int defaultGap;
    private int defaultEnd;
    private long galleryId = Long.MIN_VALUE;
    private float requiredTextWidth;

    public GalleryRatingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        stars = (View) findViewById(R.id.rating).getParent();
        text = findViewById(R.id.rating_text);
        LayoutParams params = (LayoutParams) stars.getLayoutParams();
        defaultStart = params.getMarginStart();
        defaultGap = params.getMarginEnd();
        defaultEnd = getPaddingEnd();
    }

    /** A smaller/new count cannot expand spacing again within the same gallery. */
    public void reserveTextWidth(long gid, float width) {
        if (galleryId != gid) {
            galleryId = gid;
            requiredTextWidth = width;
            requestLayout();
        } else if (width > requiredTextWidth) {
            requiredTextWidth = width;
            requestLayout();
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (stars != null) {
            LayoutParams params = (LayoutParams) stars.getLayoutParams();
            int start = defaultStart;
            int gap = defaultGap;
            int end = defaultEnd;
            if (requiredTextWidth > 0 && stars.getVisibility() != GONE
                    && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                LayoutParams textParams = (LayoutParams) text.getLayoutParams();
                int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingStart()
                        - end - params.width - start - gap
                        - textParams.getMarginStart() - textParams.getMarginEnd()
                        - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                int missing = Math.max(0, (int) Math.ceil(requiredTextWidth) - available);
                // First recover spacing in order, keeping all three at or above 12dp.
                int take = Math.min(missing, Math.max(0, end - dp(12)));
                end -= take;
                missing -= take;
                take = Math.min(missing, Math.max(0, gap - dp(12)));
                gap -= take;
                missing -= take;
                take = Math.min(missing, Math.max(0, start - dp(12)));
                start -= take;
                missing -= take;
                if (missing > 0) {
                    // Then move all three together toward their final 8dp/6dp/8dp limits.
                    int endRoom = Math.max(0, end - dp(8));
                    int gapRoom = Math.max(0, gap - dp(6));
                    int startRoom = Math.max(0, start - dp(8));
                    int room = endRoom + gapRoom + startRoom;
                    if (room > 0) {
                        double progress = Math.min(1d, (double) missing / room);
                        // Round reductions up to whole pixels to avoid leaving residual overflow.
                        end -= (int) Math.ceil(endRoom * progress);
                        gap -= (int) Math.ceil(gapRoom * progress);
                        start -= (int) Math.ceil(startRoom * progress);
                    }
                }
            }
            // These are our own children's params, applied before LinearLayout measures them.
            params.setMarginStart(start);
            params.setMarginEnd(gap);
            params.resolveLayoutDirection(getLayoutDirection());
            if (getPaddingEnd() != end) {
                setPaddingRelative(getPaddingStart(), getPaddingTop(), end, getPaddingBottom());
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
