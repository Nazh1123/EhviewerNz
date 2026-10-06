package com.hippo.ehviewer;

import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderTouchAreas;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stores all profiles and the active selection together to avoid partial updates. */
public final class ReaderKeyProfiles {
    private static final String KEY = "reader_key_profiles_v1";
    private static final int VERSION = 7;
    public final List<Profile> profiles = new ArrayList<>();
    public int selected;
    public static final int SWIPE_OFF = 0, SWIPE_UP = 1, SWIPE_DOWN = 2;

    public static final class Profile {
        public String name;
        private final int[][] directions = new int[ReaderKeyMap.DIRECTION_COUNT][ReaderKeyMap.REGION_COUNT * ReaderKeyMap.GESTURE_COUNT];
        private final int[][] animatedDirections = new int[ReaderKeyMap.DIRECTION_COUNT][ReaderKeyMap.REGION_COUNT * ReaderKeyMap.GESTURE_COUNT];
        private final ReaderTouchAreas[] areas = new ReaderTouchAreas[ReaderKeyMap.DIRECTION_COUNT];
        public boolean unifiedTouchAreas = true;
        private int builtInName;
        public int orientationSwipe = SWIPE_DOWN;

        public Profile(String name) {
            this.name = name;
            for (int[] keys : directions) Arrays.fill(keys, ReaderKeyMap.LEGACY);
            for (int[] keys : animatedDirections) Arrays.fill(keys, ReaderKeyMap.LEGACY);
            Arrays.fill(areas, ReaderTouchAreas.defaults());
        }

        public int[] keys(int direction) { return directions[GalleryView.sanitizeLayoutMode(direction)]; }
        public int[] keys(int direction, boolean animated) {
            return animated ? animatedDirections[GalleryView.sanitizeLayoutMode(direction)] : keys(direction);
        }
        private void copyNormalToAnimated() {
            for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
                System.arraycopy(directions[direction], 0, animatedDirections[direction], 0, directions[direction].length);
            }
        }
        public ReaderTouchAreas areas(int direction) { return areas[GalleryView.sanitizeLayoutMode(direction)]; }
        public void setAreas(int direction, ReaderTouchAreas value) {
            if (unifiedTouchAreas) Arrays.fill(areas, value);
            else areas[GalleryView.sanitizeLayoutMode(direction)] = value;
        }
        public void setUnifiedTouchAreas(boolean unified, int direction) {
            ReaderTouchAreas current = areas(direction);
            unifiedTouchAreas = unified;
            if (unified) Arrays.fill(areas, current);
        }
        public String displayName(Context context, int index) {
            if (!name.isEmpty()) return name;
            if (builtInName == 1) return context.getString(R.string.reader_keys_default_profile);
            if (builtInName == 2) return context.getString(R.string.reader_keys_recommended_profile);
            return context.getString(R.string.reader_keys_profile_number, index + 1);
        }
        public ReaderKeyMap map() { return new ReaderKeyMap(directions, animatedDirections, areas); }
        public Profile copy() { return fromJson(toJson()); }
        public Profile duplicate() {
            Profile copy = copy();
            copy.name = ""; copy.builtInName = 0;
            return copy;
        }

        public JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("name", name);
                JSONArray mappings = new JSONArray();
                for (int[] keys : directions) mappings.put(array(keys));
                value.put("directions", mappings);
                JSONArray animatedMappings = new JSONArray();
                for (int[] keys : animatedDirections) animatedMappings.put(array(keys));
                value.put("animatedDirections", animatedMappings);
                JSONArray sizes = new JSONArray();
                for (ReaderTouchAreas area : areas) {
                    JSONArray lines = new JSONArray();
                    for (float position : area.positions()) lines.put((double) position);
                    sizes.put(lines);
                }
                value.put("touchAreas", sizes);
                value.put("unifiedTouchAreas", unifiedTouchAreas);
                value.put("builtInName", builtInName);
                value.put("orientationSwipe", orientationSwipe);
            } catch (JSONException e) { throw new IllegalStateException(e); }
            return value;
        }

        public static Profile fromJson(JSONObject value) {
            Profile profile = new Profile(value.optString("name", ""));
            // Earlier profiles used one mapping for every direction. Preserve that behavior.
            JSONArray legacy = value.optJSONArray("normal");
            JSONArray mappings = value.optJSONArray("directions");
            JSONArray sizes = value.optJSONArray("touchAreas");
            // Convert the retired bottom percentage to the same top-relative line position as other boundaries.
            double legacyPercent = value.optDouble("animatedControlPercent", 30);
            float animatedPosition = Double.isFinite(legacyPercent) && legacyPercent >= 0 && legacyPercent <= 100
                    ? (float) (1 - legacyPercent / 100) : .7f;
            for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
                readArray(legacy, profile.directions[direction]);
                if (mappings != null) readArray(mappings.optJSONArray(direction), profile.directions[direction]);
                ReaderTouchAreas fallback = sizes == null ? ReaderTouchAreas.recommended() : ReaderTouchAreas.defaults();
                fallback = fallback.withPosition(ReaderTouchAreas.ANIMATED_SPLIT, animatedPosition);
                JSONArray lines = sizes == null ? null : sizes.optJSONArray(direction);
                float[] positions = new float[ReaderTouchAreas.LINE_COUNT];
                for (int i = 0; i < positions.length; i++) {
                    positions[i] = lines == null ? Float.NaN : (float) lines.optDouble(i,
                            i == ReaderTouchAreas.ANIMATED_SPLIT ? animatedPosition : Double.NaN);
                }
                profile.areas[direction] = ReaderTouchAreas.from(positions, fallback);
            }
            profile.unifiedTouchAreas = value.optBoolean("unifiedTouchAreas", true);
            if (profile.unifiedTouchAreas) Arrays.fill(profile.areas, profile.areas[0]);
            profile.builtInName = Math.max(0, Math.min(2, value.optInt("builtInName", 0)));
            profile.copyNormalToAnimated();
            JSONArray animatedMappings = value.optJSONArray("animatedDirections");
            if (animatedMappings != null) {
                for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
                    readArray(animatedMappings.optJSONArray(direction), profile.animatedDirections[direction]);
                }
            } else if (profile.builtInName == 2) profile.recommendAnimatedPreviousSave();
            int swipe = value.optInt("orientationSwipe", SWIPE_DOWN);
            profile.orientationSwipe = swipe >= SWIPE_OFF && swipe <= SWIPE_DOWN ? swipe : SWIPE_DOWN;
            return profile;
        }

        private static JSONArray array(int[] values) {
            JSONArray result = new JSONArray();
            for (int value : values) result.put(value);
            return result;
        }

        private void recommendAnimatedPreviousSave() {
            for (int direction : new int[]{GalleryView.LAYOUT_LEFT_TO_RIGHT, GalleryView.LAYOUT_RIGHT_TO_LEFT}) {
                int first = direction == GalleryView.LAYOUT_LEFT_TO_RIGHT ? ReaderKeyMap.LEFT_TOP : ReaderKeyMap.RIGHT_TOP;
                for (int region = first; region <= first + 1; region++) {
                    int index = region * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.LONG_PRESS;
                    if (animatedDirections[direction][index] == ReaderKeyMap.LEGACY) {
                        animatedDirections[direction][index] = ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL;
                    }
                }
            }
        }

        private static void readArray(JSONArray values, int[] target) {
            if (values == null) return;
            for (int i = 0; i < Math.min(values.length(), target.length); i++) {
                int action = values.optInt(i, target[i]);
                if (ReaderKeyMap.valid(action)) target[i] = action;
            }
        }
    }

    public static synchronized ReaderKeyProfiles load() {
        ReaderKeyProfiles result = new ReaderKeyProfiles();
        int version = 0;
        try {
            JSONObject data = new JSONObject(Settings.getString(KEY, "{}"));
            version = data.optInt("version", 0);
            JSONArray entries = data.optJSONArray("profiles");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject value = entries.optJSONObject(i);
                    if (value != null) result.profiles.add(Profile.fromJson(value));
                }
            }
            result.selected = data.optInt("selected", 0);
        } catch (JSONException ignored) { /* Restore defaults if storage is malformed. */ }
        result.selected = Math.max(0, Math.min(result.selected, result.profiles.size() - 1));
        boolean initialize = result.profiles.isEmpty();
        if (result.profiles.isEmpty()) {
            boolean existing = Settings.hasExistingReaderPreferences();
            Profile first = new Profile("");
            if (existing) {
                first.setAreas(0, ReaderTouchAreas.recommended());
                migrateLegacySwitches(first);
                first.copyNormalToAnimated();
            } else first.builtInName = 1;
            result.profiles.add(first);
            result.profiles.add(recommended(""));
        } else if (version < VERSION) {
            if (version < 4) for (Profile profile : result.profiles) {
                migrateLegacySwitches(profile);
                profile.copyNormalToAnimated();
                if (profile.builtInName == 2) profile.recommendAnimatedPreviousSave();
            }
            for (Profile profile : result.profiles) {
                if (profile.builtInName == 2) profile.recommendAnimatedPreviousSave();
            }
            boolean hasRecommendation = false;
            for (Profile profile : result.profiles) if (profile.builtInName == 2) hasRecommendation = true;
            if (!hasRecommendation) result.profiles.add(recommended(""));
        }
        result.selected = Math.max(0, Math.min(result.selected, result.profiles.size() - 1));
        // Persist initialization/migration now, before later app initialization writes preferences.
        if (initialize || version < VERSION) result.save();
        return result;
    }

    public Profile active() { return profiles.get(selected); }

    public static Profile defaults(String name) {
        Profile profile = new Profile(name);
        profile.builtInName = 1;
        return profile;
    }

    /** Snapshot of profile 1 read from the debug app on the connected device on 2026-10-03. */
    public static Profile recommended(String name) {
        Profile profile = new Profile(name);
        profile.builtInName = 2;
        profile.setAreas(0, ReaderTouchAreas.recommended());
        profile.orientationSwipe = SWIPE_DOWN;
        for (int direction : new int[]{GalleryView.LAYOUT_LEFT_TO_RIGHT, GalleryView.LAYOUT_RIGHT_TO_LEFT}) {
            int[] keys = profile.keys(direction);
            for (int region = ReaderKeyMap.LEFT_TOP; region <= ReaderKeyMap.RIGHT_BOTTOM; region++) {
                keys[region * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.DOUBLE_TAP] = ReaderKeyMap.NONE;
                boolean left = region == ReaderKeyMap.LEFT_TOP || region == ReaderKeyMap.LEFT_BOTTOM;
                boolean next = direction == GalleryView.LAYOUT_RIGHT_TO_LEFT ? left : !left;
                if (next) keys[region * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.LONG_PRESS] = ReaderKeyMap.SAVE_NEXT;
            }
        }
        int[] vertical = profile.keys(GalleryView.LAYOUT_TOP_TO_BOTTOM);
        vertical[ReaderKeyMap.RIGHT_TOP * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.LONG_PRESS] = ReaderKeyMap.SAVE;
        vertical[ReaderKeyMap.RIGHT_BOTTOM * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.LONG_PRESS] = ReaderKeyMap.SAVE;
        profile.copyNormalToAnimated();
        profile.recommendAnimatedPreviousSave();
        return profile;
    }

    /** One-time compatibility only; retired switches never control runtime actions. */
    private static void migrateLegacySwitches(Profile profile) {
        boolean quick = Settings.getBoolean("gallery_quick_page_turn",
                !Settings.getBoolean("gallery_double_tap_zoom", true));
        boolean save = Settings.getBoolean("gallery_direct_save", false);
        boolean saveTurn = Settings.getBoolean("gallery_quick_save_turn_page", false);
        for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
            int[] keys = profile.keys(direction);
            for (int region = ReaderKeyMap.LEFT_TOP; region <= ReaderKeyMap.RIGHT_BOTTOM; region++) {
                int doubleTap = region * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.DOUBLE_TAP;
                if (quick && keys[doubleTap] == ReaderKeyMap.LEGACY) keys[doubleTap] = ReaderKeyMap.NONE;
                boolean left = region == ReaderKeyMap.LEFT_TOP || region == ReaderKeyMap.LEFT_BOTTOM;
                boolean next = direction == GalleryView.LAYOUT_RIGHT_TO_LEFT ? left : !left;
                int longPress = region * ReaderKeyMap.GESTURE_COUNT + ReaderKeyMap.LONG_PRESS;
                if (save && next && keys[longPress] == ReaderKeyMap.LEGACY) {
                    keys[longPress] = saveTurn ? ReaderKeyMap.SAVE_NEXT : ReaderKeyMap.SAVE;
                }
            }
        }
    }

    public void save() {
        JSONObject data = new JSONObject();
        JSONArray entries = new JSONArray();
        for (Profile profile : profiles) entries.put(profile.toJson());
        try {
            data.put("version", VERSION);
            data.put("selected", selected);
            data.put("profiles", entries);
        } catch (JSONException e) { throw new IllegalStateException(e); }
        Settings.putString(KEY, data.toString());
    }
}
