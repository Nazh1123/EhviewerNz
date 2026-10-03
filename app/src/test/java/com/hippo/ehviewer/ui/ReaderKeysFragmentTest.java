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

    @Test public void labelsShowConcreteDefaultsAndTopCenterShowsOnlyTap() {
        String left = text(ReaderKeyMap.LEFT_TOP);
        assertTrue(left.startsWith("单击: 上一页 (默认)"));
        assertFalse(left.contains("左上"));
        assertTrue(left.contains("双击: 缩放图片 (默认)"));
        assertFalse(text(ReaderKeyMap.CENTER_TOP).contains("\n"));
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(ReaderKeyMap.LEFT_TOP).startsWith("单击: 下一页 (默认)"));
        root.findViewById(R.id.reader_keys_direction).performClick();
        assertTrue(text(ReaderKeyMap.LEFT_TOP).startsWith("单击: 向上滚动 (默认)"));
    }

    @Test public void moreOptionsSaveWithProfileAndNewProfileHasIndependentDefaults() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseOrientationSwipe");
        latest().getListView().performItemClick(null, ReaderKeyProfiles.SWIPE_UP, ReaderKeyProfiles.SWIPE_UP);
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAnimatedArea");
        AlertDialog percent = latest();
        EditText input = find(percent.getWindow().getDecorView(), EditText.class);
        SeekBar slider = find(percent.getWindow().getDecorView(), SeekBar.class);
        assertNotNull(input); assertNotNull(slider);
        input.setText("42");
        assertEquals(42, slider.getProgress());
        percent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(30, ReaderKeyProfiles.load().active().animatedControlPercent);
        root.findViewById(R.id.reader_keys_save).performClick();
        assertEquals(42, ReaderKeyProfiles.load().active().animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, ReaderKeyProfiles.load().active().orientationSwipe);
        root.findViewById(R.id.reader_keys_add).performClick();
        assertEquals(30, ReaderKeyProfiles.load().active().animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_DOWN, ReaderKeyProfiles.load().active().orientationSwipe);
        root.findViewById(R.id.reader_keys_profile).performClick();
        latest().getListView().performItemClick(null, 0, 0);
        assertEquals(42, ReaderKeyProfiles.load().active().animatedControlPercent);
        assertEquals(ReaderKeyProfiles.SWIPE_UP, ReaderKeyProfiles.load().active().orientationSwipe);
    }

    @Test public void invalidPercentageKeepsDialogOpenWithoutChangingProfile() {
        ReflectionHelpers.callInstanceMethod(fragment, "chooseAnimatedArea");
        AlertDialog percent = latest();
        EditText input = find(percent.getWindow().getDecorView(), EditText.class);
        input.setText("101");
        percent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(percent.isShowing());
        assertNotNull(input.getError());
        assertEquals(30, ReaderKeyProfiles.load().active().animatedControlPercent);
    }

    @Test public void renderPortraitLandscapeAndDarkWithoutShrinkingTouchViewport() throws Exception {
        render("portrait-light", 360, 800);
        root.findViewById(R.id.reader_keys_direction).performClick();
        render("portrait-rtl", 360, 800);
        root.findViewById(R.id.reader_keys_direction).performClick();
        render("portrait-vertical", 360, 800);
        root.findViewById(R.id.reader_keys_direction).performClick();
        render("landscape-light", 800, 360);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        controller.get().setTheme(R.style.AppTheme_Settings_Dark);
        openEditor();
        render("portrait-dark", 360, 800);
        controller.get().getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        Settings.putTheme(Settings.THEME_BLACK);
        controller.get().setTheme(R.style.AppTheme_Settings_Black);
        openEditor();
        render("portrait-black", 360, 800);
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

    private void layout(int widthDp, int heightDp) {
        float density = controller.get().getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private String text(int area) {
        return ReflectionHelpers.callInstanceMethod(fragment, "regionText",
                ReflectionHelpers.ClassParameter.from(int.class, area),
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
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
