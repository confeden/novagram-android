package org.telegram.messenger.novagram.privacy;

import android.content.DialogInterface;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

/**
 * The two per-chat entries the fork adds to a chat menu, in one place.
 *
 * <p>They used to live inside {@code ChatActivity}, which meant they existed
 * only where that screen builds its menu. A group whose rooms are shown as a
 * list never opens that screen at all — it opens {@code TopicsFragment} — so
 * the same group had the auto-delete override and Erase evidence while it was
 * set to be shown as one chat and lost both when it was not. A privacy promise
 * cannot depend on a display preference, and the fix is this class: both
 * screens call into it and get the same entries.</p>
 *
 * <p>Both rules are stored per chat, and a room is not a chat of its own — it
 * shares the identifier of the group it lives in. So inside a room these do
 * exactly what they do in the group, and {@link #autoDeleteMenuText} says so
 * instead of saying "here".</p>
 */
public final class NovaChatMenu {

    private NovaChatMenu() {
    }

    /** False in the decoy and wherever the feature is switched off. */
    public static boolean autoDeleteVisible(int account) {
        return !NovaDecoyState.isActive() && NovaAutoDelete.isEnabled(account);
    }

    public static boolean appliesTo(int account, long dialogId) {
        return NovaAutoDelete.getInstance(account).appliesTo(dialogId);
    }

    public static CharSequence autoDeleteMenuText(
            int account,
            long dialogId,
            boolean wholeGroup) {
        final boolean applies = appliesTo(account, dialogId);
        if (wholeGroup) {
            return LocaleController.getString(applies
                    ? R.string.NovaAutoDeleteGroupOff
                    : R.string.NovaAutoDeleteGroupOn);
        }
        return LocaleController.getString(applies
                ? R.string.NovaAutoDeleteChatOff
                : R.string.NovaAutoDeleteChatOn);
    }

    public static int autoDeleteMenuIcon(int account, long dialogId) {
        return appliesTo(account, dialogId) ? R.drawable.msg_cancel : R.drawable.msg_clear;
    }

    /**
     * The opposite of the current effect is stored explicitly, so gaining or
     * losing admin rights later cannot silently reverse what was chosen here.
     */
    public static void toggleAutoDelete(int account, long dialogId) {
        NovaAutoDelete engine = NovaAutoDelete.getInstance(account);
        engine.setRule(dialogId, engine.appliesTo(dialogId)
                ? NovaAutoDeleteStore.RULE_NEVER
                : NovaAutoDeleteStore.RULE_ALWAYS);
    }

    /**
     * Shows the result of an Erase evidence run once the queue has really
     * finished it. The run outlives the screen that started it — the queue
     * works for minutes — so the report waits until a screen for that chat is
     * opened again rather than being lost. It is single-shot, so whichever
     * screen asks first is the one that shows it.
     */
    public static void showPendingReport(BaseFragment fragment, long dialogId) {
        if (fragment == null
                || fragment.getParentActivity() == null
                || fragment.getFragmentView() == null
                || NovaDecoyState.isActive()) {
            return;
        }
        NovaAutoDelete engine = NovaAutoDelete.getInstance(fragment.getCurrentAccount());
        NovaAutoDeleteStore.Report report = engine.getReport(dialogId);
        if (report == null || !report.finished || report.shown) {
            return;
        }
        engine.markReportShown(dialogId);
        BulletinFactory.of(fragment).createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.NovaEraseEvidence),
                LocaleController.formatString(
                        "NovaEraseEvidenceReport",
                        R.string.NovaEraseEvidenceReport,
                        report.deleted,
                        report.replaced,
                        report.skipped)).show();
    }

    public static void showEraseEvidenceDialog(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final CharSequence[] periods = {
                LocaleController.getString(R.string.NovaEraseEvidenceDay),
                LocaleController.getString(R.string.NovaEraseEvidenceWeek),
                LocaleController.getString(R.string.NovaEraseEvidenceMonth),
                LocaleController.getString(R.string.NovaEraseEvidenceAll)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(
                fragment.getParentActivity(),
                fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.NovaEraseEvidence));
        builder.setItems(periods, (dialog, which) ->
                confirm(fragment, dialogId, which, periods[which]));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void confirm(
            BaseFragment fragment,
            long dialogId,
            int period,
            CharSequence periodName) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        String message = LocaleController.getString(R.string.NovaEraseEvidenceAbout)
                + "\n\n"
                + LocaleController.getString(R.string.NovaEraseEvidenceReactions)
                + "\n\n"
                + LocaleController.formatString(
                        "NovaEraseEvidenceConfirm",
                        R.string.NovaEraseEvidenceConfirm,
                        periodName.toString().toLowerCase());
        AlertDialog.Builder builder = new AlertDialog.Builder(
                fragment.getParentActivity(),
                fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.NovaEraseEvidence));
        builder.setMessage(message);
        builder.setPositiveButton(
                LocaleController.getString(R.string.NovaEraseEvidenceDestroy),
                (dialog, which) -> run(fragment, dialogId, period));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(fragment.getThemedColor(Theme.key_text_RedBold));
        }
    }

    private static void run(BaseFragment fragment, long dialogId, int period) {
        final int account = fragment.getCurrentAccount();
        BulletinFactory.of(fragment).createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.NovaEraseEvidenceCollecting)).show();
        NovaEraseEvidence.run(account, dialogId, period, (queued, reactions, complete) -> {
            if (fragment.getParentActivity() == null || fragment.getFragmentView() == null) {
                return;
            }
            CharSequence text;
            if (!complete) {
                // The walk stopped early, so part of the period was never seen.
                // Reporting the same success as a full pass would be a lie
                // exactly where the user needs the truth.
                text = LocaleController.formatString(
                        "NovaEraseEvidencePartial",
                        R.string.NovaEraseEvidencePartial,
                        queued);
            } else if (queued > 0) {
                text = LocaleController.formatString(
                        "NovaEraseEvidenceQueued",
                        R.string.NovaEraseEvidenceQueued,
                        queued);
            } else {
                text = LocaleController.getString(R.string.NovaEraseEvidenceNothing);
            }
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, text).show();
        });
    }
}
