package com.mellishy.customtag.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Tiny file helpers shared by the platform services (request store, counters, ledgers, ...).
 * Writes are atomic (tmp file + move) for the same reason {@code YamlStorageBackend} does it:
 * a crash mid-write must never leave a truncated, corrupt file behind - the previous complete
 * version simply survives instead.
 */
public final class JsonFiles {

    private JsonFiles() {}

    /** Atomically replaces {@code target} with {@code content} (UTF-8). Creates parent dirs. */
    public static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            // some filesystems (network shares) can't do atomic moves - plain replace is still
            // better than failing the save outright
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads the whole file as UTF-8, or returns {@code null} when it genuinely doesn't exist yet.
     *
     * A read FAILURE (permissions, a bad sector, a half-mounted volume, ...) throws instead of
     * returning null, and that distinction is the whole point. These files back the plugin's
     * never-reuse-an-id and never-lose-the-queue guarantees, and every caller loads one at startup
     * and then writes that same file back out later. Collapsing "unreadable" into "absent" meant a
     * transient I/O error silently reset the counters to zero (re-issuing REQ-00000001 and handing
     * out duplicate player custom ids), emptied the pending-request queue - and then overwrote the
     * still-perfectly-good file on disk with that empty state, destroying the only copy. Callers
     * must treat a thrown read as "degraded, do not overwrite", never as "start fresh".
     */
    public static String read(Path source) throws IOException {
        if (!Files.exists(source)) return null;
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    /** Appends one line (UTF-8, newline added) creating the file/parents when missing. */
    public static void appendLine(Path target, String line) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
