package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One emergency PIN, every device.
 *
 * <p>The emergency PIN destroys the device it was typed on. Every other client
 * of the same account keeps the messages, the media and the cache the PIN
 * exists to destroy, and stays signed in - which is the whole account, on the
 * tablet in the next room. This carries the one bit "a wipe happened" to them
 * over the only channel every client of an account already shares: Saved
 * Messages.</p>
 *
 * <p><b>The channel.</b> The device under duress writes one 24-character line
 * into Saved Messages and waits for the server to acknowledge it before
 * anything else happens - a wipe that ran first would take the authorization
 * key with it and there would be nothing left to send with. Every other
 * NovaGram client sees the line arrive, recognises it and runs the same
 * destruction locally, sending nothing itself. No server of the fork's own, no
 * push channel, no extra authorization.</p>
 *
 * <p><b>Why writing into Saved Messages is enough of an authorisation.</b> Only
 * a session signed in to this account can write there. Anyone able to do that
 * can already end every other session from the account's own device list; they
 * do not need this to do harm. So the line is not a secret and is not signed
 * with one - it is a message from the account to itself. What it does carry is
 * a check over the account's own id, so that a line copied out of someone
 * else's screenshot means nothing here.</p>
 *
 * <p><b>What keeps it from firing by accident, and from firing again at the
 * next login.</b> Three questions, all of which must answer yes:</p>
 * <ul>
 *     <li>it is this account's own outgoing message in Saved Messages, not a
 *     forward of one, and it matches the grammar and the check exactly.
 *     Twenty-four characters of which eighty bits are random are not typed by
 *     accident;</li>
 *     <li>it is newer than this authorization. The server says when this
 *     session was created, so a line already lying in Saved Messages when the
 *     user signed in here can never fire - which is exactly the state a device
 *     is in after it has wiped itself and been signed in again;</li>
 *     <li>it is newer than the moment this was switched on here, and newer than
 *     the last line this client acted on.</li>
 * </ul>
 *
 * <p><b>What it costs while nothing happens:</b> a comparison of one string
 * length on each arriving message, one small request when the account starts,
 * and one every half hour. Nothing is polled and no timer wakes the CPU by
 * itself - the half-hourly one runs on the shared queue with everything
 * else.</p>
 */
public final class NovaSyncDeauth {

    private static final String GLOBAL_PREFERENCES = "novagram_privacy";
    private static final String KEY_ENABLED = "novagram_sync_deauth";
    private static final String ACCOUNT_PREFERENCES_PREFIX = "novagram_sync_deauth_";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_ENABLED_SINCE = "enabled_since";
    private static final String KEY_LAST_ACTED = "last_acted";

    /**
     * The line. Three characters that say what it is, sixteen that no two
     * devices and no two wipes ever repeat, five that check the other nineteen
     * against the account they belong to. Twenty-four in total, because the
     * promise made in the settings names that number and because a line that
     * fits on one row is one the owner can recognise in their own Saved
     * Messages afterwards.
     */
    private static final int LENGTH = 24;
    private static final String PREFIX = "NG1";
    private static final int NONCE_CHARS = 16;
    private static final int TAG_CHARS = 5;

    /**
     * RFC 4648 base32: no character that another one can be mistaken for, and
     * nothing outside it in the line, so "does this match" is answered by a
     * character range and not by a parser.
     */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final String TAG_CONTEXT = "NovaGram/sync-deauth/v1|";

    /** How far back the one look at Saved Messages goes. */
    private static final int SCAN_LIMIT = 12;

    /**
     * The safety net, and the only thing here that repeats. Everything normally
     * arrives as an update; this covers the one hole updates have - a client
     * offline long enough for the server to refuse to tell it what it missed.
     */
    private static final long RESCAN_INTERVAL_MS = 30L * 60L * 1000L;

    /**
     * How long the device under duress waits for the server to acknowledge the
     * line before it stops waiting. Long enough for a slow network to answer,
     * short enough that whoever is standing over the device does not see a
     * client that has visibly hung. What happens when it passes is a decision
     * and not an oversight: see {@link #broadcastAndWait}.
     */
    private static final long SEND_TIMEOUT_MS = 12_000L;

    /**
     * A line is stamped by the server at the moment it is sent, so a date in
     * the future is not one. Scheduled messages are the reason this is checked:
     * they pass through the same funnel, they are outgoing, and a scheduled
     * line would be a wipe planned in advance rather than one happening now.
     */
    private static final int FUTURE_SKEW = 300;

    private static final int AUTHORIZATION_RETRIES = 3;
    private static final long AUTHORIZATION_RETRY_DELAY_MS = 4_000L;

    private static final NovaSyncDeauth[] instances =
            new NovaSyncDeauth[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * Whether the destruction has already been asked for in this process. The
     * desktop half keeps the same latch in {@code nova_pin.cpp}. Two things
     * need it here: the emergency PIN gate waits up to twelve seconds for the
     * server while its own line is already coming back over the update stream,
     * and a device with two accounts can see a line in both. Without the latch
     * that is a second {@code NovaEmergencyWipe.run()} on top of the first -
     * two recursive sweeps and two Keystore destructions racing each other -
     * and, worse, a {@code System.exit(0)} in the middle of the gate still
     * warning the account's other clients.
     */
    private static final AtomicBoolean wiping = new AtomicBoolean(false);

    /**
     * Claims the destruction for the caller. True exactly once per process.
     */
    public static boolean claimWipe() {
        return wiping.compareAndSet(false, true);
    }

    private final int currentAccount;
    private final SharedPreferences preferences;

    /**
     * Which account this instance was started for. The slot survives a logout
     * - the process does not die - and these objects are never thrown away, so
     * without this an account signing in after another one kept the previous
     * account's {@link #authorized} and would obey a line written before this
     * login. That is the one thing the feature must never do.
     */
    private long ownerId;

    /**
     * When this instance began listening, on the monotonic clock. The anchor
     * below is that moment expressed in server time, which is the only clock
     * worth writing down: before the first exchange with the server ours is
     * whatever the device says, and a device a day slow would anchor a day in
     * the past and obey a line from before the feature existed.
     */
    private long startedAtElapsed;

    /** When this was switched on here. Server time, never the device clock. */
    private int enabledSince;
    /** The newest line already acted on. */
    private int lastActed;
    /**
     * When this authorization was created, straight from the server. The one
     * answer that makes "never fire again after the next login" true without
     * depending on anything this device stores - a device that has just wiped
     * itself keeps nothing at all.
     */
    private int authorized;
    private int authorizationTries;
    private boolean authorizationInFlight;
    private boolean scanning;
    /**
     * A line recognised while the answer above was not there yet. Held, never
     * dropped: the request is on its way, and the wipe waits for it rather than
     * guessing in either direction.
     */
    private int held;
    private boolean acted;
    private boolean rescanScheduled;
    private boolean started;

    private NovaSyncDeauth(int account) {
        currentAccount = account;
        Context context = ApplicationLoader.applicationContext;
        preferences = context == null
                ? null
                : context.getSharedPreferences(
                        ACCOUNT_PREFERENCES_PREFIX + account, Context.MODE_PRIVATE);
    }

    // Settings -----------------------------------------------------------

    /**
     * On by default: the promise the emergency PIN makes is about the account
     * as the person holding the device thinks of it, and one client of it
     * staying signed in with everything on it keeps none of that promise.
     */
    public static boolean isEnabled(Context context) {
        SharedPreferences preferences = globalPreferences(context);
        return preferences == null || preferences.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences preferences = globalPreferences(context);
        if (preferences == null || preferences.getBoolean(KEY_ENABLED, true) == enabled) {
            return;
        }
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            return;
        }
        // Switching it back on must not obey a line written while it was off:
        // the device it came from was destroyed hours ago and this one was
        // told, by its owner, not to follow.
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NovaSyncDeauth instance = instances[account];
            if (instance != null) {
                instance.resetEnabledSince();
            }
        }
    }

    private static SharedPreferences globalPreferences(Context context) {
        Context target = context != null ? context : ApplicationLoader.applicationContext;
        if (target == null) {
            return null;
        }
        Context app = target.getApplicationContext();
        return (app != null ? app : target)
                .getSharedPreferences(GLOBAL_PREFERENCES, Context.MODE_PRIVATE);
    }

    // Receiver -----------------------------------------------------------

    /**
     * Starts the watchers of every signed in account. Safe to call more than
     * once and from any thread; everything below runs on the main thread.
     */
    public static void start() {
        AndroidUtilities.runOnUIThread(() -> {
            if (NovaDecoyState.isActive()) {
                return;
            }
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                try {
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        instance(account).ensureCurrent();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    /**
     * Asked about every message that arrives, live or caught up on after a
     * reconnection. Cheap by construction: everything that is not the line is
     * answered by its length.
     */
    public static void notice(int account, TLRPC.Message message) {
        if (message == null
                || message.message == null
                || message.message.length() != LENGTH
                || !message.out
                || message.fwd_from != null
                || message.action != null) {
            // This account's own outgoing message in Saved Messages, and
            // nothing else. Nobody but a session of this account can write
            // there, which is what makes the line worth obeying at all; a
            // forward is a line carried in by hand, out of an old conversation
            // or out of a screenshot, and says nothing about when.
            return;
        }
        // Read here and not on the main thread: the caller goes on filling in
        // and reusing this object as it walks the update array, so what is
        // handed over is the three values, never the message.
        final String text = message.message;
        final int date = message.date;
        final long dialogId = message.dialog_id != 0
                ? message.dialog_id
                : MessageObject.getDialogId(message);
        AndroidUtilities.runOnUIThread(
                () -> instance(account).consider(dialogId, date, text));
    }

    private static NovaSyncDeauth instance(int account) {
        NovaSyncDeauth result = instances[account];
        if (result == null) {
            result = new NovaSyncDeauth(account);
            instances[account] = result;
        }
        return result;
    }

    /**
     * Makes sure this instance belongs to the account that is signed in right
     * now, starting it if it never was and restarting it from nothing if the
     * slot changed hands. Every path that can act goes through here, because
     * the alternative is obeying another account's watermark.
     */
    private boolean ensureCurrent() {
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (selfId == 0) {
            return false;
        }
        if (started && ownerId == selfId) {
            return true;
        }
        if (started) {
            // The slot was signed out of and signed into again. Everything
            // held here answers about the previous session - above all the
            // authorization date, which is what "never fires again after
            // signing in" rests on.
            started = false;
            authorized = 0;
            authorizationTries = 0;
            enabledSince = 0;
            lastActed = 0;
            held = 0;
            acted = false;
        }
        begin();
        return started;
    }

    private void begin() {
        if (started) {
            return;
        }
        if (NovaDecoyState.isActive(ApplicationLoader.applicationContext)) {
            // The disguise has no account of its own and must never behave like
            // a client that has heard of this at all.
            return;
        }
        if (preferences == null) {
            return;
        }
        long owner = preferences.getLong(KEY_OWNER, 0);
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (selfId == 0) {
            return;
        }
        if (owner != selfId) {
            // The file is named after the account slot, and a slot is reused by
            // whoever signs into it next. A watermark left by the previous
            // account would measure this account's lines against another
            // account's clock.
            enabledSince = 0;
            lastActed = 0;
        } else {
            enabledSince = preferences.getInt(KEY_ENABLED_SINCE, 0);
            lastActed = preferences.getInt(KEY_LAST_ACTED, 0);
        }
        // Deliberately not writing the anchor here. It is set once the
        // server has said what time it is - see requestAuthorized() - and
        // until then nothing is obeyed: a line is held, never dropped, so a
        // wipe that arrives during these first seconds is still carried out.
        started = true;
        ownerId = selfId;
        startedAtElapsed = SystemClock.elapsedRealtime();
        save(selfId);
        requestAuthorized();
        scan();
        scheduleRescan();
    }

    private void resetEnabledSince() {
        if (!started) {
            return;
        }
        enabledSince = serverTime();
        held = 0;
        save(ownerId);
    }

    private void save(long owner) {
        if (preferences == null) {
            return;
        }
        preferences.edit()
                .putLong(KEY_OWNER, owner)
                .putInt(KEY_ENABLED_SINCE, enabledSince)
                .putInt(KEY_LAST_ACTED, lastActed)
                .apply();
    }

    private int serverTime() {
        return ConnectionsManager.getInstance(currentAccount).getCurrentTime();
    }

    private int threshold() {
        return Math.max(Math.max(enabledSince, lastActed), authorized);
    }

    private void consider(long dialogId, int date, String text) {
        if (acted) {
            return;
        }
        if (!isEnabled(ApplicationLoader.applicationContext)
                || NovaDecoyState.isActive(ApplicationLoader.applicationContext)) {
            return;
        }
        // Starts this account if the process was already running when it
        // signed in, which is every first login and every added account.
        if (!ensureCurrent() || dialogId != ownerId) {
            return;
        }
        consider(date, text, ownerId);
    }

    private void consider(int date, String text, long selfId) {
        if (acted
                || date <= 0
                || date > serverTime() + FUTURE_SKEW
                || date <= Math.max(enabledSince, lastActed)) {
            return;
        }
        if (!isSignal(text, selfId)) {
            return;
        }
        if (date > held) {
            held = date;
        }
        maybeAct();
    }

    private void maybeAct() {
        if (acted || held <= 0) {
            return;
        }
        if (authorized <= 0 || enabledSince <= 0) {
            // Nothing is decided without the server's two answers. Refusing
            // would leave the promise unkept; acting would risk a wipe on a
            // line from before this login, or from before the feature was
            // switched on. So the line waits.
            //
            // A line is waiting, so the attempt budget is refilled: it exists
            // to stop a client with no network asking forever, not to give up
            // on a wipe somebody has actually asked for. The retry delay still
            // paces what follows.
            authorizationTries = 0;
            requestAuthorized();
            return;
        }
        if (held <= threshold()) {
            held = 0;
            return;
        }
        acted = true;
        lastActed = held;
        held = 0;
        save(ownerId);
        if (!claimWipe()) {
            // Already being destroyed - by the emergency PIN typed here, or by
            // this same line seen in another account of this device. Running a
            // second sweep over the first is how a wipe half finishes.
            return;
        }
        FileLog.d("novagram: synchronous deauthorization accepted");
        // The same destruction the emergency PIN runs on the device it was
        // typed on, and deliberately without its first half: this client sends
        // nothing, or a room full of NovaGram clients would answer each other
        // in a circle.
        Utilities.globalQueue.postRunnable(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            NovaEmergencyWipe.run(context);
            AndroidUtilities.runOnUIThread(() -> {
                // Same ending as the emergency PIN gate: the process goes, so
                // that nothing that was holding the destroyed account in memory
                // survives into the disguise.
                System.exit(0);
            });
        });
    }

    private void requestAuthorized() {
        if (authorized > 0
                || authorizationInFlight
                || authorizationTries >= AUTHORIZATION_RETRIES) {
            return;
        }
        authorizationTries++;
        authorizationInFlight = true;
        TL_account.getAuthorizations request = new TL_account.getAuthorizations();
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    authorizationInFlight = false;
                    if (response instanceof TL_account.authorizations) {
                        TL_account.authorizations result = (TL_account.authorizations) response;
                        for (int i = 0, count = result.authorizations.size(); i < count; i++) {
                            TLRPC.TL_authorization authorization = result.authorizations.get(i);
                            if (authorization.current) {
                                authorized = authorization.date_created;
                                break;
                            }
                        }
                        // The clock is the server's from here on, so this
                        // is where the anchor is written. Not "now" but the
                        // moment this instance started, carried across on the
                        // monotonic clock: a line that arrived while the
                        // answer was travelling belongs to the time when we
                        // were already listening, and anchoring at "now" would
                        // throw it away.
                        //
                        // Written when there is none - a first run or a fresh
                        // login, where everything already lying in Saved
                        // Messages is older than the promise - and when the
                        // stored one is in the future, which is what a device
                        // with a fast clock wrote before this rule existed.
                        // Never the other way, or switching the feature off
                        // and on again would be undone by the next start.
                        int elapsed = (int) ((SystemClock.elapsedRealtime()
                                - startedAtElapsed) / 1000L);
                        int anchor = serverTime() - elapsed;
                        if (enabledSince <= 0 || enabledSince > anchor) {
                            enabledSince = anchor;
                            save(ownerId);
                        }
                        maybeAct();
                        return;
                    }
                    if (authorizationTries < AUTHORIZATION_RETRIES) {
                        AndroidUtilities.runOnUIThread(
                                this::requestAuthorized, AUTHORIZATION_RETRY_DELAY_MS);
                    }
                }));
    }

    private void scheduleRescan() {
        if (rescanScheduled || acted) {
            return;
        }
        rescanScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            rescanScheduled = false;
            if (acted) {
                return;
            }
            // scan() re-checks which account owns this slot, so a sign-out and
            // a sign-in between two ticks is noticed here as well.
            scan();
            scheduleRescan();
        }, RESCAN_INTERVAL_MS);
    }

    private void scan() {
        if (acted
                || !isEnabled(ApplicationLoader.applicationContext)
                || NovaDecoyState.isActive(ApplicationLoader.applicationContext)) {
            return;
        }
        // Before the guard below and not after it: ensureCurrent() can start
        // this account, and starting it looks at Saved Messages itself, so the
        // guard has to be read after that has happened or the same request
        // goes out twice.
        if (!ensureCurrent() || scanning) {
            return;
        }
        long selfId = ownerId;
        scanning = true;
        TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.limit = SCAN_LIMIT;
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    scanning = false;
                    if (!(response instanceof TLRPC.messages_Messages)) {
                        return;
                    }
                    TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    for (int i = 0, count = result.messages.size(); i < count; i++) {
                        TLRPC.Message message = result.messages.get(i);
                        if (message == null
                                || message.message == null
                                || !message.out
                                || message.fwd_from != null
                                || message.action != null) {
                            continue;
                        }
                        consider(message.date, message.message, selfId);
                    }
                }));
    }

    // The line ------------------------------------------------------------

    private static String makeNonce() {
        byte[] bytes = new byte[NONCE_CHARS];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(NONCE_CHARS);
        for (int i = 0; i < NONCE_CHARS; i++) {
            result.append(ALPHABET.charAt(bytes[i] & 31));
        }
        return result.toString();
    }

    /**
     * Not a signature, and not pretending to be one: the key is the account's
     * own id, which is not a secret. What it buys is worth the five characters
     * - a line out of a screenshot of somebody else's Saved Messages, or one
     * left over from another account in the same slot, is not a line for this
     * account and is refused long before the wipe.
     */
    private static String tag(long userId, String nonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((TAG_CONTEXT + userId + "|" + nonce)
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(TAG_CHARS);
            for (int i = 0; i < TAG_CHARS; i++) {
                result.append(ALPHABET.charAt(hash[i] & 31));
            }
            return result.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    private static String makeSignal(long userId) {
        String nonce = makeNonce();
        String checked = tag(userId, nonce);
        return checked.isEmpty() ? "" : (PREFIX + nonce + checked);
    }

    private static boolean isSignal(String text, long userId) {
        // Ordered so that the cheapest question is asked first: every message
        // that is not this one is answered by its length alone.
        if (text == null || text.length() != LENGTH || !text.startsWith(PREFIX)) {
            return false;
        }
        String body = text.substring(PREFIX.length());
        for (int i = 0, count = body.length(); i < count; i++) {
            if (ALPHABET.indexOf(body.charAt(i)) < 0) {
                return false;
            }
        }
        String checked = tag(userId, body.substring(0, NONCE_CHARS));
        return !checked.isEmpty() && checked.equals(body.substring(NONCE_CHARS));
    }

    // Sender --------------------------------------------------------------

    /**
     * Writes the line into Saved Messages of every signed in account and
     * returns once the server has acknowledged all of them - or once the
     * deadline passes.
     *
     * <p>Blocks the calling thread, which must not be the main one: the caller
     * is the emergency PIN gate, which is already off it and already showing
     * the same "checking" state it shows for an ordinary PIN, so the wait is
     * invisible.</p>
     *
     * <p><b>The deadline is a decision.</b> Waiting for an acknowledgement that
     * is never coming would mean a device seized with no network never destroys
     * anything, and failing to wipe at all is worse than failing to warn the
     * others. So the order is kept - nothing is destroyed until the line is
     * either acknowledged or given up on - and after twelve seconds the
     * destruction goes ahead regardless.</p>
     */
    public static void broadcastAndWait(Context context) {
        if (!isEnabled(context) || NovaDecoyState.isActive(context)) {
            return;
        }
        final AtomicInteger outstanding = new AtomicInteger(0);
        final Object lock = new Object();
        final AtomicBoolean[] answered = new AtomicBoolean[UserConfig.MAX_ACCOUNT_COUNT];
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            answered[account] = new AtomicBoolean(false);
            try {
                if (!UserConfig.getInstance(account).isClientActivated()) {
                    continue;
                }
                long selfId = UserConfig.getInstance(account).getClientUserId();
                String text = makeSignal(selfId);
                if (text.isEmpty()) {
                    continue;
                }
                TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
                request.peer = new TLRPC.TL_inputPeerSelf();
                request.message = text;
                request.random_id = new SecureRandom().nextLong();
                // The booleans only: serializeToStream() derives the flag
                // bits from them, and a bit set by hand here would land on a
                // different field - 128 is clear_draft, not no_webpage.
                request.silent = true;
                request.no_webpage = true;
                outstanding.incrementAndGet();
                final AtomicBoolean once = answered[account];
                // Deliberately a bare request and not SendMessagesHelper: no
                // local message is created, so nothing of this shows on the
                // screen of the device being taken away, and none of the fork's
                // own outgoing hooks - the auto-deletion queue, the
                // night-silent flag, the metadata scrub - is given a chance to
                // rewrite the one line whose bytes have to arrive exactly as
                // they were built.
                ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
                    if (!once.compareAndSet(false, true)) {
                        return;
                    }
                    // A refusal is an answer too. Retrying inside the deadline
                    // would only spend it, and the deadline is the whole retry
                    // budget.
                    synchronized (lock) {
                        outstanding.decrementAndGet();
                        lock.notifyAll();
                    }
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        long deadline = System.currentTimeMillis() + SEND_TIMEOUT_MS;
        synchronized (lock) {
            while (outstanding.get() > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
