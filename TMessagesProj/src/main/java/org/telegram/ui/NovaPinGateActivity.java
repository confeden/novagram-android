package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.novagram.privacy.NovaEmergencyWipe;
import org.telegram.messenger.novagram.privacy.NovaPinSession;
import org.telegram.messenger.novagram.privacy.NovaPinVault;
import org.telegram.messenger.novagram.privacy.NovaPrivacyContract;
import org.telegram.messenger.novagram.privacy.NovaSecretWiper;

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

    private static final int BACKGROUND_COLOR = Color.rgb(23, 33, 43);
    private static final int PANEL_COLOR = Color.rgb(36, 47, 61);
    private static final int PRIMARY_COLOR = Color.rgb(82, 136, 193);
    private static final int TEXT_COLOR = Color.WHITE;
    private static final int SECONDARY_TEXT_COLOR = Color.rgb(174, 183, 194);
    private static final int ERROR_COLOR = Color.rgb(232, 94, 94);

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
    private LinearLayout content;
    private GridLayout keypad;
    private LinearLayout actionPanel;
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
        getWindow().setStatusBarColor(BACKGROUND_COLOR);
        getWindow().setNavigationBarColor(BACKGROUND_COLOR);
        vault = new NovaPinVault(this);
        setContentView(createContent());
        inspectVault();
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
        if (isEmergencySetupRequested()) {
            super.onBackPressed();
        }
    }

    private boolean isEmergencySetupRequested() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_SETUP_EMERGENCY, false);
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
        root.setBackgroundColor(BACKGROUND_COLOR);
        root.setFilterTouchesWhenObscured(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(24);
        content.setPadding(side, dp(36), side, dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        titleView = text(26, TEXT_COLOR, Typeface.BOLD, Gravity.CENTER);
        content.addView(titleView, matchWrap(dp(12)));

        descriptionView = text(16, SECONDARY_TEXT_COLOR, Typeface.NORMAL, Gravity.CENTER);
        content.addView(descriptionView, matchWrap(dp(16)));

        errorView = text(15, ERROR_COLOR, Typeface.NORMAL, Gravity.CENTER);
        errorView.setMinHeight(dp(48));
        content.addView(errorView, matchWrap(dp(8)));

        keypad = new GridLayout(this);
        keypad.setColumnCount(3);
        keypad.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        content.addView(keypad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        actionPanel = new LinearLayout(this);
        actionPanel.setOrientation(LinearLayout.VERTICAL);
        actionPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(actionPanel, matchWrap(0));
        return root;
    }

    private void inspectVault() {
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
                        showFirstEnrollment();
                        break;
                    case ENROLLED:
                        if (isEmergencySetupRequested()) {
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
    }

    private void showConfirmation() {
        mode = Mode.ENROLL_CONFIRM;
        clearInput();
        titleView.setText(R.string.NovaPinConfirmTitle);
        descriptionView.setText(R.string.NovaPinHiddenInputHint);
        errorView.setText("");
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
            addKey(Integer.toString(digit), () -> appendDigit(digit));
        }
        addKey(getString(R.string.NovaPinClear), this::clearInput);
        addKey(Integer.toString(digits.get(9)), () -> appendDigit(digits.get(9)));
        addKey(getString(R.string.NovaPinContinue), this::submit);
        keypad.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
    }

    private void addKey(String label, Runnable action) {
        TextView key = text(18, TEXT_COLOR, Typeface.BOLD, Gravity.CENTER);
        key.setText(label);
        key.setClickable(true);
        key.setFocusable(false);
        key.setSoundEffectsEnabled(false);
        key.setHapticFeedbackEnabled(false);
        key.setBackground(constantBackground(PANEL_COLOR, dp(10)));
        key.setOnClickListener(view -> {
            if (!busy) {
                action.run();
            }
        });
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(58);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        keypad.addView(key, params);
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
        TextView button = text(16, TEXT_COLOR, Typeface.BOLD, Gravity.CENTER);
        button.setText(label);
        button.setMinHeight(dp(52));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setBackground(constantBackground(PRIMARY_COLOR, dp(10)));
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

    private static GradientDrawable constantBackground(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static long safeAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private enum Mode {
        LOADING,
        ENROLL_FIRST,
        ENROLL_CONFIRM,
        SOFTWARE_WARNING,
        UNLOCK,
        EMERGENCY_VERIFY,
        EMERGENCY_FIRST,
        EMERGENCY_CONFIRM,
        FATAL
    }
}
