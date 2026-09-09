package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.NovaIconDesign;
import org.telegram.ui.ActionBar.Theme;

/**
 * The icon picker: the mark on top, three rows under it, each walked left and
 * right with an arrow.
 *
 * <p>Everything here is a draft — the mark redraws on every step so the user
 * sees the combination before choosing it, and nothing is stored until the row
 * below the picker is pressed. That row belongs to the settings list, not to
 * this view: what "apply" means differs between the platforms, and on Android
 * it means offering a shortcut, which is the list's business and not the
 * picker's.</p>
 */
public class NovaIconPicker extends LinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final ImageView preview;
    private final Runnable onChanged;
    private NovaIconDesign.Design draft;

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
                108, 108, Gravity.CENTER, 0, 12, 0, 12));
        addView(previewWrap, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addRow(LocaleController.getString(R.string.NovaIconStyle),
                () -> NovaIconDesign.styleName(draft.style),
                delta -> draft = draft.withStyle(draft.style + delta));
        addRow(LocaleController.getString(R.string.NovaIconTexture),
                () -> NovaIconDesign.textureName(draft.texture),
                delta -> draft = draft.withTexture(draft.texture + delta));
        addRow(LocaleController.getString(R.string.NovaIconAccent),
                () -> NovaIconDesign.accentName(draft.accent),
                delta -> draft = draft.withAccent(draft.accent + delta));

        redraw();
    }

    public NovaIconDesign.Design getDraft() {
        return draft;
    }

    private interface Value {
        CharSequence get();
    }

    private interface Shift {
        void by(int delta);
    }

    private void addRow(CharSequence title, Value value, Shift shift) {
        final Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        name.setText(title);
        row.addView(name, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 21, 0, 8, 0));

        TextView current = new TextView(context);
        current.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        current.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider));
        current.setGravity(Gravity.CENTER);
        current.setText(value.get());

        row.addView(arrow(context, "‹", () -> {
            shift.by(-1);
            current.setText(value.get());
            redraw();
        }));
        row.addView(current, LayoutHelper.createLinear(
                120, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.addView(arrow(context, "›", () -> {
            shift.by(1);
            current.setText(value.get());
            redraw();
        }));

        addView(row, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, 0, 13, 0));
    }

    private View arrow(Context context, String glyph, Runnable action) {
        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
        button.setGravity(Gravity.CENTER);
        button.setText(glyph);
        button.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider), 1));
        button.setOnClickListener(v -> action.run());
        button.setLayoutParams(LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        return button;
    }

    private void redraw() {
        final int side = AndroidUtilities.dp(108);
        preview.setImageBitmap(NovaIconDesign.render(draft, side, false));
        if (onChanged != null) {
            onChanged.run();
        }
    }
}
