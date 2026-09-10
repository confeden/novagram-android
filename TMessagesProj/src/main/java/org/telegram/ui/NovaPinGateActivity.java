package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.privacy.NovaDeviceLock;
import org.telegram.messenger.novagram.privacy.NovaEmergencyWipe;
import org.telegram.messenger.novagram.privacy.NovaSyncDeauth;
import org.telegram.messenger.novagram.privacy.NovaPinSession;
import org.telegram.messenger.novagram.privacy.NovaPinVault;
import org.telegram.messenger.novagram.privacy.NovaPrivacyContract;
import org.telegram.messenger.novagram.privacy.NovaPrivacySettings;
import org.telegram.messenger.novagram.privacy.NovaSecretWiper;
import org.telegram.ui.ActionBar.Theme;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Mandatory NovaGram PIN enrollment and unlock screen. */
public final class NovaPinGateActivity extends Activity {
    /**
     * Opens the screen in the emergency PIN setup flow instead of the unlock
     * flow. The current PIN is asked first, so the setup is not reachable by
     * simply launching the activity.
     */
    public static final String EXTRA_SETUP_EMERGENCY = "novagram.setup_emergency";
    /** Opens the gate to remove the application PIN, see NovaPinVault.disable. */
    public static final String EXTRA_DISABLE_PIN = "novagram.disable_pin";

    /**
     * The palette this screen shipped with, kept only as a fallback. Colours
     * are read from the client's theme (see {@link #readThemeColors()}); these
     * are what is drawn if the theme engine itself could not start.
     */
    private static final int FALLBACK_PAGE_COLOR = Color.rgb(23, 33, 43);
    private static final int FALLBACK_SURFACE_COLOR = Color.rgb(36, 47, 61);
    private static final int FALLBACK_PRIMARY_COLOR = Color.rgb(82, 136, 193);
    private static final int FALLBACK_TEXT_COLOR = Color.WHITE;
    private static final int FALLBACK_SECONDARY_TEXT_COLOR = Color.rgb(174, 183, 194);
    private static final int FALLBACK_ERROR_COLOR = Color.rgb(232, 94, 94);
    private static final int FALLBACK_RIPPLE_COLOR = 0x1fffffff;

    /** Widest the block is ever drawn: a tablet gets a column, not a stretched form. */
    private static final int MAX_CONTENT_WIDTH_DP = 400;
    /** Below this window height the screen switches to its compact metrics. */
    private static final int COMPACT_HEIGHT_DP = 520;
    /** Used when the configuration cannot say how tall the window is. */
    private static final int ASSUMED_WINDOW_HEIGHT_DP = 600;
    /**
     * The keypad never claims more than this share of the window's height. The
     * title, the explanation, the line reserved for an error or the lockout
     * countdown, and the padding need roughly the other half, and the measure
     * this is applied to is {@code screenHeightDp} — the height of the window,
     * with the system bars already taken out.
     */
    private static final float KEYPAD_MAX_HEIGHT_FRACTION = 0.46f;
    private static final int KEYPAD_COLUMNS = 3;
    private static final int KEYPAD_ROWS = 4;

    /** A digit: a circle, drawn on the neutral key surface. */
    private static final int KEY_DIGIT = 0;
    /** "Очистить": a capsule the width of its column, same surface as a digit. */
    private static final int KEY_NEUTRAL = 1;
    /** "Продолжить": the same capsule in the theme's accent. */
    private static final int KEY_ACCENT = 2;

    private final char[] input = new char[NovaPrivacyContract.MAX_PIN_LENGTH];
    private final SecureRandom keypadRandom = new SecureRandom();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NovaPinVault");
        thread.setDaemon(true);
        return thread;
    });

    private NovaPinVault vault;
    private TextView titleView;
    private TextView descriptionView;
    private TextView errorView;
    private ScrollView scroll;
    private LinearLayout content;
    private KeypadView keypad;
    private LinearLayout actionPanel;

    private int pageColor;
    private int keyFillColor;
    private int keyTextColor;
    private int descriptionColor;
    private int errorColor;
    private int linkColor;
    private int accentFillColor;
    private int accentTextColor;
    private int rippleColor;
    private Typeface keyTypeface;

    private char[] firstPin;
    private char[] pendingSoftwarePin;
    private int inputLength;
    private Mode mode;
    private boolean busy;
    private long countdownDeadline;

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            long remaining = Math.max(0L, countdownDeadline - SystemClock.elapsedRealtime());
            if (remaining == 0L) {
                setBusy(false);
                errorView.setText("");
                showUnlock();
                return;
            }
            long totalSeconds = (remaining + 999L) / 1000L;
            long hours = totalSeconds / 3600L;
            long minutes = (totalSeconds % 3600L) / 60L;
            long seconds = totalSeconds % 60L;
            errorView.setText(getString(
                    R.string.NovaPinLocked,
                    hours,
                    minutes,
                    seconds
            ));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false);
        }
        readThemeColors();
        applySystemBars();
        vault = new NovaPinVault(this);
        setContentView(createContent());
        inspectVault();
    }

    /**
     * The gate is drawn from the client's own theme, so a light theme does not
     * get a black screen and a custom theme is not overruled. Every read is
     * guarded: this is the one screen that still has to come up — to take the
     * emergency PIN, or to offer "Начать заново" — even if the theme engine
     * failed to initialise, and then the palette this screen shipped with is
     * used instead.
     */
    private void readThemeColors() {
        pageColor = themeColor(Theme.key_windowBackgroundGray, FALLBACK_PAGE_COLOR);
        keyFillColor = themeColor(Theme.key_windowBackgroundWhite, FALLBACK_SURFACE_COLOR);
        keyTextColor = themeColor(Theme.key_windowBackgroundWhiteBlackText, FALLBACK_TEXT_COLOR);
        descriptionColor = themeColor(
                Theme.key_windowBackgroundWhiteGrayText2, FALLBACK_SECONDARY_TEXT_COLOR);
        errorColor = themeColor(Theme.key_text_RedRegular, FALLBACK_ERROR_COLOR);
        linkColor = themeColor(Theme.key_windowBackgroundWhiteBlueText, FALLBACK_PRIMARY_COLOR);
        accentFillColor = themeColor(
                Theme.key_featuredStickers_addButton, FALLBACK_PRIMARY_COLOR);
        accentTextColor = themeColor(
                Theme.key_featuredStickers_buttonText, FALLBACK_TEXT_COLOR);
        rippleColor = themeColor(Theme.key_listSelector, FALLBACK_RIPPLE_COLOR);
        keyTypeface = keyTypeface();
    }

    private static int themeColor(int key, int fallback) {
        try {
            return Theme.getColor(key);
        } catch (Throwable e) {
            // Theme's static initialiser rethrows, and after it has failed once
            // every later read throws NoClassDefFoundError. Not swallowed: the
            // failure is logged, and the screen still comes up readable.
            FileLog.e(e);
            return fallback;
        }
    }

    private static Typeface keyTypeface() {
        try {
            Typeface typeface = AndroidUtilities.bold();
            if (typeface != null) {
                return typeface;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return Typeface.DEFAULT_BOLD;
    }

    /**
     * The bars carry the page colour and, on a light theme, dark icons — white
     * icons on a near-white bar is the defect this avoids. From API 35 these
     * two setters are ignored for a targetSdk 35+ application: the bars are
     * transparent there and the root's own background shows through, which is
     * the same colour, and the insets listener keeps the content clear of them.
     */
    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(pageColor);
        window.setNavigationBarColor(pageColor);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        boolean lightBars = AndroidUtilities.computePerceivedBrightness(pageColor) > 0.721f;
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags = lightBars
                ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = lightBars
                    ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onBackPressed() {
        // The mandatory gate cannot be bypassed through back navigation, but
        // the emergency setup flow is entered from an unlocked application and
        // has to be dismissable.
        if (isEmergencySetupRequested() || isDisableRequested()) {
            super.onBackPressed();
        }
    }

    private boolean isEmergencySetupRequested() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_SETUP_EMERGENCY, false);
    }

    private boolean isDisableRequested() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_DISABLE_PIN, false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        clearInput();
        clearSecret(firstPin);
        clearSecret(pendingSoftwarePin);
        firstPin = null;
        pendingSoftwarePin = null;
        super.onDestroy();
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pageColor);
        root.setFilterTouchesWhenObscured(true);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        // Below API 35 the decor still fits the window inside the bars and has
        // consumed their insets by the time they reach here, so this listener
        // sees zeroes and changes nothing. From API 35 the platform stops
        // fitting the window for a targetSdk 35+ application, and these are the
        // real bar and cutout sizes the block has to stay clear of.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyBarInsets(insets);
            return insets;
        });

        content = new ContentLayout();
        content.setOrientation(LinearLayout.VERTICAL);
        // Centred in whatever vertical room is left over. fillViewport measures
        // this at the height of the viewport, so the gravity has something to
        // centre inside; once the block is taller than the viewport the measure
        // falls back to its own height and the ScrollView scrolls instead, so
        // nothing is ever clipped or pushed off a short screen.
        content.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contentParams.gravity = Gravity.CENTER_HORIZONTAL;
        scroll.addView(content, contentParams);

        titleView = text(26, keyTextColor, Typeface.BOLD, Gravity.CENTER);
        content.addView(titleView, spaced(0, dp(10)));

        descriptionView = text(16, descriptionColor, Typeface.NORMAL, Gravity.CENTER);
        content.addView(descriptionView, spaced(0, dp(14)));

        errorView = text(15, errorColor, Typeface.NORMAL, Gravity.CENTER);
        errorView.setMinHeight(dp(48));
        content.addView(errorView, spaced(0, dp(10)));

        keypad = new KeypadView();
        content.addView(keypad, spaced(0, 0));

        // Not anchored to the bottom of the window: the buttons that live here
        // belong to what is being asked above them ("Продолжить без PIN" under
        // the enrollment prompt, "Начать заново" under the device explanation),
        // so they travel with the block when it centres.
        actionPanel = new LinearLayout(this);
        actionPanel.setOrientation(LinearLayout.VERTICAL);
        actionPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(actionPanel, spaced(dp(14), 0));
        applyMetrics();
        return root;
    }

    private void applyBarInsets(WindowInsets insets) {
        int left;
        int top;
        int right;
        int bottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        if (scroll.getPaddingLeft() != left
                || scroll.getPaddingTop() != top
                || scroll.getPaddingRight() != right
                || scroll.getPaddingBottom() != bottom) {
            // Padding the scroll container, not the root: fillViewport subtracts
            // it, so the block centres inside the visible area while the root's
            // background still runs under the bars.
            scroll.setPadding(left, top, right, bottom);
        }
    }

    /**
     * Everything that depends on how much room the window actually has. Called
     * when the views are built and again on every configuration change — the
     * activity declares {@code orientation|screenSize|screenLayout} itself, so
     * it is never recreated and nothing else re-reads this.
     *
     * <p>It must never touch the keypad's digit order: turning the phone is not
     * a reason to reshuffle the keys under the user's finger.</p>
     */
    private void applyMetrics() {
        Configuration configuration = getResources().getConfiguration();
        int windowHeightDp = configuration.screenHeightDp > 0
                ? configuration.screenHeightDp
                : ASSUMED_WINDOW_HEIGHT_DP;
        boolean compact = windowHeightDp < COMPACT_HEIGHT_DP;
        int side = dp(24);
        int vertical = dp(compact ? 14 : 36);
        content.setPadding(side, vertical, side, vertical);
        titleView.setTextSize(compact ? 21 : 26);
        descriptionView.setTextSize(compact ? 14 : 16);
        errorView.setTextSize(compact ? 14 : 15);
        // Reserved so the layout does not jump when an error or the lockout
        // countdown appears; smaller on a short window, where the room is worth
        // more than the reserve.
        errorView.setMinHeight(dp(compact ? 34 : 48));
        keypad.setHeightBudget(Math.round(dp(windowHeightDp) * KEYPAD_MAX_HEIGHT_FRACTION));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (content == null) {
            return;
        }
        applyMetrics();
    }

    private void inspectVault() {
        if (NovaDeviceLock.isBlocked()) {
            // Asked before the vault: there is no PIN that opens data sealed to
            // another device, and a PIN prompt here would say the wrong thing.
            showDeviceChanged();
            return;
        }
        mode = Mode.LOADING;
        titleView.setText(R.string.NovaPinTitle);
        descriptionView.setText(R.string.NovaPinCheckingProtection);
        errorView.setText("");
        setBusy(true);
        executor.execute(() -> {
            NovaPinVault.VaultInspection inspection = vault.inspect();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false);
                switch (inspection.getState()) {
                    case NOT_ENROLLED:
                        if (isDisableRequested()) {
                            // Nothing to remove: the settings row that leads
                            // here is only drawn when a PIN is set, so this
                            // means it was removed from somewhere else while
                            // the screen was open.
                            finish();
                            return;
                        }
                        showFirstEnrollment();
                        break;
                    case ENROLLED:
                        if (isDisableRequested()) {
                            showDisableVerify();
                        } else if (isEmergencySetupRequested()) {
                            showEmergencyVerify();
                        } else {
                            showUnlock();
                        }
                        if (inspection.getRetryAfterMillis() > 0L) {
                            startCountdown(inspection.getRetryAfterMillis());
                        }
                        break;
                    case CORRUPT:
                        showFatal(R.string.NovaPinCorruptState);
                        break;
                    case UNAVAILABLE:
                    default:
                        showFatal(R.string.NovaPinStorageUnavailable);
                        break;
                }
            });
        });
    }

    private void showFirstEnrollment() {
        mode = Mode.ENROLL_FIRST;
        clearInput();
        clearSecret(firstPin);
        firstPin = null;
        titleView.setText(R.string.NovaPinCreateTitle);
        descriptionView.setText(R.string.NovaPinCreateDescription);
        errorView.setText("");
        actionPanel.removeAllViews();
        renderKeypad();
        // The application PIN is optional. This is the only escape from the
        // otherwise mandatory post-login gate; the choice is remembered so the
        // gate stops asking, and a PIN can still be set later from NovaGram
        // settings, which re-enables the emergency PIN.
        TextView skip = text(15, linkColor, Typeface.BOLD, Gravity.CENTER);
        skip.setText(R.string.NovaPinContinueWithoutPin);
        skip.setMinHeight(dp(48));
        skip.setPadding(dp(16), dp(12), dp(16), dp(12));
        skip.setBackground(pill(Color.TRANSPARENT, dp(12)));
        skip.setClickable(true);
        skip.setOnClickListener(view -> {
            if (!busy) {
                continueWithoutPin();
            }
        });
        actionPanel.addView(skip, matchWrap(0));
    }

    private void continueWithoutPin() {
        // Remember the decision so the gate stops prompting, then proceed into
        // the application. The flag alone no longer opens the gate: this screen
        // is only reached when the vault reported NOT_ENROLLED, and that is the
        // half of the answer NovaPinSession.isUnlocked() insists on.
        NovaPrivacySettings.global(getApplicationContext()).setAppPinDeclined(true);
        NovaPinSession.onPinStateChanged();
        completeGate();
    }

    private void showConfirmation() {
        mode = Mode.ENROLL_CONFIRM;
        clearInput();
        titleView.setText(R.string.NovaPinConfirmTitle);
        descriptionView.setText(R.string.NovaPinHiddenInputHint);
        errorView.setText("");
        // Drop the "continue without PIN" button: the user is mid-enrollment.
        actionPanel.removeAllViews();
        renderKeypad();
    }

    private void showUnlock() {
        mode = Mode.UNLOCK;
        clearInput();
        titleView.setText(R.string.NovaPinUnlockTitle);
        descriptionView.setText(R.string.NovaPinHiddenInputHint);
        errorView.setText("");
        actionPanel.removeAllViews();
        setBusy(false);
        renderKeypad();
    }

    private void showDisableVerify() {
        mode = Mode.DISABLE_VERIFY;
        clearInput();
        titleView.setText(R.string.NovaPinDisableTitle);
        descriptionView.setText(R.string.NovaPinDisableDescription);
        errorView.setText("");
        actionPanel.removeAllViews();
        setBusy(false);
        renderKeypad();
    }

    private void showEmergencyVerify() {
        mode = Mode.EMERGENCY_VERIFY;
        clearInput();
        clearSecret(firstPin);
        firstPin = null;
        titleView.setText(R.string.NovaEmergencyVerifyTitle);
        descriptionView.setText(R.string.NovaEmergencyVerifyDescription);
        errorView.setText("");
        actionPanel.removeAllViews();
        setBusy(false);
        renderKeypad();
    }

    private void showEmergencyFirst() {
        mode = Mode.EMERGENCY_FIRST;
        clearInput();
        clearSecret(firstPin);
        firstPin = null;
        titleView.setText(R.string.NovaEmergencyCreateTitle);
        descriptionView.setText(R.string.NovaEmergencyCreateDescription);
        errorView.setText("");
        actionPanel.removeAllViews();
        renderKeypad();
    }

    private void showEmergencyConfirm() {
        mode = Mode.EMERGENCY_CONFIRM;
        clearInput();
        titleView.setText(R.string.NovaEmergencyConfirmTitle);
        descriptionView.setText(R.string.NovaPinHiddenInputHint);
        errorView.setText("");
        renderKeypad();
    }

    private void beginEmergencyGate(char[] pin) {
        setBusy(true);
        descriptionView.setText(R.string.NovaPinCheckingPin);
        errorView.setText("");
        executor.execute(() -> {
            NovaPinVault.VerificationResult result;
            try {
                result = vault.verify(pin);
            } finally {
                clearSecret(pin);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false);
                // Entering the already stored emergency PIN here reports
                // EMERGENCY and lands in this branch as a plain refusal. That
                // is deliberate: setting a PIN up must never destroy the data.
                if (result.getStatus() == NovaPinVault.VerificationStatus.ACCEPTED) {
                    showEmergencyFirst();
                } else {
                    showEmergencyVerify();
                    errorView.setText(R.string.NovaPinIncorrect);
                    renderKeypad();
                }
            });
        });
    }

    private void beginEmergencyEnrollment(char[] pin) {
        setBusy(true);
        errorView.setText("");
        executor.execute(() -> {
            NovaPinVault.EmergencyStatus status;
            try {
                status = vault.enrollEmergency(pin);
            } finally {
                clearSecret(pin);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false);
                if (status == NovaPinVault.EmergencyStatus.ENROLLED) {
                    Toast.makeText(this, R.string.NovaEmergencyEnrolled, Toast.LENGTH_LONG).show();
                    finish();
                } else if (status == NovaPinVault.EmergencyStatus.SAME_AS_PRIMARY) {
                    showEmergencyFirst();
                    errorView.setText(R.string.NovaEmergencySameAsPrimary);
                } else if (status == NovaPinVault.EmergencyStatus.INVALID_PIN) {
                    showEmergencyFirst();
                    errorView.setText(R.string.NovaPinInvalidLength);
                } else {
                    showEmergencyFirst();
                    errorView.setText(R.string.NovaEmergencyFailed);
                }
            });
        });
    }

    private void showSoftwareWarning() {
        mode = Mode.SOFTWARE_WARNING;
        keypad.removeAllViews();
        titleView.setText(R.string.NovaPinSoftwareTitle);
        descriptionView.setText(R.string.NovaPinSoftwareWarning);
        errorView.setText("");
        actionPanel.removeAllViews();
        actionPanel.addView(actionButton(R.string.NovaPinUseSoftware, () -> {
            if (pendingSoftwarePin == null) {
                showFirstEnrollment();
                return;
            }
            char[] value = Arrays.copyOf(pendingSoftwarePin, pendingSoftwarePin.length);
            beginEnrollment(value, true);
        }), matchWrap(dp(8)));
        actionPanel.addView(actionButton(R.string.NovaPinChooseAnotherDevice, () -> {
            clearSecret(pendingSoftwarePin);
            pendingSoftwarePin = null;
            showFirstEnrollment();
        }), matchWrap(0));
    }

    private void showDeviceChanged() {
        mode = Mode.DEVICE_CHANGED;
        setBusy(false);
        clearInput();
        keypad.removeAllViews();
        actionPanel.removeAllViews();
        errorView.setText("");
        if (!NovaDeviceLock.isForeignCertain()) {
            // The data is not being called foreign - the device just could not
            // say whether the key that opens it is here. Nothing is started
            // over that, but neither is the owner offered the one irreversible
            // way out on the strength of a Keystore that did not answer.
            titleView.setText(R.string.NovaDeviceUnknownTitle);
            descriptionView.setText(R.string.NovaDeviceUnknownDescription);
            actionPanel.addView(
                    actionButton(R.string.NovaDeviceUnknownRetry, this::retryDeviceCheck),
                    matchWrap(0));
            return;
        }
        titleView.setText(R.string.NovaDeviceChangedTitle);
        descriptionView.setText(R.string.NovaDeviceChangedDescription);
        actionPanel.addView(
                actionButton(R.string.NovaDeviceChangedReset, this::confirmDeviceReset),
                matchWrap(0));
    }

    /** Asks the Keystore again, off the UI thread. */
    private void retryDeviceCheck() {
        if (busy) {
            return;
        }
        setBusy(true);
        descriptionView.setText(R.string.NovaPinCheckingProtection);
        executor.execute(() -> {
            boolean recovered = NovaDeviceLock.retryKeyAccess();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (recovered) {
                    // The key is back, but this process is not repairable: the
                    // network library already read the config without one and
                    // is holding an empty config in memory. Carrying on would
                    // mean either writing that emptiness over the sealed file
                    // or running with no authorization at all. Start again.
                    restartApplication();
                    return;
                }
                setBusy(false);
                inspectVault();
            });
        });
    }

    private void restartApplication() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
        System.exit(0);
    }

    private void confirmDeviceReset() {
        if (busy) {
            return;
        }
        // One deliberate confirmation. Nothing here can be read, but the sweep
        // that follows is final, and it is the owner who decides to give up.
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.NovaDeviceChangedReset)
                .setMessage(R.string.NovaDeviceChangedResetConfirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.NovaDeviceChangedReset,
                        (dialog, which) -> beginDeviceReset())
                .show();
    }

    private void beginDeviceReset() {
        setBusy(true);
        descriptionView.setText(R.string.NovaDeviceChangedResetting);
        executor.execute(() -> {
            NovaDeviceLock.resetForNewDevice(getApplicationContext());
            AndroidUtilities.runOnUIThread(() -> {
                Intent intent = new Intent(this, LaunchActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finishAffinity();
                System.exit(0);
            });
        });
    }

    private void showFatal(int message) {
        mode = Mode.FATAL;
        setBusy(false);
        keypad.removeAllViews();
        actionPanel.removeAllViews();
        titleView.setText(R.string.NovaPinTitle);
        descriptionView.setText(message);
        errorView.setText("");
        actionPanel.addView(actionButton(R.string.NovaPinRetry, this::inspectVault), matchWrap(0));
    }

    private void renderKeypad() {
        keypad.removeAllViews();
        List<Integer> digits = new ArrayList<>(10);
        for (int digit = 0; digit <= 9; digit++) {
            digits.add(digit);
        }
        Collections.shuffle(digits, keypadRandom);
        for (int index = 0; index < 9; index++) {
            int digit = digits.get(index);
            keypad.addView(new KeyView(
                    Integer.toString(digit), KEY_DIGIT, () -> appendDigit(digit)));
        }
        int lastDigit = digits.get(9);
        keypad.addView(new KeyView(
                getString(R.string.NovaPinClear), KEY_NEUTRAL, this::clearInput));
        keypad.addView(new KeyView(
                Integer.toString(lastDigit), KEY_DIGIT, () -> appendDigit(lastDigit)));
        keypad.addView(new KeyView(
                getString(R.string.NovaPinContinue), KEY_ACCENT, this::submit));
        keypad.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
    }

    private void appendDigit(int digit) {
        if (inputLength < input.length) {
            input[inputLength++] = (char) ('0' + digit);
        }
    }

    private void submit() {
        if (inputLength < NovaPrivacyContract.MIN_PIN_LENGTH
                || inputLength > NovaPrivacyContract.MAX_PIN_LENGTH) {
            errorView.setText(R.string.NovaPinInvalidLength);
            clearInput();
            renderKeypad();
            return;
        }
        char[] entered = Arrays.copyOf(input, inputLength);
        clearInput();
        if (mode == Mode.ENROLL_FIRST) {
            clearSecret(firstPin);
            firstPin = entered;
            showConfirmation();
        } else if (mode == Mode.ENROLL_CONFIRM) {
            if (!constantTimeEquals(firstPin, entered)) {
                clearSecret(entered);
                clearSecret(firstPin);
                firstPin = null;
                showFirstEnrollment();
                errorView.setText(R.string.NovaPinMismatch);
                return;
            }
            clearSecret(entered);
            char[] confirmed = firstPin;
            firstPin = null;
            clearSecret(pendingSoftwarePin);
            pendingSoftwarePin = Arrays.copyOf(confirmed, confirmed.length);
            beginEnrollment(confirmed, false);
        } else if (mode == Mode.UNLOCK) {
            beginVerification(entered);
        } else if (mode == Mode.DISABLE_VERIFY) {
            beginDisable(entered);
        } else if (mode == Mode.EMERGENCY_VERIFY) {
            beginEmergencyGate(entered);
        } else if (mode == Mode.EMERGENCY_FIRST) {
            clearSecret(firstPin);
            firstPin = entered;
            showEmergencyConfirm();
        } else if (mode == Mode.EMERGENCY_CONFIRM) {
            if (!constantTimeEquals(firstPin, entered)) {
                clearSecret(entered);
                clearSecret(firstPin);
                firstPin = null;
                showEmergencyFirst();
                errorView.setText(R.string.NovaPinMismatch);
                return;
            }
            clearSecret(entered);
            char[] confirmed = firstPin;
            firstPin = null;
            beginEmergencyEnrollment(confirmed);
        } else {
            clearSecret(entered);
        }
    }

    private void beginEnrollment(char[] pin, boolean acceptSoftware) {
        setBusy(true);
        descriptionView.setText(R.string.NovaPinCreatingProtection);
        errorView.setText("");
        executor.execute(() -> {
            NovaPinVault.EnrollmentResult result;
            try {
                result = vault.enroll(pin, acceptSoftware);
            } finally {
                clearSecret(pin);
            }
            runOnUiThread(() -> handleEnrollment(result));
        });
    }

    private void handleEnrollment(NovaPinVault.EnrollmentResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        switch (result.getStatus()) {
            case ENROLLED:
                clearSecret(pendingSoftwarePin);
                pendingSoftwarePin = null;
                // Setting a PIN overrides an earlier "continue without PIN".
                NovaPrivacySettings.global(getApplicationContext()).setAppPinDeclined(false);
                // The vault is what the session asks whether a PIN exists.
                NovaPinSession.onPinStateChanged();
                completeGate();
                break;
            case SOFTWARE_CONSENT_REQUIRED:
                showSoftwareWarning();
                break;
            case INVALID_PIN:
                showFirstEnrollment();
                errorView.setText(R.string.NovaPinInvalidLength);
                break;
            case ALREADY_ENROLLED:
                clearSecret(pendingSoftwarePin);
                pendingSoftwarePin = null;
                showUnlock();
                break;
            case CORRUPT_STATE:
                showFatal(R.string.NovaPinCorruptState);
                break;
            case UNAVAILABLE:
            default:
                showFatal(R.string.NovaPinStorageUnavailable);
                break;
        }
    }

    private void beginEmergencyWipe() {
        // Deliberately indistinguishable from a correct PIN on screen: someone
        // watching the entry must not learn that the emergency PIN was used.
        setBusy(true);
        descriptionView.setText(R.string.NovaPinCheckingPin);
        errorView.setText("");
        executor.execute(() -> {
            // Claimed before anything else: this device is being destroyed by
            // the PIN typed here, and the line written below comes back over
            // the update stream a moment later. Without the claim that echo
            // would start a second destruction on top of this one, in the
            // middle of the wait.
            NovaSyncDeauth.claimWipe();
            // Warn the account's other clients first and destroy second, never
            // the other way round: the wipe takes the authorization key with it
            // and after that there is nothing left to warn anybody with.
            // Returns at once when there is nothing to send, and after twelve
            // seconds at the latest when the network never answers - the screen
            // shows the same "checking" state it shows for an ordinary PIN
            // throughout, so the wait gives nothing away.
            NovaSyncDeauth.broadcastAndWait(getApplicationContext());
            NovaEmergencyWipe.run(getApplicationContext());
            AndroidUtilities.runOnUIThread(() -> {
                Intent intent = new Intent(this, LaunchActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finishAffinity();
                System.exit(0);
            });
        });
    }

    private void beginVerification(char[] pin) {
        setBusy(true);
        descriptionView.setText(R.string.NovaPinCheckingPin);
        errorView.setText("");
        executor.execute(() -> {
            NovaPinVault.VerificationResult result;
            try {
                result = vault.verify(pin);
            } finally {
                clearSecret(pin);
            }
            runOnUiThread(() -> handleVerification(result));
        });
    }

    private void beginDisable(char[] pin) {
        setBusy(true);
        descriptionView.setText(R.string.NovaPinCheckingPin);
        errorView.setText("");
        executor.execute(() -> {
            NovaPinVault.VerificationResult result;
            try {
                result = vault.disable(pin);
            } finally {
                clearSecret(pin);
            }
            runOnUiThread(() -> handleDisable(result));
        });
    }

    private void handleDisable(NovaPinVault.VerificationResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        switch (result.getStatus()) {
            case ACCEPTED:
                // The application stays open and unlocked - the user is
                // standing in its settings. Remembering the refusal keeps the
                // "set a PIN" prompt from asking again on the next start,
                // which after a deliberate removal would read as nagging.
                NovaPrivacySettings.global(getApplicationContext())
                        .setAppPinDeclined(true);
                // The vault has just been emptied; the session caches that
                // answer and has to be told to ask again.
                NovaPinSession.onPinStateChanged();
                NovaPinSession.unlock();
                Toast.makeText(this, R.string.NovaPinDisabled, Toast.LENGTH_LONG).show();
                finish();
                break;
            case EMERGENCY:
                // Answered exactly as everywhere else, and deliberately so:
                // being made to switch the PIN off is the situation the
                // emergency PIN is for.
                beginEmergencyWipe();
                break;
            case REJECTED:
                showDisableVerify();
                errorView.setText(R.string.NovaPinIncorrect);
                renderKeypad();
                if (result.getRetryAfterMillis() > 0L) {
                    startCountdown(result.getRetryAfterMillis());
                }
                break;
            case LOCKED:
                showDisableVerify();
                startCountdown(result.getRetryAfterMillis());
                break;
            case NOT_ENROLLED:
                finish();
                break;
            case INVALID_PIN:
                showDisableVerify();
                errorView.setText(R.string.NovaPinInvalidLength);
                break;
            case CORRUPT_STATE:
                showFatal(R.string.NovaPinCorruptState);
                break;
            case UNAVAILABLE:
            default:
                showFatal(R.string.NovaPinStorageUnavailable);
                break;
        }
    }

    private void handleVerification(NovaPinVault.VerificationResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);
        switch (result.getStatus()) {
            case ACCEPTED:
                completeGate();
                break;
            case EMERGENCY:
                beginEmergencyWipe();
                break;
            case REJECTED:
                showUnlock();
                errorView.setText(R.string.NovaPinIncorrect);
                renderKeypad();
                if (result.getRetryAfterMillis() > 0L) {
                    startCountdown(result.getRetryAfterMillis());
                }
                break;
            case LOCKED:
                showUnlock();
                startCountdown(result.getRetryAfterMillis());
                break;
            case NOT_ENROLLED:
                showFirstEnrollment();
                break;
            case INVALID_PIN:
                showUnlock();
                errorView.setText(R.string.NovaPinInvalidLength);
                break;
            case CORRUPT_STATE:
                showFatal(R.string.NovaPinCorruptState);
                break;
            case UNAVAILABLE:
            default:
                showFatal(R.string.NovaPinStorageUnavailable);
                break;
        }
    }

    private void startCountdown(long duration) {
        handler.removeCallbacks(countdown);
        countdownDeadline = safeAdd(SystemClock.elapsedRealtime(), duration);
        // The keypad deliberately stays usable while the delay runs. Hiding it
        // also hid the emergency PIN, so an attacker only had to burn three
        // attempts to disable the one thing the owner needs under coercion.
        // A wrong PIN entered now is answered with LOCKED and costs no extra
        // attempt, while the emergency PIN is checked ahead of the delay.
        setBusy(false);
        countdown.run();
    }

    private void completeGate() {
        NovaPinSession.unlock();
        startActivity(NovaPinSession.takePendingLaunch(this));
        overridePendingTransition(0, 0);
        finish();
    }

    private void setBusy(boolean value) {
        busy = value;
        keypad.setVisibility(value ? View.INVISIBLE : View.VISIBLE);
    }

    private void clearInput() {
        NovaSecretWiper.wipe(input);
        inputLength = 0;
    }

    private static void clearSecret(char[] value) {
        if (value != null) {
            NovaSecretWiper.wipe(value);
        }
    }

    private static boolean constantTimeEquals(char[] left, char[] right) {
        if (left == null || right == null) {
            return false;
        }
        int difference = left.length ^ right.length;
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            char a = index < left.length ? left[index] : 0;
            char b = index < right.length ? right[index] : 0;
            difference |= a ^ b;
        }
        return difference == 0;
    }

    private TextView actionButton(int label, Runnable action) {
        TextView button = text(16, accentTextColor, Typeface.BOLD, Gravity.CENTER);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setPadding(dp(20), dp(12), dp(20), dp(12));
        button.setBackground(pill(accentFillColor, dp(12)));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView text(int sp, int color, int style, int gravity) {
        TextView view = new TextView(this);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setGravity(gravity);
        return view;
    }

    /**
     * A filled capsule or rounded button with the client's own ripple. Guarded
     * like the colours are: if Theme cannot answer, the key keeps its shape and
     * stays operable, only without the pressed animation.
     */
    private Drawable pill(int fill, int radius) {
        try {
            return Theme.createSimpleSelectorRoundRectDrawable(radius, fill, rippleColor);
        } catch (Throwable e) {
            FileLog.e(e);
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(fill);
            drawable.setCornerRadius(radius);
            return drawable;
        }
    }

    private Drawable circle(int fill) {
        try {
            // The size is a hint only: the oval resizes itself to the bounds.
            return Theme.createSimpleSelectorCircleDrawable(dp(72), fill, rippleColor);
        } catch (Throwable e) {
            FileLog.e(e);
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(fill);
            return drawable;
        }
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        return spaced(0, bottomMargin);
    }

    private LinearLayout.LayoutParams spaced(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static long safeAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /**
     * Keeps the block one readable column wide. Without this the title, the
     * explanation and the keypad all stretch across a tablet or a landscape
     * window; the ScrollView centres whatever width is left over.
     */
    private final class ContentLayout extends LinearLayout {
        ContentLayout() {
            super(NovaPinGateActivity.this);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            int size = MeasureSpec.getSize(widthMeasureSpec);
            int maximum = dp(MAX_CONTENT_WIDTH_DP);
            if (widthMode != MeasureSpec.UNSPECIFIED && size > maximum) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /**
     * The 3x4 keypad. Laid out by hand rather than by a GridLayout because the
     * cell size is derived, not fixed: the keys grow with the window up to a
     * cap, shrink when the window is short, and never go below the platform's
     * 48 dp touch floor — below that the block scrolls instead.
     *
     * <p>Children are added in reading order and drawn in reading order. It
     * holds no digit map of its own: which digit sits where is decided once in
     * {@link #renderKeypad()} and lives only in each key's own click action, so
     * no id, position or description here can be read back to recover it. The
     * whole subtree stays excluded from accessibility for the same reason.</p>
     */
    private final class KeypadView extends ViewGroup {
        private int heightBudget;
        private int keySize;
        private int columnPitch;

        KeypadView() {
            super(NovaPinGateActivity.this);
            // The shuffle must not leak through the accessibility tree: with
            // the descendants hidden, no service can read the key order.
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        void setHeightBudget(int value) {
            if (heightBudget != value) {
                heightBudget = value;
                requestLayout();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int available = MeasureSpec.getSize(widthMeasureSpec);
            int rowGap = dp(12);
            int keyInset = dp(20);
            int pitch = Math.max(dp(56), Math.min(available / KEYPAD_COLUMNS, dp(92)));
            int key = Math.max(dp(48), Math.min(pitch - keyInset, dp(72)));
            if (heightBudget > 0) {
                int byHeight = (heightBudget - rowGap * (KEYPAD_ROWS - 1)) / KEYPAD_ROWS;
                if (byHeight < key) {
                    key = Math.max(dp(48), byHeight);
                }
            }
            // Keep the horizontal spacing proportional to the key that was
            // actually chosen, so a shrunken keypad does not end up as small
            // keys scattered across a wide row.
            pitch = Math.min(pitch, key + keyInset);
            keySize = key;
            columnPitch = pitch;

            int digitSpec = MeasureSpec.makeMeasureSpec(key, MeasureSpec.EXACTLY);
            // The two word keys take nearly the whole column: they carry a word,
            // not a glyph, and the wider capsule keeps that word legible.
            int wideSpec = MeasureSpec.makeMeasureSpec(pitch - dp(6), MeasureSpec.EXACTLY);
            int count = getChildCount();
            for (int index = 0; index < count; index++) {
                View child = getChildAt(index);
                boolean wide = child instanceof KeyView && ((KeyView) child).isWide();
                child.measure(wide ? wideSpec : digitSpec, digitSpec);
            }
            int rows = (count + KEYPAD_COLUMNS - 1) / KEYPAD_COLUMNS;
            int height = rows == 0 ? 0 : rows * key + (rows - 1) * rowGap;
            setMeasuredDimension(available, height);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int rowGap = dp(12);
            int gridWidth = columnPitch * KEYPAD_COLUMNS;
            int origin = (getWidth() - gridWidth) / 2;
            int count = getChildCount();
            for (int index = 0; index < count; index++) {
                View child = getChildAt(index);
                int row = index / KEYPAD_COLUMNS;
                int column = index % KEYPAD_COLUMNS;
                int centerX = origin + column * columnPitch + columnPitch / 2;
                int childTop = row * (keySize + rowGap);
                int width = child.getMeasuredWidth();
                child.layout(
                        centerX - width / 2,
                        childTop,
                        centerX - width / 2 + width,
                        childTop + keySize);
            }
        }
    }

    /**
     * One key. Custom-drawn so the label can be centred on the glyphs rather
     * than on the font's line box — digits carry no descender, and a TextView
     * centred by its line box sits visibly high inside a circle.
     *
     * <p>Deliberately carries no content description and no id: the label is
     * the only place the digit appears, and the parent hides the whole subtree
     * from accessibility.</p>
     */
    private final class KeyView extends View {
        private final String label;
        private final int style;
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Rect ink = new Rect();
        private int fittedForWidth = -1;
        private int fittedForHeight = -1;
        private float baseline;

        KeyView(String label, int style, Runnable action) {
            super(NovaPinGateActivity.this);
            this.label = label == null ? "" : label;
            this.style = style;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(keyTypeface);
            paint.setColor(style == KEY_ACCENT ? accentTextColor : keyTextColor);
            // Both shapes resize themselves to the view's bounds, so the same
            // drawable is correct at every key size; a capsule radius wider than
            // half the height is clamped down when the key shrinks.
            setBackground(style == KEY_DIGIT
                    ? circle(keyFillColor)
                    : pill(style == KEY_ACCENT ? accentFillColor : keyFillColor, dp(36)));
            setClickable(true);
            setFocusable(false);
            setSoundEffectsEnabled(false);
            setHapticFeedbackEnabled(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setOnClickListener(view -> {
                if (!busy) {
                    action.run();
                }
            });
        }

        boolean isWide() {
            return style != KEY_DIGIT;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0 || label.isEmpty()) {
                return;
            }
            if (fittedForWidth != width || fittedForHeight != height) {
                // A digit has room to spare; the word keys need most of the
                // capsule, "Продолжить" being the longest label on the screen.
                fitText(width - dp(style == KEY_DIGIT ? 8 : 7) * 2, height);
                // Optical centring: the glyph box, not the font's line box. A
                // digit has no descender, so centring on ascent and descent
                // leaves it sitting visibly above the middle of the circle.
                paint.getTextBounds(label, 0, label.length(), ink);
                baseline = height / 2f - (ink.top + ink.bottom) / 2f;
                fittedForWidth = width;
                fittedForHeight = height;
            }
            canvas.drawText(label, width / 2f, baseline, paint);
        }

        /**
         * Sized from the key, not from the user's font scale: a digit that grew
         * past its circle would be worse than a digit that stayed put. The word
         * keys shrink until they fit on one line, which is what the previous
         * auto-sizing TextView was there for.
         */
        private void fitText(int room, int height) {
            float largest = style == KEY_DIGIT
                    ? Math.min(height * 0.40f, dp(30))
                    : Math.min(height * 0.30f, dp(15));
            float smallest = style == KEY_DIGIT ? dp(14) : dp(9);
            float size = largest;
            paint.setTextSize(size);
            while (size > smallest && room > 0 && paint.measureText(label) > room) {
                size -= 1f;
                paint.setTextSize(size);
            }
        }
    }

    private enum Mode {
        LOADING,
        ENROLL_FIRST,
        ENROLL_CONFIRM,
        SOFTWARE_WARNING,
        UNLOCK,
        DISABLE_VERIFY,
        EMERGENCY_VERIFY,
        EMERGENCY_FIRST,
        EMERGENCY_CONFIRM,
        DEVICE_CHANGED,
        FATAL
    }
}
