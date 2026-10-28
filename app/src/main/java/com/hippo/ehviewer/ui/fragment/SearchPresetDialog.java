package com.hippo.ehviewer.ui.fragment;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.SearchPresetQuery;
import com.hippo.ehviewer.widget.SearchPresetEditText;
import java.util.List;

/** Retains the draft and operation when the management page is recreated. */
public final class SearchPresetDialog extends DialogFragment {
    static final String RESULT_CHANGED = "search_presets_changed";
    private SearchPresetEditText input;

    static SearchPresetDialog editor(@Nullable String preset) { return create(preset, false); }
    static SearchPresetDialog deletion(String preset) { return create(preset, true); }

    private static SearchPresetDialog create(@Nullable String preset, boolean delete) {
        SearchPresetDialog dialog = new SearchPresetDialog();
        Bundle args = new Bundle();
        args.putString("preset", preset);
        args.putBoolean("delete", delete);
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        String preset = requireArguments().getString("preset");
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setNegativeButton(android.R.string.cancel, null);
        if (requireArguments().getBoolean("delete")) {
            return builder.setTitle(R.string.settings_search_preset_remove)
                    .setMessage(getString(R.string.settings_search_preset_remove_confirm, preset))
                    .setPositiveButton(R.string.settings_search_preset_remove, (dialog, which) -> {
                        List<String> tags = Settings.getSearchPresetTags();
                        if (tags.remove(preset)) save(tags);
                    }).create();
        }
        input = new SearchPresetEditText(requireContext());
        input.setId(R.id.search_preset_input);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setText(state != null ? state.getString("draft", preset) : preset, false);
        input.setSelection(input.length());
        FrameLayout container = new FrameLayout(requireContext());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(-1, -2));
        return builder.setTitle(preset == null ? R.string.settings_search_preset_add : R.string.settings_search_preset_edit)
                .setMessage(R.string.settings_search_preset_tag_hint).setView(container)
                .setPositiveButton(android.R.string.ok, null).create();
    }

    @Override public void onStart() {
        super.onStart();
        if (input == null) return;
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> confirm());
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            confirm();
            return true;
        });
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void confirm() {
        String value = input.getText().toString().trim();
        String identity = SearchPresetQuery.identity(value);
        if (identity == null) {
            input.setError(getString(R.string.settings_search_preset_tag_invalid));
            return;
        }
        List<String> tags = Settings.getSearchPresetTags();
        String original = requireArguments().getString("preset");
        int index = original == null ? -1 : tags.indexOf(original);
        for (int i = 0; i < tags.size(); i++) {
            if (i != index && identity.equals(SearchPresetQuery.identity(tags.get(i)))) {
                input.setError(getString(R.string.settings_search_preset_duplicate));
                return;
            }
        }
        if (index < 0) tags.add(value); else tags.set(index, value);
        save(tags);
        dismiss();
    }

    private void save(List<String> tags) {
        Settings.putSearchPresetTags(tags);
        requireActivity().setResult(Activity.RESULT_OK);
        getParentFragmentManager().setFragmentResult(RESULT_CHANGED, new Bundle());
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (input != null) state.putString("draft", input.getText().toString());
    }

    @Override public void onDestroyView() {
        input = null;
        super.onDestroyView();
    }
}
