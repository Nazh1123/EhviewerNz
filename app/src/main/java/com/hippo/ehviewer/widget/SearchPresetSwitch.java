package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.SearchPresetQuery;

import java.util.function.IntConsumer;

/** A fixed left/exclude, middle/normal, right/include control. */
public class SearchPresetSwitch extends LinearLayout {
    private final TextView label;
    private final TriStateSwitch states;

    public SearchPresetSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        inflate(context, R.layout.search_preset_switch, this);
        label = findViewById(R.id.preset_label);
        states = findViewById(R.id.preset_states);
        // Each instance lives in the same hierarchy; query text is the source of state.
        setSaveFromParentEnabled(false);
    }

    public void setLabel(String text) {
        label.setText(text);
        states.setContentDescription(text);
    }

    public void setState(int state) {
        if (state != states.getState()) states.setState(state);
    }

    public void setOnStateChangedListener(IntConsumer listener) {
        states.setOnStateChangedListener(listener);
    }

    public static String labelFor(Context context, String preset) {
        String identity = SearchPresetQuery.identity(preset);
        if (identity == null) return preset;
        int resId = switch (identity) {
            case "language:japanese" -> R.string.language_japanese;
            case "language:english" -> R.string.language_english;
            case "language:chinese" -> R.string.language_chinese;
            case "language:dutch" -> R.string.language_dutch;
            case "language:french" -> R.string.language_french;
            case "language:german" -> R.string.language_german;
            case "language:hungarian" -> R.string.language_hungarian;
            case "language:italian" -> R.string.language_italian;
            case "language:korean" -> R.string.language_korean;
            case "language:polish" -> R.string.language_polish;
            case "language:portuguese" -> R.string.language_portuguese;
            case "language:russian" -> R.string.language_russian;
            case "language:spanish" -> R.string.language_spanish;
            case "language:thai" -> R.string.language_thai;
            case "language:vietnamese" -> R.string.language_vietnamese;
            case "language:other" -> R.string.language_other;
            case "other:ai generated" -> R.string.search_preset_ai_generated;
            default -> 0;
        };
        return resId == 0 ? preset : context.getString(resId);
    }
}
