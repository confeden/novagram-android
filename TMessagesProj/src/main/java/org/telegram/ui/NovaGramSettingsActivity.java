package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.novagram.net.NovaDoh;
import org.telegram.messenger.novagram.privacy.NovaAutoDelete;
import org.telegram.messenger.novagram.privacy.NovaContactSync;
import org.telegram.messenger.novagram.privacy.NovaCrashStickers;
import org.telegram.messenger.novagram.privacy.NovaDeviceLock;
import org.telegram.messenger.novagram.privacy.NovaFileNames;
import org.telegram.messenger.novagram.privacy.NovaOutgoingMetadata;
import org.telegram.messenger.novagram.privacy.NovaNotificationContent;
import org.telegram.messenger.novagram.privacy.NovaNotificationPrivacy;
import org.telegram.messenger.novagram.privacy.NovaPinLockPolicy;
import org.telegram.messenger.novagram.privacy.NovaPinSession;
import org.telegram.messenger.novagram.privacy.NovaPinVault;
import org.telegram.messenger.novagram.privacy.NovaPrivacyFeature;
import org.telegram.messenger.novagram.privacy.NovaCallPolicy;
import org.telegram.messenger.novagram.privacy.NovaReadStatus;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;
import org.telegram.messenger.novagram.privacy.NovaTranslationPolicy;
import org.telegram.messenger.novagram.update.NovaUpdateChecker;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * The single home for NovaGram's own settings, kept out of the stock Telegram
 * menus. It is presented from the main settings list and hidden entirely in the
 * decoy, where it must not exist.
 */
public class NovaGramSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_CHECK = 2;
    private static final int VIEW_TYPE_SHADOW = 3;

    private static final int ID_APP_PIN = 1;
    private static final int ID_EMERGENCY_PIN = 2;
    private static final int ID_SCREENSHOTS = 3;
    private static final int ID_UPDATE_CHECK = 4;
    private static final int ID_UPDATE_STATE = 5;
    private static final int ID_UPDATE_NOTES = 6;
    private static final int ID_VERSION = 7;
    private static final int ID_PROJECT = 8;
    private static final int ID_AUTO_DELETE = 9;
    private static final int ID_AUTO_DELETE_PERIOD = 10;
    private static final int ID_READ_STATUS = 11;
    private static final int ID_NIGHT_SILENT = 12;
    private static final int ID_NIGHT_SILENT_USERS = 13;
    private static final int ID_NIGHT_SILENT_GROUPS = 14;
    private static final int ID_NIGHT_SILENT_CHANNELS = 15;
    private static final int ID_PUSH_PREVIEW = 16;
    private static final int ID_HIDE_CONTENT = 17;
    private static final int ID_APP_PIN_OFF = 18;
    private static final int ID_PIN_POLICY = 19;
    private static final int ID_FILE_NAMES = 20;
    private static final int ID_METADATA = 21;
    private static final int ID_CALLS_RELAY = 22;
    private static final int ID_DOH = 23;
    private static final int ID_DEVICE_BINDING = 24;
    private static final int ID_AUTOTRANSLATE = 25;
    private static final int ID_CONTACT_SYNC = 26;
    private static final int ID_CRASH_STICKERS = 27;

    /** The order the PIN lock options are offered in, strictest first. */
    private static final NovaPinLockPolicy[] PIN_POLICIES = {
            NovaPinLockPolicy.ON_MINIMIZE,
            NovaPinLockPolicy.ON_SCREEN_LOCK,
            NovaPinLockPolicy.INACTIVITY_10M,
            NovaPinLockPolicy.INACTIVITY_60M,
            NovaPinLockPolicy.ON_START,
    };

    /** The periods offered for auto-deletion, in hours. */
    private static final int[] AUTO_DELETE_PERIODS = { 24, 48, 72, 120, 168 };

    private final ArrayList<Item> items = new ArrayList<>();

    private final NovaUpdateChecker.Listener updateListener = () -> {
        if (adapter != null) {
            buildItems();
        }
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NovaSettingsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onItemClick(position, view));

        buildItems();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The PIN screens run as a separate activity, so their state can change
        // while this fragment is stopped. Rebuild on return to stay accurate.
        buildItems();
    }

    @Override
    public boolean onFragmentCreate() {
        NovaUpdateChecker.addListener(updateListener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NovaUpdateChecker.removeListener(updateListener);
        super.onFragmentDestroy();
    }

    private void onItemClick(int position, View view) {
        if (position < 0 || position >= items.size() || getParentActivity() == null) {
            return;
        }
        Item item = items.get(position);
        if (item.id == ID_APP_PIN) {
            // No extra: the gate enrolls the application PIN when none is set
            // (which also clears an earlier "continue without PIN").
            getParentActivity().startActivity(new Intent(getParentActivity(), NovaPinGateActivity.class));
        } else if (item.id == ID_APP_PIN_OFF) {
            showDisablePinDialog();
        } else if (item.id == ID_PIN_POLICY) {
            showPinPolicyDialog();
        } else if (item.id == ID_EMERGENCY_PIN) {
            Intent intent = new Intent(getParentActivity(), NovaPinGateActivity.class);
            intent.putExtra(NovaPinGateActivity.EXTRA_SETUP_EMERGENCY, true);
            getParentActivity().startActivity(intent);
        } else if (item.id == ID_DEVICE_BINDING) {
            boolean enabled = !NovaDeviceLock.isEnabled();
            ((TextCheckCell) view).setChecked(enabled);
            Context context = getParentActivity();
            // Off the main thread. Switching this on can create the Keystore
            // key, which costs hundreds of milliseconds and more again when the
            // device tries StrongBox first, and it then rewrites the config for
            // every running account. The row is ticked at once so the tap still
            // feels immediate; the subtitle catches up when the work is done,
            // because it says what the binding is really doing and that is not
            // the same answer as the switch.
            Utilities.globalQueue.postRunnable(() -> {
                NovaDeviceLock.setEnabled(context, enabled);
                AndroidUtilities.runOnUIThread(this::buildItems);
            });
        } else if (item.id == ID_SCREENSHOTS) {
            NovaPrivacySettings settings = NovaPrivacySettings.global(getParentActivity());
            boolean enabled = !settings.isFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION);
            settings.setFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION, enabled);
            ((TextCheckCell) view).setChecked(enabled);
            // The flag on this activity's window is re-evaluated only when it
            // is told to. Without this the switch would take effect at the next
            // start, which is after the screenshot it was meant to prevent.
            LaunchActivity.novaRefreshFlagSecure();
        } else if (item.id == ID_AUTOTRANSLATE) {
            boolean enabled = !NovaTranslationPolicy.isChannelFlagIgnored();
            NovaTranslationPolicy.setChannelFlagIgnored(getParentActivity(), enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_CONTACT_SYNC) {
            boolean enabled = !NovaContactSync.isEnabled(currentAccount);
            NovaContactSync.setEnabled(currentAccount, enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_AUTO_DELETE) {
            boolean enabled = !NovaAutoDelete.isEnabled(currentAccount);
            NovaAutoDelete.setEnabled(currentAccount, enabled);
            ((TextCheckCell) view).setChecked(enabled);
            buildItems();
        } else if (item.id == ID_AUTO_DELETE_PERIOD) {
            showPeriodDialog();
        } else if (item.id == ID_READ_STATUS) {
            boolean enabled = !NovaReadStatus.isEnabled(currentAccount);
            NovaReadStatus.setEnabled(currentAccount, enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_CALLS_RELAY) {
            boolean enabled = !NovaCallPolicy.relayOnly(getParentActivity());
            NovaCallPolicy.setRelayOnly(getParentActivity(), enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_PUSH_PREVIEW) {
            boolean enabled = !NovaNotificationPrivacy.isEnabled(currentAccount);
            NovaNotificationPrivacy.setEnabled(currentAccount, enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_FILE_NAMES) {
            boolean enabled = !NovaFileNames.isEnabled();
            NovaFileNames.setEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_METADATA) {
            boolean enabled = !NovaOutgoingMetadata.isEnabled();
            NovaOutgoingMetadata.setEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_CRASH_STICKERS) {
            boolean enabled = !NovaCrashStickers.isEnabled();
            NovaCrashStickers.setEnabled(getParentActivity(), enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_HIDE_CONTENT) {
            boolean enabled = !NovaNotificationContent.isEnabled(getContext());
            NovaNotificationContent.setEnabled(getContext(), enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (item.id == ID_NIGHT_SILENT
                || item.id == ID_NIGHT_SILENT_USERS
                || item.id == ID_NIGHT_SILENT_GROUPS
                || item.id == ID_NIGHT_SILENT_CHANNELS) {
            NovaPrivacySettings settings = NovaPrivacySettings.global(getParentActivity());
            boolean enabled;
            if (item.id == ID_NIGHT_SILENT) {
                enabled = !settings.isNightSilentEnabled();
                settings.setNightSilentEnabled(enabled);
            } else if (item.id == ID_NIGHT_SILENT_USERS) {
                enabled = !settings.isNightSilentForUsers();
                settings.setNightSilentForUsers(enabled);
            } else if (item.id == ID_NIGHT_SILENT_GROUPS) {
                enabled = !settings.isNightSilentForGroups();
                settings.setNightSilentForGroups(enabled);
            } else {
                enabled = !settings.isNightSilentForChannels();
                settings.setNightSilentForChannels(enabled);
            }
            ((TextCheckCell) view).setChecked(enabled);
            if (item.id == ID_NIGHT_SILENT) {
                buildItems();
            }
        } else if (item.id == ID_UPDATE_CHECK) {
            boolean enabled = !NovaUpdateChecker.isEnabled(getParentActivity());
            NovaUpdateChecker.setEnabled(getParentActivity(), enabled);
            ((TextCheckCell) view).setChecked(enabled);
            buildItems();
        } else if (item.id == ID_UPDATE_STATE) {
            NovaUpdateChecker.State state = NovaUpdateChecker.getState();
            if (state == NovaUpdateChecker.State.FOUND) {
                NovaUpdateChecker.download();
            } else if (state == NovaUpdateChecker.State.DOWNLOADING) {
                NovaUpdateChecker.cancel();
            } else if (state == NovaUpdateChecker.State.READY) {
                NovaUpdateChecker.install(getParentActivity());
            } else if (state == NovaUpdateChecker.State.FAILED
                    && NovaUpdateChecker.getRelease() != null) {
                NovaUpdateChecker.download();
            } else if (state != NovaUpdateChecker.State.CHECKING) {
                NovaUpdateChecker.checkNow();
            }
        } else if (item.id == ID_UPDATE_NOTES || item.id == ID_VERSION) {
            NovaUpdateChecker.Release release = NovaUpdateChecker.getRelease();
            String url = (item.id == ID_UPDATE_NOTES
                    && release != null
                    && release.releaseUrl.length() > 0)
                    ? release.releaseUrl
                    : NovaUpdateChecker.releaseUrl();
            Browser.openUrl(getParentActivity(), url);
        } else if (item.id == ID_DOH) {
            presentFragment(new NovaDohActivity());
        } else if (item.id == ID_PROJECT) {
            Browser.openUrl(getParentActivity(), NovaUpdateChecker.PROJECT_URL);
        }
    }

    /** How many endpoints are on, shown next to the row. */
    private int enabledDohCount() {
        int result = 0;
        for (NovaDoh.Endpoint endpoint : NovaDoh.endpoints()) {
            if (endpoint.enabled) {
                result++;
            }
        }
        return result;
    }

    private boolean isAppPinSet() {
        Context context = getParentActivity() != null ? getParentActivity() : getContext();
        if (context == null) {
            return false;
        }
        try {
            return new NovaPinVault(context).isEnrolled();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Asked before the gate, not after: the gate takes the PIN and acts, and by
     * then it is too late to say that the emergency PIN goes with it. That
     * consequence is the whole reason this is a question and not a switch.
     */
    private void showDisablePinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NovaPinDisableRow));
        builder.setMessage(LocaleController.getString(R.string.NovaPinDisableWarning));
        builder.setPositiveButton(
                LocaleController.getString(R.string.NovaPinDisableConfirm),
                (dialog, which) -> {
                    if (getParentActivity() == null) {
                        return;
                    }
                    Intent intent = new Intent(getParentActivity(), NovaPinGateActivity.class);
                    intent.putExtra(NovaPinGateActivity.EXTRA_DISABLE_PIN, true);
                    getParentActivity().startActivity(intent);
                });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private static CharSequence pinPolicyName(NovaPinLockPolicy policy) {
        int id;
        if (policy == NovaPinLockPolicy.ON_SCREEN_LOCK) {
            id = R.string.NovaPinPolicyScreenLock;
        } else if (policy == NovaPinLockPolicy.INACTIVITY_10M) {
            id = R.string.NovaPinPolicyIdle10;
        } else if (policy == NovaPinLockPolicy.INACTIVITY_60M) {
            id = R.string.NovaPinPolicyIdle60;
        } else if (policy == NovaPinLockPolicy.ON_START) {
            id = R.string.NovaPinPolicyStart;
        } else {
            id = R.string.NovaPinPolicyMinimize;
        }
        return LocaleController.getString(id);
    }

    private void showPinPolicyDialog() {
        CharSequence[] titles = new CharSequence[PIN_POLICIES.length];
        for (int i = 0; i < PIN_POLICIES.length; i++) {
            titles[i] = pinPolicyName(PIN_POLICIES[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NovaPinPolicyTitle));
        builder.setItems(titles, (dialog, which) -> {
            if (which >= 0 && which < PIN_POLICIES.length && getParentActivity() != null) {
                NovaPrivacySettings.global(getParentActivity()).setPinLockPolicy(PIN_POLICIES[which]);
                // The running session was granted under the previous answer.
                // Re-arming rather than re-locking: the user is standing in
                // the settings, and asking for the PIN because they just
                // chose when to be asked for it would be absurd.
                NovaPinSession.reevaluatePolicy();
                buildItems();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showPeriodDialog() {
        CharSequence[] titles = new CharSequence[AUTO_DELETE_PERIODS.length];
        for (int i = 0; i < AUTO_DELETE_PERIODS.length; i++) {
            titles[i] = formatPeriod(AUTO_DELETE_PERIODS[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NovaAutoDeletePeriod));
        builder.setItems(titles, (dialog, which) -> {
            if (which >= 0 && which < AUTO_DELETE_PERIODS.length) {
                NovaAutoDelete.setPeriodHours(currentAccount, AUTO_DELETE_PERIODS[which]);
                buildItems();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private static CharSequence formatPeriod(int hours) {
        if (hours % 24 == 0) {
            return LocaleController.formatPluralString("Days", hours / 24);
        }
        return LocaleController.formatString(
                "NovaAutoDeleteHoursValue",
                R.string.NovaAutoDeleteHoursValue,
                hours);
    }

    private CharSequence updateStateText() {
        switch (NovaUpdateChecker.getState()) {
            case CHECKING:
                return LocaleController.getString(R.string.NovaUpdateChecking);
            case UP_TO_DATE:
                return LocaleController.getString(R.string.NovaUpdateUpToDate);
            case FOUND: {
                NovaUpdateChecker.Release release = NovaUpdateChecker.getRelease();
                return LocaleController.formatString(
                        "NovaUpdateFound",
                        R.string.NovaUpdateFound,
                        release != null ? release.version : "");
            }
            case DOWNLOADING:
                return LocaleController.formatString(
                        "NovaUpdateDownloading",
                        R.string.NovaUpdateDownloading,
                        NovaUpdateChecker.getProgress());
            case READY:
                return LocaleController.getString(R.string.NovaUpdateReady);
            case FAILED:
                // Two different failures wear one state, and saying that
                // checking went wrong when the check succeeded and the
                // download did not sends the user looking in the wrong place.
                return LocaleController.getString(
                        NovaUpdateChecker.getRelease() != null
                                ? R.string.NovaUpdateDownloadFailed
                                : R.string.NovaUpdateFailed);
            default:
                return LocaleController.getString(R.string.NovaUpdateCheckNow);
        }
    }

    private void buildItems() {
        items.clear();
        boolean appPinSet = isAppPinSet();
        // The rewrite that follows a toggle is queued on the network thread, so
        // what was remembered about the files on disk is dropped here and read
        // again rather than reported from a stale answer.
        NovaDeviceLock.refreshDiskState();
        items.add(Item.header(LocaleController.getString(R.string.NovaSettingsSecurityHeader)));
        // First in the section on purpose: it works with no PIN set, and it is
        // the only thing between a copied data directory and the account.
        items.add(Item.check(ID_DEVICE_BINDING, LocaleController.getString(R.string.NovaDeviceBindingTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaDeviceBindingInfo)));
        items.add(Item.value(
                ID_APP_PIN,
                LocaleController.getString(R.string.NovaSettingsAppPin),
                LocaleController.getString(appPinSet
                        ? R.string.NovaPinStateOn
                        : R.string.NovaPinStateOff)));
        if (appPinSet) {
            // Both rows are meaningless without a PIN: nothing to remove, and
            // nothing to ask for again. Until 2026-08-10 the first did not
            // exist at all and the PIN could not be switched off from the
            // application - the desktop fork could, so the two disagreed.
            items.add(Item.value(
                    ID_PIN_POLICY,
                    LocaleController.getString(R.string.NovaPinPolicyTitle),
                    pinPolicyName(NovaPrivacySettings.global(getContext()).getPinLockPolicy())));
            items.add(Item.text(ID_APP_PIN_OFF, LocaleController.getString(R.string.NovaPinDisableRow)));
        }
        items.add(Item.text(ID_EMERGENCY_PIN, LocaleController.getString(R.string.NovaEmergencySettingsTitle)));
        // One shadow, not two: the policy explanation only makes sense next to
        // the row that sets it, and that row only exists once a PIN does.
        items.add(Item.shadow(appPinSet
                ? LocaleController.getString(R.string.NovaSettingsPinsInfo)
                        + "\n\n"
                        + LocaleController.getString(R.string.NovaPinPolicyInfo)
                : LocaleController.getString(R.string.NovaSettingsPinsInfo)));
        items.add(Item.header(LocaleController.getString(R.string.NovaSettingsPrivacyHeader)));
        items.add(Item.check(ID_SCREENSHOTS, LocaleController.getString(R.string.NovaSettingsScreenshotProtection)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaSettingsScreenshotInfo)));

        items.add(Item.check(ID_AUTOTRANSLATE, LocaleController.getString(R.string.NovaAutoTranslateTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaAutoTranslateInfo)));

        // The one row here whose switch is the plain thing rather than the
        // protection: it is the same bit as Telegram's own "Sync contacts", and
        // giving it the opposite polarity in this screen would make two
        // switches for one value disagree on sight.
        items.add(Item.check(ID_CONTACT_SYNC, LocaleController.getString(R.string.NovaContactSyncTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaContactSyncInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaAutoDeleteHeader)));
        items.add(Item.check(ID_AUTO_DELETE, LocaleController.getString(R.string.NovaAutoDeleteEnable)));
        if (NovaAutoDelete.isEnabled(currentAccount)) {
            items.add(Item.value(
                    ID_AUTO_DELETE_PERIOD,
                    LocaleController.getString(R.string.NovaAutoDeletePeriod),
                    formatPeriod(NovaAutoDelete.getPeriodHours(currentAccount))));
        }
        items.add(Item.shadow(LocaleController.getString(R.string.NovaAutoDeleteInfo)));

        items.add(Item.check(ID_READ_STATUS, LocaleController.getString(R.string.NovaReadStatusTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaReadStatusInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaCallsHeader)));
        items.add(Item.check(ID_CALLS_RELAY, LocaleController.getString(R.string.NovaCallsRelayTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaCallsRelayInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaDohHeader)));
        items.add(Item.value(
                ID_DOH,
                LocaleController.getString(R.string.NovaDohTitle),
                String.valueOf(enabledDohCount())));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaDohInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaStickersHeader)));
        items.add(Item.check(ID_CRASH_STICKERS, LocaleController.getString(R.string.NovaCrashStickerTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaCrashStickerInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaFilesHeader)));
        items.add(Item.check(ID_FILE_NAMES, LocaleController.getString(R.string.NovaFileNamesTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaFileNamesInfo)));
        items.add(Item.check(ID_METADATA, LocaleController.getString(R.string.NovaMetadataTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaMetadataInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaSendingHeader)));
        items.add(Item.check(ID_NIGHT_SILENT, LocaleController.getString(R.string.NovaNightSilentTitle)));
        if (NovaPrivacySettings.global(getContext()).isNightSilentEnabled()) {
            items.add(Item.check(ID_NIGHT_SILENT_USERS, LocaleController.getString(R.string.NovaNightSilentUsers)));
            items.add(Item.check(ID_NIGHT_SILENT_GROUPS, LocaleController.getString(R.string.NovaNightSilentGroups)));
            items.add(Item.check(ID_NIGHT_SILENT_CHANNELS, LocaleController.getString(R.string.NovaNightSilentChannels)));
        }
        items.add(Item.shadow(LocaleController.getString(R.string.NovaNightSilentInfo)));

        // Two switches, two sections. They are easy to mistake for one another
        // and they answer opposite halves of the same question: the first is
        // about what the Telegram servers are allowed to compose into a push,
        // the second about what this device is allowed to draw once the
        // message is already here. One shared explanation under both would be
        // false about one of them.
        items.add(Item.header(LocaleController.getString(R.string.NovaNotificationsHeader)));
        items.add(Item.check(ID_PUSH_PREVIEW, LocaleController.getString(R.string.NovaPushPreviewTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaPushPreviewInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaNotificationContentHeader)));
        items.add(Item.check(ID_HIDE_CONTENT, LocaleController.getString(R.string.NovaHideContentTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaHideContentInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaSettingsUpdatesHeader)));
        items.add(Item.check(ID_UPDATE_CHECK, LocaleController.getString(R.string.NovaSettingsUpdateCheck)));
        items.add(Item.value(ID_UPDATE_STATE, updateStateText(), updateStateValue()));
        items.add(Item.text(ID_UPDATE_NOTES, LocaleController.getString(R.string.NovaUpdateNotes)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaSettingsUpdateInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaSettingsAboutHeader)));
        items.add(Item.value(
                ID_VERSION,
                LocaleController.getString(R.string.NovaSettingsVersion),
                NovaUpdateChecker.RELEASE_TAG));
        items.add(Item.text(ID_PROJECT, LocaleController.getString(R.string.NovaSettingsProject)));
        items.add(Item.shadow(LocaleController.formatString(
                "NovaSettingsAboutInfo",
                R.string.NovaSettingsAboutInfo,
                baseVersion())));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * The right-hand half of the row is what pressing it does, so it has to
     * name the action and not repeat the state. Same three actions as the bar
     * at the bottom of the chat list and as the desktop settings block.
     */
    private CharSequence updateStateValue() {
        switch (NovaUpdateChecker.getState()) {
            case FOUND:
                return LocaleController.getString(R.string.NovaUpdateDownload);
            case DOWNLOADING:
                return LocaleController.getString(R.string.NovaUpdateCancel);
            case READY:
                return LocaleController.getString(R.string.NovaUpdateInstall);
            case FAILED:
                return NovaUpdateChecker.getRelease() != null
                        ? LocaleController.getString(R.string.NovaUpdateRetry)
                        : "";
            default:
                return "";
        }
    }

    /**
     * The device binding row says what is happening, not what was asked for.
     * The switch alone kept claiming the data was bound while a Keystore that
     * had refused a key meant nothing was being sealed at all.
     */
    private int deviceBindingStateText() {
        switch (NovaDeviceLock.state()) {
            case BOUND:
                return R.string.NovaDeviceBindingStateOn;
            case PENDING:
                return R.string.NovaDeviceBindingStatePending;
            case UNAVAILABLE:
                return R.string.NovaDeviceBindingStateUnavailable;
            case FOREIGN:
                return R.string.NovaDeviceBindingStateForeign;
            case OFF:
            default:
                return R.string.NovaDeviceBindingStateOff;
        }
    }

    private String baseVersion() {
        try {
            Context context = getContext();
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static class Item {
        final int viewType;
        final int id;
        final CharSequence text;
        final CharSequence value;

        private Item(int viewType, int id, CharSequence text, CharSequence value) {
            this.viewType = viewType;
            this.id = id;
            this.text = text;
            this.value = value;
        }

        static Item header(CharSequence text) {
            return new Item(VIEW_TYPE_HEADER, 0, text, "");
        }

        static Item text(int id, CharSequence text) {
            return new Item(VIEW_TYPE_TEXT, id, text, "");
        }

        static Item value(int id, CharSequence text, CharSequence value) {
            return new Item(VIEW_TYPE_TEXT, id, text, value);
        }

        static Item check(int id, CharSequence text) {
            return new Item(VIEW_TYPE_CHECK, id, text, "");
        }

        static Item shadow(CharSequence text) {
            return new Item(VIEW_TYPE_SHADOW, 0, text, "");
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(getContext());
            } else if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(getContext());
            } else if (viewType == VIEW_TYPE_SHADOW) {
                view = new TextInfoPrivacyCell(getContext());
            } else {
                view = new TextSettingsCell(getContext());
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            Item item = items.get(position);
            boolean divider = position + 1 < items.size() && items.get(position + 1).viewType == item.viewType;
            if (item.viewType == VIEW_TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(item.text);
            } else if (item.viewType == VIEW_TYPE_TEXT) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (TextUtils.isEmpty(item.value)) {
                    cell.setText(item.text.toString(), divider);
                } else {
                    cell.setTextAndValue(item.text.toString(), item.value.toString(), divider);
                }
            } else if (item.viewType == VIEW_TYPE_CHECK && item.id == ID_DEVICE_BINDING) {
                // Two different answers, and the row shows both. The switch is
                // what the owner asked for; the subtitle is what the binding is
                // actually doing, which differs whenever the Keystore refuses.
                ((TextCheckCell) holder.itemView).setTextAndValueAndCheck(
                        item.text.toString(),
                        LocaleController.getString(deviceBindingStateText()),
                        NovaDeviceLock.isEnabled(),
                        true,
                        divider);
            } else if (item.viewType == VIEW_TYPE_CHECK && item.id == ID_NIGHT_SILENT) {
                // The window belongs in a subtitle: on one line with the label
                // it does not fit the row and gets cut off mid-way.
                ((TextCheckCell) holder.itemView).setTextAndValueAndCheck(
                        item.text.toString(),
                        LocaleController.getString(R.string.NovaNightSilentHours),
                        NovaPrivacySettings.global(getContext()).isNightSilentEnabled(),
                        false,
                        divider);
            } else if (item.viewType == VIEW_TYPE_CHECK) {
                boolean checked;
                if (item.id == ID_UPDATE_CHECK) {
                    checked = NovaUpdateChecker.isEnabled(getContext());
                } else if (item.id == ID_AUTO_DELETE) {
                    checked = NovaAutoDelete.isEnabled(currentAccount);
                } else if (item.id == ID_READ_STATUS) {
                    checked = NovaReadStatus.isEnabled(currentAccount);
                } else if (item.id == ID_CALLS_RELAY) {
                    checked = NovaCallPolicy.relayOnly(getContext());
                } else if (item.id == ID_PUSH_PREVIEW) {
                    checked = NovaNotificationPrivacy.isEnabled(currentAccount);
                } else if (item.id == ID_FILE_NAMES) {
                    checked = NovaFileNames.isEnabled();
                } else if (item.id == ID_METADATA) {
                    checked = NovaOutgoingMetadata.isEnabled();
                } else if (item.id == ID_CRASH_STICKERS) {
                    checked = NovaCrashStickers.isEnabled();
                } else if (item.id == ID_HIDE_CONTENT) {
                    checked = NovaNotificationContent.isEnabled(getContext());
                } else if (item.id == ID_NIGHT_SILENT) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentEnabled();
                } else if (item.id == ID_NIGHT_SILENT_USERS) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentForUsers();
                } else if (item.id == ID_NIGHT_SILENT_GROUPS) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentForGroups();
                } else if (item.id == ID_NIGHT_SILENT_CHANNELS) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentForChannels();
                } else if (item.id == ID_AUTOTRANSLATE) {
                    checked = NovaTranslationPolicy.isChannelFlagIgnored();
                } else if (item.id == ID_CONTACT_SYNC) {
                    checked = NovaContactSync.isEnabled(currentAccount);
                } else {
                    checked = item.id != ID_SCREENSHOTS
                            || NovaPrivacySettings.global(getContext()).isFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION);
                }
                ((TextCheckCell) holder.itemView).setTextAndCheck(item.text.toString(), checked, divider);
            } else {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (TextUtils.isEmpty(item.text)) {
                    cell.setFixedSize(12);
                    cell.setText(null);
                } else {
                    cell.setFixedSize(0);
                    cell.setText(item.text);
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_TEXT || type == VIEW_TYPE_CHECK;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return VIEW_TYPE_SHADOW;
            }
            return items.get(position).viewType;
        }
    }
}
