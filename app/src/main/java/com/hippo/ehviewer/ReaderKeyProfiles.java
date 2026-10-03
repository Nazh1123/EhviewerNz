package com.hippo.ehviewer;

import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.GalleryView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stores all profiles and the active selection together to avoid partial updates. */
public final class ReaderKeyProfiles {
    private static final String KEY = "reader_key_profiles_v1";
    public final List<Profile> profiles = new ArrayList<>();
    public int selected;
    public static final int SWIPE_OFF = 0, SWIPE_UP = 1, SWIPE_DOWN = 2;

    public static final class Profile {
        public String name;
        private final int[][] directions = new int[ReaderKeyMap.DIRECTION_COUNT][ReaderKeyMap.REGION_COUNT * ReaderKeyMap.GESTURE_COUNT];
        public int animatedControlPercent = 30;
        public int orientationSwipe = SWIPE_DOWN;

        public Profile(String name) {
            this.name = name;
            for (int[] keys : directions) Arrays.fill(keys, ReaderKeyMap.LEGACY);
        }

        public int[] keys(int direction) { return directions[GalleryView.sanitizeLayoutMode(direction)]; }
        public ReaderKeyMap map() { return new ReaderKeyMap(directions, animatedControlPercent); }
        public Profile copy() { return fromJson(toJson()); }

        public JSONObject toJson() {
            JSONObject value = new JSONObject();
            try {
                value.put("name", name);
                JSONArray mappings = new JSONArray();
                for (int[] keys : directions) mappings.put(array(keys));
                value.put("directions", mappings);
                value.put("animatedControlPercent", animatedControlPercent);
                value.put("orientationSwipe", orientationSwipe);
            } catch (JSONException e) { throw new IllegalStateException(e); }
            return value;
        }

        public static Profile fromJson(JSONObject value) {
            Profile profile = new Profile(value.optString("name", ""));
            // Earlier profiles used one mapping for every direction. Preserve that behavior.
            JSONArray legacy = value.optJSONArray("normal");
            JSONArray mappings = value.optJSONArray("directions");
            for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
                readArray(legacy, profile.directions[direction]);
                if (mappings != null) readArray(mappings.optJSONArray(direction), profile.directions[direction]);
            }
            int percent = value.optInt("animatedControlPercent", 30);
            profile.animatedControlPercent = percent >= 0 && percent <= 100 ? percent : 30;
            int swipe = value.optInt("orientationSwipe", SWIPE_DOWN);
            profile.orientationSwipe = swipe >= SWIPE_OFF && swipe <= SWIPE_DOWN ? swipe : SWIPE_DOWN;
            return profile;
        }

        private static JSONArray array(int[] values) {
            JSONArray result = new JSONArray();
            for (int value : values) result.put(value);
            return result;
        }

        private static void readArray(JSONArray values, int[] target) {
            if (values == null) return;
            for (int i = 0; i < Math.min(values.length(), target.length); i++) {
                int action = values.optInt(i, target[i]);
                if (ReaderKeyMap.valid(action)) target[i] = action;
            }
        }
    }

    public static ReaderKeyProfiles load() {
        ReaderKeyProfiles result = new ReaderKeyProfiles();
        try {
            JSONObject data = new JSONObject(Settings.getString(KEY, "{}"));
            boolean migrateSwitches = data.optInt("version", 0) < 4;
            JSONArray entries = data.optJSONArray("profiles");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject value = entries.optJSONObject(i);
                    if (value != null) result.profiles.add(Profile.fromJson(value));
                }
            }
            result.selected = data.optInt("selected", 0);
            if (result.profiles.isEmpty()) result.profiles.add(new Profile(""));
            if (migrateSwitches) for (Profile profile : result.profiles) migrateLegacySwitches(profile);
        } catch (JSONException ignored) { /* Restore defaults if storage is malformed. */ }
        if (result.profiles.isEmpty()) result.profiles.add(new Profile(""));
        result.selected = Math.max(0, Math.min(result.selected, result.profiles.size() - 1));
        return result;
    }

    public Profile active() { return profiles.get(selected); }

    /** Snapshot of profile 1 read from the debug app on the connected device on 2026-10-03. */
    public static Profile recommended(String name) {
        Profile profile = new Profile(name);
        profile.animatedControlPercent = 30;
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
            data.put("version", 4);
            data.put("selected", selected);
            data.put("profiles", entries);
        } catch (JSONException e) { throw new IllegalStateException(e); }
        Settings.putString(KEY, data.toString());
    }
}
