/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.preference.PreferenceManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.SearchQueryTokenizer;
import com.hippo.ehviewer.client.SearchQueryTokenizer.Term;
import com.hippo.ehviewer.client.SearchQueryTokenizer.Tag;
import com.hippo.util.ExceptionUtils;

import java.util.HashSet;
import java.util.Set;

public class SearchEditText extends AppCompatEditText {

    private SearchEditTextListener mListener;
    private final Object editingTag = new Object();
    private boolean ready;
    private boolean changing;
    private boolean adjustingSelection;
    private boolean composing;
    private boolean chipsEnabled = true;
    private boolean multilineChips;
    private SearchFirstRow firstRow;
    private boolean firstRowFontPadding;
    private int originalPaddingTop;
    private int originalPaddingBottom;
    private int originalGravity;
    private final float originalLineSpacingExtra;
    private final float originalLineSpacingMultiplier;
    private final boolean originalFallbackLineSpacing;
    private final boolean originalLocaleLineHeight;
    private boolean updatingRows;
    private boolean drawingText;
    private boolean scrollingChip;
    private SearchTagChipSpan touchedChip;
    private MotionEvent touchDown;
    private int touchScrollX;
    private int touchScrollY;
    private boolean moved;
    private int insertedCount;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener translationListener = (prefs, key) -> {
        if (Settings.KEY_ENABLE_SEARCH_TAG_CHIPS.equals(key)) updateTagChipMode();
        else if (Settings.KEY_SHOW_TAG_TRANSLATIONS.equals(key)) refreshTagChips(false);
    };
    private final Runnable refreshAfterSelection = () -> {
        refreshTagChips(false);
        if (mListener != null) mListener.onCursorChanged();
    };

    public SearchEditText(Context context) {
        this(context, null);
    }

    public SearchEditText(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.appcompat.R.attr.editTextStyle);
    }

    public SearchEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        originalLineSpacingExtra = getLineSpacingExtra();
        originalLineSpacingMultiplier = getLineSpacingMultiplier();
        originalFallbackLineSpacing = Build.VERSION.SDK_INT >= 28 && isFallbackLineSpacing();
        originalLocaleLineHeight = Build.VERSION.SDK_INT >= 35 && isLocalePreferredLineHeightForMinimumUsed();
        ready = true;
        updateTagChipMode();
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (updatingRows) return;
                changing = true;
                insertedCount = after;
                // A keyboard or paste may replace part of a chip without going through touch.
                Editable text = getText();
                if (text == null) return;
                for (SearchTagChipSpan chip : chips()) {
                    int a = text.getSpanStart(chip), b = text.getSpanEnd(chip);
                    if (start < b && start + count > a || count == 0 && start > a && start < b) {
                        text.removeSpan(chip);
                    }
                }
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (updatingRows) return;
                changing = false;
                refreshTagChips(insertedCount > 1 && !composing && text.getSpanStart(editingTag) < 0);
                scheduleRefresh();
            }
        });
    }

    /** Programmatic queries (history, presets, restored searches) are already committed. */
    public void setQueryText(String query) {
        composing = false;
        updatingRows = true;
        scrollTo(0, 0);
        updatingRows = false;
        if (getText() != null) getText().removeSpan(editingTag);
        setText(query);
        refreshTagChips(true);
    }

    public void setTagChipsEnabled(boolean enabled) {
        chipsEnabled = enabled;
        updateTagChipMode();
    }

    public void setFirstRowInsets(int left, int right) {
        if (firstRow == null) {
            firstRowFontPadding = getIncludeFontPadding();
            originalPaddingTop = getPaddingTop();
            originalPaddingBottom = getPaddingBottom();
            originalGravity = getGravity();
        }
        if (firstRow != null && getText() != null) firstRow.clear(getText());
        firstRow = new SearchFirstRow(left, right);
        updateTagChipMode();
    }

    private void updateFirstRowLayout() {
        if (updatingRows || firstRow == null || !multilineChips) return;
        updatingRows = true;
        try {
            setGravity((originalGravity & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.TOP);
            firstRow.update(this);
            // Rebuild only when the query or its display changes, never while scrolling.
            setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
            requestLayout();
            invalidate();
        } finally {
            updatingRows = false;
        }
    }

    private void updateTagChipMode() {
        updatingRows = true;
        multilineChips = chipsEnabled && Settings.getEnableSearchTagChips();
        setSingleLine(!multilineChips);
        setMaxLines(multilineChips ? 4 : 1);
        setHorizontallyScrolling(!multilineChips);
        setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        if (Build.VERSION.SDK_INT >= 35) {
            // Locale minimum metrics can enlarge only the first paragraph. Use the same
            // native font metrics on every chip row, restoring the default in plain mode.
            setLocalePreferredLineHeightForMinimumUsed(!multilineChips && originalLocaleLineHeight);
        }
        Paint.FontMetricsInt metrics = rowFontMetrics();
        int fontHeight = metrics.descent - metrics.ascent;
        float density = getResources().getDisplayMetrics().density;
        // Native cursors exclude lineSpacingExtra, unlike enlarged font metrics / LineHeightSpan.
        setLineSpacing(multilineChips ? Math.max(0, SearchTagChipSpan.ROW_HEIGHT_DP * density - fontHeight)
                : originalLineSpacingExtra, multilineChips ? 1 : originalLineSpacingMultiplier);
        if (Build.VERSION.SDK_INT >= 28) {
            setFallbackLineSpacing(!multilineChips && originalFallbackLineSpacing);
        }
        if (firstRow != null) {
            setIncludeFontPadding(multilineChips ? false : firstRowFontPadding);
            if (getText() != null) firstRow.clear(getText());
            int center = Math.round(-metrics.ascent + (getPaint().ascent() + getPaint().descent()) / 2);
            int top = Math.max(0, Math.round(Math.max(getMinimumHeight(),
                    SearchTagChipSpan.CHIP_HEIGHT_DP * density) / 2) - center);
            int overhang = Math.max(0, (int) Math.ceil(SearchTagChipSpan.CHIP_HEIGHT_DP * density / 2
                    + center - fontHeight));
            setPadding(firstRow.left, multilineChips ? top : originalPaddingTop,
                    firstRow.right, multilineChips ? overhang + Math.round(2 * density) : originalPaddingBottom);
            setGravity(originalGravity);
            if (multilineChips) setTransformationMethod(firstRow);
        }
        updatingRows = false;
        refreshTagChips(true);
    }

    private SearchTagChipSpan[] chips() {
        Editable text = getText();
        return text == null ? new SearchTagChipSpan[0]
                : text.getSpans(0, text.length(), SearchTagChipSpan.class);
    }

    private Paint.FontMetricsInt rowFontMetrics() {
        Paint.FontMetricsInt metrics = getPaint().getFontMetricsInt();
        if (Build.VERSION.SDK_INT >= 35) {
            Paint.FontMetrics minimum = getMinimumFontMetrics();
            if (minimum != null) {
                metrics.ascent = Math.min(metrics.ascent, (int) Math.floor(minimum.ascent));
                metrics.descent = Math.max(metrics.descent, (int) Math.ceil(minimum.descent));
            }
        }
        return metrics;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (!multilineChips || firstRow == null || getLayout() == null) return;
        int last = Math.min(4, getLayout().getLineCount()) - 1;
        Paint.FontMetricsInt metrics = rowFontMetrics();
        // maxLines otherwise includes trailing line spacing when more text is below the viewport.
        int height = getPaddingTop() + getLayout().getLineBaseline(last) + metrics.descent + getPaddingBottom();
        setMeasuredDimension(getMeasuredWidthAndState(), resolveSizeAndState(
                Math.max(getSuggestedMinimumHeight(), height), heightSpec, 0));
    }

    @Override protected void onDraw(Canvas canvas) {
        drawingText = true;
        try {
            super.onDraw(canvas);
        } finally {
            drawingText = false;
        }
    }

    @Override public int getExtendedPaddingBottom() {
        int padding = super.getExtendedPaddingBottom();
        // Let chip backgrounds extend beyond the normal font box; native scrolling still
        // uses the full padding so the last bubble can scroll completely into view.
        return drawingText && multilineChips && firstRow != null
                ? Math.min(padding, Math.round(2 * getResources().getDisplayMetrics().density)) : padding;
    }

    /** Changes spans only, so search listeners and the query history always see the raw query. */
    public void refreshTagChips(boolean commitCurrent) {
        if (!ready || changing || updatingRows) return;
        Editable text = getText();
        if (text == null) return;
        SearchTagChipSpan[] old = chips();
        if (!chipsEnabled || !Settings.getEnableSearchTagChips()) {
            for (SearchTagChipSpan chip : old) text.removeSpan(chip);
            text.removeSpan(editingTag);
            requestLayout();
            invalidate();
            return;
        }
        if (composing || BaseInputConnection.getComposingSpanStart(text) >= 0) {
            updateFirstRowLayout();
            return;
        }
        int caret = getSelectionEnd();
        int editStart = text.getSpanStart(editingTag), editEnd = text.getSpanEnd(editingTag);
        if (commitCurrent || caret < editStart || caret > editEnd) {
            text.removeSpan(editingTag);
            editStart = editEnd = -1;
        }
        EhTagDatabase database = Settings.getShowTagTranslations()
                ? EhTagDatabase.getInstance(getContext()) : null;
        float density = getResources().getDisplayMetrics().density;
        int accent = AttrResources.getAttrColor(getContext(), androidx.appcompat.R.attr.colorAccent);
        float contentWidth = getWidth() > 0
                ? getWidth() - (firstRow != null && multilineChips ? 0
                : getTotalPaddingLeft() + getTotalPaddingRight()) : 300 * density;
        float maxWidth = Math.max(0,
                Math.min(200 * density, contentWidth - SearchTagChipSpan.horizontalSpace(density)));
        Set<Term> committedRanges = new HashSet<>();
        for (SearchTagChipSpan chip : old) {
            committedRanges.add(new Term(text.getSpanStart(chip), text.getSpanEnd(chip)));
            text.removeSpan(chip);
        }
        for (Term term : SearchQueryTokenizer.terms(text)) {
            Tag tag = SearchQueryTokenizer.parseTag(text.subSequence(term.start(), term.end()).toString(), false);
            if (tag == null) continue;
            // Committed chips stay committed even when the caret is next to them.
            boolean existed = committedRanges.contains(term);
            boolean editing = editStart >= 0 && term.start() <= editEnd && term.end() >= editStart;
            boolean atCaret = caret >= term.start() && caret <= term.end();
            if (!commitCurrent && (editing || atCaret && !existed && hasFocus())) continue;
            String label = SearchTagChipSpan.labelFor(tag, database);
            float labelWidth = firstRow != null && term.start() == 0
                    ? Math.max(0, Math.min(maxWidth, contentWidth - firstRow.left - firstRow.right
                    - SearchTagChipSpan.horizontalSpace(density))) : maxWidth;
            text.setSpan(new SearchTagChipSpan(label, tag.operator(), density, accent, labelWidth),
                    term.start(), term.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        requestLayout();
        invalidate();
        updateFirstRowLayout();
    }

    private void scheduleRefresh() {
        removeCallbacks(refreshAfterSelection);
        post(refreshAfterSelection);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        preferences.registerOnSharedPreferenceChangeListener(translationListener);
        updateTagChipMode();
    }

    @Override protected void onDetachedFromWindow() {
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(translationListener);
        removeCallbacks(refreshAfterSelection);
        recycleTouch();
        super.onDetachedFromWindow();
    }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (ready) refreshTagChips(!focused);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (ready && w != oldw) scheduleRefresh();
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (!ready || changing || updatingRows || adjustingSelection || scrollingChip || start < 0 || end < 0) return;
        Editable text = getText();
        if (text == null) return;
        int low = Math.min(start, end), high = Math.max(start, end);
        for (SearchTagChipSpan chip : chips()) {
            int a = text.getSpanStart(chip), b = text.getSpanEnd(chip);
            if (low == high && low > a && low < b) {
                text.removeSpan(chip);
                markEditing(a, b);
            } else if (low < high && low < b && high > a) {
                low = Math.min(low, a);
                high = Math.max(high, b);
            }
        }
        if (low != Math.min(start, end) || high != Math.max(start, end)) {
            adjustingSelection = true;
            setSelection(start <= end ? low : high, start <= end ? high : low);
            adjustingSelection = false;
        }
        scheduleRefresh();
    }

    private void markEditing(int start, int end) {
        getText().setSpan(editingTag, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    private void editChip(SearchTagChipSpan chip) {
        Editable text = getText();
        int start = text.getSpanStart(chip), end = text.getSpanEnd(chip);
        if (start < 0) return;
        text.removeSpan(chip);
        markEditing(start, end);
        requestFocus();
        setSelection(end);
        requestLayout();
        invalidate();
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(this, 0);
    }

    private boolean expandForDelete(boolean forward) {
        Editable text = getText();
        if (text == null || getSelectionStart() != getSelectionEnd()) return false;
        int caret = getSelectionEnd();
        for (SearchTagChipSpan chip : chips()) {
            if (forward ? text.getSpanStart(chip) == caret : text.getSpanEnd(chip) == caret) {
                editChip(chip);
                if (forward) setSelection(text.getSpanStart(editingTag));
                return true;
            }
        }
        return false;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
                && expandForDelete(keyCode == KeyEvent.KEYCODE_FORWARD_DEL)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection connection = super.onCreateInputConnection(info);
        // Wrap visually, while retaining the keyboard's Search action on multiline inputs.
        info.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        if (connection == null) return null;
        return new InputConnectionWrapper(connection, false) {
            @Override public boolean setComposingText(CharSequence text, int cursor) {
                composing = true;
                return super.setComposingText(text, cursor);
            }
            @Override public boolean setComposingRegion(int start, int end) {
                composing = true;
                for (SearchTagChipSpan chip : chips()) {
                    if (getText().getSpanStart(chip) < end && getText().getSpanEnd(chip) > start) {
                        getText().removeSpan(chip);
                    }
                }
                return super.setComposingRegion(start, end);
            }
            @Override public boolean finishComposingText() {
                boolean result = super.finishComposingText();
                composing = false;
                refreshTagChips(false);
                return result;
            }
            @Override public boolean commitText(CharSequence text, int cursor) {
                boolean result = super.commitText(text, cursor);
                composing = false;
                refreshTagChips(text.length() > 1 && getText().getSpanStart(editingTag) < 0);
                return result;
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                return before == 1 && after == 0 && expandForDelete(false)
                        || before == 0 && after == 1 && expandForDelete(true)
                        || super.deleteSurroundingText(before, after);
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                return before == 1 && after == 0 && expandForDelete(false)
                        || before == 0 && after == 1 && expandForDelete(true)
                        || super.deleteSurroundingTextInCodePoints(before, after);
            }
        };
    }

    /** Suggestion input is the term at the caret, never an unrelated suffix of the query. */
    public Term suggestionRange() {
        Editable text = getText();
        int caret = Math.max(0, getSelectionEnd());
        if (text == null) return new Term(0, 0);
        if (getSelectionStart() >= 0 && getSelectionStart() != caret) {
            return new Term(Math.min(getSelectionStart(), caret), Math.max(getSelectionStart(), caret));
        }
        for (Term term : SearchQueryTokenizer.terms(text)) {
            if (caret >= term.start() && caret <= term.end()) return term;
        }
        return new Term(caret, caret);
    }

    public String getSuggestionKeyword() {
        Term range = suggestionRange();
        Editable text = getText();
        if (text == null) return "";
        for (SearchTagChipSpan chip : chips()) {
            if (text.getSpanStart(chip) == range.start() && text.getSpanEnd(chip) == range.end()) return "";
        }
        String keyword = text.subSequence(range.start(), range.end()).toString();
        return SearchQueryTokenizer.suggestionKeyword(keyword);
    }

    public void replaceSuggestion(String tag) {
        Editable text = getText();
        if (text == null) return;
        Term range = suggestionRange();
        String old = text.subSequence(range.start(), range.end()).toString();
        if (!old.isEmpty() && "-~".indexOf(old.charAt(0)) >= 0) tag = old.charAt(0) + tag;
        text.removeSpan(editingTag);
        String replacement = tag;
        if (range.end() == text.length() || !SearchQueryTokenizer.isSeparator(text.charAt(range.end()))) {
            replacement += " ";
        }
        text.replace(range.start(), range.end(), replacement);
        setSelection(range.start() + replacement.length());
        refreshTagChips(true);
    }

    private void actOnChip(SearchTagChipSpan chip, int action) {
        Editable text = getText();
        int start = text.getSpanStart(chip), end = text.getSpanEnd(chip);
        if (start < 0) return;
        if (action == 1) { editChip(chip); return; }
        int selection = getSelectionEnd();
        String replacement = "";
        if (action == 0) {
            String raw = text.subSequence(start, end).toString();
            if ("-+~".indexOf(raw.charAt(0)) >= 0) raw = raw.substring(1);
            replacement = (chip.operator == '+' ? "-" : chip.operator == '-' ? "~" : "") + raw;
        } else {
            while (end < text.length() && SearchQueryTokenizer.isSeparator(text.charAt(end))) end++;
            if (end == text.length()) {
                while (start > 0 && SearchQueryTokenizer.isSeparator(text.charAt(start - 1))) start--;
            }
        }
        int newSelection = selection >= end ? selection + replacement.length() - (end - start)
                : selection > start ? start + replacement.length() : selection;
        text.replace(start, end, replacement);
        setSelection(Math.max(0, Math.min(text.length(), newSelection)));
        refreshTagChips(true);
    }

    private float chipLeft(SearchTagChipSpan chip) {
        int start = getText().getSpanStart(chip);
        return getLayout().getPrimaryHorizontal(start);
    }

    private SearchTagChipSpan chipAt(float x, float y) {
        if (getLayout() == null || x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) return null;
        float local = x + getScrollX() - getTotalPaddingLeft();
        float localY = y + getScrollY() - getTotalPaddingTop();
        for (SearchTagChipSpan chip : chips()) {
            int line = getLayout().getLineForOffset(getText().getSpanStart(chip));
            // Bubbles extend beyond native font metrics, including the final layout line.
            // Use their drawn bounds so row gaps cannot activate a neighbouring chip.
            float center = SearchTagChipSpan.centerY(getPaint(), getLayout().getLineBaseline(line));
            if (Math.abs(localY - center) > chip.height() / 2) continue;
            float left = chipLeft(chip);
            float width = chip.getSize(getPaint(), getText(), 0, 0, null);
            if (local >= left && local <= left + width) return chip;
        }
        return null;
    }

    private void recycleTouch() {
        if (touchDown != null) touchDown.recycle();
        touchDown = null;
        touchedChip = null;
    }

    public void setSearchEditTextListener(SearchEditTextListener listener) {
        mListener = listener;
    }

    @Override public boolean onTextContextMenuItem(int id) {
        String selected = null;
        if (firstRow != null && (id == android.R.id.copy || id == android.R.id.cut)
                && getSelectionStart() >= 0 && getSelectionEnd() >= 0) {
            selected = getText().subSequence(Math.min(getSelectionStart(), getSelectionEnd()),
                    Math.max(getSelectionStart(), getSelectionEnd())).toString();
        }
        boolean handled = super.onTextContextMenuItem(id);
        if (handled && selected != null) {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("", selected));
        }
        return handled;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, @NonNull KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // special case for the back key, we do not even try to send it
            // to the drop down list but instead, consume it immediately
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                KeyEvent.DispatcherState state = getKeyDispatcherState();
                if (state != null) {
                    state.startTracking(event, this);
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                KeyEvent.DispatcherState state = getKeyDispatcherState();
                if (state != null) {
                    state.handleUpEvent(event);
                }
                if (event.isTracking() && !event.isCanceled()) {
                    // TODO stopSelectionActionMode
                    if (mListener != null) {
                        mListener.onBackPressed();
                        return true;
                    }
                }
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                recycleTouch();
                scrollingChip = false;
                moved = false;
                touchedChip = chipAt(event.getX(), event.getY());
                if (touchedChip != null) {
                    touchDown = MotionEvent.obtain(event);
                    touchScrollX = getScrollX();
                    touchScrollY = getScrollY();
                    return true;
                }
            }
            if (touchedChip != null) {
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (!moved && (Math.abs(event.getX() - touchDown.getX()) > slop
                            || Math.abs(event.getY() - touchDown.getY()) > slop)) {
                        moved = true;
                        scrollingChip = true;
                    }
                    if (moved && getLayout() != null) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        if (multilineChips) {
                            int maxScroll = Math.max(0, getLayout().getHeight()
                                    - (getHeight() - getTotalPaddingTop() - getTotalPaddingBottom()));
                            scrollTo(0, Math.max(0, Math.min(maxScroll,
                                    Math.round(touchScrollY + touchDown.getY() - event.getY()))));
                        } else if (Math.abs(event.getX() - touchDown.getX())
                                > Math.abs(event.getY() - touchDown.getY())) {
                            int maxScroll = Math.max(0, (int) Math.ceil(getLayout().getLineWidth(0))
                                    - (getWidth() - getTotalPaddingLeft() - getTotalPaddingRight()));
                            scrollTo(Math.max(0, Math.min(maxScroll,
                                    Math.round(touchScrollX + touchDown.getX() - event.getX()))), getScrollY());
                        }
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (!moved && chipAt(event.getX(), event.getY()) == touchedChip) {
                        float local = event.getX() + getScrollX() - getTotalPaddingLeft() - chipLeft(touchedChip);
                        float width = touchedChip.getSize(getPaint(), getText(), 0, 0, null);
                        if (event.getEventTime() - touchDown.getEventTime() >= ViewConfiguration.getLongPressTimeout()) {
                            int start = getText().getSpanStart(touchedChip);
                            int end = getText().getSpanEnd(touchedChip);
                            editChip(touchedChip);
                            setSelection(start, end);
                            performLongClick();
                        } else {
                            actOnChip(touchedChip, local < touchedChip.actionWidth() ? 0
                                    : local > width - touchedChip.actionWidth() ? 2 : 1);
                            performClick();
                        }
                        if (mListener != null) mListener.onClick();
                    }
                    recycleTouch();
                    scrollingChip = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    recycleTouch();
                    scrollingChip = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                return true;
            }
            boolean handled = super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP && mListener != null) mListener.onClick();
            return handled;
        } catch (Throwable t) {
            // Some devices crash here.
            // I don't why.
            ExceptionUtils.throwIfFatal(t);
            return false;
        }
    }

    private static final int CHIP_ACTION_BASE = 0x02000000;

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        SearchTagChipSpan[] chips = chips();
        for (int i = 0; i < chips.length; i++) {
            SearchTagChipSpan chip = chips[i];
            int next = chip.operator == '+' ? R.string.search_preset_exclude
                    : chip.operator == '-' ? R.string.search_tag_or : R.string.search_preset_include;
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(CHIP_ACTION_BASE + i * 3,
                    getContext().getString(R.string.search_tag_change_mode, chip.label, getContext().getString(next))));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(CHIP_ACTION_BASE + i * 3 + 1,
                    getContext().getString(R.string.search_tag_edit, chip.label)));
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(CHIP_ACTION_BASE + i * 3 + 2,
                    getContext().getString(R.string.search_tag_delete, chip.label)));
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        int index = action - CHIP_ACTION_BASE;
        SearchTagChipSpan[] chips = chips();
        if (isEnabled() && index >= 0 && index / 3 < chips.length) {
            actOnChip(chips[index / 3], index % 3);
            if (mListener != null) mListener.onClick();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    public interface SearchEditTextListener {
        void onClick();
        void onBackPressed();
        default void onCursorChanged() {}
    }
}
