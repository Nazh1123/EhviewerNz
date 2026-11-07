package com.hippo.ehviewer.ui.scene.gallery.detail;

import android.app.Application;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.hippo.ehviewer.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryRatingLayoutTest {
    private GalleryRatingLayout row;
    private TextView text;
    private View stars;

    @Before public void setUp() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme);
        View actions = LayoutInflater.from(context).inflate(R.layout.gallery_detail_actions, null);
        row = actions.findViewById(R.id.rating_group);
        text = row.findViewById(R.id.rating_text);
        stars = (View) row.findViewById(R.id.rating).getParent();
    }

    private int dp(int value) {
        return Math.round(value * row.getResources().getDisplayMetrics().density);
    }

    private void measure(int width) {
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }

    private LinearLayout.LayoutParams starParams() {
        return (LinearLayout.LayoutParams) stars.getLayoutParams();
    }

    private int spacing() {
        return row.getPaddingEnd() + starParams().getMarginStart() + starParams().getMarginEnd();
    }

    @Test public void compressionHappensDuringFirstMeasureInTheRequestedOrder() {
        int width = dp(400);
        int available = width - dp(180 + 40 + 18 + 20);
        row.reserveTextWidth(1, available + dp(5));
        measure(width);
        assertEquals(dp(15), row.getPaddingEnd());
        assertEquals(dp(18), starParams().getMarginEnd());
        assertEquals(dp(40), stars.getLeft());

        row.reserveTextWidth(1, available + dp(18));
        measure(width);
        assertEquals(dp(12), row.getPaddingEnd());
        assertEquals(dp(12), starParams().getMarginEnd());
        assertEquals(dp(36), stars.getLeft());

        row.reserveTextWidth(1, available + dp(30));
        measure(width);
        assertEquals(dp(12), row.getPaddingEnd());
        assertEquals(dp(12), starParams().getMarginEnd());
        assertEquals(dp(24), stars.getLeft());

        row.reserveTextWidth(1, available + dp(42));
        measure(width);
        assertEquals(dp(12), row.getPaddingEnd());
        assertEquals(dp(12), starParams().getMarginEnd());
        assertEquals(dp(12), stars.getLeft());
    }

    @Test public void secondStageCompressesAllThreeTogetherWithoutReopeningAnyGap() {
        int width = dp(400);
        int available = width - dp(180 + 40 + 18 + 20);
        row.reserveTextWidth(1, available + dp(42));
        measure(width);
        int previousEnd = row.getPaddingEnd();
        int previousGap = starParams().getMarginEnd();
        int previousStart = stars.getLeft();
        for (int extra = 1; extra <= dp(14); extra++) {
            row.reserveTextWidth(1, available + dp(42) + extra);
            measure(width);
            assertTrue(row.getPaddingEnd() < dp(12));
            assertTrue(starParams().getMarginEnd() < dp(12));
            assertTrue(stars.getLeft() < dp(12));
            assertTrue(row.getPaddingEnd() <= previousEnd);
            assertTrue(starParams().getMarginEnd() <= previousGap);
            assertTrue(stars.getLeft() <= previousStart);
            assertTrue(row.getPaddingEnd() >= dp(8));
            assertTrue(starParams().getMarginEnd() >= dp(6));
            assertTrue(stars.getLeft() >= dp(8));
            assertTrue(text.getWidth() >= available + dp(42) + extra);
            previousEnd = row.getPaddingEnd();
            previousGap = starParams().getMarginEnd();
            previousStart = stars.getLeft();
            if (extra == dp(7)) {
                assertEquals(dp(10), row.getPaddingEnd());
                assertEquals(dp(9), starParams().getMarginEnd());
                assertEquals(dp(10), stars.getLeft());
            }
        }
        assertEquals(dp(8), row.getPaddingEnd());
        assertEquals(dp(6), starParams().getMarginEnd());
        assertEquals(dp(8), stars.getLeft());
    }

    @Test public void extremeTextStopsAtTheThreeLowerBoundsAndCanStillEllipsize() {
        text.setText("Very long rating text (4.97, 2147483647)".repeat(5));
        row.reserveTextWidth(1, text.getPaint().measureText(text.getText().toString()));
        measure(dp(280));
        assertEquals(dp(8), row.getPaddingEnd());
        assertEquals(dp(6), starParams().getMarginEnd());
        assertEquals(dp(8), stars.getLeft());
        assertTrue(text.getLayout().getEllipsisCount(0) > 0);
    }

    @Test public void fittingActualTextKeepsSpacingAndOverflowOnlyCompressesFurther() {
        int width = dp(400);
        int available = width - dp(180 + 40 + 18 + 20);
        row.reserveTextWidth(1, available + dp(5));
        measure(width);
        int initialSpacing = spacing();
        int initialTextStart = text.getLeft();
        row.reserveTextWidth(1, available + dp(3));
        measure(width);
        assertEquals(initialSpacing, spacing());
        assertEquals(initialTextStart, text.getLeft());
        row.reserveTextWidth(1, available + dp(30));
        measure(width);
        assertTrue(spacing() < initialSpacing);
        assertTrue(text.getWidth() >= available + dp(30));
        int compressedSpacing = spacing();
        row.reserveTextWidth(1, available);
        measure(width);
        assertEquals(compressedSpacing, spacing());
    }

    @Test public void newGalleryAndChangedScreenWidthRecomputeFromDefaultSpacing() {
        row.reserveTextWidth(1, dp(180));
        measure(dp(360));
        assertTrue(spacing() < dp(78));
        measure(dp(600));
        assertEquals(dp(78), spacing());
        row.reserveTextWidth(2, dp(30));
        measure(dp(360));
        assertEquals(dp(78), spacing());
    }

    @Test public void offlineNoticeDoesNotUseTheCompressedStarRowSpacing() {
        row.reserveTextWidth(1, dp(500));
        measure(dp(360));
        stars.setVisibility(View.GONE);
        measure(dp(360));
        assertEquals(dp(20), row.getPaddingEnd());
        assertEquals(dp(40), starParams().getMarginStart());
        assertEquals(dp(18), starParams().getMarginEnd());
    }
}
