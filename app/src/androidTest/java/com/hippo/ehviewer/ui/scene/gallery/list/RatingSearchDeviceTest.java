package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.SearchRating;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.widget.CategoryTable;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.ehviewer.widget.SearchLayout;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.widget.FabLayout;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Opt-in QA on a physical device, using the saved connection and actual website responses. */
@RunWith(AndroidJUnit4.class)
public class RatingSearchDeviceTest {
    private final Instrumentation in = InstrumentationRegistry.getInstrumentation();
    private final List<HttpUrl> lists = new CopyOnWriteArrayList<>();
    private final JSONArray checks = new JSONArray();
    private final JSONObject report = new JSONObject();
    private final Map<String, Object> clients = new LinkedHashMap<>();
    private volatile String phase = "setup";
    private Context app;
    private MainActivity activity;
    private GalleryListScene scene;
    private EhClient client;
    private File evidence;

    @Test(timeout=600000) public void liveRatingSearchFlow() throws Throwable {
        Assume.assumeTrue("Pass ratingLive=true to use the actual website",
                "true".equals(InstrumentationRegistry.getArguments().getString("ratingLive")));
        app = in.getTargetContext().getApplicationContext();
        Assume.assumeTrue("The shared precise rating feature must be enabled", Settings.getEnablePreciseRatingFilter());
        evidence = new File(app.getExternalFilesDir(null), "rating-search-device");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        AccessibilityServiceInfo accessibility = in.getUiAutomation().getServiceInfo();
        int oldFlags = accessibility.flags;
        accessibility.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        in.getUiAutomation().setServiceInfo(accessibility);
        client = EhApplication.getEhClient(app);
        try {
            for (String field : List.of("mOkHttpClient", "mBackgroundOkHttpClient")) {
                OkHttpClient original = (OkHttpClient) get(client, field);
                clients.put(field, original);
                set(client, field, original.newBuilder().eventListener(new EventListener() {
                    @Override public void callStart(Call call) {
                        HttpUrl url = call.request().url();
                        if ("GET".equals(call.request().method()) && "/".equals(url.encodedPath())
                                && "language:english".equals(url.queryParameter("f_search"))
                                && "767".equals(url.queryParameter("f_cats"))) {
                            lists.add(url);
                            Log.i("RatingLiveQA", phase + " list " + url);
                        }
                    }
                }).build());
            }
            activity = openMain();
            scene = (GalleryListScene) activity.getTopScene();
            stage("unfiltered-ten-pages");
            await(() -> helper() != null && helper().getShownViewIndex() == 0 && !helper().isRefreshing() && helper().size() > 0);
            main(() -> {
                scene.setState(GalleryListScene.STATE_SEARCH);
                SearchLayout options = (SearchLayout) get(scene, "mSearchLayout");
                options.setNormalSearchMode(R.id.search_normal_search);
                ((CategoryTable) get(options, "mCategoryTable")).setCategory(EhConfig.NON_H);
                ((android.widget.CompoundButton) get(options, "mEnableAdvanceSwitch")).setChecked(false);
                options.setMinimumRating(0);
                ((SearchBar) get(scene, "mSearchBar")).setText("language:english");
                scene.onApplySearch("language:english");
            });
            await(() -> helper().getShownViewIndex() == 0 && !helper().isRefreshing() && helper().getLoadedSourcePages() >= 1
                    && scene.mUrlBuilder.getCategory() == EhConfig.NON_H && helper().size() > 0);
            while (onMain(() -> helper().getLoadedSourcePages()) < 10) loadMore(false);
            assertEquals(0, onMain(() -> scene.mUrlBuilder.getEffectiveMinRatingHundredths()).intValue());
            check("unfilteredLoaded", new JSONObject().put("pages", onMain(() -> helper().getLoadedSourcePages()))
                    .put("candidates", onMain(() -> helper().size())).put("listCalls", lists.size()));

            stage("fab-default-and-shared-search");
            int before = lists.size();
            openRating();
            assertEquals("2.75", text("search_rating_value"));
            assertTrue(text("search_rating_current").contains("0.00"));
            screenshot("01-minimum-275-current-000.png");
            enterRating("3.50"); clickAndroid("button1"); awaitSorted(350);
            assertEquals("Sorting existing pages must not restart HTML search", before, lists.size());
            verifyVisible(350);
            Set<Long> at350 = onMain(this::visibleIds);
            check("fabShared", new JSONObject().put("minimum", 350).put("visible", at350.size()).put("extraHtml", lists.size()-before));

            stage("more-options-same-band");
            before = lists.size();
            main(() -> scene.setState(GalleryListScene.STATE_SEARCH));
            awaitNode("search_rating_value"); assertEquals("3.50", text("search_rating_value"));
            enterRating("3.70");
            main(() -> ((View) get(scene, "mSearchFab")).performClick());
            awaitSorted(370); verifyVisible(370);
            assertEquals(before, lists.size());
            main(() -> scene.setState(GalleryListScene.STATE_SEARCH));
            awaitNode("search_rating_value"); enterRating("3.50");
            main(() -> ((View) get(scene, "mSearchFab")).performClick());
            awaitSorted(350); verifyVisible(350);
            assertEquals(at350, onMain(this::visibleIds)); assertEquals(before, lists.size());
            openRating(); assertTrue(text("search_rating_current").contains("3.50"));
            screenshot("02-bidirectional-current-350.png"); clickAndroid("button2");
            check("bidirectionalAndSameBand", new JSONObject().put("restoredIds", true).put("extraHtml", lists.size()-before));

            stage("cross-band-without-more");
            before = lists.size();
            String originalNext = onMain(() -> helper().nextHref);
            applyFab("3.80", 380); verifyVisible(380);
            String stricterNext = onMain(() -> helper().nextHref);
            assertEquals("4", HttpUrl.get(stricterNext).queryParameter("f_srdd"));
            assertEquals(HttpUrl.get(originalNext).queryParameter("next"), HttpUrl.get(stricterNext).queryParameter("next"));
            applyFab("3.50", 350); assertEquals(at350, onMain(this::visibleIds));
            assertEquals(before, lists.size());
            check("crossBandWithoutMore", new JSONObject().put("restoredIds", true).put("extraHtml", lists.size()-before));

            stage("stricter-continuation");
            applyFab("3.80", 380);
            String boundary = onMain(() -> helper().nextHref);
            int continuationStart = lists.size();
            loadMore(true); verifyVisible(380);
            assertTrue(lists.size() > continuationStart);
            HttpUrl continuation = lists.get(continuationStart);
            assertEquals("4", continuation.queryParameter("f_srdd"));
            assertEquals(HttpUrl.get(boundary).queryParameter("next"), continuation.queryParameter("next"));
            int pagesBeforeRestart = onMain(() -> helper().getLoadedSourcePages());
            check("stricterContinuation", new JSONObject().put("cursorKept", true).put("serverOption", 4)
                    .put("pages", pagesBeforeRestart));

            stage("lowering-incomplete-coverage-restarts");
            int restartStart = lists.size();
            applyFab("3.50", 350);
            await(() -> helper().getLoadedSourcePages() >= pagesBeforeRestart && !loading());
            assertTrue(lists.size() > restartStart);
            HttpUrl first = lists.get(restartStart);
            assertNull("Restart must begin at search head", first.queryParameter("next"));
            assertEquals("3", first.queryParameter("f_srdd"));
            verifyVisible(350);
            openRating(); screenshot("03-researched-current-350.png");
            clickAndroid("button3");
            await(() -> !(boolean) get(scene, "mRatingSortMode") && !loading());
            assertEquals(350, onMain(() -> scene.mUrlBuilder.getEffectiveMinRatingHundredths()).intValue());
            verifyVisible(350);
            check("loweringIncompleteCoverage", new JSONObject().put("restartedAtHead", true)
                    .put("pages", onMain(() -> helper().getLoadedSourcePages())).put("restoreKeptMinimum", true));
            report.put("success", true);
        } catch (Throwable failure) {
            report.put("success", false).put("failure", failure.toString());
            try { screenshot("failure.png"); } catch (Throwable ignored) { }
            throw failure;
        } finally {
            report.put("phase", phase).put("device", android.os.Build.MODEL)
                    .put("android", android.os.Build.VERSION.RELEASE).put("checks", checks);
            JSONArray urls = new JSONArray(); for (HttpUrl url : lists) urls.put(url.toString());
            report.put("listUrls", urls);
            writeReport();
            if (scene != null) try { main(() -> call(scene, "stopRatingLoading")); } catch (Throwable ignored) { }
            if (activity != null) try { main(activity::finish); } catch (Throwable ignored) { }
            for (Map.Entry<String,Object> entry : clients.entrySet()) set(client, entry.getKey(), entry.getValue());
            accessibility.flags = oldFlags; in.getUiAutomation().setServiceInfo(accessibility);
        }
    }

    private void stage(String value) throws Exception { phase=value; Log.i("RatingLiveQA", "phase " + value); report.put("phase", phase); writeReport(); }
    private void check(String name, JSONObject result) throws Exception { checks.put(result.put("case", name)); report.put("checks", checks); writeReport(); Log.i("RatingLiveQA", "passed " + name); }
    private void writeReport() throws Exception { try (FileOutputStream out = new FileOutputStream(new File(evidence,"report.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); } }
    private GalleryListScene.GalleryListHelper helper() { return (GalleryListScene.GalleryListHelper)get(scene,"mHelper"); }
    private boolean loading() { return (boolean)get(scene,"mRatingLoading") || helper().getShownViewIndex() == 1
            || helper().isRefreshing() || (boolean)get(scene,"mRestartRatingSortAfterRefresh"); }
    private void awaitSorted(int minimum) throws Exception { await(() -> scene.mUrlBuilder.getEffectiveMinRatingHundredths()==minimum && !loading() && (boolean)get(scene,"mRatingSortMode")); }
    private void applyFab(String value, int minimum) throws Exception { openRating(); enterRating(value); clickAndroid("button1"); awaitSorted(minimum); }
    private void openRating() throws Exception { main(() -> { ((FabLayout)get(scene,"mFabLayout")).setExpanded(true,false); ((View)get(scene,"mRatingSortFab")).performClick(); }); awaitNode("search_rating_value"); }
    private void enterRating(String value) throws Exception {
        clickApp("search_rating_value");
        AccessibilityNodeInfo input = awaitClass("android.widget.EditText");
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)); clickAndroid("button1");
        awaitUi(() -> value.equals(text("search_rating_value")));
    }
    private void loadMore(boolean sorted) throws Exception {
        int pages = onMain(() -> helper().getLoadedSourcePages());
        main(() -> { assertTrue("More website results required", helper().hasNextPage()); assertTrue(helper().requestNextPage()); });
        await(() -> helper().getLoadedSourcePages()>pages && !loading() && (!sorted || (boolean)get(scene,"mRatingSortMode")));
    }
    private Set<Long> visibleIds() {
        GalleryAdapterNew adapter=(GalleryAdapterNew)get(scene,"mAdapter"); Set<Long> ids=new LinkedHashSet<>();
        int count=0; for(int i=0;i<adapter.getItemCount();i++){ GalleryInfo gallery=adapter.getDataAt(i);if(gallery!=null){count++;ids.add(gallery.gid);} }
        assertEquals("No duplicate displayed galleries",count,ids.size());return ids;
    }
    private void verifyVisible(int minimum) throws Exception { main(() -> {
        GalleryAdapterNew adapter=(GalleryAdapterNew)get(scene,"mAdapter"); Set<Long> expected=new HashSet<>();
        for(GalleryInfo gallery:helper().getData()) if(gallery.hasApiRating()&&SearchRating.matches(gallery.apiRating,minimum)) expected.add(gallery.gid);
        assertFalse("Live query must have matching galleries",expected.isEmpty()); assertEquals(expected,visibleIds());
        float previous=Float.POSITIVE_INFINITY;
        for(int i=0;i<adapter.getItemCount();i++){GalleryInfo gallery=adapter.getDataAt(i);if(gallery==null)continue;
            assertTrue(gallery.hasApiRating());assertTrue(SearchRating.matches(gallery.apiRating,minimum));
            if((boolean)get(scene,"mRatingSortMode")){assertTrue(gallery.apiRating<=previous);previous=gallery.apiRating;}
        }
    }); }
    private MainActivity openMain() throws Exception {
        Instrumentation.ActivityMonitor monitor=in.addMonitor(MainActivity.class.getName(),null,false);
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(in.getUiAutomation().executeShellCommand("am start -W -n "+app.getPackageName()+"/"+MainActivity.class.getName()))){while(input.read()!=-1){}}
        MainActivity result=(MainActivity)in.waitForMonitorWithTimeout(monitor,15000);in.removeMonitor(monitor);assertNotNull(result);
        main(() -> { result.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); result.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); });
        await(() -> result.getTopScene() instanceof GalleryListScene);return result;
    }
    private AccessibilityNodeInfo node(String id) { AccessibilityNodeInfo root=in.getUiAutomation().getRootInActiveWindow();if(root==null)return null;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId(id);return nodes.isEmpty()?null:nodes.get(0); }
    private void awaitNode(String id) throws Exception { awaitUi(() -> node(app.getPackageName()+":id/"+id)!=null); }
    private String text(String id) { AccessibilityNodeInfo node=node(app.getPackageName()+":id/"+id);return node==null?"":String.valueOf(node.getText()); }
    private void clickApp(String id) throws Exception { awaitNode(id); assertTrue(node(app.getPackageName()+":id/"+id).performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(250); }
    private void clickAndroid(String id) throws Exception { awaitUi(() -> node("android:id/"+id)!=null); assertTrue(node("android:id/"+id).performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(250); }
    private AccessibilityNodeInfo classNode(AccessibilityNodeInfo root,String clazz){if(root==null)return null;if(clazz.contentEquals(root.getClassName()))return root;for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo found=classNode(root.getChild(i),clazz);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo awaitClass(String clazz) throws Exception { awaitUi(() -> classNode(in.getUiAutomation().getRootInActiveWindow(),clazz)!=null);return classNode(in.getUiAutomation().getRootInActiveWindow(),clazz); }
    private void screenshot(String name) throws Exception { in.waitForIdleSync(); SystemClock.sleep(250); Bitmap bitmap=in.getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(FileOutputStream out=new FileOutputStream(new File(evidence,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle(); }
    private void await(Callable<Boolean> predicate) throws Exception { long end=SystemClock.elapsedRealtime()+90000;while(SystemClock.elapsedRealtime()<end){ if(onMain(predicate))return;SystemClock.sleep(150); }throw new AssertionError("Timeout: "+phase); }
    private void awaitUi(Callable<Boolean> predicate) throws Exception { long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(predicate.call())return;SystemClock.sleep(100);}throw new AssertionError("UI timeout: "+phase); }
    private <T> T onMain(Callable<T> action) throws Exception { AtomicReference<T> result=new AtomicReference<>(); main(() -> result.set(action.call())); return result.get(); }
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception { AtomicReference<Throwable> error=new AtomicReference<>();in.runOnMainSync(() -> {try{action.run();}catch(Throwable failure){error.set(failure);}});Throwable failure=error.get();if(failure instanceof Exception)throw(Exception)failure;if(failure!=null)throw new AssertionError(failure); }
    private static Object get(Object target,String name){try{Class<?> clazz=target.getClass();while(clazz!=null){try{Field field=clazz.getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(NoSuchFieldException ignored){clazz=clazz.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception error){throw new IllegalStateException(name,error);}}
    private static void set(Object target,String name,Object value){try{Class<?> clazz=target.getClass();while(clazz!=null){try{Field field=clazz.getDeclaredField(name);field.setAccessible(true);field.set(target,value);return;}catch(NoSuchFieldException ignored){clazz=clazz.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception error){throw new IllegalStateException(name,error);}}
    private static void call(Object target,String name)throws Exception{java.lang.reflect.Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}
}
