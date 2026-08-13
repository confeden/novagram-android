package org.telegram.messenger.novagram.update;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActionDrawable;
import org.telegram.ui.Components.RadialProgress2;
import org.telegram.ui.IUpdateLayout;

/**
 * The update bar at the bottom of the chat list.
 *
 * <p>Built to the shape of the official one that ships with the standalone
 * Telegram build ({@code TMessagesProj_AppStandalone/.../UpdateLayout.java}):
 * the same 44dp strip, the same {@link RadialProgress2} icon that turns from a
 * download arrow into a progress ring and then into an install arrow, the same
 * 180ms slide. Only the source of the state is different — upstream reads
 * {@code SharedConfig.pendingAppUpdate}, which is filled by the Telegram
 * servers and is always null here, so this one reads {@link NovaUpdateChecker}
 * instead.</p>
 *
 * <p>Everything around the bar comes for free: {@code UpdateLayoutWrapper}
 * draws the background and the strip under the navigation bar, and asks for a
 * new inset pass whenever the bar appears or goes away, which is what lifts the
 * tab bar above it.</p>
 */
public final class NovaUpdateLayout extends IUpdateLayout {

    private final Activity activity;
    private final ViewGroup sideMenuContainer;

    private FrameLayout updateLayout;
    private RadialProgress2 updateLayoutIcon;
    private AnimatedTextView updateTextView;
    private AnimatedTextView.AnimatedTextDrawable updateSizeTextView;

    private int account;

    private final NovaUpdateChecker.Listener listener = () -> updateAppUpdateViews(account, true);

    public NovaUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        super(activity, sideMenuContainer);
        this.activity = activity;
        this.sideMenuContainer = sideMenuContainer;
        if (sideMenuContainer == null) {
            return;
        }
        // The checker is a process-wide singleton with a static listener list,
        // and this object belongs to one MainTabsActivity, which is recreated
        // on every account switch. Tying the subscription to the wrapper being
        // on screen is what keeps dead layouts out of that list.
        sideMenuContainer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                NovaUpdateChecker.addListener(listener);
                updateAppUpdateViews(account, false);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
                NovaUpdateChecker.removeListener(listener);
            }
        });
        if (sideMenuContainer.isAttachedToWindow()) {
            NovaUpdateChecker.addListener(listener);
        }
    }

    /**
     * Upstream feeds progress in from the file loader. This checker downloads
     * over its own connection and reports through the listener, so there is
     * nothing to do here; the method stays because the base class declares it.
     */
    @Override
    public void updateFileProgress(Object[] args) {
    }

    @Override
    public void createUpdateUI(int currentAccount) {
        account = currentAccount;
        if (sideMenuContainer == null || updateLayout != null) {
            return;
        }
        updateLayout = new FrameLayout(activity);
        updateLayout.setVisibility(View.INVISIBLE);
        updateLayout.setTranslationY(dp(44));
        updateLayout.setBackground(Theme.getSelectorDrawable(0x40ffffff, false));
        sideMenuContainer.addView(updateLayout, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                44,
                Gravity.LEFT | Gravity.BOTTOM));
        updateLayout.setOnClickListener(v -> {
            switch (NovaUpdateChecker.getState()) {
                case FOUND:
                case FAILED:
                    NovaUpdateChecker.download();
                    break;
                case DOWNLOADING:
                    NovaUpdateChecker.cancel();
                    break;
                case READY:
                    NovaUpdateChecker.install(activity);
                    break;
                default:
                    break;
            }
            updateAppUpdateViews(account, true);
        });

        updateTextView = new AnimatedTextView(activity, true, true, true) {
            @Override
            protected void onDraw(Canvas canvas) {
                updateSizeTextView.setBounds(0, 0, getMeasuredWidth() - dp(20), getMeasuredHeight());
                updateSizeTextView.draw(canvas);

                canvas.save();
                canvas.translate(dp(15), 0);
                super.onDraw(canvas);
                canvas.translate((getMeasuredWidth() - width()) / 2f - dp(30), dp(11));
                updateLayoutIcon.draw(canvas);
                canvas.restore();
            }

            @Override
            protected boolean verifyDrawable(@NonNull Drawable who) {
                return super.verifyDrawable(who) || who == updateSizeTextView;
            }
        };
        updateTextView.setTextSize(dp(15));
        updateTextView.setTypeface(AndroidUtilities.bold());
        updateTextView.setTextColor(0xffffffff);
        updateTextView.setGravity(Gravity.CENTER);
        updateLayout.addView(updateTextView, LayoutHelper.createFrameMatchParent());
        updateTextView.setText(LocaleController.getString(R.string.NovaUpdateBarUpdate), false);

        updateLayoutIcon = new RadialProgress2(updateTextView);
        updateLayoutIcon.setColors(
                0xffffffff,
                0xffffffff,
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButton));
        updateLayoutIcon.setProgressRect(0, 0, dp(22), dp(22));
        updateLayoutIcon.setCircleRadius(dp(11));
        updateLayoutIcon.setAsMini();

        updateSizeTextView = new AnimatedTextView.AnimatedTextDrawable(true, true, true);
        updateSizeTextView.setCallback(updateTextView);
        updateSizeTextView.setTextSize(dp(14));
        updateSizeTextView.setTypeface(AndroidUtilities.bold());
        updateSizeTextView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        updateSizeTextView.setTextColor(0xccffffff);
    }

    @Override
    public void updateAppUpdateViews(int currentAccount, boolean animated) {
        account = currentAccount;
        if (sideMenuContainer == null) {
            return;
        }
        if (!isOnMainThread()) {
            AndroidUtilities.runOnUIThread(() -> updateAppUpdateViews(currentAccount, animated));
            return;
        }
        final NovaUpdateChecker.State state = NovaUpdateChecker.getState();
        final NovaUpdateChecker.Release found = NovaUpdateChecker.getRelease();
        // FAILED with a release still known means the download broke, not the
        // check. The bar has to go on offering it: hiding it the moment the
        // user pressed it would leave no way back except the settings.
        final boolean visible = state == NovaUpdateChecker.State.FOUND
                || state == NovaUpdateChecker.State.DOWNLOADING
                || state == NovaUpdateChecker.State.READY
                || (state == NovaUpdateChecker.State.FAILED && found != null);
        if (visible) {
            createUpdateUI(currentAccount);

            boolean showSize = false;
            if (state == NovaUpdateChecker.State.READY) {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_UPDATE, true, animated);
                updateTextView.setText(
                        LocaleController.getString(R.string.NovaUpdateBarInstall), animated);
            } else if (state == NovaUpdateChecker.State.DOWNLOADING) {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_CANCEL, true, animated);
                updateLayoutIcon.setProgress(NovaUpdateChecker.getProgress() / 100f, animated);
                updateTextView.setText(LocaleController.formatString(
                        "NovaUpdateBarDownloading",
                        R.string.NovaUpdateBarDownloading,
                        NovaUpdateChecker.getProgress()), animated);
            } else {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_DOWNLOAD, true, animated);
                updateTextView.setText(
                        LocaleController.getString(R.string.NovaUpdateBarUpdate), animated);
                showSize = found != null && found.size > 0;
            }
            updateSizeTextView.setText(
                    showSize ? AndroidUtilities.formatFileSize(found.size) : null,
                    animated);
            if (updateLayout.getTag() != null) {
                return;
            }
            updateLayout.setVisibility(View.VISIBLE);
            updateLayout.setTag(1);
            if (animated) {
                updateLayout.animate()
                        .translationY(0)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                        .setListener(null)
                        .setDuration(180)
                        .start();
            } else {
                updateLayout.setTranslationY(0);
            }
        } else {
            if (updateLayout == null || updateLayout.getTag() == null) {
                return;
            }
            updateLayout.setTag(null);
            if (animated) {
                updateLayout.animate()
                        .translationY(dp(44))
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                if (updateLayout.getTag() == null) {
                                    updateLayout.setVisibility(View.INVISIBLE);
                                }
                            }
                        })
                        .setDuration(180)
                        .start();
            } else {
                updateLayout.setTranslationY(dp(44));
                updateLayout.setVisibility(View.INVISIBLE);
            }
        }
    }

    private static boolean isOnMainThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }
}
