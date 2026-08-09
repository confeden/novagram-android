package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.Calendar;

/**
 * Night mode: outgoing messages are sent silently between 22:00 and 07:00.
 *
 * <p>Only the recipient's notification sound is affected. The message is
 * delivered as usual, appears in the chat as usual and raises the unread
 * counter as usual — the one thing that does not happen is a sound at three in
 * the morning. The user's own incoming notifications are not touched; their own
 * outgoing "message sent" chirp does fall silent, because Telegram plays it
 * only for messages that are not marked silent.</p>
 *
 * <p>The hour is the local hour of this device. For a scheduled message the
 * hour of its delivery decides, because the flag travels to the server with the
 * schedule and is never recomputed.</p>
 */
public final class NovaNightSilent {
    /** Fixed on both platforms: a user-set window would need the same change on desktop. */
    public static final int FROM_HOUR = 22;
    public static final int TILL_HOUR = 7;

    private NovaNightSilent() {
    }

    /** True while the local clock is inside the night window. */
    public static boolean isSilentHourNow() {
        return isSilentHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }

    private static boolean isSilentHour(int hour) {
        // The window crosses midnight, so it is two ranges joined by "or":
        // 22:00-23:59 today and 00:00-06:59 tomorrow.
        return hour >= FROM_HOUR || hour < TILL_HOUR;
    }

    /**
     * The hour that decides for this message. A scheduled message is judged by
     * the time it will actually arrive, not by the time it was written: the
     * flag travels to the server with the schedule and is never recomputed, so
     * taking the current hour would put a silent stamp on a message due at
     * nine in the morning, and a loud one on a message due at three at night.
     */
    private static boolean silentFor(int scheduleDate) {
        if (scheduleDate <= 0 || scheduleDate == 0x7FFFFFFE) {
            // 0x7FFFFFFE means "send when the person comes online", and there
            // is no way to know when that will be.
            return isSilentHourNow();
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(scheduleDate * 1000L);
        return isSilentHour(calendar.get(Calendar.HOUR_OF_DAY));
    }

    /**
     * Whether a message to this dialog should be sent without a sound.
     * {@code scheduleDate} is 0 for an ordinary message.
     */
    public static boolean isActive(int account, long dialogId, int scheduleDate) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || dialogId == 0 || NovaDecoyState.isActive()) {
            return false;
        }
        NovaPrivacySettings settings = NovaPrivacySettings.global(context);
        if (!settings.isNightSilentEnabled() || !silentFor(scheduleDate)) {
            return false;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            // Secret chats are sent by SecretChatHelper on a path of their own,
            // which never reaches this decision. Named here so the exception is
            // visible rather than accidental.
            return false;
        }
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            boolean broadcast = ChatObject.isChannel(chat) && !chat.megagroup;
            return broadcast
                    ? settings.isNightSilentForChannels()
                    : settings.isNightSilentForGroups();
        }
        // Users, bots and Saved Messages alike: the same rule as on desktop.
        return settings.isNightSilentForUsers();
    }
}
