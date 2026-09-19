package com.hippo.ehviewer.download;

import java.util.function.BooleanSupplier;

/** Gates destructive cleanup, including retries after some parent folders have been removed. */
final class GalleryUpdatePreparation {
    private GalleryUpdatePreparation() {}

    static boolean prepare(boolean progressMigrated, BooleanSupplier validateTarget,
                           BooleanSupplier migrateProgress, BooleanSupplier checkpointProgress) {
        // Always revalidate storage, even after a previously successful migration.
        if (!validateTarget.getAsBoolean()) return false;
        return progressMigrated || (migrateProgress.getAsBoolean()
                && checkpointProgress.getAsBoolean());
    }
}
