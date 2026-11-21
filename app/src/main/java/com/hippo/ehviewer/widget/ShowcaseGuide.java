package com.hippo.ehviewer.widget;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.text.Layout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.StringRes;

import com.github.amlcurran.showcaseview.ShowcaseView;
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener;
import com.github.amlcurran.showcaseview.targets.ViewTarget;
import com.hippo.ehviewer.R;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.function.Supplier;

/** Waits for a menu row or list heading to be laid out before showing the standard guide. */
public final class ShowcaseGuide {
    private final View host;
    private final ViewTreeObserver.OnPreDrawListener preDraw;
    private final Rect visibleBounds = new Rect();
    private ShowcaseView showcase;

    public ShowcaseGuide(Activity activity, View host, Supplier<View> findTarget,
            @StringRes int title, CharSequence text, Runnable complete) {
        this.host = host;
        preDraw = () -> {
            View target = findTarget.get();
            if (target != null && target.isShown() && target.getGlobalVisibleRect(visibleBounds)
                    && visibleBounds.height() == target.getHeight()) {
                show(activity, target, title, text, complete);
            }
            return true;
        };
        host.getViewTreeObserver().addOnPreDrawListener(preDraw);
        host.invalidate();
    }

    public boolean isShowing() {
        return showcase != null;
    }

    public void dismiss() {
        host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        if (showcase != null) {
            ViewUtils.removeFromParent(showcase);
            showcase = null;
        }
    }

    private void show(Activity activity, View target, @StringRes int title, CharSequence text,
            Runnable complete) {
        host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        showcase = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(() -> getTargetPoint(target))
                .blockAllTouches()
                .setContentTitle(title)
                .setContentText(text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override public void onShowcaseViewDidHide(ShowcaseView view) {
                        if (view == showcase) {
                            dismiss();
                            complete.run();
                        }
                    }
                })
                .build();
    }

    private static Point getTargetPoint(View view) {
        Point center = new ViewTarget(view).getPoint();
        if (view instanceof TextView textView) {
            Layout layout = textView.getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                // The label can occupy only a small portion of a full-width row.
                center.x += textView.getCompoundPaddingLeft() - view.getWidth() / 2
                        + Math.round((layout.getLineLeft(0) + layout.getLineRight(0)) / 2f);
            }
        }
        return center;
    }
}
