package com.almurshid.kidscontrol;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * شاشة فتح القفل:
 * - قفل عادي: بصمة ولي الأمر.
 * - مدخل الطوارئ أثناء الاستراحة: الرقم السري (الطبقة تكون قد أُزيلت من الخدمة
 *   قبل فتح هذه الشاشة، فيظهر الإدخال فوق كل شيء). البصمة لا تنهي الاستراحة.
 */
public class UnlockActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        boolean owner = intent != null && intent.getBooleanExtra("owner", false);
        boolean emergency = intent != null && intent.getBooleanExtra("emergency", false);
        Prefs prefs = new Prefs(this);
        pinMode = false;
        if (prefs.isChild()) {
            // جهاز الطفل: بصمة الجهاز ورمز قفله قد يكونان للطفل نفسه، فلا يُقبل إلا رقم التطبيق السري.
            // أثناء الاستراحة لا فتح محلي إطلاقاً (من جوال ولي الأمر فقط).
            if (prefs.breakActive() || !prefs.hasPin()) { reshowOverlay(); finish(); return; }
            childPin(prefs);
            return;
        }
        if (owner) { ownerUnlock(prefs); return; }   // جهاز ولي الأمر: بصمة أو رقم سري
        if (emergency || prefs.breakActive()) {
            emergencyPin(prefs);
        } else {
            showBiometric();
        }
    }

    private boolean pinMode = false;   // نافذة رقم سري مفتوحة والطبقة مُزالة مؤقتاً

    /** إن غادر المستخدم الشاشة ونافذة الرقم السري مفتوحة: أعِد القفل فوراً. */
    @Override
    protected void onStop() {
        super.onStop();
        if (pinMode && !isFinishing()) { pinMode = false; reshowOverlay(); finish(); }
    }

    // ===== جهاز الطفل: القفل العادي يُفتح برقم التطبيق السري فقط =====
    private void childPin(final Prefs prefs) {
        pinMode = true;
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.unlock_title)
                .setMessage(R.string.child_pin_sub)
                .setView(in)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    pinMode = false;
                    if (prefs.checkPin(in.getText().toString().trim())) {
                        LocalBroadcastManager.getInstance(this)
                                .sendBroadcast(new Intent(MonitorService.ACTION_UNLOCK_OK));
                    } else {
                        Toast.makeText(this, prefs.pinLocked() ? getString(R.string.pin_locked_fmt, (int) ((prefs.pinLockedSec() + 59) / 60)) : getString(R.string.pin_wrong), Toast.LENGTH_LONG).show();
                        reshowOverlay();
                    }
                    finish();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> { pinMode = false; reshowOverlay(); finish(); })
                .show();
    }

    // ===== مدخل الطوارئ: رقم سري =====
    private void emergencyPin(final Prefs prefs) {
        if (!prefs.hasPin()) { sendBreakOverride(); finish(); return; }
        pinMode = true;
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.break_pin_title)
                .setMessage(R.string.break_pin_sub)
                .setView(in)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    pinMode = false;
                    if (prefs.checkPin(in.getText().toString().trim())) {
                        sendBreakOverride();
                    } else {
                        Toast.makeText(this, prefs.pinLocked() ? getString(R.string.pin_locked_fmt, (int) ((prefs.pinLockedSec() + 59) / 60)) : getString(R.string.pin_wrong), Toast.LENGTH_LONG).show();
                        reshowOverlay();
                    }
                    finish();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> { pinMode = false; reshowOverlay(); finish(); })
                .show();
    }

    private void reshowOverlay() {
        startService(new Intent(this, MonitorService.class)
                .setAction(MonitorService.ACTION_RESHOW_OVERLAY));
    }

    private void sendBreakOverride() {
        Intent i = new Intent(MonitorService.ACTION_UNLOCK_OK);
        i.putExtra("break_override", true);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ===== جهاز ولي الأمر: بصمتك أولاً، و«الرقم السري» بديلاً (تنهي القفل أو الاستراحة) =====
    private void ownerUnlock(final Prefs prefs) {
        final int bio = BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.BIOMETRIC_WEAK;
        if (BiometricManager.from(this).canAuthenticate(bio) != BiometricManager.BIOMETRIC_SUCCESS) {
            emergencyPin(prefs);
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        sendBreakOverride();   // ينهي الاستراحة أو القفل العادي
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence errString) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || code == BiometricPrompt.ERROR_LOCKOUT
                                || code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                                || code == BiometricPrompt.ERROR_NO_BIOMETRICS
                                || code == BiometricPrompt.ERROR_HW_UNAVAILABLE) {
                            emergencyPin(prefs);         // الرقم السري للتطبيق
                        } else {
                            reshowOverlay();             // أُلغي: أعِد القفل فوراً
                            finish();
                        }
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_title))
                .setSubtitle(getString(R.string.owner_unlock_sub))
                .setAllowedAuthenticators(bio)
                .setNegativeButtonText(getString(R.string.use_pin))
                .build();
        try {
            prompt.authenticate(info);
        } catch (Throwable t) {
            emergencyPin(prefs);
        }
    }

    // ===== قفل عادي: بصمة =====
    private void showBiometric() {
        BiometricManager bm = BiometricManager.from(this);
        int allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.BIOMETRIC_WEAK;

        if (bm.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }

        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        LocalBroadcastManager.getInstance(UnlockActivity.this)
                                .sendBroadcast(new Intent(MonitorService.ACTION_UNLOCK_OK));
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        finish();
                    }
                });

        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_title))
                .setSubtitle(getString(R.string.unlock_sub));

        if ((allowed & BiometricManager.Authenticators.DEVICE_CREDENTIAL) != 0) {
            b.setAllowedAuthenticators(allowed);
        } else {
            b.setAllowedAuthenticators(allowed);
            b.setNegativeButtonText(getString(R.string.cancel));
        }

        try {
            prompt.authenticate(b.build());
        } catch (Throwable t) {
            Toast.makeText(this, R.string.biometric_unavailable, Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
