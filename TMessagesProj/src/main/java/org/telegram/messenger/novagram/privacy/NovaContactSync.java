package org.telegram.messenger.novagram.privacy;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.UserConfig;

/**
 * Whether this client uploads the phone's address book to Telegram.
 *
 * <p>Upstream ships {@code UserConfig.syncContacts = true} and only draws the
 * "Sync contacts" checkbox when a <em>second</em> account is being added, so a
 * fresh install sends the address book without ever putting the question. What
 * goes out is {@code TL_inputPhoneContact} — phone number, first name, last
 * name of every entry, in the clear, not hashed — for people who never
 * installed Telegram and never agreed to anything.</p>
 *
 * <h3>New installs only</h3>
 *
 * <p>The default is changed for accounts that have never been configured, and
 * only for those. {@code SharedPreferences.getBoolean(key, fallback)} already
 * gives the three states this needs: a slot that was signed in before has the
 * {@code syncContacts} key written by {@code UserConfig.saveConfig}, so its
 * stored value wins and somebody whose contacts are already synchronised is not
 * flipped by an update. A slot that has never been written falls through to
 * {@link #DEFAULT_FOR_NEW_INSTALL}. Nothing may seed the key on first run —
 * writing it would turn "never asked" into "explicitly agreed".</p>
 *
 * <p>The value is a compile-time constant on purpose: it is read from
 * {@code UserConfig}, which is loaded very early, and an inlined boolean cannot
 * drag this class's initialisation into that path.</p>
 */
public final class NovaContactSync {

    /** What an account slot that has never been configured answers. */
    public static final boolean DEFAULT_FOR_NEW_INSTALL = false;

    private NovaContactSync() {
    }

    public static boolean isEnabled(int account) {
        return UserConfig.getInstance(account).syncContacts;
    }

    /**
     * The way back demanded by I8, and the same work Telegram's own privacy
     * screen does when its checkbox is applied: switching the upload on has to
     * actually run one, or the switch would claim something that only happens
     * at the next restart.
     *
     * <p>Switching it off does not delete what was already uploaded. Telegram
     * keeps the imported contacts server-side and only its own "Delete synced
     * contacts" removes them; saying so is the honest limit under the row.</p>
     */
    public static void setEnabled(int account, boolean enabled) {
        UserConfig config = UserConfig.getInstance(account);
        if (config.syncContacts == enabled) {
            return;
        }
        config.syncContacts = enabled;
        config.saveConfig(false);
        if (enabled && ContactsController.hasContactsPermission()) {
            ContactsController.getInstance(account).forceImportContacts();
        }
    }
}
