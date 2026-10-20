package com.example.foldword.doc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe writing for files inside the app's private storage (drafts, backups, records).
 * The new content is written to a temporary file in the same folder, flushed to disk, and only then
 * moved over the old file, so a crash or full storage never leaves a half-written file behind.
 */
public final class SafeFiles {

    private SafeFiles() {}

    /** Thrown when the device does not have enough free space. */
    public static final class LowStorageException extends IOException {
        public LowStorageException(String m) { super(m); }
    }

    /** Extra free space kept in reserve so the phone itself never runs completely full. */
    public static final long RESERVE_BYTES = 2L * 1024 * 1024;

    public static void writeAtomic(File target, byte[] data) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (dir == null) throw new IOException("No folder for " + target);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create folder " + dir);
        long free = dir.getUsableSpace();
        if (free > 0 && free < data.length + RESERVE_BYTES) {
            throw new LowStorageException("Not enough free storage to save (" + (data.length / 1024 + 1)
                + " KB needed). Free some space and try again.");
        }
        File tmp = new File(dir, "." + target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            if (isNoSpace(e)) throw new LowStorageException("The phone's storage is full. Free some space and try again.");
            throw e;
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static byte[] read(File f) throws IOException {
        return Files.readAllBytes(f.toPath());
    }

    /** True when an IOException means "no space left on device". */
    public static boolean isNoSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("ENOSPC") || m.toLowerCase(java.util.Locale.ROOT).contains("no space left"))) return true;
        }
        return false;
    }
}
