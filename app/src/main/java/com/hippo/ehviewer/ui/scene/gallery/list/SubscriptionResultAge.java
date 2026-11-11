package com.hippo.ehviewer.ui.scene.gallery.list;

import android.content.res.Resources;

import com.hippo.ehviewer.R;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class SubscriptionResultAge {
    private SubscriptionResultAge() { }

    static String title(Resources resources, String title, long ageMillis) {
        long age = Math.max(0L, ageMillis);
        if (age <= 60_000L) {
            return resources.getString(R.string.subscription_result_age_seconds, title, age / 1000L);
        }
        if (age <= 3_600_000L) {
            return resources.getString(R.string.subscription_result_age_minutes, title, age / 60_000L);
        }
        String hours = BigDecimal.valueOf(age)
                .divide(BigDecimal.valueOf(3_600_000L), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return resources.getString(R.string.subscription_result_age_hours, title, hours);
    }
}
