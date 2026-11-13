package com.hippo.ehviewer.ui.dialog;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.ui.fragment.SubscriptionUpdatePreferences;
import com.hippo.ehviewer.widget.RatingFilterControl;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DialogActionLayoutTest {
    private Activity activity;

    @Before public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.AppTheme);
    }

    @Test public void ratingAndSubscriptionActionsUseTwoRowsAcrossLanguagesAndFontSizes() throws Exception {
        for (String language : new String[]{"en", "de", "es", "fr", "ja", "ko", "th", "zh-CN", "zh-TW", "zh-HK"}) {
            for (float scale : new float[]{1f, 1.3f}) {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.setLocale(Locale.forLanguageTag(language));
                config.fontScale = scale;
                Context context = new ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme);
                for (boolean subscription : new boolean[]{false, true}) {
                    AlertDialog dialog;
                    if (subscription) {
                        dialog = SubscriptionUpdatePreferences.showDialog(context, () -> {}, () -> {});
                    } else {
                        View content = LayoutInflater.from(context).inflate(R.layout.gallery_rating_sort_dialog, null);
                        new RatingFilterControl(content, 275, ignored -> {});
                        dialog = new AlertDialog.Builder(context)
                                .setTitle(R.string.gallery_rating_sort).setView(content)
                                .setNeutralButton(R.string.gallery_rating_sort_restore, null)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.gallery_rating_sort_execute, null).create();
                        dialog.show();
                        DialogActionLayout.placeNeutralAboveActions(dialog);
                    }
                    ViewGroup decor = layout(dialog, context);
                    Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                    Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    Rect n = bounds(decor, neutral), c = bounds(decor, cancel), p = bounds(decor, confirm);
                    String description = language + " scale=" + scale + " subscription=" + subscription;
                    assertTrue(description, n.bottom <= Math.min(c.top, p.top));
                    assertEquals(description, c.top, p.top);
                    assertTrue(description, c.right <= p.left);
                    assertEquals(description, n.right, p.right);
                    assertEquals(description, android.view.Gravity.RIGHT,
                            android.view.Gravity.getAbsoluteGravity(neutral.getGravity(), neutral.getLayoutDirection())
                                    & android.view.Gravity.HORIZONTAL_GRAVITY_MASK);
                    assertTrue(description, n.left >= 0 && p.right <= decor.getWidth());
                    assertTrue(description, p.bottom <= decor.getHeight());
                    for (Button button : new Button[]{neutral, cancel, confirm}) {
                        assertTrue(description, button.getLayout().getHeight() <= button.getHeight()
                                - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom());
                        for (int line = 0; line < button.getLineCount(); line++) {
                            assertEquals(description, 0, button.getLayout().getEllipsisCount(line));
                        }
                    }
                    if ((language.equals("en") && scale == 1f) || (language.equals("de") && scale == 1.3f)) {
                        save(decor, (subscription ? "subscription-" : "rating-") + language + ".png");
                    }
                    dialog.dismiss();
                }
            }
        }
    }

    @Test public void movingButtonsPreservesCallbacksAndAutomaticDismissal() {
        AtomicInteger restored = new AtomicInteger();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage("Dialog content")
                .setNeutralButton(R.string.gallery_rating_sort_restore, (d, which) -> restored.incrementAndGet())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
        android.text.method.TransformationMethod transformation = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .getTransformationMethod();
        DialogActionLayout.placeNeutralAboveActions(dialog);
        assertSame(transformation, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getTransformationMethod());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, restored.get());
        assertFalse(dialog.isShowing());
    }

    private ViewGroup layout(AlertDialog dialog, Context context) {
        int width = Math.round(320 * context.getResources().getDisplayMetrics().density);
        int height = Math.round(640 * context.getResources().getDisplayMetrics().density);
        dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        ViewGroup decor = (ViewGroup) dialog.getWindow().getDecorView();
        ShadowLooper.idleMainLooper();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        return decor;
    }

    private Rect bounds(ViewGroup root, View view) {
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        root.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private void save(View view, String name) throws Exception {
        File directory = new File("build/qa-dialog-layout");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
