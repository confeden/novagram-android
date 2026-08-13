package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

/**
 * Keeping the text of a message out of the push queue.
 *
 * <p>The text that Android shows in a notification is not written by this
 * client: it is written by the Telegram server, packed into the push payload
 * and handed to Google for delivery. The payload itself is encrypted with a key
 * Google does not have, so the text is not readable in transit — but it is
 * still composed on a server, stored in a push queue and carried through a
 * third party. This switch stops that at the source.</p>
 *
 * <p>How: the server is told {@code show_previews = false}, which is the only
 * lever Telegram offers for "do not put the text into push". It then sends
 * {@code MESSAGE_NOTEXT}, which carries the sender's name and nothing else. The
 * text is filled in afterwards from the real message, which this client
 * receives over its own connection anyway — see the redraw in
 * {@code NotificationsController.processNewMessages}.</p>
 *
 * <p>The stock client cannot do this because {@code show_previews} carries two
 * meanings at once: it is sent to the server and it is echoed back into the
 * same local preferences that decide whether the text is drawn here. The two
 * are separated below — the server always hears "no previews", the local
 * {@code EnablePreview*} preferences keep whatever the user chose and keep
 * deciding what appears on this screen.</p>
 *
 * <p>Honest boundaries, and they are written in the setting's description too:
 * {@code show_previews} is an account setting, so switching it off is visible
 * on every other device and in every other client; the text arrives a moment
 * after the notification rather than with it, and under Doze or without network
 * it does not arrive at all; and Google still sees that a push was delivered,
 * when, and how large it was.</p>
 */
public final class NovaNotificationPrivacy {

    /** Nothing has been said to the server by this client yet. */
    public static final int SERVER_PREVIEW_UNKNOWN = 0;
    /** The server has been told to keep the text out of push. */
    public static final int SERVER_PREVIEW_WITHHELD = 1;
    /** The server holds the user's own preview settings, as upstream sends them. */
    public static final int SERVER_PREVIEW_USER = 2;

    /**
     * The per-dialog {@code show_previews} as the server holds it, written by
     * {@link #rememberServerPreview}. See
     * {@code NotificationsSettingsFacade.PROPERTY_SERVER_PREVIEW} for why it is
     * kept apart from {@code content_preview_}.
     */
    private static final String SERVER_PREVIEW_PREFIX = "novagram_server_preview_";

    /**
     * What this client last sent for a dialog and has not heard back about.
     * Cleared by {@link #rememberServerPreview} on any fresh answer from the
     * server, because that answer supersedes what this client believes it sent.
     * Without it a request that never produced an update would be repeated at
     * every start for ever; with it, and with the value above as the real
     * gate, a dialog is sent at most once per value.
     */
    private static final String DIALOG_PUSHED_PREFIX = "novagram_preview_pushed_";

    /**
     * The markers written before the sweep started going by the stored server
     * value held {@code enabled}, not the value that was sent, and the two do
     * not mean the same thing. They are dropped once, so that a dialog whose
     * exception is only now becoming visible is not skipped by a marker left
     * from the blind sweep.
     */
    private static final String MARKER_VERSION_KEY = "novagram_preview_marker_version";
    private static final int MARKER_VERSION = 2;

    /**
     * The global scopes told in one push, see {@link #pushToServer}. All four
     * have to be confirmed before the account is written down as done.
     */
    private static final int SERVER_SCOPES = 4;

    /**
     * Long enough for the connection to be up and the account to be usable at a
     * cold start. Nothing depends on the exact value: the request is idempotent
     * and the state below keeps it from being sent twice.
     */
    private static final long START_DELAY_MS = 15_000L;

    /** How many per-dialog exceptions are re-sent at a time, see pushDialogBatch. */
    private static final int DIALOG_BATCH = 10;
    private static final long DIALOG_BATCH_DELAY_MS = 1_500L;

    /**
     * The account the server has already been asked again for during this run,
     * per slot. One retry is a correction; retrying on every echo would be a
     * loop, because each answer of the server is itself an echo. Keyed by the
     * signed-in user and not by a flag, because a slot is reused: the next
     * account to sign in has never been retried and would otherwise inherit
     * the marker of the previous one and stay unprotected for good.
     */
    private static final long[] retriedForUser = new long[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * Dialogs already sent during this run of the process, per slot. The stored
     * server value only flips once the server sends the change back, so without
     * this every further settings update for the same dialog - a mute, a sound -
     * would fire another request while the first one is still in flight.
     */
    private static final HashSet<String>[] pushedThisRun = newPushedSets();

    /**
     * Who {@link #pushedThisRun} belongs to. Same reason as for
     * {@link #retriedForUser}: a slot is reused, and a dialog identifier is not
     * unique to an account - 777000 is the same number for everyone. Without
     * this the next account to sign in would inherit the previous one's set and
     * silently skip the dialogs it names.
     */
    private static final long[] pushedForUser = new long[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * Bumped whenever the answer being sent changes. A sweep is spread over
     * seconds, and one that outlives its reason has to die rather than finish.
     */
    private static final int[] sweepGeneration = new int[UserConfig.MAX_ACCOUNT_COUNT];

    /** A sweep is pending for this slot; a burst of dialog pages costs one pass. */
    private static final boolean[] sweepScheduled = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /** Long enough to swallow a burst of dialog pages, short enough to be timely. */
    private static final long SWEEP_DELAY_MS = 3_000L;

    @SuppressWarnings("unchecked")
    private static HashSet<String>[] newPushedSets() {
        HashSet<String>[] sets = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
        for (int i = 0; i < sets.length; i++) {
            sets[i] = new HashSet<>();
        }
        return sets;
    }

    private NovaNotificationPrivacy() {
    }

    // Settings ---------------------------------------------------------------

    public static boolean isEnabled(int account) {
        NovaPrivacySettings settings = settingsFor(account);
        if (settings == null) {
            return false;
        }
        try {
            return settings.isFeatureEnabled(NovaPrivacyFeature.NOTIFICATION_TEXT_OFF_SERVERS);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void setEnabled(int account, boolean enabled) {
        NovaPrivacySettings settings = settingsFor(account);
        if (settings == null) {
            return;
        }
        try {
            settings.setFeatureEnabled(NovaPrivacyFeature.NOTIFICATION_TEXT_OFF_SERVERS, enabled);
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        // Both directions have to reach the server. Without this the new value
        // would only be sent the next time the user happens to change some
        // notification setting, and until then the server would keep doing what
        // it was last told — including keeping previews off after the switch
        // was turned back off.
        pushToServer(account, enabled);
    }

    // What the server is told -------------------------------------------------

    /**
     * The value of {@code show_previews} to send. Called from every place that
     * builds {@code updateNotifySettings} or {@code setReactionsNotifySettings}.
     *
     * @param localValue what upstream would have sent — the user's own choice,
     *                   which stays in force for what is drawn on this device.
     */
    public static boolean serverShowPreviews(int account, boolean localValue) {
        return localValue && !isEnabled(account);
    }

    /**
     * Writes an echoed {@code show_previews} into the local preference, unless
     * the switch is on — in which case the echo is this client's own "false"
     * coming back, and storing it would turn the text off on this screen as
     * well, quietly reducing the feature to the stock "disable previews".
     *
     * <p>A {@code true} coming back while the switch is on means the server has
     * not been told yet (a new account, or a request that never arrived), so it
     * is told again from here.</p>
     *
     * <p>A {@code false} is only this client's own value once this client has
     * actually told the server to withhold. Before that it is the user's own
     * choice, made in another client, and it decides what is drawn here — the
     * whole point is to leave that choice alone, not to overrule it.</p>
     */
    public static void putServerPreview(
            SharedPreferences.Editor editor, int account, String key, boolean serverValue) {
        if (editor == null || key == null) {
            return;
        }
        if (!isEnabled(account)) {
            editor.putBoolean(key, serverValue);
            return;
        }
        if (serverValue) {
            requestServerUpdate(account);
        } else if (serverPreviewState(account) != SERVER_PREVIEW_WITHHELD) {
            editor.putBoolean(key, false);
        }
    }

    // Per-dialog exceptions ---------------------------------------------------

    /** The preference that marks a dialog as sent, for a dialog preference key. */
    public static String dialogPushedKey(String key) {
        return DIALOG_PUSHED_PREFIX + key;
    }

    /**
     * Writes down the {@code show_previews} the server just reported for one
     * dialog, into the same editor the rest of that dialog's settings go into.
     *
     * <p>This is the whole point of the per-dialog half. Upstream reads the
     * flags for silence, mute and stories and drops this one, which left the
     * fork with no way to tell a dialog that really holds a permission to put
     * the text into push from one that has no settings of its own — so it
     * resent every dialog it had ever touched, and still could not see an
     * exception somebody had set on another device.</p>
     *
     * <p>Absent means "no settings of its own", and it has to stay absent:
     * sending such a dialog would create a server-side exception where there
     * was none, which is the same mistake the desktop side avoids by refusing
     * to serialise a value with no flags in it.</p>
     */
    public static void rememberServerPreview(
            SharedPreferences.Editor editor,
            int account,
            TLRPC.PeerNotifySettings settings,
            String key) {
        if (editor == null || settings == null || key == null) {
            return;
        }
        // Bit 0 is show_previews. Without it TLRPC leaves the field at the Java
        // default of false, which is indistinguishable from a real "no".
        if ((settings.flags & 1) != 0) {
            editor.putBoolean(SERVER_PREVIEW_PREFIX + key, settings.show_previews);
        } else {
            editor.remove(SERVER_PREVIEW_PREFIX + key);
        }
        // A fresh answer from the server is newer than anything this client
        // believes it sent, so the "sent, awaiting confirmation" mark stops
        // being a reason to stay silent about this dialog. The in-memory set
        // has to forget it for the same reason, or a dialog whose exception was
        // opened again elsewhere would stay skipped until the next cold start -
        // and an Android process lives for days. Posted, because this runs on
        // the global queue while the set belongs to the main thread.
        editor.remove(DIALOG_PUSHED_PREFIX + key);
        AndroidUtilities.runOnUIThread(() -> pushedSetFor(account).remove(key));
    }

    /**
     * Asks for a pass over the per-dialog exceptions once the settings that
     * just arrived are on disk. Coalesced: the dialog list arrives page by
     * page, and one pass after the burst sees everything the pages brought.
     */
    public static void scheduleDialogSweep(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        if (NovaDecoyState.isActive()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (sweepScheduled[account]) {
                return;
            }
            sweepScheduled[account] = true;
            AndroidUtilities.runOnUIThread(() -> {
                sweepScheduled[account] = false;
                try {
                    if (!UserConfig.getInstance(account).isClientActivated()) {
                        return;
                    }
                    pushDialogExceptions(account, isEnabled(account));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }, SWEEP_DELAY_MS);
        });
    }

    // Start-up ---------------------------------------------------------------

    /**
     * Tells the server what it has not been told yet. The switch is on by
     * default, and a default that nobody ever sends is a promise that is not
     * kept: without this, a fresh install would keep receiving the text inside
     * push until the user touched some notification setting.
     */
    public static void start() {
        if (NovaDecoyState.isActive()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                try {
                    if (!UserConfig.getInstance(account).isClientActivated()) {
                        continue;
                    }
                    boolean enabled = isEnabled(account);
                    int wanted = enabled ? SERVER_PREVIEW_WITHHELD : SERVER_PREVIEW_USER;
                    if (serverPreviewState(account) == wanted) {
                        // The global scopes are confirmed, but the per-dialog
                        // exceptions are not a one-off: they become known as
                        // the chat list arrives, so a single pass at a cold
                        // start cannot have seen them all. Dialogs that already
                        // hold the wanted value are skipped, so a repeat costs
                        // nothing when there is nothing new - which, unlike
                        // before, is the usual case.
                        pushDialogExceptions(account, enabled);
                        continue;
                    }
                    pushToServer(account, enabled);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }, START_DELAY_MS);
    }

    /**
     * Re-sends the current value after the server turned out not to know it.
     * This is also what covers a fresh sign-in: {@link #start()} runs while the
     * application is loading, before there is an account to tell anything to,
     * while the settings the server sends right after the login pass through
     * here.
     */
    private static void requestServerUpdate(int account) {
        if (account < 0 || account >= retriedForUser.length) {
            return;
        }
        long ownerId = ownerIdFor(account);
        if (ownerId == 0 || retriedForUser[account] == ownerId) {
            return;
        }
        retriedForUser[account] = ownerId;
        pushToServer(account, isEnabled(account));
    }

    /**
     * Sends the notification settings again so that the new {@code show_previews}
     * reaches the server. The global scopes are the ones that matter; a dialog
     * that carries settings of its own overrides them, and a single such dialog
     * with previews still allowed would keep letting the text through — so the
     * per-dialog exceptions are swept too, by the stored server value rather
     * than by guesswork, see {@link #customisedDialogs}.
     */
    private static void pushToServer(int account, boolean enabled) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // The value being sent has changed, so what was already sent
                // during this run says nothing about what still has to be, and
                // a sweep already in flight has to stop. The per-dialog markers
                // on disk stay: they record a value, and the sweep compares
                // against the value it wants.
                pushedSetFor(account).clear();
                sweepGeneration[account]++;
                NotificationsController controller = NotificationsController.getInstance(account);
                long ownerId = ownerIdFor(account);
                int wanted = enabled ? SERVER_PREVIEW_WITHHELD : SERVER_PREVIEW_USER;
                // Written down for the four scopes below, which are what the
                // server consults for a dialog with no settings of its own, and
                // only once all four have been answered. A flag set at the
                // moment of sending would record an intention: the requests
                // die unsent when the process is killed without network, and
                // the next start would find the account already done and never
                // try again. The per-dialog exceptions are kept apart — they
                // only narrow the remaining gap and are swept at every start.
                int[] left = { SERVER_SCOPES };
                Runnable confirmed = () -> {
                    if (--left[0] > 0) {
                        return;
                    }
                    NovaPrivacySettings settings = settingsFor(account);
                    if (settings != null && ownerId != 0) {
                        settings.setServerPreviewState(wanted, ownerId);
                    }
                    FileLog.d("novagram: notification previews confirmed by server");
                };
                controller.updateServerNotificationsSettings(
                        NotificationsController.TYPE_PRIVATE, confirmed);
                controller.updateServerNotificationsSettings(
                        NotificationsController.TYPE_GROUP, confirmed);
                controller.updateServerNotificationsSettings(
                        NotificationsController.TYPE_CHANNEL, confirmed);
                controller.updateServerNotificationsSettings(
                        NotificationsController.TYPE_REACTIONS_MESSAGES, confirmed);
                pushDialogExceptions(account, enabled);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Starts the sweep of the per-dialog exceptions. Only the dialogs that have
     * not been told this value yet are sent, so this is repeated at every start
     * and costs nothing once there is nothing new.
     */
    private static void pushDialogExceptions(int account, boolean enabled) {
        if (!enabled) {
            // Nothing is sent when the switch goes off, and that is not an
            // oversight - it is the rule the desktop half already keeps
            // (Watcher::pushPeer and Watcher::sweepPeers both return on
            // !_state.withheld). There is no earlier per-dialog value to
            // restore: content_preview_<key> is written only by this phone's
            // own notification screen, so its absence means "not known", not
            // "the user wants previews". Treating it as the latter would send
            // show_previews=true over an exception the user had set on another
            // device and destroy it for the whole account.
            //
            // The global scopes are restored, and that is correct: their echo
            // does come back into EnablePreview*, so there the previous value
            // really is known.
            return;
        }
        dropBlindSweepMarkers(account);
        ArrayList<PendingDialog> dialogs = customisedDialogs(account);
        if (dialogs.isEmpty()) {
            return;
        }
        FileLog.d("novagram: notification previews, dialogs to push " + dialogs.size());
        pushDialogBatch(account, dialogs, 0, sweepGeneration[account]);
    }

    /**
     * The per-dialog exceptions, a few at a time. An account can hold hundreds
     * of dialogs with their own notification settings, and firing that many
     * requests at once earns a flood wait, which would drop exactly the ones
     * that never got through.
     */
    private static void pushDialogBatch(
            int account, ArrayList<PendingDialog> dialogs, int from, int generation) {
        if (from >= dialogs.size()) {
            return;
        }
        if (generation != sweepGeneration[account]) {
            // The switch moved, or the account did, while the batches were
            // spread over the seconds between them. A sweep that outlives the
            // answer it was started for would keep sending it.
            return;
        }
        int to = Math.min(from + DIALOG_BATCH, dialogs.size());
        NotificationsController controller = NotificationsController.getInstance(account);
        for (int i = from; i < to; i++) {
            PendingDialog dialog = dialogs.get(i);
            try {
                String marker = DIALOG_PUSHED_PREFIX + dialog.key;
                // post = false: one refresh of the settings screen per batch is
                // pointless, and nothing on screen depends on these. The mark
                // is written by the answer, so a dialog whose request was lost
                // is picked up again by the sweep at the next start.
                //
                // The value is passed rather than left to be re-read at the
                // moment of sending: otherwise the request and the mark written
                // for it could disagree.
                controller.updateServerNotificationsSettings(
                        dialog.dialogId,
                        dialog.topicId,
                        false,
                        Boolean.FALSE,
                        () -> MessagesController.getNotificationsSettings(account)
                                .edit()
                                .putBoolean(marker, false)
                                .apply());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        AndroidUtilities.runOnUIThread(
                () -> pushDialogBatch(account, dialogs, to, generation),
                DIALOG_BATCH_DELAY_MS);
    }

    /** One dialog waiting to be told that its previews have to go off. */
    private static final class PendingDialog {
        final long dialogId;
        final long topicId;
        final String key;

        PendingDialog(long dialogId, long topicId, String key) {
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.key = key;
        }
    }

    /**
     * The dialogs whose own {@code show_previews} on the server differs from
     * what it has to be.
     *
     * <p>Until 2026-08-10 this walked every dialog preference key instead - the
     * presence of a mute, a sound or a preview choice was taken as proof that
     * the server might be holding a per-dialog permission. That was blind in
     * both directions: it re-sent hundreds of dialogs that had nothing to
     * change, and it could not see an exception set from another device, since
     * such a dialog leaves no key on this phone at all. Now the server value
     * itself is stored as it arrives (see {@link #rememberServerPreview}) and
     * this only picks the dialogs that actually disagree - normally none.</p>
     *
     * <p>Dialogs with no stored value are not touched. That is not an oversight
     * but the same rule the desktop side keeps: a dialog with no settings of
     * its own is answered for by its scope, which has been told, and sending it
     * would create a server-side exception where the user never asked for one.
     * Neither is a dialog whose stored value is already {@code false} - it is
     * what the promise asks for, whoever put it there.</p>
     *
     * <p>Only ever called while the switch is on, so the value to send is
     * always {@code false}. The other direction has no per-dialog half at all,
     * see {@link #pushDialogExceptions}.</p>
     */
    private static ArrayList<PendingDialog> customisedDialogs(int account) {
        ArrayList<PendingDialog> dialogs = new ArrayList<>();
        SharedPreferences preferences = MessagesController.getNotificationsSettings(account);
        Map<String, ?> all = preferences.getAll();
        if (all == null) {
            return dialogs;
        }
        HashSet<String> sentThisRun = pushedSetFor(account);
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(SERVER_PREVIEW_PREFIX)) {
                continue;
            }
            if (!(entry.getValue() instanceof Boolean) || !((Boolean) entry.getValue())) {
                continue;
            }
            String suffix = key.substring(SERVER_PREVIEW_PREFIX.length());
            long[] parsed = parseDialogKey(suffix);
            // An encrypted dialog sends no notify settings at all, so it would
            // never be confirmed and would come back at every start.
            if (parsed == null
                    || parsed[0] == 0
                    || DialogObject.isEncryptedDialog(parsed[0])) {
                continue;
            }
            Object marked = all.get(DIALOG_PUSHED_PREFIX + suffix);
            if (marked instanceof Boolean && !((Boolean) marked)) {
                // Already sent and not heard back about. Any answer from the
                // server clears this, so a lost request is retried at the next
                // start and a confirmed one is never sent twice.
                continue;
            }
            if (!sentThisRun.add(suffix)) {
                continue;
            }
            dialogs.add(new PendingDialog(parsed[0], parsed[1], suffix));
        }
        return dialogs;
    }

    /**
     * Drops the markers left by the blind sweep. They recorded the state of the
     * switch rather than the value that was sent, so keeping them would silence
     * dialogs whose exception has only now become visible.
     */
    private static void dropBlindSweepMarkers(int account) {
        SharedPreferences preferences = MessagesController.getNotificationsSettings(account);
        if (preferences.getInt(MARKER_VERSION_KEY, 0) >= MARKER_VERSION) {
            return;
        }
        try {
            Map<String, ?> all = preferences.getAll();
            SharedPreferences.Editor editor = preferences.edit();
            if (all != null) {
                for (String key : all.keySet()) {
                    if (key != null && key.startsWith(DIALOG_PUSHED_PREFIX)) {
                        editor.remove(key);
                    }
                }
            }
            editor.putInt(MARKER_VERSION_KEY, MARKER_VERSION).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static HashSet<String> pushedSetFor(int account) {
        if (account < 0 || account >= pushedThisRun.length) {
            return new HashSet<>();
        }
        long ownerId = ownerIdFor(account);
        if (pushedForUser[account] != ownerId) {
            pushedThisRun[account].clear();
            pushedForUser[account] = ownerId;
        }
        return pushedThisRun[account];
    }

    /** The signed-in user of this slot, or zero when there is none. */
    private static long ownerIdFor(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0L;
        }
        try {
            UserConfig config = UserConfig.getInstance(account);
            return config.isClientActivated() ? config.getClientUserId() : 0L;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0L;
        }
    }

    /** What this client has confirmed to the server for the account in this slot. */
    private static int serverPreviewState(int account) {
        NovaPrivacySettings settings = settingsFor(account);
        long ownerId = ownerIdFor(account);
        if (settings == null || ownerId == 0) {
            return SERVER_PREVIEW_UNKNOWN;
        }
        try {
            return settings.getServerPreviewState(ownerId);
        } catch (Throwable e) {
            FileLog.e(e);
            return SERVER_PREVIEW_UNKNOWN;
        }
    }

    /** {@code "<dialogId>"} or {@code "<dialogId>_<topicId>"}, as built by
     *  {@code NotificationsController.getSharedPrefKey}. */
    private static long[] parseDialogKey(String suffix) {
        if (suffix == null || suffix.length() == 0) {
            return null;
        }
        // The minus sign of a chat identifier sits at index 0, so the first
        // underscore is always the separator and never part of the number.
        int separator = suffix.indexOf('_');
        try {
            if (separator < 0) {
                return new long[] { Long.parseLong(suffix), 0L };
            }
            return new long[] {
                    Long.parseLong(suffix.substring(0, separator)),
                    Long.parseLong(suffix.substring(separator + 1))
            };
        } catch (NumberFormatException ignored) {
            // Some other preference happens to share the prefix. Not a dialog.
            return null;
        }
    }

    private static NovaPrivacySettings settingsFor(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        try {
            return NovaPrivacySettings.forAccount(context, account);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
