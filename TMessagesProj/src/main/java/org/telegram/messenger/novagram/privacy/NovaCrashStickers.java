package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Refuses to decode a sticker that is not a sticker but a bomb.
 *
 * <p>A sticker set is content the sender picks and the receiver opens without
 * being asked, so a file that kills the decoder is a remote crash. This is the
 * twin of the desktop {@code NovaGram::CrashStickers} module and carries the
 * same limits; the desktop half is the one that needed it, but a fork promise
 * is made for both platforms.</p>
 *
 * <h3>What it looks at, and what it does not</h3>
 *
 * <p>Stickers from a real set, and nothing else. Emoji of every kind are out:
 * reactions, animated emoji, custom emoji, statuses, topic icons, dice and
 * gift animations are the same .tgs files to a decoder, but they are not what
 * a sender hands you — and an ordinary reaction being shown as "neutralised"
 * is a false accusation about a file Telegram itself supplied. See
 * {@code isSticker}.</p>
 *
 * <h3>What "not a sticker" means</h3>
 *
 * <p>Telegram's own publishing rules, loosened by a wide margin: 512x512
 * becomes 1024, 64 KB becomes 4 MB, three seconds become thirty. A file past
 * any of those was never going to be drawn as a sticker, so refusing it costs
 * nobody a sticker they could have seen. On top of the sizes the animation
 * itself is read as bytes first — how deep it nests and how many objects it
 * holds — because the two cheap ways to write a crash file are nesting that
 * overflows the parser's own stack and a scene that is small to write and
 * impossible to rasterise.</p>
 */
public final class NovaCrashStickers {

    /** 512x512 is what Telegram publishes; 1024 cannot be reached by accident. */
    private static final int MAX_SIDE = 1024;
    private static final long MAX_AREA = 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_UNPACKED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DURATION_SECONDS = 30;

    private static final int MAX_DEPTH = 128;
    private static final int MAX_OBJECTS = 200 * 1000;
    private static final int MAX_ITEMS = 1000 * 1000;
    private static final int MAX_REPEATER_COPIES = 100;
    private static final double MAX_FPS = 120.;
    private static final double MAX_FRAMES = 1200.;

    /**
     * One entry per file looked at. Cleared wholesale rather than by age: the
     * answers are cheap to work out again, a map that grows for the life of
     * the process is not.
     */
    private static final int VERDICTS_LIMIT = 4096;

    private static final Map<String, Boolean> verdicts = new ConcurrentHashMap<>();

    private static volatile Boolean cachedEnabled;

    private NovaCrashStickers() {
    }

    public static boolean isEnabled() {
        final Boolean known = cachedEnabled;
        if (known != null) {
            return known;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            // Asked before the application object exists. On is the default,
            // so answering it costs nobody the setting they chose - but it is
            // not the setting, so it is not remembered either.
            return true;
        }
        final boolean enabled = NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.CRASH_STICKER_GUARD);
        cachedEnabled = enabled;
        return enabled;
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        NovaPrivacySettings.global(context)
                .setFeatureEnabled(NovaPrivacyFeature.CRASH_STICKER_GUARD, enabled);
        cachedEnabled = enabled;
        verdicts.clear();
    }

    /**
     * The single question the image loader asks, on its own thread, before the
     * file reaches rlottie, FFmpeg or BitmapFactory.
     *
     * @param document what the server said about the file, or null
     * @param lottie   whether the loader is about to treat it as an animation
     * @param path     the file on disk, already downloaded
     */
    public static boolean refuse(TLRPC.Document document, boolean lottie, String path) {
        if (!isEnabled()) {
            return false;
        }
        if (document == null) {
            // Nothing says what this is, so nothing says it is a sticker. What
            // reaches here with no document is the application's own bundled
            // animation, not a file anybody sent — and judging those produced
            // accusations instead of protection.
            return false;
        } else if (!isSticker(document)) {
            // Photos, videos, plain files and every kind of emoji are none of
            // this switch's business: refusing one would hide something real.
            return false;
        } else if (declaredDangerous(document)) {
            return true;
        }
        return refuseByFile(lottie, path);
    }

    /**
     * Whether this document must not leave the device.
     *
     * <p>What the guard refuses to draw it also refuses to relay: the file
     * would arrive at somebody whose client has no such guard, and forwarding
     * a crash file is the only thing worse than opening one. Asked on the send
     * and forward paths, where the file is already on disk - a sticker that is
     * being sent has been drawn first, so the verdict is usually cached.</p>
     */
    public static boolean refuseToSend(int account, TLRPC.Document document) {
        if (!isEnabled() || document == null || !isSticker(document)) {
            return false;
        } else if (declaredDangerous(document)) {
            return true;
        }
        final File file = FileLoader.getInstance(account).getPathToAttach(document, true);
        if (file == null || !file.exists()) {
            // Nothing on disk to look at. Not refused: the send path is not
            // the place to guess, and whoever draws it will ask again.
            return false;
        }
        return refuseByFile(
                "application/x-tgsticker".equals(document.mime_type),
                file.getAbsolutePath());
    }

    private static boolean refuseByFile(boolean lottie, String path) {
        if (path == null || path.length() == 0) {
            return false;
        }
        final Boolean known = verdicts.get(path);
        if (known != null) {
            return known;
        }
        boolean dangerous;
        try {
            dangerous = fileDangerous(lottie, new File(path));
        } catch (Throwable e) {
            // A StackOverflowError from a parser is exactly the shape of
            // attack this exists to stop, so a file that throws is refused
            // rather than excused.
            dangerous = true;
        }
        if (verdicts.size() >= VERDICTS_LIMIT) {
            verdicts.clear();
        }
        verdicts.put(path, dangerous);
        return dangerous;
    }

    /**
     * A sticker, and nothing else that happens to be drawn by the same
     * decoder. Emoji are deliberately outside this: reactions, animated emoji,
     * statuses, topic icons, dice and gift animations all arrive as .tgs files
     * and all went through here, which made an ordinary reaction show up as
     * "neutralised" — a false accusation about a file Telegram itself supplied.
     *
     * <p>The line is who chose the file. This guard exists against a document
     * <em>another user sends</em>, and only a real set — one with an id or a
     * short name — can be sent from. Everything the server hands out from its
     * own configuration carries one of the special set types or no set at all,
     * and none of it is content anybody picked to send here. A custom emoji is
     * refused as a sticker for the same reason and by the owner's rule: emoji
     * are not checked, stickers are.</p>
     */
    private static boolean isSticker(TLRPC.Document document) {
        if (document.attributes == null) {
            return false;
        }
        TLRPC.TL_documentAttributeSticker sticker = null;
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                return false;
            } else if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                sticker = (TLRPC.TL_documentAttributeSticker) attribute;
            }
        }
        return sticker != null
                && (sticker.stickerset instanceof TLRPC.TL_inputStickerSetID
                        || sticker.stickerset instanceof TLRPC.TL_inputStickerSetShortName);
    }

    /**
     * What the server said, which is known before a byte is downloaded. A lie
     * here is caught by the file itself below; the truth here is caught first.
     */
    private static boolean declaredDangerous(TLRPC.Document document) {
        if (document.size > MAX_FILE_BYTES) {
            return true;
        }
        if (document.attributes == null) {
            return false;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                if (tooBig(attribute.w, attribute.h)) {
                    return true;
                }
            } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                if (tooBig(attribute.w, attribute.h)
                        || attribute.duration > MAX_DURATION_SECONDS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tooBig(int width, int height) {
        return width > MAX_SIDE
                || height > MAX_SIDE
                || (long) width * height > MAX_AREA;
    }

    private static boolean fileDangerous(boolean lottie, File file) throws Exception {
        if (!file.exists()) {
            return false;
        }
        final long length = file.length();
        if (length > MAX_FILE_BYTES) {
            return true;
        }
        if (lottie || gzipped(file)) {
            final Unpacked unpacked = unpack(file);
            if (unpacked.overflowed) {
                // Only a bomb gets here. Measured on a popular pack: the
                // heaviest sticker's JSON is 445 KB, and the cap is 4 MB.
                return true;
            } else if (unpacked.json == null) {
                // Not something this can read, and a file it cannot read is
                // not a file it can accuse: rlottie refuses garbage by itself,
                // and it is a bomb that parses which this exists to stop.
                return false;
            }
            return lottieDangerous(unpacked.json);
        }
        return imageDangerous(file);
    }

    /** The animation, and whether it was cut off at {@link #MAX_UNPACKED_BYTES}. */
    private static final class Unpacked {
        static final Unpacked UNREADABLE = new Unpacked(null, false);
        static final Unpacked OVERFLOWED = new Unpacked(null, true);

        final String json;
        final boolean overflowed;

        Unpacked(String json, boolean overflowed) {
            this.json = json;
            this.overflowed = overflowed;
        }
    }

    private static boolean gzipped(File file) {
        try (InputStream stream = new FileInputStream(file)) {
            final int first = stream.read();
            final int second = stream.read();
            return first == 0x1F && second == 0x8B;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * The animation's JSON, however the file happens to hold it.
     *
     * <p>A .tgs on the wire is gzipped JSON, and this used to assume that the
     * file on disk is the same thing. It is not: Telegram caches an animated
     * sticker <b>already inflated</b>, so the file with the .tgs name is plain
     * JSON. The assumption made {@code GZIPInputStream} throw on every real
     * sticker, and "could not unpack" was being read as "dangerous" — which
     * hid whole packs behind "Краш-стикер (обезврежен)". Both shapes are read
     * now, and the one that never actually arrives here is the gzip.</p>
     */
    private static Unpacked unpack(File file) {
        final boolean gzip = gzipped(file);
        try (InputStream stream = gzip
                ? new GZIPInputStream(new FileInputStream(file))
                : new FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                if (out.size() + read > MAX_UNPACKED_BYTES) {
                    return Unpacked.OVERFLOWED;
                }
                out.write(buffer, 0, read);
            }
            final String json = out.toString("UTF-8");
            // Whatever it is, it has to look like the object rlottie will be
            // handed. Anything else is not judged rather than accused.
            return json.trim().startsWith("{")
                    ? new Unpacked(json, false)
                    : Unpacked.UNREADABLE;
        } catch (Throwable e) {
            return Unpacked.UNREADABLE;
        }
    }

    /**
     * A pass over the characters before any parser sees them. It answers how
     * deep the file goes without recursing itself, which is the whole point:
     * the parser that would answer the same question is the one protected.
     */
    private static boolean lottieDangerous(String json) {
        int depth = 0;
        int objects = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0, size = json.length(); i < size; i++) {
            final char ch = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                if (ch == '{' && ++objects > MAX_OBJECTS) {
                    return true;
                }
                if (++depth > MAX_DEPTH) {
                    return true;
                }
            } else if (ch == '}' || ch == ']') {
                if (--depth < 0) {
                    return true;
                }
            }
        }
        if (depth != 0 || inString) {
            return true;
        }
        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Throwable e) {
            return true;
        }
        final int width = root.optInt("w");
        final int height = root.optInt("h");
        if (width <= 0 || height <= 0 || tooBig(width, height)) {
            return true;
        }
        final double fps = root.optDouble("fr", 0.);
        if (!(fps > 0.) || fps > MAX_FPS) {
            return true;
        }
        final double frames = root.optDouble("op", 0.) - root.optDouble("ip", 0.);
        if (!(frames > 0.) || frames > MAX_FRAMES) {
            return true;
        }
        return walkDangerous(root, new int[1]);
    }

    /**
     * Recursion is safe here and only here: the scan above has already refused
     * anything deeper than {@link #MAX_DEPTH}, so this tree is at most that
     * tall.
     */
    private static boolean walkDangerous(Object value, int[] items) {
        if (++items[0] > MAX_ITEMS) {
            return true;
        }
        if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            if ("rp".equals(object.optString("ty", null))) {
                // A repeater with a fixed count says it in "c": {"a":0,"k":N}.
                // An animated count leaves k an array of keyframes and reads
                // as zero here - exotic enough to leave to the size limits
                // rather than teaching this to interpolate.
                final JSONObject copies = object.optJSONObject("c");
                if (copies != null && copies.optDouble("k", 0.) > MAX_REPEATER_COPIES) {
                    return true;
                }
            }
            for (Iterator<String> i = object.keys(); i.hasNext(); ) {
                if (walkDangerous(object.opt(i.next()), items)) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            for (int a = 0, size = array.length(); a < size; a++) {
                if (walkDangerous(array.opt(a), items)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Only the header is read, never the pixels. */
    private static boolean imageDangerous(File file) {
        final android.graphics.BitmapFactory.Options options =
                new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            // Not a picture this build can read at all, so nothing decodes it.
            return false;
        }
        return tooBig(options.outWidth, options.outHeight);
    }

    public static CharSequence placeholderText() {
        return LocaleController.getString(R.string.NovaCrashStickerDefused);
    }

    /**
     * The plate drawn in the place of the refused sticker. A fresh bitmap
     * every time on purpose: what is handed to an ImageReceiver ends up in the
     * bitmap cache, which recycles what it evicts, and a shared one would be
     * recycled out from under the next draw.
     */
    public static Bitmap placeholder() {
        final int side = 256;
        final Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(Color.argb(140, 0, 0, 0));
        final float inset = side / 16f;
        final float radius = side / 12f;
        canvas.drawRoundRect(
                new RectF(inset, inset, side - inset, side - inset),
                radius,
                radius,
                plate);

        final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.argb(230, 255, 255, 255));
        text.setTextSize(side / 9f);
        text.setTypeface(AndroidUtilities.bold());
        final CharSequence words = placeholderText();
        final int textWidth = (int) (side - 2 * inset - side / 6f);
        final StaticLayout layout = new StaticLayout(
                words,
                text,
                Math.max(textWidth, 1),
                Layout.Alignment.ALIGN_CENTER,
                1f,
                0f,
                false);
        canvas.save();
        canvas.translate(
                (side - textWidth) / 2f,
                (side - layout.getHeight()) / 2f);
        layout.draw(canvas);
        canvas.restore();
        return bitmap;
    }
}
