package com.hippo.ehviewer.gallery;

import android.app.Application;
import android.content.Context;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.image.Image;
import com.hippo.unifile.UniFile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE,
        shadows = UpdatedGalleryProviderTest.FakeSpider.class)
public class UpdatedGalleryProviderTest {
    private Context context;
    private GalleryInfo gallery;
    private CaptureProvider provider;
    private FakeSpider spider;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        gallery = new GalleryInfo(); gallery.gid = 200; gallery.token = "token";
        provider = new CaptureProvider(context, gallery, 123);
        ReflectionHelpers.setField(provider, "mUpdatePages", new int[]{4, 17, 39});
        SpiderQueen queen = ReflectionHelpers.callConstructor(SpiderQueen.class,
                ReflectionHelpers.ClassParameter.from(EhApplication.class, null),
                ReflectionHelpers.ClassParameter.from(GalleryInfo.class, gallery));
        spider = Shadow.extract(queen);
        ReflectionHelpers.setField(provider, "mSpiderQueen", queen);
    }

    @Test public void filteredRequestsSavesAndNamesUseOriginalPageIndexes() {
        assertEquals(3, provider.size());
        provider.request(1);
        assertEquals(17, spider.lastPage);
        provider.save(2, (UniFile) null);
        assertEquals(39, spider.lastPage);
        provider.saveWithResult(0, null, "page");
        assertEquals(4, spider.lastPage);
        provider.prepareTranslationSource(1);
        assertEquals(17, spider.lastPage);
        assertEquals("200-token-00000018", provider.getImageFilename(1));
        assertEquals("00000018", provider.getTranslationFilename(1));
    }

    @Test public void callbacksSkipOldPagesAndMapSelectedPagesBackToReaderPositions() {
        provider.onPageSuccess(18, 1, 1, 40);
        assertEquals(-1, provider.changedIndex);
        provider.onPageSuccess(17, 1, 1, 40);
        assertEquals(1, provider.changedIndex);
        provider.onGetImageSuccess(39, null);
        assertEquals(2, provider.imageIndex);
        provider.onGetImageSuccess(38, null);
        assertEquals(2, provider.imageIndex);
    }

    @Test public void updatesResumeSeparatelyAndTranslationCloneKeepsPageSelection() {
        ReflectionHelpers.setField(provider, "mInitialUpdateReadingPage", 2);
        assertEquals(2, provider.getStartPage());
        GalleryProvider2 clone = provider.createTranslationProvider(context);
        assertEquals(17, clone.getSourcePage(1));
        assertEquals(2, clone.getStartPage());
        assertEquals(provider.getTranslationIdentity(), clone.getTranslationIdentity());
        assertNotEquals(new EhGalleryProvider(context, gallery).getTranslationIdentity(),
                clone.getTranslationIdentity());
    }

    private static class CaptureProvider extends EhGalleryProvider {
        int changedIndex = -1, imageIndex = -1;
        CaptureProvider(Context context, GalleryInfo gallery, long time) { super(context, gallery, time); }
        @Override public void notifyDataChanged(int index) { changedIndex = index; }
        @Override public void notifyPageSucceed(int index, Image image) { imageIndex = index; }
    }

    @Implements(SpiderQueen.class)
    public static class FakeSpider {
        int lastPage = -1;
        @Implementation protected void __constructor__(EhApplication app, GalleryInfo info) {}
        @Implementation protected int size() { return 40; }
        @Implementation protected Object request(int page) { lastPage = page; return null; }
        @Implementation protected boolean save(int page, UniFile file) { lastPage = page; return true; }
        @Implementation protected GalleryProvider2.SaveResult saveWithResult(int page, UniFile dir,
                                                                            String filename) {
            lastPage = page; return null;
        }
        @Implementation protected void prepareTranslationSource(int page) { lastPage = page; }
    }
}
