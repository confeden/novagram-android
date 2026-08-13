package org.telegram.messenger.novagram.privacy;

import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

/**
 * Muting one person inside one chat.
 *
 * <p>Blocking someone is a decision about every chat at once, and leaving a
 * group is a decision about everyone in it. This is the missing middle: in a
 * group with more than two people, the messages of one member stop demanding
 * attention — no notification of any kind, and in the conversation itself the
 * message collapses to a single dimmed line that unfolds when tapped. Nothing
 * is hidden from the user, and nothing is told to the server: the rule lives on
 * this device only, and the other side cannot observe it.</p>
 *
 * <p>Private dialogs are excluded on purpose. There the stock mute already says
 * the same thing, and collapsing the only person in the conversation would
 * leave an empty screen.</p>
 *
 * <p>The rules are sealed in {@link NovaMutedMembersStore}. The in-memory image
 * is replaced wholesale rather than mutated, so a reader on any thread — the
 * notification path runs off the main one — always sees a consistent set.</p>
 */
public final class NovaMutedMembers implements NotificationCenter.NotificationCenterDelegate {

    private static final Object INSTANCE_LOCK = new Object();
    private static final NovaMutedMembers[] instances = new NovaMutedMembers[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile boolean wiped;

    /** Writing is debounced: toggling several members in a row is one save. */
    private static final long SAVE_DELAY_MS = 400L;
    /** The Keystore can be busy at a cold start; the rules are not urgent. */
    private static final long RETRY_DELAY_MS = 30_000L;
    /** How long the notification path waits for the rules, see awaitRules. */
    private static final long LOAD_WAIT_MS = 1_500L;

    private final int currentAccount;
    private final NovaMutedMembersStore store;
    private final Runnable saveRunnable = this::save;

    /**
     * Read from any thread, replaced only on the main one. Never null: before
     * the file has been read it is an empty set, and an empty set means "show
     * everything" — the safe default here, unlike the read status rules, where
     * the safe default is to stay quiet.
     */
    private volatile NovaMutedMembersStore.State state = new NovaMutedMembersStore.State(0L);

    private boolean loaded;
    private boolean loading;
    /** Bumped whenever a read in flight stops being about the current account. */
    private int loadGeneration;
    private boolean observing;
    private boolean saveScheduled;

    private NovaMutedMembers(int account) {
        currentAccount = account;
        store = new NovaMutedMembersStore(ApplicationLoader.applicationContext, account);
    }

    public static NovaMutedMembers getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            throw new IllegalArgumentException("Unknown account " + account);
        }
        NovaMutedMembers instance = instances[account];
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                instance = instances[account];
                if (instance == null) {
                    instance = new NovaMutedMembers(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    public static void start() {
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
                NovaMutedMembers instance = instances[account];
                if (instance != null) {
                    instance.stop();
                }
            }
        });
    }

    public void ensureStarted() {
        // The decoy check belongs here and not only in start(): this is the one
        // door in, and a decoy that read the rules would create a store file for
        // an account that is supposed to know nothing about NovaGram.
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
                        .addObserver(this, NotificationCenter.dialogDeleted);
                NotificationCenter.getInstance(currentAccount)
                        .addObserver(this, NotificationCenter.appDidLogout);
            }
            if (!loaded && !loading) {
                load();
            }
        });
    }

    private void stop() {
        AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        saveScheduled = false;
        if (observing) {
            observing = false;
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.dialogDeleted);
            NotificationCenter.getInstance(currentAccount)
                    .removeObserver(this, NotificationCenter.appDidLogout);
        }
        state = new NovaMutedMembersStore.State(0L);
        loaded = false;
        loading = false;
        // Reads and retries already in flight belong to the account that is
        // going away; their results must not land on the next one.
        ++loadGeneration;
    }

    private void load() {
        final long ownerId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (ownerId == 0) {
            // No account in this slot. Marking the engine loaded here would
            // stamp every later write with owner zero, and the next read would
            // throw the file away as somebody else's.
            loading = false;
            return;
        }
        loading = true;
        // A logout, or a second account signing into this slot, invalidates a
        // read that is already in flight - and the delayed retry below, which
        // stop() cannot cancel because each retry is a new lambda.
        final int generation = ++loadGeneration;
        Utilities.globalQueue.postRunnable(() -> {
            final NovaMutedMembersStore.State loadedState = store.load(ownerId);
            AndroidUtilities.runOnUIThread(() -> {
                if (loaded || wiped || generation != loadGeneration) {
                    return;
                }
                if (loadedState.isUnavailable()) {
                    // Keystore was busy. Everything stays visible until the
                    // retry succeeds, which is the harmless direction: a message
                    // shown that should have been dimmed is a nuisance, one
                    // hidden by a failure to read is a message lost.
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!loaded && !wiped && generation == loadGeneration) {
                            load();
                        }
                    }, RETRY_DELAY_MS);
                    return;
                }
                loading = false;
                // Anything toggled while the file was being read wins: it is a
                // decision the user made just now.
                final NovaMutedMembersStore.State pending = state;
                final NovaMutedMembersStore.State merged = loadedState.copy();
                final boolean hadPending = !pending.isEmpty();
                pending.forEachRule(merged::setMutedTrue);
                state = merged;
                loaded = true;
                if (hadPending) {
                    // Those decisions were refused a write while the file was
                    // being read; now that it is read, they have to reach it.
                    scheduleSave();
                }
                notifyRulesChanged();
            });
        });
    }

    // Questions ---------------------------------------------------------------

    /**
     * Whether this member is muted in this chat. Safe to call from any thread
     * and before the rules have been read, when it answers "no".
     */
    public static boolean isMuted(int account, long dialogId, long userId) {
        if (wiped || NovaDecoyState.isActive()) {
            return false;
        }
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || dialogId == 0 || userId == 0) {
            return false;
        }
        NovaMutedMembers instance = instances[account];
        if (instance == null) {
            return false;
        }
        return instance.state.isMuted(dialogId, userId);
    }

    /**
     * The same question, for the notification path, which can ask it before the
     * rules have been read.
     *
     * <p>A push that arrives while the process is still starting used to be
     * answered "not muted" and rang - at every cold start, which is most of the
     * pushes a phone gets. Here the caller waits a moment for the read instead.
     * It runs on the notification queue, never on the main thread, and the wait
     * is short and bounded: a notification a fraction of a second late is a
     * smaller cost than one the user was promised would not arrive at all.</p>
     */
    private static void awaitRules(int account) {
        NovaMutedMembers instance = instances[account];
        if (instance == null || instance.loaded) {
            return;
        }
        instance.ensureStarted();
        final long deadline = SystemClock.elapsedRealtime() + LOAD_WAIT_MS;
        while (!instance.loaded && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Whether anyone at all is muted in this chat, for cheap early exits. */
    public static boolean hasAnyMuted(int account, long dialogId) {
        if (wiped || NovaDecoyState.isActive() || dialogId == 0) {
            return false;
        }
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        NovaMutedMembers instance = instances[account];
        return instance != null && instance.state.hasChat(dialogId);
    }

    /**
     * Whether this message was written by a muted member. One place answers it
     * for both halves of the feature — the notification gate and the collapsed
     * row — so the two can never disagree.
     */
    public static boolean isMutedMessage(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (messageObject.isOut() || messageObject.isOutOwner()) {
            return false;
        }
        long dialogId = messageObject.getDialogId();
        if (!DialogObject.isChatDialog(dialogId)) {
            // Only chats with more than two people. In a private dialog the
            // stock mute already says this, and there is nobody left to read.
            return false;
        }
        long userId = messageObject.getFromChatId();
        if (userId <= 0) {
            // Anonymous admins and channel posts speak as the chat itself.
            // There is no member to mute there.
            return false;
        }
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                && !NovaDecoyState.isActive()
                && !wiped
                && Looper.myLooper() != Looper.getMainLooper()) {
            // Off the main thread this is the notification path; give the rules
            // their moment to arrive rather than answering "not muted" because
            // the process has only just started. See awaitRules.
            awaitRules(account);
        }
        return isMuted(account, dialogId, userId);
    }

    /** Whether the chat is one where a member can be muted at all. */
    public static boolean eligibleChat(TLRPC.Chat chat) {
        return chat != null
                && !ChatObject.isMonoForum(chat)
                && (!ChatObject.isChannel(chat) || chat.megagroup);
    }

    // Changes -----------------------------------------------------------------

    public static void setMuted(int account, long dialogId, long userId, boolean muted) {
        if (wiped || NovaDecoyState.isActive()) {
            return;
        }
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || dialogId == 0 || userId == 0) {
            return;
        }
        getInstance(account).change(dialogId, userId, muted);
    }

    private void change(long dialogId, long userId, boolean muted) {
        AndroidUtilities.runOnUIThread(() -> {
            if (wiped) {
                return;
            }
            NovaMutedMembersStore.State updated = state.copy();
            updated.setMuted(dialogId, userId, muted);
            state = updated;
            // Started after the change, not before: the answer above is the
            // user's and must not be lost if the file is still being read.
            ensureStarted();
            scheduleSave();
            notifyRulesChanged();
        });
    }

    private void scheduleSave() {
        // Never before the file has been read. Until then the in-memory image
        // is empty and stamped with owner 0, and writing it would replace every
        // rule on disk with the one just made - which the next read would then
        // throw away whole, seeing a blob that belongs to nobody.
        if (!loaded || saveScheduled || wiped) {
            return;
        }
        saveScheduled = true;
        AndroidUtilities.runOnUIThread(saveRunnable, SAVE_DELAY_MS);
    }

    private void save() {
        saveScheduled = false;
        if (!loaded || wiped) {
            return;
        }
        // A snapshot, because the write happens on another thread and the user
        // can keep toggling members while it runs.
        NovaMutedMembersStore.State snapshot = state.copy();
        Utilities.globalQueue.postRunnable(() -> {
            if (wiped) {
                return;
            }
            if (!store.save(snapshot)) {
                FileLog.e("novagram: muted members could not be sealed");
            }
        });
    }

    private void notifyRulesChanged() {
        NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.novaMutedMembersUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            stop();
            Utilities.globalQueue.postRunnable(store::destroy);
        } else if (id == NotificationCenter.dialogDeleted) {
            if (args.length > 0 && args[0] instanceof Long) {
                long dialogId = (Long) args[0];
                NovaMutedMembersStore.State current = state;
                if (current.hasChat(dialogId)) {
                    // A rule that outlives its conversation would mute a
                    // stranger in whatever chat takes that identifier next.
                    NovaMutedMembersStore.State updated = current.copy();
                    updated.forgetChat(dialogId);
                    state = updated;
                    scheduleSave();
                }
            }
        }
    }
}
