package org.telegram.messenger.novagram.privacy;

import android.app.Activity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.Collection;

/**
 * The two warnings of the prohibited-content check, both asked at the moment
 * the user acts and never before: a listed author is about to be quoted,
 * forwarded or answered, or a listed link is about to be sent. Each one says
 * what was found, which list it is on, and that carrying it on may be
 * punishable; the user continues or cancels. Nothing here runs while typing.
 */
public final class NovaForbiddenUi {
    private NovaForbiddenUi() {
    }

    /** Runs {@code proceed} at once when nothing is listed, else after the user agrees. */
    public static void confirmMessages(BaseFragment fragment, Collection<MessageObject> messages, Runnable proceed, Runnable cancel) {
        int mask = 0;
        if (messages != null && NovaForbiddenList.isEnabled()) {
            for (MessageObject message : messages) {
                mask |= NovaForbiddenList.maskFor(message);
            }
        }
        mask &= NovaForbiddenList.PEOPLE_MASK;
        if (mask == 0 || fragment == null || fragment.getParentActivity() == null) {
            proceed.run();
            return;
        }
        show(fragment, R.string.NovaForbiddenAuthorText, NovaForbiddenList.names(mask), proceed, cancel);
    }

    public static void confirmMessage(BaseFragment fragment, MessageObject message, Runnable proceed, Runnable cancel) {
        if (message == null) {
            proceed.run();
            return;
        }
        confirmMessages(fragment, java.util.Collections.singletonList(message), proceed, cancel);
    }

    /** True when a dialog was shown and {@code proceed} will run later (or never). */
    public static boolean confirmText(BaseFragment fragment, CharSequence text, long dialogId, long selfId, Runnable proceed) {
        // Saved Messages is the user's own notebook: nothing is published there.
        if (dialogId == selfId || !NovaForbiddenList.isEnabled() || fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        int mask = NovaForbiddenList.scanText(text);
        if (mask == 0) {
            return false;
        }
        show(fragment, textFor(mask), NovaForbiddenList.names(mask), proceed, null);
        return true;
    }

    /**
     * Forwarding carries the content of the messages as well as their authors:
     * a forwarded post with a listed link or the full title of a listed material
     * is an outgoing message too. The author is asked first, as before.
     */
    public static void confirmForward(BaseFragment fragment, Collection<MessageObject> messages, Runnable proceed, Runnable cancel) {
        confirmMessages(fragment, messages, () -> {
            if (messages == null || !NovaForbiddenList.isEnabled() || fragment == null || fragment.getParentActivity() == null) {
                proceed.run();
                return;
            }
            int mask = 0;
            for (MessageObject message : messages) {
                if (message == null) {
                    continue;
                }
                mask |= NovaForbiddenList.scanText(message.messageText);
                mask |= NovaForbiddenList.scanText(message.caption);
            }
            if (mask == 0) {
                proceed.run();
                return;
            }
            show(fragment, textFor(mask), NovaForbiddenList.names(mask), proceed, cancel);
        }, cancel);
    }

    /** The link wording unless the only find is a material title. */
    private static int textFor(int mask) {
        return (mask & ~(1 << NovaForbiddenList.LIST_MATERIALS)) != 0
                ? R.string.NovaForbiddenLinkText
                : R.string.NovaForbiddenMaterialText;
    }

    private static void show(BaseFragment fragment, int text, String names, Runnable proceed, Runnable cancel) {
        Activity activity = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.NovaForbiddenWarningTitle));
        builder.setMessage(LocaleController.formatString(text, names));
        builder.setPositiveButton(LocaleController.getString(R.string.NovaForbiddenContinue), (dialog, which) -> proceed.run());
        builder.setNegativeButton(LocaleController.getString(R.string.NovaForbiddenCancel), (dialog, which) -> {
            if (cancel != null) {
                cancel.run();
            }
        });
        fragment.showDialog(builder.create());
    }
}
