package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.SettingsActivity;

import java.util.ArrayList;
import java.util.List;

import android.widget.Toast;

public class BasePreferenceFragmentCompat extends PreferenceFragmentCompat {
    private SettingsActivity settingsActivity;
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    }

    private void setBaseStyle(Preference preference) {
        String key = preference.getKey();
        boolean animatedWebpChild = Settings.KEY_ANIMATED_WEBP_SHOW_TIME.equals(key)
                || Settings.KEY_ANIMATED_WEBP_ALLOW_SEEK.equals(key)
                || Settings.KEY_ANIMATED_WEBP_AUTO_ADVANCE.equals(key)
                || Settings.KEY_ANIMATED_WEBP_AUTO_TRANSFER_BUTTON.equals(key)
                || Settings.KEY_ANIMATED_WEBP_LONG_PRESS_SPEED.equals(key);
        preference.setIconSpaceReserved(animatedWebpChild);
        if (Settings.KEY_ANIMATED_WEBP_LONG_PRESS_SPEED.equals(key)
                && preference instanceof EditTextPreference speedPreference) {
            configureAnimatedWebpLongPressSpeed(speedPreference);
        }
        if (preference instanceof PreferenceGroup group) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                setBaseStyle(group.getPreference(i));
            }
        }
    }

    private void configureAnimatedWebpLongPressSpeed(EditTextPreference preference) {
        String normalized = Settings.normalizeAnimatedWebpLongPressSpeed(preference.getText());
        if (normalized == null) normalized = "2.0";
        if (!normalized.equals(preference.getText())) {
            preference.setText(normalized);
        }
        preference.setSummaryProvider(valuePreference -> {
            String value = Settings.normalizeAnimatedWebpLongPressSpeed(
                    ((EditTextPreference) valuePreference).getText());
            return (value != null ? value : "2.0") + "x";
        });
        preference.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            editText.setSelectAllOnFocus(true);
            editText.setSingleLine(true);
        });
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            String value = Settings.normalizeAnimatedWebpLongPressSpeed(
                    String.valueOf(newValue));
            if (value == null) {
                Toast.makeText(requireContext(),
                        R.string.settings_animated_webp_long_press_speed_invalid,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!value.contentEquals(String.valueOf(newValue))) {
                preference.setText(value);
                return false;
            }
            return true;
        });
    }

    @Override
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        if (preferenceScreen != null)
            setBaseStyle(preferenceScreen);
        super.setPreferenceScreen(preferenceScreen);
    }

    @Override
    public void onDestroyView() {
        if (null==getActivity()){
            return;
        }
        SettingsActivity settingsActivity = (SettingsActivity) getActivity();
        settingsActivity.setSettingsTitle(R.string.settings);
        super.onDestroyView();

    }



}
