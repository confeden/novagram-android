package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Metadata carried inside a file the user sends.
 *
 * <p>A photograph out of the camera roll carries the camera model, the exact
 * second it was taken and, unless the owner turned it off, the coordinates of
 * where. None of that is in the picture, all of it travels with the file, and
 * the caption is the only part the user meant to send.</p>
 *
 * <p>The blocks are walked and dropped, not re-encoded. Re-encoding costs a
 * generation of quality on a file the user deliberately chose to send without
 * compression, and swaps one encoder fingerprint for another. The desktop half
 * of the fork drops exactly the same blocks, so both platforms promise the same
 * thing.</p>
 *
 * <p>Photographs sent as photographs do not come through here and do not need
 * to: that path re-encodes through {@code Bitmap.compress}, which writes a new
 * file with nothing carried over. This is for the path that uploads the picked
 * bytes as they are — "send as file", sharing into the application, and the
 * formats that are always sent as documents.</p>
 */
public final class NovaOutgoingMetadata {

    private static final String PREFERENCES = "novagram_metadata";
    private static final String KEY_ENABLED = "enabled";

    private static final String FOLDER = "novagram_stripped";

    /** Stripping reads the file into memory; nothing photographic is close. */
    private static final long MAX_BYTES = 256L * 1024L * 1024L;

    /**
     * How long a cleaned copy is kept before it is swept. Long enough that a
     * queued upload over a slow connection is never pulled out from under
     * itself, short enough that the cache does not grow without bound.
     */
    private static final long KEEP_MS = 24L * 60L * 60L * 1000L;

    private NovaOutgoingMetadata() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        return preferences(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /**
     * Decided by the first bytes of the file and not by its name. A picture
     * shared from another application often arrives with a made-up extension
     * or none at all, and the name is the one thing about a file nobody has to
     * tell the truth in.
     */
    public static boolean canStrip(File file) {
        InputStream stream = null;
        try {
            stream = new FileInputStream(file);
            byte[] head = new byte[8];
            int read = stream.read(head);
            if (read < 8) {
                return false;
            }
            return isJpeg(head) || isPng(head);
        } catch (Throwable e) {
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Returns the path to send. When something was removed that is a cleaned
     * copy in the application's own cache; otherwise it is the path that came
     * in, unchanged.
     *
     * <p>Never edits in place. The path handed in is often the user's own file,
     * picked straight out of their storage, and rewriting it would destroy the
     * original to clean a copy that is being sent.</p>
     *
     * <p>The copy keeps the original file name, because the caller reads the
     * name off this path and the recipient sees it.</p>
     */
    public static String sanitizeDocument(String path) {
        if (path == null || !isEnabled()) {
            return path;
        }
        try {
            File source = new File(path);
            if (!source.exists() || source.length() == 0 || source.length() > MAX_BYTES) {
                return path;
            }
            if (!canStrip(source)) {
                return path;
            }
            byte[] original = read(source);
            if (original == null) {
                return path;
            }
            byte[] cleaned = strip(original);
            if (cleaned == null || cleaned.length == original.length) {
                return path;
            }
            File folder = folder();
            if (folder == null) {
                return path;
            }
            File target = new File(folder, source.getName());
            OutputStream stream = null;
            try {
                stream = new FileOutputStream(target);
                stream.write(cleaned);
                stream.flush();
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return target.getAbsolutePath();
        } catch (Throwable e) {
            // A file that could not be cleaned is sent as it is rather than not
            // sent at all, and the honest boundary for that is written in the
            // description under the switch.
            FileLog.e(e);
            return path;
        }
    }

    /**
     * A fresh directory for every call, so two sends of files with the same
     * name cannot overwrite each other. The cleaned copy has to outlive this
     * method — the upload reads it later, and it becomes the message's
     * attachment path — so it cannot be deleted here.
     *
     * <p>Old directories are removed by age and never by count. Counting was
     * the first version and it was wrong: it deleted every directory as soon
     * as there were more than a few, with no notion of which copies were still
     * queued for upload, so a batch of pictures could destroy its own files
     * halfway through sending.</p>
     */
    private static File folder() {
        File cache = ApplicationLoader.applicationContext.getCacheDir();
        if (cache == null) {
            return null;
        }
        File root = new File(cache, FOLDER);
        File[] old = root.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            for (File one : old) {
                // A clock moved backwards would otherwise keep every copy for
                // as long as it was set back by.
                long modified = one.lastModified();
                if (modified < cutoff || modified > System.currentTimeMillis() + KEEP_MS) {
                    delete(one);
                }
            }
        }
        File folder = new File(root, Long.toHexString(System.nanoTime()));
        return folder.isDirectory() || folder.mkdirs() ? folder : null;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    private static byte[] read(File file) {
        InputStream stream = null;
        try {
            stream = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Null when the format is not one of the two this build can clean. */
    public static byte[] strip(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (isJpeg(bytes)) {
            return stripJpeg(bytes);
        } else if (isPng(bytes)) {
            return stripPng(bytes);
        }
        return null;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length > 3
                && (bytes[0] & 0xff) == 0xFF
                && (bytes[1] & 0xff) == 0xD8;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length > 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == '\r' && bytes[5] == '\n'
                && (bytes[6] & 0xff) == 0x1A && bytes[7] == '\n';
    }

    /**
     * Everything an application, a camera or an editor writes about itself
     * lives in an APPn or a comment segment. The three kept are the ones that
     * change how the picture looks: the JFIF density, an embedded colour
     * profile, and the Adobe marker that says how the channels were
     * transformed.
     */
    private static boolean keepJpegSegment(int marker, byte[] bytes, int payload, int size) {
        switch (marker) {
            case 0xE0: // APP0, JFIF.
                return true;
            case 0xE2: // APP2: a colour profile, or a multi-picture index.
                return size >= 12 && matches(bytes, payload, "ICC_PROFILE");
            case 0xEE: // APP14, Adobe.
                return true;
            case 0xE1: // APP1: Exif and XMP, the two that carry everything.
            case 0xEC: // APP12, Ducky.
            case 0xED: // APP13, Photoshop and IPTC.
            case 0xFE: // COM.
                return false;
            default:
                // The remaining APPn are vendor blocks. Nothing reads them.
                return marker < 0xE0 || marker > 0xEF;
        }
    }

    private static boolean matches(byte[] bytes, int offset, String text) {
        if (offset + text.length() >= bytes.length) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return bytes[offset + text.length()] == 0;
    }

    private static byte[] stripJpeg(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 2); // SOI.

        int position = 2;
        final int size = bytes.length;
        while (position + 1 < size) {
            if ((bytes[position] & 0xff) != 0xFF) {
                // Not a marker where one has to be: the file is not what it
                // says it is, and guessing would be worse than leaving it.
                return bytes;
            }
            int markerAt = position + 1;
            int marker = bytes[markerAt] & 0xff;
            while (marker == 0xFF && markerAt + 1 < size) {
                markerAt++;
                marker = bytes[markerAt] & 0xff;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(bytes, position, markerAt + 1 - position);
                position = markerAt + 1;
                continue;
            } else if (marker == 0xDA || marker == 0xD9) {
                // Start of scan: what follows is compressed data, not segments.
                out.write(bytes, position, size - position);
                return out.toByteArray();
            }
            if (markerAt + 2 >= size) {
                return bytes;
            }
            int length = ((bytes[markerAt + 1] & 0xff) << 8) | (bytes[markerAt + 2] & 0xff);
            if (length < 2 || markerAt + 1 + length > size) {
                return bytes;
            }
            if (keepJpegSegment(marker, bytes, markerAt + 3, length - 2)) {
                out.write(bytes, position, markerAt + 1 + length - position);
            }
            position = markerAt + 1 + length;
        }
        // Fell off the end without ever reaching the scan. A whole JPEG always
        // leaves through the branch above, so getting here means the file is
        // truncated — and what has been collected is a header with no picture
        // behind it. Sending that instead of what the user picked would be
        // worse than sending an unstripped file.
        return bytes;
    }

    private static final String[] DROPPED_PNG_CHUNKS = {
            "tEXt", "zTXt", "iTXt", "tIME", "eXIf"
    };

    private static boolean keepPngChunk(byte[] bytes, int offset) {
        for (String dropped : DROPPED_PNG_CHUNKS) {
            boolean same = true;
            for (int i = 0; i < 4; i++) {
                if (bytes[offset + i] != (byte) dropped.charAt(i)) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEnd(byte[] bytes, int offset) {
        return bytes[offset] == 'I' && bytes[offset + 1] == 'E'
                && bytes[offset + 2] == 'N' && bytes[offset + 3] == 'D';
    }

    private static byte[] stripPng(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 8); // Signature.

        int position = 8;
        boolean ended = false;
        final int size = bytes.length;
        while (position + 8 <= size) {
            long length = ((long) (bytes[position] & 0xff) << 24)
                    | ((long) (bytes[position + 1] & 0xff) << 16)
                    | ((long) (bytes[position + 2] & 0xff) << 8)
                    | (bytes[position + 3] & 0xff);
            long whole = length + 12; // Length, type, data, checksum.
            if (length < 0 || position + whole > size) {
                return bytes;
            }
            if (keepPngChunk(bytes, position + 4)) {
                out.write(bytes, position, (int) whole);
            }
            boolean end = isEnd(bytes, position + 4);
            position += (int) whole;
            if (end) {
                ended = true;
                break;
            }
        }
        // No IEND: the file is truncated, and what was collected is a header
        // without a picture. Same rule as for JPEG — leave it alone.
        return ended ? out.toByteArray() : bytes;
    }
}
