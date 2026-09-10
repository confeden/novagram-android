package org.telegram.messenger.novagram;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.privacy.NovaDecoyState;
import org.telegram.messenger.novagram.privacy.NovaPrivacyFeature;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;
import android.os.Bundle;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.URLSpan;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.ProfileActivity;
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


    /** The scheme of the links this feature puts into message text. */
    public static final String LINK_PREFIX = "nova-peer:";

    /**
     * Shorter than any identifier Telegram hands out and longer than any it
     * will, so a year, a price and a four-digit code are never candidates.
     */
    private static final int MIN_DIGITS = 6;
    private static final int MAX_DIGITS = 13;

    /**
     * The five identifiers the documentation allows a zero access hash for.
     */
    private static final long[] SERVICE_IDS = {
        777000L, 1271266957L, 1087968824L, 136817688L, 5434988373L
    };

    /**
     * Whether this client holds what the server needs to be asked about the
     * peer behind a bare number.
     *
     * <p>MTProto has no "give me a peer by number": an identifier carries no
     * access key and not even the kind of peer, and the user, chat and channel
     * spaces are independent and overlap. What is possible is opening what
     * this authorization already knows - a peer with an access hash, a plain
     * group, which has none by design, this account itself, or one of the five
     * service identifiers - and nothing else. Hence the rule the whole feature
     * rests on: a number becomes a link only where something can be opened for
     * it, so there are no dead links on prices and order numbers, and no tap
     * that answers with silence.</p>
     */
    public static boolean resolvable(int account, long bare) {
        if (bare <= 0) {
            return false;
        }
        try {
            if (UserConfig.getInstance(account).getClientUserId() == bare) {
                return true;
            }
            for (long id : SERVICE_IDS) {
                if (id == bare) {
                    return true;
                }
            }
            MessagesController controller = MessagesController.getInstance(account);
            TLRPC.User user = controller.getUser(bare);
            if (user != null && (user.access_hash != 0 || user.self)) {
                return true;
            }
            TLRPC.Chat chat = controller.getChat(bare);
            if (chat != null) {
                // A plain group has no access hash by design; a channel does,
                // and without it the identifier addresses nothing.
                return !ChatObject.isChannel(chat) || chat.access_hash != 0;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    /**
     * Opens the profile, or says the one true thing when it cannot: that this
     * client has no key for that number. Never "no such peer" - the server
     * does not distinguish "does not exist" from "not for you", and neither
     * may this.
     */
    public static void open(BaseFragment fragment, String bareText) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        long bare;
        try {
            bare = Long.parseLong(bareText);
        } catch (Throwable e) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        if (resolvable(account, bare)) {
            Bundle args = new Bundle();
            MessagesController controller = MessagesController.getInstance(account);
            if (controller.getUser(bare) != null
                    || UserConfig.getInstance(account).getClientUserId() == bare) {
                args.putLong("user_id", bare);
                fragment.presentFragment(new ProfileActivity(args));
            } else {
                args.putLong("chat_id", bare);
                fragment.presentFragment(new ProfileActivity(args));
            }
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(
                fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle("ID " + bare);
        builder.setMessage(LocaleController.getString(R.string.NovaPeerIdNoKey));
        builder.setPositiveButton(LocaleController.getString(R.string.Copy), (d, w) -> {
            AndroidUtilities.addToClipboard(String.valueOf(bare));
            BulletinFactory.of(fragment).createCopyBulletin(
                    LocaleController.getString(R.string.NovaPeerIdCopied)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }

    /**
     * Turns the resolvable numbers of a message into links. Called from the
     * one place every message text passes on its way to the screen.
     */
    public static void linkify(CharSequence text, int account) {
        if (!(text instanceof Spannable) || NovaDecoyState.isActive()) {
            // No fork-only link may appear in the decoy: a number that turns
            // blue where plain Telegram leaves it black is a sign of the fork.
            return;
        }
        final Spannable spannable = (Spannable) text;
        final int length = text.length();
        int i = 0;
        while (i < length) {
            if (!Character.isDigit(text.charAt(i))) {
                i++;
                continue;
            }
            final int from = i;
            while (i < length && Character.isDigit(text.charAt(i))) {
                i++;
            }
            final int count = i - from;
            if (count < MIN_DIGITS || count > MAX_DIGITS) {
                continue;
            }
            if ((from > 0 && glued(text.charAt(from - 1)))
                    || (i < length && glued(text.charAt(i)))) {
                continue;
            }
            if (spannable.getSpans(from, i, URLSpan.class).length > 0) {
                continue;
            }
            long bare;
            try {
                bare = Long.parseLong(text.subSequence(from, i).toString());
            } catch (Throwable e) {
                continue;
            }
            if (!resolvable(account, bare)) {
                continue;
            }
            spannable.setSpan(
                    new URLSpanNoUnderline(LINK_PREFIX + bare),
                    from,
                    i,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static boolean glued(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '.' || ch == '/';
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
