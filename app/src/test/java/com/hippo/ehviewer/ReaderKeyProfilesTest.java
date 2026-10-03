package com.hippo.ehviewer;

import android.app.Application;
import android.content.Context;
import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.GalleryView;
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

    @Test public void profilesPersistIndependentMappingsAndSelection() {
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        store.active().keys(0)[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.NEXT;
        ReaderKeyProfiles.Profile second = store.active().copy();
        second.name = "Animation";
        second.keys(0)[ReaderKeyMap.RIGHT_TOP * 3] = ReaderKeyMap.PREVIOUS;
        second.animatedControlPercent = 47;
        second.orientationSwipe = ReaderKeyProfiles.SWIPE_UP;
        store.profiles.add(second); store.selected = 1; store.save();

        ReaderKeyProfiles restored = ReaderKeyProfiles.load();
        assertEquals(1, restored.selected);
        assertEquals("Animation", restored.active().name);
        assertEquals(ReaderKeyMap.NEXT, restored.profiles.get(0).map().action(ReaderKeyMap.RIGHT_TOP, 0, 0));
        assertEquals(ReaderKeyMap.PREVIOUS, restored.active().map().action(ReaderKeyMap.RIGHT_TOP, 0, 0));
        assertEquals(47, restored.active().animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, restored.active().orientationSwipe);
        assertEquals(30, restored.profiles.get(0).animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, restored.profiles.get(0).orientationSwipe);
    }

    @Test public void malformedStorageAndOutOfRangeActionsRestoreDefaults() {
        Settings.putString("reader_key_profiles_v1", "invalid json");
        assertEquals(1, ReaderKeyProfiles.load().profiles.size());
        Settings.putString("reader_key_profiles_v1",
                "{\"selected\":99,\"profiles\":[{\"normal\":[999,-2],\"animatedControlPercent\":999,\"orientationSwipe\":99}]}");
        ReaderKeyProfiles restored = ReaderKeyProfiles.load();
        assertEquals(0, restored.selected);
        assertEquals(ReaderKeyMap.LEGACY, restored.active().keys(0)[0]);
        assertEquals(ReaderKeyMap.LEGACY, restored.active().keys(0)[1]);
        assertEquals(30, restored.active().animatedControlPercent);
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
        assertEquals(30, profile.animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, profile.orientationSwipe);
        assertFalse(profile.toJson().has("animated"));
        assertFalse(profile.toJson().has("holdSpeed"));
    }

    @Test public void concreteDefaultsRespectReadingDirectionAndSaveSettings() {
        assertEquals(ReaderKeyMap.LEFT, ReaderKeyDefaults.action(0, 0, 0, false, false, false));
        assertEquals(ReaderKeyMap.NONE, ReaderKeyDefaults.action(0, 1, 0, true, false, false));
        assertEquals(ReaderKeyMap.ZOOM, ReaderKeyDefaults.action(0, 1, 0, false, false, false));
        assertEquals(ReaderKeyMap.SAVE_NEXT, ReaderKeyDefaults.action(2, 2, 0, false, true, true));
        assertEquals(ReaderKeyMap.PAGE_MENU, ReaderKeyDefaults.action(0, 2, 0, false, true, true));
        assertEquals(ReaderKeyMap.SAVE, ReaderKeyDefaults.action(0, 2, 1, false, true, false));
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
        assertEquals(42, profile.animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, profile.orientationSwipe);
        assertFalse(profile.toJson().has("normal"));
    }
}
