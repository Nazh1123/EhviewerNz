package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Application;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.widget.FabLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class PopularGalleryActionsTest {
    private GalleryListScene scene;
    private View root;
    private FabLayout menu;
    private ListUrlBuilder builder;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(Settings.class, "sSettingsPre",
                app.getSharedPreferences("popular-actions-test", Context.MODE_PRIVATE));
        Context context = new ContextThemeWrapper(app, R.style.AppTheme);
        root = LayoutInflater.from(context).inflate(R.layout.scene_gallery_list, null);
        menu = root.findViewById(R.id.fab_layout);
        menu.setExpanded(false, false);
        scene = new GalleryListScene();
        builder = new ListUrlBuilder();
        builder.setMode(ListUrlBuilder.MODE_WHATS_HOT);
        ReflectionHelpers.setField(scene, "mUrlBuilder", builder);
        ReflectionHelpers.setField(scene, "mFabLayout", menu);
        bind("mPopularActions", R.id.popular_actions);
        bind("mPopularUpdates", R.id.popular_updates);
        bind("mPopularPrevious", R.id.popular_previous);
        bind("mPopularUpdatesMenu", R.id.popular_updates_menu);
        bind("mPopularPreviousMenu", R.id.popular_previous_menu);
        ReflectionHelpers.setField(scene, "mPopularResponseReady", true);
        ReflectionHelpers.setField(scene, "mPopularActionsOffered", true);
        menu.setOnExpandListener(scene);
        update();
    }

    @Test public void accumulatesSmallScrollStepsAndKeepsMenuActionsAfterHalfScreen() {
        RecyclerView recycler = new RecyclerView(root.getContext());
        recycler.layout(0, 0, 600, 1200);
        RecyclerView.OnScrollListener listener = ReflectionHelpers.getField(scene, "mOnScrollListener");
        // Refreshing while far down the list can reposition it by more than half a screen.
        listener.onScrolled(recycler, 0, -1200);
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_actions).getVisibility());
        ReflectionHelpers.setField(recycler, "mScrollState", RecyclerView.SCROLL_STATE_DRAGGING);
        for (int i = 0; i < 59; i++) listener.onScrolled(recycler, 0, 10);
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_actions).getVisibility());
        listener.onScrolled(recycler, 0, 10);
        assertEquals(View.GONE, root.findViewById(R.id.popular_actions).getVisibility());
        menu.setExpanded(true, false);
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_updates_menu).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_previous_menu).getVisibility());
        listener.onScrolled(recycler, 0, -600);
        assertEquals(View.GONE, root.findViewById(R.id.popular_actions).getVisibility());
    }

    @Test public void openingMenuDismissesOutsideActionsUntilAnotherResponse() {
        scene.onClickPrimaryFab(menu, menu.getPrimaryFab());
        assertTrue(menu.isExpanded());
        assertEquals(View.GONE, root.findViewById(R.id.popular_actions).getVisibility());
        menu.setExpanded(false, false);
        assertEquals(View.GONE, root.findViewById(R.id.popular_actions).getVisibility());
        ReflectionHelpers.setField(scene, "mPopularActionsOffered", true);
        update();
        assertEquals(View.VISIBLE, root.findViewById(R.id.popular_actions).getVisibility());
    }

    @Test public void actionsAppearOnlyOnPopularAndStayDisabledDuringRequests() {
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", true);
        update();
        assertEquals(View.GONE, root.findViewById(R.id.popular_actions).getVisibility());
        assertFalse(root.findViewById(R.id.popular_updates_menu).isEnabled());
        builder.setMode(ListUrlBuilder.MODE_NORMAL);
        update();
        assertEquals(View.GONE, root.findViewById(R.id.popular_updates_menu).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.popular_previous_menu).getVisibility());
    }

    @Test public void leavingDuringARequestSchedulesAFreshRequestOnReturn() {
        ReflectionHelpers.setField(scene, "mHasFirstRefresh", true);
        ReflectionHelpers.setField(scene, "mPopularRequestInFlight", true);
        scene.onDestroyView();
        assertFalse(ReflectionHelpers.<Boolean>getField(scene, "mHasFirstRefresh"));
        assertFalse(ReflectionHelpers.<Boolean>getField(scene, "mPopularRequestInFlight"));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void iconAndBackgroundColorsMatchNeighboringFabsInAllThemes() {
        for (int theme : new int[]{R.style.AppTheme, R.style.AppTheme_Dark, R.style.AppTheme_Black}) {
            Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), theme);
            View themed = LayoutInflater.from(context).inflate(R.layout.scene_gallery_list, null);
            FabLayout themedMenu = themed.findViewById(R.id.fab_layout);
            FloatingActionButton menuPeer = themed.findViewById(R.id.downloaded_only_fab);
            FloatingActionButton outsidePeer = themedMenu.getPrimaryFab();
            outsidePeer.setImageDrawable(new com.hippo.drawable.AddDeleteDrawable(context,
                    context.getColor(R.color.primary_drawable_dark)));
            for (int id : new int[]{R.id.popular_updates, R.id.popular_previous,
                    R.id.popular_updates_menu, R.id.popular_previous_menu}) {
                FloatingActionButton action = themed.findViewById(id);
                FloatingActionButton backgroundPeer = id == R.id.popular_updates || id == R.id.popular_previous
                        ? outsidePeer : menuPeer;
                assertEquals(backgroundPeer.getBackgroundTintList().getDefaultColor(),
                        action.getBackgroundTintList().getDefaultColor());
                assertEquals(iconColor(backgroundPeer), iconColor(action));
                assertNotNull(action.getContentDescription());
            }
        }
    }

    @Test public void iconMenuActionsFitAndDispatchWithOtherRoundFabs() {
        ReflectionHelpers.callInstanceMethod(scene, "showNormalFabs", ClassParameter.from(FabLayout.class, menu));
        menu.setExpanded(true, false);
        int width = (int) (360 * root.getResources().getDisplayMetrics().density);
        int height = (int) (640 * root.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        View updates = root.findViewById(R.id.popular_updates_menu);
        View previous = root.findViewById(R.id.popular_previous_menu);
        assertTrue(updates.getTop() >= 0);
        assertTrue(updates.getBottom() <= previous.getTop());
        assertTrue(previous.getBottom() <= menu.getPrimaryFab().getTop());
        final int[] clicked = {-1};
        menu.setOnClickFabListener(new FabLayout.OnClickFabListener() {
            @Override public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {}
            @Override public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
                clicked[0] = position;
            }
        });
        updates.performClick();
        assertEquals(8, clicked[0]);
        previous.performClick();
        assertEquals(9, clicked[0]);
    }

    private void bind(String field, int id) {
        ReflectionHelpers.setField(scene, field, root.findViewById(id));
    }
    private void update() {
        ReflectionHelpers.callInstanceMethod(scene, "updatePopularActions");
    }
    private int iconColor(FloatingActionButton button) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(48, 48,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable icon = button.getDrawable();
        icon.setBounds(0, 0, 48, 48);
        icon.draw(new android.graphics.Canvas(bitmap));
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 48; x++) {
                int color = bitmap.getPixel(x, y);
                if (android.graphics.Color.alpha(color) == 255) return color;
            }
        }
        throw new AssertionError("Icon did not draw any solid pixels");
    }
}
