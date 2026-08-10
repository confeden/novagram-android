package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hiding the read status in dialogs the other side started.
 *
 * <p>When someone writes first, NovaGram stops telling the server that their
 * messages were read: the other side never sees the read marks. Dialogs the
 * user started are left alone, and so are groups and channels — the promise is
 * about someone who wrote to you, not about every conversation.</p>
 *
 * <p>"The other side started it" is decided once, from three facts: the dialog
 * is private and ordinary, it was new to this client when the first incoming
 * message arrived, and the local copy of the conversation begins with that
 * message and holds nothing the user wrote. It is deliberately not
 * decided from the "report spam / add to contacts" bar any more — the server
 * only offers that bar for people who are not in the contact list, so a contact
 * who wrote first would never have been covered.</p>
 *
 * <p>One thing takes the hiding back, and it takes it back permanently: any
 * ordinary message the user sends into that dialog, from this device or from
 * another one. Answering somebody is telling them you read what they wrote, so
 * keeping the receipts back afterwards would protect nothing and only leave the
 * conversation looking strange. Service entries and a saved draft are not that
 * message and change nothing.</p>
 *
 * <p>Honest boundary, which the user is told in plain words: for Telegram those
 * messages stay unread. The unread counter of such a dialog can therefore come
 * back after a restart and on other devices.</p>
 */
public final class NovaReadStatus implements NotificationCenter.NotificationCenterDelegate {
    private static final Object INSTANCE_LOCK = new Object();
    private static final NovaReadStatus[] instances = new NovaReadStatus[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile boolean wiped;

    /** Telegram's own service account, which never shows read marks. */
    private static final long SERVICE_NOTIFICATIONS_ID = 777000L;

    private static final int PENDING_INCOMING = 1;
    private static final int PENDING_OUTGOING = 2;
    /** The dialog was absent from the chat list when the message was handed to us. */
    private static final int PENDING_DIALOG_ABSENT = 4;

    /**
     * Written down when the baseline was taken but the chat list held nothing
     * datable. Nothing is older than it, so no dialog is called an old one by
     * its date alone, and each is decided from its own history instead.
     */
    private static final int BASELINE_DATE_UNKNOWN = 1;

    /**
     * What a waiting dialog knows about itself: why it waits, and the earliest
     * incoming message that is deciding it. The message identifier is what
     * tells "this dialog begins here" from "this dialog already held messages
     * older than this one", which is the only thing the local cache can answer
     * for certain — it is not a complete copy of a conversation.
     */
    private static final class Pending {
        int flags;
        int mid;

        Pending(int flags, int mid) {
            this.flags = flags;
            this.mid = mid;
        }
    }

    private final int currentAccount;
    private final NovaReadStatusStore store;

    private NovaReadStatusStore.State state = new NovaReadStatusStore.State(0L);
    /**
     * Dialogs whose messages arrived before the answer could be given — either
     * the rules were still being decrypted, or the chat list was not loaded yet
     * and an absent rule did not mean anything. The first message is the one
     * that decides, and at start-up it is exactly the one most likely to land in
     * that window, so it waits here instead of being dropped.
     */
    private final LinkedHashMap<Long, Pending> pendingDialogs = new LinkedHashMap<>();
    /** Dialogs whose stored read state is being looked up right now. */
    private final HashSet<Long> verifying = new HashSet<>();
    private boolean loaded;
    /** A read of the store is on its way, see {@link #load()}. */
    private boolean loading;
    private boolean observing;
    private boolean saveScheduled;
    /** A retry of the waiting dialogs is already on its way, see {@link #retryPendingLater()}. */
    private boolean retryScheduled;
    /**
     * Size of the chat list the last sweep saw. {@code dialogsNeedReload} is
     * posted on every little change, and walking the whole list each time would
     * cost the main thread for nothing: only a longer list can hold dialogs the
     * sweep has not seen.
     */
    private int sweptDialogCount = -1;

    private final Runnable saveRunnable = this::save;

    private NovaReadStatus(int account) {
        currentAccount = account;
        store = new NovaReadStatusStore(ApplicationLoader.applicationContext, account);
    }

    public static NovaReadStatus getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            throw new IllegalArgumentException("Unknown account " + account);
        }
        NovaReadStatus instance = instances[account];
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                instance = instances[account];
                if (instance == null) {
                    instance = new NovaReadStatus(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    public static void start() {
        // Deliberately not gated on the application PIN. The store has a
        // Keystore key of its own and does not need the PIN, while a gate here
        // would leave the rules unread on a cold start with a PIN set: nothing
        // re-runs this after the unlock, no observer would be registered, and
        // isHidden would answer "hidden" for every private dialog from an
        // engine that was never started.
        if (wiped || NovaDecoyState.isActive()) {
            return;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    getInstance(account).ensureStarted();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** Called by the emergency wipe before it deletes anything. */
    public static void shutdown() {
        wiped = true;
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                NovaReadStatus instance = instances[account];
                if (instance != null) {
                    instance.stop();
                }
            }
        });
    }

    private void stop() {
        AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        saveScheduled = false;
        if (observing) {
            observing = false;
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.didReceiveNewMessages);
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.dialogsNeedReload);
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.dialogDeleted);
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.appDidLogout);
        }
        pendingDialogs.clear();
        verifying.clear();
        retryScheduled = false;
        sweptDialogCount = -1;
        state = new NovaReadStatusStore.State(0L);
        loaded = false;
        loading = false;
    }

    public void ensureStarted() {
        // The decoy check belongs here and not only in start(): this is the
        // single door in, and a decoy that read the rules would create a store
        // file for an account that is supposed to know nothing about NovaGram.
        if (wiped || NovaDecoyState.isActive()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (wiped) {
                return;
            }
            if (!observing) {
                observing = true;
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.dialogsNeedReload);
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.dialogDeleted);
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.appDidLogout);
            }
            if (!loaded && !loading) {
                load();
            }
        });
    }

    private void load() {
        loading = true;
        long ownerId = UserConfig.getInstance(currentAccount).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            NovaReadStatusStore.State loadedState = store.load(ownerId);
            AndroidUtilities.runOnUIThread(() -> {
                if (loaded || wiped) {
                    return;
                }
                if (loadedState.isUnavailable()) {
                    // Keystore was busy. Not marking the state loaded keeps the
                    // gate closed, which errs towards silence rather than
                    // towards a receipt the user did not want sent. loading
                    // stays true until that retry runs, so the callers below
                    // that ask ensureStarted() again do not pile up loads.
                    AndroidUtilities.runOnUIThread(this::load, 30_000L);
                    return;
                }
                loading = false;
                loadedState.setOwnerId(ownerId);
                for (Map.Entry<Long, Integer> rule : state.getRules().entrySet()) {
                    loadedState.setRule(rule.getKey(), rule.getValue());
                }
                state = loadedState;
                loaded = true;
                settleDialogs();
                notifyRulesChanged();
            });
        });
    }

    // Settings ---------------------------------------------------------------

    public static boolean isEnabled(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        return NovaPrivacySettings.forAccount(context, account)
                .isFeatureEnabled(NovaPrivacyFeature.READ_STATUS_HIDING);
    }

    public static void setEnabled(int account, boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        NovaPrivacySettings.forAccount(context, account)
                .setFeatureEnabled(NovaPrivacyFeature.READ_STATUS_HIDING, enabled);
        if (enabled) {
            getInstance(account).ensureStarted();
        }
    }

    // Rules ------------------------------------------------------------------

    /**
     * Whether read receipts have to be withheld in this dialog right now. It
     * answers "yes" while the answer is not known yet, so it is the question to
     * ask before talking to the server and the wrong question to ask before
     * telling the user something — see {@link #isRuleHidden}.
     */
    public static boolean isHidden(int account, long dialogId) {
        if (wiped || dialogId >= 0 && !DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        try {
            return getInstance(account).hiddenFor(dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Whether hiding is really written down for this dialog. Unlike
     * {@link #isHidden} this says nothing while the rules are still being read,
     * because a panel that promises silence has to be shown for a rule that
     * exists, not for one that may yet turn out not to.
     */
    public static boolean isRuleHidden(int account, long dialogId) {
        if (wiped || !DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        try {
            NovaReadStatus instance = getInstance(account);
            if (!instance.loaded || !isEnabled(account)) {
                return false;
            }
            return instance.state.getRule(dialogId) == NovaReadStatusStore.RULE_HIDDEN;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Whether {@link #isHidden} is holding this dialog back without having
     * decided it. A caller that would have talked to the server has to keep
     * what it did not send instead of dropping it: the answer can still turn
     * out to be "this dialog is the user's", and by then nothing would offer
     * the read again — the local half of it has already run.
     */
    public static boolean isUndecided(int account, long dialogId) {
        if (wiped || dialogId >= 0 && !DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        try {
            NovaReadStatus instance = getInstance(account);
            if (!instance.hiddenFor(dialogId)) {
                return false;
            }
            return !instance.loaded || instance.state.getRule(dialogId) == 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private boolean hiddenFor(long dialogId) {
        if (!isEnabled(currentAccount)) {
            return false;
        }
        if (!loaded) {
            // Whoever is asking is about to talk to the server, so this is the
            // last moment to notice that nobody has started the engine — and
            // an engine that is never started answers "hidden" for every
            // private dialog forever, holding receipts that are then lost.
            ensureStarted();
            // The rules are still being decrypted. Answering "not hidden" here
            // would send exactly the receipt the user asked to withhold, and a
            // receipt cannot be taken back; holding it for the second the read
            // takes costs nothing, because the next read of the same dialog
            // sends it anyway once the rules are known.
            return DialogObject.isUserDialog(dialogId)
                    && dialogId != UserConfig.getInstance(currentAccount).getClientUserId();
        }
        int rule = state.getRule(dialogId);
        if (rule != 0) {
            // Decided, and the decision outranks any leftover entry in the
            // waiting lists below. Only the user's own message revises it, and
            // by then the rule itself has already been rewritten.
            return rule == NovaReadStatusStore.RULE_HIDDEN;
        }
        // A dialog still waiting for the chat list, or for the answer of the
        // stored read state, has no rule yet. Same reasoning as above: a receipt
        // held back by mistake is sent by the next read, a receipt sent by
        // mistake cannot be recalled.
        return pendingDialogs.containsKey(dialogId) || verifying.contains(dialogId);
    }

    /**
     * Turns hiding off for this dialog for good. There is no way back on
     * purpose: the next receipt reaches the server, the other side sees every
     * message as read at once, and pretending that could be undone would be a
     * lie. Reached both from the button above the chat and from the user's own
     * message, which means the same thing.
     */
    public void reveal(long dialogId) {
        if (loaded && state.getRule(dialogId) == NovaReadStatusStore.RULE_REVEALED) {
            // Already open, and the receipt below has gone out once. Every
            // outgoing message arrives here, so without this a readHistory
            // would be sent for each of them.
            return;
        }
        state.setRule(dialogId, NovaReadStatusStore.RULE_REVEALED);
        ensureStarted();
        scheduleSave();
        // Not releaseHeldReads: while the rule said "hidden" the reads were not
        // held but dropped, and the local half of them has already run. Only
        // the catch-up sends anything in that case.
        catchUpReads(dialogId);
        notifyRulesChanged();
    }

    /**
     * The conversation is being deleted, so what was decided about it goes with
     * it. A rule left behind would answer about the deleted dialog when the
     * next one begins: a {@code RULE_REVEALED} would send the receipt the other
     * side was not supposed to get when they write first, a {@code RULE_HIDDEN}
     * would silence a dialog the user has started. Starting over after deleting
     * a conversation is exactly what this feature is for.
     */
    private void forgetDialog(long dialogId) {
        pendingDialogs.remove(dialogId);
        // Anything already on its way from the storage queue is about the
        // conversation that is being deleted, and is dropped by the callback
        // when it finds its entry gone.
        verifying.remove(dialogId);
        MessagesController.getInstance(currentAccount).novaForgetHeldReads(dialogId);
        if (loaded && state.getRule(dialogId) != 0) {
            state.removeRule(dialogId);
            scheduleSave();
            notifyRulesChanged();
        }
    }

    /**
     * Sends what the gate held back while this dialog had no rule yet, now that
     * it turned out to be one the user started. Without it the reads dropped
     * during that window would be lost for good: their local half has already
     * run, so the chat looks read here and the other side keeps seeing one tick.
     */
    private void releaseHeldReads(long dialogId) {
        if (wiped) {
            return;
        }
        try {
            MessagesController.getInstance(currentAccount).novaSendHeldReads(dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * The same, plus the receipt for the position this client already stands
     * at. Needed where a written-down {@code RULE_HIDDEN} is being taken back:
     * nothing was kept for such a dialog — the receipts were dropped, not held —
     * yet the local read markers moved all the same, so no ordinary read would
     * ever offer them again.
     */
    private void catchUpReads(long dialogId) {
        if (wiped) {
            return;
        }
        try {
            MessagesController.getInstance(currentAccount).novaSendReadAfterReveal(dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * The rules are read and written long after a chat has been opened, so a
     * screen showing what is happening in the dialog it is open on has to be
     * told. Nothing about this reaches the server: it is one process telling
     * itself what it has just decided.
     */
    private void notifyRulesChanged() {
        if (wiped) {
            return;
        }
        NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.novaReadStatusUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            stop();
            Utilities.globalQueue.postRunnable(store::destroy);
            return;
        }
        if (id == NotificationCenter.dialogsNeedReload) {
            if (loaded && !wiped) {
                settleDialogs();
            }
            return;
        }
        if (id == NotificationCenter.dialogDeleted) {
            if (!wiped && args != null && args.length > 0 && args[0] instanceof Long) {
                forgetDialog((Long) args[0]);
            }
            return;
        }
        if (id != NotificationCenter.didReceiveNewMessages || args == null || args.length < 2) {
            return;
        }
        if (!(args[0] instanceof Long) || !(args[1] instanceof ArrayList)) {
            return;
        }
        if (args.length >= 3 && args[2] instanceof Boolean && (Boolean) args[2]) {
            // Scheduled. The message is only queued, so treating it as an
            // answer would take the hiding back hours before anything was said.
            return;
        }
        if (args.length >= 4 && args[3] instanceof Integer && (Integer) args[3] != 0) {
            // Not the ordinary chat: quick replies and saved-dialog threads
            // come through the same event and are not written to this dialog.
            return;
        }
        long dialogId = (Long) args[0];
        if (wiped
                || !DialogObject.isUserDialog(dialogId)
                || dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            // Only private dialogs, and never Saved Messages.
            return;
        }
        int flags = 0;
        int decidingMid = 0;
        ArrayList<?> messages = (ArrayList<?>) args[1];
        for (int i = 0, count = messages.size(); i < count; i++) {
            Object object = messages.get(i);
            if (!(object instanceof MessageObject)) {
                continue;
            }
            MessageObject message = (MessageObject) object;
            if (message.messageOwner == null
                    || message.messageOwner.action != null
                    && !(message.messageOwner.action instanceof TLRPC.TL_messageActionEmpty)) {
                // Service entries are not somebody writing to somebody.
                continue;
            }
            if (message.isOut()) {
                flags |= PENDING_OUTGOING;
                continue;
            }
            flags |= PENDING_INCOMING;
            // The earliest of the batch: it is the one that would have started
            // the dialog, and everything older than it was here before.
            int mid = message.getId();
            if (mid > 0 && (decidingMid == 0 || mid < decidingMid)) {
                decidingMid = mid;
            }
        }
        if (flags == 0) {
            return;
        }
        // Asked here rather than later: the chat list is the one fact that stops
        // being true the moment this notification returns, because the caller
        // creates the dialog right after posting it.
        if (MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId) == null) {
            flags |= PENDING_DIALOG_ABSENT;
        }
        onMessages(dialogId, flags, decidingMid);
    }

    /**
     * The first message decides which way the dialog goes, and only the user's
     * own message can decide it again. Whether the dialog was started by the
     * other side cannot be recomputed later: by then the user has replied, and
     * the beginning may not even be loaded any more.
     */
    private void onMessages(long dialogId, int flags, int decidingMid) {
        if (!loaded) {
            postpone(dialogId, flags, decidingMid);
            return;
        }
        if ((flags & PENDING_OUTGOING) != 0) {
            // Writing in a dialog is reading what was written there, so the
            // user's own message takes the hiding back, and takes it back for
            // good — exactly what the button above the chat does. This stands
            // above the check for an existing rule on purpose: a written-down
            // RULE_HIDDEN is precisely what has to be undone here, and it also
            // keeps a dialog the user started from turning hidden the moment
            // the other side answers. A message sent from another device
            // arrives here the same way, as an ordinary new message with
            // out = true.
            if (!neverHidden(dialogId)) {
                reveal(dialogId);
            }
            return;
        }
        if (state.getRule(dialogId) != 0) {
            return;
        }
        if (neverHidden(dialogId)) {
            return;
        }
        if (!state.isBaselineTaken() && !looksLikeStranger(dialogId)) {
            // There is nothing yet to say whether this dialog was there before
            // the message. Waiting for the chat list is the only way to tell
            // the two apart, and it is waited for whether or not the dialog is
            // in it already: at start-up an absent dialog means "the list has
            // not arrived", not "this dialog is new". The receipt is held
            // meanwhile, which can be taken back; a sent one cannot.
            postpone(dialogId, flags, decidingMid);
            return;
        }
        verifyAndHide(dialogId, flags, decidingMid);
    }

    /**
     * Order matters. The dialogs that were waiting are asked first, because the
     * baseline writes down the dialogs the account already had, and a dialog
     * whose first message has only just arrived is not one of them: by the time
     * this class sees that message the caller has already put its dialog into
     * the chat list, where it looks exactly like an old one. Asking again
     * afterwards settles the ones that were waiting for the baseline itself.
     */
    private void settleDialogs() {
        drainPending();
        takeBaseline();
        revealDialogsOlderThanBaseline();
        drainPending();
        // The waiting list only holds dialogs a message arrived in. A read
        // taken before the rules were decrypted — a chat opened right at
        // start-up, a reply from the notification shade — is held for a dialog
        // that never gets there, and nothing else would look at it again.
        if (loaded && !wiped) {
            try {
                MessagesController.getInstance(currentAccount).novaSettleHeldReads();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private void postpone(long dialogId, int flags, int decidingMid) {
        Pending previous = pendingDialogs.get(dialogId);
        if (previous == null) {
            pendingDialogs.put(dialogId, new Pending(flags, decidingMid));
            return;
        }
        previous.flags |= flags;
        if (decidingMid > 0 && (previous.mid == 0 || decidingMid < previous.mid)) {
            previous.mid = decidingMid;
        }
    }

    private void drainPending() {
        if (pendingDialogs.isEmpty()) {
            return;
        }
        ArrayList<Long> dialogIds = new ArrayList<>(pendingDialogs.keySet());
        for (int i = 0, count = dialogIds.size(); i < count; i++) {
            long dialogId = dialogIds.get(i);
            Pending pending = pendingDialogs.remove(dialogId);
            if (pending != null) {
                onMessages(dialogId, pending.flags, pending.mid);
            }
            if (loaded && state.getRule(dialogId) == NovaReadStatusStore.RULE_REVEALED) {
                // The wait is over and this dialog is not one to hide, so the
                // reads held back while it waited have to go out after all.
                releaseHeldReads(dialogId);
            }
        }
    }

    /**
     * Asks the waiting dialogs again a little later. Used when the answer could
     * not be obtained at all — the database was busy or being rebuilt — because
     * a failure is not an answer and must not become a rule.
     */
    private void retryPendingLater() {
        if (retryScheduled || wiped) {
            return;
        }
        retryScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            retryScheduled = false;
            if (!wiped && loaded) {
                settleDialogs();
            }
        }, 30_000L);
    }

    /**
     * Dialogs that can never be hidden: they send no read marks of their own, so
     * withholding receipts would cost the user something and buy nothing.
     */
    private boolean neverHidden(long dialogId) {
        if (dialogId == SERVICE_NOTIFICATIONS_ID
                || dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return true;
        }
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
        return user != null && (user.bot || user.support || user.self);
    }

    /**
     * Whether the server is offering the "report spam / add to contacts" bar,
     * which it does only while somebody outside the contact list has written
     * and the user has not answered. It is an extra ground to hide, never a
     * condition: the bar is not shown for contacts, and a contact who writes
     * first is exactly the case this feature had been missing. Only settings
     * that are already known are used — nothing is requested and nothing is
     * waited for, because {@code loadPeerSettings} answers only for chats the
     * user opens.
     */
    private boolean looksLikeStranger(long dialogId) {
        TLRPC.PeerSettings settings =
                MessagesController.getInstance(currentAccount).getPeerSettings(dialogId);
        return settings != null
                && (settings.report_spam || settings.block_contact || settings.add_contact);
    }

    /**
     * Last check before the rule is written: whether this dialog really begins
     * with the message that is deciding it, and holds nothing the user wrote.
     * Asked of the database rather than of the loaded chat list, because the
     * chat list holds only the pages that have been loaded, and a dialog that
     * has been quiet for a long time is exactly the one missing from it. An
     * answer that says nothing writes no rule at all — see
     * {@link #looksStartedByUser}.
     */
    private void verifyAndHide(long dialogId, int flags, int decidingMid) {
        // The server's read markers are not asked here, neither
        // dialogs_read_outbox_max nor Dialog.read_outbox_max_id. Both survive
        // deleting the conversation and come back with the record of the new
        // one, so they answer "these two have talked at some point", not "the
        // user has written in this dialog". The database below is asked
        // instead: only messages that are actually here count.
        if (!verifying.add(dialogId)) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            Boolean started = looksStartedByUser(storage, dialogId, decidingMid);
            AndroidUtilities.runOnUIThread(() -> {
                if (!verifying.remove(dialogId)) {
                    // The conversation was deleted while the answer travelled,
                    // so the answer is about a dialog that no longer exists.
                    return;
                }
                if (wiped || !loaded || state.getRule(dialogId) != 0) {
                    return;
                }
                if (started == null) {
                    // The question could not be asked — no database, or it
                    // threw. That is not an answer, and a rule made from it
                    // would never be revisited, so the dialog goes back to
                    // waiting: its receipts stay held and the next round asks
                    // again.
                    // The original flags go back with it: "the dialog was not
                    // in the chat list when its message arrived" is what keeps
                    // the baseline from answering for it.
                    postpone(dialogId, flags | PENDING_INCOMING, decidingMid);
                    retryPendingLater();
                    return;
                }
                if (started) {
                    state.setRule(dialogId, NovaReadStatusStore.RULE_REVEALED);
                    scheduleSave();
                    releaseHeldReads(dialogId);
                    notifyRulesChanged();
                    return;
                }
                state.setRule(dialogId, NovaReadStatusStore.RULE_HIDDEN);
                MessagesController.getInstance(currentAccount).novaForgetHeldReads(dialogId);
                // Deliberately without the dialog: the list of these dialogs is
                // what the sealed storage exists to protect, and the log file
                // is a plain file in the external directory of the app.
                FileLog.d("novagram: read status rule written");
                scheduleSave();
                notifyRulesChanged();
            });
        });
    }

    /**
     * Whether this dialog has to be left alone, asked of the local database.
     * Three answers, not two: {@code TRUE} — the account has written here, or
     * the dialog already held messages before {@code decidingMid}, so it was
     * not started by that message; {@code FALSE} — the dialog begins with the
     * message that is deciding it and holds nothing of the account's;
     * {@code null} — the question could not be asked at all, which is not an
     * answer and must not become a rule.
     *
     * <p>Asking only whether the user was read is not enough: the other side
     * may simply not have opened the chat — answering from the notification
     * shade is enough for that — and a dialog the user started would then be
     * taken for a dialog of a stranger. Runs on the storage queue.</p>
     */
    private static Boolean looksStartedByUser(
            MessagesStorage storage, long dialogId, int decidingMid) {
        if (decidingMid <= 0) {
            // Nothing to measure the dialog against, so nothing is decided.
            return null;
        }
        SQLiteCursor cursor = null;
        try {
            SQLiteDatabase database = storage.getDatabase();
            if (database == null) {
                return null;
            }
            // Asking the dialogs table for outbox_max looks like the same
            // question and is not. Found by running the two-account check on
            // 2026-08-08: the server keeps the read markers of a deleted
            // conversation and hands them back with the record of the new one,
            // so a dialog created ten seconds ago arrives with a non-zero
            // outbox_max and is taken for one the user has written in. Only
            // messages that are actually here answer this, and starting over
            // after deleting the conversation is exactly what this feature is
            // for.
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT mid FROM messages_v2 WHERE uid = %d AND out = 1 AND mid > 0 LIMIT 1",
                    dialogId));
            if (cursor.next()) {
                return Boolean.TRUE;
            }
            cursor.dispose();
            cursor = null;
            // "Nothing of ours here" is not an answer by itself: the local copy
            // of a conversation is not a complete one. A dialog that came with
            // the chat list keeps only its top message, "clear database" leaves
            // one or two, and NovaAutoDelete erases the user's own messages on
            // purpose - so years of what the user wrote can be missing while
            // the dialog is old. What the cache does answer for certain is
            // whether the dialog held anything before the message that is
            // deciding it: if it did, it was there before, which is what the
            // baseline says about every dialog it can see, and it is left alone
            // for the same reason.
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT mid FROM messages_v2 WHERE uid = %d AND mid > 0 AND mid < %d LIMIT 1",
                    dialogId, decidingMid));
            return cursor.next() ? Boolean.TRUE : Boolean.FALSE;
        } catch (Throwable e) {
            FileLog.e(e);
            // The question could not be asked. Before, this returned "the user
            // has not written here" and a database hiccup wrote a permanent
            // rule; now it says nothing and the dialog goes back to waiting.
            return null;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    // Baseline ---------------------------------------------------------------

    /**
     * Writes down the chat list the account already had. Without it an absent
     * rule would be indistinguishable from "this dialog is new", and every old
     * conversation would turn hidden as soon as it received a message.
     *
     * <p>It cannot be replaced by looking at the chat list at the moment a
     * message arrives: {@code didReceiveNewMessages} is posted through
     * {@link NotificationCenter}, which holds notifications back while a screen
     * transition is running, so by the time this class sees the message the
     * caller may already have created the dialog.</p>
     */
    private void takeBaseline() {
        if (!loaded || wiped || state.isBaselineTaken()) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);
        if (!controller.dialogsLoaded) {
            return;
        }
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        LongSparseArray<TLRPC.Dialog> dialogs = controller.dialogs_dict;
        int written = 0;
        int oldest = 0;
        for (int i = 0, count = dialogs.size(); i < count; i++) {
            long dialogId = dialogs.keyAt(i);
            TLRPC.Dialog dialog = dialogs.valueAt(i);
            if (dialog != null
                    && dialog.last_message_date > 0
                    && (oldest == 0 || dialog.last_message_date < oldest)
                    && !waitsForItsOwnAnswer(dialogId)) {
                // Every kind of dialog counts here, not only the personal ones:
                // the older the date, the fewer dialogs the sweep below calls
                // old ones, and a dialog it does not call old is decided from
                // its own history rather than written off by the date.
                oldest = dialog.last_message_date;
            }
            if (!DialogObject.isUserDialog(dialogId) || dialogId == selfId) {
                continue;
            }
            if (waitsForItsOwnAnswer(dialogId)) {
                // This dialog was not in the chat list when its first message
                // was handed to us, so it is being decided right now and the
                // baseline knows nothing about it.
                continue;
            }
            // Only where nothing is known yet: a dialog already hidden stays
            // hidden, because dropping the rule means sending exactly the
            // receipts the user was promised would be held back.
            if (state.getRule(dialogId) == 0) {
                state.setRule(dialogId, NovaReadStatusStore.RULE_REVEALED);
                written++;
            }
        }
        // The date of the oldest dialog of the loaded page, not the clock. The
        // page holds the newest dialogs of the account, so a dialog not newer
        // than its last one is further down the same list and was there before.
        // The clock cannot be used for that: the baseline is taken before the
        // first answer of the server, so the time is the device's own, and a
        // device whose clock is a day fast would call every dialog somebody
        // starts today an old one.
        state.setBaselineDate(oldest > 0 ? oldest : BASELINE_DATE_UNKNOWN);
        FileLog.d("novagram: read status baseline taken, " + written + " dialogs marked");
        scheduleSave();
    }

    /**
     * Whether this dialog is deciding its own rule right now: its message
     * arrived before the chat list held it, or the stored read state is being
     * looked up for it. Neither the baseline nor the sweep may answer for such
     * a dialog — they only know that it is in the list, which by then it is in
     * either way.
     */
    private boolean waitsForItsOwnAnswer(long dialogId) {
        if (verifying.contains(dialogId)) {
            return true;
        }
        Pending pending = pendingDialogs.get(dialogId);
        return pending != null && (pending.flags & PENDING_DIALOG_ABSENT) != 0;
    }

    /**
     * The chat list arrives a page at a time, so dialogs older than the baseline
     * keep turning up long after it was taken. They existed before the baseline
     * and are marked as such the moment they appear.
     */
    private void revealDialogsOlderThanBaseline() {
        if (!loaded || wiped || !state.isBaselineTaken()) {
            return;
        }
        int baselineDate = state.getBaselineDate();
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        LongSparseArray<TLRPC.Dialog> dialogs =
                MessagesController.getInstance(currentAccount).dialogs_dict;
        if (dialogs.size() <= sweptDialogCount) {
            return;
        }
        sweptDialogCount = dialogs.size();
        boolean changed = false;
        for (int i = 0, count = dialogs.size(); i < count; i++) {
            long dialogId = dialogs.keyAt(i);
            if (!DialogObject.isUserDialog(dialogId) || dialogId == selfId) {
                continue;
            }
            TLRPC.Dialog dialog = dialogs.valueAt(i);
            if (dialog == null
                    || dialog.last_message_date <= 0
                    || dialog.last_message_date > baselineDate
                    || waitsForItsOwnAnswer(dialogId)) {
                continue;
            }
            if (state.getRule(dialogId) == 0) {
                state.setRule(dialogId, NovaReadStatusStore.RULE_REVEALED);
                changed = true;
            }
        }
        if (changed) {
            scheduleSave();
        }
    }

    // Persistence ------------------------------------------------------------

    private void scheduleSave() {
        if (!loaded || saveScheduled || wiped) {
            return;
        }
        saveScheduled = true;
        AndroidUtilities.runOnUIThread(saveRunnable, 400L);
    }

    private void save() {
        saveScheduled = false;
        if (!loaded || wiped || NovaDecoyState.isActive()) {
            return;
        }
        NovaReadStatusStore.State snapshot = new NovaReadStatusStore.State(state.getOwnerId());
        snapshot.setBaselineDate(state.getBaselineDate());
        for (Map.Entry<Long, Integer> rule : state.getRules().entrySet()) {
            snapshot.setRule(rule.getKey(), rule.getValue());
        }
        Utilities.globalQueue.postRunnable(() -> {
            if (!wiped && !store.save(snapshot)) {
                // "Turn off permanently" is a promise about the next start too,
                // so a failed write is reported rather than left to look done.
                FileLog.e("novagram: read status rules could not be sealed");
            }
        });
    }
}
