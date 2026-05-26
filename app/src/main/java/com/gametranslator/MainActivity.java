package com.gametranslator;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * MainActivity — v14
 * - يطلب الصلاحيات مرة واحدة فقط في العمر
 * - إذا الخدمة شغالة → يغلق مباشرة بدون أي طلب
 * - إذا الخدمة ماتت → زر لإعادة التشغيل فقط بدون طلب صلاحيات جديدة
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG        = "GT_Main";
    private static final String PREFS      = "ut_prefs";
    private static final String KEY_GRANTED = "screen_granted";
    private static final int    REQ_NOTIF  = 1001;

    private MediaProjectionManager mpManager;
    private Button                 btnStart;
    private TextView               tvStatus;
    private SharedPreferences      prefs;

    // ── Overlay permission ───────────────────────────────────────
    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && Settings.canDrawOverlays(this)) {
                    requestScreenCapture();
                } else {
                    setStatus("❌ صلاحية الـ Overlay مرفوضة");
                    if (btnStart != null) btnStart.setEnabled(true);
                }
            });

    // ── MediaProjection permission ───────────────────────────────
    private final ActivityResultLauncher<Intent> screenCapLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                int    rc   = result.getResultCode();
                Intent data = result.getData();
                if (rc == Activity.RESULT_OK && data != null) {
                    // احفظ أن المستخدم وافق — ما نسأله مجدداً
                    prefs.edit().putBoolean(KEY_GRANTED, true).apply();
                    launchService(rc, data);
                } else {
                    setStatus("❌ صلاحية الشاشة مرفوضة — اضغط مجدداً");
                    if (btnStart != null) btnStart.setEnabled(true);
                }
            });

    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // ── إذا الخدمة شغالة → أغلق فوراً بدون أي شيء ──────────
        if (isServiceRunning()) {
            finish();
            return;
        }

        // ── بناء الواجهة ─────────────────────────────────────────
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF020710);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("مترجم الألعاب");
        tvTitle.setTextColor(0xFF3D8BFF);
        tvTitle.setTextSize(24f);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, dp(8));
        card.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setTextColor(0xFF8899BB);
        tvDesc.setTextSize(13f);
        tvDesc.setGravity(Gravity.CENTER);
        tvDesc.setPadding(0, 0, 0, dp(28));
        card.addView(tvDesc);

        btnStart = new Button(this);
        btnStart.setTextColor(0xFFFFFFFF);
        btnStart.setBackgroundColor(0xFF3D8BFF);
        btnStart.setTextSize(16f);
        btnStart.setPadding(dp(32), dp(14), dp(32), dp(14));
        btnStart.setOnClickListener(v -> onStartPressed());
        card.addView(btnStart);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(0xFF00E676);
        tvStatus.setTextSize(12f);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(16), 0, 0);
        card.addView(tvStatus);

        root.addView(card, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // ── تحديد نص الزر حسب الحالة ─────────────────────────────
        if (prefs.getBoolean(KEY_GRANTED, false)) {
            // سبق ووافق — الزر لإعادة التشغيل فقط بدون طلب صلاحيات
            tvDesc.setText("المترجم متوقف\nاضغط لإعادة التشغيل");
            btnStart.setText("▶  إعادة التشغيل");
        } else {
            // أول مرة
            tvDesc.setText("اضغط لتشغيل المترجم العائم\nستُطلب الصلاحية مرة واحدة فقط");
            btnStart.setText("▶  تشغيل المترجم");
        }
    }

    private void onStartPressed() {
        if (btnStart != null) btnStart.setEnabled(false);

        // إذا سبق وأعطى الصلاحية → أعد التشغيل مباشرة بدون طلب
        if (prefs.getBoolean(KEY_GRANTED, false)) {
            setStatus("جاري إعادة التشغيل…");
            // نفتح MainActivity من الخدمة لطلب MediaProjection جديد
            checkOverlayThenCapture();
            return;
        }

        // أول مرة — تحقق من الصلاحيات
        setStatus("جاري التحقق من الصلاحيات…");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
        }
        checkOverlayThenCapture();
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == REQ_NOTIF) checkOverlayThenCapture();
    }

    private void checkOverlayThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            setStatus("اسمح بـ 'العرض فوق التطبيقات' ثم ارجع");
            overlayLauncher.launch(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } else {
            requestScreenCapture();
        }
    }

    private void requestScreenCapture() {
        setStatus("اسمح بتصوير الشاشة…");
        try {
            screenCapLauncher.launch(mpManager.createScreenCaptureIntent());
        } catch (Exception e) {
            Log.e(TAG, "createScreenCaptureIntent: " + e.getMessage(), e);
            setStatus("❌ خطأ: " + e.getMessage());
            if (btnStart != null) btnStart.setEnabled(true);
        }
    }

    private void launchService(int resultCode, Intent data) {
        Intent svc = new Intent(this, FloatingTranslatorService.class);
        if (resultCode == Activity.RESULT_OK && data != null) {
            svc.putExtra("mp_result_code", resultCode);
            svc.putExtra("mp_data", data);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else
                startService(svc);

            setStatus("✅ تم التشغيل!");
            // أغلق بعد ثانية
            new Handler().postDelayed(this::finish, 1000);
        } catch (Exception e) {
            Log.e(TAG, "start service: " + e.getMessage(), e);
            setStatus("❌ فشل: " + e.getMessage());
            if (btnStart != null) btnStart.setEnabled(true);
        }
    }

    private boolean isServiceRunning() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
            if (FloatingTranslatorService.class.getName().equals(s.service.getClassName()))
                return true;
        }
        return false;
    }

    private void setStatus(String msg) {
        if (tvStatus != null) tvStatus.setText(msg);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() { finish(); }
    @Override protected void onDestroy() { super.onDestroy(); }
}
