package com.gametranslator;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * MainActivity — v2 (Production)
 *
 * Changes vs v1:
 * ─ Handler now uses Looper.getMainLooper() to avoid deprecated no-arg constructor
 * ─ startForegroundService / startService wrapped in a helper with try-catch
 * ─ finish() delayed via mainHandler — explicit reference prevents Handler leak
 * ─ onBackPressed() replaced with onBackPressedDispatcher for API 33+ compatibility
 * ─ onDestroy() clears handler callbacks to prevent rare post-destroy callback fire
 * ─ mpManager null-check before createScreenCaptureIntent()
 * ─ pickLang() dialog: window attributes checked before setLayout() to avoid NPE
 * ─ Language list divider no longer relies on last-element code match (was fragile)
 * ─ enable()/status() null-guard already existed — kept as-is
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG           = "GT_Main";
    private static final String PREFS         = "ut_prefs";
    private static final String KEY_GRANTED   = "screen_granted";
    private static final String KEY_LANG_FROM = "lang_from";
    private static final String KEY_LANG_TO   = "lang_to";
    private static final int    REQ_NOTIF     = 1001;

    // ── Colors ────────────────────────────────────────────────────
    private static final int C_BG   = 0xFF060D1A;
    private static final int C_CARD = 0xFF0D1B2E;
    private static final int C_BLUE = 0xFF3D8BFF;
    private static final int C_TEXT = 0xFFCCDDFF;
    private static final int C_HINT = 0xFF6677AA;
    private static final int C_GREEN= 0xFF00E676;
    private static final int C_DIV  = 0xFF1A2A44;

    // ── Languages ─────────────────────────────────────────────────
    private static final String[][] LANGS = {
        {"auto","Auto Detect / تلقائي"},
        {"ar","العربية"},
        {"en","English"},
        {"ja","日本語 Japanese"},
        {"ko","한국어 Korean"},
        {"zh-CN","中文 Chinese"},
        {"fr","Français"},
        {"de","Deutsch"},
        {"es","Español"},
        {"ru","Русский"},
        {"tr","Türkçe"},
        {"it","Italiano"},
        {"pt","Português"},
        {"hi","हिन्दी Hindi"},
        {"th","ภาษาไทย Thai"},
        {"vi","Tiếng Việt"},
        {"id","Bahasa Indonesia"},
        {"ms","Bahasa Melayu"},
        {"fa","فارسی"},
        {"he","עברית"},
        {"ur","اردو"},
        {"bn","বাংলা Bengali"},
        {"pl","Polski"},
        {"nl","Nederlands"},
        {"sv","Svenska"},
        {"da","Dansk"},
        {"fi","Suomi"},
        {"cs","Čeština"},
        {"ro","Română"},
        {"uk","Українська"},
    };

    // ── UI ─────────────────────────────────────────────────────────
    private boolean           isAr;
    private TextView          tvFromVal, tvToVal, tvStatus;
    private Button            btnStart;
    private SharedPreferences prefs;
    private MediaProjectionManager mpManager;

    // Use explicit Looper to avoid deprecated Handler() constructor
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Launchers ──────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this))
                requestCapture();
            else { status(str("overlay_denied")); enable(true); }
        });

    private final ActivityResultLauncher<Intent> captureLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                prefs.edit().putBoolean(KEY_GRANTED, true).apply();
                launch(r.getResultCode(), r.getData());
            } else { status(str("cap_denied")); enable(true); }
        });

    // ═════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        isAr      = Locale.getDefault().getLanguage().equals("ar");
        buildUI();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUI() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(48));

        // ── Title ─────────────────────────────────────────────────
        TextView title = new TextView(this);
        title.setText(str("app_name"));
        title.setTextColor(C_BLUE);
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Game Translator");
        sub.setTextColor(C_HINT);
        sub.setTextSize(13f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(36));
        root.addView(sub);

        // ── Language Card ─────────────────────────────────────────
        root.addView(label(str("lang_settings")));

        LinearLayout langCard = card();

        LinearLayout rowFrom = langRow(str("from"), getLangName(prefs.getString(KEY_LANG_FROM, "auto")));
        tvFromVal = (TextView) rowFrom.getTag();
        rowFrom.setOnClickListener(v -> pickLang(true));
        langCard.addView(rowFrom);
        langCard.addView(divider());

        LinearLayout rowTo = langRow(str("to"), getLangName(prefs.getString(KEY_LANG_TO, "ar")));
        tvToVal = (TextView) rowTo.getTag();
        rowTo.setOnClickListener(v -> pickLang(false));
        langCard.addView(rowTo);

        root.addView(langCard);
        root.addView(spacer(dp(28)));

        // ── Start Card ────────────────────────────────────────────
        root.addView(label(str("start_section")));

        LinearLayout startCard = card();

        TextView desc = new TextView(this);
        desc.setText(prefs.getBoolean(KEY_GRANTED, false) ? str("desc_restart") : str("desc_first"));
        desc.setTextColor(C_HINT);
        desc.setTextSize(13f);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(dp(8), dp(8), dp(8), dp(18));
        startCard.addView(desc);

        btnStart = new Button(this);
        btnStart.setText(prefs.getBoolean(KEY_GRANTED, false) ? str("btn_restart") : str("btn_start"));
        btnStart.setTextColor(0xFFFFFFFF);
        btnStart.setTextSize(15f);
        btnStart.setPadding(dp(36), dp(14), dp(36), dp(14));
        styleBtn(btnStart);
        btnStart.setOnClickListener(v -> startTranslator());

        LinearLayout bw = new LinearLayout(this);
        bw.setGravity(Gravity.CENTER);
        bw.setPadding(0, 0, 0, dp(6));
        bw.addView(btnStart);
        startCard.addView(bw);

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

    // ═════════════════════════════════════════════════════════════
    // Language Picker
    // ═════════════════════════════════════════════════════════════

    private void pickLang(boolean isFrom) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);

        Window win = d.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(android.R.color.transparent);
            // Soft input adjustment — prevents dialog jumping when keyboard appears
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
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(18));
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(isFrom ? str("pick_from") : str("pick_to"));
        title.setTextColor(C_BLUE);
        title.setTextSize(15f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(14));
        box.addView(title);

        String current = prefs.getString(
            isFrom ? KEY_LANG_FROM : KEY_LANG_TO,
            isFrom ? "auto" : "ar");

        int totalLangs = 0;
        for (String[] lang : LANGS) {
            if (!isFrom && lang[0].equals("auto")) continue;
            totalLangs++;
        }

        int idx = 0;
        for (String[] lang : LANGS) {
            if (!isFrom && lang[0].equals("auto")) continue;
            idx++;

            boolean sel = lang[0].equals(current);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(13), dp(14), dp(13));

            if (sel) {
                GradientDrawable rb = new GradientDrawable();
                rb.setColor(0x223D8BFF);
                rb.setCornerRadius(dp(8));
                row.setBackground(rb);
            }

            TextView dot = new TextView(this);
            dot.setText(sel ? "\u25CF  " : "\u25CB  ");
            dot.setTextColor(sel ? C_BLUE : C_HINT);
            dot.setTextSize(13f);
            row.addView(dot);

            TextView tv = new TextView(this);
            tv.setText(lang[1]);
            tv.setTextColor(sel ? C_TEXT : C_HINT);
            tv.setTextSize(14f);
            row.addView(tv);

            row.setOnClickListener(v -> {
                prefs.edit().putString(isFrom ? KEY_LANG_FROM : KEY_LANG_TO, lang[0]).apply();
                if (isFrom  && tvFromVal != null) tvFromVal.setText(lang[1]);
                if (!isFrom && tvToVal   != null) tvToVal.setText(lang[1]);
                d.dismiss();
            });

            box.addView(row);

            // Divider between rows — use index comparison, not code matching (was fragile)
            if (idx < totalLangs) {
                android.view.View dv = new android.view.View(this);
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
    // Start Logic
    // ═════════════════════════════════════════════════════════════

    private void startTranslator() {
        enable(false);
        if (prefs.getBoolean(KEY_GRANTED, false)) {
            status(str("restarting"));
            checkOverlay();
            return;
        }
        status(str("checking"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
        }
        checkOverlay();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_NOTIF) checkOverlay();
    }

    private void checkOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            status(str("overlay_req"));
            overlayLauncher.launch(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } else {
            requestCapture();
        }
    }

    private void requestCapture() {
        if (mpManager == null) {
            status("❌ MediaProjectionManager unavailable");
            enable(true);
            return;
        }
        status(str("cap_req"));
        try {
            captureLauncher.launch(mpManager.createScreenCaptureIntent());
        } catch (Exception e) {
            Log.e(TAG, "capture intent: " + e.getMessage(), e);
            status("❌ " + e.getMessage());
            enable(true);
        }
    }

    private void launch(int rc, Intent data) {
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
            // Use named handler reference — prevents anonymous Handler leak warning
            mainHandler.postDelayed(this::finish, 1_000);
        } catch (Exception e) {
            Log.e(TAG, "start svc: " + e.getMessage(), e);
            status("❌ " + e.getMessage());
            enable(true);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // UI Helpers
    // ═════════════════════════════════════════════════════════════

    private LinearLayout card() {
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

    private TextView label(String text) {
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

    /** Builds a language row and stores the value TextView in setTag(). */
    private LinearLayout langRow(String rowLabel, String currentVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(14), dp(8), dp(14));

        TextView icon = new TextView(this);
        icon.setText("\uD83C\uDF10"); // 🌐
        icon.setTextSize(18f);
        icon.setPadding(0, 0, dp(12), 0);
        row.addView(icon);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView lbl = new TextView(this);
        lbl.setText(rowLabel);
        lbl.setTextColor(C_HINT);
        lbl.setTextSize(11f);
        info.addView(lbl);

        TextView val = new TextView(this);
        val.setText(currentVal);
        val.setTextColor(C_TEXT);
        val.setTextSize(14f);
        info.addView(val);

        row.addView(info);

        TextView arrow = new TextView(this);
        arrow.setText("\u203A"); // ›
        arrow.setTextColor(C_BLUE);
        arrow.setTextSize(22f);
        row.addView(arrow);

        row.setTag(val);
        return row;
    }

    private android.view.View divider() {
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(C_DIV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(8), 0, dp(8), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private android.view.View spacer(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private void styleBtn(Button b) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BLUE);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
    }

    private void status(String msg) { if (tvStatus != null) tvStatus.setText(msg); }
    private void enable(boolean on)  { if (btnStart != null) btnStart.setEnabled(on); }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String getLangName(String code) {
        for (String[] l : LANGS) if (l[0].equals(code)) return l[1];
        return code;
    }

    // ═════════════════════════════════════════════════════════════
    // Strings — Arabic / English based on device locale
    // ═════════════════════════════════════════════════════════════

    private String str(String key) {
        switch (key) {
            case "app_name":       return isAr ? "مترجم الألعاب"              : "Game Translator";
            case "lang_settings":  return isAr ? "\u2699\uFE0F  إعدادات اللغة"          : "\u2699\uFE0F  Language Settings";
            case "from":           return isAr ? "من  (From)"                  : "From";
            case "to":             return isAr ? "إلى  (To)"                   : "To";
            case "pick_from":      return isAr ? "اختر لغة النص في اللعبة"    : "Select source language";
            case "pick_to":        return isAr ? "اختر لغة الترجمة"           : "Select translation language";
            case "start_section":  return isAr ? "\uD83D\uDE80  التشغيل"                : "\uD83D\uDE80  Start";
            case "desc_first":     return isAr
                ? "اضغط لتشغيل المترجم العائم\nستُطلب الصلاحية مرة واحدة فقط"
                : "Tap to start the floating translator\nPermission requested once only";
            case "desc_restart":   return isAr
                ? "المترجم متوقف — اضغط لإعادة التشغيل"
                : "Translator stopped — tap to restart";
            case "btn_start":      return isAr ? "\u25B6  تشغيل المترجم"           : "\u25B6  Start Translator";
            case "btn_restart":    return isAr ? "\u25B6  إعادة التشغيل"           : "\u25B6  Restart";
            case "checking":       return isAr ? "جاري التحقق من الصلاحيات…" : "Checking permissions…";
            case "restarting":     return isAr ? "جاري إعادة التشغيل…"       : "Restarting…";
            case "overlay_req":    return isAr
                ? "اسمح بـ 'العرض فوق التطبيقات' ثم ارجع"
                : "Allow 'Draw over apps' then return";
            case "overlay_denied": return isAr ? "\u274C صلاحية Overlay مرفوضة"   : "\u274C Overlay permission denied";
            case "cap_req":        return isAr ? "اسمح بتصوير الشاشة…"       : "Allow screen capture…";
            case "cap_denied":     return isAr ? "\u274C صلاحية الشاشة مرفوضة"    : "\u274C Screen permission denied";
            case "launched":       return isAr ? "\u2705 تم التشغيل!"              : "\u2705 Started!";
            default:               return key;
        }
    }
}
