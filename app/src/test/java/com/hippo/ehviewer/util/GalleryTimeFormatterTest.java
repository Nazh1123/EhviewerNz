package com.hippo.ehviewer.util;

import org.junit.Test;
import java.time.ZoneId;
import static org.junit.Assert.assertEquals;

public class GalleryTimeFormatterTest {
    @Test public void localTimeUsesOffsetAtPublicationIncludingDaylightSaving() {
        ZoneId zone = ZoneId.of("America/New_York");
        assertEquals("2026-01-01 07:00", GalleryTimeFormatter.format("2026-01-01 12:00", true, zone));
        assertEquals("2026-07-01 08:00", GalleryTimeFormatter.format("2026-07-01 12:00", true, zone));
        assertEquals("2026-03-08 01:59", GalleryTimeFormatter.format("2026-03-08 06:59", true, zone));
        assertEquals("2026-03-08 03:00", GalleryTimeFormatter.format("2026-03-08 07:00", true, zone));
    }

    @Test public void conversionPreservesPrecisionAcrossYearBoundary() {
        assertEquals("2027-01-01 08:15:42", GalleryTimeFormatter.format("2026-12-31 23:15:42", true, ZoneId.of("Asia/Tokyo")));
    }

    @Test public void disabledOrUnavailableDatesRemainUnchanged() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        assertEquals("2026-01-01 12:00", GalleryTimeFormatter.format("2026-01-01 12:00", false, zone));
        assertEquals("2026-02-30 12:00", GalleryTimeFormatter.format("2026-02-30 12:00", true, zone));
        assertEquals("Unknown", GalleryTimeFormatter.format("Unknown", true, zone));
        assertEquals("", GalleryTimeFormatter.format(null, true, zone));
    }
}
