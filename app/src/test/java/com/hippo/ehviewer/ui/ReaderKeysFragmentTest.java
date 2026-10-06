package com.hippo.ehviewer.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ReaderKeyProfiles;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.fragment.ReaderKeysFragment;
import com.hippo.lib.glgallery.GalleryView;
import com.hippo.lib.glgallery.ReaderKeyMap;
import com.hippo.lib.glgallery.ReaderTouchAreas;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "zh-rCN-w360dp-h800dp-port-xhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderKeysFragmentTest {
    private ActivityController<FragmentActivity> controller;
    private ReaderKeysFragment fragment;
    private View root;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("keys-ui-test", 0);
        prefs.edit().clear().commit();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", prefs);
        Settings.putReadingDirection(GalleryView.LAYOUT_LEFT_TO_RIGHT);
        controller = Robolectric.buildActivity(FragmentActivity.class);
        controller.get().setTheme(R.style.AppTheme_Settings);
        controller.setup();
        FrameLayout content = new FrameLayout(controller.get());
        content.setId(android.R.id.content);
        controller.get().setContentView(content);
        openEditor();
        layout(360, 800);
    }

    private void openEditor() {
        fragment = new ReaderKeysFragment();
        controller.get().getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commitNow();
        root = fragment.requireView();
    }

    @Test public void boundaryTapEntersDecimalSizesAndSyncsOnlyDimensions() {
        android.view.MenuItem unified = unifiedItem();
        assertTrue(unified.isChecked());
        assertEquals("触摸区域统一", unified.getTitle().toString());
        assertNull(root.findViewById(R.id.reader_keys_unified_areas));
        View zones = root.findViewById(R.id.reader_keys_canvas);
        sendTouch(zones, MotionEvent.ACTION_DOWN, zones.getWidth() / 3f, zones.getHeight() * .3f);
        sendTouch(zones, MotionEvent.ACTION_UP, zones.getWidth() / 3f, zones.getHeight() * .3f);
        AlertDialog inputDialog = latest();
        EditText input = inputDialog.findViewById(R.id.reader_keys_boundary_input);
        assertNotNull(input);
        input.setText("40.5");
        inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        for (int direction = 0; direction < 3; direction++) assertEquals(.405f, draft.areas(direction).position(0), .000001f);
        assertEquals(1f / 3f, ReaderKeyProfiles.load().active().areas(0).position(0), 0f);
        toggleUnified();
        root.findViewById(R.id.reader_keys_direction).performClick();
        ReflectionHelpers.callInstanceMethod(fragment, "chooseBoundary", ReflectionHelpers.ClassParameter.from(int.class, ReaderTouchAreas.LEFT_EDGE));
        AlertDialog independent = latest();
        ((EditText) independent.findViewById(R.id.reader_keys_boundary_input)).setText("25");
        independent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(.405f, draft.areas(0).position(0), .000001f);
        assertEquals(.25f, draft.areas(1).position(0), 0f);
        assertEquals(.405f, draft.areas(2).position(0), .000001f);
        root.findViewById(R.id.reader_keys_save).performClick();
        ReaderKeyProfiles.Profile saved = ReaderKeyProfiles.load().active();
        assertFalse(saved.unifiedTouchAreas);
        assertEquals(.25f, saved.areas(1).position(0), 0f);
        toggleUnified();
        for (int direction = 0; direction < 3; direction++) assertEquals(.25f, draft.areas(direction).position(0), 0f);
        assertEquals(ReaderKeyMap.LEGACY, draft.keys(0)[0]);
        assertEquals(ReaderKeyMap.LEGACY, draft.keys(1)[0]);
    }

    @Test public void initialPresetsAndDuplicatesKeepTheirNamesAndDimensions() {
        root.findViewById(R.id.reader_keys_profile).performClick();
        AlertDialog choices = latest();
        assertEquals("1. 默认", choices.getListView().getAdapter().getItem(0));
        assertEquals("2. 推荐", choices.getListView().getAdapter().getItem(1));
        choices.getListView().performItemClick(null, 1, 1);
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        assertEquals(.36f, draft.areas(0).position(0), 0f);
        assertEquals(ReaderKeyMap.NONE, draft.keys(0)[1]);
        ReflectionHelpers.callInstanceMethod(fragment, "addProfile", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        ReaderKeyProfiles store = ReaderKeyProfiles.load();
        assertEquals(3, store.profiles.size());
        assertEquals("配置 3", store.active().displayName(controller.get(), 2));
        assertEquals(.36f, store.active().areas(0).position(0), 0f);
        assertArrayEquals(store.profiles.get(1).keys(0), store.active().keys(0));
        root.findViewById(R.id.reader_keys_manage).performClick();
        latest().getListView().performItemClick(null, 4, 4);
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        root.findViewById(R.id.reader_keys_save).performClick();
        store = ReaderKeyProfiles.load();
        assertEquals("默认", store.active().displayName(controller.get(), 2));
        assertEquals(1f / 3f, store.active().areas(0).position(0), 0f);
        assertEquals(ReaderKeyMap.ZOOM, store.active().map().resolvedAction(0, 1, 0));
    }

    @Test public void dragChangesBoundaryAndCancellationRestoresPreviousSizes() {
        View zones = root.findViewById(R.id.reader_keys_canvas);
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        float x = zones.getWidth() / 6f;
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .5f);
        sendTouch(zones, MotionEvent.ACTION_MOVE, x, zones.getHeight() * .65f);
        sendTouch(zones, MotionEvent.ACTION_UP, x, zones.getHeight() * .65f);
        for (int direction = 0; direction < 3; direction++) assertEquals(.65f, draft.areas(direction).position(2), .000001f);
        assertEquals(.5f, draft.areas(0).position(3), 0f);
        assertEquals(-1, (int) ReflectionHelpers.getField(zones, "pressedLine"));
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .65f);
        sendTouch(zones, MotionEvent.ACTION_MOVE, x, zones.getHeight() * .8f);
        assertEquals(.8f, draft.areas(0).position(2), .000001f);
        sendTouch(zones, MotionEvent.ACTION_CANCEL, x, zones.getHeight() * .8f);
        assertEquals(.65f, draft.areas(0).position(2), .000001f);
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals(.65f, ReaderKeyProfiles.load().active().areas(0).position(2), .000001f);
    }

    @Test public void inputRejectsCrossingBoundariesAndAnimationLineCannotBeDragged() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseBoundary", ReflectionHelpers.ClassParameter.from(int.class, ReaderTouchAreas.LEFT_EDGE));
        AlertDialog dialog = latest();
        EditText input = dialog.findViewById(R.id.reader_keys_boundary_input);
        input.setText("80");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing()); assertNotNull(input.getError());
        dialog.dismiss();
        View zones = root.findViewById(R.id.reader_keys_canvas);
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        float x = zones.getWidth() / 6f;
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .7f);
        assertEquals(-1, (int) ReflectionHelpers.getField(zones, "pressedLine"));
        sendTouch(zones, MotionEvent.ACTION_MOVE, x, zones.getHeight() * .85f);
        sendTouch(zones, MotionEvent.ACTION_UP, x, zones.getHeight() * .85f);
        assertEquals(0.7f, draft.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(.5f, draft.areas(0).position(2), 0f);
    }

    @Test public void editedDimensionsAndUnifiedSwitchSurviveRecreation() {
        android.view.MenuItem unified = unifiedItem();
        toggleUnified();
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        draft.setAreas(0, draft.areas(0).withPosition(ReaderTouchAreas.CENTER_TOP_SPLIT, .25f));
        androidx.fragment.app.Fragment.SavedState state = controller.get().getSupportFragmentManager().saveFragmentInstanceState(fragment);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        fragment = new ReaderKeysFragment(); fragment.setInitialSavedState(state);
        controller.get().getSupportFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commitNow();
        root = fragment.requireView(); layout(360, 800);
        unified = unifiedItem();
        assertFalse(unified.isChecked());
        draft = ReflectionHelpers.getField(fragment, "draft");
        assertEquals(.25f, draft.areas(0).position(4), 0f);
        assertEquals(.15f, draft.areas(1).position(4), 0f);
    }

    private android.view.MenuItem unifiedItem() {
        androidx.appcompat.widget.PopupMenu menu = ReflectionHelpers.callInstanceMethod(fragment, "showMore");
        android.view.MenuItem item = menu.getMenu().findItem(R.id.reader_keys_unified_areas);
        assertEquals(3, menu.getMenu().size());
        assertNotNull(item);
        menu.dismiss();
        return item;
    }

    private void toggleUnified() {
        androidx.appcompat.widget.PopupMenu menu = ReflectionHelpers.callInstanceMethod(fragment, "showMore");
        assertTrue(menu.getMenu().performIdentifierAction(R.id.reader_keys_unified_areas, 0));
        menu.dismiss();
    }

    private void sendTouch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre", null);
    }

    @Test public void areaTapOpensGestureMenuAndSaveIconAppliesAction() {
        View canvas = root.findViewById(R.id.reader_keys_canvas);
        long now = SystemClock.uptimeMillis();
        float x = canvas.getWidth() * .18f, y = canvas.getHeight() * .25f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0);
        canvas.dispatchTouchEvent(down); canvas.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
        AlertDialog gestures = latest();
        assertEquals(3, gestures.getListView().getCount());
        assertTrue(gestures.getListView().getAdapter().getItem(0).toString().contains("上一页 (默认)"));
        gestures.getListView().performItemClick(null, 0, 0);
        AlertDialog chooser = latest();
        assertEquals("上一页 (默认)", chooser.getListView().getAdapter().getItem(0));
        chooser.getListView().performItemClick(null, 4, 4);
        assertEquals(ReaderKeyMap.LEGACY, ReaderKeyProfiles.load().active().keys(0)[0]);
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals(ReaderKeyMap.NEXT, ReaderKeyProfiles.load().active().keys(0)[0]);
    }

    @Test public void labelsShowAllGesturesIncludingTopCenter() {
        String left = text(ReaderKeyMap.LEFT_TOP);
        assertTrue(left.startsWith("单击: 上一页 (默认)"));
        assertFalse(left.contains("左上"));
        assertTrue(left.contains("双击: 缩放图片 (默认)"));
        assertFalse(text(ReaderKeyMap.CENTER_TOP).contains("..."));
        assertTrue(text(ReaderKeyMap.CENTER_TOP).contains("双击"));
        assertTrue(text(ReaderKeyMap.CENTER_TOP).contains("长按"));
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(ReaderKeyMap.LEFT_TOP).startsWith("单击: 下一页 (默认)"));
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(ReaderKeyMap.LEFT_TOP).startsWith("单击: 向上滚动 (默认)"));
    }

    @Test public void moreOptionsSaveWithProfileAndNewProfileHasIndependentDefaults() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseOrientationSwipe");
        latest().getListView().performItemClick(null, ReaderKeyProfiles.SWIPE_UP, ReaderKeyProfiles.SWIPE_UP);
        ReflectionHelpers.callInstanceMethod(fragment, "chooseBoundary", ReflectionHelpers.ClassParameter.from(int.class, ReaderTouchAreas.ANIMATED_SPLIT));
        AlertDialog percent = latest();
        EditText input = find(percent.getWindow().getDecorView(), EditText.class);
        SeekBar slider = find(percent.getWindow().getDecorView(), SeekBar.class);
        assertNotNull(input); assertNull(slider);
        input.setText("58.125");
        percent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(0.7f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals(.58125f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, ReaderKeyProfiles.load().active().orientationSwipe);
        root.findViewById(R.id.reader_keys_add).performClick();
        assertEquals(0.7f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, ReaderKeyProfiles.load().active().orientationSwipe);
        root.findViewById(R.id.reader_keys_profile).performClick();
        latest().getListView().performItemClick(null, 0, 0);
        assertEquals(.58125f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, ReaderKeyProfiles.load().active().orientationSwipe);
    }

    @Test public void invalidPercentageKeepsDialogOpenWithoutChangingProfile() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseBoundary", ReflectionHelpers.ClassParameter.from(int.class, ReaderTouchAreas.ANIMATED_SPLIT));
        AlertDialog percent = latest();
        EditText input = find(percent.getWindow().getDecorView(), EditText.class);
        input.setText("101");
        percent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(percent.isShowing());
        assertNotNull(input.getError());
        assertEquals(0.7f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
    }

    @Test public void animatedBoundaryDragsCancelsAndAcceptsExactInput() {
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        View zones = root.findViewById(R.id.reader_keys_canvas);
        float x = zones.getWidth() / 6f;
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .7f);
        assertEquals(ReaderTouchAreas.ANIMATED_SPLIT, (int) ReflectionHelpers.getField(zones, "pressedLine"));
        sendTouch(zones, MotionEvent.ACTION_MOVE, x, zones.getHeight() * .6f);
        assertEquals(0.6f, draft.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        sendTouch(zones, MotionEvent.ACTION_CANCEL, x, zones.getHeight() * .6f);
        assertEquals(0.7f, draft.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .7f);
        sendTouch(zones, MotionEvent.ACTION_UP, x, zones.getHeight() * .7f);
        AlertDialog dialog = latest();
        ((EditText) dialog.findViewById(R.id.reader_keys_boundary_input)).setText("45.625");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(.45625f, draft.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .45625f);
        sendTouch(zones, MotionEvent.ACTION_MOVE, x, zones.getHeight() * .81234f);
        sendTouch(zones, MotionEvent.ACTION_UP, x, zones.getHeight() * .81234f);
        assertEquals(.81234f, draft.areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(.5f, draft.areas(0).position(ReaderTouchAreas.LEFT_SPLIT), 0f);
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals(.81234f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        // A zero-percent line remains reachable at the bottom edge.
        draft.setAreas(0, draft.areas(0).withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 1f));
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() - 1);
        sendTouch(zones, MotionEvent.ACTION_UP, x, zones.getHeight() - 1);
        assertNotNull(latest().findViewById(R.id.reader_keys_boundary_input));
    }

    @Test public void translationToggleCanBeSavedInNormalAndAnimatedBindings() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, ReaderKeyMap.TAP));
        int normalRow = latest().getListView().getCount() - 1;
        assertEquals("开启/关闭翻译", latest().getListView().getAdapter().getItem(normalRow));
        latest().getListView().performItemClick(null, normalRow, 0);
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        assertEquals(ReaderKeyMap.TOGGLE_TRANSLATION, draft.keys(0)[0]);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, ReaderKeyMap.LONG_PRESS));
        int animatedRow = latest().getListView().getCount() - 1;
        assertEquals("开启/关闭翻译", latest().getListView().getAdapter().getItem(animatedRow));
        latest().getListView().performItemClick(null, animatedRow, 0);
        root.findViewById(R.id.reader_keys_save).performClick();
        ReaderKeyProfiles.Profile saved = ReaderKeyProfiles.load().active();
        assertEquals(ReaderKeyMap.TOGGLE_TRANSLATION, saved.map().action(0, ReaderKeyMap.TAP, 0));
        assertEquals(ReaderKeyMap.TOGGLE_TRANSLATION, saved.map().action(0, ReaderKeyMap.LONG_PRESS, 0, true));
    }

    @Test public void animationEditingKeepsNormalKeysAndModeAcrossRecreation() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, ReaderKeyMap.LONG_PRESS));
        assertEquals(ReaderKeyMap.TOGGLE_TRANSLATION + 1, latest().getListView().getCount());
        assertEquals("保存当前并下一页", latest().getListView().getAdapter().getItem(ReaderKeyMap.SAVE_NEXT + 1));
        assertEquals("保存上一页", latest().getListView().getAdapter().getItem(ReaderKeyMap.SAVE_PREVIOUS + 1));
        latest().dismiss();
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, ReaderKeyMap.LONG_PRESS));
        assertEquals("保存上一页 (仅顺序播放时)", latest().getListView().getAdapter().getItem(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL + 1));
        latest().getListView().performItemClick(null, ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL + 1, 0);
        ReaderKeyProfiles.Profile draft = ReflectionHelpers.getField(fragment, "draft");
        assertEquals(ReaderKeyMap.LEGACY, draft.keys(0)[2]);
        assertEquals(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, draft.keys(0, true)[2]);
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertEquals(ReaderKeyMap.LEGACY, draft.keys(1, true)[2]);
        androidx.fragment.app.Fragment.SavedState state = controller.get().getSupportFragmentManager().saveFragmentInstanceState(fragment);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        fragment = new ReaderKeysFragment(); fragment.setInitialSavedState(state);
        controller.get().getSupportFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commitNow();
        root = fragment.requireView(); layout(360, 800);
        assertTrue(root.findViewById(R.id.reader_keys_animated_control).isSelected());
        root.findViewById(R.id.reader_keys_save).performClick();
        ReaderKeyProfiles.Profile saved = ReaderKeyProfiles.load().active();
        assertEquals(ReaderKeyMap.SAVE_PREVIOUS_SEQUENTIAL, saved.keys(0, true)[2]);
        assertEquals(ReaderKeyMap.LEGACY, saved.keys(0)[2]);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        assertFalse(root.findViewById(R.id.reader_keys_animated_control).isSelected());
    }

    @Test public void rtlRecommendedPreviousSideShowsConditionalSaveOnBothRightRegions() throws Exception {
        root.findViewById(R.id.reader_keys_manage).performClick();
        latest().getListView().performItemClick(null, 3, 3);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        root.findViewById(R.id.reader_keys_direction).performClick();
        for (int area : new int[]{ReaderKeyMap.RIGHT_TOP, ReaderKeyMap.RIGHT_BOTTOM}) {
            assertTrue(text(area).contains("单击: 上一页 (默认)"));
            assertTrue(text(area).contains("长按: 保存上一页 (仅顺序播放时)"));
        }
        render("animation-recommended-rtl", 360, 800);
        root.findViewById(R.id.reader_keys_save).performClick();
        openEditor(); layout(360, 800);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(ReaderKeyMap.RIGHT_BOTTOM).contains("长按: 保存上一页 (仅顺序播放时)"));
    }

    @Test public void floatingControlsAndMoreUseRequestedAppearance() {
        for (int id : new int[]{R.id.reader_keys_back, R.id.reader_keys_direction, R.id.reader_keys_animated_control}) {
            assertEquals(.5f, root.findViewById(id).getAlpha(), 0f);
        }
        androidx.appcompat.widget.AppCompatImageButton more = root.findViewById(R.id.reader_keys_more);
        androidx.appcompat.widget.AppCompatImageButton add = root.findViewById(R.id.reader_keys_add);
        assertEquals(add.getImageTintList().getDefaultColor(), more.getImageTintList().getDefaultColor());
        assertEquals(iconOpacity(add), iconOpacity(more));
        assertTrue(root.findViewById(R.id.reader_keys_animated_control).getRight() <= root.findViewById(R.id.reader_keys_direction).getLeft());
    }

    private int iconOpacity(androidx.appcompat.widget.AppCompatImageButton button) {
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable drawable = button.getDrawable();
        android.graphics.Rect previous = new android.graphics.Rect(drawable.getBounds());
        drawable.setBounds(0, 0, 48, 48);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(previous);
        int alpha = 0;
        for (int x = 0; x < 48; x++) for (int y = 0; y < 48; y++) alpha = Math.max(alpha, android.graphics.Color.alpha(bitmap.getPixel(x, y)));
        bitmap.recycle();
        assertTrue(alpha > 0);
        return alpha;
    }

    @Test public void renderPortraitLandscapeAndDarkWithoutShrinkingTouchViewport() throws Exception {
        render("portrait-light", 360, 800);
        renderDragging("drag-bubble-light", 360, 800);
        root.findViewById(R.id.reader_keys_direction).performClick();
        render("portrait-rtl", 360, 800);
        root.findViewById(R.id.reader_keys_direction).performClick();
        render("portrait-vertical", 360, 800);
        root.findViewById(R.id.reader_keys_direction).performClick();
        render("landscape-light", 800, 360);
        renderDragging("drag-bubble-landscape", 800, 360);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        render("animation-landscape-light", 800, 360);
        render("animation-portrait-light", 360, 800);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        Settings.putTheme(Settings.THEME_DARK);
        controller.get().setTheme(R.style.AppTheme_Settings_Dark);
        openEditor();
        render("portrait-dark", 360, 800);
        renderDragging("drag-bubble-dark", 360, 800);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        render("animation-portrait-dark", 360, 800);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        Settings.putTheme(Settings.THEME_BLACK);
        controller.get().setTheme(R.style.AppTheme_Settings_Black);
        openEditor();
        render("portrait-black", 360, 800);
        renderDragging("drag-bubble-black", 360, 800);
        root.findViewById(R.id.reader_keys_animated_control).performClick();
        render("animation-portrait-black", 360, 800);
    }

    @Test public void directionButtonEditsIndependentMappingsAndKeepsDraftsAcrossRecreation() {
        chooseLeftTap(ReaderKeyMap.NEXT);
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(0).startsWith("单击: 下一页 (默认)"));
        chooseLeftTap(ReaderKeyMap.PREVIOUS);
        root.findViewById(R.id.reader_keys_direction).performClick();
        chooseLeftTap(ReaderKeyMap.NONE);
        androidx.fragment.app.Fragment.SavedState state = controller.get().getSupportFragmentManager()
                .saveFragmentInstanceState(fragment);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        fragment = new ReaderKeysFragment();
        fragment.setInitialSavedState(state);
        controller.get().getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commitNow();
        root = fragment.requireView(); layout(360, 800);
        assertTrue(text(0).startsWith("单击: 无操作"));
        assertTrue(root.findViewById(R.id.reader_keys_direction).getContentDescription().toString()
                .contains(controller.get().getString(R.string.settings_read_reading_direction_top_to_bottom)));
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(0).startsWith("单击: 下一页\n"));
        root.findViewById(R.id.reader_keys_save).performClick();
        ReaderKeyProfiles.Profile saved = ReaderKeyProfiles.load().active();
        assertEquals(ReaderKeyMap.NEXT, saved.keys(0)[0]);
        assertEquals(ReaderKeyMap.PREVIOUS, saved.keys(1)[0]);
        assertEquals(ReaderKeyMap.NONE, saved.keys(2)[0]);
        assertEquals(GalleryView.LAYOUT_LEFT_TO_RIGHT, Settings.getReadingDirection());
    }

    private void chooseLeftTap(int action) {
        ReflectionHelpers.setField(fragment, "region", ReaderKeyMap.LEFT_TOP);
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, 0));
        latest().getListView().performItemClick(null, action + 1, action + 1);
    }

    @Test public void onlyExplicitDoubleTapNoneGetsQuickTapHint() {
        ReflectionHelpers.setField(fragment, "region", ReaderKeyMap.LEFT_TOP);
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, ReaderKeyMap.DOUBLE_TAP));
        AlertDialog choices = latest();
        assertEquals("缩放图片 (默认)", choices.getListView().getAdapter().getItem(0));
        assertEquals("无操作 (允许快速单击)", choices.getListView().getAdapter().getItem(1));
        choices.getListView().performItemClick(null, 1, 1);
        assertTrue(text(0).contains("双击: 无操作 (允许快速单击)"));
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAction", ReflectionHelpers.ClassParameter.from(int.class, ReaderKeyMap.TAP));
        assertEquals("无操作", latest().getListView().getAdapter().getItem(1));
        assertEquals("键位", controller.get().getString(R.string.reader_keys_title));
    }

    @Test public void retiredSwitchesAreAbsentAndReadingMenuStillInflates() throws Exception {
        Context context = controller.get();
        androidx.preference.PreferenceManager manager = new androidx.preference.PreferenceManager(context);
        for (int xml : new int[]{R.xml.read_settings, R.xml.fork_features_settings}) {
            androidx.preference.PreferenceScreen screen = manager.inflateFromResource(context, xml, null);
            assertNull(screen.findPreference("gallery_direct_save"));
            assertNull(screen.findPreference("gallery_quick_page_turn"));
            assertNull(screen.findPreference("gallery_quick_save_turn_page"));
        }
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        profiles.profiles.add(new ReaderKeyProfiles.Profile("夜间"));
        profiles.selected = profiles.profiles.size() - 1;
        profiles.save();
        GalleryActivity gallery = Robolectric.buildActivity(GalleryActivity.class).get();
        gallery.setTheme(R.style.AppTheme_Settings);
        try {
            Class<?> helper = Class.forName("com.hippo.ehviewer.ui.GalleryActivity$GalleryMenuHelper");
            java.lang.reflect.Constructor<?> constructor = helper.getDeclaredConstructor(GalleryActivity.class, Context.class);
            constructor.setAccessible(true);
            Object menu = constructor.newInstance(gallery, gallery);
            View view = ReflectionHelpers.callInstanceMethod(menu, "getView");
            android.widget.Spinner profile = view.findViewById(R.id.reader_key_profile);
            assertTrue(profile instanceof com.hippo.widget.CuteSpinner);
            assertEquals(profiles.profiles.size(), profile.getCount());
            assertEquals(profiles.selected, profile.getSelectedItemPosition());
            for (int i = 0; i < profile.getCount(); i++) {
                assertEquals(context.getString(R.string.reader_keys_profile_label, i + 1,
                        profiles.profiles.get(i).displayName(context, i)), profile.getItemAtPosition(i));
            }
            assertTrue(hasText(view, context.getString(R.string.reader_keys_profile_keyword)));
            assertFalse(hasText(view, context.getString(R.string.reader_keys_select_profile)));
            assertFalse(hasText(view, context.getString(R.string.reader_keys_title)));
            assertFalse(hasText(view, context.getString(R.string.settings_read_direct_save)));
            assertFalse(hasText(view, context.getString(R.string.settings_read_quick_page_turn)));
            profile.setSelection(0);
            assertEquals(profiles.selected, ReaderKeyProfiles.load().selected);
        } finally {
            ((java.util.concurrent.ExecutorService) ReflectionHelpers.getField(gallery, "mImageFileExecutor")).shutdownNow();
            ((java.util.concurrent.ExecutorService) ReflectionHelpers.getField(gallery, "transferService")).shutdownNow();
            ((android.os.Handler) ReflectionHelpers.getField(gallery, "mAnimatedWebpHandler")).removeCallbacksAndMessages(null);
        }
    }

    private boolean hasText(View view, String text) {
        if (view instanceof android.widget.TextView label && text.contentEquals(label.getText())) return true;
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) if (hasText(group.getChildAt(index), text)) return true;
        }
        return false;
    }

    @Test public void profileOrdinalIsDisplayedButNeverAddedToTheSavedName() {
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        profiles.profiles.remove(1);
        profiles.active().name = "配置3";
        profiles.profiles.add(new ReaderKeyProfiles.Profile("夜间"));
        profiles.save();
        openEditor(); layout(360, 800);
        android.widget.TextView button = root.findViewById(R.id.reader_keys_profile);
        assertTrue(button.getText().toString().startsWith("1. 配置3"));
        button.performClick();
        AlertDialog choices = latest();
        assertEquals("1. 配置3", choices.getListView().getAdapter().getItem(0));
        assertEquals("2. 夜间", choices.getListView().getAdapter().getItem(1));
        choices.getListView().performItemClick(null, 1, 1);
        assertTrue(button.getText().toString().startsWith("2. 夜间"));
        ReflectionHelpers.callInstanceMethod(fragment, "rename");
        EditText input = find(latest().getWindow().getDecorView(), EditText.class);
        assertEquals("夜间", input.getText().toString());
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals("夜间", ReaderKeyProfiles.load().active().name);
    }

    @Test public void recommendedMenuPreservesNameAndOtherProfilesAndSavesAllDirections() {
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        profiles.profiles.remove(1);
        profiles.active().name = "夜间";
        profiles.active().setAreas(0, profiles.active().areas(0).withPosition(ReaderTouchAreas.ANIMATED_SPLIT, 0.23f));
        profiles.active().orientationSwipe = ReaderKeyProfiles.SWIPE_OFF;
        profiles.active().keys(0)[0] = ReaderKeyMap.NONE;
        ReaderKeyProfiles.Profile other = new ReaderKeyProfiles.Profile("另外一份");
        other.keys(1)[0] = ReaderKeyMap.NEXT;
        profiles.profiles.add(other);
        profiles.save();
        openEditor(); layout(360, 800);
        root.findViewById(R.id.reader_keys_manage).performClick();
        AlertDialog menu = latest();
        assertEquals("使用推荐配置", menu.getListView().getAdapter().getItem(3));
        assertEquals("恢复默认配置", menu.getListView().getAdapter().getItem(4));
        menu.getListView().performItemClick(null, 3, 3);
        assertTrue(text(ReaderKeyMap.LEFT_TOP).contains("双击: 无操作 (允许快速单击)"));
        assertEquals(0.23f, ReaderKeyProfiles.load().active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertTrue(((android.widget.TextView) root.findViewById(R.id.reader_keys_profile)).getText().toString().contains("*"));
        root.findViewById(R.id.reader_keys_save).performClick();
        ReaderKeyProfiles saved = ReaderKeyProfiles.load();
        assertEquals(0, saved.selected);
        assertEquals("夜间", saved.active().name);
        ReaderKeyProfiles.Profile recommendation = ReaderKeyProfiles.recommended("夜间");
        for (int direction = 0; direction < ReaderKeyMap.DIRECTION_COUNT; direction++) {
            assertArrayEquals(recommendation.keys(direction), saved.active().keys(direction));
        }
        assertEquals(0.7f, saved.active().areas(0).position(ReaderTouchAreas.ANIMATED_SPLIT), .000001f);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, saved.active().orientationSwipe);
        assertEquals(ReaderKeyMap.NEXT, saved.profiles.get(1).keys(1)[0]);
        root.findViewById(R.id.reader_keys_manage).performClick();
        latest().getListView().performItemClick(null, 4, 4);
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals(ReaderKeyMap.LEGACY, ReaderKeyProfiles.load().active().keys(0)[1]);
    }

    @Test public void readerProfileSelectorUpdatesNumberedQuickIconAndSelection() throws Exception {
        ReaderKeyProfiles profiles = ReaderKeyProfiles.load();
        profiles.profiles.remove(1);
        profiles.active().name = "配置3";
        profiles.profiles.add(new ReaderKeyProfiles.Profile("夜间"));
        profiles.save();
        GalleryActivity gallery = Robolectric.buildActivity(GalleryActivity.class).get();
        gallery.setTheme(R.style.AppTheme_Gallery_Dark);
        android.widget.ImageButton quick = new android.widget.ImageButton(gallery);
        ReflectionHelpers.setField(gallery, "mQuickReaderKeyProfile", quick);
        try {
            ReflectionHelpers.callInstanceMethod(gallery, "bindQuickReaderKeyProfile");
            ReflectionHelpers.callInstanceMethod(gallery, "updateQuickReaderKeyProfile");
            assertEquals("切换配置：1. 配置3", quick.getContentDescription().toString());
            quick.performClick();
            assertEquals(1, ReaderKeyProfiles.load().selected);
            assertEquals("切换配置：2. 夜间", quick.getContentDescription().toString());
            assertEquals("已切换至配置 夜间", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            assertTrue(quick.performLongClick());
            assertEquals(1, ReaderKeyProfiles.load().selected);
            AlertDialog choices = latest();
            assertEquals("2. 夜间", choices.getListView().getAdapter().getItem(1));
            choices.getListView().performItemClick(null, 0, 0);
            assertEquals(0, ReaderKeyProfiles.load().selected);
            assertEquals("切换配置：1. 配置3", quick.getContentDescription().toString());
            assertEquals("已切换至 配置3", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            quick.performClick();
            quick.performClick();
            assertEquals(0, ReaderKeyProfiles.load().selected);
            profiles.profiles.remove(1);
            profiles.active().name = "";
            profiles.save();
            quick.performClick();
            assertEquals(0, ReaderKeyProfiles.load().selected);
            assertEquals("已切换至配置 默认", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            Bitmap image = Bitmap.createBitmap(240, 96, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(gallery.getColor(R.color.grey_850));
            for (int ordinal = 1; ordinal <= 3; ordinal++) {
                android.graphics.drawable.Drawable icon = new com.hippo.ehviewer.widget.ReaderKeyProfileDrawable(gallery, ordinal);
                icon.setBounds((ordinal - 1) * 80 + 16, 24, (ordinal - 1) * 80 + 64, 72);
                icon.draw(canvas);
            }
            File directory = new File("build/reader-key-previews");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, "quick-profile-icons.png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            image.recycle();
            Bitmap keyboard = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas keyboardCanvas = new Canvas(keyboard);
            android.graphics.drawable.Drawable keyboardIcon = controller.get().getDrawable(R.drawable.v_keyboard_primary_x24);
            keyboardIcon.setBounds(24, 24, 72, 72);
            keyboardIcon.draw(keyboardCanvas);
            try (FileOutputStream output = new FileOutputStream(new File(directory, "keyboard-icon.png"))) {
                assertTrue(keyboard.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            keyboard.recycle();
        } finally {
            ((java.util.concurrent.ExecutorService) ReflectionHelpers.getField(gallery, "mImageFileExecutor")).shutdownNow();
            ((java.util.concurrent.ExecutorService) ReflectionHelpers.getField(gallery, "transferService")).shutdownNow();
            ((android.os.Handler) ReflectionHelpers.getField(gallery, "mAnimatedWebpHandler")).removeCallbacksAndMessages(null);
        }
    }

    private void render(String name, int widthDp, int heightDp) throws Exception {
        layout(widthDp, heightDp);
        View canvas = root.findViewById(R.id.reader_keys_canvas);
        assertEquals(root.getWidth(), canvas.getWidth());
        assertEquals(root.getHeight(), canvas.getHeight());
        View bar = root.findViewById(R.id.reader_keys_bar);
        assertTrue(bar.getTop() > root.getHeight() / 2);
        View more = root.findViewById(R.id.reader_keys_more);
        View add = root.findViewById(R.id.reader_keys_add);
        assertSame(more.getParent(), add.getParent());
        assertTrue(more.getRight() <= add.getLeft());
        Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        File directory = new File("build/reader-key-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        image.recycle();
    }

    private void renderDragging(String name, int widthDp, int heightDp) throws Exception {
        layout(widthDp, heightDp);
        View zones = root.findViewById(R.id.reader_keys_canvas);
        float x = zones.getWidth() / 6f;
        sendTouch(zones, MotionEvent.ACTION_DOWN, x, zones.getHeight() * .5f);
        sendTouch(zones, MotionEvent.ACTION_MOVE, x, zones.getHeight() * .654321f);
        render(name, widthDp, heightDp);
        sendTouch(zones, MotionEvent.ACTION_CANCEL, x, zones.getHeight() * .654321f);
    }

    private void layout(int widthDp, int heightDp) {
        float density = controller.get().getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private String text(int area) {
        return ReflectionHelpers.callInstanceMethod(fragment, "regionText",
                ReflectionHelpers.ClassParameter.from(int.class, area));
    }
    private AlertDialog latest() {
        shadowOf(Looper.getMainLooper()).idle();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
    private <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
