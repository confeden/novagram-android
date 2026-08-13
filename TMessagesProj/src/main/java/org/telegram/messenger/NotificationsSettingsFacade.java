package org.telegram.messenger;

import static org.telegram.messenger.NotificationsController.TYPE_PRIVATE;
import static org.telegram.messenger.NotificationsController.TYPE_REACTIONS_MESSAGES;
import static org.telegram.messenger.NotificationsController.TYPE_REACTIONS_STORIES;

import android.content.SharedPreferences;

import org.telegram.messenger.novagram.privacy.NovaNotificationPrivacy;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.NotificationsSoundActivity;

public class NotificationsSettingsFacade {

    public final static String PROPERTY_NOTIFY = "notify2_";
    public final static String PROPERTY_CUSTOM = "custom_";
    public final static String PROPERTY_NOTIFY_UNTIL = "notifyuntil_";
    public final static String PROPERTY_CONTENT_PREVIEW = "content_preview_";
    public final static String PROPERTY_SILENT  = "silent_";
    public final static String PROPERTY_STORIES_NOTIFY = "stories_";

    /**
     * NovaGram: the per-dialog {@code show_previews} as the server holds it.
     *
     * <p>Deliberately not {@link #PROPERTY_CONTENT_PREVIEW}: that one is the
     * user's own choice made on this phone and decides what is drawn here.
     * This one answers a different question - whether the server has a
     * per-dialog permission to put the text of a message into a push payload,
     * possibly set from another device. Upstream throws that answer away, which
     * left the fork resending every dialog it had ever touched and still blind
     * to the exceptions it had not.</p>
     *
     * <p>Three-valued on purpose. Absent means the dialog has no settings of its
     * own and is answered for by its scope; the field alone cannot say that,
     * because when bit 0 of the flags is missing {@code show_previews} keeps its
     * Java default of {@code false} and would read as an exception.</p>
     */
    public final static String PROPERTY_SERVER_PREVIEW = "novagram_server_preview_";

    private final int currentAccount;

    public NotificationsSettingsFacade(int currentAccount) {
        this.currentAccount = currentAccount;
    }


    public boolean isDefault(long dialogId, long topicId) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        return false;
    }

    public void clearPreference(long dialogId, long topicId) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        getPreferences().edit()
                .remove(PROPERTY_NOTIFY + key)
                .remove(PROPERTY_CUSTOM + key)
                .remove(PROPERTY_NOTIFY_UNTIL + key)
                .remove(PROPERTY_CONTENT_PREVIEW + key)
                .remove(PROPERTY_SILENT + key)
                .remove(PROPERTY_STORIES_NOTIFY + key)
                .remove(PROPERTY_SERVER_PREVIEW + key)
                .remove(NovaNotificationPrivacy.dialogPushedKey(key))
                .apply();

    }


    public int getProperty(String property, long dialogId, long topicId, int defaultValue) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        if (getPreferences().contains(property + key)) {
            return getPreferences().getInt(property + key, defaultValue);
        }
        key = NotificationsController.getSharedPrefKey(dialogId, 0, true);
        return getPreferences().getInt(property + key, defaultValue);
    }

    public long getProperty(String property, long dialogId, long topicId, long defaultValue) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
        if (getPreferences().contains(property + key)) {
            return getPreferences().getLong(property + key, defaultValue);
        }
        key = NotificationsController.getSharedPrefKey(dialogId, 0, true);
        return getPreferences().getLong(property + key, defaultValue);
    }

    public boolean getProperty(String property, long dialogId, long topicId, boolean defaultValue) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId);
        if (getPreferences().contains(property + key)) {
            return getPreferences().getBoolean(property + key, defaultValue);
        }
        key = NotificationsController.getSharedPrefKey(dialogId, 0);
        return getPreferences().getBoolean(property + key, defaultValue);
    }

    public String getPropertyString(String property, long dialogId, long topicId, String defaultValue) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId);
        if (getPreferences().contains(property + key)) {
            return getPreferences().getString(property + key, defaultValue);
        }
        key = NotificationsController.getSharedPrefKey(dialogId, 0);
        return getPreferences().getString(property + key, defaultValue);
    }


    public void removeProperty(String property, long dialogId, long topicId) {
        String key = NotificationsController.getSharedPrefKey(dialogId, topicId);
        getPreferences().edit().remove(property + key).apply();
    }

    private SharedPreferences getPreferences() {
        return MessagesController.getNotificationsSettings(currentAccount);
    }

    public void applyDialogNotificationsSettings(long dialogId, long topicId, TLRPC.PeerNotifySettings notify_settings) {
        if (notify_settings == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
            MessagesStorage messagesStorage = MessagesStorage.getInstance(currentAccount);
            NotificationsController notificationsController = NotificationsController.getInstance(currentAccount);

            int currentValue = getPreferences().getInt(PROPERTY_NOTIFY + key, -1);
            int currentValue2 = getPreferences().getInt(PROPERTY_NOTIFY_UNTIL + key, 0);
            SharedPreferences.Editor editor = getPreferences().edit();
            boolean updated = false;
            if ((notify_settings.flags & 2) != 0) {
                editor.putBoolean(PROPERTY_SILENT + key, notify_settings.silent);
            } else {
                editor.remove(PROPERTY_SILENT + key);
            }
            if ((notify_settings.flags & 64) != 0) {
                editor.putBoolean(PROPERTY_STORIES_NOTIFY + key, !notify_settings.stories_muted);
            } else {
                editor.remove(PROPERTY_STORIES_NOTIFY + key);
            }
            // NovaGram: remember what the server holds for this dialog, see
            // PROPERTY_SERVER_PREVIEW. Bit 0 is the only thing that separates
            // "this dialog has an exception" from "the field was not on the
            // wire at all"; without it every dialog would read as an exception
            // that forbids previews, and the sweep would find nothing to do.
            NovaNotificationPrivacy.rememberServerPreview(
                    editor, currentAccount, notify_settings, key);

            TLRPC.Dialog dialog = null;
            if (topicId == 0) {
                dialog = messagesController.dialogs_dict.get(dialogId);
            }
            if (dialog != null) {
                dialog.notify_settings = notify_settings;
            }

            if ((notify_settings.flags & 4) != 0) {
                if (notify_settings.mute_until > connectionsManager.getCurrentTime()) {
                    int until = 0;
                    if (notify_settings.mute_until > connectionsManager.getCurrentTime() + 60 * 60 * 24 * 365) {
                        if (currentValue != 2) {
                            updated = true;
                            editor.putInt(PROPERTY_NOTIFY + key, 2);
                            if (dialog != null) {
                                dialog.notify_settings.mute_until = Integer.MAX_VALUE;
                            }
                        }
                    } else {
                        if (currentValue != 3 || currentValue2 != notify_settings.mute_until) {
                            updated = true;
                            editor.putInt(PROPERTY_NOTIFY + key, 3);
                            editor.putInt(PROPERTY_NOTIFY_UNTIL + key, notify_settings.mute_until);
                            if (dialog != null) {
                                dialog.notify_settings.mute_until = until;
                            }
                        }
                        until = notify_settings.mute_until;
                    }
                    if (topicId == 0) {
                        messagesStorage.setDialogFlags(dialogId, ((long) until << 32) | 1);
                        notificationsController.removeNotificationsForDialog(dialogId);
                    }
                } else {
                    if (currentValue != 0 && currentValue != 1) {
                        updated = true;
                        if (dialog != null) {
                            dialog.notify_settings.mute_until = 0;
                        }
                        editor.putInt(PROPERTY_NOTIFY + key, 0);
                    }
                    if (topicId == 0) {
                        messagesStorage.setDialogFlags(dialogId, 0);
                    }
                }
            } else {
                if (currentValue != -1) {
                    updated = true;
                    if (dialog != null) {
                        dialog.notify_settings.mute_until = 0;
                    }
                    editor.remove(PROPERTY_NOTIFY + key);
                }
                if (topicId == 0) {
                    messagesStorage.setDialogFlags(dialogId, 0);
                }
            }
            applySoundSettings(notify_settings.android_sound, editor, dialogId, topicId, 0, false);
            editor.apply();
            // NovaGram: after apply(), so what the sweep reads is already on
            // disk. This is the moment the desktop fork acts on too - a dialog
            // whose settings arrive after the start-up sweep would otherwise
            // keep letting the text into push until the next launch.
            NovaNotificationPrivacy.scheduleDialogSweep(currentAccount);
            if (updated) {
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.notificationsSettingsUpdated);
                });
            }
        });
    }

    public void applySoundSettings(TLRPC.NotificationSound settings, SharedPreferences.Editor editor, long dialogId, long topicId, int globalType, boolean serverUpdate) {
        if (settings == null) {
            return;
        }
        String soundPref;
        String soundPathPref;
        String soundDocPref;
        if (dialogId != 0) {
            String key = NotificationsController.getSharedPrefKey(dialogId, topicId, true);
            soundPref = "sound_" + key;
            soundPathPref = "sound_path_" + key;
            soundDocPref = "sound_document_id_" + key;
        } else {
            if (globalType == NotificationsController.TYPE_GROUP) {
                soundPref = "GroupSound";
                soundDocPref = "GroupSoundDocId";
                soundPathPref = "GroupSoundPath";
            } else if (globalType == NotificationsController.TYPE_STORIES) {
                soundPref = "StoriesSound";
                soundDocPref = "StoriesSoundDocId";
                soundPathPref = "StoriesSoundPath";
            } else if (globalType == TYPE_PRIVATE) {
                soundPref = "GlobalSound";
                soundDocPref = "GlobalSoundDocId";
                soundPathPref = "GlobalSoundPath";
            } else if (globalType == TYPE_REACTIONS_MESSAGES || globalType == TYPE_REACTIONS_STORIES) {
                soundPref = "ReactionSound";
                soundDocPref = "ReactionSoundDocId";
                soundPathPref = "ReactionSoundPath";
            } else {
                soundPref = "ChannelSound";
                soundDocPref = "ChannelSoundDocId";
                soundPathPref = "ChannelSoundPath";
            }
        }

        if (settings instanceof TLRPC.TL_notificationSoundLocal) {
            TLRPC.TL_notificationSoundLocal localSound = (TLRPC.TL_notificationSoundLocal) settings;
            if ("Default".equalsIgnoreCase(localSound.data)) {
                settings = new TLRPC.TL_notificationSoundDefault();
            } else if ("NoSound".equalsIgnoreCase(localSound.data)) {
                settings = new TLRPC.TL_notificationSoundNone();
            } else {
                String path = NotificationsSoundActivity.findRingtonePathByName(localSound.title);
                if (path == null) {
//                    settings = new TLRPC.TL_notificationSoundDefault();
                    return;
                } else {
                    localSound.data = path;
                }
            }
        }

        if (settings instanceof TLRPC.TL_notificationSoundDefault) {
            editor.putString(soundPref, "Default");
            editor.putString(soundPathPref, "Default");
            editor.remove(soundDocPref);
        } else if (settings instanceof TLRPC.TL_notificationSoundNone) {
            editor.putString(soundPref, "NoSound");
            editor.putString(soundPathPref, "NoSound");
            editor.remove(soundDocPref);
        } else if (settings instanceof TLRPC.TL_notificationSoundLocal) {
            TLRPC.TL_notificationSoundLocal localSound = (TLRPC.TL_notificationSoundLocal) settings;
            editor.putString(soundPref, localSound.title);
            editor.putString(soundPathPref, localSound.data);
            editor.remove(soundDocPref);
        } else if (settings instanceof TLRPC.TL_notificationSoundRingtone) {
            TLRPC.TL_notificationSoundRingtone soundRingtone = (TLRPC.TL_notificationSoundRingtone) settings;
            editor.putLong(soundDocPref, soundRingtone.id);
            MediaDataController.getInstance(currentAccount).checkRingtones(true);
            if (serverUpdate && dialogId != 0) {
                editor.putBoolean("custom_" + dialogId, true);
            }
            MediaDataController.getInstance(currentAccount).ringtoneDataStore.getDocument(soundRingtone.id);
        }
    }

    /**
     * Only ever called with dialogs that came straight out of a server answer -
     * the four call sites in MessagesController are the dialog list past the
     * cache branch, the dialog reset, the pinned dialogs and the pinned remote
     * filter. That matters for NovaGram below: a dialog rebuilt from the local
     * database carries {@code flags == 0} and only a mute time, and taking that
     * for "the server has no exception here" would throw away what was known.
     */
    public void setSettingsForDialog(SharedPreferences.Editor editor, TLRPC.Dialog dialog, TLRPC.PeerNotifySettings notify_settings) {
        long dialogId = MessageObject.getPeerId(dialog.peer);

        // NovaGram: see PROPERTY_SERVER_PREVIEW. This is the path most dialogs
        // arrive by, so leaving it out would mean learning the exceptions one
        // opened chat at a time.
        NovaNotificationPrivacy.rememberServerPreview(
                editor, currentAccount, dialog.notify_settings, String.valueOf(dialogId));

        if ((dialog.notify_settings.flags & 2) != 0) {
            editor.putBoolean(PROPERTY_SILENT + dialogId, dialog.notify_settings.silent);
        } else {
            editor.remove(PROPERTY_SILENT + dialogId);
        }
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        if ((dialog.notify_settings.flags & 4) != 0) {
            if (dialog.notify_settings.mute_until > connectionsManager.getCurrentTime()) {
                if (dialog.notify_settings.mute_until > connectionsManager.getCurrentTime() + 60 * 60 * 24 * 365) {
                    editor.putInt(PROPERTY_NOTIFY + dialogId, 2);
                    dialog.notify_settings.mute_until = Integer.MAX_VALUE;
                } else {
                    editor.putInt(PROPERTY_NOTIFY + dialogId, 3);
                    editor.putInt(PROPERTY_NOTIFY_UNTIL + dialogId, dialog.notify_settings.mute_until);
                }
            } else {
                editor.putInt(PROPERTY_NOTIFY + dialogId, 0);
            }
        } else {
            editor.remove(PROPERTY_NOTIFY + dialogId);
        }
    }
}
