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
     * Prefixes of the per-dialog notification preferences. Their presence means
     * this client has at some point sent {@code updateNotifySettings} for that
     * dialog, and every such request carries {@code show_previews} — so the
     * server may still be holding a per-dialog permission to put the text in.
     */
    private static final String[] DIALOG_KEY_PREFIXES = {
            "content_preview_", "notify2_", "silent_", "stories_"
    };

    /**
     * Marks a dialog whose own {@code show_previews} has been pushed, and with
     * which value. Kept in the notification preferences, next to the keys the
     * list of dialogs is derived from, so that the sweep can be repeated at
     * every start without sending the same hundreds of requests again: the
     * dialogs whose settings had not arrived yet the first time are exactly
     * the ones this has to catch.
     */
    private static final String DIALOG_PUSHED_PREFIX = "novagram_preview_pushed_";

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
                        // exceptions are not a one-off: the list they are read
                        // from keeps filling up as the chat list arrives, so a
                        // single pass at a cold start cannot have seen it all.
                        // Already pushed dialogs are skipped, so a repeat costs
                        // nothing when there is nothing new.
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
     * reaches the server. The global scopes are the ones that matter; the
     * per-dialog ones are re-sent too, because a dialog whose settings this
     * client has ever changed carries its own {@code show_previews} on the
     * server, and a single such dialog would keep letting the text through.
     */
    private static void pushToServer(int account, boolean enabled) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
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
        ArrayList<long[]> dialogs = customisedDialogs(account, enabled);
        if (dialogs.isEmpty()) {
            return;
        }
        FileLog.d("novagram: notification previews, dialogs to push " + dialogs.size());
        pushDialogBatch(account, dialogs, 0, enabled);
    }

    /**
     * The per-dialog exceptions, a few at a time. An account can hold hundreds
     * of dialogs with their own notification settings, and firing that many
     * requests at once earns a flood wait, which would drop exactly the ones
     * that never got through.
     */
    private static void pushDialogBatch(
            int account, ArrayList<long[]> dialogs, int from, boolean enabled) {
        if (from >= dialogs.size()) {
            return;
        }
        int to = Math.min(from + DIALOG_BATCH, dialogs.size());
        NotificationsController controller = NotificationsController.getInstance(account);
        for (int i = from; i < to; i++) {
            long[] dialog = dialogs.get(i);
            try {
                String marker = DIALOG_PUSHED_PREFIX
                        + NotificationsController.getSharedPrefKey(dialog[0], dialog[1]);
                // post = false: one refresh of the settings screen per batch is
                // pointless, and nothing on screen depends on these. The mark
                // is written by the answer, so a dialog whose request was lost
                // is picked up again by the sweep at the next start.
                controller.updateServerNotificationsSettings(
                        dialog[0],
                        dialog[1],
                        false,
                        () -> MessagesController.getNotificationsSettings(account)
                                .edit()
                                .putBoolean(marker, enabled)
                                .apply());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        AndroidUtilities.runOnUIThread(
                () -> pushDialogBatch(account, dialogs, to, enabled), DIALOG_BATCH_DELAY_MS);
    }

    /**
     * Dialogs this client has ever sent notification settings for, as
     * {@code {dialogId, topicId}}. Read out of the preference keys rather than
     * out of the chat list: the chat list holds only the loaded pages, while a
     * dialog muted a year ago is exactly the one that would be missed.
     */
    private static ArrayList<long[]> customisedDialogs(int account, boolean enabled) {
        ArrayList<long[]> dialogs = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        SharedPreferences preferences = MessagesController.getNotificationsSettings(account);
        Map<String, ?> all = preferences.getAll();
        if (all == null) {
            return dialogs;
        }
        for (String key : all.keySet()) {
            if (key == null) {
                continue;
            }
            for (String prefix : DIALOG_KEY_PREFIXES) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                String suffix = key.substring(prefix.length());
                long[] parsed = parseDialogKey(suffix);
                // An encrypted dialog sends no notify settings at all, so it
                // would never be marked and would come back at every start.
                if (parsed != null
                        && parsed[0] != 0
                        && !DialogObject.isEncryptedDialog(parsed[0])
                        && seen.add(suffix)) {
                    String marker = DIALOG_PUSHED_PREFIX + suffix;
                    boolean done = all.containsKey(marker)
                            && Boolean.valueOf(enabled).equals(all.get(marker));
                    if (!done) {
                        dialogs.add(parsed);
                    }
                }
                break;
            }
        }
        return dialogs;
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
