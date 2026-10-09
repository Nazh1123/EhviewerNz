package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.BookmarkSubscriptionTest;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.scene.gallery.list.QuickSearchScene;
import com.hippo.scene.StageActivity;

public final class SubscriptionFragment extends BasePreferenceFragmentCompat {
    private BookmarkSubscriptionTest mTest;
    private Preference mTestPreference;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.subscription_settings, rootKey);
        SubscriptionUpdatePreferences.bind(this);
        SubscriptionSidebarPreferences.bind(this, () -> requireActivity().setResult(Activity.RESULT_OK));
        Preference management = findPreference("bookmark_subscription_settings");
        if (management != null) management.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(requireContext(), MainActivity.class);
            intent.setAction(StageActivity.ACTION_START_SCENE);
            intent.putExtra(StageActivity.KEY_SCENE_NAME, QuickSearchScene.class.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            requireActivity().finish();
            return true;
        });
        mTest = EhApplication.getBookmarkSubscriptionTest(requireContext());
        mTestPreference = findPreference("test_bookmark_subscription");
        if (mTestPreference != null) mTestPreference.setOnPreferenceClickListener(preference -> {
            mTest.start();
            return true;
        });
        renderTestState();
    }

    @Override
    public void onResume() {
        super.onResume();
        mTest.setListener(() -> {
            renderTestState();
            if (!mTest.isRunning()) {
                Toast.makeText(requireContext(), BookmarkSubscriptionTest.getLastResult().failed()
                        ? R.string.settings_test_bookmark_subscription_failed
                        : R.string.settings_test_bookmark_subscription_finished, Toast.LENGTH_SHORT).show();
            }
        });
        renderTestState();
    }

    @Override
    public void onPause() {
        mTest.setListener(null);
        super.onPause();
    }

    private void renderTestState() {
        if (mTestPreference == null) return;
        BookmarkSubscriptionTest.Result result = BookmarkSubscriptionTest.getLastResult();
        mTestPreference.setEnabled(!mTest.isRunning());
        mTestPreference.setTitle(mTest.isRunning() ? R.string.settings_test_bookmark_subscription_running
                : R.string.settings_test_bookmark_subscription);
        mTestPreference.setSummary(getString(R.string.settings_test_bookmark_subscription_summary,
                result.bookmarkText(), result.requestText(), result.durationText()));
    }
}
