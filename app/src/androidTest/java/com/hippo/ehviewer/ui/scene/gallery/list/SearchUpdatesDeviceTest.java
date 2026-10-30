package com.hippo.ehviewer.ui.scene.gallery.list;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.WindowManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.adapter.GalleryAdapterNew;
import com.hippo.ehviewer.client.*;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.client.parser.ParserUtils;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.widget.GalleryInfoContentHelper;
import com.hippo.scene.Announcer;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.Buffer;
import org.json.*;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in device QA. Real HTTP/parser/manager/database/scene paths with deterministic responses. */
@RunWith(AndroidJUnit4.class)
public class SearchUpdatesDeviceTest {
    private final Instrumentation in = InstrumentationRegistry.getInstrumentation();
    private final JSONObject report = new JSONObject();
    private final JSONArray checks = new JSONArray();
    private final MockSite site = new MockSite();
    private final Map<String,Object> appFields = new LinkedHashMap<>();
    private Context app, isolated;
    private MainActivity activity;
    private File evidence;
    private SubscriptionUpdateManager manager;
    private String phase = "setup";
    private static final long ORIGIN = 1767225600000L;

    @Test(timeout=240000) public void deterministicDeviceFlows() throws Exception {
        Assume.assumeTrue("Pass searchUpdates=true", "true".equals(InstrumentationRegistry.getArguments().getString("searchUpdates")));
        app=in.getTargetContext().getApplicationContext();
        evidence=new File(app.getExternalFilesDir(null),"search-updates-device");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        Object prefs=get(Settings.class,"sSettingsPre"), config=get(Settings.class,"sEhConfig");
        Object dao=get(EhDB.class,"sDaoSession"), filter=get(EhFilter.class,"sInstance");
        Object engineFilter=EhEngine.sEhFilter, oldStoreContext=get(SearchUpdateStore.class,"defaultContext");
        SQLiteDatabase memory=null;
        try {
            for(String name:List.of("mEhClient","mSubscriptionUpdateManager","mOkHttpClient","mImageOkHttpClient",
                    "mBrowsingOkHttpClient","mBrowsingImageOkHttpClient","mConaco")) appFields.put(name,get(app,name));
            if(appFields.get("mSubscriptionUpdateManager")!=null) main(()->((SubscriptionUpdateManager)appFields.get("mSubscriptionUpdateManager")).cancelCheck());
            SharedPreferences testPrefs=app.getSharedPreferences("qa-search-updates-device",Context.MODE_PRIVATE);
            testPrefs.edit().clear().commit();set(Settings.class,"sSettingsPre",testPrefs);set(Settings.class,"sEhConfig",new EhConfig());
            Settings.putGallerySite(0);Settings.putShowWarning(false);Settings.putAskAnalytics(false);
            Settings.putNeedSignIn(false);Settings.putSelectSite(false);Settings.putSecurity("");
            Settings.putBoolean(Settings.KEY_SHOW_VIEWED_GALLERIES,true);
            Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_EH,false);
            Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK,false);
            Settings.putBoolean(Settings.KEY_ENABLE_ANALYTICS,false);
            Settings.putBoolean("show_gallery_pages",false);Settings.putBoolean("show_thumbnail_info_details",false);
            Settings.putBoolean("guide_quick_search",false);Settings.putBoolean("guide_collections",false);
            Settings.putBoolean("guide_download_thumb",false);Settings.putBoolean("guide_download_labels",false);
            Settings.putString(Settings.KEY_LIST_MODE,"0");Settings.putPersistentMinRatingHundredths(0);
            Settings.putLong("subscription_updates_last_check_time",System.currentTimeMillis());
            memory=SQLiteDatabase.create(null);DaoMaster.createAllTables(new org.greenrobot.greendao.database.StandardDatabase(memory),false);
            set(EhDB.class,"sDaoSession",new DaoMaster(memory).newSession());set(EhFilter.class,"sInstance",null);EhEngine.initialize();
            OkHttpClient http=new OkHttpClient.Builder().addInterceptor(site::respond).build();
            for(String name:List.of("mOkHttpClient","mImageOkHttpClient","mBrowsingOkHttpClient","mBrowsingImageOkHttpClient"))set(app,name,http);
            set(app,"mConaco",null);set(app,"mEhClient",new EhClient(app));
            manager=new SubscriptionUpdateManager(app);set(app,"mSubscriptionUpdateManager",manager);
            File dbRoot=new File(app.getCacheDir(),"qa-search-updates-"+System.nanoTime());assertTrue(dbRoot.mkdirs());
            isolated=new ContextWrapper(app) {
                @Override public Context getApplicationContext(){return this;}
                @Override public File getDatabasePath(String name){return new File(dbRoot,name);}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory,handler);}
            };
            SearchUpdateStore.initialize(isolated);
            site.png=thumbnail();
            report.put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
                    .put("sdk",android.os.Build.VERSION.SDK_INT).put("checks",checks);
            activity=openMain();
            plainFlow();
            aggregateFlow(false);
            aggregateFlow(true);
            capacityAndMinuteFlows(http);
            report.put("success",true);
        } catch(Throwable failure) {
            report.put("success",false).put("phase",phase).put("failure",failure.toString());
            try{screenshot("failure.png");}catch(Throwable ignored){}
            throw failure instanceof Exception ? (Exception)failure : new AssertionError(failure);
        } finally {
            report.put("phase",phase).put("http",site.requests);
            try(FileOutputStream out=new FileOutputStream(new File(evidence,"report.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
            if(manager!=null)main(manager::cancelCheck);
            if(activity!=null)main(activity::finish);
            in.waitForIdleSync();
            for(Map.Entry<String,Object> entry:appFields.entrySet())set(app,entry.getKey(),entry.getValue());
            set(Settings.class,"sSettingsPre",prefs);set(Settings.class,"sEhConfig",config);
            set(EhDB.class,"sDaoSession",dao);set(EhFilter.class,"sInstance",filter);EhEngine.sEhFilter=(EhFilter)engineFilter;
            set(SearchUpdateStore.class,"defaultContext",oldStoreContext);
            if(memory!=null)memory.close();
        }
    }

    private void plainFlow() throws Exception {
        phase="ordinary-search";
        List<Item> baseline=rows(710000,100,"qa_plain",25);
        site.put("qa_plain",baseline);site.pageSize=25;
        GalleryListScene scene=openSearch("qa_plain");await(()->dataSize(scene)==25);
        List<Item> changed=new ArrayList<>(baseline);
        Item normal=item(719001,101,"qa_plain"), late=item(719002,90,"qa_plain");
        changed.add(normal);changed.add(late);site.put("qa_plain",changed);
        refresh(scene);await(()->newIds(scene).containsAll(Set.of(normal.gid,late.gid)) && divider(scene)==2);
        assertEquals(Arrays.asList(normal.gid,late.gid,null),visible(scene).subList(0,3));
        screenshot("01-ordinary-late-before-divider.png");
        Item nextLate=item(719003,77,"qa_plain");changed.add(nextLate);site.put("qa_plain",changed);
        loadMore(scene);await(()->dataSize(scene)==28);
        assertEquals(2,divider(scene));assertTrue(visible(scene).indexOf(nextLate.gid)>divider(scene));
        assertTrue(newIds(scene).contains(nextLate.gid));
        scrollTo(scene,visible(scene).indexOf(nextLate.gid));screenshot("02-next-page-late-stays-below.png");
        refresh(scene);await(()->divider(scene)==0 && !refreshing(scene));
        long version=read(builder("qa_plain")).version;
        List<Long> before=visible(scene);site.failQuery="qa_plain";
        int requests=site.listCalls;refresh(scene);
        await(()->site.listCalls>requests && !refreshing(scene));
        assertEquals(version,read(builder("qa_plain")).version);assertEquals(before,visible(scene));
        site.failQuery="";
        checks.put(new JSONObject().put("case",phase).put("lateBeforeDivider",true)
                .put("nextPageNotReordered",true).put("repeatNoDuplicates",true).put("failedRefreshPreservesState",true));
    }

    private void aggregateFlow(boolean deep) throws Exception {
        phase=deep?"aggregate-later-page":"aggregate-independent-browse";
        main(()->{manager.cancelCheck();EhDB.getAllQuickSearch().forEach(EhDB::deleteQuickSearch);});
        String marker=deep?"qa_deep":"qa_merge";
        QuickSearch a=bookmark(marker,"qa_alice"),b=bookmark(marker,"qa_bob");
        List<Item> baseline=new ArrayList<>(rows(deep?740000:720000,100,"qa_alice",25));
        baseline.addAll(rows(deep?750000:730000,100,"qa_bob",25));site.put(marker,baseline);site.pageSize=25;
        Settings.putBoolean(Settings.KEY_AUTO_SUBSCRIPTION_UPDATES_BOOKMARK,true);
        SubscriptionUpdateManager.CheckResult seeded=check();assertEquals(0,seeded.newBookmarkCount);
        assertEquals(0,read(from(a)).viewed);assertEquals(0,read(from(b)).viewed);
        List<Item> changed=new ArrayList<>(baseline);
        List<Item> aNew=deep?rows(749000,130,"qa_alice",30):List.of(item(729001,110,"qa_alice"),item(729002,92,"qa_alice"));
        Item bNew=item(deep?759001:739001,deep?105:109,"qa_bob");
        Item bLate=item(deep?759002:739002,deep?104:91,"qa_bob");
        changed.addAll(aNew);changed.add(bNew);changed.add(bLate);site.put(marker,changed);
        SubscriptionUpdateManager.CheckResult found=check();assertEquals(aNew.size()+2,found.newBookmarkCount);
        assertEquals(aNew.size()+2,manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION).size());
        long aChecked=read(from(a)).checked;
        GalleryListScene single=openSearch(from(a));await(()->dataSize(single)==25 && !refreshing(single));
        assertTrue(read(from(a)).viewed>0);assertEquals(aChecked,read(from(a)).checked);
        assertEquals(Set.of(bNew.gid,bLate.gid),manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        if(!deep) {
            changed.remove(bLate);site.put(marker,changed);assertEquals(0,check().newBookmarkCount);
            assertEquals(Set.of(bNew.gid),manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
            changed.add(bLate);site.put(marker,changed);assertEquals(0,check().newBookmarkCount);
            assertEquals(Set.of(bNew.gid,bLate.gid),manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION));
        }
        GalleryListScene combined=openAggregate();await(()->dataSize(combined)>=25 && !refreshing(combined));
        assertTrue(manager.getUnreadGalleryGids(ListUrlBuilder.MODE_BOOKMARK_SUBSCRIPTION).isEmpty());
        if(deep) {
            assertEquals(0,divider(combined));assertFalse(visible(combined).contains(bLate.gid));
            loadMore(combined);await(()->visible(combined).contains(bLate.gid));
            assertEquals(0,divider(combined));assertTrue(visible(combined).indexOf(bLate.gid)>=25);
            scrollTo(combined,visible(combined).indexOf(bLate.gid));screenshot("05-aggregate-late-page-stable.png");
        } else {
            assertEquals(2,divider(combined));
            assertEquals(Arrays.asList(bNew.gid,bLate.gid,null),visible(combined).subList(0,3));
            assertTrue(visible(combined).indexOf(aNew.get(0).gid)>2);
            screenshot("03-aggregate-read-a-unread-b.png");
        }
        checks.put(new JSONObject().put("case",phase).put("independentCheckAndBrowse",true)
                .put("individualBrowseSuppressesAOnly",true).put("overallAcknowledgement",true)
                .put("restoredTagDoesNotRenotify",!deep).put("frozenDivider",true));
    }

    private void capacityAndMinuteFlows(OkHttpClient http) throws Exception {
        phase="capacity-and-minute-boundary";
        for(int size:new int[]{25,50,100}) {
            String marker="qa_capacity_"+size;site.pageSize=size;site.put(marker,rows(800000+size*100,500,"qa_capacity",size));
            int apiStart=site.apiBatches.size();
            GalleryListParser.Result page=EhEngine.getGalleryList(null,http,builder(marker).buildWithoutRatingFilter(),
                    ListUrlBuilder.MODE_NORMAL,true,true,450,false,null,null,true);
            assertEquals(size,page.rawGalleries.size());assertEquals(size,page.galleryInfoList.size());
            assertEquals((size+24)/25,site.apiBatches.size()-apiStart);
            try(SearchUpdateStore store=SearchUpdateStore.open()) {
                SearchUpdateStore.Pass p=new SearchUpdateStore.Pass(store.read("device-capacity",marker));
                p.observe(page.rawGalleries,page.galleryInfoList,page.rawTailPosted,false);store.commit(p,false);
                for(GalleryInfo g:page.galleryInfoList)assertFalse(store.read("device-capacity",marker).isNew(g));
            }
            checks.put(new JSONObject().put("case","capacity-"+size).put("stored",size).put("apiBatches",(size+24)/25));
        }
        site.pageSize=25;
        List<Item> same=new ArrayList<>();for(int i=0;i<35;i++)same.add(item(890000+i,200,"qa_minute"));
        same.add(item(889999,199,"qa_minute"));site.put("qa_minute",same);
        ListUrlBuilder query=builder("qa_minute");
        SearchUpdateSession seed=new SearchUpdateSession(query,null,false);scan(http,query,seed);seed.commit(false);
        Item x=item(899999,200,"qa_minute");same.add(x);site.put("qa_minute",same);
        SearchUpdateSession next=new SearchUpdateSession(query,null,false);scan(http,query,next);
        assertEquals(Set.of(x.gid),next.newGids());next.commit(false);
        SearchUpdateSession repeat=new SearchUpdateSession(query,null,false);scan(http,query,repeat);assertTrue(repeat.newGids().isEmpty());
        checks.put(new JSONObject().put("case","same-minute-cross-page").put("knownSameMinute",35).put("new",1).put("repeat",0));
        for(int count:site.apiBatches)assertTrue(count<=25);
    }

    private void scan(OkHttpClient http,ListUrlBuilder query,SearchUpdateSession session) throws Throwable {
        String url=query.buildWithoutRatingFilter();int count=0;
        do {
            GalleryListParser.Result page=EhEngine.getGalleryList(null,http,url,ListUrlBuilder.MODE_NORMAL,true,false,0,false);
            session.observe(page,page.nextHref!=null&&!page.nextHref.isEmpty());url=page.nextHref;
            assertTrue(++count<8);
        } while(!session.complete());
    }
    private QuickSearch bookmark(String marker,String uploader) {
        QuickSearch q=builder("uploader:\""+uploader+"\" title:\""+marker+"\"").toQuickSearch();q.subscribed=true;q.name=marker+uploader;EhDB.insertQuickSearch(q);return q;
    }
    private ListUrlBuilder builder(String keyword){ListUrlBuilder b=new ListUrlBuilder();b.setKeyword(keyword);return b;}
    private ListUrlBuilder from(QuickSearch q){ListUrlBuilder b=new ListUrlBuilder();b.set(q);return b;}
    private SearchUpdateStore.Snapshot read(ListUrlBuilder b){try(SearchUpdateStore store=SearchUpdateStore.open()){return store.read(SubscriptionSearchContext.key(),SearchUpdateStore.queryKey(b));}}
    private SubscriptionUpdateManager.CheckResult check() throws Exception {
        AtomicReference<SubscriptionUpdateManager.CheckResult> result=new AtomicReference<>();
        main(()->{manager.setListener(new SubscriptionUpdateManager.Listener(){public void onSubscriptionUpdateStateChanged(){}public void onSubscriptionUpdateCheckFinished(SubscriptionUpdateManager.CheckResult r){result.set(r);}});assertTrue(manager.checkForUpdates(true));});
        await(()->result.get()!=null);assertFalse("Check failed in "+phase,result.get().failed);return result.get();
    }
    private MainActivity openMain() throws Exception {
        Instrumentation.ActivityMonitor monitor=in.addMonitor(MainActivity.class.getName(),null,false);
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(in.getUiAutomation().executeShellCommand(
                "am start -W -n "+app.getPackageName()+"/"+MainActivity.class.getName()))){while(input.read()!=-1){}}
        MainActivity result=(MainActivity)in.waitForMonitorWithTimeout(monitor,12000);in.removeMonitor(monitor);assertNotNull("MainActivity did not launch",result);
        main(()->{result.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);result.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});
        await(()->result.getTopScene() instanceof GalleryListScene);return result;
    }
    private GalleryListScene openSearch(String keyword) throws Exception{return openSearch(builder(keyword));}
    private GalleryListScene openSearch(ListUrlBuilder b) throws Exception {
        main(()->activity.startScene(GalleryListScene.getStartAnnouncer(b)));
        await(()->activity.getTopScene() instanceof GalleryListScene && !((GalleryListScene)activity.getTopScene()).mUrlBuilder.build().isEmpty());
        in.waitForIdleSync();return (GalleryListScene)activity.getTopScene();
    }
    private GalleryListScene openAggregate() throws Exception {
        Bundle args=new Bundle();args.putString(GalleryListScene.KEY_ACTION,GalleryListScene.ACTION_BOOKMARK_SUBSCRIPTION);
        main(()->activity.startScene(new Announcer(GalleryListScene.class).setArgs(args)));
        in.waitForIdleSync();return (GalleryListScene)activity.getTopScene();
    }
    private GalleryInfoContentHelper helper(GalleryListScene s){return (GalleryInfoContentHelper)get(s,"mHelper");}
    private int dataSize(GalleryListScene s){return helper(s)==null?-1:helper(s).getData().size();}
    private boolean refreshing(GalleryListScene s){Object r=get(helper(s),"mRefreshLayout");return ((com.hippo.refreshlayout.RefreshLayout)r).isRefreshing();}
    @SuppressWarnings("unchecked") private Set<Long> newIds(GalleryListScene s){return new HashSet<>((Set<Long>)get(s,"mSeenNewGids"));}
    private List<Long> visible(GalleryListScene s){GalleryAdapterNew a=(GalleryAdapterNew)get(s,"mAdapter");List<Long> ids=new ArrayList<>();if(a!=null)for(int i=0;i<a.getItemCount();i++){GalleryInfo g=a.getDataAt(i);ids.add(g==null?null:g.gid);}return ids;}
    private int divider(GalleryListScene s){return visible(s).indexOf(null);}
    private void refresh(GalleryListScene s) throws Exception {main(()->helper(s).refresh());}
    private void loadMore(GalleryListScene s) throws Exception {main(()->{RecyclerView r=(RecyclerView)get(s,"mRecyclerView");r.scrollToPosition(Math.max(0,r.getAdapter().getItemCount()-1));});in.waitForIdleSync();main(()->{if(!refreshing(s)&&helper(s).hasNextPage())helper(s).requestNextPage();});}
    private void scrollTo(GalleryListScene s,int position) throws Exception {main(()->((RecyclerView)get(s,"mRecyclerView")).scrollToPosition(position));in.waitForIdleSync();SystemClock.sleep(300);}
    private void screenshot(String name) throws Exception {in.waitForIdleSync();SystemClock.sleep(250);Bitmap b=in.getUiAutomation().takeScreenshot();assertNotNull(b);try(FileOutputStream out=new FileOutputStream(new File(evidence,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}b.recycle();}
    private void await(Callable<Boolean> predicate) throws Exception {long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){AtomicReference<Boolean> ok=new AtomicReference<>(false);main(()->ok.set(predicate.call()));if(ok.get())return;SystemClock.sleep(80);}throw new AssertionError("Timeout: "+phase);}
    private interface Action {void run() throws Exception;}
    private void main(Action action) throws Exception {AtomicReference<Throwable> error=new AtomicReference<>();in.runOnMainSync(()->{try{action.run();}catch(Throwable t){error.set(t);}});Throwable t=error.get();if(t instanceof Exception)throw(Exception)t;if(t!=null)throw new AssertionError(t);}
    private static Object get(Object target,String name){try{Class<?> c=target instanceof Class?(Class<?>)target:target.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class?null:target);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private static void set(Object target,String name,Object value){try{Class<?> c=target instanceof Class?(Class<?>)target:target.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(target instanceof Class?null:target,value);return;}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}catch(Exception e){throw new IllegalStateException(name,e);}}
    private static Item item(long gid,int minute,String uploader){return new Item(gid,minute,uploader);}
    private static List<Item> rows(long gid,int head,String uploader,int count){List<Item> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(item(gid+i,head-i,uploader));return rows;}
    private static final class Item {final long gid;final int minute;final String uploader;Item(long g,int m,String u){gid=g;minute=m;uploader=u;}String posted(){return ParserUtils.formatPostedDate(ORIGIN+minute*60000L);}}
    private static byte[] thumbnail(){Bitmap b=Bitmap.createBitmap(160,220,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.rgb(35,72,112));Paint p=new Paint(3);p.setColor(Color.WHITE);p.setTextSize(34);c.drawText("QA",45,115,p);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();return out.toByteArray();}

    private static final class MockSite {
        final Map<String,List<Item>> datasets=new LinkedHashMap<>();
        final Map<Long,Item> items=new HashMap<>();
        final JSONArray requests=new JSONArray();
        final List<Integer> apiBatches=new ArrayList<>();
        volatile int pageSize=25,listCalls;volatile String failQuery="";byte[] png;
        synchronized void put(String key,List<Item> rows){List<Item> sorted=new ArrayList<>(rows);sorted.sort(Comparator.comparingInt((Item i)->i.minute).thenComparingLong(i->i.gid).reversed());datasets.put(key,sorted);for(Item i:rows)items.put(i.gid,i);}
        synchronized Response respond(Interceptor.Chain chain) throws IOException {
            Request req=chain.request();String query=req.url().queryParameter("f_search");if(query==null)query="";
            try {
                if(req.url().encodedPath().endsWith(".png"))return response(req,png,"image/png");
                if(req.method().equals("POST")) {
                    Buffer buffer=new Buffer();req.body().writeTo(buffer);JSONArray ids=new JSONObject(buffer.readUtf8()).getJSONArray("gidlist");apiBatches.add(ids.length());JSONArray data=new JSONArray();
                    for(int i=0;i<ids.length();i++){long gid=ids.getJSONArray(i).getLong(0);Item item=items.get(gid);data.put(new JSONObject().put("gid",gid).put("first_gid",gid).put("title","QA "+gid).put("title_jpn","").put("category","Manga").put("thumb","https://qa.invalid/"+gid+".png").put("uploader",item.uploader).put("posted",(ORIGIN+item.minute*60000L)/1000).put("filecount",10).put("rating","4.50").put("tags",new JSONArray()));}
                    requests.put(new JSONObject().put("method","POST").put("gids",ids.length()));return response(req,new JSONObject().put("gmetadata",data).toString().getBytes(StandardCharsets.UTF_8),"application/json");
                }
                listCalls++;requests.put(new JSONObject().put("method","GET").put("query",query).put("page",req.url().queryParameter("qa_page")));
                if(!failQuery.isEmpty()&&query.contains(failQuery))throw new IOException("QA simulated offline");
                List<Item> list=new ArrayList<>();for(Map.Entry<String,List<Item>> entry:datasets.entrySet())if(query.contains(entry.getKey())){list.addAll(entry.getValue());break;}
                if(query.contains("uploader:")) {boolean a=query.contains("qa_alice"),b=query.contains("qa_bob");list.removeIf(i->!(a&&i.uploader.equals("qa_alice")||b&&i.uploader.equals("qa_bob")));}
                int page=req.url().queryParameter("qa_page")==null?0:Integer.parseInt(req.url().queryParameter("qa_page"));
                int start=Math.min(list.size(),page*pageSize),end=Math.min(list.size(),start+pageSize);
                StringBuilder html=new StringBuilder("<html><body><div class='searchnav'>");
                if(end<list.size()){String next=req.url().newBuilder().setQueryParameter("qa_page",Integer.toString(page+1)).setQueryParameter("next",Long.toString(list.get(end-1).gid)).build().toString();html.append("<a id='unext' href='").append(next.replace("&","&amp;")).append("'>Next</a>");}
                html.append("</div><div class='searchtext'>Found ").append(list.size()).append(" results</div><table class='itg gltc'><tbody><tr><th>QA</th></tr>");
                if(start==end)html.append("<tr><td>No hits found</td></tr>");
                for(Item i:list.subList(start,end))html.append("<tr><td><div class='cs'>Manga</div></td><td><div class='glthumb'><div><img style='height:160px;width:100px;' src='https://qa.invalid/").append(i.gid).append(".png'/></div></div></td><td><div class='glname'><a href='https://e-hentai.org/g/").append(i.gid).append("/0123456789/'><div class='glink'>QA ").append(i.uploader).append(" #").append(i.gid).append("</div></a></div><div id='posted_").append(i.gid).append("'>").append(i.posted()).append("</div><div class='ir' style='background-position:0px -1px'></div><div class='glhide'><div><a>").append(i.uploader).append("</a></div><div>10 pages</div></div></td></tr>");
                html.append("</tbody></table></body></html>");return response(req,html.toString().getBytes(StandardCharsets.UTF_8),"text/html");
            }catch(JSONException e){throw new IOException(e);}
        }
        private Response response(Request req,byte[] body,String type){return new Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(MediaType.parse(type),body)).build();}
    }
}
