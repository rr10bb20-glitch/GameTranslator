package com.gametranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * FloatingTranslatorService — v5 Minimal & Clean
 * ═══════════════════════════════════════════════════════
 *
 * الصلاحيات المستخدمة فعلياً:
 *   INTERNET               — Google Translate عبر HTTPS (لا API key)
 *   SYSTEM_ALERT_WINDOW    — الأوفرلاي
 *   FOREGROUND_SERVICE     — الخدمة
 *
 * خصائص اللوحة:
 *   ✅ FLAG_NOT_TOUCHABLE   — لا تحجب اللمس أبداً، اللعبة تستقبل كل اللمسات
 *   ✅ FLAG_NOT_FOCUSABLE   — لا تسرق focus لوحة المفاتيح
 *   ✅ FLAG_NOT_TOUCH_MODAL — لا تحجب events خارجها
 *   ✅ شفافة بالكامل — يُرى ما خلفها
 *   ✅ إغلاق تلقائي بعد 10 ثوان
 *   ✅ لا مفاتيح API — gtx endpoint مجاني
 *
 * تحكم الزر:
 *   نقرة واحدة  — ترجمة الحافظة / إخفاء اللوحة
 *   نقرة مزدوجة — اختيار اللغة
 *   ضغط طويل   — تبديل AUTO MODE
 */
public class FloatingTranslatorService extends Service {

    private static final String CHANNEL_ID = "gt_v5";
    private static final int    NOTIF_ID   = 1;
    private static final long   DISMISS_MS = 10_000;
    private static final long   LONG_MS    = 700;
    private static final long   DOUBLE_MS  = 350;
    private static final float  ALPHA_IDLE = 0.38f;
    private static final float  ALPHA_ON   = 0.96f;

    private static final String[][] LANGS = {
        {"auto","تلقائي","🔍"},
        {"ar","العربية","🇸🇦"},
        {"en","الإنجليزية","🇺🇸"},
        {"ja","اليابانية","🇯🇵"},
        {"ko","الكورية","🇰🇷"},
        {"zh","الصينية","🇨🇳"},
        {"fr","الفرنسية","🇫🇷"},
        {"de","الألمانية","🇩🇪"},
        {"es","الإسبانية","🇪🇸"},
        {"ru","الروسية","🇷🇺"},
        {"tr","التركية","🇹🇷"},
        {"it","الإيطالية","🇮🇹"},
        {"pt","البرتغالية","🇧🇷"},
        {"hi","الهندية","🇮🇳"},
    };

    private WindowManager wm;
    private int SW, SH;
    private final Handler H = new Handler(Looper.getMainLooper());

    // Views
    private View     btnView;
    private View     overlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, overlayLP, pickerLP;

    // Child refs
    private TextView tvBtnIcon;
    private View     autoDot;
    private TextView tvTranslation;
    private TextView tvOriginal;
    private TextView tvLangBar;

    // State
    private boolean overlayVisible = false;
    private boolean autoMode       = false;
    private boolean translating    = false;
    private volatile boolean destroyed = false;
    private String  fromLang       = "auto";
    private String  toLang         = "ar";
    private String  pickerFrom     = "auto";

    // Clipboard
    private ClipboardManager clipMgr;
    private ClipboardManager.OnPrimaryClipChangedListener clipCb;
    private String lastClipHash = "";

    // Timers
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private int      tapCount = 0;

    // ═════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            SW = bounds.width();
            SH = bounds.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getMetrics(dm);
            SW = dm.widthPixels;
            SH = dm.heightPixels;
        }

        fromLang = ClipboardBridge.readFromLang(this);
        toLang   = ClipboardBridge.readToLang(this);
        clipMgr  = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        createChannel();
        startForeground(NOTIF_ID, buildNotif());
        buildButton();
        buildOverlay();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroyed = true;
        cancelDismiss();
        stopAuto();
        safeRemove(btnView);
        safeRemove(overlayView);
        safeRemove(pickerView);
    }

    // ═════════════════════════════════════════════════════
    // Floating Button
    // ═════════════════════════════════════════════════════
    private void buildButton() {
        FrameLayout root   = new FrameLayout(this);
        FrameLayout circle = new FrameLayout(this);

        int sz = dp(54);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(sz, sz);
        clp.setMargins(dp(6), dp(6), dp(6), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#0f1e40"));
        bg.setStroke(dp(2), Color.parseColor("#2a5090"));
        circle.setBackground(bg);
        circle.setElevation(dp(12));

        tvBtnIcon = new TextView(this);
        tvBtnIcon.setText("🌐");
        tvBtnIcon.setTextSize(21f);
        tvBtnIcon.setGravity(Gravity.CENTER);
        tvBtnIcon.setIncludeFontPadding(false);
        circle.addView(tvBtnIcon, matchParent());

        // Auto dot
        autoDot = new View(this);
        int dotSz = dp(9);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dotSz, dotSz);
        dotLp.gravity = Gravity.TOP | Gravity.END;
        dotLp.setMargins(0, dp(3), dp(3), 0);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#00e676"));
        autoDot.setBackground(dotBg);
        autoDot.setVisibility(View.GONE);
        circle.addView(autoDot, dotLp);

        root.addView(circle, clp);
        btnView = root;
        btnView.setAlpha(ALPHA_IDLE);

        btnLP = new WindowManager.LayoutParams(
            wc(), wc(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        btnLP.gravity = Gravity.TOP | Gravity.START;
        btnLP.x = SW - dp(76);
        btnLP.y = SH / 2 - dp(34);

        btnView.setOnTouchListener(new BtnTouch());
        wm.addView(btnView, btnLP);
    }

    // ═════════════════════════════════════════════════════
    // Overlay — FLAG_NOT_TOUCHABLE: لا تحجب اللمس أبداً
    // ═════════════════════════════════════════════════════
    private void buildOverlay() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(10), dp(4), dp(10), dp(4));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(10));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        // شفافية 85% — يُرى ما خلفها
        cardBg.setColor(Color.argb(217, 4, 8, 22));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.parseColor("#1e3566"));
        card.setBackground(cardBg);

        // شريط اللغة (أعلى)
        tvLangBar = new TextView(this);
        tvLangBar.setText(pairText());
        tvLangBar.setTextColor(Color.parseColor("#4a6a9a"));
        tvLangBar.setTextSize(9f);
        tvLangBar.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangBar.setLetterSpacing(0.08f);
        tvLangBar.setPadding(0, 0, 0, dp(4));
        card.addView(tvLangBar);

        // فاصل
        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, 0, 0, dp(6));
        div.setBackgroundColor(Color.parseColor("#121e40"));
        card.addView(div, divLp);

        // النص الأصلي (صغير، خافت)
        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.parseColor("#2e4466"));
        tvOriginal.setTextSize(10f);
        tvOriginal.setMaxLines(1);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(4));
        card.addView(tvOriginal);

        // الترجمة (بارزة)
        tvTranslation = new TextView(this);
        tvTranslation.setText("جاهز  🌐");
        tvTranslation.setTextColor(Color.parseColor("#364a70"));
        tvTranslation.setTextSize(18f);
        tvTranslation.setTypeface(Typeface.DEFAULT_BOLD);
        tvTranslation.setLineSpacing(dp(2), 1.2f);
        tvTranslation.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        tvTranslation.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tvTranslation.setMaxLines(4);
        tvTranslation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvTranslation);

        // تلميح صغير
        TextView hint = new TextView(this);
        hint.setText("⏱ يختفي بعد 10 ث  •  اضغط 🌐 للإغلاق");
        hint.setTextColor(Color.parseColor("#1e3050"));
        hint.setTextSize(8f);
        hint.setPadding(0, dp(6), 0, 0);
        card.addView(hint);

        outer.addView(card, new LinearLayout.LayoutParams(mp(), wc()));
        overlayView = outer;
        overlayView.setAlpha(0f);

        overlayLP = new WindowManager.LayoutParams(
            SW, wc(), overlayType(),
            // ══ FLAG_NOT_TOUCHABLE: اللوحة لا تستقبل اللمس إطلاقاً ══
            // كل اللمسات تصل للتطبيق/اللعبة تحتها مباشرة
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        );
        overlayLP.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayLP.y = dp(10);

        wm.addView(overlayView, overlayLP);
    }

    // ═════════════════════════════════════════════════════
    // Show / Hide
    // ═════════════════════════════════════════════════════
    private void showOverlay() {
        overlayVisible = true;
        overlayView.animate().alpha(0.92f).setDuration(200).start();
    }

    private void hideOverlay() {
        cancelDismiss();
        overlayView.animate().alpha(0f).setDuration(180)
            .withEndAction(() -> overlayVisible = false).start();
        btnView.setAlpha(ALPHA_IDLE);
    }

    private void scheduleDismiss() {
        cancelDismiss();
        dismissR = this::hideOverlay;
        H.postDelayed(dismissR, DISMISS_MS);
    }

    private void cancelDismiss() {
        if (dismissR != null) { H.removeCallbacks(dismissR); dismissR = null; }
    }

    // ═════════════════════════════════════════════════════
    // AUTO MODE
    // ═════════════════════════════════════════════════════
    private void startAuto() {
        if (autoMode) return;
        autoMode = true;
        if (autoDot != null) autoDot.setVisibility(View.VISIBLE);
        clipCb = () -> H.post(() -> {
            String t = clip();
            if (t != null && !t.isEmpty()) {
                String hash = t.substring(0, Math.min(t.length(), 80));
                if (!hash.equals(lastClipHash)) {
                    lastClipHash = hash;
                    doTranslate(t);
                }
            }
        });
        clipMgr.addPrimaryClipChangedListener(clipCb);
        Toast.makeText(this, "✓ AUTO MODE — كل نص تنسخه يُترجم تلقائياً", Toast.LENGTH_SHORT).show();
    }

    private void stopAuto() {
        if (!autoMode) return;
        autoMode = false;
        if (autoDot != null) autoDot.setVisibility(View.GONE);
        if (clipCb  != null) {
            try { clipMgr.removePrimaryClipChangedListener(clipCb); } catch (Exception ignored) {}
            clipCb = null;
        }
        Toast.makeText(this, "AUTO MODE معطّل", Toast.LENGTH_SHORT).show();
    }

    // ═════════════════════════════════════════════════════
    // Translation
    // ═════════════════════════════════════════════════════
    private void doTranslate(String text) {
        if (translating) return;
        translating = true;
        cancelDismiss();

        tvBtnIcon.setText("⏳");
        btnView.setAlpha(ALPHA_ON);

        if (tvOriginal != null)
            tvOriginal.setText(text.length() > 80 ? text.substring(0, 80) + "…" : text);
        if (tvTranslation != null) {
            tvTranslation.setText("⏳  جاري الترجمة…");
            tvTranslation.setTextColor(Color.parseColor("#2a4a70"));
        }
        if (tvLangBar != null) tvLangBar.setText(pairText());
        showOverlay();

        final String input = text.length() > 600 ? text.substring(0, 600) : text;
        new Thread(() -> {
            try {
                String result = googleTranslate(input, fromLang, toLang);
                if (destroyed) return;
                H.post(() -> {
                    translating = false;
                    tvBtnIcon.setText("✓");
                    H.postDelayed(() -> tvBtnIcon.setText("🌐"), 2_000);
                    tvTranslation.setText(result);
                    tvTranslation.setTextColor(Color.parseColor("#d0e4ff"));
                    btnView.setAlpha(ALPHA_IDLE);
                    if (!autoMode) scheduleDismiss();
                    try { wm.updateViewLayout(overlayView, overlayLP); } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                if (destroyed) return;
                H.post(() -> {
                    translating = false;
                    tvBtnIcon.setText("🌐");
                    tvTranslation.setText("⚠  " + e.getMessage());
                    tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                    btnView.setAlpha(ALPHA_IDLE);
                });
            }
        }).start();
    }

    // ═════════════════════════════════════════════════════
    // Language Picker (يُفتح بنقرتين على الزر)
    // ═════════════════════════════════════════════════════
    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog("من أي لغة؟", true, code -> {
            pickerFrom = code;
            H.post(() -> showPickerDialog("إلى أي لغة؟", false, code2 -> {
                fromLang = pickerFrom;
                toLang   = code2;
                ClipboardBridge.saveLang(this, fromLang, toLang);
                if (tvLangBar != null) tvLangBar.setText(pairText());
                closePicker();
            }));
        });
    }

    private void showPickerDialog(String title, boolean includeAuto, LangCb cb) {
        closePicker();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2040c22"));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#243a72"));
        root.setBackground(bg);
        root.setElevation(dp(20));

        // Title row
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(0, 0, 0, dp(10));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor("#7aa0d4"));
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams flexLp = new LinearLayout.LayoutParams(0, wc(), 1f);
        tr.addView(tvTitle, flexLp);

        TextView xBtn = new TextView(this);
        xBtn.setText("✕");
        xBtn.setTextColor(Color.parseColor("#e05050"));
        xBtn.setTextSize(16f);
        xBtn.setPadding(dp(12), dp(4), dp(2), dp(4));
        xBtn.setOnClickListener(v -> closePicker());
        tr.addView(xBtn);
        root.addView(tr);

        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, 0, 0, dp(6));
        div.setBackgroundColor(Color.parseColor("#192848"));
        root.addView(div, divLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (String[] lang : LANGS) {
            if (!includeAuto && lang[0].equals("auto")) continue;
            if (!includeAuto && lang[0].equals(pickerFrom)) continue;

            TextView row = new TextView(this);
            row.setText(lang[2] + "  " + lang[1]);
            row.setTextColor(Color.parseColor("#aac0e8"));
            row.setTextSize(15f);
            row.setPadding(dp(6), dp(15), dp(6), dp(15));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> cb.pick(lang[0]));
            row.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN)
                    row.setTextColor(Color.parseColor("#4a90ff"));
                else if (e.getAction() == MotionEvent.ACTION_UP
                      || e.getAction() == MotionEvent.ACTION_CANCEL)
                    row.setTextColor(Color.parseColor("#aac0e8"));
                return false;
            });
            list.addView(row);
            View rd = new View(this);
            LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(mp(), dp(1));
            rd.setBackgroundColor(Color.parseColor("#0c1a38"));
            list.addView(rd, rdLp);
        }

        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(mp(), (int)(SH * 0.5f)));

        pickerView = root;
        pickerLP = new WindowManager.LayoutParams(
            (int)(SW * 0.72f), wc(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        pickerLP.gravity = Gravity.CENTER;
        wm.addView(pickerView, pickerLP);
    }

    private void closePicker() {
        safeRemove(pickerView);
        pickerView = null;
    }

    // ═════════════════════════════════════════════════════
    // Button Touch — نقرة / نقرتان / ضغط طويل / سحب
    // ═════════════════════════════════════════════════════
    private class BtnTouch implements View.OnTouchListener {
        private int   ix, iy;
        private float rx, ry;
        private boolean dragged;
        private long    downAt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = btnLP.x; iy = btnLP.y;
                    rx = e.getRawX(); ry = e.getRawY();
                    dragged = false; downAt = System.currentTimeMillis();
                    btnView.setAlpha(ALPHA_ON);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragged = true;
                        btnLP.x = Math.max(0, Math.min(ix + (int)dx, SW - dp(76)));
                        btnLP.y = Math.max(0, Math.min(iy + (int)dy, SH - dp(76)));
                        try { wm.updateViewLayout(btnView, btnLP); } catch (Exception ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= LONG_MS) {
                            // ضغط طويل → AUTO MODE
                            if (autoMode) stopAuto(); else startAuto();
                        } else {
                            // نقرة — نتحقق هل هي نقرتان
                            tapCount++;
                            if (tapCount == 1) {
                                doubleTapCheck = () -> {
                                    tapCount = 0;
                                    onSingleTap(); // نقرة واحدة مؤكدة
                                };
                                H.postDelayed(doubleTapCheck, DOUBLE_MS);
                            } else {
                                // نقرتان
                                if (doubleTapCheck != null) H.removeCallbacks(doubleTapCheck);
                                tapCount = 0;
                                onDoubleTap();
                            }
                        }
                    } else {
                        btnView.setAlpha(autoMode ? 0.75f : ALPHA_IDLE);
                    }
                    return true;
            }
            return false;
        }
    }

    private void onSingleTap() {
        if (pickerView != null)  { closePicker(); btnView.setAlpha(ALPHA_IDLE); return; }
        if (overlayVisible)       { hideOverlay(); return; }
        String text = clip();
        if (text != null && !text.isEmpty()) {
            doTranslate(text);
        } else {
            Toast.makeText(this, "الحافظة فارغة — انسخ نصاً من اللعبة 📋", Toast.LENGTH_SHORT).show();
            btnView.setAlpha(ALPHA_IDLE);
        }
    }

    private void onDoubleTap() {
        // نقرتان → اختيار اللغة
        if (overlayVisible) hideOverlay();
        openPicker();
    }

    // ═════════════════════════════════════════════════════
    // Google Translate — HTTPS، لا API key، لا بيانات حساسة
    // ═════════════════════════════════════════════════════
    private String googleTranslate(String text, String from, String to) throws Exception {
        String q = URLEncoder.encode(text, "UTF-8");
        String url = "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx&sl=" + (from.equals("auto") ? "auto" : from)
            + "&tl=" + to + "&dt=t&q=" + q;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONArray root = new JSONArray(sb.toString());
        JSONArray parts = root.getJSONArray(0);
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONArray p = parts.getJSONArray(i);
            if (!p.isNull(0)) res.append(p.getString(0));
        }
        String result = res.toString().trim();
        if (result.isEmpty()) throw new Exception("الترجمة فارغة");
        return result;
    }

    // ═════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════
    private String clip() {
        try {
            if (clipMgr != null && clipMgr.hasPrimaryClip()) {
                ClipData.Item it = clipMgr.getPrimaryClip().getItemAt(0);
                if (it != null && it.getText() != null) return it.getText().toString().trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String pairText() { return emoji(fromLang) + " → " + emoji(toLang) + " " + name(toLang); }
    private String emoji(String c) { for (String[] l : LANGS) if (l[0].equals(c)) return l[2]; return "🌐"; }
    private String name(String c)  { for (String[] l : LANGS) if (l[0].equals(c)) return l[1]; return c;    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }
    private int wc() { return WindowManager.LayoutParams.WRAP_CONTENT; }
    private int mp() { return LinearLayout.LayoutParams.MATCH_PARENT;  }
    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }
    private void safeRemove(View v) {
        if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
    }

    // ═════════════════════════════════════════════════════
    // Notification
    // ═════════════════════════════════════════════════════
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "مترجم الألعاب", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌐 مترجم الألعاب يعمل")
            .setContentText("نقرة = ترجمة  •  نقرتان = اللغة  •  ضغط طويل = AUTO MODE")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
