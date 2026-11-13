package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Paint;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.RatingBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.SearchRating;
import java.util.function.IntConsumer;

/** Shared stars and exact minimum-rating input for search and rating sort. */
public final class RatingFilterControl {
    private final Context context;
    private final RatingBar mRatingStars;
    private final TextView mRatingValue;
    private final IntConsumer onChanged;
    private int minimumRating;

    public RatingFilterControl(View root, int initialRating, IntConsumer onChanged) {
        context = root.getContext();
        this.onChanged = onChanged;
        mRatingStars = root.findViewById(R.id.search_rating_stars);
        mRatingStars.setStepSize(0.01f);
        mRatingStars.setKeyProgressIncrement(50);
        mRatingValue = root.findViewById(R.id.search_rating_value);
        mRatingValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, mRatingValue.getTextSize()
                + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 2, context.getResources().getDisplayMetrics()));
        mRatingValue.setPaintFlags(mRatingValue.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        mRatingValue.setOnClickListener(v -> showRatingInput());
        mRatingStars.setOnRatingBarChangeListener((bar, rating, fromUser) -> {
            if (fromUser) changeRating(Math.round(rating * 2) * 50);
        });
        setMinimumRating(initialRating);
    }

    public int getMinimumRating() { return minimumRating; }

    public void setMinimumRating(int hundredths) {
        minimumRating = Math.max(0, Math.min(500, hundredths));
        // Preserve exact input while touch interaction selects half stars.
        mRatingStars.setRating(minimumRating / 100f);
        mRatingValue.setText(SearchRating.format(minimumRating));
    }

    private void changeRating(int hundredths) {
        setMinimumRating(hundredths);
        onChanged.accept(minimumRating);
    }

    private void showRatingInput() {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setHint(R.string.search_rating_hint);
        input.setText(mRatingValue.getText());
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.search_rating_title)
                .setMessage(R.string.search_rating_hint)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            View.OnClickListener confirm = v -> {
                try {
                    changeRating(SearchRating.parse(input.getText().toString()));
                    dialog.dismiss();
                } catch (IllegalArgumentException e) {
                    input.setError(context.getString(R.string.search_rating_invalid));
                }
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(confirm);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                confirm.onClick(v);
                return true;
            });
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }
}
