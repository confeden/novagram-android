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

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.novagram.privacy.NovaAutoDelete;
import org.telegram.messenger.novagram.privacy.NovaNotificationPrivacy;
import org.telegram.messenger.novagram.privacy.NovaPrivacyFeature;
import org.telegram.messenger.novagram.privacy.NovaReadStatus;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;
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
        } else if (item.id == ID_EMERGENCY_PIN) {
            Intent intent = new Intent(getParentActivity(), NovaPinGateActivity.class);
            intent.putExtra(NovaPinGateActivity.EXTRA_SETUP_EMERGENCY, true);
            getParentActivity().startActivity(intent);
        } else if (item.id == ID_SCREENSHOTS) {
            NovaPrivacySettings settings = NovaPrivacySettings.global(getParentActivity());
            boolean enabled = !settings.isFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION);
            settings.setFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION, enabled);
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
        } else if (item.id == ID_PUSH_PREVIEW) {
            boolean enabled = !NovaNotificationPrivacy.isEnabled(currentAccount);
            NovaNotificationPrivacy.setEnabled(currentAccount, enabled);
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
            } else if (state == NovaUpdateChecker.State.READY) {
                NovaUpdateChecker.install(getParentActivity());
            } else if (state != NovaUpdateChecker.State.CHECKING
                    && state != NovaUpdateChecker.State.DOWNLOADING) {
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
        } else if (item.id == ID_PROJECT) {
            Browser.openUrl(getParentActivity(), NovaUpdateChecker.PROJECT_URL);
        }
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
                return LocaleController.getString(R.string.NovaUpdateFailed);
            default:
                return LocaleController.getString(R.string.NovaUpdateCheckNow);
        }
    }

    private void buildItems() {
        items.clear();
        items.add(Item.header(LocaleController.getString(R.string.NovaSettingsSecurityHeader)));
        items.add(Item.text(ID_APP_PIN, LocaleController.getString(R.string.NovaSettingsAppPin)));
        items.add(Item.text(ID_EMERGENCY_PIN, LocaleController.getString(R.string.NovaEmergencySettingsTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaSettingsPinsInfo)));
        items.add(Item.header(LocaleController.getString(R.string.NovaSettingsPrivacyHeader)));
        items.add(Item.check(ID_SCREENSHOTS, LocaleController.getString(R.string.NovaSettingsScreenshotProtection)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaSettingsScreenshotInfo)));

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

        items.add(Item.header(LocaleController.getString(R.string.NovaSendingHeader)));
        items.add(Item.check(ID_NIGHT_SILENT, LocaleController.getString(R.string.NovaNightSilentTitle)));
        if (NovaPrivacySettings.global(getContext()).isNightSilentEnabled()) {
            items.add(Item.check(ID_NIGHT_SILENT_USERS, LocaleController.getString(R.string.NovaNightSilentUsers)));
            items.add(Item.check(ID_NIGHT_SILENT_GROUPS, LocaleController.getString(R.string.NovaNightSilentGroups)));
            items.add(Item.check(ID_NIGHT_SILENT_CHANNELS, LocaleController.getString(R.string.NovaNightSilentChannels)));
        }
        items.add(Item.shadow(LocaleController.getString(R.string.NovaNightSilentInfo)));

        items.add(Item.header(LocaleController.getString(R.string.NovaNotificationsHeader)));
        items.add(Item.check(ID_PUSH_PREVIEW, LocaleController.getString(R.string.NovaPushPreviewTitle)));
        items.add(Item.shadow(LocaleController.getString(R.string.NovaPushPreviewInfo)));

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

    private CharSequence updateStateValue() {
        return NovaUpdateChecker.getState() == NovaUpdateChecker.State.FOUND
                ? LocaleController.getString(R.string.NovaUpdateDownload)
                : "";
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
                } else if (item.id == ID_PUSH_PREVIEW) {
                    checked = NovaNotificationPrivacy.isEnabled(currentAccount);
                } else if (item.id == ID_NIGHT_SILENT) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentEnabled();
                } else if (item.id == ID_NIGHT_SILENT_USERS) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentForUsers();
                } else if (item.id == ID_NIGHT_SILENT_GROUPS) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentForGroups();
                } else if (item.id == ID_NIGHT_SILENT_CHANNELS) {
                    checked = NovaPrivacySettings.global(getContext()).isNightSilentForChannels();
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
