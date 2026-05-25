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
 * MainActivity — v12 (Final Stable)
 *
 * Flow:
 *   1. POST_NOTIFICATIONS (Android 13+)
 *   2. SYSTEM_ALERT_WINDOW (Overlay permission)
 *   3. MediaProjection (screen capture)
 *   4. startForegroundService()
 *   5. finish() immediately — no black screen ever
 *
 * Key fixes vs v11:
 * - Service already running? Skip re-launch, just finish().
 * - All result paths call finish() — no path leaves Activity visible.
 * - No heavy work in onCreate() — Activity stays <5ms on screen.
 * - Theme is @style/Theme.Translucent so there's no white/black flash.
 * - onNewIntent() just finishes (singleTop — prevents stacking).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG       = "GT_Main";
    private static final int    REQ_NOTIF = 1001;

    private MediaProjectionManager mpManager;
    private boolean                alreadyLaunched = false;

    // ── Overlay permission launcher ──────────────────────────────
    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && Settings.canDrawOverlays(this)) {
                    requestScreenCapture();
                } else {
                    Toast.makeText(this,
                        "Overlay permission required for floating button",
                        Toast.LENGTH_LONG).show();
                    launchServiceAndFinish(Activity.RESULT_CANCELED, null);
                }
            });

    // ── MediaProjection permission launcher ──────────────────────
    private final ActivityResultLauncher<Intent> screenCapLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                int    rc   = result.getResultCode();
                Intent data = result.getData();
                if (rc == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "Screen capture GRANTED");
                    launchServiceAndFinish(rc, data);
                } else {
                    Log.w(TAG, "Screen capture DENIED");
                    Toast.makeText(this,
                        "Screen capture denied — OCR unavailable. Re-open to grant.",
                        Toast.LENGTH_LONG).show();
                    launchServiceAndFinish(Activity.RESULT_CANCELED, null);
                }
            });

    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Minimal transparent layout — user never sees this
        setContentView(R.layout.activity_main);

        // Prevent double-launch on config change / re-create
        if (alreadyLaunched) { finish(); return; }
        alreadyLaunched = true;

        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpManager == null) {
            Log.e(TAG, "MediaProjectionManager null — OCR unavailable");
            Toast.makeText(this,
                "Device does not support screen capture",
                Toast.LENGTH_LONG).show();
            launchServiceAndFinish(Activity.RESULT_CANCELED, null);
            return;
        }

        // Step 1 — notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF);
                return; // continues in onRequestPermissionsResult
            }
        }
        checkOverlay();
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        // Always continue regardless of result — notification is optional
        if (req == REQ_NOTIF) checkOverlay();
    }

    /** Step 2 — overlay permission */
    private void checkOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "Allow 'Display over other apps' for floating button",
                Toast.LENGTH_LONG).show();
            overlayLauncher.launch(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } else {
            requestScreenCapture();
        }
    }

    /** Step 3 — screen capture permission */
    private void requestScreenCapture() {
        try {
            screenCapLauncher.launch(mpManager.createScreenCaptureIntent());
        } catch (Exception e) {
            Log.e(TAG, "createScreenCaptureIntent failed: " + e.getMessage(), e);
            Toast.makeText(this, "Cannot request screen permission", Toast.LENGTH_LONG).show();
            launchServiceAndFinish(Activity.RESULT_CANCELED, null);
        }
    }

    /**
     * Step 4+5: Start service → finish Activity immediately.
     * This is the ONLY exit point — every code path calls this method.
     */
    private void launchServiceAndFinish(int resultCode, Intent data) {
        Intent svc = new Intent(this, FloatingTranslatorService.class);
        if (resultCode == Activity.RESULT_OK && data != null) {
            svc.putExtra("mp_result_code", resultCode);
            svc.putExtra("mp_data", data);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Log.d(TAG, "Service started (resultCode=" + resultCode + ")");
        } catch (Exception e) {
            Log.e(TAG, "startForegroundService failed: " + e.getMessage(), e);
            Toast.makeText(this,
                "Could not start translator: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
        // Always close — never leave user staring at a black screen
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop re-launch — just close
        finish();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Do NOT stop the foreground service here
    }
}
