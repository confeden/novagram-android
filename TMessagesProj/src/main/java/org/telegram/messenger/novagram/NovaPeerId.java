package org.telegram.messenger.novagram;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.privacy.NovaDecoyState;
import org.telegram.messenger.novagram.privacy.NovaPrivacyFeature;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

/**
 * The numeric identifier of a peer, shown in the profile of a person, a bot, a
 * group and a channel alike.
 *
 * <p>An identifier that appears for some kinds of peer and not others is worse
 * than none: it makes the reader guess whether the row is missing or the peer
 * has no id. So there is one rule and it holds everywhere.</p>
 *
 * <p>The number is what the rest of Telegram calls the dialog id, with the sign
 * that encodes the kind stripped off — that is the form other clients show and
 * the form people paste to each other.</p>
 */
public final class NovaPeerId {
    private NovaPeerId() {
    }

    public static boolean isEnabled(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || NovaDecoyState.isActive(context)) {
            // The decoy has to look like plain Telegram, which shows no such
            // row. A row nobody else has is a sign of the fork.
            return false;
        }
        return NovaPrivacySettings.forAccount(context, account)
                .isFeatureEnabled(NovaPrivacyFeature.SHOW_PEER_IDS);
    }

    /** The bare number, without the sign that encodes the kind of peer. */
    public static long bare(long dialogId) {
        return dialogId < 0 ? -dialogId : dialogId;
    }

    /**
     * What the row shows. Deliberately not formatted with group separators:
     * the moment the screen says "8 202 775 533" and the clipboard holds
     * "8202775533", the two have to be explained to the user, and one of them
     * is always the wrong one to paste.
     */
    public static String displayText(long dialogId, int account) {
        return "ID: " + bare(dialogId);
    }

    /** What lands in the clipboard: the number alone, ready to paste. */
    public static String clipboardText(long dialogId) {
        return String.valueOf(bare(dialogId));
    }

    public static void copy(BaseFragment fragment, long dialogId, int account) {
        if (fragment == null || dialogId == 0) {
            return;
        }
        try {
            AndroidUtilities.addToClipboard(clipboardText(dialogId));
            BulletinFactory.of(fragment)
                    .createCopyBulletin(LocaleController.getString(R.string.NovaPeerIdCopied))
                    .show();
        } catch (Throwable ignored) {
            // Copying is a convenience; a failure here must not take the
            // profile screen down with it.
        }
    }
}
