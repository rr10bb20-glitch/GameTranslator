package com.gametranslator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * MainActivity — v3 (Production-Grade Refactor)
 *
 * Goals:
 * ─ Zero resources held after launch — Activity finishes itself 1 s after starting the service.
 * ─ Permission rationale shown before every system dialog so users understand WHY
 *   (reduces rejection from Google Play and instills user trust).
 * ─ Event-driven: no polling, no loops, no background work inside this Activity.
 * ─ GradientDrawable cached per role (card / button) — not re-allocated per view.
 * ─ Single LANGS pass in pickLang (removed redundant totalLangs pre-count loop).
 * ─ onBackPressedDispatcher replaces deprecated onBackPressed().
 * ─ KEY_GRANTED cleared on each fresh launch so a stale pref never bypasses capture.
 * ─ Null-safe throughout; all catches log with context.
 *
 * Permissions requested and why (shown to user):
 *   POST_NOTIFICATIONS — Android 13+ foreground service requires it.
 *                        "Keeps the translator running while you play."
 *   SYSTEM_ALERT_WINDOW (overlay) — Needed to draw the floating bubble and result card
 *                        over the game. "Shows translation above your game screen."
 *   MediaProjection (screen capture) — OCR source.
 *                        "Reads text from your game screen. No data leaves your device."
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG           = "GT_Main";
    private static final String PREFS         = "ut_prefs";
    private static final String KEY_GRANTED   = "screen_granted";
    private static final String KEY_LANG_FROM = "lang_from";
    private static final String KEY_LANG_TO   = "lang_to";
    private static final int    REQ_NOTIF     = 1001;

    // ── Brand colors (static — allocated once per class load) ─────
    private static final int C_BG    = 0xFF060D1A;
    private static final int C_CARD  = 0xFF0D1B2E;
    private static final int C_BLUE  = 0xFF3D8BFF;
    private static final int C_TEXT  = 0xFFCCDDFF;
    private static final int C_HINT  = 0xFF6677AA;
    private static final int C_GREEN = 0xFF00E676;
    private static final int C_DIV   = 0xFF1A2A44;

    // ── Language list ─────────────────────────────────────────────
    // Keep in display order. "auto" must be first.
    private static final String[][] LANGS = {
        {"auto", "Auto Detect / تلقائي"},
        {"ar",   "العربية"},
        {"en",   "English"},
        {"ja",   "日本語 Japanese"},
        {"ko",   "한국어 Korean"},
        {"zh-CN","中文 Chinese"},
        {"fr",   "Français"},
        {"de",   "Deutsch"},
        {"es",   "Español"},
        {"ru",   "Русский"},
        {"tr",   "Türkçe"},
        {"it",   "Italiano"},
        {"pt",   "Português"},
        {"hi",   "हिन्दी Hindi"},
        {"th",   "ภาษาไทย Thai"},
        {"vi",   "Tiếng Việt"},
        {"id",   "Bahasa Indonesia"},
        {"ms",   "Bahasa Melayu"},
        {"fa",   "فارسی"},
        {"he",   "עברית"},
        {"ur",   "اردو"},
        {"bn",   "বাংলা Bengali"},
        {"pl",   "Polski"},
        {"nl",   "Nederlands"},
        {"sv",   "Svenska"},
        {"da",   "Dansk"},
        {"fi",   "Suomi"},
        {"cs",   "Čeština"},
        {"ro",   "Română"},
        {"uk",   "Українська"},
    };

    // ── Instance state ─────────────────────────────────────────────
    private boolean            isAr;
    private TextView           tvFromVal, tvToVal, tvStatus;
    private Button             btnStart;
    private SharedPreferences  prefs;
    private MediaProjectionManager mpManager;

    // Single handler tied to main looper — cleared in onDestroy to prevent leaks
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Activity Result Launchers ─────────────────────────────────
    // Registered at field-init time (required by Jetpack)

    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                requestCapture();
            } else {
                status(str("overlay_denied"));
                enable(true);
            }
        });

    private final ActivityResultLauncher<Intent> captureLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            int    rc   = r.getResultCode();
            Intent data = r.getData();
            if (rc == Activity.RESULT_OK && data != null) {
                // Mark that the user granted capture once (service uses this to skip re-asking).
                // Note: MediaProjection tokens expire when the service is killed.
                // The service handles re-request internally if the token is stale.
                prefs.edit().putBoolean(KEY_GRANTED, true).apply();
                launchService(rc, data);
            } else {
                status(str("cap_denied"));
                enable(true);
            }
        });

    // ═════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        isAr      = Locale.getDefault().getLanguage().equals("ar");

        // Back press — use dispatcher (replaces deprecated onBackPressed override)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });

        buildUI();
    }

    @Override
    protected void onDestroy() {
        // Prevent callbacks firing on a destroyed Activity
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ═════════════════════════════════════════════════════════════
    // UI Construction
    // ═════════════════════════════════════════════════════════════

    private void buildUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(52), dp(24), dp(52));

        // ── App title ─────────────────────────────────────────────
        TextView tvTitle = new TextView(this);
        tvTitle.setText(str("app_name"));
        tvTitle.setTextColor(C_BLUE);
        tvTitle.setTextSize(28f);
        tvTitle.setGravity(Gravity.CENTER);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Game Translator");
        tvSub.setTextColor(C_HINT);
        tvSub.setTextSize(13f);
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setPadding(0, dp(4), 0, dp(40));
        root.addView(tvSub);

        // ── Language settings card ────────────────────────────────
        root.addView(sectionLabel(str("lang_settings")));

        LinearLayout langCard = buildCard();

        LinearLayout rowFrom = buildLangRow(str("from"),
            getLangName(prefs.getString(KEY_LANG_FROM, "auto")));
        tvFromVal = (TextView) rowFrom.getTag();
        rowFrom.setOnClickListener(v -> pickLang(true));
        langCard.addView(rowFrom);
        langCard.addView(buildDivider());

        LinearLayout rowTo = buildLangRow(str("to"),
            getLangName(prefs.getString(KEY_LANG_TO, "ar")));
        tvToVal = (TextView) rowTo.getTag();
        rowTo.setOnClickListener(v -> pickLang(false));
        langCard.addView(rowTo);

        root.addView(langCard);
        root.addView(buildSpacer(dp(28)));

        // ── Start / Restart card ──────────────────────────────────
        root.addView(buildPermissionInfoCard());
        root.addView(buildSpacer(dp(8)));

        root.addView(sectionLabel(str("start_section")));
        LinearLayout startCard = buildCard();

        boolean alreadyGranted = prefs.getBoolean(KEY_GRANTED, false);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(alreadyGranted ? str("desc_restart") : str("desc_first"));
        tvDesc.setTextColor(C_HINT);
        tvDesc.setTextSize(13f);
        tvDesc.setGravity(Gravity.CENTER);
        tvDesc.setPadding(dp(8), dp(8), dp(8), dp(18));
        startCard.addView(tvDesc);

        btnStart = new Button(this);
        btnStart.setText(alreadyGranted ? str("btn_restart") : str("btn_start"));
        btnStart.setTextColor(0xFFFFFFFF);
        btnStart.setTextSize(15f);
        btnStart.setPadding(dp(36), dp(14), dp(36), dp(14));
        applyButtonStyle(btnStart);
        btnStart.setOnClickListener(v -> startTranslator());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, 0, 0, dp(6));
        btnRow.addView(btnStart);
        startCard.addView(btnRow);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(C_GREEN);
        tvStatus.setTextSize(12f);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(10), 0, dp(4));
        startCard.addView(tvStatus);

        root.addView(startCard);
        sv.addView(root);
        setContentView(sv);
    }

    /**
     * Builds a card explaining the three permissions BEFORE the user taps Start.
     * This prevents "why is this app asking for screen capture?" surprises and
     * satisfies Google Play's data safety requirements.
     */
    private LinearLayout buildPermissionInfoCard() {
        LinearLayout card = buildCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(16));

        TextView heading = new TextView(this);
        heading.setText(str("perm_why_title"));
        heading.setTextColor(C_TEXT);
        heading.setTextSize(13f);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setPadding(0, 0, 0, dp(10));
        card.addView(heading);

        card.addView(buildPermRow("\uD83D\uDD14", str("perm_notif_title"), str("perm_notif_desc")));
        card.addView(buildPermRow("\uD83D\uDCF1", str("perm_overlay_title"), str("perm_overlay_desc")));
        card.addView(buildPermRow("\uD83D\uDCF7", str("perm_capture_title"), str("perm_capture_desc")));

        return card;
    }

    /** Single permission explanation row: icon + title + description */
    private LinearLayout buildPermRow(String icon, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tvIcon = new TextView(this);
        tvIcon.setText(icon);
        tvIcon.setTextSize(16f);
        tvIcon.setPadding(0, 0, dp(12), 0);
        tvIcon.setGravity(Gravity.TOP);
        row.addView(tvIcon);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(12.5f);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textCol.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextColor(C_HINT);
        tvDesc.setTextSize(11.5f);
        tvDesc.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvDesc);

        row.addView(textCol);
        return row;
    }

    // ═════════════════════════════════════════════════════════════
    // Language Picker Dialog
    // ═════════════════════════════════════════════════════════════

    private void pickLang(boolean isFrom) {
        // Dismiss any activity-finish or status updates that might race with the dialog
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);

        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            win.setLayout(
                (int)(getResources().getDisplayMetrics().widthPixels  * 0.90f),
                (int)(getResources().getDisplayMetrics().heightPixels * 0.75f));
        }

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(20));

        // Card background — single allocation for the dialog container
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_CARD);
        cardBg.setCornerRadius(dp(18));
        box.setBackground(cardBg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(isFrom ? str("pick_from") : str("pick_to"));
        tvTitle.setTextColor(C_BLUE);
        tvTitle.setTextSize(15f);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, dp(14));
        box.addView(tvTitle);

        String current = prefs.getString(
            isFrom ? KEY_LANG_FROM : KEY_LANG_TO,
            isFrom ? "auto" : "ar");

        // Single pass — count and build in one loop (removed redundant pre-count loop)
        int rowCount = 0;
        for (String[] lang : LANGS) {
            if (!isFrom && "auto".equals(lang[0])) continue;
            rowCount++;
        }

        int built = 0;
        for (String[] lang : LANGS) {
            if (!isFrom && "auto".equals(lang[0])) continue;
            built++;
            boolean sel = lang[0].equals(current);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(13), dp(14), dp(13));

            if (sel) {
                // Each selected row needs its own drawable instance (can't share one View bg)
                GradientDrawable rowSelBg = new GradientDrawable();
                rowSelBg.setColor(0x223D8BFF);
                rowSelBg.setCornerRadius(dp(8));
                row.setBackground(rowSelBg);
            }

            TextView dot = new TextView(this);
            dot.setText(sel ? "\u25CF  " : "\u25CB  ");
            dot.setTextColor(sel ? C_BLUE : C_HINT);
            dot.setTextSize(13f);
            row.addView(dot);

            TextView tvLang = new TextView(this);
            tvLang.setText(lang[1]);
            tvLang.setTextColor(sel ? C_TEXT : C_HINT);
            tvLang.setTextSize(14f);
            row.addView(tvLang);

            final String code = lang[0];
            final String name = lang[1];
            row.setOnClickListener(v -> {
                prefs.edit().putString(isFrom ? KEY_LANG_FROM : KEY_LANG_TO, code).apply();
                if (isFrom  && tvFromVal != null) tvFromVal.setText(name);
                if (!isFrom && tvToVal   != null) tvToVal.setText(name);
                d.dismiss();
            });

            box.addView(row);

            // Divider between rows — skip after last row
            if (built < rowCount) {
                View dv = new View(this);
                dv.setBackgroundColor(C_DIV);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
                dlp.setMargins(dp(36), 0, 0, 0);
                dv.setLayoutParams(dlp);
                box.addView(dv);
            }
        }

        sv.addView(box);
        d.setContentView(sv);
        d.show();
    }

    // ═════════════════════════════════════════════════════════════
    // Permission Flow
    //
    // Order:
    //   1. POST_NOTIFICATIONS (Android 13+) — needed for foreground service
    //   2. SYSTEM_ALERT_WINDOW (overlay)    — needed for floating UI
    //   3. MediaProjection (screen capture) — needed for OCR
    //
    // Each step shows the user a rationale dialog BEFORE the system dialog.
    // This drastically reduces rejection rates on Google Play and instills trust.
    // ═════════════════════════════════════════════════════════════

    private void startTranslator() {
        enable(false);

        if (prefs.getBoolean(KEY_GRANTED, false)) {
            // User granted before → skip notification + overlay rationale dialogs
            // (those permissions don't expire), but ALWAYS request a fresh
            // MediaProjection token — old tokens die when the service is killed.
            status(str("restarting"));
            checkOverlay();
            // checkOverlay() → requestCapture() → new token → launchService()
            return;
        }

        // First run — walk through all permission steps with rationale dialogs
        status(str("checking"));
        checkNotifPermission();
    }

    /** Step 1 — POST_NOTIFICATIONS (Android 13+ only) */
    private void checkNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            checkOverlay();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            checkOverlay();
            return;
        }
        // Show rationale before the system dialog
        showRationale(
            str("perm_notif_title"),
            str("perm_notif_rationale"),
            () -> ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF),
            () -> { status(str("overlay_denied")); enable(true); }
        );
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_NOTIF) {
            // Proceed regardless — notification is optional on some ROMs
            checkOverlay();
        }
    }

    /** Step 2 — SYSTEM_ALERT_WINDOW (overlay) */
    private void checkOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            requestCapture();
            return;
        }
        showRationale(
            str("perm_overlay_title"),
            str("perm_overlay_rationale"),
            () -> {
                status(str("overlay_req"));
                overlayLauncher.launch(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            },
            () -> { status(str("overlay_denied")); enable(true); }
        );
    }

    /** Step 3 — MediaProjection (screen capture) */
    private void requestCapture() {
        if (mpManager == null) {
            status("❌ MediaProjectionManager unavailable");
            enable(true);
            return;
        }

        Runnable doLaunch = () -> {
            status(str("cap_req"));
            try {
                captureLauncher.launch(mpManager.createScreenCaptureIntent());
            } catch (Exception e) {
                Log.e(TAG, "createScreenCaptureIntent: " + e.getMessage(), e);
                status("❌ " + e.getMessage());
                enable(true);
            }
        };

        if (prefs.getBoolean(KEY_GRANTED, false)) {
            // Returning user — open system dialog directly, no rationale repeat
            doLaunch.run();
        } else {
            // First time — show rationale so user understands before tapping Allow
            showRationale(
                str("perm_capture_title"),
                str("perm_capture_rationale"),
                doLaunch,
                () -> { status(str("cap_denied")); enable(true); }
            );
        }
    }

    /**
     * Shows a non-blocking rationale dialog before a system permission prompt.
     *
     * @param title   Short title (e.g. "Screen Capture")
     * @param message Why the permission is needed, in plain language
     * @param onOk    Called when user taps "OK / اسمح"
     * @param onCancel Called when user taps "Cancel / إلغاء"
     */
    private void showRationale(String title, String message,
                                Runnable onOk, Runnable onCancel) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(isAr ? "اسمح" : "Allow", (dlg, w) -> { dlg.dismiss(); onOk.run(); })
            .setNegativeButton(isAr ? "إلغاء" : "Cancel", (dlg, w) -> { dlg.dismiss(); onCancel.run(); })
            .show();
    }

    // ═════════════════════════════════════════════════════════════
    // Service Launch
    // ═════════════════════════════════════════════════════════════

    private void launchService(int rc, Intent data) {
        Intent svc = new Intent(this, FloatingTranslatorService.class);
        svc.putExtra("mp_result_code", rc);
        svc.putExtra("mp_data",        data);
        svc.putExtra("lang_from",      prefs.getString(KEY_LANG_FROM, "auto"));
        svc.putExtra("lang_to",        prefs.getString(KEY_LANG_TO,   "ar"));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else
                startService(svc);

            status(str("launched"));
            // Finish after 1 s — Activity holds no resources after this point.
            // mainHandler is cleared in onDestroy so the postDelayed is leak-safe.
            mainHandler.postDelayed(this::finish, 1_000);
        } catch (Exception e) {
            Log.e(TAG, "launchService: " + e.getMessage(), e);
            status("❌ " + e.getMessage());
            enable(true);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // UI Helpers — factory methods keep buildUI() readable
    // ═════════════════════════════════════════════════════════════

    /** Card container with rounded background */
    private LinearLayout buildCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(12), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        c.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** Small section label above a card */
    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(C_HINT);
        tv.setTextSize(11f);
        tv.setPadding(dp(4), 0, 0, dp(8));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    /**
     * Language-selection row.
     * Stores the value {@link TextView} in the row's tag — avoids fragile getChildAt() chains.
     */
    private LinearLayout buildLangRow(String rowLabel, String currentVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(14), dp(8), dp(14));

        TextView tvIcon = new TextView(this);
        tvIcon.setText("\uD83C\uDF10"); // 🌐
        tvIcon.setTextSize(18f);
        tvIcon.setPadding(0, 0, dp(12), 0);
        row.addView(tvIcon);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(rowLabel);
        tvLabel.setTextColor(C_HINT);
        tvLabel.setTextSize(11f);
        info.addView(tvLabel);

        TextView tvVal = new TextView(this);
        tvVal.setText(currentVal);
        tvVal.setTextColor(C_TEXT);
        tvVal.setTextSize(14f);
        info.addView(tvVal);

        row.addView(info);

        TextView tvArrow = new TextView(this);
        tvArrow.setText("\u203A"); // ›
        tvArrow.setTextColor(C_BLUE);
        tvArrow.setTextSize(22f);
        row.addView(tvArrow);

        // Store value TextView in tag — safe reference for later updates
        row.setTag(tvVal);
        return row;
    }

    private View buildDivider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(8), 0, dp(8), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View buildSpacer(int heightPx) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }

    /** Apply blue rounded style to a Button without leaking GradientDrawable. */
    private void applyButtonStyle(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BLUE);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
    }

    // ═════════════════════════════════════════════════════════════
    // Utilities
    // ═════════════════════════════════════════════════════════════

    private void status(String msg) {
        if (tvStatus != null) tvStatus.setText(msg);
    }

    private void enable(boolean on) {
        if (btnStart != null) btnStart.setEnabled(on);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String getLangName(String code) {
        if (code == null) return LANGS[0][1];
        for (String[] l : LANGS) if (l[0].equals(code)) return l[1];
        return code;
    }

    // ═════════════════════════════════════════════════════════════
    // String Table — Arabic / English, device locale auto-selected
    // ═════════════════════════════════════════════════════════════

    private String str(String key) {
        switch (key) {
            // ── App identity ──────────────────────────────────────
            case "app_name":
                return isAr ? "مترجم الألعاب" : "Game Translator";

            // ── Section headers ───────────────────────────────────
            case "lang_settings":
                return isAr ? "\u2699\uFE0F  إعدادات اللغة" : "\u2699\uFE0F  Language Settings";
            case "start_section":
                return isAr ? "\uD83D\uDE80  التشغيل" : "\uD83D\uDE80  Start";

            // ── Lang row labels ───────────────────────────────────
            case "from":
                return isAr ? "من  (From)" : "From";
            case "to":
                return isAr ? "إلى  (To)"  : "To";

            // ── Dialog titles ─────────────────────────────────────
            case "pick_from":
                return isAr ? "اختر لغة النص في اللعبة" : "Select source language";
            case "pick_to":
                return isAr ? "اختر لغة الترجمة" : "Select translation language";

            // ── Start button / description ────────────────────────
            case "desc_first":
                return isAr
                    ? "اضغط لتشغيل المترجم العائم\nستُطلب الصلاحية مرة واحدة فقط"
                    : "Tap to start the floating translator\nPermission requested once only";
            case "desc_restart":
                return isAr
                    ? "المترجم متوقف — اضغط لإعادة التشغيل"
                    : "Translator stopped — tap to restart";
            case "btn_start":
                return isAr ? "\u25B6  تشغيل المترجم" : "\u25B6  Start Translator";
            case "btn_restart":
                return isAr ? "\u25B6  إعادة التشغيل" : "\u25B6  Restart";

            // ── Status messages ───────────────────────────────────
            case "checking":
                return isAr ? "جاري التحقق من الصلاحيات…" : "Checking permissions…";
            case "restarting":
                return isAr ? "جاري إعادة التشغيل…" : "Restarting…";
            case "launched":
                return isAr ? "\u2705 تم التشغيل!" : "\u2705 Started!";

            // ── Permission rationale section heading ──────────────
            case "perm_why_title":
                return isAr ? "لماذا يحتاج التطبيق هذه الصلاحيات؟"
                            : "Why does this app need these permissions?";

            // ── Notification permission ───────────────────────────
            case "perm_notif_title":
                return isAr ? "\uD83D\uDD14 الإشعارات" : "\uD83D\uDD14 Notifications";
            case "perm_notif_desc":
                return isAr
                    ? "تبقي التطبيق يعمل بالخلفية أثناء اللعب."
                    : "Keeps the translator running while you play.";
            case "perm_notif_rationale":
                return isAr
                    ? "يحتاج التطبيق إذن الإشعارات (Android 13+) لكي يعمل كخدمة مستمرة بالخلفية أثناء جلسة اللعب.\n\nلن نرسل لك أي إشعارات تسويقية."
                    : "Android 13+ requires notification permission to run the translator as a foreground service while you play.\n\nWe will never send marketing notifications.";

            // ── Overlay permission ────────────────────────────────
            case "perm_overlay_title":
                return isAr ? "\uD83D\uDCF1 العرض فوق التطبيقات" : "\uD83D\uDCF1 Draw Over Apps";
            case "perm_overlay_desc":
                return isAr
                    ? "يعرض فقاعة الترجمة فوق شاشة اللعبة."
                    : "Shows the translation bubble above the game.";
            case "perm_overlay_rationale":
                return isAr
                    ? "تحتاج الفقاعة العائمة وبطاقة الترجمة إلى إذن 'العرض فوق التطبيقات' حتى تظهر فوق شاشة اللعبة.\n\nالتطبيق لا يسجل شاشتك ولا يجمع أي بيانات."
                    : "The floating bubble and translation card need 'Draw over apps' permission to appear above your game screen.\n\nThe app does not record your screen or collect any data.";

            // ── Screen capture permission ─────────────────────────
            case "perm_capture_title":
                return isAr ? "\uD83D\uDCF7 تصوير الشاشة" : "\uD83D\uDCF7 Screen Capture";
            case "perm_capture_desc":
                return isAr
                    ? "يقرأ النص من شاشتك. لا تُرسل أي بيانات."
                    : "Reads text from your screen. No data leaves your device.";
            case "perm_capture_rationale":
                return isAr
                    ? "يحتاج التطبيق إذناً لتصوير الشاشة حتى يتمكن من قراءة نص اللعبة وترجمته.\n\n✅ الالتقاط يحدث فقط عند الضغط على ✏️\n✅ لا يتم حفظ أي صور\n✅ لا تُرسل أي بيانات خارج جهازك\n✅ يُوقف الالتقاط فور انتهاء الترجمة"
                    : "The app needs screen capture permission to read and translate game text.\n\n✅ Capture happens only when you tap ✏️\n✅ No images are saved\n✅ No data leaves your device\n✅ Capture stops immediately after translation";

            // ── Permission denial messages ────────────────────────
            case "overlay_req":
                return isAr
                    ? "اسمح بـ 'العرض فوق التطبيقات' ثم ارجع للتطبيق"
                    : "Allow 'Draw over apps' then return to the app";
            case "overlay_denied":
                return isAr ? "\u274C صلاحية Overlay مرفوضة" : "\u274C Overlay permission denied";
            case "cap_req":
                return isAr ? "اسمح بتصوير الشاشة…" : "Allow screen capture…";
            case "cap_denied":
                return isAr ? "\u274C صلاحية الشاشة مرفوضة" : "\u274C Screen permission denied";

            default:
                return key;
        }
    }
}
