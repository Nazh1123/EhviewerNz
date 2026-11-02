package com.hippo.ehviewer.gallery;

import com.hippo.a7zip.ArchiveException;
import com.hippo.unifile.UniFile;
import com.hippo.unifile.UniRandomAccessFile;
import com.hippo.util.NaturalComparator;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** One owner per handle: saving never shares the reader's archive seek position. */
abstract class ArchivePageSource implements Closeable {
    interface Factory { ArchivePageSource open() throws IOException; }
    abstract String[] names();
    abstract void extract(int page, OutputStream output) throws IOException;

    static String relativeName(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) return null;
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.equals("..")) return null;
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    static ArchivePageSource open(UniFile file) throws IOException {
        UniRandomAccessFile input = file.createRandomAccessFile("r");
        A7ZipArchive archive = null;
        try {
            archive = A7ZipArchive.create(input);
            List<A7ZipArchive.A7ZipArchiveEntry> entries = archive.getArchiveEntries();
            entries.removeIf(entry -> relativeName(entry.getPath()) == null);
            NaturalComparator comparator = new NaturalComparator();
            entries.sort((a, b) -> comparator.compare(relativeName(a.getPath()), relativeName(b.getPath())));
            String[] names = entries.stream().map(entry -> relativeName(entry.getPath())).toArray(String[]::new);
            A7ZipArchive owned = archive;
            return new ArchivePageSource() {
                @Override String[] names() { return names; }
                @Override void extract(int page, OutputStream output) throws IOException {
                    try { entries.get(page).extract(output); }
                    catch (ArchiveException failure) { throw new IOException(failure); }
                }
                @Override public void close() throws IOException {
                    try { owned.close(); } finally { input.close(); }
                }
            };
        } catch (ArchiveException | RuntimeException | LinkageError failure) {
            try { if (archive != null) archive.close(); } finally { input.close(); }
            throw new IOException("Cannot open archive", failure);
        }
    }
}
