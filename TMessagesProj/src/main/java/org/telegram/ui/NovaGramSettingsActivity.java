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
import org.telegram.messenger.novagram.privacy.NovaPrivacyFeature;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;
import org.telegram.ui.ActionBar.ActionBar;
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

    private final ArrayList<Item> items = new ArrayList<>();

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
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static class Item {
        final int viewType;
        final int id;
        final CharSequence text;

        private Item(int viewType, int id, CharSequence text) {
            this.viewType = viewType;
            this.id = id;
            this.text = text;
        }

        static Item header(CharSequence text) {
            return new Item(VIEW_TYPE_HEADER, 0, text);
        }

        static Item text(int id, CharSequence text) {
            return new Item(VIEW_TYPE_TEXT, id, text);
        }

        static Item check(int id, CharSequence text) {
            return new Item(VIEW_TYPE_CHECK, id, text);
        }

        static Item shadow(CharSequence text) {
            return new Item(VIEW_TYPE_SHADOW, 0, text);
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
                ((TextSettingsCell) holder.itemView).setText(item.text.toString(), divider);
            } else if (item.viewType == VIEW_TYPE_CHECK) {
                boolean checked = item.id != ID_SCREENSHOTS
                        || NovaPrivacySettings.global(getContext()).isFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION);
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
