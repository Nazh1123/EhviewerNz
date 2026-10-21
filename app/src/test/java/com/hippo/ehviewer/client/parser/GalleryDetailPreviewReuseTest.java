package com.hippo.ehviewer.client.parser;

import android.app.Application;
import android.os.Parcel;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.PreviewSet;
import com.hippo.ehviewer.spider.SpiderInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GalleryDetailPreviewReuseTest {
    @Before public void setup() { Settings.initialize(RuntimeEnvironment.getApplication()); }

    private String fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("GalleryDetail.html")) {
            assertNotNull(input);
            // The stored fixture is pretty printed; production HTML uses compact table rows.
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll(">\\s+<", "><");
        }
    }

    private void verify(String html, int expectedCount) throws Exception {
        GalleryDetail detail = GalleryDetailParser.parse(html);
        assertSame(detail.previewSet, detail.SpiderInfoPreviewSet);
        assertEquals(GalleryDetailParser.parsePages(html), detail.SpiderInfoPages);
        assertEquals(GalleryDetailParser.parsePreviewPages(html), detail.SpiderInfoPreviewPages);
        PreviewSet previous = GalleryDetailParser.parsePreviewSet(html);
        assertEquals(expectedCount, detail.previewSet.size());
        assertEquals(previous.size(), detail.SpiderInfoPreviewSet.size());
        for (int i = 0; i < previous.size(); i++) {
            assertEquals(previous.getPosition(i), detail.SpiderInfoPreviewSet.getPosition(i));
            assertEquals(previous.getPageUrlAt(i), detail.SpiderInfoPreviewSet.getPageUrlAt(i));
        }
        verifySpider(detail);
        Parcel parcel = Parcel.obtain();
        try {
            detail.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            // Parcel round trips may create two objects; SpiderInfo still consumes equivalent data.
            verifySpider(GalleryDetail.CREATOR.createFromParcel(parcel));
        } finally { parcel.recycle(); }
    }

    private void verifySpider(GalleryDetail detail) {
        SpiderInfo spider = SpiderInfo.getSpiderInfo(detail);
        assertNotNull(spider);
        assertEquals(detail.SpiderInfoPages, spider.pages);
        assertEquals(detail.previewPages, spider.previewPages);
        assertEquals(detail.previewSet.size(), spider.previewPerPage);
        for (int i = 0; i < detail.previewSet.size(); i++) {
            GalleryPageUrlParser.Result page = GalleryPageUrlParser.parse(detail.previewSet.getPageUrlAt(i));
            assertNotNull(page);
            assertEquals(page.pToken, spider.pTokenMap.get(page.page));
        }
    }

    @Test public void modernPreviewAndSpiderDataRemainEquivalent() throws Exception { verify(fixture(), 20); }

    @Test public void legacyNormalAndLargePreviewsRetainSpiderTokens() throws Exception {
        for (String preview : new String[] {
                "<div class=\"gdtm\"><div style=\"width:100px;height:140px;background:url(https://a.hath.network/c1/t/123-0.jpg) -100px 0\"><a href=\"https://exhentai.org/s/0123456789/123-2\"><img alt=\"2\"></a></div></div>",
                "<div class=\"gdtl\"><a href=\"https://exhentai.org/s/0123456789/123-2\"><img alt=\"2\" src=\"https://ehgt.org/large.jpg\"></a></div>"}) {
            Document document = Jsoup.parse(fixture());
            document.outputSettings().prettyPrint(false);
            // Legacy markup predates the modern gt100/gt200 container classes.
            document.getElementById("gdt").removeAttr("class");
            document.getElementById("gdt").html(preview);
            verify(document.outerHtml().replaceAll(">\\s+<", "><"), 1);
        }
    }
}
