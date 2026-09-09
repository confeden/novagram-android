package org.telegram.messenger.novagram.privacy;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;


/**
 * Drops everything the other side sends in one dialog, on this device only.
 *
 * <p>The case it exists for: somebody writes to you whom you do not want to
 * block — blocking tells them, and it also tells them that everything they
 * sent up to that point was read — but do not want to hear from either. With
 * this on, what they write from that moment never reaches this device's
 * storage, its notifications or its screen. They keep sending into a
 * conversation that looks, from their side, exactly like one being ignored:
 * delivered, never read.</p>
 *
 * <h3>Where it can be turned on, and what turns it off</h3>
 *
 * <p>Only inside a dialog that already hides its read receipts — that is,
 * one the other side started and the user has never answered in
 * ({@link NovaReadStatus#isRuleHidden}). Two reasons, and both matter. The
 * promise "they get no read receipt" is what makes the silence complete, and
 * that promise is the read-status rule's, not this switch's. And a dialog
 * where the user did answer is a conversation: quietly dropping half of it is
 * not ignoring somebody, it is losing mail.</p>
 *
 * <p>It goes off the moment the user sends anything into that dialog — a
 * message, a reaction, from this device or another one — because that is the
 * same door the read-status hiding is lifted by
 * ({@link NovaReadStatus#reveal}). One switch cannot outlive the other.</p>
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <ul>
 *   <li>Nothing is deleted for the other side. Their copy, their history and
 *       their dialog are untouched, and no request leaves this device.</li>
 *   <li>There is no way to read what was dropped. It is not a folder, not an
 *       archive and not a filter — the message is not kept anywhere.</li>
 *   <li>Messages that arrived before the switch was turned on stay where they
 *       are; the watermark is the id it was turned on at.</li>
 * </ul>
 */
public final class NovaDropIncoming {

    private NovaDropIncoming() {
    }

    /**
     * Whether the switch may be offered for this dialog at all. The same
     * condition the read-status entry in the chat menu uses, because this one
     * lives directly under it and means nothing without it.
     */
    public static boolean offered(int account, long dialogId) {
        return NovaReadStatus.isRuleHidden(account, dialogId);
    }

    /** Whether the dialog is dropping what the other side sends right now. */
    public static boolean active(int account, long dialogId) {
        final int[] ranges = NovaReadStatus.dropRanges(account, dialogId);
        return ranges != null
                && ranges.length >= 3
                && ranges[ranges.length - 1] != 0;
    }

    /**
     * Starts dropping. The watermark is the newest message the dialog is known
     * to hold, so everything that is already there stays readable and only
     * what comes after is dropped.
     */
    public static void enable(int account, long dialogId) {
        int from = 1;
        try {
            TLRPC.Dialog dialog = MessagesController.getInstance(account)
                    .dialogs_dict.get(dialogId);
            if (dialog != null && dialog.top_message > 0) {
                from = dialog.top_message + 1;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        NovaReadStatus.openDropRange(account, dialogId, from);
    }

    /**
     * Stops dropping. The spell is closed at the newest id it threw away, and
     * kept: without it the server would hand every dropped message back the
     * next time the history is opened.
     */
    public static void disable(int account, long dialogId) {
        NovaReadStatus.closeDropRange(account, dialogId);
    }

    /**
     * The one decision, asked once per message on whichever thread the message
     * arrived on.
     */
    public static boolean drops(int account, long dialogId, MessageObject message) {
        if (message == null || message.isOut()) {
            return false;
        }
        return drops(account, dialogId, message.getId());
    }

    public static boolean drops(int account, long dialogId, TLRPC.Message message) {
        if (message == null || message.out) {
            return false;
        }
        return drops(account, dialogId, message.id);
    }

    private static boolean drops(int account, long dialogId, int messageId) {
        if (messageId <= 0 || !DialogObject.isUserDialog(dialogId)) {
            // Negative ids are this device's own unsent messages, and only a
            // private dialog can be ignored this way: a group has members
            // whose messages are not the one person being ignored.
            return false;
        }
        final int[] ranges = NovaReadStatus.dropRanges(account, dialogId);
        if (ranges == null) {
            return false;
        }
        for (int i = 0; i + 2 < ranges.length; i += 3) {
            final int from = ranges[i], till = ranges[i + 1], open = ranges[i + 2];
            if (messageId < from) {
                continue;
            } else if (open != 0) {
                // The open spell goes on into whatever arrives next, and the id
                // it is now dropping is written down so that closing it later
                // leaves behind exactly what was thrown away.
                NovaReadStatus.noteDropped(account, dialogId, messageId);
                return true;
            } else if (messageId <= till) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes the unread counters of a dropping dialog down to nothing.
     *
     * <p>The server goes on counting what it delivered and this client threw
     * away, so without this the chat list would show a badge climbing over
     * messages the user cannot open — the one thing the switch promises not to
     * happen. Local only: the counters are not read back to the server, and no
     * receipt is sent to bring them down honestly.</p>
     */
    public static void silence(int account, TLRPC.Dialog dialog) {
        if (dialog == null || !active(account, dialog.id)) {
            return;
        }
        dialog.unread_count = 0;
        dialog.unread_mark = false;
        dialog.unread_mentions_count = 0;
        dialog.unread_reactions_count = 0;
    }
}
