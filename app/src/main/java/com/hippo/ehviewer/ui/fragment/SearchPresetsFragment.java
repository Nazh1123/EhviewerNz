package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.SettingsActivity;
import com.hippo.ehviewer.widget.SearchPresetChip;
import java.util.Collections;
import java.util.List;

/** A dedicated settings page; query toggles remain in the search options. */
public final class SearchPresetsFragment extends Fragment {
    private PresetAdapter adapter;
    private View empty;
    private ItemTouchHelper touchHelper;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getChildFragmentManager().setFragmentResultListener(SearchPresetDialog.RESULT_CHANGED,
                this, (key, result) -> refresh());
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_search_presets, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        RecyclerView list = view.findViewById(R.id.search_presets_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new PresetAdapter();
        list.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean isLongPressDragEnabled() { return false; }

            @Override public boolean onMove(@NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
                int from = source.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (adapter == null || from == RecyclerView.NO_POSITION
                        || to == RecyclerView.NO_POSITION || from == to) return false;
                adapter.tags.add(to, adapter.tags.remove(from));
                Settings.putSearchPresetTags(adapter.tags);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {}
        });
        touchHelper.attachToRecyclerView(list);
        empty = view.findViewById(R.id.search_presets_empty);
        view.findViewById(R.id.search_presets_add).setOnClickListener(ignored -> edit(null));
        refresh();
    }

    @Override public void onResume() {
        super.onResume();
        if (getActivity() instanceof SettingsActivity activity) {
            activity.setSettingsTitle(R.string.settings_search_preset_tags);
        }
        refresh();
    }

    @Override public void onDestroyView() {
        touchHelper.attachToRecyclerView(null);
        touchHelper = null;
        adapter = null;
        empty = null;
        super.onDestroyView();
    }

    private void refresh() {
        if (adapter == null) return;
        adapter.tags = Settings.getSearchPresetTags();
        adapter.notifyDataSetChanged();
        empty.setVisibility(adapter.tags.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void edit(@Nullable String preset) {
        if (getChildFragmentManager().findFragmentByTag("preset_editor") == null) {
            SearchPresetDialog.editor(preset).show(getChildFragmentManager(), "preset_editor");
        }
    }

    private final class PresetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<String> tags = Collections.emptyList();

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_search_preset, parent, false)) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            String preset = tags.get(position);
            View row = holder.itemView;
            View drag = row.findViewById(R.id.search_preset_drag);
            drag.setContentDescription(getString(R.string.settings_search_preset_reorder, preset));
            drag.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null
                        && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                    touchHelper.startDrag(holder);
                }
                return false;
            });
            ((TextView) row.findViewById(R.id.search_preset_preview)).setText(
                    SearchPresetChip.labelFor(requireContext(), preset));
            ((TextView) row.findViewById(R.id.search_preset_query)).setText(preset);
            View details = row.findViewById(R.id.search_preset_details);
            details.setContentDescription(getString(R.string.search_tag_edit, preset));
            details.setOnClickListener(view -> edit(preset));
            View edit = row.findViewById(R.id.search_preset_edit);
            edit.setContentDescription(getString(R.string.search_tag_edit, preset));
            edit.setOnClickListener(view -> edit(preset));
            View delete = row.findViewById(R.id.search_preset_delete);
            delete.setContentDescription(getString(R.string.search_tag_delete, preset));
            delete.setOnClickListener(view -> {
                if (getChildFragmentManager().findFragmentByTag("preset_delete") == null) {
                    SearchPresetDialog.deletion(preset).show(getChildFragmentManager(), "preset_delete");
                }
            });
        }

        @Override public int getItemCount() { return tags.size(); }
    }
}
