package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Stands in for Telegram while the decoy is on.
 *
 * <p>{@link org.telegram.tgnet.ConnectionsManager} hands every request here
 * instead of the network. Answering a handful of them is what lets the real
 * client fill itself with the invented account through its own code: it asks
 * for dialogs, stores them, asks for a history, stores that too. Nothing is
 * written into the database behind the client's back, so the result is a
 * genuinely ordinary Telegram installation whose only lie is the content.</p>
 *
 * <p>Everything not answered is dropped without a callback, exactly as a
 * request that never came back. That is deliberate: replying with an error
 * makes parts of the client retry in a loop, while silence is a state it is
 * built to sit in.</p>
 */
public final class NovaDecoyServer {
    private static final String PREFERENCES = "novagram_decoy";
    private static final String KEY_LAST_MESSAGE_ID = "lastMessageId";

    private static final Object SEND_LOCK = new Object();

    private NovaDecoyServer() {
    }

    /** @return the answer for this request, or {@code null} to drop it. */
    public static TLObject respond(int account, TLObject request) {
        try {
            if (request instanceof TLRPC.TL_messages_getDialogs) {
                return dialogs((TLRPC.TL_messages_getDialogs) request);
            }
            if (request instanceof TLRPC.TL_messages_getHistory) {
                return history((TLRPC.TL_messages_getHistory) request);
            }
            if (request instanceof TLRPC.TL_users_getFullUser) {
                return fullUser((TLRPC.TL_users_getFullUser) request);
            }
            if (request instanceof TLRPC.TL_messages_getFullChat) {
                return fullChat((TLRPC.TL_messages_getFullChat) request);
            }
            if (request instanceof TLRPC.TL_channels_getFullChannel) {
                return fullChannel((TLRPC.TL_channels_getFullChannel) request);
            }
            if (request instanceof TLRPC.TL_contacts_getContacts) {
                return contacts();
            }
            if (request instanceof TLRPC.TL_updates_getState) {
                return state(account);
            }
            if (request instanceof TLRPC.TL_updates_getDifference) {
                return noDifference(account);
            }
            if (request instanceof TLRPC.TL_updates_getChannelDifference) {
                return noChannelDifference((TLRPC.TL_updates_getChannelDifference) request);
            }
            if (request instanceof TLRPC.TL_messages_readHistory) {
                return readAcknowledged(account);
            }
            if (request instanceof TLRPC.TL_messages_sendMessage) {
                return sent(account, (TLRPC.TL_messages_sendMessage) request);
            }
            if (request instanceof TL_account.getAuthorizations) {
                return authorizations();
            }
            if (request instanceof TL_account.getPrivacy) {
                return privacyRules();
            }
        } catch (Throwable ignored) {
            // A decoy that crashes is worse than a decoy that shows less.
        }
        return null;
    }

    private static TLObject dialogs(TLRPC.TL_messages_getDialogs request) {
        NovaDecoyPersona persona = NovaDecoyPersona.build();
        TLRPC.TL_messages_dialogs response = new TLRPC.TL_messages_dialogs();
        boolean firstPage = request.offset_id == 0 && request.offset_date == 0;
        boolean mainFolder = (request.flags & 2) == 0 || request.folder_id == 0;
        if (!firstPage || !mainFolder) {
            // Anything past the first page is the end of the list, and the
            // archive is empty. Both are ordinary answers, not errors.
            return response;
        }
        response.dialogs.addAll(persona.dialogs);
        response.messages.addAll(persona.topMessages);
        response.chats.addAll(persona.chats);
        response.users.addAll(persona.users);
        return response;
    }

    private static TLObject history(TLRPC.TL_messages_getHistory request) {
        NovaDecoyPersona persona = NovaDecoyPersona.build();
        TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages();
        long dialogId = dialogId(request.peer);
        if (dialogId == 0 || !persona.knowsDialog(dialogId)) {
            return response;
        }
        // Newest first, which is the order the client expects back.
        for (TLRPC.Message message : persona.historyOf(dialogId)) {
            if (request.offset_id != 0 && request.add_offset >= 0 && message.id >= request.offset_id) {
                continue;
            }
            if (request.min_id != 0 && message.id <= request.min_id) {
                continue;
            }
            if (request.max_id != 0 && message.id >= request.max_id) {
                continue;
            }
            response.messages.add(message);
            if (request.limit > 0 && response.messages.size() >= request.limit) {
                break;
            }
        }
        response.chats.addAll(persona.chats);
        response.users.addAll(persona.users);
        return response;
    }

    /**
     * Everyone in the decoy is left without the call flags. That is an
     * ordinary state on the real network — it is what a user who turned calls
     * off looks like — and it keeps the call button away from a path that has
     * no server behind it.
     */
    private static TLObject fullUser(TLRPC.TL_users_getFullUser request) {
        NovaDecoyPersona persona = NovaDecoyPersona.build();
        long id = userId(request.id, persona);
        TLRPC.User user = null;
        for (TLRPC.User candidate : persona.users) {
            if (candidate.id == id) {
                user = candidate;
                break;
            }
        }
        if (user == null) {
            return null;
        }
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = id;
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        full.common_chats_count = 0;
        String about = persona.about.get(id);
        if (about != null && about.length() > 0) {
            full.about = about;
            // The bit as well as the field: the client stores this object by
            // serializing it, and the writer only looks at the flag.
            full.flags |= 1 << 1;
        }
        TLRPC.TL_users_userFull response = new TLRPC.TL_users_userFull();
        response.full_user = full;
        response.users.add(user);
        return response;
    }

    private static TLObject fullChat(TLRPC.TL_messages_getFullChat request) {
        NovaDecoyPersona persona = NovaDecoyPersona.build();
        long dialogId = -request.chat_id;
        ArrayList<Long> ids = persona.members.get(dialogId);
        if (request.chat_id == 0 || ids == null || ids.isEmpty()) {
            return null;
        }
        TLRPC.TL_chatParticipants participants = new TLRPC.TL_chatParticipants();
        participants.chat_id = request.chat_id;
        participants.version = 1;
        for (int index = 0; index < ids.size(); index++) {
            if (index == 0) {
                TLRPC.TL_chatParticipantCreator creator = new TLRPC.TL_chatParticipantCreator();
                creator.user_id = ids.get(index);
                participants.participants.add(creator);
            } else {
                TLRPC.TL_chatParticipant participant = new TLRPC.TL_chatParticipant();
                participant.user_id = ids.get(index);
                participant.inviter_id = ids.get(0);
                participant.date = NovaDecoyState.anchor() - 150 * 24 * 60 * 60;
                participants.participants.add(participant);
            }
        }
        TLRPC.TL_chatFull full = new TLRPC.TL_chatFull();
        full.id = request.chat_id;
        full.about = text(persona.about.get(dialogId));
        full.participants = participants;
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        TLRPC.TL_messages_chatFull response = new TLRPC.TL_messages_chatFull();
        response.full_chat = full;
        response.chats.addAll(persona.chats);
        response.users.addAll(persona.users);
        return response;
    }

    private static TLObject fullChannel(TLRPC.TL_channels_getFullChannel request) {
        NovaDecoyPersona persona = NovaDecoyPersona.build();
        long channelId = request.channel != null ? request.channel.channel_id : 0;
        long dialogId = -channelId;
        if (channelId == 0 || !persona.knowsDialog(dialogId)) {
            return null;
        }
        TLRPC.Chat channel = null;
        for (TLRPC.Chat candidate : persona.chats) {
            if (candidate.id == channelId) {
                channel = candidate;
                break;
            }
        }
        if (channel == null) {
            return null;
        }
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = channelId;
        full.about = text(persona.about.get(dialogId));
        full.participants_count = channel.participants_count;
        full.flags |= 1;
        full.chat_photo = new TLRPC.TL_photoEmpty();
        full.notify_settings = new TLRPC.TL_peerNotifySettings();
        // Repeated from the dialog list rather than invented, so the profile
        // of the channel cannot contradict its own row in the list.
        for (TLRPC.Dialog dialog : persona.dialogs) {
            if (dialog.id != dialogId) {
                continue;
            }
            full.read_inbox_max_id = dialog.read_inbox_max_id;
            full.read_outbox_max_id = dialog.read_outbox_max_id;
            full.unread_count = dialog.unread_count;
            full.pts = Math.max(dialog.pts, 1);
        }
        TLRPC.TL_messages_chatFull response = new TLRPC.TL_messages_chatFull();
        response.full_chat = full;
        response.chats.addAll(persona.chats);
        response.users.addAll(persona.users);
        return response;
    }

    private static long userId(TLRPC.InputUser input, NovaDecoyPersona persona) {
        if (input instanceof TLRPC.TL_inputUserSelf) {
            return persona.self.id;
        }
        return input != null ? input.user_id : 0;
    }

    private static String text(String value) {
        return value != null ? value : "";
    }

    private static TLObject contacts() {
        NovaDecoyPersona persona = NovaDecoyPersona.build();
        TLRPC.TL_contacts_contacts response = new TLRPC.TL_contacts_contacts();
        response.contacts.addAll(persona.contacts);
        response.users.addAll(persona.users);
        response.saved_count = persona.contacts.size();
        return response;
    }

    private static TLObject state(int account) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        TLRPC.TL_updates_state response = new TLRPC.TL_updates_state();
        response.pts = Math.max(storage.getLastPtsValue(), 1);
        response.qts = Math.max(storage.getLastQtsValue(), 1);
        response.seq = Math.max(storage.getLastSeqValue(), 1);
        response.date = now();
        response.unread_count = unreadCount();
        return response;
    }

    /**
     * Nothing happened since the client last asked, which is true: there is no
     * server. Answering matters because an unanswered difference leaves the
     * client stuck showing "Updating…" over the chat list.
     */
    private static TLObject noDifference(int account) {
        TLRPC.TL_updates_differenceEmpty response = new TLRPC.TL_updates_differenceEmpty();
        response.date = now();
        response.seq = Math.max(MessagesStorage.getInstance(account).getLastSeqValue(), 1);
        return response;
    }

    /**
     * Same for a channel. Answering also clears the pending task the client
     * writes before asking, which would otherwise be retried at every start.
     */
    private static TLObject noChannelDifference(TLRPC.TL_updates_getChannelDifference request) {
        TLRPC.TL_updates_channelDifferenceEmpty response = new TLRPC.TL_updates_channelDifferenceEmpty();
        response.isFinal = true;
        response.flags |= 1;
        response.pts = Math.max(request.pts, 1);
        return response;
    }

    private static TLObject readAcknowledged(int account) {
        TLRPC.TL_messages_affectedMessages response = new TLRPC.TL_messages_affectedMessages();
        response.pts = Math.max(MessagesStorage.getInstance(account).getLastPtsValue(), 1);
        response.pts_count = 0;
        return response;
    }

    /**
     * Confirms a message the user typed into the decoy. It is stored locally by
     * the client and goes nowhere. Without this the message would keep the
     * sending clock forever, which contradicts a chat list that says the
     * account is connected.
     */
    private static TLObject sent(int account, TLRPC.TL_messages_sendMessage request) {
        TLRPC.TL_updateShortSentMessage response = new TLRPC.TL_updateShortSentMessage();
        response.id = nextMessageId();
        response.date = now();
        response.out = true;
        response.flags |= 2;
        response.pts_count = 1;
        response.pts = MessagesStorage.getInstance(account).getLastPtsValue() + 1;
        return response;
    }

    /**
     * One session, this device, signed in long ago. Without an answer the
     * «Devices» screen in settings spins for ever, which is the most visible
     * place where an unanswered request shows through.
     */
    private static TLObject authorizations() {
        TL_account.authorizations response = new TL_account.authorizations();
        response.authorization_ttl_days = 180;
        TLRPC.TL_authorization session = new TLRPC.TL_authorization();
        session.current = true;
        session.official_app = true;
        // Both the booleans and the bits: nothing serializes these objects on
        // the way to the client, and parts of it read the raw flags. Without
        // this the current device is listed as a second, foreign session.
        session.flags |= 1 | 2;
        session.hash = 0;
        session.device_model = Build.MANUFACTURER + " " + Build.MODEL;
        session.platform = "Android";
        session.system_version = "SDK " + Build.VERSION.SDK_INT;
        session.api_id = 0;
        session.app_name = "Telegram Android";
        session.app_version = appVersion();
        session.date_created = NovaDecoyState.anchor() - 130 * 24 * 60 * 60;
        session.date_active = now();
        // The address is left empty on purpose: an invented one is a claim
        // that can be checked and found wrong, while the country taken from
        // the system locale is the country the device really is in.
        session.ip = "";
        session.country = Locale.getDefault().getDisplayCountry();
        session.region = "";
        response.authorizations.add(session);
        return response;
    }

    /**
     * Every privacy key answers the same: visible to contacts. Without an
     * answer the rows in «Privacy and Security» stay blank, where a real
     * client always shows a value.
     */
    private static TLObject privacyRules() {
        TL_account.privacyRules response = new TL_account.privacyRules();
        response.rules.add(new TLRPC.TL_privacyValueAllowContacts());
        return response;
    }

    private static int nextMessageId() {
        synchronized (SEND_LOCK) {
            SharedPreferences preferences = preferences();
            int floor = NovaDecoyPersona.snapshot().lastMessageId;
            int next = Math.max(preferences.getInt(KEY_LAST_MESSAGE_ID, floor), floor) + 1;
            preferences.edit().putInt(KEY_LAST_MESSAGE_ID, next).apply();
            return next;
        }
    }

    private static String appVersion() {
        try {
            Context context = ApplicationLoader.applicationContext;
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static int unreadCount() {
        int total = 0;
        for (TLRPC.Dialog dialog : NovaDecoyPersona.snapshot().dialogs) {
            total += dialog.unread_count;
        }
        return total;
    }

    private static int now() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    private static long dialogId(TLRPC.InputPeer peer) {
        if (peer == null) {
            return 0;
        }
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return NovaDecoyPersona.snapshot().self.id;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        return peer.user_id;
    }
}
