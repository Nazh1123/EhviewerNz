package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

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

    public static AlertDialog showDialog(Context context, Runnable onSaved,
            Runnable onManageBookmarks) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = LayoutInflater.from(builder.getContext()).inflate(
                R.layout.dialog_subscription_update_settings, null);
        // Homepage scanning is disabled along with its settings controls.
        // SwitchCompat homepage = view.findViewById(R.id.subscription_homepage_scan);
        // homepage.setChecked(Settings.getSubscriptionHomepageScan());
        SwitchCompat automatic = view.findViewById(R.id.auto_subscription_updates);
        SwitchCompat eh = view.findViewById(R.id.auto_subscription_updates_eh);
        SwitchCompat bookmark = view.findViewById(R.id.auto_subscription_updates_bookmark);
        TextView intervalLabel = view.findViewById(R.id.auto_subscription_update_interval_label);
        EditText interval = view.findViewById(R.id.auto_subscription_update_interval);
        // Use the dialog's switch text appearance, including its disabled-state colors.
        for (TextView text : new TextView[]{intervalLabel, interval}) {
            text.setTextColor(bookmark.getTextColors());
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, bookmark.getTextSize());
        }
        automatic.setChecked(Settings.getAutoSubscriptionUpdates());
        eh.setChecked(Settings.getAutoSubscriptionUpdatesEh());
        bookmark.setChecked(Settings.getAutoSubscriptionUpdatesBookmark());
        interval.setText(Integer.toString(Settings.getAutoSubscriptionUpdateIntervalMinutes()));
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            eh.setEnabled(enabled);
            bookmark.setEnabled(enabled);
            intervalLabel.setEnabled(enabled);
            interval.setEnabled(enabled);
        });
        eh.setEnabled(automatic.isChecked());
        bookmark.setEnabled(automatic.isChecked());
        intervalLabel.setEnabled(automatic.isChecked());
        interval.setEnabled(automatic.isChecked());

        AlertDialog dialog = builder
                .setTitle(R.string.update_subscriptions)
                .setView(view)
                .setNeutralButton(R.string.manage_subscription_bookmarks,
                        (ignored, which) -> onManageBookmarks.run())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String normalized = Settings.normalizeAutoSubscriptionUpdateInterval(
                    interval.getText().toString());
            if (normalized == null && automatic.isChecked()) {
                interval.setError(context.getString(
                        R.string.settings_fork_auto_subscription_update_interval_invalid));
                interval.requestFocus();
                return;
            }
            // Settings.putBoolean(Settings.KEY_SUBSCRIPTION_HOMEPAGE_SCAN, homepage.isChecked());
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
