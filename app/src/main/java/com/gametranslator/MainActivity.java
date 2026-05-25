package com.gametranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * MainActivity — الوظيفة الوحيدة هي:
 * 1. طلب إذن POST_NOTIFICATIONS (Android 13+)
 * 2. طلب إذن SYSTEM_ALERT_WINDOW (Overlay)
 * 3. طلب إذن MediaProjection (تصوير الشاشة)
 * 4. تمرير نتيجة MediaProjection للـ Service
 * 5. الاختفاء فوراً — الخدمة تعمل بدونها
 *
 * ❌ لا WebView
 * ❌ لا Clipboard
 * ❌ لا UI معقد
 *
 * التحسينات المطبّقة:
 * ✅ استبدال startActivityForResult → registerForActivityResult (modern API)
 * ✅ حماية mpManager == null
 * ✅ إزالة Emoji من Toast لتوافق الأجهزة القديمة
 * ✅ تعليقات واضحة على كل قرار
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GT_Main";
    private static final int REQ_NOTIF = 1001;

    private MediaProjectionManager mpManager;

    // ─── Modern Activity Result API (بديل startActivityForResult المهجور) ────

    /**
     * Launcher لإذن الـ Overlay (ACTION_MANAGE_OVERLAY_PERMISSION).
     * لا يعيد resultCode مباشرة، لذلك نتحقق يدوياً بعد العودة.
     */
    private final ActivityResultLauncher<Intent> overlayLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // نتحقق من الإذن مباشرة — resultCode هنا دائماً CANCELED
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                && Settings.canDrawOverlays(this)) {
                            requestScreenCapture();
                        } else {
                            Toast.makeText(this,
                                    "بدون اذن الـ Overlay لن يظهر الزر العائم",
                                    Toast.LENGTH_LONG).show();
                            launchService(Activity.RESULT_CANCELED, null);
                        }
                    });

    /**
     * Launcher لإذن تصوير الشاشة (MediaProjection).
     * هنا resultCode يكون RESULT_OK أو RESULT_CANCELED بوضوح.
     */
    private final ActivityResultLauncher<Intent> screenCapLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        int resultCode = result.getResultCode();
                        Intent data    = result.getData();

                        if (resultCode == Activity.RESULT_OK && data != null) {
                            Log.d(TAG, "Screen capture permission GRANTED");
                            launchService(resultCode, data);
                        } else {
                            Log.w(TAG, "Screen capture permission DENIED");
                            // لا Emoji — توافق أكبر مع الأجهزة القديمة
                            Toast.makeText(this,
                                    "رُفض اذن تصوير الشاشة — OCR لن يعمل.\nاعد فتح التطبيق وامنح الاذن.",
                                    Toast.LENGTH_LONG).show();
                            launchService(Activity.RESULT_CANCELED, null);
                        }
                    });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ✅ حماية: بعض الأجهزة ترجع null لـ MediaProjectionManager
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpManager == null) {
            Log.e(TAG, "MediaProjectionManager is null — device may not support screen capture");
            Toast.makeText(this,
                    "جهازك لا يدعم تصوير الشاشة — OCR لن يعمل",
                    Toast.LENGTH_LONG).show();
            // شغّل الخدمة بدون OCR بدل ما نوقف التطبيق كلياً
            launchService(Activity.RESULT_CANCELED, null);
            return;
        }

        // الخطوة 1: إذن الإشعارات (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
                return;
            }
        }
        checkOverlay();
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        // سواء مُنح أو رُفض — نكمل
        if (req == REQ_NOTIF) {
            checkOverlay();
        }
    }

    /** الخطوة 2: إذن الـ Overlay */
    private void checkOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "اسمح بـ الظهور فوق التطبيقات حتى يعمل الزر العائم",
                    Toast.LENGTH_LONG).show();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayLauncher.launch(intent);
        } else {
            requestScreenCapture();
        }
    }

    /** الخطوة 3: إذن تصوير الشاشة */
    private void requestScreenCapture() {
        Log.d(TAG, "Requesting screen capture permission...");
        // mpManager مضمون غير null هنا (تحققنا في onCreate)
        screenCapLauncher.launch(mpManager.createScreenCaptureIntent());
    }

    /**
     * تشغيل FloatingTranslatorService مع تمرير mp_result_code + mp_data.
     *
     * ملاحظة حول finish():
     * لم نضف finish() هنا عمداً — بعض الأجهزة (Xiaomi / Oppo / Realme / Huawei)
     * تقتل الـ Foreground Service عند إغلاق الـ Activity الأم مباشرة.
     * moveTaskToBack(true) يخفي التطبيق بأمان مع إبقاء الخدمة حية.
     * إذا أردت اختبار finish() لاحقاً، أضفه بعد moveTaskToBack وراقب السلوك
     * على أجهزة Xiaomi تحديداً.
     */
    private void launchService(int resultCode, Intent data) {
        Intent svc = new Intent(this, FloatingTranslatorService.class);
        if (resultCode == Activity.RESULT_OK && data != null) {
            svc.putExtra("mp_result_code", resultCode);
            svc.putExtra("mp_data", data);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        // اخفِ التطبيق فوراً — الزر العائم يتولى الباقي
        moveTaskToBack(true);
    }

    @Override
    public void onBackPressed() {
        // زر الرجوع يخفي بدل ما يغلق — نفس سبب عدم استخدام finish()
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ✅ لا stopService هنا أبداً — الخدمة تبقى حية
    }
}
