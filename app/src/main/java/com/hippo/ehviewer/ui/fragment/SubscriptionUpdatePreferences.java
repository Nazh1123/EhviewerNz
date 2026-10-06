package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

public final class SubscriptionUpdatePreferences {
    private SubscriptionUpdatePreferences() {}

    public static void bind(PreferenceFragmentCompat fragment) {
        EditTextPreference preference = fragment.findPreference(
                Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL);
        if (preference == null) {
            return;
        }
        preference.setText(Integer.toString(Settings.getAutoSubscriptionUpdateIntervalMinutes()));
        preference.setSummaryProvider(ignored -> fragment.getString(
                R.string.settings_fork_auto_subscription_update_interval_summary,
                Settings.getAutoSubscriptionUpdateIntervalMinutes()));
        preference.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
            editText.setSingleLine(true);
            editText.setSelectAllOnFocus(true);
        });
        preference.setOnPreferenceChangeListener((ignored, newValue) -> {
            String normalized = Settings.normalizeAutoSubscriptionUpdateInterval(
                    String.valueOf(newValue));
            if (normalized == null) {
                Toast.makeText(fragment.requireContext(),
                        R.string.settings_fork_auto_subscription_update_interval_invalid,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            // Persist the normalized value once, including inputs with leading zeroes.
            preference.setText(normalized);
            return false;
        });
    }

    public static AlertDialog showDialog(Context context, Runnable onSaved) {
        View view = LayoutInflater.from(context).inflate(
                R.layout.dialog_subscription_update_settings, null);
        SwitchCompat automatic = view.findViewById(R.id.auto_subscription_updates);
        SwitchCompat eh = view.findViewById(R.id.auto_subscription_updates_eh);
        SwitchCompat bookmark = view.findViewById(R.id.auto_subscription_updates_bookmark);
        TextInputLayout intervalLayout = view.findViewById(
                R.id.auto_subscription_update_interval_layout);
        EditText interval = view.findViewById(R.id.auto_subscription_update_interval);
        automatic.setChecked(Settings.getAutoSubscriptionUpdates());
        eh.setChecked(Settings.getAutoSubscriptionUpdatesEh());
        bookmark.setChecked(Settings.getAutoSubscriptionUpdatesBookmark());
        interval.setText(Integer.toString(Settings.getAutoSubscriptionUpdateIntervalMinutes()));
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            eh.setEnabled(enabled);
            bookmark.setEnabled(enabled);
            intervalLayout.setEnabled(enabled);
        });
        eh.setEnabled(automatic.isChecked());
        bookmark.setEnabled(automatic.isChecked());
        intervalLayout.setEnabled(automatic.isChecked());

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.update_subscriptions)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String normalized = Settings.normalizeAutoSubscriptionUpdateInterval(
                    interval.getText().toString());
            if (normalized == null && automatic.isChecked()) {
                intervalLayout.setError(context.getString(
                        R.string.settings_fork_auto_subscription_update_interval_invalid));
                interval.requestFocus();
                return;
            }
            Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES,
                    automatic.isChecked());
            Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH,
                    eh.isChecked());
            Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK,
                    bookmark.isChecked());
            if (normalized != null) {
                Settings.putString(Settings.KEY_AUTO_SUBSCRIPTION_UPDATE_INTERVAL,
                        normalized);
            }
            onSaved.run();
            dialog.dismiss();
        });
        return dialog;
    }
}
