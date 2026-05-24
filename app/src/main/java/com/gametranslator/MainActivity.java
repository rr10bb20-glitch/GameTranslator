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
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GT_Main";
    private static final int REQ_NOTIF       = 1001;
    private static final int REQ_OVERLAY     = 1002;
    private static final int REQ_SCREEN_CAP  = 1003;

    private MediaProjectionManager mpManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // لا setContentView — نعرض فقط شاشة بيضاء للحظة ثم نختفي
        setContentView(R.layout.activity_main);

        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

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
        // سواء منح أو رفض — نكمل
        if (req == REQ_NOTIF) {
            checkOverlay();
        }
    }

    /** الخطوة 2: إذن الـ Overlay */
    private void checkOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "اسمح بـ \"الظهور فوق التطبيقات\" حتى يعمل الزر العائم",
                    Toast.LENGTH_LONG).show();
            startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                               Uri.parse("package:" + getPackageName())),
                    REQ_OVERLAY);
        } else {
            requestScreenCapture();
        }
    }

    /** الخطوة 3: إذن تصوير الشاشة */
    private void requestScreenCapture() {
        Log.d(TAG, "Requesting screen capture permission...");
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_SCREEN_CAP);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int resultCode, Intent data) {
        super.onActivityResult(req, resultCode, data);

        if (req == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                requestScreenCapture();
            } else {
                Toast.makeText(this, "⚠ بدون إذن الـ Overlay لن يظهر الزر العائم", Toast.LENGTH_LONG).show();
                // شغّل الخدمة بدون MediaProjection — لن يعمل OCR
                launchService(Activity.RESULT_CANCELED, null);
            }
            return;
        }

        if (req == REQ_SCREEN_CAP) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "Screen capture permission GRANTED ✓");
                launchService(resultCode, data);
            } else {
                Log.w(TAG, "Screen capture permission DENIED");
                Toast.makeText(this,
                        "⚠ رُفض إذن تصوير الشاشة — OCR لن يعمل.\nأعد فتح التطبيق وامنح الإذن.",
                        Toast.LENGTH_LONG).show();
                // شغّل الخدمة بدون OCR (الزر العائم يبقى)
                launchService(Activity.RESULT_CANCELED, null);
            }
        }
    }

    /**
     * تشغيل FloatingTranslatorService مع تمرير mp_result_code + mp_data.
     * بعدها نخفي الـ Activity — لا نغلقها بـ finish() لأن
     * بعض الأجهزة تقتل الـ Service لو أُغلقت الـ Activity الأم مباشرة.
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
        // زر الرجوع يخفي بدل ما يغلق
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ✅ لا stopService هنا أبداً — الخدمة تبقى حية
    }
}
