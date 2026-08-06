package org.telegram.messenger.novagram.privacy;

import android.content.res.Resources;
import android.text.TextUtils;
import android.util.LongSparseArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/**
 * The account the decoy pretends to be.
 *
 * <p>Everything here is ordinary Telegram data: real {@code TLRPC} users,
 * chats, dialogs and messages. The decoy does not draw an imitation of the
 * client, it hands the client something to draw. That is the whole idea — a
 * hand made screen can only get close to the original, and "almost right" is
 * worse than plainly different, because it is exactly what a Telegram user
 * notices.</p>
 *
 * <p>Generation is deterministic: the same seed produces the same people, the
 * same conversations and the same timestamps on every launch, so the decoy
 * does not rearrange itself under someone who looks at it twice. The seed and
 * the time anchor live in {@link NovaDecoyState}.</p>
 *
 * <p>The name and phone number are the real ones of the destroyed account when
 * they were captured before the wipe. Only the content is invented.</p>
 */
public final class NovaDecoyPersona {
    private static final int MINUTE = 60;
    private static final int HOUR = 60 * MINUTE;
    private static final int DAY = 24 * HOUR;

    private static volatile NovaDecoyPersona instance;

    public final TLRPC.User self;
    public final ArrayList<TLRPC.User> users = new ArrayList<>();
    public final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
    public final ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>();
    /** Newest conversation first, the order the dialog list shows. */
    public final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
    /** Last message of every dialog, what {@code messages.getDialogs} carries. */
    public final ArrayList<TLRPC.Message> topMessages = new ArrayList<>();
    /** Highest identifier handed out, the floor for anything sent later. */
    public int lastMessageId;

    private final LongSparseArray<ArrayList<TLRPC.Message>> history = new LongSparseArray<>();
    private final Random random;
    private final int anchor;
    private Resources resources;
    private int nextMessageId;

    /**
     * A cached copy, for reading identifiers only. Never hand its objects to
     * the client: it would then be holding the decoy's own instances and every
     * change it makes to them would reach back here.
     */
    public static NovaDecoyPersona snapshot() {
        NovaDecoyPersona local = instance;
        if (local == null) {
            synchronized (NovaDecoyPersona.class) {
                local = instance;
                if (local == null) {
                    instance = local = new NovaDecoyPersona();
                }
            }
        }
        return local;
    }

    /**
     * A freshly built copy for one answer.
     *
     * <p>Building is deterministic, so this is equal to every other copy down
     * to the identifiers and timestamps, and it costs about as much as
     * serializing one would. Copying through the wire format is not an option:
     * {@code TL_message} appends the local attach path, which the network
     * deserializer does not read back.</p>
     */
    public static NovaDecoyPersona build() {
        return new NovaDecoyPersona();
    }

    /** Full history of one dialog, newest message first. */
    public ArrayList<TLRPC.Message> historyOf(long dialogId) {
        ArrayList<TLRPC.Message> messages = history.get(dialogId);
        return messages != null ? messages : new ArrayList<>();
    }

    public boolean knowsDialog(long dialogId) {
        return history.indexOfKey(dialogId) >= 0;
    }

    private NovaDecoyPersona() {
        random = new Random(NovaDecoyState.seed());
        anchor = NovaDecoyState.anchor();
        nextMessageId = 4000 + random.nextInt(20000);

        Resources resources = ApplicationLoader.applicationContext.getResources();

        this.resources = resources;
        self = buildSelf(resources);
        users.add(self);

        ArrayList<TLRPC.User> people = buildContacts(resources);
        users.addAll(people);
        for (TLRPC.User person : people) {
            TLRPC.TL_contact contact = new TLRPC.TL_contact();
            contact.user_id = person.id;
            contact.mutual = true;
            contacts.add(contact);
        }

        TLRPC.User colleague = people.get(0);
        TLRPC.User friend = people.get(1);
        TLRPC.User mother = people.get(2);
        TLRPC.User father = people.get(3);
        TLRPC.User repairman = people.get(4);
        TLRPC.User neighbourOne = people.get(5);
        TLRPC.User neighbourTwo = people.get(6);

        // Not a contact: a shop notification account nobody adds to the
        // address book, and every real Telegram has one or two of them.
        TLRPC.User delivery = user(resources.getString(R.string.NovaDecoyServiceDelivery), "", false);
        users.add(delivery);

        TLRPC.Chat family = group(resources.getString(R.string.NovaDecoyGroupFamily), 3, 40 * DAY);
        TLRPC.Chat building = group(resources.getString(R.string.NovaDecoyGroupBuilding), 18, 200 * DAY);
        TLRPC.Chat deals = channel(resources.getString(R.string.NovaDecoyChannelDeals), 130 * DAY);
        chats.add(family);
        chats.add(building);
        chats.add(deals);

        // Ordered newest first, which is also the order the dialog list shows.
        privateDialog(colleague, resources.getStringArray(R.array.NovaDecoyChatColleague),
                42 * MINUTE, 25 * MINUTE, 0);
        groupDialog(family, new TLRPC.User[] { mother, father },
                resources.getStringArray(R.array.NovaDecoyChatFamily),
                3 * HOUR + 20 * MINUTE, 20 * MINUTE, 0);
        privateDialog(delivery, resources.getStringArray(R.array.NovaDecoyChatDelivery),
                7 * HOUR, 9 * HOUR, 1);
        privateDialog(friend, resources.getStringArray(R.array.NovaDecoyChatFriend),
                21 * HOUR, 35 * MINUTE, 0);
        groupDialog(building, new TLRPC.User[] { neighbourOne, neighbourTwo },
                resources.getStringArray(R.array.NovaDecoyChatBuilding),
                2 * DAY + 4 * HOUR, 50 * MINUTE, 0);
        channelDialog(deals, resources.getStringArray(R.array.NovaDecoyChannelPosts),
                3 * DAY + 6 * HOUR, 22 * HOUR, 2);
        privateDialog(repairman, resources.getStringArray(R.array.NovaDecoyChatRepairs),
                6 * DAY + 3 * HOUR, 40 * MINUTE, 0);
        savedMessages(resources.getStringArray(R.array.NovaDecoySavedMessages), 9 * DAY, 2 * DAY);
    }

    // region people and chats

    private TLRPC.User buildSelf(Resources resources) {
        String first = NovaDecoyState.selfFirstName();
        String last = NovaDecoyState.selfLastName();
        if (TextUtils.isEmpty(first)) {
            first = resources.getString(R.string.NovaDecoySelfFirstName);
            last = resources.getString(R.string.NovaDecoySelfLastName);
        }
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = 100000000L + Math.abs(random.nextLong() % 800000000L);
        user.access_hash = random.nextLong();
        user.first_name = first;
        user.last_name = last != null ? last : "";
        user.phone = NovaDecoyState.selfPhone();
        if (TextUtils.isEmpty(user.phone)) {
            // Only when the wipe could not capture the real one, which happens
            // for a marker armed by an older build. An account with no number
            // at all in the settings is more obviously wrong than a made up one.
            user.phone = invent(resources.getString(R.string.NovaDecoyPhonePrefix));
        }
        user.self = true;
        user.flags |= 1 | 2 | 16;
        if (!TextUtils.isEmpty(user.last_name)) {
            user.flags |= 4;
        }
        return user;
    }

    private String invent(String prefix) {
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = 0; i < 10 - prefix.length(); i++) {
            builder.append(i == 0 ? 1 + random.nextInt(9) : random.nextInt(10));
        }
        return builder.toString();
    }

    private ArrayList<TLRPC.User> buildContacts(Resources resources) {
        ArrayList<TLRPC.User> people = new ArrayList<>();
        for (String entry : resources.getStringArray(R.array.NovaDecoyContacts)) {
            int separator = entry.indexOf('|');
            String first = separator >= 0 ? entry.substring(0, separator) : entry;
            String last = separator >= 0 ? entry.substring(separator + 1) : "";
            people.add(user(first, last, true));
        }
        return people;
    }

    private TLRPC.User user(String first, String last, boolean contact) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = 100000000L + Math.abs(random.nextLong() % 800000000L);
        user.access_hash = random.nextLong();
        user.first_name = first;
        user.last_name = last != null ? last : "";
        user.contact = contact;
        user.mutual_contact = contact;
        user.flags |= 1 | 2;
        if (!TextUtils.isEmpty(user.last_name)) {
            user.flags |= 4;
        }
        if (contact) {
            user.phone = neighbouringPhone();
            user.flags |= 16;
        }
        TLRPC.TL_userStatusOffline status = new TLRPC.TL_userStatusOffline();
        status.expires = anchor - (5 * MINUTE + random.nextInt(3 * DAY));
        user.status = status;
        user.flags |= 64;
        return user;
    }

    /**
     * A number shaped like the owner's own: same country and operator part,
     * different subscriber digits. Contacts from another country would be the
     * kind of detail that makes a decoy fall apart under a glance.
     */
    private String neighbouringPhone() {
        String own = self != null ? self.phone : null;
        String prefix = own != null && own.length() > 7
                ? own.substring(0, own.length() - 7)
                : resources.getString(R.string.NovaDecoyPhonePrefix);
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = 0; i < 7; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    private TLRPC.Chat group(String title, int participants, int createdAgo) {
        TLRPC.TL_chat chat = new TLRPC.TL_chat();
        chat.id = 1000000L + Math.abs(random.nextLong() % 900000000L);
        chat.title = title;
        chat.photo = new TLRPC.TL_chatPhotoEmpty();
        chat.participants_count = participants;
        chat.date = anchor - createdAgo;
        chat.version = 1;
        return chat;
    }

    private TLRPC.Chat channel(String title, int createdAgo) {
        TLRPC.TL_channel chat = new TLRPC.TL_channel();
        chat.id = 1000000000L + Math.abs(random.nextLong() % 900000000L);
        chat.access_hash = random.nextLong();
        chat.title = title;
        chat.photo = new TLRPC.TL_chatPhotoEmpty();
        chat.date = anchor - createdAgo;
        chat.broadcast = true;
        chat.participants_count = 400 + random.nextInt(9000);
        chat.flags |= 1 << 13;
        chat.flags |= 1 << 17;
        return chat;
    }

    // endregion

    // region conversations

    private void privateDialog(TLRPC.User peer, String[] script, int lastAgo, int spacing, int unread) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        int date = anchor - lastAgo;
        for (int i = script.length - 1; i >= 0; i--) {
            Line line = Line.parse(script[i]);
            TLRPC.Message message = message(line.text, date, peer.id);
            if (line.speaker == 0) {
                message.out = true;
                message.from_id = peerUser(self.id);
                message.peer_id = peerUser(peer.id);
            } else {
                // Incoming private messages carry the owner as the peer and the
                // other side as the sender: that is how the client works the
                // conversation out.
                message.from_id = peerUser(peer.id);
                message.peer_id = peerUser(self.id);
            }
            message.flags |= 256;
            messages.add(message);
            date -= step(spacing);
        }
        finish(messages, peerUser(peer.id), peer.id, unread, 0);
    }

    private void savedMessages(String[] script, int lastAgo, int spacing) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        int date = anchor - lastAgo;
        for (int i = script.length - 1; i >= 0; i--) {
            Line line = Line.parse(script[i]);
            TLRPC.Message message = message(line.text, date, self.id);
            message.out = true;
            message.from_id = peerUser(self.id);
            message.peer_id = peerUser(self.id);
            message.flags |= 256;
            messages.add(message);
            date -= step(spacing);
        }
        finish(messages, peerUser(self.id), self.id, 0, 0);
    }

    private void groupDialog(TLRPC.Chat chat, TLRPC.User[] participants, String[] script, int lastAgo, int spacing, int unread) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        long dialogId = -chat.id;
        int date = anchor - lastAgo;
        for (int i = script.length - 1; i >= 0; i--) {
            Line line = Line.parse(script[i]);
            TLRPC.Message message = message(line.text, date, dialogId);
            message.peer_id = peerChat(chat.id);
            if (line.speaker == 0) {
                message.out = true;
                message.from_id = peerUser(self.id);
            } else {
                TLRPC.User sender = participants[Math.min(line.speaker, participants.length) - 1];
                message.from_id = peerUser(sender.id);
            }
            message.flags |= 256;
            messages.add(message);
            date -= step(spacing);
        }
        finish(messages, peerChat(chat.id), dialogId, unread, 0);
    }

    private void channelDialog(TLRPC.Chat chat, String[] script, int lastAgo, int spacing, int unread) {
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        long dialogId = -chat.id;
        int date = anchor - lastAgo;
        for (int i = script.length - 1; i >= 0; i--) {
            Line line = Line.parse(script[i]);
            TLRPC.Message message = message(line.text, date, dialogId);
            message.peer_id = peerChannel(chat.id);
            message.post = true;
            message.views = 300 + random.nextInt(5000);
            message.forwards = random.nextInt(20);
            message.flags |= 1024;
            messages.add(message);
            date -= step(spacing);
        }
        finish(messages, peerChannel(chat.id), dialogId, unread, 40 + random.nextInt(400));
    }

    /** Random but reproducible spacing between two messages of one conversation. */
    private int step(int spacing) {
        return MINUTE + random.nextInt(Math.max(2 * MINUTE, spacing));
    }

    private TLRPC.Message message(String text, int date, long dialogId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = text;
        message.date = date;
        message.dialog_id = dialogId;
        return message;
    }

    /**
     * Numbers the conversation, works out what is still unread and publishes it
     * as a dialog. Identifiers grow with time across the whole account, the way
     * Telegram numbers messages outside channels.
     */
    private void finish(ArrayList<TLRPC.Message> messages, TLRPC.Peer peer, long dialogId, int unread, int pts) {
        Collections.sort(messages, new Comparator<TLRPC.Message>() {
            @Override
            public int compare(TLRPC.Message left, TLRPC.Message right) {
                return Integer.compare(left.date, right.date);
            }
        });
        for (TLRPC.Message message : messages) {
            message.id = nextMessageId++;
        }
        lastMessageId = Math.max(lastMessageId, nextMessageId - 1);
        // A gap before the next conversation: real numbering also skips the
        // identifiers of everything that happened elsewhere in between.
        nextMessageId += 1 + random.nextInt(40);

        ArrayList<Integer> incoming = new ArrayList<>();
        int readOutbox = 0;
        for (TLRPC.Message message : messages) {
            if (message.out) {
                readOutbox = message.id;
            } else {
                incoming.add(message.id);
            }
        }
        int unreadCount = Math.min(unread, incoming.size());
        int readInbox;
        if (unreadCount == 0) {
            readInbox = messages.isEmpty() ? 0 : messages.get(messages.size() - 1).id;
        } else {
            readInbox = incoming.get(incoming.size() - unreadCount) - 1;
        }
        for (TLRPC.Message message : messages) {
            message.unread = !message.out && message.id > readInbox;
        }

        TLRPC.Message top = messages.get(messages.size() - 1);

        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.id = dialogId;
        dialog.peer = peer;
        dialog.top_message = top.id;
        dialog.last_message_date = top.date;
        dialog.read_inbox_max_id = readInbox;
        dialog.read_outbox_max_id = readOutbox;
        dialog.unread_count = unreadCount;
        dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
        if (pts != 0) {
            dialog.pts = pts;
            dialog.flags |= 1;
        }

        ArrayList<TLRPC.Message> newestFirst = new ArrayList<>(messages);
        Collections.reverse(newestFirst);

        history.put(dialogId, newestFirst);
        dialogs.add(dialog);
        topMessages.add(top);
    }

    private static TLRPC.Peer peerUser(long id) {
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = id;
        return peer;
    }

    private static TLRPC.Peer peerChat(long id) {
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = id;
        return peer;
    }

    private static TLRPC.Peer peerChannel(long id) {
        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
        peer.channel_id = id;
        return peer;
    }

    // endregion

    /** One "speaker|text" line of a conversation script. */
    private static final class Line {
        final int speaker;
        final String text;

        private Line(int speaker, String text) {
            this.speaker = speaker;
            this.text = text;
        }

        static Line parse(String raw) {
            int separator = raw.indexOf('|');
            if (separator <= 0) {
                return new Line(1, raw);
            }
            int speaker;
            try {
                speaker = Integer.parseInt(raw.substring(0, separator));
            } catch (NumberFormatException e) {
                speaker = 1;
            }
            return new Line(speaker, raw.substring(separator + 1));
        }
    }
}
