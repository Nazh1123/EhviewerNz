package com.hippo.ehviewer.widget;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.Gravity;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.view.ViewCompat;
import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.SearchPresetQuery;
import com.hippo.ehviewer.client.SearchQueryTokenizer;
import java.util.function.IntConsumer;

/** Shares search-input chip rendering; the query owns the state, never the view. */
public final class SearchPresetChip extends AppCompatTextView {
    private final String preset;
    private final IntConsumer onChange;
    private final float density;
    private int state;
    private boolean operatorPressed;
    private int modeAction;

    public SearchPresetChip(Context context, String preset, IntConsumer onChange) {
        super(context);
        this.preset = preset;
        this.onChange = onChange;
        density = getResources().getDisplayMetrics().density;
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.text_little_small));
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setSingleLine(true);
        // The parent owns horizontal scrolling; keep centered text inside the measured chip.
        setHorizontallyScrolling(false);
        setMinHeight(Math.round(48 * density));
        setSaveFromParentEnabled(false);
        setOnClickListener(view -> {
            boolean changeMode = operatorPressed;
            operatorPressed = false;
            onChange.accept(changeMode ? nextState()
                    : state == SearchPresetQuery.NORMAL ? SearchPresetQuery.INCLUDE : SearchPresetQuery.NORMAL);
        });
        setState(SearchPresetQuery.NORMAL);
    }

    public static String labelFor(Context context, String preset) {
        return SearchTagChipSpan.labelFor(SearchQueryTokenizer.parseTag(preset, true),
                Settings.getShowTagTranslations() ? EhTagDatabase.getInstance(context) : null);
    }

    public String getPreset() { return preset; }
    public int getState() { return state; }

    private int nextState() {
        return switch (state) {
            case SearchPresetQuery.INCLUDE -> SearchPresetQuery.EXCLUDE;
            case SearchPresetQuery.EXCLUDE -> SearchPresetQuery.OR;
            default -> SearchPresetQuery.INCLUDE;
        };
    }

    public void setState(int state) {
        this.state = state;
        char operator = state == SearchPresetQuery.EXCLUDE ? '-' : state == SearchPresetQuery.OR ? '~' : '+';
        String label = labelFor(getContext(), preset);
        SpannableString text = new SpannableString("\uFFFC");
        // The row scrolls horizontally, so long labels can retain their complete text.
        text.setSpan(new SearchTagChipSpan(label, operator, density,
                        AttrResources.getAttrColor(getContext(), androidx.appcompat.R.attr.colorAccent),
                        Float.MAX_VALUE, state != SearchPresetQuery.NORMAL),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText(text);
        int stateLabel = state == SearchPresetQuery.NORMAL ? R.string.search_preset_absent
                : state == SearchPresetQuery.EXCLUDE ? R.string.search_preset_exclude
                : state == SearchPresetQuery.OR ? R.string.search_tag_or : R.string.search_preset_include;
        setContentDescription(label + ", " + getContext().getString(stateLabel));
        int actionLabel = state == SearchPresetQuery.NORMAL ? R.string.search_preset_add : R.string.search_tag_delete;
        ViewCompat.replaceAccessibilityAction(this,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getContext().getString(actionLabel, label), (view, args) -> {
                    operatorPressed = false;
                    return performClick();
                });
        if (modeAction != 0) ViewCompat.removeAccessibilityAction(this, modeAction);
        int nextLabel = nextState() == SearchPresetQuery.EXCLUDE ? R.string.search_preset_exclude
                : nextState() == SearchPresetQuery.OR ? R.string.search_tag_or : R.string.search_preset_include;
        modeAction = ViewCompat.addAccessibilityAction(this,
                getContext().getString(R.string.search_tag_change_mode, label, getContext().getString(nextLabel)),
                (view, args) -> { onChange.accept(nextState()); return true; });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            operatorPressed = event.getX() < 20 * density;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            operatorPressed = false;
        }
        return super.onTouchEvent(event);
    }
}
