package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Auto-deletion of the account's own sent messages.
 *
 * <p>Once the configured period passes, a message is first edited into a single
 * dot and deleted for both sides a minute later. The replacement exists for the
 * other side's screen: a deleted message leaves a visible hole, while a dot that
 * was there for a minute looks like an ordinary correction. A message that can
 * no longer be edited, and any message carrying media, is deleted directly:
 * editing a caption would leave the file itself in place.</p>
 *
 * <p>The queue lives in {@link NovaAutoDeleteStore}, sealed by its own Keystore
 * key, and survives restarts. State is owned by the UI thread; disk and network
 * work happen elsewhere and come back to it.</p>
 */
public final class NovaAutoDelete implements NotificationCenter.NotificationCenterDelegate {
    /** Ordinary polling interval when nothing is due. */
    private static final long TICK_INTERVAL_MS = 60L * 1000L;
    /**
     * Polling interval while overdue entries remain. Without it a manual Erase
     * evidence over hundreds of messages would take hours.
     */
    private static final long BUSY_INTERVAL_MS = 5L * 1000L;
    /** The first tick waits, so a start-up does not open with a burst of requests. */
    private static final long STARTUP_DELAY_MS = 15L * 1000L;
    private static final long SAVE_DEBOUNCE_MS = 400L;
    private static final long RELOAD_DELAY_MS = 30L * 1000L;
    /** At most five operations per tick: the only throttle protecting from flood waits. */
    private static final int PER_TICK = 5;
    /** The replacement has to reach the other side before the message goes. */
    private static final int REPLACE_TO_DELETE_DELAY = 60;
    private static final String REPLACEMENT = ".";

    private static final Object INSTANCE_LOCK = new Object();
    private static final NovaAutoDelete[] instances = new NovaAutoDelete[UserConfig.MAX_ACCOUNT_COUNT];
    /**
     * Set by the emergency wipe. Everything that could put the queue back on
     * disk checks it, because a request in flight when the PIN was entered
     * returns after the files are already gone and would recreate both the
     * sealed file and its Keystore key.
     */
    private static volatile boolean wiped;
    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private final int currentAccount;
    private final NovaAutoDeleteStore store;

    /**
     * Never null: the user can set a per-chat rule before the sealed file has
     * been read, and losing that choice silently would be worse than merging
     * it with the file afterwards.
     */
    private NovaAutoDeleteStore.State state = new NovaAutoDeleteStore.State(0L);
    /** Erase evidence entries waiting for the file: rules do not apply to them. */
    private final ArrayList<NovaAutoDeleteStore.Entry> pending = new ArrayList<>();
    /**
     * Sent messages waiting for the file. Per-chat rules live in that file, so
     * whether these belong in the queue can only be decided once it is read.
     */
    private final ArrayList<NovaAutoDeleteStore.Entry> pendingTracked = new ArrayList<>();
    /** Entries with a request in flight, held by identity: no key can collide. */
    private final HashSet<NovaAutoDeleteStore.Entry> busy = new HashSet<>();
    private boolean loaded;
    private boolean observing;
    private boolean saveScheduled;
    private int floodUntil;
    private long nextTickAt;

    private final Runnable tickRunnable = this::tick;
    private final Runnable saveRunnable = this::save;

    private NovaAutoDelete(int account) {
        currentAccount = account;
        store = new NovaAutoDeleteStore(ApplicationLoader.applicationContext, account);
    }

    /**
     * Starts the engine for every signed-in account. Called from
     * {@code ApplicationLoader.postInitApplication} behind the same gates as the
     * update checker: never before the PIN is entered, never in the decoy.
     */
    public static void start() {
        if (wiped || !NovaPinSession.isUnlocked() || NovaDecoyState.isActive()) {
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

    /**
     * Stops every engine for good and forgets the queue. Called by the
     * emergency wipe before it starts deleting, so that nothing writes the
     * sealed file back after the sweep has passed over it.
     */
    public static void shutdown() {
        wiped = true;
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                NovaAutoDelete instance = instances[account];
                if (instance != null) {
                    instance.stop();
                }
            }
        });
    }

    private void stop() {
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        saveScheduled = false;
        if (observing) {
            observing = false;
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.messageReceivedByServer2);
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.appDidLogout);
        }
        state = new NovaAutoDeleteStore.State(0L);
        pending.clear();
        pendingTracked.clear();
        busy.clear();
        loaded = false;
    }

    /**
     * Drops everything belonging to the account that just signed out. Account
     * slots are reused, so a queue left behind would be applied to whoever
     * signs in next, whose message identifiers mean entirely different messages.
     */
    private void onLoggedOut() {
        stop();
        Utilities.globalQueue.postRunnable(store::destroy);
    }

    public static NovaAutoDelete getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            throw new IllegalArgumentException("Unknown account " + account);
        }
        NovaAutoDelete instance = instances[account];
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                instance = instances[account];
                if (instance == null) {
                    instance = new NovaAutoDelete(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    // Settings ---------------------------------------------------------------

    public static boolean isEnabled(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        return NovaPrivacySettings.forAccount(context, account)
                .isFeatureEnabled(NovaPrivacyFeature.ADVANCED_MESSAGE_RETENTION);
    }

    public static void setEnabled(int account, boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        NovaPrivacySettings.forAccount(context, account)
                .setFeatureEnabled(NovaPrivacyFeature.ADVANCED_MESSAGE_RETENTION, enabled);
        if (enabled) {
            getInstance(account).ensureStarted();
        }
    }

    public static int getPeriodHours(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return NovaPrivacyContract.DEFAULT_RETENTION_HOURS;
        }
        return NovaPrivacySettings.forAccount(context, account).getRemoteRetentionHours();
    }

    public static void setPeriodHours(int account, int hours) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        NovaPrivacySettings.forAccount(context, account).setRemoteRetentionHours(hours);
    }

    private boolean adminChatsExempt() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return true;
        }
        return NovaPrivacySettings.forAccount(context, currentAccount)
                .isFeatureEnabled(NovaPrivacyFeature.ADMIN_CHAT_RETENTION_EXEMPTIONS);
    }

    // Per-chat rules ---------------------------------------------------------

    /** Whether newly sent messages in this dialog are queued at all. */
    public boolean appliesTo(long dialogId) {
        if (!isEnabled(currentAccount) || isSelfDialog(dialogId)) {
            return false;
        }
        switch (getRule(dialogId)) {
            case NovaAutoDeleteStore.RULE_ALWAYS:
                return true;
            case NovaAutoDeleteStore.RULE_NEVER:
                return false;
            default:
                return !hasModerationRights(dialogId);
        }
    }

    public int getRule(long dialogId) {
        return state.getRule(dialogId);
    }

    /**
     * Writes an explicit rule for the dialog. The menu stores the opposite of
     * the current effect rather than toggling the default, because gaining or
     * losing admin rights would otherwise silently change what the user chose.
     *
     * <p>Works before the sealed file has been read: the rule is kept in memory
     * and merged with the file when it arrives. Saving waits for the same
     * moment, because writing first would overwrite the stored queue with an
     * empty one.</p>
     */
    public void setRule(long dialogId, int rule) {
        state.setRule(dialogId, rule);
        ensureStarted();
        scheduleSave();
    }

    /**
     * Formats a remaining time as {@code 3d 23h 16m}. Minutes are always shown,
     * so a message about to go does not read as having no time left at all.
     */
    public static String formatRemaining(int seconds) {
        int left = Math.max(0, seconds);
        int days = left / 86400;
        int hours = (left % 86400) / 3600;
        int minutes = (left % 3600) / 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(LocaleController.formatString(
                    "NovaAutoDeleteDaysShort", R.string.NovaAutoDeleteDaysShort, days));
        }
        if (days > 0 || hours > 0) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(LocaleController.formatString(
                    "NovaAutoDeleteHoursShort", R.string.NovaAutoDeleteHoursShort, hours));
        }
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(LocaleController.formatString(
                "NovaAutoDeleteMinutesShort", R.string.NovaAutoDeleteMinutesShort, minutes));
        return text.toString();
    }

    /** Seconds until the message is destroyed, or 0 when it is not queued. */
    public int dueIn(long dialogId, int messageId) {
        if (state == null) {
            return 0;
        }
        NovaAutoDeleteStore.Entry entry = state.find(dialogId, messageId);
        if (entry == null) {
            return 0;
        }
        int remaining = entry.dueAt - now();
        // An entry that is already due still exists, so it reports a second
        // rather than disappearing from the menu.
        return Math.max(1, remaining);
    }

    // Lifecycle --------------------------------------------------------------

    /**
     * Starts the engine for this account if it is not running yet. Safe to call
     * from anywhere: {@code ApplicationLoader} calls it for accounts present at
     * start-up, and {@code ChatActivity} calls it when a chat is opened, which
     * covers an account that signed in after the process did.
     */
    public void ensureStarted() {
        if (wiped) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (wiped) {
                return;
            }
            if (!observing) {
                observing = true;
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.messageReceivedByServer2);
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.appDidLogout);
            }
            if (!loaded) {
                loadState();
            }
        });
    }

    private void loadState() {
        long ownerId = UserConfig.getInstance(currentAccount).getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            NovaAutoDeleteStore.LoadResult result = store.load(ownerId);
            AndroidUtilities.runOnUIThread(() -> onStateLoaded(result, ownerId));
        });
    }

    private void onStateLoaded(NovaAutoDeleteStore.LoadResult result, long ownerId) {
        if (loaded || wiped) {
            return;
        }
        if (result.getStatus() == NovaAutoDeleteStore.LoadStatus.UNAVAILABLE) {
            // Keystore or storage was busy. Messages sent meanwhile stay in the
            // pending lists, so nothing is lost by waiting.
            AndroidUtilities.runOnUIThread(this::loadState, RELOAD_DELAY_MS);
            return;
        }
        if (result.getStatus() == NovaAutoDeleteStore.LoadStatus.CORRUPT) {
            FileLog.e("novagram: auto-delete queue was corrupt and has been dropped");
        } else if (result.getStatus() == NovaAutoDeleteStore.LoadStatus.FOREIGN) {
            FileLog.e("novagram: auto-delete queue belonged to another account and was dropped");
        }

        NovaAutoDeleteStore.State loadedState = result.getState();
        loadedState.setOwnerId(ownerId);
        // Rules the user set while the file was being read win over the stored
        // ones: they are the newer choice.
        for (Map.Entry<Long, Integer> rule : state.getRules().entrySet()) {
            loadedState.setRule(rule.getKey(), rule.getValue());
        }
        state = loadedState;
        loaded = true;

        // Only now are the per-chat rules known, so messages sent meanwhile are
        // judged here rather than when they arrived.
        for (int i = 0, count = pendingTracked.size(); i < count; i++) {
            NovaAutoDeleteStore.Entry entry = pendingTracked.get(i);
            if (appliesTo(entry.dialogId) && !addEntry(entry)) {
                break;
            }
        }
        pendingTracked.clear();
        for (int i = 0, count = pending.size(); i < count; i++) {
            if (!addEntry(pending.get(i))) {
                break;
            }
        }
        pending.clear();

        scheduleSave();
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        nextTickAt = SystemClock.elapsedRealtime() + STARTUP_DELAY_MS;
        AndroidUtilities.runOnUIThread(tickRunnable, STARTUP_DELAY_MS);
    }

    /** Returns false when the queue is full and nothing more can be stored. */
    private boolean addEntry(NovaAutoDeleteStore.Entry entry) {
        if (state.find(entry.dialogId, entry.messageId) != null) {
            return true;
        }
        if (!state.add(entry)) {
            reportQueueFull();
            return false;
        }
        return true;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            onLoggedOut();
            return;
        }
        if (id != NotificationCenter.messageReceivedByServer2
                || account != currentAccount
                || args == null
                || args.length < 7) {
            return;
        }
        if (Boolean.TRUE.equals(args[6])) {
            // Scheduled messages are not sent yet; they enter the queue when
            // their own confirmation arrives.
            return;
        }
        if (!(args[1] instanceof Integer) || !(args[3] instanceof Long)) {
            return;
        }
        int messageId = (Integer) args[1];
        long dialogId = (Long) args[3];
        TLRPC.Message message = args[2] instanceof TLRPC.Message ? (TLRPC.Message) args[2] : null;
        track(dialogId, messageId, message);
    }

    /**
     * Queues a message that the server has just acknowledged.
     *
     * <p>This is the point where the client identifier becomes the server one,
     * which is why tracking happens here rather than at send time: the queue
     * must hold the identifier the deletion request will use.</p>
     */
    private void track(long dialogId, int messageId, TLRPC.Message message) {
        if (messageId <= 0 || wiped || !isEnabled(currentAccount) || isSelfDialog(dialogId)) {
            return;
        }
        // Per-chat rules are only known once the sealed file has been read, so
        // before that the message is remembered and judged in onStateLoaded.
        // Deciding here would apply the default to a chat the user explicitly
        // marked "keep my messages here".
        if (loaded && !appliesTo(dialogId)) {
            return;
        }
        if (message != null && (!message.out || isService(message))) {
            return;
        }
        int date = message != null && message.date > 0 ? message.date : now();
        int period = NovaPrivacyContract.isValidRetentionHours(getPeriodHours(currentAccount))
                ? getPeriodHours(currentAccount)
                : NovaPrivacyContract.DEFAULT_RETENTION_HOURS;
        NovaAutoDeleteStore.Entry entry = new NovaAutoDeleteStore.Entry(
                dialogId,
                messageId,
                date,
                date + period * 3600,
                NovaAutoDeleteStore.STAGE_REPLACE,
                // A resynchronised confirmation carries no message object, so
                // whether the text can be replaced is unknown: such a message is
                // deleted without the replacement step rather than guessed at.
                message != null && isTextEditable(message) ? NovaAutoDeleteStore.FLAG_EDITABLE : 0
        );
        if (!loaded) {
            pendingTracked.add(entry);
            return;
        }
        enqueue(entry);
    }

    /**
     * Queues messages for immediate destruction on behalf of Erase evidence.
     * That command is an explicit order, so it ignores the global switch and
     * per-chat rules, and it moves messages that were already waiting to now.
     */
    public void eraseNow(long dialogId, List<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty() || wiped) {
            return;
        }
        ensureStarted();
        AndroidUtilities.runOnUIThread(() -> {
            int at = now();
            NovaAutoDeleteStore.Report report = state.startReport(dialogId);
            for (int i = 0, count = messages.size(); i < count; i++) {
                TLRPC.Message message = messages.get(i);
                if (message == null || message.id <= 0 || isService(message)) {
                    continue;
                }
                report.queued++;
                NovaAutoDeleteStore.Entry existing = state.find(dialogId, message.id);
                if (existing != null) {
                    existing.dueAt = at;
                    continue;
                }
                enqueue(new NovaAutoDeleteStore.Entry(
                        dialogId,
                        message.id,
                        message.date > 0 ? message.date : at,
                        at,
                        NovaAutoDeleteStore.STAGE_REPLACE,
                        (isTextEditable(message) ? NovaAutoDeleteStore.FLAG_EDITABLE : 0)
                                | NovaAutoDeleteStore.FLAG_ERASE
                ));
            }
            if (report.queued == 0) {
                state.removeReport(dialogId);
            }
            scheduleSave();
            schedule();
            // Nothing was queued, so nothing will ever finish it: report now.
            if (report.queued == 0) {
                notifyReport(dialogId);
            }
        });
    }

    /** The report of the last Erase evidence run in this chat, or null. */
    public NovaAutoDeleteStore.Report getReport(long dialogId) {
        return state.findReport(dialogId);
    }

    public void markReportShown(long dialogId) {
        NovaAutoDeleteStore.Report report = state.findReport(dialogId);
        if (report != null && !report.shown) {
            report.shown = true;
            scheduleSave();
        }
    }

    /**
     * Closes the report once the chat has no Erase evidence entries left in the
     * queue. Called after every entry leaves it, because the run ends whenever
     * its last message does, which may be minutes later or after a restart.
     */
    private void checkReportFinished(long dialogId) {
        NovaAutoDeleteStore.Report report = state.findReport(dialogId);
        if (report == null || report.finished) {
            return;
        }
        List<NovaAutoDeleteStore.Entry> queue = state.getQueue();
        for (int i = 0, count = queue.size(); i < count; i++) {
            NovaAutoDeleteStore.Entry entry = queue.get(i);
            if (entry.dialogId == dialogId
                    && (entry.flags & NovaAutoDeleteStore.FLAG_ERASE) != 0) {
                return;
            }
        }
        report.finished = true;
        scheduleSave();
        notifyReport(dialogId);
    }

    private static void notifyReport(long dialogId) {
        for (int i = 0, count = listeners.size(); i < count; i++) {
            try {
                listeners.get(i).onNovaEraseReportReady(dialogId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** Told when an Erase evidence run has finished and has a report to show. */
    public interface Listener {
        void onNovaEraseReportReady(long dialogId);
    }

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void enqueue(NovaAutoDeleteStore.Entry entry) {
        if (wiped) {
            return;
        }
        if (!loaded) {
            pending.add(entry);
            return;
        }
        if (!addEntry(entry)) {
            return;
        }
        scheduleSave();
        schedule();
    }

    private void reportQueueFull() {
        // Silence here would read as "everything is queued" while it is not.
        FileLog.e("novagram: auto-delete queue is full at "
                + NovaAutoDeleteStore.MAX_QUEUE_ENTRIES
                + " entries, newer messages are not queued");
    }

    // Scheduling -------------------------------------------------------------

    private void schedule() {
        if (!loaded || wiped) {
            return;
        }
        List<NovaAutoDeleteStore.Entry> queue = state.getQueue();
        if (queue.isEmpty()) {
            AndroidUtilities.cancelRunOnUIThread(tickRunnable);
            nextTickAt = 0L;
            return;
        }
        int at = now();
        boolean overdue = false;
        for (int i = 0, count = queue.size(); i < count; i++) {
            if (queue.get(i).dueAt <= at) {
                overdue = true;
                break;
            }
        }
        long delay = overdue ? BUSY_INTERVAL_MS : TICK_INTERVAL_MS;
        long elapsed = SystemClock.elapsedRealtime();
        // An already scheduled earlier tick is left alone. Rescheduling on every
        // sent message would push the tick further away for as long as the user
        // keeps typing, and overdue messages would never be processed.
        if (nextTickAt > elapsed && nextTickAt <= elapsed + delay) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        nextTickAt = elapsed + delay;
        AndroidUtilities.runOnUIThread(tickRunnable, delay);
    }

    private void tick() {
        nextTickAt = 0L;
        if (!loaded || wiped || NovaDecoyState.isActive()) {
            return;
        }
        int at = now();
        if (floodUntil > at) {
            long delay = (floodUntil - at) * 1000L;
            AndroidUtilities.cancelRunOnUIThread(tickRunnable);
            nextTickAt = SystemClock.elapsedRealtime() + delay;
            AndroidUtilities.runOnUIThread(tickRunnable, delay);
            return;
        }
        ArrayList<NovaAutoDeleteStore.Entry> due = new ArrayList<>();
        List<NovaAutoDeleteStore.Entry> queue = state.getQueue();
        for (int i = 0, count = queue.size(); i < count && due.size() < PER_TICK; i++) {
            NovaAutoDeleteStore.Entry entry = queue.get(i);
            if (entry.dueAt <= at && !busy.contains(entry)) {
                due.add(entry);
            }
        }
        for (int i = 0, count = due.size(); i < count; i++) {
            process(due.get(i));
        }
        schedule();
    }

    private void process(NovaAutoDeleteStore.Entry entry) {
        if (entry.messageId <= 0) {
            // The send never completed, so there is nothing on the server.
            count(entry, Outcome.SKIPPED);
            drop(entry);
            return;
        }
        if (!ensurePeerLoaded(entry)) {
            return;
        }
        if (entry.stage == NovaAutoDeleteStore.STAGE_DELETE
                || !entry.isEditable()
                || !canStillEdit(entry)) {
            erase(entry);
            return;
        }
        replace(entry);
    }

    /**
     * Makes sure the dialog is in the controller cache. {@code getInputPeer}
     * never fails loudly: for an unknown peer it returns one with a zero access
     * hash, and the request dies on the server instead. The chat is therefore
     * pulled from the local database first, and the entry waits one tick.
     */
    private boolean ensurePeerLoaded(NovaAutoDeleteStore.Entry entry) {
        long dialogId = entry.dialogId;
        MessagesController controller = MessagesController.getInstance(currentAccount);
        if (DialogObject.isEncryptedDialog(dialogId)) {
            // Secret chats have their own deletion path and no cloud editing.
            count(entry, Outcome.SKIPPED);
            drop(entry);
            return false;
        }
        if (dialogId < 0) {
            if (controller.getChat(-dialogId) != null) {
                return true;
            }
        } else if (controller.getUser(dialogId) != null) {
            return true;
        }
        busy.add(entry);
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.Chat chat = dialogId < 0
                    ? MessagesStorage.getInstance(currentAccount).getChatSync(-dialogId)
                    : null;
            TLRPC.User user = dialogId > 0
                    ? MessagesStorage.getInstance(currentAccount).getUserSync(dialogId)
                    : null;
            AndroidUtilities.runOnUIThread(() -> {
                busy.remove(entry);
                if (chat != null) {
                    controller.putChat(chat, true);
                } else if (user != null) {
                    controller.putUser(user, true);
                } else {
                    // The dialog is not known locally any more, so the peer this
                    // message belongs to cannot be addressed at all.
                    count(entry, Outcome.SKIPPED);
                    drop(entry);
                }
            });
        });
        return false;
    }

    private boolean canStillEdit(NovaAutoDeleteStore.Entry entry) {
        if (isSelfDialog(entry.dialogId)) {
            return true;
        }
        int limit = MessagesController.getInstance(currentAccount).maxEditTime;
        if (limit <= 0) {
            limit = 3600;
        }
        return now() - entry.date <= limit;
    }

    private void replace(NovaAutoDeleteStore.Entry entry) {
        busy.add(entry);

        TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(entry.dialogId);
        req.id = entry.messageId;
        req.message = REPLACEMENT;
        req.flags |= 2048;
        req.no_webpage = true;
        req.flags |= 2;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            int flood = floodWaitSeconds(error);
            AndroidUtilities.runOnUIThread(() -> {
                busy.remove(entry);
                if (response instanceof TLRPC.Updates) {
                    MessagesController.getInstance(currentAccount)
                            .processUpdates((TLRPC.Updates) response, false);
                }
                if (flood > 0) {
                    // Deleting right now would hit the same wait, so the message
                    // keeps its place in the queue until the server allows it.
                    floodUntil = now() + flood;
                    entry.dueAt = floodUntil;
                    scheduleSave();
                    schedule();
                    return;
                }
                if (error == null) {
                    count(entry, Outcome.REPLACED);
                }
                // Any other edit failure still ends in deletion: the user asked
                // for removal, and the replacement is only a courtesy.
                advance(entry, error == null ? now() + REPLACE_TO_DELETE_DELAY : now());
            });
        });
    }

    private void advance(NovaAutoDeleteStore.Entry entry, int dueAt) {
        entry.stage = NovaAutoDeleteStore.STAGE_DELETE;
        entry.dueAt = dueAt;
        scheduleSave();
        schedule();
    }

    private void erase(NovaAutoDeleteStore.Entry entry) {
        ArrayList<Integer> ids = new ArrayList<>(1);
        ids.add(entry.messageId);
        try {
            MessagesController.getInstance(currentAccount).deleteMessages(
                    ids,
                    null,
                    null,
                    entry.dialogId,
                    0,
                    true,
                    0
            );
            count(entry, Outcome.DELETED);
        } catch (Throwable e) {
            FileLog.e(e);
            count(entry, Outcome.SKIPPED);
        }
        // Dropped immediately: deleteMessages persists its own pending task, so
        // a failed request is retried by the client, not by this queue.
        drop(entry);
    }

    private void drop(NovaAutoDeleteStore.Entry entry) {
        state.remove(entry.dialogId, entry.messageId);
        busy.remove(entry);
        scheduleSave();
        if ((entry.flags & NovaAutoDeleteStore.FLAG_ERASE) != 0) {
            checkReportFinished(entry.dialogId);
        }
    }

    private enum Outcome {
        REPLACED,
        DELETED,
        SKIPPED
    }

    private void count(NovaAutoDeleteStore.Entry entry, Outcome outcome) {
        if ((entry.flags & NovaAutoDeleteStore.FLAG_ERASE) == 0) {
            return;
        }
        NovaAutoDeleteStore.Report report = state.findReport(entry.dialogId);
        if (report == null) {
            return;
        }
        switch (outcome) {
            case REPLACED:
                report.replaced++;
                break;
            case DELETED:
                report.deleted++;
                break;
            default:
                report.skipped++;
                break;
        }
    }

    // Persistence ------------------------------------------------------------

    private void scheduleSave() {
        if (!loaded || saveScheduled || wiped) {
            return;
        }
        saveScheduled = true;
        AndroidUtilities.runOnUIThread(saveRunnable, SAVE_DEBOUNCE_MS);
    }

    private void save() {
        saveScheduled = false;
        if (!loaded || wiped || NovaDecoyState.isActive()) {
            return;
        }
        NovaAutoDeleteStore.State snapshot = new NovaAutoDeleteStore.State(state.getOwnerId());
        for (Map.Entry<Long, Integer> rule : state.getRules().entrySet()) {
            snapshot.setRule(rule.getKey(), rule.getValue());
        }
        List<NovaAutoDeleteStore.Entry> queue = state.getQueue();
        for (int i = 0, count = queue.size(); i < count; i++) {
            NovaAutoDeleteStore.Entry entry = queue.get(i);
            snapshot.add(new NovaAutoDeleteStore.Entry(
                    entry.dialogId,
                    entry.messageId,
                    entry.date,
                    entry.dueAt,
                    entry.stage,
                    entry.flags
            ));
        }
        Utilities.globalQueue.postRunnable(() -> {
            if (wiped) {
                return;
            }
            if (!store.save(snapshot) && BuildVars.LOGS_ENABLED) {
                FileLog.d("novagram: auto-delete queue could not be sealed");
            }
        });
    }

    // Helpers ----------------------------------------------------------------

    private int now() {
        return ConnectionsManager.getInstance(currentAccount).getCurrentTime();
    }

    private boolean isSelfDialog(long dialogId) {
        return dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private boolean hasModerationRights(long dialogId) {
        if (dialogId >= 0 || !adminChatsExempt()) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        return chat != null && (chat.creator || ChatObject.hasAdminRights(chat));
    }

    private static boolean isService(TLRPC.Message message) {
        return message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty);
    }

    /** Whether the dot replacement is possible at all for this message. */
    private static boolean isTextEditable(TLRPC.Message message) {
        if (isService(message) || message.fwd_from != null || message.via_bot_id != 0) {
            return false;
        }
        if (message.media != null
                && !(message.media instanceof TLRPC.TL_messageMediaEmpty)
                && !(message.media instanceof TLRPC.TL_messageMediaWebPage)) {
            // Replacing a caption would leave the file, so media is deleted whole.
            return false;
        }
        return message.message != null && message.message.length() > 0;
    }

    private static int floodWaitSeconds(TLRPC.TL_error error) {
        if (error == null || error.text == null || !error.text.startsWith("FLOOD_WAIT_")) {
            return 0;
        }
        try {
            int seconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
            return Math.max(1, seconds);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
