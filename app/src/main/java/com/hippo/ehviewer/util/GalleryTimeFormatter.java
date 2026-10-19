package com.hippo.ehviewer.util;

import com.hippo.ehviewer.Settings;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Converts canonical UTC website timestamps only at the presentation boundary. */
public final class GalleryTimeFormatter {
    private static final DateTimeFormatter MINUTES = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SECONDS = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private GalleryTimeFormatter() { }

    public static String format(String utcTime) {
        return format(utcTime, Settings.getUseLocalTimezone(), ZoneId.systemDefault());
    }

    static String format(String utcTime, boolean local, ZoneId zone) {
        if (utcTime == null) return "";
        if (!local || utcTime.isEmpty()) return utcTime;
        DateTimeFormatter formatter = utcTime.length() == 19 ? SECONDS : MINUTES;
        try {
            return LocalDateTime.parse(utcTime, formatter).atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(zone).format(formatter);
        } catch (DateTimeParseException ignored) {
            // Preserve unavailable, imported or legacy strings whose timezone cannot be inferred.
            return utcTime;
        }
    }
}
