package com.hippo.ehviewer;

import android.app.Application;
import android.content.Context;
import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderTouchAreas;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReaderKeyProfilesTest {
    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("reader-keys-test", 0);
        prefs.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", prefs);
    }

    @After public void cleanup() { ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null); }

    @Test public void freshInstallCreatesDefaultAndRecommendedProfilesOnce() {
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        assertEquals(2, store.profiles.size());
        assertEquals("Default", store.active().displayName(RuntimeEnvironment.getApplication(), 0));
        assertEquals("Recommended", store.profiles.get(1).displayName(RuntimeEnvironment.getApplication(), 1));
        for (int direction = 0; direction < 3; direction++) {
            assertArrayEquals(ReaderTouchAreas.defaults().positions(), store.active().areas(direction).positions(), 0f);
            assertArrayEquals(ReaderTouchAreas.recommended().positions(), store.profiles.get(1).areas(direction).positions(), 0f);
            assertArrayEquals(ReaderKeyProfiles.recommended("").keys(direction), store.profiles.get(1).keys(direction));
        }
        Settings.putVersionCode(111);
        Settings.putBoolean("gallery_quick_page_turn", true);
        Settings.putBoolean("gallery_direct_save", true);
        ReaderKeyProfiles restored = ReaderKeyProfiles.load();
        assertEquals(2, restored.profiles.size());
        assertEquals(ReaderKeyMap.LEGACY, restored.active().keys(0)[1]);
        assertEquals(ReaderKeyMap.LEGACY, restored.active().keys(0)[8]);
        assertEquals(ReaderKeyMap.ZOOM, restored.active().map().resolvedAction(0, 1, 0));
    }

    @Test public void oldInstallWithoutProfilesUsesPreferencesAndRecommendedSizesOnce() {
        Settings.putVersionCode(110);
        Settings.putBoolean("gallery_quick_page_turn", true);
        Settings.putBoolean("gallery_direct_save", true);
        Settings.putBoolean("gallery_quick_save_turn_page", true);
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        assertEquals(2, store.profiles.size());
        assertEquals(ReaderKeyMap.NONE, store.active().keys(0)[1]);
        assertEquals(ReaderKeyMap.SAVE_NEXT, store.active().keys(0)[8]);
        assertEquals(ReaderKeyMap.SAVE_NEXT, store.active().keys(1)[2]);
        assertArrayEquals(ReaderTouchAreas.recommended().positions(), store.active().areas(0).positions(), 0f);
        store.active().keys(0)[8] = ReaderKeyMap.LEGACY;
        store.save();
        assertEquals(ReaderKeyMap.LEGACY, ReaderKeyProfiles.load().active().keys(0)[8]);
    }

    @Test public void existingKeyProfilesKeepSelectionAndActionsWithoutReadingRetiredSwitches() {
        Settings.putBoolean("gallery_quick_page_turn", true);
        Settings.putBoolean("gallery_direct_save", true);
        Settings.putString("reader_key_profiles_v1", "{\"version\":4,\"selected\":1,\"profiles\":[{\"name\":\"A\"},{\"name\":\"B\",\"directions\":[[-1,-1,8]]}]}");
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        assertEquals(3, store.profiles.size());
        assertEquals(1, store.selected);
        assertEquals("B", store.active().name);
        assertEquals(ReaderKeyMap.LEGACY, store.active().keys(0)[1]);
        assertEquals(ReaderKeyMap.LEGACY, store.active().keys(0)[8]);
        assertArrayEquals(ReaderTouchAreas.recommended().positions(), store.active().areas(0).positions(), 0f);
        assertEquals(3, ReaderKeyProfiles.load().profiles.size());
    }

    @Test public void areaSyncOnlyChangesSizesAndSurvivesCopiesAndSnapshots() {
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        ReaderKeyProfiles.Profile profile = store.active();
        profile.keys(1)[0] = ReaderKeyMap.NEXT;
        ReaderTouchAreas leftWide = profile.areas(0).withPosition(ReaderTouchAreas.LEFT_EDGE, .4f);
        profile.setAreas(0, leftWide);
        for (int direction = 0; direction < 3; direction++) assertEquals(.4f, profile.areas(direction).position(0), 0f);
        assertEquals(ReaderKeyMap.LEGACY, profile.keys(0)[0]);
        assertEquals(ReaderKeyMap.NEXT, profile.keys(1)[0]);
        profile.setUnifiedTouchAreas(false, 0);
        profile.setAreas(1, leftWide.withPosition(ReaderTouchAreas.LEFT_SPLIT, .65f));
        ReaderKeyMap snapshot = profile.map();
        store.save();
        profile = ReaderKeyProfiles.load().active();
        assertFalse(profile.unifiedTouchAreas);
        assertEquals(.5f, profile.areas(0).position(2), 0f);
        assertEquals(.65f, profile.areas(1).position(2), 0f);
        ReaderKeyProfiles.Profile copy = profile.copy();
        copy.setUnifiedTouchAreas(true, 1);
        for (int direction = 0; direction < 3; direction++) assertEquals(.65f, copy.areas(direction).position(2), 0f);
        copy.setAreas(0, leftWide);
        assertEquals(.65f, snapshot.areas(1).position(2), 0f);
        assertEquals(.65f, profile.areas(1).position(2), 0f);
    }

    @Test public void recommendedProfileMatchesCapturedDeviceSnapshotAndIsIndependent() throws Exception {
        try (InputStream snapshot = getClass().getResourceAsStream("/reader-keys-device-profile-1.json")) {
            assertNotNull(snapshot);
            ReaderKeyProfiles.Profile captured = ReaderKeyProfiles.Profile.fromJson(
                    new JSONObject(new String(snapshot.readAllBytes(), StandardCharsets.UTF_8)));
            ReaderKeyProfiles.Profile recommended = ReaderKeyProfiles.recommended("夜间");
            for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
                assertArrayEquals(captured.keys(direction), recommended.keys(direction));
            }
            assertEquals(captured.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), recommended.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), 0f);
            assertEquals(captured.orientationSwipe, recommended.orientationSwipe);
            assertEquals("夜间", recommended.name);
            recommended.keys(0)[0] = ReaderKeyMap.NONE;
            assertEquals(captured.keys(0)[0], ReaderKeyProfiles.recommended("").keys(0)[0]);
        }
    }

    @Test public void profilesPersistIndependentMappingsAndSelection() {
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        store.active().keys(0)[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        ReaderKeyProfiles.Profile second = store.active().copy();
        second.name = "Animation";
        second.keys(0)[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.PREVIOUS;
        second.setAreas(0, second.areas(0).withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 0.53f));
        second.orientationSwipe = ReaderKeyProfiles.SWIPE_UP;
        store.profiles.remove(1);
        store.profiles.add(second); store.selected = 1; store.save();

        ReaderKeyProfiles restored = ReaderKeyProfiles.load();
        assertEquals(1, restored.selected);
        assertEquals("Animation", restored.active().name);
        assertEquals(ReaderKeyMap.NEXT, restored.profiles.get(0).map().action(ReaderKeyMap.RIGHT_TOP, 0, 0));
        assertEquals(ReaderKeyMap.PREVIOUS, restored.active().map().action(ReaderKeyMap.RIGHT_TOP, 0, 0));
        assertEquals(0.53f, restored.active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, restored.active().orientationSwipe);
        assertEquals(0.7f, restored.profiles.get(0).areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, restored.profiles.get(0).orientationSwipe);
    }

    @Test public void animationMappingsMigratePersistAndRemainIndependent() {
        Settings.putString("reader_key_profiles_v1", "{\"version\":5,\"profiles\":[{\"name\":\"Old\",\"directions\":[[3],[4],[6]]}]}");
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        for (int direction = 0; direction < 3; direction++) {
            assertArrayEquals(store.active().keys(direction), store.active().keys(direction, true));
            store.active().keys(direction, true)[0] = ReaderKeyMap.SAVE_PREVIOUS;
        }
        ReaderKeyMap snapshot = store.active().map();
        store.save();
        ReaderKeyProfiles.Profile restored = ReaderKeyProfiles.load().active();
        for (int direction = 0; direction < 3; direction++) {
            assertEquals(ReaderKeyMap.SAVE_PREVIOUS, restored.map().action(0, 0, direction, true));
            assertNotEquals(ReaderKeyMap.SAVE_PREVIOUS, restored.map().action(0, 0, direction));
        }
        ReaderKeyProfiles.Profile duplicate = restored.duplicate();
        duplicate.keys(0, true)[0] = ReaderKeyMap.NONE;
        assertEquals(ReaderKeyMap.SAVE_PREVIOUS, restored.keys(0, true)[0]);
        assertEquals(ReaderKeyMap.SAVE_PREVIOUS, snapshot.action(0, 0, 0, true));
    }

    @Test public void recommendedAnimationLongPressSavesPreviousOnlyOnPreviousSide() {
        ReaderKeyProfiles.Profile profile = ReaderKeyProfiles.recommended("");
        for (int direction = 0; direction < 2; direction++) {
            for (int region = 0; region <= ReaderKeyMap.RIGHT_BOTTOM; region++) {
                boolean previous = (region <= ReaderKeyMap.LEFT_BOTTOM) == (direction == GalleryView.LAYOUT_LEFT_TO_RIGHT);
                int index = region * 3 + ReaderKeyMap.LONG_PRESS;
                assertEquals(previous ? ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL : ReaderKeyMap.SAVE_NEXT,
                        profile.keys(direction, true)[index]);
                assertEquals(previous ? ReaderKeyMap.LEGACY : ReaderKeyMap.SAVE_NEXT, profile.keys(direction)[index]);
            }
        }
        assertArrayEquals(profile.keys(2), profile.keys(2, true));
        JSONObject old = profile.toJson();
        old.remove("animatedDirections");
        ReaderKeyProfiles.Profile migrated = ReaderKeyProfiles.Profile.fromJson(old);
        assertArrayEquals(profile.keys(0, true), migrated.keys(0, true));
        assertArrayEquals(profile.keys(1, true), migrated.keys(1, true));
    }

    @Test public void legacyAnimationPercentMigratesToSharedLineAndRepairsRecommendedRtlDefaults() throws Exception {
        ReaderKeyProfiles.Profile previous = ReaderKeyProfiles.recommended("");
        previous.unifiedTouchAreas = false;
        previous.setAreas(1, previous.areas(1).withPosition(ReaderTouchAreas.LEFT_EDGE, .28f));
        previous.keys(1, true)[ReaderKeyMap.RIGHT_TOP * 3 + ReaderKeyMap.LONG_PRESS] = ReaderKeyMap.LEGACY;
        previous.keys(1, true)[ReaderKeyMap.RIGHT_BOTTOM * 3 + ReaderKeyMap.LONG_PRESS] = ReaderKeyMap.LEGACY;
        JSONObject old = previous.toJson();
        for (int direction = 0; direction < 3; direction++) old.getJSONArray("touchAreas").getJSONArray(direction).remove(ReaderTouchAreas.ANIMATED_SPLIT);
        old.put("animatedControlPercent", 42.125);
        JSONObject storage = new JSONObject().put("version", 6).put("profiles", new org.json.JSONArray().put(old));
        Settings.putString("reader_key_profiles_v1", storage.toString());
        ReaderKeyProfiles.Profile restored = ReaderKeyProfiles.load().active();
        for (int direction = 0; direction < 3; direction++) assertEquals(.57875f,
                restored.areas(direction).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(.28f, restored.areas(1).position(ReaderTouchAreas.LEFT_EDGE), 0f);
        assertEquals(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, restored.keys(1, true)[ReaderKeyMap.RIGHT_TOP * 3 + ReaderKeyMap.LONG_PRESS]);
        assertEquals(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, restored.keys(1, true)[ReaderKeyMap.RIGHT_BOTTOM * 3 + ReaderKeyMap.LONG_PRESS]);
        assertFalse(restored.toJson().has("animatedControlPercent"));
        assertEquals(ReaderTouchAreas.LINE_COUNT, restored.toJson().getJSONArray("touchAreas").getJSONArray(1).length());
    }

    @Test public void fractionalAnimatedLineUsesDirectionSyncCopyAndSnapshotLikeEveryBoundary() {
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        ReaderKeyProfiles.Profile profile = store.active();
        profile.setUnifiedTouchAreas(false, 0);
        float[] positions = {.712345f, .623456f, .834567f};
        for (int direction = 0; direction < 3; direction++) profile.setAreas(direction,
                profile.areas(direction).withPosition(ReaderTouchAreas.ANIMATED_SPLIT, positions[direction]));
        store.save();
        profile = ReaderKeyProfiles.load().active();
        ReaderKeyMap snapshot = profile.map();
        ReaderKeyProfiles.Profile copy = profile.copy();
        for (int direction = 0; direction < 3; direction++) assertEquals(positions[direction],
                copy.areas(direction).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        copy.setUnifiedTouchAreas(true, 1);
        for (int direction = 0; direction < 3; direction++) assertEquals(positions[1],
                copy.areas(direction).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        copy.setAreas(0, copy.areas(0).withPosition(ReaderTouchAreas.ANIMATED_SPLIT, .123456f));
        assertEquals(positions[0], snapshot.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertTrue(snapshot.areas(0).isAnimatedControlArea(900, 713, 1000, 1000));
        assertFalse(snapshot.areas(0).isAnimatedControlArea(900, 712, 1000, 1000));
    }

    @Test public void malformedStorageAndOutOfRangeActionsRestoreDefaults() {
        Settings.putString("reader_key_profiles_v1", "invalid json");
        assertEquals(2, ReaderKeyProfiles.load().profiles.size());
        Settings.putString("reader_key_profiles_v1",
                "{\"selected\":99,\"profiles\":[{\"normal\":[999,-2],\"animatedControlPercent\":999,\"orientationSwipe\":99}]}");
        ReaderKeyProfiles restored = ReaderKeyProfiles.load();
        assertEquals(0, restored.selected);
        assertEquals(ReaderKeyMap.LEGACY, restored.active().keys(0)[0]);
        assertEquals(ReaderKeyMap.LEGACY, restored.active().keys(0)[1]);
        assertEquals(0.7f, restored.active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, restored.active().orientationSwipe);
    }

    @Test public void draftAndRuntimeSnapshotDoNotMutateSavedProfile() {
        ReaderKeyProfiles.Profile saved = ReaderKeyProfiles.load().active();
        ReaderKeyProfiles.Profile draft = saved.copy();
        draft.keys(0)[0] = ReaderKeyMap.NEXT;
        ReaderKeyMap snapshot = draft.map();
        draft.keys(0)[0] = ReaderKeyMap.NONE;
        assertEquals(ReaderKeyMap.LEGACY, saved.keys(0)[0]);
        assertEquals(ReaderKeyMap.NEXT, snapshot.action(0, 0, 0));
        assertEquals(ReaderKeyMap.LEGACY, snapshot.action(0, 0, 1));
    }

    @Test public void halfScreenBoundaryIsConsistentAcrossOrientations() {
        assertEquals(ReaderKeyMap.LEFT_TOP, ReaderKeyMap.region(100, 499, 1000, 1000));
        assertEquals(ReaderKeyMap.LEFT_BOTTOM, ReaderKeyMap.region(100, 500, 1000, 1000));
        assertEquals(ReaderKeyMap.RIGHT_TOP, ReaderKeyMap.region(900, 499, 1000, 1000));
        assertEquals(ReaderKeyMap.RIGHT_BOTTOM, ReaderKeyMap.region(900, 500, 1000, 1000));
        assertEquals(ReaderKeyMap.RIGHT_BOTTOM, ReaderKeyMap.region(1800, 500, 2000, 1000));
        assertEquals(ReaderKeyMap.CENTER_TOP, ReaderKeyMap.region(500, 149, 1000, 1000));
        assertEquals(ReaderKeyMap.CENTER_MENU, ReaderKeyMap.region(500, 150, 1000, 1000));
        assertEquals(ReaderKeyMap.CENTER_BOTTOM, ReaderKeyMap.region(500, 500, 1000, 1000));
        assertEquals(-1, ReaderKeyMap.region(1000, 500, 1000, 1000));
    }

    @Test public void oldProfilesKeepReadingActionsAndReceiveNewGestureDefaults() {
        Settings.putString("reader_key_profiles_v1",
                "{\"version\":1,\"profiles\":[{\"name\":\"Old\",\"normal\":[3],\"animated\":[11],\"holdSpeed\":2.5}]}");
        ReaderKeyProfiles.Profile profile = ReaderKeyProfiles.load().active();
        assertEquals("Old", profile.name);
        for (int direction = 0; direction < 3; direction++) assertEquals(ReaderKeyMap.NEXT, profile.keys(direction)[0]);
        assertEquals(0.7f, profile.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, profile.orientationSwipe);
        assertFalse(profile.toJson().has("animated"));
        assertFalse(profile.toJson().has("holdSpeed"));
    }

    @Test public void defaultsNoLongerDependOnRetiredSwitches() {
        assertEquals(ReaderKeyMap.LEFT, ReaderKeyDefaults.action(0, 0));
        assertEquals(ReaderKeyMap.ZOOM, ReaderKeyDefaults.action(0, 1));
        assertEquals(ReaderKeyMap.PAGE_MENU, ReaderKeyDefaults.action(2, 2));
        assertEquals(ReaderKeyMap.PAGE_MENU, ReaderKeyDefaults.action(0, 2));
    }

    @Test public void directionsPersistAndCopyIndependentlyWithinEachProfile() {
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        store.active().keys(GalleryView.LAYOUT_LEFT_TO_RIGHT)[0] = ReaderKeyMap.NEXT;
        store.active().keys(GalleryView.LAYOUT_RIGHT_TO_LEFT)[0] = ReaderKeyMap.PREVIOUS;
        store.active().keys(GalleryView.LAYOUT_TOP_TO_BOTTOM)[0] = ReaderKeyMap.CONTROLS;
        store.save();
        ReaderKeyProfiles.Profile restored = ReaderKeyProfiles.load().active();
        assertEquals(ReaderKeyMap.NEXT, restored.map().action(0, 0, 0));
        assertEquals(ReaderKeyMap.PREVIOUS, restored.map().action(0, 0, 1));
        assertEquals(ReaderKeyMap.CONTROLS, restored.map().action(0, 0, 2));
        ReaderKeyProfiles.Profile copy = restored.copy();
        copy.keys(1)[0] = ReaderKeyMap.NONE;
        assertEquals(ReaderKeyMap.PREVIOUS, restored.keys(1)[0]);
        assertEquals(ReaderKeyMap.NEXT, copy.keys(0)[0]);
        assertEquals(ReaderKeyMap.CONTROLS, copy.keys(2)[0]);
    }

    @Test public void versionTwoProfileKeepsItsMappingsInAllThreeDirectionsAfterSaving() {
        Settings.putString("reader_key_profiles_v1",
                "{\"version\":2,\"profiles\":[{\"normal\":[3,0,8],\"animatedControlPercent\":42,\"orientationSwipe\":1}]}");
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        store.active().keys(1)[0] = ReaderKeyMap.PREVIOUS;
        store.save();
        ReaderKeyProfiles.Profile profile = ReaderKeyProfiles.load().active();
        assertEquals(ReaderKeyMap.NEXT, profile.keys(0)[0]);
        assertEquals(ReaderKeyMap.PREVIOUS, profile.keys(1)[0]);
        assertEquals(ReaderKeyMap.NEXT, profile.keys(2)[0]);
        for (int direction = 0; direction < 3; direction++) {
            assertEquals(ReaderKeyMap.NONE, profile.keys(direction)[1]);
            assertEquals(ReaderKeyMap.PAGE_MENU, profile.keys(direction)[2]);
        }
        assertEquals(0.58f, profile.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, profile.orientationSwipe);
        assertFalse(profile.toJson().has("normal"));
    }

    @Test public void retiredSwitchesMigrateOnceWithoutOverwritingExplicitKeys() {
        Settings.putBoolean("gallery_quick_page_turn", true);
        Settings.putBoolean("gallery_direct_save", true);
        Settings.putBoolean("gallery_quick_save_turn_page", true);
        Settings.putString("reader_key_profiles_v1",
                "{\"version\":3,\"profiles\":[{\"directions\":[[-1,7,8]]}]}");
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        assertEquals(ReaderKeyMap.ZOOM, store.active().keys(0)[1]);
        assertEquals(ReaderKeyMap.PAGE_MENU, store.active().keys(0)[2]);
        assertEquals(ReaderKeyMap.NONE, store.active().keys(0)[7]);
        assertEquals(ReaderKeyMap.SAVE_NEXT, store.active().keys(0)[8]);
        assertEquals(ReaderKeyMap.SAVE_NEXT, store.active().keys(1)[2]);
        store.active().keys(0)[7] = ReaderKeyMap.LEGACY;
        store.save();
        assertEquals(ReaderKeyMap.LEGACY, ReaderKeyProfiles.load().active().keys(0)[7]);
    }

    @Test public void legacyDoubleTapFlagMigratesToQuickTapKeyWithoutAnyExistingProfile() {
        Settings.putBoolean("gallery_double_tap_zoom", false);
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        assertEquals(ReaderKeyMap.NONE, store.active().keys(0)[1]);
        assertEquals(ReaderKeyMap.LEGACY, store.active().keys(0)[13]);
    }
}
