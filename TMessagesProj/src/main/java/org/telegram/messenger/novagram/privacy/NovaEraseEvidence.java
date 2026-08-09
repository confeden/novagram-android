package org.telegram.messenger.novagram.privacy;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * The manual {@code Erase evidence} command for a single chat.
 *
 * <p>It walks the history backwards, page by page, keeping the account's own
 * messages and dropping its own reactions, then hands everything to
 * {@link NovaAutoDelete} for immediate destruction through the usual
 * replace-then-delete steps. Nothing of the other side is touched.</p>
 *
 * <p>History is paged rather than searched because Telegram cannot filter a
 * private dialog by sender: one path that works the same way in every kind of
 * chat is worth more than a faster one that works in some.</p>
 */
public final class NovaEraseEvidence {
    public static final int PERIOD_DAY = 0;
    public static final int PERIOD_WEEK = 1;
    public static final int PERIOD_MONTH = 2;
    public static final int PERIOD_ALL = 3;

    private static final int PAGE_SIZE = 100;
    /**
     * A hard stop on paging. A chat older than this many pages is unusual, and
     * an unbounded walk over a huge history would keep the client busy for a
     * long time without telling anybody why.
     */
    private static final int MAX_PAGES = 200;

    private NovaEraseEvidence() {
    }

    public interface Callback {
        /**
         * @param queued  own messages handed to the destruction queue
         * @param reactions own reactions withdrawn
         * @param complete false when paging stopped early, so older messages
         *                 outside the walked range were not covered
         */
        void onFinished(int queued, int reactions, boolean complete);
    }

    public static int sinceForPeriod(int account, int period) {
        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        switch (period) {
            case PERIOD_DAY:
                return now - 24 * 60 * 60;
            case PERIOD_WEEK:
                return now - 7 * 24 * 60 * 60;
            case PERIOD_MONTH:
                return now - 30 * 24 * 60 * 60;
            default:
                return 0;
        }
    }

    /** Starts the walk. The callback arrives on the UI thread when it ends. */
    public static void run(int account, long dialogId, int period, Callback callback) {
        new Collector(account, dialogId, sinceForPeriod(account, period), callback).next(0);
    }

    private static final class Collector {
        private final int account;
        private final long dialogId;
        private final int since;
        private final Callback callback;
        private final ArrayList<TLRPC.Message> found = new ArrayList<>();

        private int reactionsRemoved;
        private int pages;

        private Collector(int account, long dialogId, int since, Callback callback) {
            this.account = account;
            this.dialogId = dialogId;
            this.since = since;
            this.callback = callback;
        }

        private void next(int offsetId) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            req.offset_id = offsetId;
            req.limit = PAGE_SIZE;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> onPage(response, error)));
        }

        private void onPage(org.telegram.tgnet.TLObject response, TLRPC.TL_error error) {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                // Whatever was collected so far is still destroyed: a partial
                // result is reported as partial, not as success.
                finish(false);
                return;
            }
            List<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
            if (messages == null || messages.isEmpty()) {
                finish(true);
                return;
            }
            boolean reachedEnd = false;
            int lastId = 0;
            for (int i = 0, count = messages.size(); i < count; i++) {
                TLRPC.Message message = messages.get(i);
                if (message == null) {
                    continue;
                }
                lastId = message.id;
                if (since != 0 && message.date < since) {
                    reachedEnd = true;
                    break;
                }
                if (message.out && !isService(message) && message.id > 0) {
                    found.add(message);
                }
                if (withdrawReaction(message)) {
                    reactionsRemoved++;
                }
            }
            pages++;
            if (reachedEnd || messages.size() < PAGE_SIZE || lastId <= 0) {
                finish(true);
            } else if (pages >= MAX_PAGES) {
                finish(false);
            } else {
                next(lastId);
            }
        }

        /**
         * Drops this account's reactions from a message.
         *
         * <p>Honest boundary: Telegram marks reactions {@code min} when the
         * server answers without the caller's context, and then the chosen flag
         * is not filled in. Reactions on such messages are invisible here and
         * stay where they are.</p>
         */
        private boolean withdrawReaction(TLRPC.Message message) {
            if (message.reactions == null
                    || message.reactions.results == null
                    || message.id <= 0) {
                return false;
            }
            boolean chosen = false;
            for (int i = 0, count = message.reactions.results.size(); i < count; i++) {
                TLRPC.ReactionCount reactionCount = message.reactions.results.get(i);
                if (reactionCount != null && reactionCount.chosen) {
                    chosen = true;
                    break;
                }
            }
            if (!chosen) {
                return false;
            }
            TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            req.msg_id = message.id;
            // The reaction vector stays out of the request: an empty selection
            // is how Telegram is told to remove every reaction of this account.
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.Updates) {
                    MessagesController.getInstance(account)
                            .processUpdates((TLRPC.Updates) response, false);
                }
            });
            return true;
        }

        private void finish(boolean complete) {
            int queued = found.size();
            if (queued > 0) {
                try {
                    NovaAutoDelete.getInstance(account).eraseNow(dialogId, found);
                } catch (Throwable e) {
                    FileLog.e(e);
                    queued = 0;
                }
            }
            if (callback != null) {
                callback.onFinished(queued, reactionsRemoved, complete);
            }
        }

        private static boolean isService(TLRPC.Message message) {
            return message.action != null
                    && !(message.action instanceof TLRPC.TL_messageActionEmpty);
        }
    }
}
