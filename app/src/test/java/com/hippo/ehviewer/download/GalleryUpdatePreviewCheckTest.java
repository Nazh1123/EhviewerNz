package com.hippo.ehviewer.download;

import android.app.Application;
import android.util.SparseArray;

import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.LargePreviewSet;
import com.hippo.ehviewer.spider.SpiderInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
public class GalleryUpdatePreviewCheckTest {
    @Test public void confirmationCanArriveBeforeOrAfterTheComparison() {
        var target = new GalleryDetail();
        target.gid = 200;
        var completed = new GalleryUpdatePreviewCheck.Result(null, "unavailable");
        for (boolean early : new boolean[]{true, false}) {
            var task = new GalleryUpdatePreviewCheck(RuntimeEnvironment.getApplication(), new GalleryInfo(), target);
            var window = new ArrayList<GalleryUpdatePreviewCheck.Result>();
            var service = new ArrayList<GalleryUpdatePreviewCheck.Result>();
            task.observe(window::add);
            if (!early) task.deliver(completed);
            task.handOff();
            var transferred = GalleryUpdatePreviewCheck.take(target.gid);
            assertSame(task, transferred);
            assertNull(GalleryUpdatePreviewCheck.take(target.gid));
            transferred.observe(service::add);
            if (early) {
                assertTrue(service.isEmpty());
                task.deliver(completed);
            }
            assertEquals(early ? 0 : 1, window.size());
            assertEquals(java.util.List.of(completed), service);
        }
    }

    @Test public void cancellingSuppressesLateResultsAndRevokesHandoff() {
        var target = new GalleryDetail();
        target.gid = 200;
        var task = new GalleryUpdatePreviewCheck(RuntimeEnvironment.getApplication(), new GalleryInfo(), target);
        task.handOff();
        task.observe(result -> fail("Cancelled check delivered a result"));
        task.cancel();
        task.deliver(new GalleryUpdatePreviewCheck.Result(null, "late"));
        assertNull(GalleryUpdatePreviewCheck.take(target.gid));
    }

    @Test public void separatePreviewPagesMergeByActualGalleryAndPageNumber() {
        var info = new SpiderInfo();
        info.gid = 200;
        info.pTokenMap = new SparseArray<>();
        var first = new LargePreviewSet();
        first.addItem(0, "", "https://e-hentai.org/s/0123456789/200-1");
        first.addItem(1, "", "https://e-hentai.org/s/1111111111/200-2");
        GalleryUpdatePreviewCheck.merge(info, 3, 2, 0, first);
        assertFalse(GalleryUpdateRecord.hasAllTokens(info));
        var last = new LargePreviewSet();
        last.addItem(2, "", "https://e-hentai.org/s/2222222222/201-3");
        GalleryUpdatePreviewCheck.merge(info, 3, 2, 1, last);
        assertNull(info.pTokenMap.get(2));
        last = new LargePreviewSet();
        last.addItem(2, "", "https://e-hentai.org/s/3333333333/200-3");
        GalleryUpdatePreviewCheck.merge(info, 3, 2, 1, last);
        assertEquals(2, info.previewPerPage);
        assertTrue(GalleryUpdateRecord.hasAllTokens(info));
    }
}
