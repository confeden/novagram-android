package org.telegram.messenger.novagram.privacy;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatsWidgetProvider;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

/**
 * Keeping the text of a message off this screen.
 *
 * <p>The sibling switch, {@link NovaNotificationPrivacy}, is about what the
 * Telegram servers are allowed to compose into a push payload. This one is
 * about what this device is allowed to draw once the message is already here.
 * They are orthogonal on purpose: a user may want the text kept out of push and
 * still shown on the screen, or carried in push and hidden on the screen.</p>
 *
 * <p>What disappears is the text of the message. The sender, the chat and the
 * notification's own avatar stay: they are what tells whether the conversation
 * is worth opening now, and a notification that says nothing at all is a
 * different promise. This is the same choice the desktop fork made.</p>
 *
 * <p>How: upstream already has the whole "previews are off" path, reached from
 * {@code getShortStringForMessage} and {@code getStringForMessage} through one
 * boolean each. Every consumer - the summary, the big text, the inbox lines,
 * the ticker, the MessagingStyle, Android Auto - is fed by those two methods,
 * so hiding at that point covers all of them, including the ones that are easy
 * to forget. Writing a new path would have had to find them all again.</p>
 *
 * <p>Three surfaces compose the message themselves, past both notification
 * composers, and each is closed at its own drawing point: the in-app popup
 * notification, which the popup setting throws on top of the lock screen; the
 * home screen widget; and the catch branch of the Android Auto composer. The
 * popup and the widget were left open until 2026-08-10 and the setting's
 * description said so.</p>
 *
 * <p>Honest boundaries, written in the setting's description too: the sender
 * avatar inside the expanded messaging view goes with the text, because
 * upstream hangs both on the same flag; and nothing here changes what a push
 * payload carried on its way to the device - that is the other switch.</p>
 */
public final class NovaNotificationContent {

    /**
     * Read on the notification path, once per message, so the answer is kept
     * rather than re-derived. There is one process, and the only writer is
     * {@link #setEnabled}, which refreshes it.
     */
    private static volatile Boolean cached;

    private NovaNotificationContent() {
    }

    /** For the notification code, which has no Context at hand. */
    public static boolean isEnabled() {
        return isEnabled(ApplicationLoader.applicationContext);
    }

    public static boolean isEnabled(Context context) {
        if (NovaDecoyState.isActive()) {
            // The decoy has to behave like a plain client that never heard of
            // the fork, and a notification that hides its text is exactly the
            // kind of detail that answers the question the decoy exists to
            // avoid.
            return false;
        }
        Boolean known = cached;
        if (known != null) {
            return known;
        }
        NovaPrivacySettings settings = settingsFor(context);
        if (settings == null) {
            return false;
        }
        try {
            boolean enabled = settings.isFeatureEnabled(
                    NovaPrivacyFeature.NOTIFICATION_TEXT_HIDDEN_LOCALLY);
            cached = enabled;
            return enabled;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        NovaPrivacySettings settings = settingsFor(context);
        if (settings == null) {
            return;
        }
        try {
            settings.setFeatureEnabled(
                    NovaPrivacyFeature.NOTIFICATION_TEXT_HIDDEN_LOCALLY, enabled);
            cached = enabled;
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        redrawShown();
        redrawWidgets();
    }

    /**
     * What is already in the shade was drawn with the previous answer and
     * would go on showing the text. This is the last moment the application
     * can still reach those notifications; once the process is gone they stay
     * as they are.
     */
    private static void redrawShown() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    NotificationsController.getInstance(account).showNotifications();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /**
     * The home screen widget was drawn with the previous answer too, and
     * nothing would ask it again until a message arrives in one of the dialogs
     * it lists — which can be hours. Unlike the shade, a widget is on screen
     * without anyone unlocking anything.
     */
    private static void redrawWidgets() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, ChatsWidgetProvider.class));
            for (int id : ids) {
                manager.notifyAppWidgetViewDataChanged(id, R.id.list_view);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static NovaPrivacySettings settingsFor(Context context) {
        Context resolved = context != null
                ? context
                : ApplicationLoader.applicationContext;
        if (resolved == null) {
            return null;
        }
        try {
            return NovaPrivacySettings.global(resolved);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
