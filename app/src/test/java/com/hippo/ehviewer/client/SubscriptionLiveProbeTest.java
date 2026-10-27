package com.hippo.ehviewer.client;

import android.app.Application;
import androidx.preference.PreferenceManager;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.QuickSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Opt-in export of real search plans for a local, authenticated network experiment. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class SubscriptionLiveProbeTest {
    @Test public void exportSavedPlans() throws Exception {
        String directory = System.getenv("EH_SUBSCRIPTION_PROBE_DIR");
        Assume.assumeTrue("Requires an explicitly supplied local dataset", directory != null);
        Path root = Paths.get(directory);
        Application application = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(application).edit().clear()
                .putString("gallery_site", "1").commit();
        Settings.initialize(application);
        JSONArray rows = new JSONArray(new String(Files.readAllBytes(root.resolve("bookmarks.json")), StandardCharsets.UTF_8));
        ArrayList<QuickSearch> bookmarks = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            QuickSearch bookmark = new QuickSearch();
            bookmark.id = row.getLong("_id");
            bookmark.name = row.optString("NAME");
            bookmark.mode = row.getInt("MODE");
            bookmark.category = row.getInt("CATEGORY");
            bookmark.keyword = row.isNull("KEYWORD") ? null : row.getString("KEYWORD");
            bookmark.advanceSearch = row.getInt("ADVANCE_SEARCH");
            bookmark.minRating = row.getInt("MIN_RATING");
            bookmark.minRatingHundredths = row.optInt("MIN_RATING_HUNDREDTHS");
            bookmark.ratingOverride = row.optInt("RATING_OVERRIDE") != 0;
            bookmark.pageFrom = row.getInt("PAGE_FROM");
            bookmark.pageTo = row.getInt("PAGE_TO");
            bookmark.subscribed = true;
            bookmarks.add(bookmark);
        }
        JSONArray plans = new JSONArray();
        for (BookmarkSubscriptionPlanner.Source plan : BookmarkSubscriptionPlanner.plan(bookmarks)) {
            plans.put(new JSONObject().put("id", "source-" + plans.length())
                    .put("url", plan.createBuilder().buildWithoutRatingFilter())
                    .put("bookmarks", plan.getBookmarks().size()));
        }
        Files.write(root.resolve("plans.json"), plans.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("PROBE exported " + bookmarks.size() + " bookmarks as " + plans.length() + " search sources");
    }
}
