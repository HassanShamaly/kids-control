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
        boolean emergency = intent != null && intent.getBooleanExtra("emergency", false);
        Prefs prefs = new Prefs(this);
        if (emergency || prefs.breakActive()) {
            emergencyPin(prefs);
        } else {
            showBiometric();
        }
    }

    // ===== مدخل الطوارئ: رقم سري =====
    private void emergencyPin(final Prefs prefs) {
        if (!prefs.hasPin()) { sendBreakOverride(); finish(); return; }
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
                    if (prefs.checkPin(in.getText().toString().trim())) {
                        sendBreakOverride();
                    } else {
                        Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_LONG).show();
                        reshowOverlay();
                    }
                    finish();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> { reshowOverlay(); finish(); })
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
