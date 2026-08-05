package org.telegram.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Permanent decoy shown after the emergency PIN destroyed the local data.
 *
 * <p>Nothing here touches the network or any Telegram component. The dialogs
 * are built in memory every launch, so there is no store an examiner could
 * date or recover, and messages typed into them never leave this activity.</p>
 *
 * <p>The conversations are deliberately dull. A decoy full of obviously staged
 * content is worse than none: it invites the very question it exists to avoid.</p>
 */
public final class NovaDecoyActivity extends Activity {
    private static final int BACKGROUND_COLOR = Color.rgb(255, 255, 255);
    private static final int HEADER_COLOR = Color.rgb(82, 136, 193);
    private static final int TEXT_COLOR = Color.rgb(17, 17, 17);
    private static final int SECONDARY_COLOR = Color.rgb(126, 137, 148);
    private static final int OUTGOING_COLOR = Color.rgb(226, 255, 199);
    private static final int INCOMING_COLOR = Color.rgb(240, 240, 240);

    private static final class Dialog {
        private final String title;
        private final String preview;
        private final String time;
        private final List<String[]> messages = new ArrayList<>();

        private Dialog(String title, String preview, String time) {
            this.title = title;
            this.preview = preview;
            this.time = time;
        }

        private Dialog message(String author, String text, String time) {
            messages.add(new String[] { author, text, time });
            return this;
        }
    }

    private final List<Dialog> dialogs = new ArrayList<>();
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(HEADER_COLOR);
        buildDialogs();
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND_COLOR);
        setContentView(root);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (root.getChildCount() > 0 && root.getChildAt(0).getTag() != null) {
            showList();
            return;
        }
        super.onBackPressed();
    }

    private void buildDialogs() {
        dialogs.add(new Dialog(getString(R.string.NovaDecoyName1),
                getString(R.string.NovaDecoyPreview1), "12:04")
                .message(getString(R.string.NovaDecoyName1),
                        getString(R.string.NovaDecoyLine1), "12:01")
                .message("", getString(R.string.NovaDecoyLine2), "12:03")
                .message(getString(R.string.NovaDecoyName1),
                        getString(R.string.NovaDecoyPreview1), "12:04"));
        dialogs.add(new Dialog(getString(R.string.NovaDecoyName2),
                getString(R.string.NovaDecoyPreview2), "09:41")
                .message(getString(R.string.NovaDecoyName2),
                        getString(R.string.NovaDecoyPreview2), "09:41"));
        dialogs.add(new Dialog(getString(R.string.NovaDecoyName3),
                getString(R.string.NovaDecoyPreview3), "Пн")
                .message("", getString(R.string.NovaDecoyLine3), "18:22")
                .message(getString(R.string.NovaDecoyName3),
                        getString(R.string.NovaDecoyPreview3), "18:30"));
    }

    private void showList() {
        root.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(header(getString(R.string.AppName), false));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final Dialog dialog : dialogs) {
            list.addView(dialogRow(dialog));
        }
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(column);
    }

    private View dialogRow(final Dialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> showChat(dialog));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(dialog.title, 16, TEXT_COLOR, Typeface.BOLD));
        texts.addView(label(dialog.preview, 15, SECONDARY_COLOR, Typeface.NORMAL));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, grow);
        row.addView(label(dialog.time, 13, SECONDARY_COLOR, Typeface.NORMAL));
        return row;
    }

    private void showChat(final Dialog dialog) {
        root.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setTag("chat");
        column.addView(header(dialog.title, true));

        ScrollView scroll = new ScrollView(this);
        final LinearLayout messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(12), dp(12), dp(12), dp(12));
        for (String[] message : dialog.messages) {
            messages.addView(bubble(message[1], message[0].isEmpty(), message[2]));
        }
        scroll.addView(messages);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setPadding(dp(12), dp(8), dp(12), dp(8));
        final EditText input = new EditText(this);
        input.setHint(R.string.NovaDecoyHint);
        input.setTextColor(TEXT_COLOR);
        input.setBackground(null);
        composer.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = label(getString(R.string.NovaDecoySend), 15, HEADER_COLOR, Typeface.BOLD);
        send.setPadding(dp(12), dp(8), 0, dp(8));
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            // Stays in this view only. One tick, because nothing was ever sent
            // and a second one would be a lie the screen tells for us.
            dialog.messages.add(new String[] { "", text, time() });
            messages.addView(bubble(text, true, time()));
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        composer.addView(send);
        column.addView(composer);
        root.addView(column);
    }

    private View bubble(String text, boolean outgoing, String time) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.HORIZONTAL);
        holder.setGravity(outgoing ? Gravity.END : Gravity.START);
        holder.setPadding(0, dp(4), 0, dp(4));

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundColor(outgoing ? OUTGOING_COLOR : INCOMING_COLOR);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.addView(label(text, 16, TEXT_COLOR, Typeface.NORMAL));
        bubble.addView(label(outgoing ? time + " ✓" : time, 12, SECONDARY_COLOR, Typeface.NORMAL));
        holder.addView(bubble);
        return holder;
    }

    private View header(String title, boolean back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(HEADER_COLOR);
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (back) {
            TextView arrow = label("←", 20, Color.WHITE, Typeface.BOLD);
            arrow.setPadding(0, 0, dp(16), 0);
            arrow.setOnClickListener(v -> showList());
            bar.addView(arrow);
        }
        bar.addView(label(title, 19, Color.WHITE, Typeface.BOLD));
        return bar;
    }

    private TextView label(String text, int sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        return view;
    }

    private String time() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        return String.format(
                java.util.Locale.US,
                "%02d:%02d",
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE));
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
