package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.net.NovaDoh;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The encrypted DNS list, as the owner of the device may edit it — the same
 * screen the desktop half shows, with the same rules.
 *
 * <p>The list is edited in memory and written on the way out, through
 * {@link NovaDoh#validate}. A list that would leave the client with no resolver
 * at all is refused with an explanation rather than saved: falling silently
 * back to the system is the one thing this feature exists to prevent, so it
 * must not become possible through the settings screen either.</p>
 */
public class NovaDohActivity extends BaseFragment {

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_TEXT = 1;
    private static final int VIEW_TYPE_SHADOW = 2;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<NovaDoh.Endpoint> endpoints = new ArrayList<>();

    /** Index into {@link #endpoints}, or -1 for the "add" and info rows. */
    private final ArrayList<Integer> rows = new ArrayList<>();
    private int addRow;
    private int infoRow;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NovaDohTitle));
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
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onItemClick(position, view));

        endpoints.clear();
        endpoints.addAll(NovaDoh.endpoints());
        buildRows();
        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    /**
     * Saving happens on the way out rather than on every tap, so that a list
     * which is briefly invalid while it is being edited — the last endpoint
     * switched off before another is added — does not have to be refused mid
     * way. What cannot be left behind is an invalid list, and that is checked
     * here.
     */
    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!super.onBackPressed(invoked)) {
            return false;
        }
        String error = NovaDoh.validate(endpoints, isRussian());
        if (error != null) {
            // The screen is also asked speculatively, for the swipe-back
            // gesture; the answer there is the same "no", but a dialog raised
            // by a gesture the user has not finished would be raised for
            // nothing.
            if (invoked && getParentActivity() != null) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(LocaleController.getString(R.string.NovaDohTitle));
                builder.setMessage(error);
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                showDialog(builder.create());
            }
            return false;
        }
        NovaDoh.setEndpoints(endpoints);
        return true;
    }

    private boolean isRussian() {
        try {
            return "ru".equals(LocaleController.getInstance().getCurrentLocale().getLanguage());
        } catch (Throwable e) {
            return false;
        }
    }

    private void buildRows() {
        rows.clear();
        for (int i = 0; i < endpoints.size(); i++) {
            rows.add(i);
        }
        addRow = rows.size();
        rows.add(-1);
        infoRow = rows.size();
        rows.add(-2);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onItemClick(int position, View view) {
        if (getParentActivity() == null || position < 0 || position >= rows.size()) {
            return;
        }
        if (position == addRow) {
            showAddDialog();
            return;
        }
        if (position == infoRow) {
            return;
        }
        int index = rows.get(position);
        NovaDoh.Endpoint endpoint = endpoints.get(index);
        if (!endpoint.builtin) {
            // Its own switch would be one tap away from a row that cannot be
            // brought back once removed, so a server the owner added is
            // removed by asking first.
            showRemoveDialog(index);
            return;
        }
        endpoints.set(index, endpoint.withEnabled(!endpoint.enabled));
        ((TextCheckCell) view).setChecked(!endpoint.enabled);
    }

    private void showRemoveDialog(int index) {
        NovaDoh.Endpoint endpoint = endpoints.get(index);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NovaDohRemove));
        builder.setMessage(LocaleController.formatString(
                "NovaDohRemoveQuestion", R.string.NovaDohRemoveQuestion, endpoint.host));
        builder.setPositiveButton(LocaleController.getString(R.string.NovaDohRemove), (dialog, which) -> {
            endpoints.remove(index);
            buildRows();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showAddDialog() {
        Context context = getParentActivity();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(24);
        layout.setPadding(padding, AndroidUtilities.dp(8), padding, 0);

        final EditText host = new EditText(context);
        host.setHint(LocaleController.getString(R.string.NovaDohAddHost));
        host.setSingleLine(true);
        host.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        host.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        layout.addView(host, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final EditText addresses = new EditText(context);
        addresses.setHint(LocaleController.getString(R.string.NovaDohAddAddresses));
        addresses.setSingleLine(true);
        addresses.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        addresses.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        layout.addView(addresses, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.NovaDohAddTitle));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.Add), (dialog, which) -> {
            String name = host.getText().toString().trim().toLowerCase(Locale.US);
            if (name.length() == 0 || name.indexOf('/') >= 0) {
                Toast.makeText(getParentActivity(),
                        LocaleController.getString(R.string.NovaDohAddHost),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> list = new ArrayList<>();
            for (String part : addresses.getText().toString().split(",")) {
                String trimmed = part.trim();
                if (trimmed.length() > 0) {
                    list.add(trimmed);
                }
            }
            // Only a host is asked for: the path is the one every RFC 8484
            // endpoint uses, and a field for it would be a way to get it wrong.
            endpoints.add(new NovaDoh.Endpoint(name, "/dns-query", list, false, true));
            buildRows();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            View view;
            if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_TEXT) {
                view = new TextSettingsCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);
            if (type == VIEW_TYPE_SHADOW) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        LocaleController.getString(R.string.NovaDohListInfo));
                return;
            }
            if (type == VIEW_TYPE_TEXT) {
                ((TextSettingsCell) holder.itemView).setText(
                        LocaleController.getString(R.string.NovaDohAdd), false);
                return;
            }
            NovaDoh.Endpoint endpoint = endpoints.get(rows.get(position));
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < endpoint.addresses.size() && i < 2; i++) {
                if (value.length() > 0) {
                    value.append(", ");
                }
                value.append(endpoint.addresses.get(i));
            }
            if (endpoint.addresses.isEmpty()) {
                // Said out loud rather than left blank: an added server with no
                // address of its own is the case the bootstrap rule is about.
                value.append(endpoint.builtin ? "" : "—");
            }
            ((TextCheckCell) holder.itemView).setTextAndValueAndCheck(
                    endpoint.host,
                    value.toString(),
                    endpoint.enabled,
                    true,
                    position != rows.size() - 3);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infoRow) {
                return VIEW_TYPE_SHADOW;
            }
            if (position == addRow) {
                return VIEW_TYPE_TEXT;
            }
            return VIEW_TYPE_CHECK;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != VIEW_TYPE_SHADOW;
        }
    }
}
