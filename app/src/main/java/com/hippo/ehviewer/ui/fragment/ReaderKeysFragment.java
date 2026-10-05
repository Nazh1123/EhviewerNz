package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import androidx.fragment.app.Fragment;
import com.google.android.material.card.MaterialCardView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ReaderKeyDefaults;
import com.hippo.ehviewer.ReaderKeyProfiles;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.SettingsActivity;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.ReaderTouchAreas;
import com.hippo.util.SystemUiHelper;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

/** Full-size touch-area editor with floating controls over the reading viewport. */
public final class ReaderKeysFragment extends Fragment {
    private ReaderKeyProfiles profiles;
    private ReaderKeyProfiles.Profile draft;
    private int region, direction;
    private String[] regions, gestures, actions;
    private TextView profileButton;
    private AppCompatImageButton saveButton, directionButton;
    private androidx.appcompat.widget.SwitchCompat unifiedAreasButton;
    private View bottomBar, backButton, moreButton;
    private ZoneView zones;
    private OnBackPressedCallback back;
    private int accent, foreground, secondary, surface;
    private int originalWindowFlags, originalSystemUi;
    private boolean actionBarWasShown;
    private SystemUiHelper systemUi;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        profiles = ReaderKeyProfiles.load();
        draft = profiles.active().copy();
        direction = Settings.getReadingDirection();
        if (state != null) {
            direction = GalleryView.sanitizeLayoutMode(state.getInt("direction", direction));
            region = Math.max(0, Math.min(ReaderKeyMap.REGION_COUNT - 1, state.getInt("region", 0)));
            try {
                if (state.containsKey("draft")) draft = ReaderKeyProfiles.Profile.fromJson(
                        new JSONObject(state.getString("draft", "{}")));
            } catch (JSONException ignored) { }
        }
        regions = getResources().getStringArray(R.array.reader_keys_regions);
        gestures = getResources().getStringArray(R.array.reader_keys_gestures);
        actions = getResources().getStringArray(R.array.reader_keys_actions);
        accent = color(androidx.appcompat.R.attr.colorPrimary);
        foreground = color(android.R.attr.textColorPrimary);
        secondary = color(android.R.attr.textColorSecondary);
        surface = Settings.getTheme() == Settings.THEME_BLACK
                ? requireContext().getColor(R.color.grey_850)
                : color(androidx.appcompat.R.attr.colorBackgroundFloating);
        FrameLayout root = new FrameLayout(requireContext());
        root.setBackgroundColor(color(android.R.attr.colorBackground));
        zones = new ZoneView(requireContext());
        zones.setId(R.id.reader_keys_canvas);
        root.addView(zones, new FrameLayout.LayoutParams(-1, -1));

        backButton = icon(R.drawable.v_arrow_left_dark_x24, R.string.reader_keys_back,
                () -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        backButton.setId(R.id.reader_keys_back);
        moreButton = icon(R.drawable.v_dots_vertical_secondary_dark_x24, R.string.reader_keys_more, this::showMore);
        ((AppCompatImageButton) moreButton).setImageTintList(ColorStateList.valueOf(foreground));
        moreButton.setId(R.id.reader_keys_more);
        directionButton = icon(R.drawable.v_arrow_right_x24, R.string.settings_read_reading_direction, this::cycleDirection);
        directionButton.setId(R.id.reader_keys_direction);
        directionButton.setImageTintList(ColorStateList.valueOf(foreground));
        floatingButton(root, backButton, Gravity.TOP | Gravity.LEFT);
        floatingButton(root, directionButton, Gravity.TOP | Gravity.RIGHT);

        unifiedAreasButton = new androidx.appcompat.widget.SwitchCompat(requireContext());
        unifiedAreasButton.setId(R.id.reader_keys_unified_areas);
        unifiedAreasButton.setText(R.string.reader_keys_unified_areas);
        unifiedAreasButton.setTextSize(12);
        unifiedAreasButton.setTextColor(foreground);
        unifiedAreasButton.setSwitchPadding(dp(6));
        int[][] switchStates = {new int[]{android.R.attr.state_checked}, new int[]{}};
        unifiedAreasButton.setThumbTintList(new ColorStateList(switchStates, new int[]{accent, secondary}));
        unifiedAreasButton.setTrackTintList(new ColorStateList(switchStates,
                new int[]{ColorUtils.setAlphaComponent(accent, 100), ColorUtils.setAlphaComponent(secondary, 100)}));
        unifiedAreasButton.setPadding(dp(6), 0, dp(6), 0);
        unifiedAreasButton.setBackground(ripple(16));
        unifiedAreasButton.setOnCheckedChangeListener((button, checked) -> {
            if (checked != draft.unifiedTouchAreas) {
                draft.setUnifiedTouchAreas(checked, direction);
                refresh();
            }
        });
        ViewCompat.setTooltipText(unifiedAreasButton, getString(R.string.reader_keys_unified_areas_hint));
        FrameLayout.LayoutParams unifyParams = new FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        unifyParams.setMargins(dp(76), dp(12), dp(76), 0);
        root.addView(unifiedAreasButton, unifyParams);

        MaterialCardView card = new MaterialCardView(requireContext());
        card.setId(R.id.reader_keys_bar);
        card.setRadius(dp(20));
        card.setCardElevation(dp(6));
        card.setCardBackgroundColor(surface);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ColorUtils.setAlphaComponent(foreground, 20));
        LinearLayout controls = new LinearLayout(requireContext());
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(8), 0, dp(4), 0);
        profileButton = new AppCompatTextView(requireContext());
        profileButton.setId(R.id.reader_keys_profile);
        profileButton.setTextSize(16);
        profileButton.setTextColor(foreground);
        profileButton.setGravity(Gravity.CENTER_VERTICAL);
        profileButton.setSingleLine(true);
        profileButton.setEllipsize(TextUtils.TruncateAt.END);
        profileButton.setPadding(dp(8), 0, dp(4), 0);
        profileButton.setBackground(ripple(12));
        profileButton.setOnClickListener(v -> guard(this::chooseProfile));
        ViewCompat.setTooltipText(profileButton, getString(R.string.reader_keys_select_profile));
        controls.addView(profileButton, new LinearLayout.LayoutParams(0, -1, 1));
        controls.addView(moreButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View add = icon(R.drawable.v_plus_dark_x24, R.string.reader_keys_new, () -> guard(() -> addProfile(false)));
        add.setId(R.id.reader_keys_add);
        controls.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        View manage = icon(R.drawable.v_settings_dark_x24, R.string.reader_keys_manage, this::manageProfile);
        manage.setId(R.id.reader_keys_manage);
        controls.addView(manage, new LinearLayout.LayoutParams(dp(48), dp(48)));
        saveButton = icon(R.drawable.v_save_x24, R.string.reader_keys_save, this::saveDraft);
        saveButton.setId(R.id.reader_keys_save);
        controls.addView(saveButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
        barParams.setMargins(dp(16), 0, dp(16), dp(16));
        root.addView(card, barParams);
        bottomBar = card;

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            FrameLayout.LayoutParams left = (FrameLayout.LayoutParams) backButton.getLayoutParams();
            left.leftMargin = dp(12) + safe.left; left.topMargin = dp(12) + safe.top;
            backButton.setLayoutParams(left);
            FrameLayout.LayoutParams right = (FrameLayout.LayoutParams) directionButton.getLayoutParams();
            right.rightMargin = dp(12) + safe.right; right.topMargin = dp(12) + safe.top;
            directionButton.setLayoutParams(right);
            FrameLayout.LayoutParams unify = (FrameLayout.LayoutParams) unifiedAreasButton.getLayoutParams();
            unify.topMargin = dp(12) + safe.top;
            unify.leftMargin = dp(76) + safe.left; unify.rightMargin = dp(76) + safe.right;
            unifiedAreasButton.setLayoutParams(unify);
            FrameLayout.LayoutParams bottom = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            bottom.setMargins(dp(16) + safe.left, 0, dp(16) + safe.right, dp(16) + safe.bottom);
            bottomBar.setLayoutParams(bottom);
            return insets;
        });
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (requireActivity() instanceof SettingsActivity activity) activity.setSettingsTitle(R.string.reader_keys_title);
        if (requireActivity() instanceof AppCompatActivity activity && activity.getSupportActionBar() != null) {
            actionBarWasShown = activity.getSupportActionBar().isShowing();
            activity.getSupportActionBar().setShowHideAnimationEnabled(false);
            activity.getSupportActionBar().hide();
        }
        Window window = requireActivity().getWindow();
        originalWindowFlags = window.getAttributes().flags;
        originalSystemUi = window.getDecorView().getSystemUiVisibility();
        if (Settings.getReadingFullscreen()) {
            int flags = WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
            window.setFlags(flags, flags);
            systemUi = new SystemUiHelper(requireActivity(), SystemUiHelper.LEVEL_IMMERSIVE,
                    SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES | SystemUiHelper.FLAG_IMMERSIVE_STICKY);
            systemUi.hide();
        }
        back = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                guard(() -> { setEnabled(false); requireActivity().getOnBackPressedDispatcher().onBackPressed(); });
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        ViewCompat.requestApplyInsets(view);
        refresh();
    }

    @Override public void onDestroyView() {
        ViewCompat.setOnApplyWindowInsetsListener(requireView(), null);
        Window window = requireActivity().getWindow();
        if (systemUi != null) {
            systemUi.show(); systemUi = null;
            window.getDecorView().setOnSystemUiVisibilityChangeListener(null);
        }
        int mask = WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        window.setFlags(originalWindowFlags, mask);
        window.getDecorView().setSystemUiVisibility(originalSystemUi);
        if (requireActivity() instanceof AppCompatActivity activity && activity.getSupportActionBar() != null) {
            if (actionBarWasShown) activity.getSupportActionBar().show();
            activity.getSupportActionBar().setShowHideAnimationEnabled(true);
        }
        zones = null; profileButton = null; saveButton = null; directionButton = null;
        unifiedAreasButton = null;
        bottomBar = null; backButton = null; moreButton = null; back = null;
        super.onDestroyView();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (draft != null) state.putString("draft", draft.toJson().toString());
        state.putInt("region", region);
        state.putInt("direction", direction);
    }

    private boolean dirty() { return !draft.toJson().toString().equals(profiles.active().toJson().toString()); }
    private String profileName(int index) {
        return getString(R.string.reader_keys_profile_label, index + 1, profileTitle(index));
    }
    private String profileTitle(int index) {
        return profiles.profiles.get(index).displayName(requireContext(), index);
    }
    private void refresh() {
        if (zones == null) return;
        boolean changed = dirty();
        profileButton.setText(profileName(profiles.selected) + (changed ? " *" : "") + " ▾");
        profileButton.setContentDescription(getString(R.string.reader_keys_current_profile, profileName(profiles.selected)));
        saveButton.setImageTintList(ColorStateList.valueOf(changed ? accent : secondary));
        unifiedAreasButton.setChecked(draft.unifiedTouchAreas);
        int icon, label;
        switch (direction) {
            case GalleryView.LAYOUT_RIGHT_TO_LEFT -> {
                icon = R.drawable.v_arrow_left_x24;
                label = R.string.settings_read_reading_direction_right_to_Left;
            }
            case GalleryView.LAYOUT_TOP_TO_BOTTOM -> {
                icon = R.drawable.v_arrow_down_x24;
                label = R.string.settings_read_reading_direction_top_to_bottom;
            }
            default -> {
                icon = R.drawable.v_arrow_right_x24;
                label = R.string.settings_read_reading_direction_left_to_right;
            }
        }
        directionButton.setImageResource(icon);
        String description = getString(R.string.reader_keys_edit_direction, getString(label));
        directionButton.setContentDescription(description);
        ViewCompat.setTooltipText(directionButton, description);
        if (back != null) back.setEnabled(changed);
        zones.accessibility.invalidateRoot();
        zones.invalidate();
    }

    private void cycleDirection() {
        direction = (direction + 1) % ReaderKeyMap.DIRECTION_COUNT;
        refresh();
    }

    private String defaultLabel(int area, int gesture) {
        int value = ReaderKeyDefaults.action(area, gesture);
        String label;
        if (value == ReaderKeyMap.LEFT || value == ReaderKeyMap.RIGHT) {
            boolean left = value == ReaderKeyMap.LEFT;
            if (direction == GalleryView.LAYOUT_TOP_TO_BOTTOM) {
                label = getString(left ? R.string.reader_keys_scroll_up : R.string.reader_keys_scroll_down);
            } else {
                boolean previous = direction == GalleryView.LAYOUT_RIGHT_TO_LEFT ? !left : left;
                label = actions[(previous ? ReaderKeyMap.PREVIOUS : ReaderKeyMap.NEXT) + 1];
            }
        } else label = actions[value + 1];
        return getString(R.string.reader_keys_default_action, label);
    }
    private String actionLabel(int area, int gesture) {
        int value = draft.keys(direction)[area * ReaderKeyMap.GESTURE_COUNT + gesture];
        return value == ReaderKeyMap.LEGACY ? defaultLabel(area, gesture) : actionCaption(value, gesture);
    }
    private String actionCaption(int value, int gesture) {
        if (value == ReaderKeyMap.NONE && gesture == ReaderKeyMap.DOUBLE_TAP) {
            return getString(R.string.reader_keys_double_tap_none);
        }
        if (direction == GalleryView.LAYOUT_TOP_TO_BOTTOM && (value == ReaderKeyMap.LEFT || value == ReaderKeyMap.RIGHT)) {
            return getString(value == ReaderKeyMap.LEFT ? R.string.reader_keys_scroll_up : R.string.reader_keys_scroll_down);
        }
        return actions[value + 1];
    }
    private String regionText(int area, boolean preview) {
        StringBuilder text = new StringBuilder();
        int count = preview && area == ReaderKeyMap.CENTER_TOP ? 1 : ReaderKeyMap.GESTURE_COUNT;
        for (int gesture = 0; gesture < count; gesture++) {
            if (gesture > 0) text.append('\n');
            text.append(gestures[gesture]).append(": ").append(actionLabel(area, gesture));
        }
        if (preview && area == ReaderKeyMap.CENTER_TOP) text.append("\n...");
        return text.toString();
    }
    private void chooseGesture() {
        String[] rows = new String[ReaderKeyMap.GESTURE_COUNT];
        for (int i = 0; i < rows.length; i++) rows[i] = gestures[i] + ": " + actionLabel(region, i);
        new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_edit_actions)
                .setItems(rows, (dialog, which) -> chooseAction(which)).show();
    }
    private void chooseAction(int gesture) {
        int count = ReaderKeyMap.SAVE_NEXT + 2;
        String[] labels = new String[count];
        labels[0] = defaultLabel(region, gesture);
        for (int i = 1; i < count; i++) labels[i] = actionCaption(i - 1, gesture);
        int index = region * ReaderKeyMap.GESTURE_COUNT + gesture;
        int[] keys = draft.keys(direction);
        int selected = keys[index] + 1;
        new AlertDialog.Builder(requireContext()).setTitle(gestures[gesture])
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    keys[index] = which - 1; dialog.dismiss(); refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void showMore() {
        PopupMenu menu = new PopupMenu(requireContext(), moreButton);
        menu.getMenu().add(0, 0, 0, getString(R.string.reader_keys_animated_area, draft.animatedControlPercent));
        menu.getMenu().add(0, 1, 1, getString(R.string.reader_keys_orientation_swipe,
                getResources().getStringArray(R.array.reader_keys_swipe_choices)[draft.orientationSwipe]));
        menu.getMenu().add(0, 2, 2, R.string.reader_keys_reset_sizes);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0 -> chooseAnimatedArea();
                case 1 -> chooseOrientationSwipe();
                case 2 -> { draft.setAreas(direction, ReaderTouchAreas.defaults()); refresh(); }
            }
            return true;
        });
        menu.show();
    }
    private void chooseAnimatedArea() {
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(8));
        TextView label = new AppCompatTextView(requireContext());
        label.setText(R.string.reader_keys_bottom_percent);
        content.addView(label);
        EditText input = new androidx.appcompat.widget.AppCompatEditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true); input.setSelectAllOnFocus(true);
        input.setContentDescription(getString(R.string.reader_keys_animated_area_title));
        input.setText(String.valueOf(draft.animatedControlPercent));
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        SeekBar seek = new androidx.appcompat.widget.AppCompatSeekBar(requireContext());
        seek.setMax(100); seek.setProgress(draft.animatedControlPercent);
        seek.setContentDescription(getString(R.string.reader_keys_animated_area_title));
        content.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) { input.setText(String.valueOf(value)); input.setSelection(input.length()); }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                try {
                    int value = Integer.parseInt(text.toString());
                    if (value >= 0 && value <= 100) seek.setProgress(value);
                } catch (NumberFormatException ignored) { }
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_animated_area_title)
                .setView(content).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(input.getText().toString());
                if (value < 0 || value > 100) throw new NumberFormatException();
                draft.animatedControlPercent = value; refresh(); dialog.dismiss();
            } catch (NumberFormatException invalid) { input.setError(getString(R.string.reader_keys_percent_error)); }
        }));
        dialog.show();
    }

    private String lineName(int line) {
        return getResources().getStringArray(R.array.reader_keys_lines)[line];
    }

    private String percent(float position) {
        return String.format(Locale.US, "%.6f", position * 100).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void chooseBoundary(int line) {
        ReaderTouchAreas areas = draft.areas(direction);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(8));
        TextView label = new AppCompatTextView(requireContext());
        label.setText(line < ReaderTouchAreas.LEFT_SPLIT
                ? R.string.reader_keys_boundary_horizontal_percent : R.string.reader_keys_boundary_vertical_percent);
        content.addView(label);
        EditText input = new androidx.appcompat.widget.AppCompatEditText(requireContext());
        input.setId(R.id.reader_keys_boundary_input);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true); input.setSelectAllOnFocus(true);
        input.setContentDescription(lineName(line));
        input.setText(percent(areas.position(line)));
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(lineName(line))
                .setView(content).setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ReaderTouchAreas current = draft.areas(direction);
            try {
                float value = Float.parseFloat(input.getText().toString()) / 100;
                if (!Float.isFinite(value) || value < current.min(line) - .000001f
                        || value > current.max(line) + .000001f) throw new NumberFormatException();
                draft.setAreas(direction, current.withPosition(line, value));
                refresh(); dialog.dismiss();
            } catch (NumberFormatException invalid) {
                input.setError(getString(R.string.reader_keys_boundary_error, percent(current.min(line)), percent(current.max(line))));
            }
        }));
        dialog.show();
    }
    private void chooseOrientationSwipe() {
        new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_orientation_title)
                .setSingleChoiceItems(R.array.reader_keys_swipe_choices, draft.orientationSwipe, (dialog, which) -> {
                    draft.orientationSwipe = which; dialog.dismiss(); refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseProfile() {
        String[] names = new String[profiles.profiles.size()];
        for (int i = 0; i < names.length; i++) names[i] = profileName(i);
        new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_select_profile)
                .setSingleChoiceItems(names, profiles.selected, (dialog, which) -> {
                    profiles.selected = which; profiles.save(); draft = profiles.active().copy();
                    dialog.dismiss(); refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addProfile(boolean copy) {
        ReaderKeyProfiles.Profile profile = copy ? draft.duplicate() : new ReaderKeyProfiles.Profile("");
        profiles.profiles.add(profile);
        profiles.selected = profiles.profiles.size() - 1;
        profiles.save(); draft = profile.copy(); refresh();
    }

    private void manageProfile() {
        String[] items = {getString(R.string.reader_keys_rename), getString(R.string.reader_keys_copy),
                getString(R.string.reader_keys_delete), getString(R.string.reader_keys_recommended),
                getString(R.string.reader_keys_reset)};
        new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_manage)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0 -> rename();
                        case 1 -> guard(() -> addProfile(true));
                        case 2 -> {
                            if (profiles.profiles.size() == 1) {
                                Toast.makeText(requireContext(), R.string.reader_keys_keep_one, Toast.LENGTH_SHORT).show();
                            } else new AlertDialog.Builder(requireContext()).setMessage(R.string.reader_keys_delete_question)
                                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                                        profiles.profiles.remove(profiles.selected);
                                        profiles.selected = Math.min(profiles.selected, profiles.profiles.size() - 1);
                                        profiles.save(); draft = profiles.active().copy(); refresh();
                                    }).setNegativeButton(android.R.string.cancel, null).show();
                        }
                        case 3 -> { draft = ReaderKeyProfiles.recommended(draft.name); refresh(); }
                        case 4 -> new AlertDialog.Builder(requireContext()).setMessage(R.string.reader_keys_reset_question)
                                .setPositiveButton(android.R.string.ok, (d, w) -> {
                                    draft = ReaderKeyProfiles.defaults(draft.name); refresh();
                                }).setNegativeButton(android.R.string.cancel, null).show();
                    }
                }).show();
    }

    private void rename() {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setText(draft.displayName(requireContext(), profiles.selected));
        new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_rename).setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    draft.name = input.getText().toString().trim(); refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void saveDraft() {
        profiles.profiles.set(profiles.selected, draft.copy());
        profiles.save(); refresh();
        Toast.makeText(requireContext(), R.string.reader_keys_saved, Toast.LENGTH_SHORT).show();
    }

    private void guard(Runnable next) {
        if (!dirty()) { next.run(); return; }
        new AlertDialog.Builder(requireContext()).setMessage(R.string.reader_keys_unsaved)
                .setPositiveButton(R.string.reader_keys_save, (dialog, which) -> { saveDraft(); next.run(); })
                .setNeutralButton(R.string.reader_keys_discard, (dialog, which) -> {
                    draft = profiles.active().copy(); refresh(); next.run();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int attr) {
        TypedValue value = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? requireContext().getColorStateList(value.resourceId).getDefaultColor() : value.data;
    }
    private RippleDrawable ripple(int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE); mask.setCornerRadius(dp(radius));
        return new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 35)), null, mask);
    }
    private AppCompatImageButton icon(int drawable, int description, Runnable action) {
        AppCompatImageButton button = new AppCompatImageButton(requireContext());
        button.setImageResource(drawable); button.setImageTintList(ColorStateList.valueOf(secondary));
        button.setContentDescription(getString(description));
        ViewCompat.setTooltipText(button, getString(description));
        button.setPadding(dp(12), dp(12), dp(12), dp(12)); button.setBackground(ripple(16));
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void floatingButton(FrameLayout root, View button, int gravity) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface); shape.setCornerRadius(dp(24));
        shape.setStroke(dp(1), ColorUtils.setAlphaComponent(foreground, 20));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 35)), shape, null));
        button.setElevation(dp(3));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(48), gravity);
        params.setMargins(dp(12), dp(12), dp(12), 0); root.addView(button, params);
    }

    private final class ZoneView extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final ExploreByTouchHelper accessibility;
        private float downX, downY;
        private int pressedLine = -1, activePointer = -1;
        private boolean dragging, moved;
        private ReaderTouchAreas downAreas;

        ZoneView(Context context) {
            super(context); setClickable(true); setFocusable(true);
            setOnClickListener(v -> { if (pressedLine >= 0) chooseBoundary(pressedLine); else chooseGesture(); });
            accessibility = new ExploreByTouchHelper(this) {
                @Override protected int getVirtualViewAt(float x, float y) {
                    int line = lineAt(x, y);
                    if (line >= 0) return ReaderKeyMap.REGION_COUNT + line;
                    int area = draft.areas(direction).region(x, y, getWidth(), getHeight());
                    return area < 0 ? INVALID_ID : area;
                }
                @Override protected void getVisibleVirtualViews(List<Integer> areas) {
                    for (int area = 0; area < ReaderKeyMap.REGION_COUNT; area++) areas.add(area);
                    for (int line = 0; line < ReaderTouchAreas.LINE_COUNT; line++) areas.add(ReaderKeyMap.REGION_COUNT + line);
                }
                @Override protected void onPopulateNodeForVirtualView(int area, AccessibilityNodeInfoCompat node) {
                    if (area >= ReaderKeyMap.REGION_COUNT) {
                        int line = area - ReaderKeyMap.REGION_COUNT;
                        float[] segment = lineSegment(line);
                        int inset = dp(12);
                        node.setBoundsInParent(new Rect(Math.max(0, (int) segment[0] - inset), Math.max(0, (int) segment[1] - inset),
                                Math.min(getWidth(), (int) segment[2] + inset), Math.min(getHeight(), (int) segment[3] + inset)));
                        node.setContentDescription(lineName(line) + ", " + percent(draft.areas(direction).position(line)) + "%");
                        node.setClassName("android.widget.Button"); node.setClickable(true);
                        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
                        return;
                    }
                    RectF bounds = areaBounds(area);
                    node.setBoundsInParent(new Rect((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom));
                    node.setContentDescription(regions[area] + ", " + regionText(area, false));
                    node.setClassName("android.widget.Button"); node.setClickable(true);
                    node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
                }
                @Override protected boolean onPerformActionForVirtualView(int area, int action, Bundle args) {
                    if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false;
                    if (area >= ReaderKeyMap.REGION_COUNT) { chooseBoundary(area - ReaderKeyMap.REGION_COUNT); return true; }
                    region = area; refresh(); chooseGesture(); return true;
                }
            };
            ViewCompat.setAccessibilityDelegate(this, accessibility);
        }
        private RectF areaBounds(int area) {
            float[] b = draft.areas(direction).bounds(area);
            return new RectF(b[0] * getWidth(), b[1] * getHeight(), b[2] * getWidth(), b[3] * getHeight());
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            for (int area = 0; area < ReaderKeyMap.REGION_COUNT; area++) {
                RectF rect = areaBounds(area);
                border.setStyle(Paint.Style.FILL);
                border.setColor(ColorUtils.setAlphaComponent(area == region ? accent : foreground, area == region ? 14 : 4));
                canvas.drawRect(rect, border);
            }
            border.setColor(ColorUtils.setAlphaComponent(foreground, 65)); border.setStrokeWidth(dp(1));
            for (int line = 0; line < ReaderTouchAreas.LINE_COUNT; line++) {
                float[] segment = lineSegment(line);
                canvas.drawLine(segment[0], segment[1], segment[2], segment[3], border);
            }
            drawAnimatedBoundary(canvas);
            for (int area = 0; area < ReaderKeyMap.REGION_COUNT; area++) drawActions(canvas, area, areaBounds(area));
            for (int line = 0; line < ReaderTouchAreas.LINE_COUNT; line++) drawHandle(canvas, line);
        }

        private void drawActions(Canvas canvas, int area, RectF rect) {
            float top = rect.top + dp(12), bottom = rect.bottom - dp(12);
            if (rect.top == 0 && directionButton.getHeight() > 0) {
                top = Math.max(top, directionButton.getBottom() + dp(12));
            }
            if (rect.bottom == getHeight() && bottomBar.getHeight() > 0) bottom = Math.min(bottom, bottomBar.getTop() - dp(12));
            SpannableStringBuilder text = new SpannableStringBuilder(regionText(area, true));
            int start = 0;
            while (start < text.length()) {
                int end = TextUtils.indexOf(text, '\n', start);
                if (end < 0) end = text.length();
                int hint = TextUtils.indexOf(text, " (", start, end);
                if (hint >= 0) {
                    text.setSpan(new RelativeSizeSpan(.8f), hint, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new ForegroundColorSpan(secondary), hint, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                start = end + 1;
            }
            textPaint.setColor(area == region ? accent : foreground);
            int inset = dp(8);
            int width = Math.max(1, (int) rect.width() - inset * 2);
            StaticLayout layout = null;
            int preferredSize = rect.width() < dp(150) ? 12 : 14;
            for (int size = preferredSize; size >= 10; size--) {
                textPaint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
                layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                        .setLineSpacing(dp(4), 1).build();
                if (layout.getHeight() <= bottom - top) break;
            }
            canvas.save(); canvas.clipRect(rect);
            canvas.translate(rect.left + inset, Math.max(top, top + (bottom - top - layout.getHeight()) / 2));
            layout.draw(canvas); canvas.restore();
        }

        private float[] lineSegment(int line) {
            float[] segment = draft.areas(direction).segment(line);
            return new float[]{segment[0] * getWidth(), segment[1] * getHeight(), segment[2] * getWidth(), segment[3] * getHeight()};
        }

        private int lineAt(float x, float y) {
            int nearest = -1;
            float best = dp(12), bestHandleDistance = Float.MAX_VALUE;
            for (int line = 0; line < ReaderTouchAreas.LINE_COUNT; line++) {
                float[] s = lineSegment(line);
                float px = Math.max(s[0], Math.min(s[2], x)), py = Math.max(s[1], Math.min(s[3], y));
                float distance = (float) Math.hypot(x - px, y - py);
                float handleDistance = (float) Math.hypot(x - (s[0] + s[2]) / 2, y - (s[1] + s[3]) / 2);
                if (distance < best || (distance == best && handleDistance < bestHandleDistance)) {
                    nearest = line; best = distance; bestHandleDistance = handleDistance;
                }
            }
            return nearest;
        }

        private void drawHandle(Canvas canvas, int line) {
            float[] segment = lineSegment(line);
            float x = (segment[0] + segment[2]) / 2, y = (segment[1] + segment[3]) / 2;
            boolean vertical = line < ReaderTouchAreas.LEFT_SPLIT;
            border.setColor(surface);
            canvas.drawRoundRect(x - dp(vertical ? 4 : 12), y - dp(vertical ? 12 : 4),
                    x + dp(vertical ? 4 : 12), y + dp(vertical ? 12 : 4), dp(4), dp(4), border);
            border.setColor(line == pressedLine ? accent : secondary);
            border.setStrokeWidth(dp(2));
            canvas.drawLine(x - dp(vertical ? 0 : 8), y - dp(vertical ? 8 : 0),
                    x + dp(vertical ? 0 : 8), y + dp(vertical ? 8 : 0), border);
            if (dragging && line == pressedLine) {
                boolean grayTheme = Settings.getTheme() == Settings.THEME_DARK;
                textPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
                textPaint.setColor(grayTheme ? Color.DKGRAY : Color.WHITE);
                String label = String.format(Locale.US, "%.2f%%", draft.areas(direction).position(line) * 100);
                Paint.FontMetrics metrics = textPaint.getFontMetrics();
                float paddingX = dp(4), paddingY = dp(2);
                float width = textPaint.measureText(label) + paddingX * 2;
                float height = metrics.descent - metrics.ascent + paddingY * 2;
                float left = Math.max(dp(4), Math.min(getWidth() - width - dp(4), x + dp(10)));
                float top = y - dp(14) - height;
                if (top < dp(4)) top = y + dp(14);
                top = Math.max(dp(4), Math.min(getHeight() - height - dp(4), top));
                border.setColor(ColorUtils.setAlphaComponent(grayTheme ? Color.WHITE : Color.GRAY, 196));
                canvas.drawRoundRect(left, top, left + width, top + height, dp(4), dp(4), border);
                canvas.drawText(label, left + paddingX, top + paddingY - metrics.ascent, textPaint);
            }
        }

        private void drawAnimatedBoundary(Canvas canvas) {
            if (draft.animatedControlPercent == 0) return;
            float y = getHeight() * ((100 - draft.animatedControlPercent) / 100f);
            ReaderTouchAreas areas = draft.areas(direction);
            border.setColor(ColorUtils.setAlphaComponent(accent, 100));
            border.setStrokeWidth(dp(1));
            border.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            canvas.drawLine(0, y, getWidth() * areas.position(ReaderTouchAreas.LEFT_EDGE), y, border);
            canvas.drawLine(getWidth() * areas.position(ReaderTouchAreas.RIGHT_EDGE), y, getWidth(), y, border);
            border.setPathEffect(null);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    activePointer = event.getPointerId(0);
                    downX = event.getX(); downY = event.getY();
                    pressedLine = lineAt(downX, downY);
                    downAreas = draft.areas(direction); dragging = false; moved = false;
                    if (pressedLine >= 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                }
                case MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    int pointer = event.findPointerIndex(activePointer);
                    if (pointer < 0) { cancelDrag(); return true; }
                    float x = event.getX(pointer), y = event.getY(pointer);
                    if (Math.hypot(x - downX, y - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
                    if (pressedLine >= 0 && moved) {
                        dragging = true;
                        float delta = pressedLine < ReaderTouchAreas.LEFT_SPLIT ? (x - downX) / getWidth() : (y - downY) / getHeight();
                        float position = downAreas.position(pressedLine) + delta;
                        draft.setAreas(direction, downAreas.withPosition(pressedLine, position)); refresh();
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (!moved) {
                            int selected = draft.areas(direction).region(x, y, getWidth(), getHeight());
                            if (selected >= 0) { region = selected; refresh(); performClick(); }
                        }
                        finishTouch();
                    }
                }
                case MotionEvent.ACTION_CANCEL -> cancelDrag();
                case MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.getActionIndex()) == activePointer) cancelDrag();
                }
            }
            return true;
        }

        private void cancelDrag() {
            if (dragging && downAreas != null) { draft.setAreas(direction, downAreas); refresh(); }
            finishTouch();
        }
        private void finishTouch() {
            activePointer = -1; pressedLine = -1; dragging = false; downAreas = null;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
        }
        @Override public boolean dispatchHoverEvent(MotionEvent event) { return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event); }
        @Override public boolean onKeyDown(int code, KeyEvent event) { return accessibility.dispatchKeyEvent(event) || super.onKeyDown(code, event); }
        @Override protected void onFocusChanged(boolean gain, int direction, Rect previous) {
            super.onFocusChanged(gain, direction, previous);
            if (accessibility != null) accessibility.onFocusChanged(gain, direction, previous);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
