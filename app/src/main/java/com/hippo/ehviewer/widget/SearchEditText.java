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
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
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
import java.util.Locale;
import java.util.Set;

public class SearchEditText extends AppCompatEditText {

    private SearchEditTextListener mListener;
    private final Object editingTag = new Object();
    private boolean ready;
    private boolean changing;
    private boolean adjustingSelection;
    private boolean composing;
    private boolean chipsEnabled = true;
    private boolean scrollingChip;
    private SearchTagChipSpan touchedChip;
    private MotionEvent touchDown;
    private int touchScrollX;
    private boolean moved;
    private int insertedCount;
    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener translationListener = (prefs, key) -> {
        if (Settings.KEY_SHOW_TAG_TRANSLATIONS.equals(key)) refreshTagChips(false);
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
        ready = true;
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
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
                changing = false;
                refreshTagChips(insertedCount > 1 && !composing && text.getSpanStart(editingTag) < 0);
                scheduleRefresh();
            }
        });
    }

    /** Programmatic queries (history, presets, restored searches) are already committed. */
    public void setQueryText(String query) {
        composing = false;
        if (getText() != null) getText().removeSpan(editingTag);
        setText(query);
        refreshTagChips(true);
    }

    public void setTagChipsEnabled(boolean enabled) {
        chipsEnabled = enabled;
        refreshTagChips(true);
    }

    private SearchTagChipSpan[] chips() {
        Editable text = getText();
        return text == null ? new SearchTagChipSpan[0]
                : text.getSpans(0, text.length(), SearchTagChipSpan.class);
    }

    /** Changes spans only, so search listeners and the query history always see the raw query. */
    public void refreshTagChips(boolean commitCurrent) {
        if (!ready || changing) return;
        Editable text = getText();
        if (text == null) return;
        SearchTagChipSpan[] old = chips();
        if (!chipsEnabled) {
            for (SearchTagChipSpan chip : old) text.removeSpan(chip);
            return;
        }
        if (composing || BaseInputConnection.getComposingSpanStart(text) >= 0) return;
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
        float maxWidth = Math.max(48 * density,
                Math.min(200 * density, (getWidth() > 0 ? getWidth() : 300 * density) - 96 * density));
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
            String label = chipLabel(tag, database);
            text.setSpan(new SearchTagChipSpan(label, tag.operator(), density, accent, maxWidth),
                    term.start(), term.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        requestLayout();
        invalidate();
    }

    private String chipLabel(Tag tag, EhTagDatabase database) {
        String namespace = tag.namespace();
        String value = tag.value();
        if (database != null) {
            String group = database.getTranslation("n:" + namespace);
            String prefix = EhTagDatabase.namespaceToPrefix(namespace);
            String translated = database.getTranslation((prefix == null ? namespace + ":" : prefix)
                    + value.toLowerCase(Locale.ROOT));
            if (group != null) namespace = group;
            if (translated != null) value = translated;
        }
        return namespace + ":" + value;
    }

    private void scheduleRefresh() {
        removeCallbacks(refreshAfterSelection);
        post(refreshAfterSelection);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        preferences.registerOnSharedPreferenceChangeListener(translationListener);
        refreshTagChips(false);
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
        if (!ready || changing || adjustingSelection || scrollingChip || start < 0 || end < 0) return;
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
        if (!keyword.isEmpty() && "-+~".indexOf(keyword.charAt(0)) >= 0) keyword = keyword.substring(1);
        int colon = keyword.indexOf(':');
        if (colon >= 0) keyword = SearchQueryTokenizer.canonicalNamespace(keyword.substring(0, colon))
                + keyword.substring(colon);
        return keyword.replace("\"", "").replace("$", "").trim();
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
        int end = getText().getSpanEnd(chip);
        return Math.min(getLayout().getPrimaryHorizontal(start), getLayout().getPrimaryHorizontal(end));
    }

    private SearchTagChipSpan chipAt(float x, float y) {
        if (getLayout() == null || y < 0 || y > getHeight()) return null;
        float local = x + getScrollX() - getTotalPaddingLeft();
        for (SearchTagChipSpan chip : chips()) {
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
                    if (moved && Math.abs(event.getX() - touchDown.getX())
                            > Math.abs(event.getY() - touchDown.getY())) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        int maxScroll = Math.max(0, (int) Math.ceil(getLayout().getLineWidth(0))
                                - (getWidth() - getTotalPaddingLeft() - getTotalPaddingRight()));
                        scrollTo(Math.max(0, Math.min(maxScroll,
                                Math.round(touchScrollX + touchDown.getX() - event.getX()))), getScrollY());
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
