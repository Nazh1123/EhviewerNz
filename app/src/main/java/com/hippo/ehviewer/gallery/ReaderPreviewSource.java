package com.hippo.ehviewer.gallery;

import java.io.IOException;

/** Owned and used by a single background preview worker. */
public interface ReaderPreviewSource extends AutoCloseable {
    ReaderPreviewBitmapCache.Result load(int index, int edge) throws IOException;
    @Override default void close() throws IOException { }
}
