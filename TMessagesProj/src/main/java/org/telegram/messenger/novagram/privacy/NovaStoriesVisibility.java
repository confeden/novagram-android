package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

/**
 * Whether this client draws anything about stories.
 *
 * <p>Off by default, unlike every other switch in this package. The others
 * take away something the user never sees — a request, a header, a piece of
 * metadata — while this one removes a working part of Telegram from the
 * screen, so it has to be chosen rather than inherited. The way back is the
 * same menu row that switched it on, which is what I8 asks of anything that
 * hides.</p>
 *
 * <h3>The screen, and only the screen</h3>
 *
 * <p>Nothing here touches the network. Stories are still delivered and still
 * counted, a {@code t.me} story link handed to the client still opens, and the
 * separate promise that a hidden dialog does not have its story views reported
 * is decided by {@code NovaReadStatus} and is unaffected either way.</p>
 */
public final class NovaStoriesVisibility {

    /**
     * The answer is wanted once per avatar per frame while the chat list
     * scrolls, so it is held here instead of reopening the preference file on
     * every draw. {@link #setHidden} is the only way it can change while the
     * process lives, and it writes both.
     */
    private static volatile Boolean cached;

    private NovaStoriesVisibility() {
    }

    public static boolean isHidden() {
        final Boolean known = cached;
        if (known != null) {
            return known;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            // Asked before the application object exists. "Shown" is the
            // default anyway, so answering it costs nobody the setting they
            // chose — but it is not the setting, so it is not remembered.
            return false;
        }
        final boolean hidden = NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.STORIES_HIDDEN);
        cached = hidden;
        return hidden;
    }

    public static void setHidden(Context context, boolean hidden) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        NovaPrivacySettings.global(context)
                .setFeatureEnabled(NovaPrivacyFeature.STORIES_HIDDEN, hidden);
        cached = hidden;
    }

    /**
     * Whether the chat list may offer the row at all. Stock Telegram has no
     * such entry, so in the decoy it would be the one thing on that screen
     * nobody else has — I4 keeps the fork invisible there.
     */
    public static boolean menuRowVisible() {
        return !NovaDecoyState.isActive();
    }

    public static CharSequence menuRowText() {
        return LocaleController.getString(R.string.NovaHideStories);
    }

    /**
     * Flips the switch from the chat list menu and, when it goes on, says the
     * one thing the row itself cannot: this is a screen setting, so a story
     * link opened from somewhere else still plays. Switching it back off needs
     * no explanation, so it gets none.
     */
    public static void toggleFromMenu(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final boolean hidden = !isHidden();
        setHidden(fragment.getParentActivity(), hidden);
        if (hidden && fragment.getFragmentView() != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.NovaHideStories),
                    LocaleController.getString(R.string.NovaHideStoriesEnabled)).show();
        }
    }
}
