package com.hippo.ehviewer.widget;

import android.content.Context;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.SearchPresetQuery;
import com.hippo.ehviewer.client.SearchQueryTokenizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Completes one preset from the same local tag dictionary as the main search bar. */
public final class SearchPresetEditText extends AppCompatAutoCompleteTextView {
    private final Suggestions suggestions;

    public SearchPresetEditText(Context context) {
        super(context);
        setThreshold(1);
        setHint(R.string.search_preset_input_hint);
        suggestions = new Suggestions();
        setAdapter(suggestions);
        setOnItemClickListener((parent, view, position, id) -> setError(null));
    }

    @Override public void onFilterComplete(int count) {
        super.onFilterComplete(suggestions.source.equals(getText().toString()) ? count : 0);
    }

    @Override protected void onDetachedFromWindow() {
        dismissDropDown();
        super.onDetachedFromWindow();
    }

    private record Hint(String english, String translation, String query) {}
    private record Matches(String source, List<Hint> hints) {}

    private final class Suggestions extends BaseAdapter implements Filterable {
        private List<Hint> hints = Collections.emptyList();
        private String source = "";
        private final Filter filter = new Filter() {
            @Override protected FilterResults performFiltering(CharSequence constraint) {
                String source = constraint == null ? "" : constraint.toString();
                String keyword = SearchQueryTokenizer.suggestionKeyword(source);
                EhTagDatabase database = EhTagDatabase.getInstance(getContext());
                List<Hint> matches = new ArrayList<>();
                if (!keyword.isEmpty() && database != null) {
                    for (Pair<String, String> hint : database.suggest(keyword)) {
                        String query = SearchPresetQuery.formatSuggestion(hint.second);
                        if (query != null) matches.add(new Hint(hint.second, hint.first, query));
                    }
                }
                FilterResults result = new FilterResults();
                result.values = new Matches(source, matches);
                result.count = matches.size();
                return result;
            }

            @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                Matches matches = (Matches) results.values;
                // A result may arrive after more typing, completion, or closing the dialog.
                if (!isAttachedToWindow() || matches == null || !matches.source.equals(getText().toString())) return;
                hints = matches.hints;
                source = matches.source;
                if (hints.isEmpty()) notifyDataSetInvalidated(); else notifyDataSetChanged();
            }

            @Override public CharSequence convertResultToString(Object result) { return ((Hint) result).query; }
        };

        @Override public Filter getFilter() { return filter; }
        @Override public int getCount() { return hints.size(); }
        @Override public Object getItem(int position) { return hints.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            View row = recycled != null ? recycled : LayoutInflater.from(getContext())
                    .inflate(R.layout.search_suggestion_item, parent, false);
            Hint hint = hints.get(position);
            ((TextView) row.findViewById(R.id.hintView)).setText(hint.english);
            TextView translation = row.findViewById(R.id.textView);
            boolean showTranslation = Settings.getShowTagTranslations() && hint.translation != null
                    && !hint.translation.isEmpty() && !"null".equals(hint.translation);
            translation.setText(showTranslation ? hint.translation : "");
            translation.setVisibility(showTranslation ? View.VISIBLE : View.GONE);
            return row;
        }
    }
}
