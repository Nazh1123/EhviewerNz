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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-size touch-area editor with floating controls over the reading viewport. */
public final class ReaderKeysFragment extends Fragment {
    private ReaderKeyProfiles profiles;
    private ReaderKeyProfiles.Profile draft, copyBaseline;
    private int region, direction;
    private String[] regions, gestures, actions;
    private TextView profileButton;
    private AppCompatImageButton saveButton, directionButton, animatedButton, helpButton, copyButton;
    private HelpOverlay helpOverlay;
    private final Rect helpInsets = new Rect();
    private boolean animatedMode;
    private static final int ANIMATED_LINE = ReaderTouchAreas.ANIMATED_SPLIT;
    private static final int ACTION_TEXT_ANCHOR = -2;
    private View bottomBar, backButton, moreButton;
    private ZoneView zones;
    private OnBackPressedCallback back;
    private int accent, foreground, secondary, surface, background;
    private int originalWindowFlags, originalSystemUi;
    private boolean actionBarWasShown;
    private SystemUiHelper systemUi;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        profiles = ReaderKeyProfiles.load();
        draft = profiles.active().copy();
        copyBaseline = profiles.active().copy();
        direction = Settings.getReadingDirection();
        if (state != null) {
            animatedMode = state.getBoolean("animatedMode", false);
            direction = GalleryView.sanitizeLayoutMode(state.getInt("direction", direction));
            region = Math.max(0, Math.min(ReaderKeyMap.REGION_COUNT - 1, state.getInt("region", 0)));
            try {
                if (state.containsKey("draft")) draft = ReaderKeyProfiles.Profile.fromJson(
                        new JSONObject(state.getString("draft", "{}")));
                if (state.containsKey("copyBaseline")) copyBaseline = ReaderKeyProfiles.Profile.fromJson(
                        new JSONObject(state.getString("copyBaseline", "{}")));
            } catch (JSONException ignored) { }
        }
        regions = getResources().getStringArray(R.array.reader_keys_regions);
        gestures = getResources().getStringArray(R.array.reader_keys_gestures);
        actions = getResources().getStringArray(R.array.reader_keys_actions);
        accent = color(androidx.appcompat.R.attr.colorPrimary);
        foreground = color(android.R.attr.textColorPrimary);
        secondary = color(android.R.attr.textColorSecondary);
        background = color(android.R.attr.colorBackground);
        surface = Settings.getTheme() == Settings.THEME_BLACK
                ? requireContext().getColor(R.color.grey_850)
                : color(androidx.appcompat.R.attr.colorBackgroundFloating);
        FrameLayout root = new FrameLayout(requireContext());
        root.setBackgroundColor(background);
        zones = new ZoneView(requireContext());
        zones.setId(R.id.reader_keys_canvas);
        root.addView(zones, new FrameLayout.LayoutParams(-1, -1));

        backButton = icon(R.drawable.v_arrow_left_dark_x24, R.string.reader_keys_back,
                () -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        backButton.setId(R.id.reader_keys_back);
        moreButton = icon(R.drawable.v_dots_vertical_x24, R.string.reader_keys_more, this::showMore);
        moreButton.setId(R.id.reader_keys_more);
        directionButton = icon(R.drawable.v_arrow_right_x24, R.string.settings_read_reading_direction, this::cycleDirection);
        directionButton.setId(R.id.reader_keys_direction);
        directionButton.setImageTintList(ColorStateList.valueOf(foreground));
        floatingButton(root, backButton, Gravity.TOP | Gravity.LEFT);
        helpButton = icon(R.drawable.v_help_circle_x24, R.string.reader_keys_help, this::toggleHelp);
        helpButton.setId(R.id.reader_keys_help);
        floatingButton(root, helpButton, Gravity.TOP | Gravity.LEFT);
        ((FrameLayout.LayoutParams) helpButton.getLayoutParams()).leftMargin = dp(68);
        floatingButton(root, directionButton, Gravity.TOP | Gravity.RIGHT);
        animatedButton = icon(R.drawable.v_animated_webp_x24, R.string.reader_keys_animated_control,
                () -> { animatedMode = !animatedMode; refresh(); });
        animatedButton.setId(R.id.reader_keys_animated_control);
        floatingButton(root, animatedButton, Gravity.TOP | Gravity.RIGHT);
        ((FrameLayout.LayoutParams) animatedButton.getLayoutParams()).rightMargin = dp(68);
        copyButton = icon(R.drawable.v_copy_x24, R.string.reader_keys_copy_apply, this::copyChanges);
        copyButton.setId(R.id.reader_keys_copy_apply);
        floatingButton(root, copyButton, Gravity.TOP | Gravity.RIGHT);
        ((FrameLayout.LayoutParams) copyButton.getLayoutParams()).rightMargin = dp(124);
        copyButton.setImageTintList(ColorStateList.valueOf(accent));
        copyButton.setVisibility(View.GONE);

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
            FrameLayout.LayoutParams help = (FrameLayout.LayoutParams) helpButton.getLayoutParams();
            help.leftMargin = dp(68) + safe.left; help.topMargin = dp(12) + safe.top;
            helpButton.setLayoutParams(help);
            helpInsets.set(safe.left, safe.top, safe.right, safe.bottom);
            if (helpOverlay != null) helpOverlay.requestLayout();
            FrameLayout.LayoutParams right = (FrameLayout.LayoutParams) directionButton.getLayoutParams();
            right.rightMargin = dp(12) + safe.right; right.topMargin = dp(12) + safe.top;
            directionButton.setLayoutParams(right);
            FrameLayout.LayoutParams animated = (FrameLayout.LayoutParams) animatedButton.getLayoutParams();
            animated.rightMargin = dp(68) + safe.right; animated.topMargin = dp(12) + safe.top;
            animatedButton.setLayoutParams(animated);
            FrameLayout.LayoutParams copy = (FrameLayout.LayoutParams) copyButton.getLayoutParams();
            copy.rightMargin = dp(124) + safe.right; copy.topMargin = dp(12) + safe.top;
            copyButton.setLayoutParams(copy);
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
                if (helpOverlay != null) { hideHelp(); return; }
                guard(() -> { setEnabled(false); requireActivity().getOnBackPressedDispatcher().onBackPressed(); });
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        ViewCompat.requestApplyInsets(view);
        refresh();
        if (state != null && state.getBoolean("helpVisible")) toggleHelp();
    }

    @Override public void onDestroyView() {
        hideHelp();
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
        }
        zones = null; profileButton = null; saveButton = null; directionButton = null;
        animatedButton = null; helpButton = null; copyButton = null;
        bottomBar = null; backButton = null; moreButton = null; back = null;
        super.onDestroyView();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (draft != null) state.putString("draft", draft.toJson().toString());
        if (copyBaseline != null) state.putString("copyBaseline", copyBaseline.toJson().toString());
        state.putInt("region", region);
        state.putInt("direction", direction);
        state.putBoolean("animatedMode", animatedMode);
        state.putBoolean("helpVisible", helpOverlay != null);
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
        copyButton.setVisibility(draft.hasKeyChanges(copyBaseline, direction, animatedMode) ? View.VISIBLE : View.GONE);
        animatedButton.setSelected(animatedMode);
        animatedButton.setImageTintList(ColorStateList.valueOf(animatedMode ? accent : foreground));
        String modeDescription = getString(animatedMode ? R.string.reader_keys_edit_normal : R.string.reader_keys_edit_animated);
        animatedButton.setContentDescription(modeDescription);
        ViewCompat.setTooltipText(animatedButton, modeDescription);
        int icon, label;
        switch (direction) {
            case GalleryView.LAYOUT_RIGHT_TO_LEFT -> {
                icon = R.drawable.v_arrow_left_x24;
                label = R.string.reader_keys_direction_right_to_left;
            }
            case GalleryView.LAYOUT_TOP_TO_BOTTOM -> {
                icon = R.drawable.v_arrow_down_x24;
                label = R.string.reader_keys_direction_top_to_bottom;
            }
            default -> {
                icon = R.drawable.v_arrow_right_x24;
                label = R.string.reader_keys_direction_left_to_right;
            }
        }
        directionButton.setImageResource(icon);
        String description = getString(R.string.reader_keys_edit_direction, getString(label));
        directionButton.setContentDescription(description);
        ViewCompat.setTooltipText(directionButton, description);
        if (back != null) back.setEnabled(changed || helpOverlay != null);
        zones.accessibility.invalidateRoot();
        zones.invalidate();
    }

    private void cycleDirection() {
        direction = (direction + 1) % ReaderKeyMap.DIRECTION_COUNT;
        refresh();
    }

    private void copyChanges() {
        int copied = draft.copyKeyChangesToOtherModes(copyBaseline, direction, animatedMode);
        refresh();
        Toast.makeText(requireContext(), copied > 0 ? R.string.reader_keys_copy_applied
                : R.string.reader_keys_copy_skipped, Toast.LENGTH_SHORT).show();
    }

    private void toggleHelp() {
        if (helpOverlay != null) { hideHelp(); return; }
        FrameLayout root = (FrameLayout) requireView();
        helpOverlay = new HelpOverlay(requireContext());
        helpOverlay.setId(R.id.reader_keys_help_overlay);
        root.addView(helpOverlay, new FrameLayout.LayoutParams(-1, -1));
        // Keep the question mark available to toggle the modal help layer.
        helpButton.setElevation(dp(10));
        helpButton.setAlpha(1f);
        helpButton.setSelected(true);
        helpButton.setImageTintList(ColorStateList.valueOf(accent));
        helpButton.setContentDescription(getString(R.string.reader_keys_close_help));
        ViewCompat.setTooltipText(helpButton, getString(R.string.reader_keys_close_help));
        zones.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        bottomBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (View button : new View[]{backButton, directionButton, animatedButton, copyButton})
            button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        back.setEnabled(true);
    }

    private void hideHelp() {
        if (helpOverlay == null) return;
        ((FrameLayout) helpOverlay.getParent()).removeView(helpOverlay);
        helpOverlay = null;
        helpButton.setElevation(dp(3));
        helpButton.setAlpha(.5f);
        helpButton.setSelected(false);
        helpButton.setImageTintList(ColorStateList.valueOf(secondary));
        helpButton.setContentDescription(getString(R.string.reader_keys_help));
        ViewCompat.setTooltipText(helpButton, getString(R.string.reader_keys_help));
        for (View view : new View[]{zones, bottomBar, backButton, directionButton, animatedButton, copyButton})
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        zones.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (back != null) back.setEnabled(dirty());
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
        int value = draft.keys(direction, animatedMode)[area * ReaderKeyMap.GESTURE_COUNT + gesture];
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
    private String regionText(int area) {
        StringBuilder text = new StringBuilder();
        int count = ReaderKeyMap.GESTURE_COUNT;
        for (int gesture = 0; gesture < count; gesture++) {
            if (gesture > 0) text.append('\n');
            text.append(gestures[gesture]).append(": ").append(actionLabel(area, gesture));
        }
        return text.toString();
    }
    private void chooseGesture() {
        String[] rows = new String[ReaderKeyMap.GESTURE_COUNT];
        for (int i = 0; i < rows.length; i++) rows[i] = gestures[i] + ": " + actionLabel(region, i);
        new AlertDialog.Builder(requireContext()).setTitle(R.string.reader_keys_edit_actions)
                .setItems(rows, (dialog, which) -> chooseAction(which)).show();
    }
    private void chooseAction(int gesture) {
        int count = ReaderKeyMap.SWITCH_PROFILE + (animatedMode ? 2 : 1);
        int[] values = new int[count];
        String[] labels = new String[count];
        labels[0] = defaultLabel(region, gesture);
        int row = 0;
        for (int value = ReaderKeyMap.LEGACY; value <= ReaderKeyMap.SWITCH_PROFILE; value++) {
            if (!animatedMode && value == ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL) continue;
            values[row] = value;
            if (row > 0) labels[row] = actionCaption(value, gesture);
            row++;
        }
        int index = region * ReaderKeyMap.GESTURE_COUNT + gesture;
        int[] keys = draft.keys(direction, animatedMode);
        int selected = -1;
        for (int i = 0; i < count; i++) if (values[i] == keys[index]) selected = i;
        new AlertDialog.Builder(requireContext()).setTitle(gestures[gesture])
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    keys[index] = values[which]; dialog.dismiss(); refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private PopupMenu showMore() {
        PopupMenu menu = new PopupMenu(requireContext(), moreButton);
        menu.getMenu().add(0, R.id.reader_keys_unified_areas, 0, R.string.reader_keys_unified_areas)
                .setCheckable(true).setChecked(draft.unifiedTouchAreas);
        menu.getMenu().add(0, 1, 1, getString(R.string.reader_keys_orientation_swipe,
                getResources().getStringArray(R.array.reader_keys_swipe_choices)[draft.orientationSwipe]));
        menu.getMenu().add(0, 2, 2, R.string.reader_keys_reset_sizes);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.reader_keys_unified_areas -> {
                    draft.setUnifiedTouchAreas(!draft.unifiedTouchAreas, direction); refresh();
                }
                case 1 -> chooseOrientationSwipe();
                case 2 -> { draft.setAreas(direction, ReaderTouchAreas.defaults()); refresh(); }
            }
            return true;
        });
        menu.show();
        return menu;
    }
    private String lineName(int line) {
        if (line == ANIMATED_LINE) return getString(R.string.reader_keys_animated_area_title);
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
                    copyBaseline = draft.copy();
                    dialog.dismiss(); refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addProfile(boolean copy) {
        ReaderKeyProfiles.Profile profile = copy ? draft.duplicate() : new ReaderKeyProfiles.Profile("");
        profiles.profiles.add(profile);
        profiles.selected = profiles.profiles.size() - 1;
        profiles.save(); draft = profile.copy(); copyBaseline = draft.copy(); refresh();
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
                                        profiles.save(); draft = profiles.active().copy(); copyBaseline = draft.copy(); refresh();
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
        profiles.save(); copyBaseline = draft.copy(); refresh();
        Toast.makeText(requireContext(), R.string.reader_keys_saved, Toast.LENGTH_SHORT).show();
    }

    private void guard(Runnable next) {
        if (!dirty()) { next.run(); return; }
        new AlertDialog.Builder(requireContext()).setMessage(R.string.reader_keys_unsaved)
                .setPositiveButton(R.string.reader_keys_save, (dialog, which) -> { saveDraft(); next.run(); })
                .setNeutralButton(R.string.reader_keys_discard, (dialog, which) -> {
                    draft = profiles.active().copy(); copyBaseline = draft.copy(); refresh(); next.run();
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
        button.setAlpha(.5f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(48), gravity);
        params.setMargins(dp(12), dp(12), dp(12), 0); root.addView(button, params);
    }

    /** One modal layer allows every tooltip to stay visible without PopupWindow focus conflicts. */
    private final class HelpOverlay extends FrameLayout {
        private final List<HelpHint> hints = new ArrayList<>();
        private final Paint connector = new Paint(Paint.ANTI_ALIAS_FLAG);

        HelpOverlay(Context context) {
            super(context);
            setElevation(dp(8));
            setBackgroundColor(ColorUtils.setAlphaComponent(background, 150));
            setOnClickListener(v -> hideHelp());
            setContentDescription(getString(R.string.reader_keys_close_help));
            addButtonHint(backButton);
            addButtonHint(helpButton);
            addButtonHint(directionButton);
            addButtonHint(animatedButton);
            if (copyButton.getVisibility() == View.VISIBLE) addButtonHint(copyButton);
            addHint(null, ACTION_TEXT_ANCHOR, getString(R.string.reader_keys_help_quick_tap));
            addHint(null, ReaderTouchAreas.CENTER_BOTTOM_SPLIT, getString(R.string.reader_keys_help_resize));
            addHint(profileButton, -1, getString(R.string.reader_keys_select_profile));
            for (int id : new int[]{R.id.reader_keys_more, R.id.reader_keys_add, R.id.reader_keys_manage, R.id.reader_keys_save})
                addButtonHint(requireView().findViewById(id));
        }

        private void addButtonHint(View button) { addHint(button, -1, button.getContentDescription()); }

        private void addHint(@Nullable View anchor, int line, CharSequence text) {
            TextView label = new AppCompatTextView(getContext());
            label.setText(text);
            label.setTextColor(foreground);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(8), dp(5), dp(8), dp(5));
            label.setElevation(dp(2));
            GradientDrawable bubble = new GradientDrawable();
            bubble.setColor(surface);
            bubble.setCornerRadius(dp(8));
            bubble.setStroke(dp(1), ColorUtils.setAlphaComponent(foreground, 40));
            label.setBackground(bubble);
            label.setOnClickListener(v -> hideHelp());
            label.setContentDescription(text + ". " + getString(R.string.reader_keys_close_help));
            addView(label, new FrameLayout.LayoutParams(-2, -2));
            hints.add(new HelpHint(label, anchor, line));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
            int available = Math.max(1, getMeasuredWidth() - helpInsets.left - helpInsets.right - dp(16));
            for (HelpHint hint : hints) hint.label.measure(
                    MeasureSpec.makeMeasureSpec(Math.min(available, dp(hint.anchor == null ? 180 : 160)), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            List<Rect> occupied = new ArrayList<>();
            int minX = helpInsets.left + dp(8), maxX = getWidth() - helpInsets.right - dp(8);
            int minY = backButton.getBottom() + dp(8), maxY = bottomBar.getTop() - dp(8);
            for (HelpHint hint : hints) {
                boolean below = hint.line == ReaderTouchAreas.CENTER_BOTTOM_SPLIT
                        || (hint.anchor != null && hint.anchor.getParent() == getParent());
                if (hint.line == ACTION_TEXT_ANCHOR) {
                    RectF text = zones.actionTextBounds(ReaderKeyMap.LEFT_TOP);
                    hint.x = text.right; hint.y = text.centerY();
                } else if (hint.line >= 0) {
                    float[] segment = zones.lineSegment(hint.line);
                    hint.x = (segment[0] + segment[2]) / 2;
                    hint.y = segment[1];
                } else {
                    Rect anchor = new Rect();
                    hint.anchor.getDrawingRect(anchor);
                    ((FrameLayout) getParent()).offsetDescendantRectToMyCoords(hint.anchor, anchor);
                    hint.x = anchor.exactCenterX();
                    hint.y = below ? anchor.bottom : anchor.top;
                }
                int width = hint.label.getMeasuredWidth(), height = hint.label.getMeasuredHeight();
                int left = Math.max(minX, Math.min(maxX - width, Math.round(hint.x - width / 2f)));
                int top = Math.max(minY, Math.min(maxY - height,
                        Math.round(hint.y + (below ? dp(8) : -dp(8) - height))));
                if (hint.line == ACTION_TEXT_ANCHOR) {
                    left = Math.max(minX, Math.min(maxX - width, Math.round(hint.x + dp(12))));
                    top = Math.max(minY, Math.min(maxY - height, Math.round(hint.y - height / 2f)));
                }
                Rect bounds = new Rect(left, top, left + width, top + height);
                // Try the closest free row on either side, including when a line is near an edge.
                List<Integer> rows = new ArrayList<>();
                rows.add(top);
                for (Rect previous : occupied) {
                    rows.add(previous.top - height - dp(4));
                    rows.add(previous.bottom + dp(4));
                }
                int bestDistance = Integer.MAX_VALUE;
                for (int row : rows) {
                    if (row < minY || row + height > maxY) continue;
                    Rect candidate = new Rect(left, row, left + width, row + height);
                    boolean overlaps = false;
                    for (Rect previous : occupied) {
                        Rect padded = new Rect(previous);
                        padded.inset(-dp(3), -dp(3));
                        if (Rect.intersects(candidate, padded)) { overlaps = true; break; }
                    }
                    int distance = Math.abs(row - top);
                    // Keep each boundary explanation on its intended side whenever space permits.
                    if (hint.line >= 0 && (below ? row < hint.y : row + height > hint.y)) distance += getHeight();
                    if (!overlaps && distance < bestDistance) {
                        bounds.set(candidate);
                        bestDistance = distance;
                    }
                }
                hint.label.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
                occupied.add(bounds);
            }
        }

        @Override protected void dispatchDraw(@NonNull Canvas canvas) {
            connector.setColor(ColorUtils.setAlphaComponent(accent, 180));
            connector.setStrokeWidth(dp(1));
            for (HelpHint hint : hints) {
                TextView label = hint.label;
                float endX = Math.max(label.getLeft(), Math.min(label.getRight(), hint.x));
                float endY = Math.max(label.getTop(), Math.min(label.getBottom(), hint.y));
                canvas.drawLine(hint.x, hint.y, endX, endY, connector);
                canvas.drawCircle(hint.x, hint.y, dp(2), connector);
            }
            super.dispatchDraw(canvas);
        }

        private final class HelpHint {
            final TextView label;
            final View anchor;
            final int line;
            float x, y;
            HelpHint(TextView label, View anchor, int line) {
                this.label = label; this.anchor = anchor; this.line = line;
            }
        }
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
                    for (int line = 0; line < ReaderTouchAreas.ANIMATED_SPLIT; line++) areas.add(ReaderKeyMap.REGION_COUNT + line);
                    if (animatedMode) areas.add(ReaderKeyMap.REGION_COUNT + ANIMATED_LINE);
                }
                @Override protected void onPopulateNodeForVirtualView(int area, AccessibilityNodeInfoCompat node) {
                    if (area >= ReaderKeyMap.REGION_COUNT) {
                        int line = area - ReaderKeyMap.REGION_COUNT;
                        float[] segment = lineSegment(line);
                        int inset = dp(12);
                        node.setBoundsInParent(new Rect(Math.max(0, (int) segment[0] - inset), Math.max(0, (int) segment[1] - inset),
                                Math.min(getWidth(), (int) segment[2] + inset), Math.min(getHeight(), (int) segment[3] + inset)));
                        node.setContentDescription(lineName(line) + ", " + percent(draft.areas(direction).position(line)) + "%"
                                + (line == ANIMATED_LINE ? ", " + getString(R.string.reader_keys_animated_hint) : ""));
                        node.setClassName("android.widget.Button"); node.setClickable(true);
                        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
                        return;
                    }
                    RectF bounds = areaBounds(area);
                    node.setBoundsInParent(new Rect((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom));
                    node.setContentDescription(regions[area] + ", " + regionText(area));
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
            if (animatedMode) {
                Path takeover = new Path();
                takeover.addRect(0, animatedY(), getWidth(), getHeight(), Path.Direction.CW);
                RectF centerBottom = areaBounds(ReaderKeyMap.CENTER_BOTTOM);
                takeover.addRect(centerBottom, Path.Direction.CW);
                border.setColor(ColorUtils.setAlphaComponent(accent, 14));
                canvas.drawPath(takeover, border);
            }
            border.setColor(ColorUtils.setAlphaComponent(animatedMode ? accent : foreground, animatedMode ? 100 : 65));
            border.setStrokeWidth(dp(1));
            if (animatedMode) border.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            for (int line = 0; line < ReaderTouchAreas.ANIMATED_SPLIT; line++) {
                float[] segment = lineSegment(line);
                canvas.drawLine(segment[0], segment[1], segment[2], segment[3], border);
            }
            border.setPathEffect(null);
            if (!animatedMode) drawAnimatedBoundary(canvas);
            for (int area = 0; area < ReaderKeyMap.REGION_COUNT; area++) drawActions(canvas, area, areaBounds(area));
            if (animatedMode) {
                drawAnimatedHint(canvas);
                drawAnimatedBoundary(canvas);
                drawHandleAt(canvas, ANIMATED_LINE, getWidth() / 3f, animatedY());
                drawHandleAt(canvas, ANIMATED_LINE, getWidth() * 2 / 3f, animatedY());
            } else {
                for (int line = 0; line < ReaderTouchAreas.ANIMATED_SPLIT; line++) drawHandle(canvas, line);
            }
        }

        private void drawActions(Canvas canvas, int area, RectF rect) {
            ActionTextBlock block = actionTextBlock(area, rect);
            canvas.save(); canvas.clipRect(rect);
            canvas.translate(block.x, block.y);
            block.layout.draw(canvas); canvas.restore();
        }

        private RectF actionTextBounds(int area) {
            RectF rect = areaBounds(area);
            ActionTextBlock block = actionTextBlock(area, rect);
            float right = 0;
            for (int line = 0; line < block.layout.getLineCount(); line++)
                right = Math.max(right, block.layout.getLineRight(line));
            return new RectF(block.x, Math.min(rect.bottom, block.y), Math.min(rect.right, block.x + right),
                    Math.min(rect.bottom, block.y + block.layout.getHeight()));
        }

        private ActionTextBlock actionTextBlock(int area, RectF rect) {
            float verticalInset = Math.min(dp(12), rect.height() / 12f);
            float top = rect.top + verticalInset, bottom = rect.bottom - verticalInset;
            if (rect.bottom == getHeight() && bottomBar.getHeight() > 0) bottom = Math.min(bottom, bottomBar.getTop() - dp(12));
            if (animatedMode && rect.bottom > animatedY()) {
                if (animatedY() - top >= dp(60)) bottom = Math.min(bottom, animatedY() - dp(12));
                else top = Math.max(top, animatedHintBottom() + dp(12));
            }
            SpannableStringBuilder text = new SpannableStringBuilder(regionText(area));
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
            return new ActionTextBlock(layout, rect.left + inset,
                    Math.max(top, top + (bottom - top - layout.getHeight()) / 2));
        }

        private record ActionTextBlock(StaticLayout layout, float x, float y) { }

        private float[] lineSegment(int line) {
            float[] segment = draft.areas(direction).segment(line);
            return new float[]{segment[0] * getWidth(), segment[1] * getHeight(), segment[2] * getWidth(), segment[3] * getHeight()};
        }

        private int lineAt(float x, float y) {
            int nearest = -1;
            float best = dp(12), bestHandleDistance = Float.MAX_VALUE;
            for (int line = 0; line < (animatedMode ? ReaderTouchAreas.LINE_COUNT : ANIMATED_LINE); line++) {
                float[] s = lineSegment(line);
                float px = Math.max(s[0], Math.min(s[2], x)), py = Math.max(s[1], Math.min(s[3], y));
                float distance = (float) Math.hypot(x - px, y - py);
                float handleDistance = (float) Math.hypot(x - (s[0] + s[2]) / 2, y - (s[1] + s[3]) / 2);
                if (line == ANIMATED_LINE) handleDistance = (float) Math.min(
                        Math.hypot(x - getWidth() / 3f, y - s[1]), Math.hypot(x - getWidth() * 2 / 3f, y - s[1]));
                if (distance < best || (distance == best && handleDistance < bestHandleDistance)) {
                    nearest = line; best = distance; bestHandleDistance = handleDistance;
                }
            }
            return nearest;
        }

        private void drawHandle(Canvas canvas, int line) {
            float[] segment = lineSegment(line);
            float x = (segment[0] + segment[2]) / 2, y = (segment[1] + segment[3]) / 2;
            drawHandleAt(canvas, line, x, y);
        }

        private void drawHandleAt(Canvas canvas, int line, float x, float y) {
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
            float y = animatedY();
            if (animatedMode) {
                border.setColor(background);
                border.setStrokeWidth(dp(5));
                canvas.drawLine(0, y, getWidth(), y, border);
                border.setColor(accent);
                border.setStrokeWidth(dp(1));
                canvas.drawLine(0, y, getWidth(), y, border);
                return;
            }
            border.setColor(ColorUtils.setAlphaComponent(accent, 100));
            border.setStrokeWidth(dp(1));
            if (!animatedMode) border.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            canvas.drawLine(0, y, getWidth(), y, border);
            border.setPathEffect(null);
        }

        private float animatedY() {
            return getHeight() * draft.areas(direction).position(ANIMATED_LINE);
        }

        private void drawAnimatedHint(Canvas canvas) {
            boolean grayTheme = Settings.getTheme() == Settings.THEME_DARK;
            StaticLayout layout = animatedHintLayout(grayTheme);
            int paddingX = dp(4), paddingY = dp(2), width = layout.getWidth();
            float height = layout.getHeight() + paddingY * 2;
            float left = (getWidth() - width - paddingX * 2) / 2f;
            float top = animatedHintBottom() - height;
            border.setColor(ColorUtils.setAlphaComponent(grayTheme ? Color.WHITE : Color.GRAY, 196));
            canvas.drawRoundRect(left, top, left + width + paddingX * 2, top + height, dp(4), dp(4), border);
            canvas.save(); canvas.translate(left + paddingX, top + paddingY);
            layout.draw(canvas); canvas.restore();
        }

        private StaticLayout animatedHintLayout(boolean grayTheme) {
            textPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
            textPaint.setColor(grayTheme ? Color.DKGRAY : Color.WHITE);
            String hint = getString(R.string.reader_keys_animated_hint);
            int width = Math.max(1, Math.min(getWidth() - dp(24), (int) Math.ceil(textPaint.measureText(hint))));
            return StaticLayout.Builder.obtain(hint, 0, hint.length(), textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
        }

        private float animatedHintBottom() {
            float height = animatedHintLayout(Settings.getTheme() == Settings.THEME_DARK).getHeight() + dp(4);
            float bottom = bottomBar.getHeight() > 0 ? bottomBar.getTop() - dp(4) : getHeight() - dp(4);
            float top = Math.max(dp(4), Math.min(animatedY() + dp(14), bottom - height));
            return top + height;
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
                        draft.setAreas(direction, downAreas.withPosition(pressedLine, position));
                        refresh();
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (!moved) {
                            if (pressedLine >= 0) performClick();
                            else {
                                int selected = draft.areas(direction).region(x, y, getWidth(), getHeight());
                                if (selected >= 0) { region = selected; refresh(); performClick(); }
                            }
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
            if (dragging && downAreas != null) {
                draft.setAreas(direction, downAreas);
                refresh();
            }
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
