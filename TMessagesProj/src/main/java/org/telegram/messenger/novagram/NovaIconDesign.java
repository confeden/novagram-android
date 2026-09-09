package org.telegram.messenger.novagram;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.privacy.NovaDecoyState;

/**
 * The application's own mark, drawn rather than shipped as a picture.
 *
 * <p>Three axes — style, texture, accent — and the whole point of drawing it
 * is that their product, 3840 looks, could never be shipped as files. The mark
 * itself is the same in all of them: a paper plane in two facets, laid out in
 * a unit box and scaled into whatever size is asked for.</p>
 *
 * <p>The 32 accents are sixteen hues in a deep and a bright tone, and each was
 * picked so that whichever of white or ink the plane is drawn in contrasts
 * with the plate by at least 5.7:1 — the colour is the user's choice, the
 * readability is not. This is the twin of the desktop
 * {@code NovaGram::IconDesign}: same axes, same numbers, same drawing.</p>
 *
 * <p><b>What Android allows.</b> An application cannot replace its own
 * launcher icon with a drawing: {@code android:icon} points at a resource
 * inside the signed package, and the launcher reads it from the package
 * manager. The only icon a drawing can reach is a pinned shortcut, which the
 * system adds <i>beside</i> the application's own and only with the user's
 * consent. So on this platform the picker changes what the application draws
 * of itself, and offers to put the chosen mark on the home screen as a
 * shortcut — said plainly, rather than pretending the launcher icon changed.
 * </p>
 */
public final class NovaIconDesign {

    public static final int STYLES = 10;
    public static final int TEXTURES = 12;
    public static final int ACCENTS = 32;

    /** Style 0, texture 0 and this accent are the stock look. */
    public static final int DEFAULT_ACCENT = 18;

    private static final String PREFS = "novagram_icon";
    private static final String KEY_STYLE = "style";
    private static final String KEY_TEXTURE = "texture";
    private static final String KEY_ACCENT = "accent";

    private static final int[] ACCENT_COLORS = {
        0xC22525, 0xF39E9E, 0x9F4F1E, 0xEEA377, 0x7A6217, 0xDFAE1B, 0x606B14,
        0xABC018, 0x437015, 0x72CB19, 0x227516, 0x31D01A, 0x16752E, 0x1AD047,
        0x167350, 0x19CD8A, 0x157171, 0x19C8C9, 0x1D6A99, 0x6EBEED, 0x315BD8,
        0x9EB3F3, 0x5F4EDD, 0xB6ACF5, 0x8838D9, 0xCCA5F4, 0xA823BA, 0xE798F2,
        0xB52390, 0xF297DB, 0xBE245E, 0xF39ABC,
    };

    private static final int INK = 0x12141A;

    private NovaIconDesign() {
    }

    /** The three axes together; immutable, and cheap enough to pass around. */
    public static final class Design {
        public final int style;
        public final int texture;
        public final int accent;

        public Design(int style, int texture, int accent) {
            this.style = wrap(style, STYLES);
            this.texture = wrap(texture, TEXTURES);
            this.accent = wrap(accent, ACCENTS);
        }

        public Design withStyle(int value) {
            return new Design(value, texture, accent);
        }

        public Design withTexture(int value) {
            return new Design(style, value, accent);
        }

        public Design withAccent(int value) {
            return new Design(style, texture, value);
        }

        public boolean isOriginal() {
            return style == 0 && texture == 0 && accent == DEFAULT_ACCENT;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Design)) {
                return false;
            }
            Design d = (Design) other;
            return d.style == style && d.texture == texture && d.accent == accent;
        }

        @Override
        public int hashCode() {
            return (style * 31 + texture) * 31 + accent;
        }
    }

    public static int wrap(int value, int count) {
        return ((value % count) + count) % count;
    }

    private static SharedPreferences prefs() {
        final Context context = ApplicationLoader.applicationContext;
        return context == null
                ? null
                : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Design current() {
        if (NovaDecoyState.isActive()) {
            // The decoy has to look like plain Telegram, and an icon nobody
            // else has is a sign of the fork (I4).
            return new Design(0, 0, DEFAULT_ACCENT);
        }
        final SharedPreferences p = prefs();
        if (p == null) {
            return new Design(0, 0, DEFAULT_ACCENT);
        }
        return new Design(
                p.getInt(KEY_STYLE, 0),
                p.getInt(KEY_TEXTURE, 0),
                p.getInt(KEY_ACCENT, DEFAULT_ACCENT));
    }

    public static void setCurrent(Design design) {
        final SharedPreferences p = prefs();
        if (p == null || design == null) {
            return;
        }
        p.edit()
                .putInt(KEY_STYLE, design.style)
                .putInt(KEY_TEXTURE, design.texture)
                .putInt(KEY_ACCENT, design.accent)
                .apply();
    }

    public static int accentColor(int accent) {
        return 0xFF000000 | ACCENT_COLORS[wrap(accent, ACCENTS)];
    }

    // ---------------------------------------------------------------- colour

    private static int shade(int value, float k) {
        final float target = k > 0 ? 255f : 0f;
        final float amount = Math.abs(k);
        return (mix(value, 16, target, amount) << 16)
                | (mix(value, 8, target, amount) << 8)
                | mix(value, 0, target, amount);
    }

    private static int mix(int value, int shift, float target, float amount) {
        final float channel = (value >> shift) & 0xFF;
        return Math.round(channel + (target - channel) * amount);
    }

    private static float luminance(int value) {
        return 0.2126f * channel(value, 16)
                + 0.7152f * channel(value, 8)
                + 0.0722f * channel(value, 0);
    }

    private static float channel(int value, int shift) {
        final float c = ((value >> shift) & 0xFF) / 255f;
        return c <= 0.03928f
                ? c / 12.92f
                : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private static float contrast(float a, float b) {
        return (Math.max(a, b) + 0.05f) / (Math.min(a, b) + 0.05f);
    }

    /** White or ink, whichever the plate lets be read. Never a matter of taste. */
    private static int planeColor(int plate) {
        final float lum = luminance(plate);
        return contrast(lum, 1f) >= contrast(lum, luminance(INK))
                ? 0xFFFFFF
                : INK;
    }

    private static int blend(int a, int b, float k) {
        return (blendChannel(a, b, 16, k) << 16)
                | (blendChannel(a, b, 8, k) << 8)
                | blendChannel(a, b, 0, k);
    }

    private static int blendChannel(int a, int b, int shift, float k) {
        final float from = (a >> shift) & 0xFF;
        final float to = (b >> shift) & 0xFF;
        return Math.round(from + (to - from) * k);
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    private static int alpha(int rgb, int a) {
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    // ----------------------------------------------------------------- shape

    private static Path platePath(int style, RectF box) {
        final Path path = new Path();
        final float side = Math.min(box.width(), box.height());
        if (style == 1 || style == 8) {
            path.addRoundRect(box, side * 0.28f, side * 0.28f, Path.Direction.CW);
        } else if (style == 5) {
            final float inset = side * 0.04f;
            path.addOval(new RectF(
                    box.left + inset, box.top + inset,
                    box.right - inset, box.bottom - inset), Path.Direction.CW);
        } else if (style == 9) {
            final float cx = box.centerX(), cy = box.centerY(), r = side / 2f;
            for (int i = 0; i < 6; i++) {
                final double a = Math.PI / 6 + i * Math.PI / 3;
                final float x = cx + r * (float) Math.cos(a);
                final float y = cy + r * (float) Math.sin(a);
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            path.close();
        } else if (style == 2 || style == 7) {
            path.addRoundRect(box, side * 0.16f, side * 0.16f, Path.Direction.CW);
        } else {
            path.addOval(box, Path.Direction.CW);
        }
        return path;
    }

    private static void paintPlate(
            Canvas canvas, int style, int accent, int second, RectF box, Path plate) {
        final float side = Math.min(box.width(), box.height());
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (style == 0) {
            paint.setShader(new LinearGradient(box.left, box.top, box.left, box.bottom,
                    opaque(shade(accent, 0.18f)), opaque(shade(accent, -0.14f)),
                    Shader.TileMode.CLAMP));
            canvas.drawPath(plate, paint);
        } else if (style == 1) {
            paint.setColor(opaque(0x0B0D12));
            canvas.drawPath(plate, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(alpha(accent, 90));
            paint.setStrokeWidth(side * 0.10f);
            canvas.drawPath(plate, paint);
            paint.setColor(opaque(accent));
            paint.setStrokeWidth(side * 0.045f);
            canvas.drawPath(plate, paint);
        } else if (style == 2) {
            paint.setColor(opaque(shade(accent, 0.62f)));
            canvas.drawPath(plate, paint);
            canvas.save();
            canvas.clipPath(plate);
            for (int i = 0; i < 4; i++) {
                paint.setColor(alpha(shade(accent, -0.10f - i * 0.06f), 140));
                canvas.drawRect(box.left, box.top + side * (0.58f + i * 0.11f),
                        box.right, box.top + side * (0.66f + i * 0.11f), paint);
            }
            canvas.restore();
        } else if (style == 3) {
            paint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                    opaque(shade(accent, 0.30f)), opaque(shade(accent, -0.32f)),
                    Shader.TileMode.CLAMP));
            canvas.drawPath(plate, paint);
            paint.setShader(null);
            canvas.save();
            canvas.clipPath(plate);
            paint.setShader(new RadialGradient(
                    box.left + side * 0.30f, box.top + side * 0.22f, side * 0.75f,
                    new int[] { 0x96FFFFFF, 0x1AFFFFFF, 0x00FFFFFF },
                    new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawRect(box, paint);
            canvas.restore();
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(side * 0.016f);
            paint.setColor(0x8CFFFFFF);
            canvas.drawPath(plate, paint);
        } else if (style == 4) {
            paint.setColor(opaque(accent));
            canvas.drawPath(plate, paint);
        } else if (style == 5) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(side * 0.075f);
            paint.setColor(opaque(accent));
            canvas.drawPath(plate, paint);
        } else if (style == 6) {
            paint.setShader(new LinearGradient(box.left, box.bottom, box.right, box.top,
                    new int[] {
                        opaque(shade(accent, -0.35f)),
                        opaque(accent),
                        opaque(shade(second, 0.10f)) },
                    new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawPath(plate, paint);
        } else if (style == 7) {
            paint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                    new int[] {
                        opaque(shade(accent, 0.55f)),
                        opaque(shade(accent, -0.05f)),
                        opaque(shade(accent, 0.35f)),
                        opaque(shade(accent, -0.32f)),
                        opaque(shade(accent, 0.25f)) },
                    new float[] { 0f, 0.38f, 0.5f, 0.62f, 1f }, Shader.TileMode.CLAMP));
            canvas.drawPath(plate, paint);
        } else if (style == 8) {
            paint.setColor(opaque(0x06070A));
            canvas.drawPath(plate, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(side * 0.02f);
            paint.setColor(alpha(accent, 200));
            canvas.drawPath(plate, paint);
        } else if (style == 9) {
            paint.setColor(opaque(accent));
            canvas.drawPath(plate, paint);
            canvas.save();
            canvas.clipPath(plate);
            final Path fold = new Path();
            fold.moveTo(box.left, box.bottom);
            fold.lineTo(box.right, box.top + side * 0.25f);
            fold.lineTo(box.right, box.bottom);
            fold.close();
            paint.setColor(0x33000000);
            canvas.drawPath(fold, paint);
            canvas.restore();
        }
    }

    private static void paintTexture(
            Canvas canvas, int texture, int style, int accent, RectF box, Path plate) {
        if (texture == 0) {
            return;
        }
        final boolean dark = (style == 1 || style == 8);
        final int ink = dark ? alpha(accent, 51) : 0x29FFFFFF;
        final int ink2 = dark ? alpha(accent, 31) : 0x1A000000;
        final float side = Math.min(box.width(), box.height());
        final float u = side / 16f;
        final float x0 = box.left, y0 = box.top;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(ink);
        canvas.save();
        canvas.clipPath(plate);
        switch (texture) {
            case 1: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.012f, 1f));
                final float[][] seed = {
                    { 3, 4, 2.4f }, { 9, 3, 1.5f }, { 12, 7, 2.8f }, { 5, 9, 1.9f },
                    { 10, 12, 2.2f }, { 2, 12, 1.3f }, { 14, 11, 1.2f }, { 7, 6, 1f } };
                for (float[] c : seed) {
                    canvas.drawCircle(x0 + c[0] * u, y0 + c[1] * u, c[2] * u, paint);
                }
                break;
            }
            case 2: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.02f, 1f));
                for (int i = -16; i < 32; i += 2) {
                    canvas.drawLine(x0 + i * u, y0, x0 + (i + 16) * u, y0 + 16 * u, paint);
                }
                paint.setColor(ink2);
                for (int i = -16; i < 32; i += 2) {
                    canvas.drawLine(x0 + i * u, y0 + 16 * u, x0 + (i + 16) * u, y0, paint);
                }
                break;
            }
            case 3: {
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 220; i++) {
                    final float x = fract((float) Math.sin(i * 12.9898) * 43758.5453f);
                    final float y = fract((float) Math.sin(i * 78.233) * 12345.6789f);
                    canvas.drawRect(x0 + x * side, y0 + y * side,
                            x0 + x * side + side * 0.014f,
                            y0 + y * side + side * 0.014f, paint);
                }
                break;
            }
            case 4: {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ink2);
                for (int y = 0; y < 16; y += 2) {
                    for (int x = 0; x < 16; x += 2) {
                        final int shift = (y % 4) != 0 ? 1 : 0;
                        canvas.drawRect(x0 + (x + shift) * u, y0 + y * u,
                                x0 + (x + shift + 1) * u, y0 + (y + 1) * u, paint);
                    }
                }
                break;
            }
            case 5: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(side * 0.05f);
                for (int i = -16; i < 32; i += 3) {
                    canvas.drawLine(x0 + i * u, y0, x0 + (i + 16) * u, y0 + 16 * u, paint);
                }
                break;
            }
            case 6: {
                paint.setStyle(Paint.Style.FILL);
                for (int y = 1; y < 16; y += 2) {
                    for (int x = 1; x < 16; x += 2) {
                        final float r = (0.18f + 0.34f * (x / 16f)) * u * 1.6f;
                        canvas.drawCircle(x0 + x * u, y0 + y * u, r, paint);
                    }
                }
                break;
            }
            case 7: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.012f, 1f));
                for (int i = 2; i < 16; i += 3) {
                    canvas.drawLine(x0 + i * u, y0, x0 + i * u, y0 + 16 * u, paint);
                    canvas.drawLine(x0, y0 + i * u, x0 + 16 * u, y0 + i * u, paint);
                }
                break;
            }
            case 8: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.016f, 1f));
                for (int y = 1; y < 17; y += 3) {
                    final Path path = new Path();
                    for (int x = 0; x <= 16; x++) {
                        final float yy = y0 + (y + (float) Math.sin(x * 0.9) * 0.8f) * u;
                        if (x == 0) {
                            path.moveTo(x0, yy);
                        } else {
                            path.lineTo(x0 + x * u, yy);
                        }
                    }
                    canvas.drawPath(path, paint);
                }
                break;
            }
            case 9: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.012f, 1f));
                final float r = u * 1.7f;
                for (int row = -1; row < 7; row++) {
                    for (int col = -1; col < 7; col++) {
                        final float cx = col * r * 1.73f + ((row % 2) != 0 ? r * 0.87f : 0f);
                        final float cy = row * r * 1.5f;
                        final Path path = new Path();
                        for (int i = 0; i < 6; i++) {
                            final double a = Math.PI / 6 + i * Math.PI / 3;
                            final float x = x0 + cx + r * (float) Math.cos(a);
                            final float y = y0 + cy + r * (float) Math.sin(a);
                            if (i == 0) {
                                path.moveTo(x, y);
                            } else {
                                path.lineTo(x, y);
                            }
                        }
                        path.close();
                        canvas.drawPath(path, paint);
                    }
                }
                break;
            }
            case 10: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.016f, 1f));
                final Path path = new Path();
                path.moveTo(x0 + 2 * u, y0 + 3 * u);
                path.lineTo(x0 + 7 * u, y0 + 3 * u);
                path.lineTo(x0 + 7 * u, y0 + 8 * u);
                path.lineTo(x0 + 13 * u, y0 + 8 * u);
                path.moveTo(x0 + 4 * u, y0 + 12 * u);
                path.lineTo(x0 + 11 * u, y0 + 12 * u);
                path.lineTo(x0 + 11 * u, y0 + 5 * u);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                final float[][] pads = {
                    { 2, 3 }, { 7, 3 }, { 7, 8 }, { 13, 8 }, { 4, 12 }, { 11, 12 }, { 11, 5 } };
                for (float[] pad : pads) {
                    canvas.drawCircle(x0 + pad[0] * u, y0 + pad[1] * u, u * 0.32f, paint);
                }
                break;
            }
            default: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(side * 0.014f, 1f));
                for (int row = 0; row < 8; row++) {
                    for (int col = -1; col < 8; col++) {
                        final float cx = (col * 2 + ((row % 2) != 0 ? 1 : 0)) * u;
                        final float cy = row * 1.6f * u;
                        final float r = u * 1.15f;
                        canvas.drawArc(new RectF(x0 + cx - r, y0 + cy - r,
                                x0 + cx + r, y0 + cy + r), 0f, 180f, false, paint);
                    }
                }
                break;
            }
        }
        canvas.restore();
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }

    private static void paintPlane(Canvas canvas, int style, int accent, RectF box) {
        final float side = Math.min(box.width(), box.height());
        final float inner = side * 0.58f;
        final float left = box.left + (box.width() - inner) / 2f;
        final float top = box.top + (box.height() - inner) / 2f;

        final Path wing = new Path();
        wing.moveTo(left + 0.02f * inner, top + 0.52f * inner);
        wing.lineTo(left + 0.98f * inner, top + 0.04f * inner);
        wing.lineTo(left + 0.42f * inner, top + 0.66f * inner);
        wing.close();
        final Path fold = new Path();
        fold.moveTo(left + 0.42f * inner, top + 0.66f * inner);
        fold.lineTo(left + 0.98f * inner, top + 0.04f * inner);
        fold.lineTo(left + 0.58f * inner, top + 0.98f * inner);
        fold.close();

        int plate = accent;
        int main = planeColor(accent);
        if (style == 1 || style == 8) {
            main = accent;
            plate = 0x0B0D12;
        } else if (style == 5) {
            main = accent;
            plate = 0x1B1D21;
        } else if (style == 2) {
            main = 0x2A2622;
            plate = shade(accent, 0.62f);
        }

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (style == 4) {
            // Cutout takes the mark out of the plate instead of drawing it on
            // top, so whatever is behind the icon shows through it.
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            paint.setColor(0xFF000000);
            canvas.drawPath(wing, paint);
            paint.setColor(0x8C000000);
            canvas.drawPath(fold, paint);
            paint.setXfermode(null);
        } else {
            paint.setColor(opaque(main));
            canvas.drawPath(wing, paint);
            paint.setColor(opaque(blend(main, plate, 0.42f)));
            canvas.drawPath(fold, paint);
        }
    }

    /** The mark at any size. {@code margin} leaves the transparent border. */
    public static Bitmap render(Design design, int size, boolean margin) {
        if (size < 1) {
            size = 1;
        }
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final float inset = size * (margin ? 0.055f : 0.01f);
        final RectF box = new RectF(inset, inset, size - inset, size - inset);
        final int accent = ACCENT_COLORS[design.accent];
        final int second = ACCENT_COLORS[wrap(design.accent + 9, ACCENTS)];
        final Path plate = platePath(design.style, box);
        paintPlate(canvas, design.style, accent, second, box, plate);
        paintTexture(canvas, design.texture, design.style, accent, box, plate);
        paintPlane(canvas, design.style, accent, box);
        return bitmap;
    }

    // ------------------------------------------------------------------ text

    public static CharSequence sectionTitle() {
        return LocaleController.getString(R.string.NovaIconHeader);
    }

    public static CharSequence sectionAbout() {
        return LocaleController.getString(R.string.NovaIconInfo);
    }

    public static CharSequence styleName(int style) {
        return name(R.array.NovaIconStyles, style, STYLES);
    }

    public static CharSequence textureName(int texture) {
        return name(R.array.NovaIconTextures, texture, TEXTURES);
    }

    public static CharSequence accentName(int accent) {
        return (wrap(accent, ACCENTS) + 1) + " / " + ACCENTS;
    }

    private static CharSequence name(int arrayId, int index, int count) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return String.valueOf(index + 1);
        }
        final String[] names = context.getResources().getStringArray(arrayId);
        final int at = wrap(index, count);
        return at < names.length ? names[at] : String.valueOf(at + 1);
    }
}
