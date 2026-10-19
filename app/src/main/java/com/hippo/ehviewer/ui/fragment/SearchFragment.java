package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SearchPresetQuery;
import com.hippo.ehviewer.client.SearchRating;

public final class SearchFragment extends BasePreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.search_settings);
        for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            getPreferenceScreen().getPreference(i).setOnPreferenceChangeListener((preference, value) -> {
                notifySettingsChanged();
                return true;
            });
        }
        bindSearchPreset(Settings.KEY_SEARCH_PRESET_TAG_1, Settings.getSearchPresetTag1());
        bindSearchPreset(Settings.KEY_SEARCH_PRESET_TAG_2, Settings.getSearchPresetTag2());
        bindPersistentRating();
        ThumbnailInfoBarPreferences.bind(this, this::notifySettingsChanged);
    }

    private void notifySettingsChanged() {
        if (getActivity() != null) getActivity().setResult(Activity.RESULT_OK);
    }

    private void bindPersistentRating() {
        Preference preference = findPreference(Settings.KEY_PERSISTENT_MIN_RATING);
        preference.setSummaryProvider(ignored -> getString(R.string.settings_persistent_min_rating_description)
                + "\n" + getString(R.string.settings_persistent_min_rating_value,
                SearchRating.format(Settings.getConfiguredPersistentMinRatingHundredths())));
        preference.setOnPreferenceClickListener(ignored -> {
            EditText input = new EditText(requireContext());
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setSingleLine(true);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setText(SearchRating.format(Settings.getConfiguredPersistentMinRatingHundredths()));
            input.selectAll();
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_persistent_min_rating)
                    .setMessage(getString(R.string.settings_persistent_min_rating_description)
                            + "\n" + getString(R.string.search_rating_hint))
                    .setView(input)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, null).create();
            dialog.setOnShowListener(unused -> {
                android.view.View.OnClickListener confirm = view -> {
                    try {
                        Settings.putPersistentMinRatingHundredths(SearchRating.parse(input.getText().toString()));
                        notifySettingsChanged();
                        preference.setSummaryProvider(preference.getSummaryProvider());
                        dialog.dismiss();
                    } catch (IllegalArgumentException error) {
                        input.setError(getString(R.string.search_rating_invalid));
                    }
                };
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(confirm);
                input.setOnEditorActionListener((view, action, event) -> {
                    if (action != EditorInfo.IME_ACTION_DONE) return false;
                    confirm.onClick(view);
                    return true;
                });
                input.requestFocus();
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            });
            dialog.show();
            return true;
        });
    }

    private void bindSearchPreset(String key, String value) {
        EditTextPreference preference = findPreference(key);
        preference.setText(value);
        preference.setOnBindEditTextListener(editText -> {
            editText.setSingleLine(true);
            editText.setSelectAllOnFocus(true);
            editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        });
        preference.setOnPreferenceChangeListener((ignored, newValue) -> {
            if (!SearchPresetQuery.isValid((String) newValue)) {
                Toast.makeText(requireContext(), R.string.settings_search_preset_tag_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            notifySettingsChanged();
            return true;
        });
    }
}
