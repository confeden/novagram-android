package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.NovaIconDesign;
import org.telegram.ui.ActionBar.Theme;

import java.util.HashMap;
import java.util.Map;

/**
 * The icon picker: the mark on top, and under it one strip per axis — the
 * chosen variant in the middle at full size, its neighbours smaller on either
 * side, and a tap on any of them chooses it.
 *
 * <p>Arrows moved one step at a time and showed nothing of what was coming. A
 * strip shows where you are in the row, which is what choosing a look actually
 * needs. Every strip draws the other two axes as they stand, so changing the
 * style changes what the texture strip is showing — the whole reason these are
 * pictures and not names.</p>
 *
 * <p>Nothing here is stored: the draft lives in this view until the row below
 * the picker is pressed. What "apply" means differs between the platforms, and
 * on Android it means offering a shortcut, which is the settings list's
 * business and not the picker's.</p>
 */
public class NovaIconPicker extends LinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final ImageView preview;
    private final Strip[] strips = new Strip[3];
    private NovaIconDesign.Design draft;
    private Runnable onChanged;

    public NovaIconPicker(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            Runnable onChanged) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.onChanged = onChanged;
        this.draft = NovaIconDesign.current();

        setOrientation(VERTICAL);

        FrameLayout previewWrap = new FrameLayout(context);
        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewWrap.addView(preview, LayoutHelper.createFrame(
                112, 112, Gravity.CENTER, 0, 12, 0, 8));
        addView(previewWrap, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        strips[0] = addStrip(NovaIconDesign.STYLES,
                index -> draft.withStyle(index),
                index -> NovaIconDesign.styleName(index),
                index -> chose(draft.withStyle(index)));
        strips[1] = addStrip(NovaIconDesign.TEXTURES,
                index -> draft.withTexture(index),
                index -> NovaIconDesign.textureName(index),
                index -> chose(draft.withTexture(index)));
        strips[2] = addStrip(NovaIconDesign.ACCENTS,
                index -> draft.withAccent(index),
                index -> NovaIconDesign.accentName(index),
                index -> chose(draft.withAccent(index)));
        strips[0].setCurrent(draft.style);
        strips[1].setCurrent(draft.texture);
        strips[2].setCurrent(draft.accent);
        redraw();
    }

    public NovaIconDesign.Design getDraft() {
        return draft;
    }

    /**
     * Told after every choice, so that whoever owns the draft keeps it rather
     * than this view. The view is recycled; the draft must not be.
     */
    public void setOnChanged(Runnable listener) {
        onChanged = listener;
    }

    /**
     * Shows a draft that was made elsewhere - the one the owner is holding
     * while this view was away being recycled. Silent: it is not a choice, and
     * telling the owner what the owner just said would be a loop.
     */
    public void setDraft(NovaIconDesign.Design design) {
        if (design == null || design.equals(draft)) {
            return;
        }
        draft = design;
        strips[0].setCurrent(draft.style);
        strips[1].setCurrent(draft.texture);
        strips[2].setCurrent(draft.accent);
        redraw();
    }

    private void chose(NovaIconDesign.Design design) {
        draft = design;
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private interface Variant {
        NovaIconDesign.Design at(int index);
    }

    private interface Name {
        CharSequence at(int index);
    }

    private interface Chosen {
        void at(int index);
    }

    private Strip addStrip(int count, Variant variant, Name name, Chosen chosen) {
        Strip strip = new Strip(getContext(), count, variant, name, index -> {
            chosen.at(index);
            redraw();
        });
        addView(strip, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 96));
        return strip;
    }

    private void redraw() {
        preview.setImageBitmap(
                NovaIconDesign.render(draft, AndroidUtilities.dp(112), false));
        for (Strip strip : strips) {
            if (strip != null) {
                strip.refresh();
            }
        }
    }

    /** One axis, drawn as a row of marks centred on the chosen one. */
    private class Strip extends View {
        private static final int SIDE_COUNT = 3;

        private final int count;
        private final Variant variant;
        private final Name name;
        private final Chosen chosen;
        private final Map<Integer, Bitmap> cache = new HashMap<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int current;

        Strip(Context context, int count, Variant variant, Name name, Chosen chosen) {
            super(context);
            this.count = count;
            this.variant = variant;
            this.name = name;
            this.chosen = chosen;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(AndroidUtilities.dp(2));
            text.setTextSize(AndroidUtilities.dp(13));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(AndroidUtilities.bold());
        }

        void setCurrent(int index) {
            current = NovaIconDesign.wrap(index, count);
            cache.clear();
            invalidate();
        }

        void refresh() {
            cache.clear();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int large = AndroidUtilities.dp(56);
            final int small = AndroidUtilities.dp(38);
            final int step = AndroidUtilities.dp(50);
            final int center = getWidth() / 2;
            final int middle = (getHeight() - AndroidUtilities.dp(22)) / 2;
            ring.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            for (int at = -SIDE_COUNT; at <= SIDE_COUNT; at++) {
                final int index = NovaIconDesign.wrap(current + at, count);
                final int side = (at == 0) ? large : small;
                final int key = index * 2 + ((at == 0) ? 1 : 0);
                Bitmap bitmap = cache.get(key);
                if (bitmap == null) {
                    bitmap = NovaIconDesign.render(variant.at(index), side, false);
                    cache.put(key, bitmap);
                }
                final int x = center + at * step - side / 2;
                final int y = middle - side / 2;
                if (at == 0) {
                    canvas.drawCircle(center, middle,
                            side / 2f + AndroidUtilities.dp(4), ring);
                }
                paint.setAlpha(at == 0 ? 255 : Math.max(70, 255 - 60 * Math.abs(at)));
                canvas.drawBitmap(bitmap, null,
                        new Rect(x, y, x + side, y + side), paint);
            }
            text.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            canvas.drawText(name.at(current).toString(), center,
                    getHeight() - AndroidUtilities.dp(6), text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return true;
            }
            final int step = AndroidUtilities.dp(50);
            final int dx = (int) event.getX() - getWidth() / 2;
            final int moved = (dx > step / 2)
                    ? ((dx + step / 2) / step)
                    : (dx < -step / 2)
                    ? -((-dx + step / 2) / step)
                    : 0;
            if (moved != 0) {
                setCurrent(current + moved);
                chosen.at(current);
            }
            return true;
        }
    }
}
