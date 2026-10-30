package com.hippo.ehviewer.client;
import android.app.Application;
import android.os.Bundle;
import com.hippo.ehviewer.client.data.GalleryInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;
import static com.hippo.ehviewer.client.GallerySeenStateTest.gallery;
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=28)
public class GallerySeenProjectionTest {
    private List<Long> ids(List<GalleryInfo> rows) {return rows.stream().map(g->g.gid).toList();}
    @Test public void initialLateMatchingGalleryMovesBeforeDividerButNextPageDoesNot() {
        GallerySeenProjection p=new GallerySeenProjection();
        GalleryInfo a=gallery(1,100),old=gallery(2,90),late=gallery(3,80),next=gallery(4,70);
        assertEquals(List.of(1L,3L,2L),ids(p.project(List.of(a,old,late),g->g.gid!=2)));
        assertEquals(List.of(1L,3L,2L,4L),ids(p.project(List.of(a,old,late,next),g->g.gid!=2)));
        assertFalse(p.inPrefix(4));
    }
    @Test public void allNewPagesRemainAppendOnlyUntilFirstOldBatchFormsDivider() {
        GallerySeenProjection p=new GallerySeenProjection();
        GalleryInfo a=gallery(1,100),b=gallery(2,90),old=gallery(3,80),late=gallery(4,70);
        p.project(List.of(a,b),g->true);
        assertEquals(List.of(1L,2L,4L,3L),ids(p.project(List.of(a,b,old,late),g->g.gid!=3)));
        assertTrue(p.inPrefix(4));
    }
    @Test public void acknowledgementAndRebindDoNotEraseInitialPrefix() {
        GallerySeenProjection p=new GallerySeenProjection();
        List<GalleryInfo> rows=List.of(gallery(1,100),gallery(2,90));
        p.project(rows,g->g.gid==1);
        assertEquals(List.of(1L,2L),ids(p.project(rows,g->false)));
        assertTrue(p.inPrefix(1));
    }
    @Test public void restoredSceneKeepsFrozenPrefixAndRefreshStartsAnotherCycle() {
        GallerySeenProjection p=new GallerySeenProjection();
        List<GalleryInfo> rows=List.of(gallery(1,100),gallery(2,90),gallery(3,80));
        p.project(rows.subList(0,2),g->g.gid==1);
        Bundle bundle=new Bundle();p.save(bundle);
        GallerySeenProjection restored=new GallerySeenProjection();restored.restore(bundle);
        assertEquals(List.of(1L,2L,3L),ids(restored.project(rows,g->g.gid!=2)));
        restored.reset();
        assertEquals(List.of(1L,3L,2L),ids(restored.project(rows,g->g.gid!=2)));
    }
    @Test public void oldOnlyFirstPageNeverPullsNextPageAboveTopDivider() {
        GallerySeenProjection p=new GallerySeenProjection();
        GalleryInfo old=gallery(1,100),late=gallery(2,90);
        p.project(List.of(old),g->false);
        assertEquals(List.of(1L,2L),ids(p.project(List.of(old,late),g->g.gid==2)));
        assertFalse(p.inPrefix(2));
    }
}
