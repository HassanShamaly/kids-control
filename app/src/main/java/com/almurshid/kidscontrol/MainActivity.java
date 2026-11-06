package com.almurshid.kidscontrol;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** الواجهة الرئيسية: محمية برقم سري + بصمة، ومنها تُضبط المدد والصلاحيات. */
public class MainActivity extends AppCompatActivity {

    /** مدة «وضع ولي الأمر» بعد آخر استخدام للتطبيق: تُسمح فيها صفحات التطبيق في الإعدادات. */
    private static final long PARENT_MODE_MS = 5 * 60_000L;

    private static final int PICK_VIDEO = 1, PICK_GAMES = 2, PICK_ALLOWED = 3;

    private Prefs prefs;
    private boolean authed = false;      // تم فتح قسم الإعدادات

    private EditText etYt, etGame, etCooldown, etNightLimit, etBreakThreshold, etBreakMinutes, etResume, etRevert, etRelaxLimit;
    private CheckBox cbYt, cbGame, cbSchedule, cbNight, cbRevert, cbRelax, cbExcludeFb, cbExcludeIg;
    private java.util.Set<String> relaxDays;
    private TextView tvDefaultStatus;
    private Button btnSchedFrom, btnSchedTo, btnNightFrom, btnNightTo;
    private TextView tvUsage, tvOverlay, tvA11y, tvNotif, tvAdmin, tvStatus;
    private TextView tvHomeStatus, tvHomeSub;
    private Button btnStart, btnStop;
    private int schedFrom, schedTo, nightFrom, nightTo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_main);
        setupHome();
        showCrashIfAny();
        if (getIntent() != null && getIntent().getBooleanExtra("stop_request", false)) {
            getIntent().removeExtra("stop_request");
            tvHomeStatus.post(this::confirmStop);   // طلب الإيقاف من الإشعار
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("stop_request", false)) {
            intent.removeExtra("stop_request");
            confirmStop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // عند العودة للتطبيق (من الخلفية أيضاً) نعود دائماً للشاشة الرئيسية
        // ونُغلق قسم الإعدادات، فلا يصل إليه الطفل بلا رقم سري.
        if (authed) closeSettingsSection();
        refreshHome();
        if (authed) refreshPermissionStatus();
    }

    @Override
    protected void onDestroy() {
        // الخروج من التطبيق ينهي وضع ولي الأمر فوراً
        if (authed && isFinishing()) prefs.setParentUntil(0L);
        super.onDestroy();
    }

    // ===== الشاشة الرئيسية (بلا رقم سري) =====
    private void setupHome() {
        tvHomeStatus = findViewById(R.id.tv_home_status);
        tvHomeSub = findViewById(R.id.tv_home_sub);
        findViewById(R.id.btn_home_start).setOnClickListener(v -> startMonitoring()); // تشغيل بضغطة
        findViewById(R.id.btn_home_stop).setOnClickListener(v -> confirmStop());      // إيقاف برقم سري
        findViewById(R.id.btn_home_close).setOnClickListener(v -> confirmFullClose()); // إغلاق تام برقم سري
        findViewById(R.id.btn_open_settings).setOnClickListener(v -> openSettings()); // رقم سري + بصمة
        findViewById(R.id.btn_download_latest).setOnClickListener(v -> openReleases());
        TextView ver = findViewById(R.id.tv_version);
        try {
            ver.setText(getString(R.string.version_fmt,
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
        } catch (Exception ignored) { }
        refreshHome();
    }

    private static final String RELEASES_URL =
            "https://github.com/HassanShamaly/kids-control/releases/latest";

    /** يفتح صفحة الإصدارات في المتصفح لتنزيل آخر نسخة يدوياً. */
    private void openReleases() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RELEASES_URL)));
        } catch (Throwable t) {
            Toast.makeText(this, RELEASES_URL, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshHome() {
        if (tvHomeStatus == null) return;
        boolean on = prefs.enabled();
        if (on && prefs.locked()) {
            tvHomeStatus.setText(R.string.home_locked);
            tvHomeSub.setText(R.string.home_on_sub);
        } else if (on && prefs.inPauseWindow()) {
            tvHomeStatus.setText(R.string.home_paused);
            tvHomeSub.setText(R.string.home_on_sub);
        } else if (on) {
            tvHomeStatus.setText(R.string.home_on);
            tvHomeSub.setText(R.string.home_on_sub);
        } else {
            tvHomeStatus.setText(R.string.home_off);
            long rAt = prefs.resumeAt();
            if (rAt > System.currentTimeMillis()) {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.setTimeInMillis(rAt);
                String t = String.format(Locale.US, "%02d:%02d",
                        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
                tvHomeSub.setText(getString(R.string.home_resume_sub, t));
            } else {
                tvHomeSub.setText(R.string.home_off_sub);
            }
        }
    }

    /** إيقاف المراقبة يتطلب الرقم السري. */
    private void confirmStop() {
        if (!prefs.enabled() && !MonitorService.isActive()) {
            Toast.makeText(this, R.string.already_off, Toast.LENGTH_SHORT).show(); return;
        }
        if (!prefs.hasPin()) { stopMonitoring(); refreshHome(); return; }
        final EditText in = pinInput();
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_monitor)
                .setMessage(R.string.enter_pin_title)
                .setView(in)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (prefs.checkPin(in.getText().toString().trim())) {
                        stopMonitoring();
                        refreshHome();
                    } else {
                        Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.forgot_pin, (d, w) -> startResetPin())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** إغلاق التطبيق تماماً — يتطلب الرقم السري. */
    private void confirmFullClose() {
        if (!prefs.hasPin()) { fullClose(); return; }
        final EditText in = pinInput();
        new AlertDialog.Builder(this)
                .setTitle(R.string.close_app)
                .setMessage(R.string.close_confirm)
                .setView(in)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (prefs.checkPin(in.getText().toString().trim())) fullClose();
                    else Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fullClose() {
        prefs.setEnabled(false);
        prefs.setResumeAt(0);
        prefs.setLocked(false, 0);
        Intent svc = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_FULL_STOP);
        startService(svc);
        Toast.makeText(this, R.string.closed_fully, Toast.LENGTH_SHORT).show();
        refreshHome();
    }

    /** فتح الإعدادات: رقم سري ثم بصمة. */
    private void openSettings() {
        if (!prefs.hasPin()) {
            promptCreatePin();   // أول مرة: أنشئ الرقم السري ثم افتح الإعدادات
        } else {
            promptPin();
        }
    }

    /** يعرض آخر انهيار محفوظ (إن وُجد) مع خيار النسخ والمسح. */
    private void showCrashIfAny() {
        final SharedPreferences sp = getSharedPreferences(KidsApp.CRASH_PREFS, MODE_PRIVATE);
        final String crash = sp.getString(KidsApp.K_CRASH, null);
        if (crash == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(crash)
                .setPositiveButton(R.string.crash_copy, (d, w) -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("crash", crash));
                        Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) { }
                })
                .setNegativeButton(R.string.crash_clear, (d, w) ->
                        sp.edit().remove(KidsApp.K_CRASH).apply())
                .show();
    }

    private void closeSettingsSection() {
        authed = false;
        prefs.setParentUntil(0L);
        findViewById(R.id.settings_root).setVisibility(View.GONE);
        findViewById(R.id.home_root).setVisibility(View.VISIBLE);
    }

    private void promptCreatePin() {
        final EditText in = pinInput();
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_pin_title)
                .setMessage(R.string.set_pin_msg)
                .setView(in)
                .setCancelable(false)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String p = in.getText().toString().trim();
                    if (p.length() < 4) {
                        Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_LONG).show();
                        promptCreatePin();
                    } else {
                        prefs.setPin(p);
                        if (!authed) requireBiometricThenReveal();
                        else Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptPin() {
        final EditText in = pinInput();
        new AlertDialog.Builder(this)
                .setTitle(R.string.enter_pin_title)
                .setView(in)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (prefs.checkPin(in.getText().toString().trim())) {
                        requireBiometricThenReveal();
                    } else {
                        Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(R.string.forgot_pin, (d, w) -> startResetPin())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ===== استرجاع الرقم السري عبر قفل الجهاز =====
    private static final int REQ_RESET_PIN = 9101;

    private void startResetPin() {
        android.app.KeyguardManager km =
                (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            Toast.makeText(this, R.string.reset_no_lock, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = km.createConfirmDeviceCredentialIntent(
                    getString(R.string.reset_pin_title), getString(R.string.reset_pin_sub));
            if (i != null) { startActivityForResult(i, REQ_RESET_PIN); return; }
        } catch (Throwable ignored) { }
        Toast.makeText(this, R.string.reset_no_lock, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESET_PIN && resultCode == RESULT_OK) {
            createNewPinAfterReset();
        }
    }

    /** بعد التحقق بقفل الجهاز: أنشئ رقماً سرياً جديداً ثم افتح الإعدادات. */
    private void createNewPinAfterReset() {
        final EditText in = pinInput();
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_pin_title)
                .setMessage(R.string.set_pin_msg)
                .setView(in)
                .setCancelable(false)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String p = in.getText().toString().trim();
                    if (p.length() < 4) {
                        Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_LONG).show();
                        createNewPinAfterReset();
                    } else {
                        prefs.setPin(p);
                        Toast.makeText(this, R.string.pin_reset_done, Toast.LENGTH_SHORT).show();
                        reveal();   // تم التحقق بقفل الجهاز، فادخل الإعدادات مباشرة
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private EditText pinInput() {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad, pad, pad);
        return in;
    }

    private void requireBiometricThenReveal() {
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
                    @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                        reveal();
                    }
                    @Override public void onAuthenticationError(int code, @NonNull CharSequence err) {
                        Toast.makeText(MainActivity.this, R.string.auth_failed, Toast.LENGTH_LONG).show();
                        // تبقى الشاشة الرئيسية؛ لا يُفتح قسم الإعدادات
                    }
                });

        BiometricPrompt.PromptInfo.Builder pb = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.gate_title))
                .setSubtitle(getString(R.string.gate_sub))
                .setAllowedAuthenticators(allowed);
        if ((allowed & BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
            pb.setNegativeButtonText(getString(R.string.cancel));
        }

        try {
            prompt.authenticate(pb.build());
        } catch (Throwable t) {
            // لا بصمة متاحة إطلاقاً: نكتفي بالرقم السري
            reveal();
        }
    }

    // ===== الإعدادات بعد التحقق =====
    private void reveal() {
        try {
            revealInner();
        } catch (Throwable t) {
            // لا ننهار: نعود للرئيسية ونعرض رسالة بدل الإغلاق المفاجئ
            closeSettingsSection();
            Toast.makeText(this, "تعذّر فتح الإعدادات: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void revealInner() {
        authed = true;
        prefs.setParentUntil(System.currentTimeMillis() + PARENT_MODE_MS);
        findViewById(R.id.home_root).setVisibility(View.GONE);
        findViewById(R.id.settings_root).setVisibility(View.VISIBLE);

        // زر عرض/إخفاء المتقدم
        final CheckBox cbAdv = findViewById(R.id.cb_advanced);
        final View advBox = findViewById(R.id.advanced_box);
        cbAdv.setOnCheckedChangeListener((b, checked) ->
                advBox.setVisibility(checked ? View.VISIBLE : View.GONE));

        etYt = findViewById(R.id.et_yt);
        etGame = findViewById(R.id.et_game);
        etCooldown = findViewById(R.id.et_cooldown);
        cbYt = findViewById(R.id.cb_yt);
        cbGame = findViewById(R.id.cb_game);
        cbSchedule = findViewById(R.id.cb_schedule);
        btnSchedFrom = findViewById(R.id.btn_sched_from);
        btnSchedTo = findViewById(R.id.btn_sched_to);
        tvUsage = findViewById(R.id.tv_usage_status);
        tvOverlay = findViewById(R.id.tv_overlay_status);
        tvA11y = findViewById(R.id.tv_a11y_status);
        tvNotif = findViewById(R.id.tv_notif_status);
        tvAdmin = findViewById(R.id.tv_admin_status);
        tvStatus = findViewById(R.id.tv_service_status);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        etYt.setText(String.valueOf(prefs.ytLimit()));
        etGame.setText(String.valueOf(prefs.gameLimit()));
        etCooldown.setText(String.valueOf(Math.max(1, prefs.cooldownSec() / 60)));
        cbYt.setChecked(prefs.ytOn());
        cbGame.setChecked(prefs.gameOn());
        cbSchedule.setChecked(prefs.schedOn());
        schedFrom = prefs.schedFrom();
        schedTo = prefs.schedTo();
        updateSchedButtons();
        btnSchedFrom.setOnClickListener(v -> pickTime(true));
        btnSchedTo.setOnClickListener(v -> pickTime(false));

        cbNight = findViewById(R.id.cb_night);
        btnNightFrom = findViewById(R.id.btn_night_from);
        btnNightTo = findViewById(R.id.btn_night_to);
        etNightLimit = findViewById(R.id.et_night_limit);
        etBreakThreshold = findViewById(R.id.et_break_threshold);
        etBreakMinutes = findViewById(R.id.et_break_minutes);
        cbNight.setChecked(prefs.nightOn());
        nightFrom = prefs.nightFrom();
        nightTo = prefs.nightTo();
        etNightLimit.setText(String.valueOf(prefs.nightLimit()));
        etBreakThreshold.setText(String.valueOf(prefs.breakThreshold()));
        etBreakMinutes.setText(String.valueOf(prefs.breakMinutes()));
        etResume = findViewById(R.id.et_resume);
        etResume.setText(String.valueOf(prefs.resumeMinutes()));

        // أيام مفتوحة
        cbRelax = findViewById(R.id.cb_relax);
        etRelaxLimit = findViewById(R.id.et_relax_limit);
        cbRelax.setChecked(prefs.relaxOn());
        etRelaxLimit.setText(String.valueOf(prefs.relaxLimit()));
        relaxDays = prefs.relaxDays();
        findViewById(R.id.btn_relax_days).setOnClickListener(v -> pickRelaxDays());
        // استثناء فيسبوك/إنستاجرام
        cbExcludeFb = findViewById(R.id.cb_exclude_fb);
        cbExcludeIg = findViewById(R.id.cb_exclude_ig);
        cbExcludeFb.setChecked(prefs.excludeFb());
        cbExcludeIg.setChecked(prefs.excludeIg());
        cbRevert = findViewById(R.id.cb_revert);
        etRevert = findViewById(R.id.et_revert);
        tvDefaultStatus = findViewById(R.id.tv_default_status);
        cbRevert.setChecked(prefs.revertOn());
        etRevert.setText(String.valueOf(prefs.revertMinutes()));
        tvDefaultStatus.setText(prefs.hasDefaults()
                ? R.string.default_status_set : R.string.default_status_none);
        findViewById(R.id.btn_save_default).setOnClickListener(v -> {
            // احفظ القيم المكتوبة أولاً ثم خذها كإعداد افتراضي
            applySettingsToPrefs();
            prefs.saveDefaultsFromCurrent();
            tvDefaultStatus.setText(R.string.default_status_set);
            Toast.makeText(this, R.string.default_saved, Toast.LENGTH_SHORT).show();
        });
        updateNightButtons();
        btnNightFrom.setOnClickListener(v -> pickNightTime(true));
        btnNightTo.setOnClickListener(v -> pickNightTime(false));

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_usage).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        findViewById(R.id.btn_overlay).setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        findViewById(R.id.btn_a11y).setOnClickListener(v -> {
            Toast.makeText(this, R.string.a11y_howto, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        findViewById(R.id.btn_notif).setOnClickListener(v ->
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        findViewById(R.id.btn_bank_mode).setOnClickListener(v -> toggleBankMode());
        findViewById(R.id.btn_admin).setOnClickListener(v -> requestAdmin());
        findViewById(R.id.btn_restricted).setOnClickListener(v -> {
            Toast.makeText(this, R.string.restricted_howto, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        });

        findViewById(R.id.btn_pick_video).setOnClickListener(v -> pickApps(PICK_VIDEO));
        findViewById(R.id.btn_pick_games).setOnClickListener(v -> pickApps(PICK_GAMES));
        findViewById(R.id.btn_pick_allowed).setOnClickListener(v -> pickApps(PICK_ALLOWED));

        findViewById(R.id.btn_change_pin).setOnClickListener(v -> promptCreatePin());
        btnStart.setOnClickListener(v -> startMonitoring());
        btnStop.setOnClickListener(v -> stopMonitoring());

        maybeRequestNotifications();
        if (prefs.enabled()) startMonitoring();   // تأكد أن الخدمة تعمل فعلاً (مثلاً بعد تحديث التطبيق)
        refreshPermissionStatus();
    }

    /** يكتب كل قيم الواجهة إلى الإعدادات (دون إغلاق). */
    private void applySettingsToPrefs() {
        prefs.setYtLimit(Math.max(1, parseInt(etYt.getText().toString(), 30)));
        prefs.setGameLimit(Math.max(1, parseInt(etGame.getText().toString(), 30)));
        prefs.setCooldownSec(Math.max(1, parseInt(etCooldown.getText().toString(), 10)) * 60);
        prefs.setYtOn(cbYt.isChecked());
        prefs.setGameOn(cbGame.isChecked());
        prefs.setSchedOn(cbSchedule.isChecked());
        prefs.setSchedFrom(schedFrom);
        prefs.setSchedTo(schedTo);
        prefs.setNightOn(cbNight.isChecked());
        prefs.setNightFrom(nightFrom);
        prefs.setNightTo(nightTo);
        prefs.setNightLimit(Math.max(1, parseInt(etNightLimit.getText().toString(), 1)));
        prefs.setBreakThreshold(Math.max(1, parseInt(etBreakThreshold.getText().toString(), 20)));
        prefs.setBreakMinutes(Math.max(1, parseInt(etBreakMinutes.getText().toString(), 60)));
        prefs.setResumeMinutes(Math.max(1, parseInt(etResume.getText().toString(), 30)));
        prefs.setRevertOn(cbRevert.isChecked());
        prefs.setRevertMinutes(Math.max(1, parseInt(etRevert.getText().toString(), 30)));
        if (prefs.revertOn()) prefs.setRevertAt(0L); // يُعاد ضبط المؤقّت في الخدمة
        prefs.setRelaxOn(cbRelax.isChecked());
        prefs.setRelaxLimit(Math.max(1, parseInt(etRelaxLimit.getText().toString(), 60)));
        if (relaxDays != null) prefs.setRelaxDays(relaxDays);
        prefs.setExcludeFb(cbExcludeFb.isChecked());
        prefs.setExcludeIg(cbExcludeIg.isChecked());
    }

    // اختيار الأيام المفتوحة (الأحد=1 .. السبت=7)
    private void pickRelaxDays() {
        final String[] names = {"الأحد","الاثنين","الثلاثاء","الأربعاء","الخميس","الجمعة","السبت"};
        final boolean[] checked = new boolean[7];
        for (int i = 0; i < 7; i++) checked[i] = relaxDays.contains(String.valueOf(i + 1));
        new AlertDialog.Builder(this)
                .setTitle(R.string.relax_days_title)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    java.util.Set<String> s = new java.util.HashSet<>();
                    for (int i = 0; i < 7; i++) if (checked[i]) s.add(String.valueOf(i + 1));
                    relaxDays = s;
                    if (!cbRelax.isChecked()) cbRelax.setChecked(true);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void save() {
        applySettingsToPrefs();
        if (prefs.enabled()) startMonitoring(); // تطبيق الإعدادات الجديدة فوراً
        // إغلاق التطبيق وإزالته من المهام حتى لا يجده الطفل مفتوحاً في الخلفية
        Toast.makeText(this, R.string.closing, Toast.LENGTH_SHORT).show();
        prefs.setParentUntil(0L);
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
            else finish();
        }, 900L);
    }

    // ===== جدول التعطيل =====
    private void updateSchedButtons() {
        btnSchedFrom.setText(getString(R.string.sched_from, hhmm(schedFrom)));
        btnSchedTo.setText(getString(R.string.sched_to, hhmm(schedTo)));
    }

    private String hhmm(int m) {
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private void pickTime(final boolean isFrom) {
        int cur = isFrom ? schedFrom : schedTo;
        new android.app.TimePickerDialog(this, (view, h, min) -> {
            int v = h * 60 + min;
            if (isFrom) schedFrom = v; else schedTo = v;
            if (!cbSchedule.isChecked()) cbSchedule.setChecked(true);
            updateSchedButtons();
        }, cur / 60, cur % 60, true).show();
    }

    private void updateNightButtons() {
        btnNightFrom.setText(getString(R.string.night_from, hhmm(nightFrom)));
        btnNightTo.setText(getString(R.string.night_to, hhmm(nightTo)));
    }

    private void pickNightTime(final boolean isFrom) {
        int cur = isFrom ? nightFrom : nightTo;
        new android.app.TimePickerDialog(this, (view, h, min) -> {
            int v = h * 60 + min;
            if (isFrom) nightFrom = v; else nightTo = v;
            if (!cbNight.isChecked()) cbNight.setChecked(true);
            updateNightButtons();
        }, cur / 60, cur % 60, true).show();
    }

    private void startMonitoring() {
        if (!hasUsageAccess()) {
            Toast.makeText(this, R.string.need_usage, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        prefs.setEnabled(true);
        prefs.setResumeAt(0);
        Intent svc = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
        Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show();
        refreshPermissionStatus();
    }

    private void stopMonitoring() {
        prefs.setEnabled(false);
        prefs.setLocked(false, 0);
        // العودة التلقائية بعد المدة المحددة (الإيقاف المؤقت)
        long resumeAt = System.currentTimeMillis() + prefs.resumeMinutes() * 60_000L;
        prefs.setResumeAt(resumeAt);
        Intent svc = new Intent(this, MonitorService.class);
        svc.setAction(MonitorService.ACTION_STOP);
        startService(svc);
        Toast.makeText(this, getString(R.string.stopped_resume, prefs.resumeMinutes()),
                Toast.LENGTH_LONG).show();
        refreshPermissionStatus();
    }

    // ===== منع الحذف =====
    private ComponentName adminComponent() {
        return new ComponentName(this, KidsAdminReceiver.class);
    }

    private boolean isAdminActive() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(adminComponent());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestAdmin() {
        if (isAdminActive()) {
            Toast.makeText(this, R.string.admin_already, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent());
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explain));
        try { startActivity(i); } catch (Throwable t) {
            Toast.makeText(this, R.string.admin_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ===== اختيار التطبيقات يدوياً =====
    private static class AppItem {
        final String pkg; final String label;
        AppItem(String p, String l) { pkg = p; label = l; }
    }

    private void pickApps(final int mode) {
        Toast.makeText(this, R.string.loading_apps, Toast.LENGTH_SHORT).show();
        final AppClassifier cls = new AppClassifier(this);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(li, 0);
            Set<String> seen = new HashSet<>();
            final List<AppItem> items = new ArrayList<>();
            for (ResolveInfo r : ris) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                CharSequence l = r.loadLabel(pm);
                items.add(new AppItem(p, l == null ? p : l.toString()));
            }
            Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));

            final boolean[] checked = new boolean[items.size()];
            final boolean[] auto = new boolean[items.size()];
            Set<String> allowed = prefs.getSet(Prefs.K_ALLOWED);
            for (int i = 0; i < items.size(); i++) {
                String p = items.get(i).pkg;
                if (mode == PICK_VIDEO) { auto[i] = cls.autoVideo(p); checked[i] = cls.isVideoApp(p); }
                else if (mode == PICK_GAMES) { auto[i] = cls.autoGame(p); checked[i] = cls.isGame(p); }
                else { checked[i] = allowed.contains(p); }
            }
            runOnUiThread(() -> showPicker(mode, items, checked, auto));
        }).start();
    }

    private void showPicker(final int mode, final List<AppItem> items,
                            final boolean[] checked, final boolean[] auto) {
        if (isFinishing()) return;
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label;

        int title = mode == PICK_VIDEO ? R.string.pick_video_title
                : mode == PICK_GAMES ? R.string.pick_games_title : R.string.pick_allowed_title;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.save, (d, w) -> {
                    if (mode == PICK_ALLOWED) {
                        Set<String> s = new HashSet<>();
                        for (int i = 0; i < items.size(); i++) if (checked[i]) s.add(items.get(i).pkg);
                        prefs.putSet(Prefs.K_ALLOWED, s);
                    } else {
                        Set<String> inc = new HashSet<>(), exc = new HashSet<>();
                        for (int i = 0; i < items.size(); i++) {
                            if (checked[i] && !auto[i]) inc.add(items.get(i).pkg);
                            if (!checked[i] && auto[i]) exc.add(items.get(i).pkg);
                        }
                        if (mode == PICK_VIDEO) {
                            prefs.putSet(Prefs.K_INC_VIDEO, inc);
                            prefs.putSet(Prefs.K_EXC_VIDEO, exc);
                        } else {
                            prefs.putSet(Prefs.K_INC_GAME, inc);
                            prefs.putSet(Prefs.K_EXC_GAME, exc);
                        }
                    }
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ===== حالة الصلاحيات =====
    private void refreshPermissionStatus() {
        refreshHome();
        if (tvUsage == null) return;   // قسم الإعدادات غير مفتوح؛ عناصره غير مهيّأة
        tvUsage.setText(getString(R.string.usage_status, yesNo(hasUsageAccess())));
        tvOverlay.setText(getString(R.string.overlay_status, yesNo(Settings.canDrawOverlays(this))));
        tvA11y.setText(getString(R.string.a11y_status, yesNo(isA11yEnabled())));
        tvNotif.setText(getString(R.string.notif_status, yesNo(isNotifListenerEnabled())));
        tvAdmin.setText(getString(R.string.admin_status, yesNo(isAdminActive())));
        android.widget.TextView tvBank = findViewById(R.id.tv_bank_status);
        android.widget.Button btnBank = findViewById(R.id.btn_bank_mode);
        boolean a11yRunning = KidsAccessibilityService.isRunning();
        if (tvBank != null) tvBank.setText(a11yRunning ? R.string.bank_status_on : R.string.bank_status_off);
        if (btnBank != null) btnBank.setText(a11yRunning ? R.string.bank_pause : R.string.bank_resume);
        tvStatus.setText(getString(R.string.service_status,
                getString(prefs.enabled() ? R.string.running : R.string.off)));
        btnStart.setEnabled(true);
        btnStop.setEnabled(prefs.enabled());
    }

    private String yesNo(boolean b) {
        return getString(b ? R.string.granted : R.string.not_granted);
    }

    /**
     * وضع البنوك: يوقف خدمة الحماية مؤقتاً (تطبيقات البنوك ترفض العمل معها)،
     * أو — إن كانت متوقفة — يفتح إعدادات إمكانية الوصول لإعادة تفعيلها بضغطة.
     * حدود الوقت والقفل تبقى شغّالة في الحالتين.
     */
    private void toggleBankMode() {
        if (KidsAccessibilityService.isRunning()) {
            boolean ok = KidsAccessibilityService.pauseForBanking();
            Toast.makeText(this, ok ? R.string.bank_paused_toast : R.string.a11y_howto,
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.bank_resume_toast, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
        // disableSelf غير فوري؛ حدّث الحالة بعد لحظة
        getWindow().getDecorView().postDelayed(this::refreshPermissionStatus, 900L);
    }

    private boolean isA11yEnabled() {
        if (KidsAccessibilityService.isRunning()) return true;
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(s)) return false;
            String me = new ComponentName(this, KidsAccessibilityService.class).flattenToString();
            String meShort = new ComponentName(this, KidsAccessibilityService.class).flattenToShortString();
            String low = s.toLowerCase(Locale.US);
            return low.contains(me.toLowerCase(Locale.US)) || low.contains(meShort.toLowerCase(Locale.US));
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isNotifListenerEnabled() {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
            }
        }
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
