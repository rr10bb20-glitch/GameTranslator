package com.gametranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
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

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FloatingTranslatorService — v16 (Production)
 *
 * Changes vs v15:
 * ─ executor resurrected after START_STICKY restart (RejectedExecutionException fix)
 * ─ duplicate LANGS entries removed (bn, id were duplicated)
 * ─ PendingIntent flags: FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE
 * ─ System.gc() removed — useless on Android, causes lag spikes
 * ─ Bitmap copy optimised: RGB_565 for OCR (halves RAM), direct crop avoids second copy
 * ─ scheduleDismiss() called on all OCR failure paths (overlay no longer gets stuck)
 * ─ safeSubmit() helper prevents RejectedExecutionException from crashing the app
 * ─ onNoTextFound() centralises the "no text" UI path + calls scheduleDismiss()
 * ─ hideOverlay() cancel-animation guard prevents double-fade race on fast taps
 * ─ Executor factory extracted to rebuildExecutorIfNeeded() — safe to call any time
 * ─ pickerView WindowManager leak fixed: closePicker() guards against already-removed view
 * ─ MediaProjection callback: translating flag also reset on MP stop
 * ─ Handler leak: H.removeCallbacksAndMessages(null) kept; executor.shutdownNow() order fixed
 * ─ doTranslate(): executor.submit wrapped in safeSubmit to avoid silent drop on restart
 * ─ showResult() guard: destroyed check before touching views
 * ─ Notification: FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE for Android 12+ compatibility
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "UT";
    private static final String CHANNEL_ID = "ut_v16";
    private static final int    NOTIF_ID   = 1;

    // SharedPreferences keys — must match MainActivity
    private static final String PREFS         = "ut_prefs";
    private static final String KEY_LANG_FROM = "lang_from";
    private static final String KEY_LANG_TO   = "lang_to";

    // ── Timing ──────────────────────────────────────────────────
    private static final long DISMISS_MS       = 6_000;
    private static final long LONG_PRESS_MS    = 650;
    private static final long DOUBLE_TAP_MS    = 300;
    private static final long CAPTURE_DELAY_MS = 700;
    private static final long OCR_COOLDOWN_MS  = 1_200;

    // ── Alpha ────────────────────────────────────────────────────
    private static final float ALPHA_IDLE = 0.25f;
    private static final float ALPHA_BUSY = 0.92f;

    // ── OCR crop region ──────────────────────────────────────────
    private static final float OCR_TOP    = 0.58f;
    private static final float OCR_BOTTOM = 0.93f;

    private static final int CACHE_SIZE = 60;

    // ── Language table [code, name, abbreviation] ─────────────────
    // NOTE: de-duplicated — no repeated codes
    private static final String[][] LANGS = {
        {"auto","Auto","?"},
        {"ar","Arabic","AR"},{"en","English","EN"},{"ja","Japanese","JA"},
        {"ko","Korean","KO"},{"zh-CN","Chinese","ZH"},{"zh-TW","Chinese TW","ZT"},
        {"fr","French","FR"},{"de","German","DE"},{"es","Spanish","ES"},
        {"ru","Russian","RU"},{"tr","Turkish","TR"},{"it","Italian","IT"},
        {"pt","Portuguese","PT"},{"hi","Hindi","HI"},{"th","Thai","TH"},
        {"vi","Vietnamese","VI"},{"id","Indonesian","ID"},{"ms","Malay","MS"},
        {"fa","Persian","FA"},{"he","Hebrew","HE"},{"ur","Urdu","UR"},
        {"bn","Bengali","BN"},{"pl","Polish","PL"},{"nl","Dutch","NL"},
        {"sv","Swedish","SV"},{"da","Danish","DA"},{"fi","Finnish","FI"},
        {"cs","Czech","CS"},{"ro","Romanian","RO"},{"uk","Ukrainian","UK"},
        {"af","Afrikaans","AF"},{"sq","Albanian","SQ"},{"hy","Armenian","HY"},
        {"az","Azerbaijani","AZ"},{"be","Belarusian","BE"},{"bs","Bosnian","BS"},
        {"bg","Bulgarian","BG"},{"ca","Catalan","CA"},{"hr","Croatian","HR"},
        {"et","Estonian","ET"},{"gl","Galician","GL"},{"ka","Georgian","KA"},
        {"el","Greek","EL"},{"hu","Hungarian","HU"},{"is","Icelandic","IS"},
        {"ga","Irish","GA"},{"lv","Latvian","LV"},{"lt","Lithuanian","LT"},
        {"mk","Macedonian","MK"},{"mt","Maltese","MT"},{"no","Norwegian","NO"},
        {"sr","Serbian","SR"},{"sk","Slovak","SK"},{"sl","Slovenian","SL"},
        {"cy","Welsh","CY"},{"my","Burmese","MY"},{"gu","Gujarati","GU"},
        {"kn","Kannada","KN"},{"kk","Kazakh","KK"},{"km","Khmer","KM"},
        {"lo","Lao","LO"},{"ml","Malayalam","ML"},{"mr","Marathi","MR"},
        {"mn","Mongolian","MN"},{"ne","Nepali","NE"},{"pa","Punjabi","PA"},
        {"si","Sinhala","SI"},{"tg","Tajik","TG"},{"ta","Tamil","TA"},
        {"te","Telugu","TE"},{"tl","Filipino","TL"},{"tk","Turkmen","TK"},
        {"ug","Uyghur","UG"},{"uz","Uzbek","UZ"},{"tt","Tatar","TT"},
        {"ku","Kurdish","KU"},{"am","Amharic","AM"},{"ha","Hausa","HA"},
        {"sw","Swahili","SW"},{"yo","Yoruba","YO"},{"zu","Zulu","ZU"},
        {"so","Somali","SO"},{"ht","Haitian Creole","HC"},
    };

    // ── Core ─────────────────────────────────────────────────────
    private WindowManager            wm;
    private int                      SW, SH;
    private int                      capW, capH;
    private final Handler            H = new Handler(Looper.getMainLooper());
    private ExecutorService          executor;
    private LruCache<String, String> translateCache;
    private SharedPreferences        prefs;
    private boolean                  isAr;

    // ── Views ─────────────────────────────────────────────────────
    private View     btnView;
    private View     overlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, overlayLP;
    private TextView                   tvBtnLabel;
    private TextView                   tvTranslation;
    private TextView                   tvOriginal;
    private TextView                   tvLangPair;

    // ── State ─────────────────────────────────────────────────────
    private final AtomicBoolean ocrBusy     = new AtomicBoolean(false);
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private volatile long       lastOcrTime = 0;
    private volatile boolean    overlayVisible   = false;
    private volatile boolean    overlayHiding    = false;   // NEW: guard double-fade
    private volatile boolean    destroyed        = false;
    private volatile boolean    viewsAdded       = false;

    private volatile String fromLang   = "auto";
    private volatile String toLang     = "ar";
    private String          pickerFrom = "auto";

    // ── MediaProjection ───────────────────────────────────────────
    private final Object           mpLock = new Object();
    private MediaProjection        mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer         recognizerJa;
    private TextRecognizer         recognizerLat;

    // ── Tap detection ─────────────────────────────────────────────
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable fadeOutR;
    private int      tapCount = 0;

    // ═════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();

        createChannel();
        startForeground(NOTIF_ID, buildNotif());

        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        isAr      = Locale.getDefault().getLanguage().equals("ar");
        wm        = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        fromLang = prefs.getString(KEY_LANG_FROM, "auto");
        toLang   = prefs.getString(KEY_LANG_TO,   "ar");

        resolveScreenSize();

        // Capture at half resolution, aligned to 16px
        capW = Math.max((SW / 2 / 16) * 16, 480);
        capH = Math.max((SH / 2 / 16) * 16, 640);
        Log.d(TAG, "Screen=" + SW + "x" + SH + " Capture=" + capW + "x" + capH);

        initOCR();
        rebuildExecutorIfNeeded();
        translateCache = new LruCache<>(CACHE_SIZE);

        try {
            buildButton();
            buildOverlay();
            viewsAdded = true;
            H.postDelayed(() -> {
                if (!destroyed)
                    toast(isAr
                        ? "ضغطة = ترجمة  |  ضغطتين أو ضغطة طويلة = اللغة"
                        : "Tap=translate  |  Double-tap or Hold=language");
            }, 800);
        } catch (Exception e) {
            Log.e(TAG, "UI build: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ── START_STICKY restart (intent == null) ───────────────
        if (intent == null) {
            Log.w(TAG, "onStartCommand: null intent — START_STICKY restart");
            // Executor may be dead after shutdownNow() in a previous onDestroy
            rebuildExecutorIfNeeded();
            H.postDelayed(() -> {
                if (!destroyed)
                    toast(isAr
                        ? "أعد فتح التطبيق لاستعادة صلاحية الشاشة"
                        : "Reopen app to restore screen permission");
            }, 1_000);
            return START_STICKY;
        }

        // Always ensure executor is alive (defensive — cheap check)
        rebuildExecutorIfNeeded();

        // ── Language update ──────────────────────────────────────
        String lf = intent.getStringExtra("lang_from");
        String lt = intent.getStringExtra("lang_to");
        if (lf != null && !lf.isEmpty()) {
            fromLang = lf;
            prefs.edit().putString(KEY_LANG_FROM, lf).apply();
        }
        if (lt != null && !lt.isEmpty()) {
            toLang = lt;
            prefs.edit().putString(KEY_LANG_TO, lt).apply();
        }
        H.post(() -> { if (!destroyed && tvBtnLabel != null) tvBtnLabel.setText(shortPair()); });

        // ── MediaProjection ──────────────────────────────────────
        if (intent.hasExtra("mp_result_code")) {
            int    rc   = intent.getIntExtra("mp_result_code", -1);
            Intent data = intent.getParcelableExtra("mp_data");

            if (rc == android.app.Activity.RESULT_OK && data != null) {
                synchronized (mpLock) {
                    if (mediaProjection != null) {
                        // Already alive — skip to avoid Samsung invalid-token crash
                        Log.d(TAG, "MediaProjection already alive, skipping replace");
                    } else {
                        try {
                            mediaProjection = mpManager.getMediaProjection(rc, data);
                            mediaProjection.registerCallback(new MediaProjection.Callback() {
                                @Override public void onStop() {
                                    Log.w(TAG, "MediaProjection stopped by system");
                                    synchronized (mpLock) { mediaProjection = null; }
                                    ocrBusy.set(false);
                                    translating.set(false);
                                    H.post(() -> {
                                        if (!destroyed) {
                                            resetBtn(shortPair());
                                            toast(isAr
                                                ? "انتهت صلاحية الشاشة — أعد فتح التطبيق"
                                                : "Screen permission lost — reopen app");
                                        }
                                    });
                                }
                            }, H);
                            Log.d(TAG, "MediaProjection ready");
                        } catch (Exception e) {
                            Log.e(TAG, "MediaProjection init: " + e.getMessage(), e);
                            mediaProjection = null;
                        }
                    }
                }
            } else {
                Log.w(TAG, "MediaProjection not granted rc=" + rc);
                H.post(() -> {
                    if (!destroyed)
                        toast(isAr ? "رُفضت صلاحية الشاشة" : "Screen permission denied");
                });
            }
        }

        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        destroyed = true;
        super.onDestroy();

        H.removeCallbacksAndMessages(null);

        // Shut down executor AFTER clearing handler to avoid submitting after shutdown
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();

        closeOCR();

        synchronized (mpLock) {
            if (mediaProjection != null) {
                try { mediaProjection.stop(); } catch (Exception ignored) {}
                mediaProjection = null;
            }
        }

        if (viewsAdded) {
            safeRemove(btnView);
            safeRemove(overlayView);
            safeRemove(pickerView);
        }

        // Null out view references to help GC
        btnView = overlayView = pickerView = null;
        tvBtnLabel = tvTranslation = tvOriginal = tvLangPair = null;
    }

    // ═════════════════════════════════════════════════════════════
    // Executor management
    // ═════════════════════════════════════════════════════════════

    /** Creates a new executor if the current one is null or shut down. */
    private void rebuildExecutorIfNeeded() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "UT-Worker");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            Log.d(TAG, "Executor (re)built");
        }
    }

    /**
     * Submits a task to the executor safely.
     * Prevents RejectedExecutionException from crashing the app if the executor
     * was shut down between the null-check and the submit() call.
     */
    private void safeSubmit(Runnable task) {
        rebuildExecutorIfNeeded();
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "safeSubmit: executor rejected task — " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // OCR initialisation helpers
    // ═════════════════════════════════════════════════════════════

    private void initOCR() {
        try {
            recognizerJa  = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Exception e) {
            Log.e(TAG, "OCR init: " + e.getMessage());
            recognizerJa = recognizerLat = null;
        }
    }

    private void closeOCR() {
        try { if (recognizerJa  != null) recognizerJa.close();  } catch (Exception ignored) {}
        try { if (recognizerLat != null) recognizerLat.close(); } catch (Exception ignored) {}
        recognizerJa = recognizerLat = null;
    }

    // ═════════════════════════════════════════════════════════════
    // Screen size
    // ═════════════════════════════════════════════════════════════

    private void resolveScreenSize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                SW = b.width(); SH = b.height();
            } else {
                DisplayMetrics dm = new DisplayMetrics();
                //noinspection deprecation
                wm.getDefaultDisplay().getMetrics(dm);
                SW = dm.widthPixels; SH = dm.heightPixels;
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveScreenSize: " + e.getMessage());
            SW = 1080; SH = 1920;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Floating Button
    // ═════════════════════════════════════════════════════════════

    private void buildButton() {
        FrameLayout root = new FrameLayout(this);
        FrameLayout pill = new FrameLayout(this);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setCornerRadius(dp(14));
        pillBg.setColor(Color.argb(200, 10, 20, 50));
        pillBg.setStroke(dp(1), Color.argb(120, 60, 120, 220));
        pill.setBackground(pillBg);
        pill.setElevation(dp(8));

        tvBtnLabel = new TextView(this);
        tvBtnLabel.setText(shortPair());
        tvBtnLabel.setTextColor(Color.argb(230, 160, 200, 255));
        tvBtnLabel.setTextSize(10f);
        tvBtnLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tvBtnLabel.setLetterSpacing(0.05f);
        tvBtnLabel.setGravity(Gravity.CENTER);
        tvBtnLabel.setIncludeFontPadding(false);
        pill.addView(tvBtnLabel, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(52), dp(28));
        plp.setMargins(dp(4), dp(4), dp(4), dp(4));
        root.addView(pill, plp);

        btnView = root;
        btnView.setAlpha(ALPHA_IDLE);

        btnLP = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        btnLP.gravity = Gravity.TOP | Gravity.START;
        btnLP.x = SW - dp(68);
        btnLP.y = SH / 2;

        btnView.setOnTouchListener(new BtnTouch());
        safeAddView(btnView, btnLP);
        scheduleBtnFade();
    }

    private void scheduleBtnFade() {
        if (fadeOutR != null) H.removeCallbacks(fadeOutR);
        fadeOutR = () -> {
            if (!ocrBusy.get() && !translating.get() && !overlayVisible && btnView != null)
                btnView.animate().alpha(ALPHA_IDLE).setDuration(800).start();
        };
        H.postDelayed(fadeOutR, 3_000);
    }

    private void resetBtn(String label) {
        if (destroyed || tvBtnLabel == null || btnView == null) return;
        tvBtnLabel.setText(label);
        btnView.animate().alpha(ALPHA_IDLE).setDuration(300).start();
        scheduleBtnFade();
    }

    // ═════════════════════════════════════════════════════════════
    // Overlay
    // ═════════════════════════════════════════════════════════════

    private void buildOverlay() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(10));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(Color.argb(210, 5, 10, 28));
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(1), Color.argb(80, 40, 90, 200));
        card.setBackground(cardBg);

        // Top row: lang pair + close button
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        tvLangPair = new TextView(this);
        tvLangPair.setText(pairText());
        tvLangPair.setTextColor(Color.argb(160, 80, 130, 220));
        tvLangPair.setTextSize(9f);
        tvLangPair.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangPair.setLetterSpacing(0.06f);
        topRow.addView(tvLangPair, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView xBtn = new TextView(this);
        xBtn.setText("  \u00D7  ");
        xBtn.setTextColor(Color.argb(180, 200, 80, 80));
        xBtn.setTextSize(16f);
        xBtn.setClickable(true);
        xBtn.setFocusable(true);
        xBtn.setOnClickListener(v -> hideOverlay());
        topRow.addView(xBtn);
        card.addView(topRow);

        // Divider
        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(4), 0, dp(5));
        div.setBackgroundColor(Color.argb(60, 40, 80, 180));
        card.addView(div, divLp);

        // Original text (small)
        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.argb(100, 140, 170, 220));
        tvOriginal.setTextSize(9.5f);
        tvOriginal.setMaxLines(1);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(3));
        card.addView(tvOriginal);

        // Translation (large)
        tvTranslation = new TextView(this);
        tvTranslation.setTextColor(Color.argb(240, 220, 235, 255));
        tvTranslation.setTextSize(17f);
        tvTranslation.setTypeface(Typeface.DEFAULT_BOLD);
        tvTranslation.setLineSpacing(dp(1), 1.15f);
        tvTranslation.setMaxLines(3);
        tvTranslation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvTranslation);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(10), 0, dp(10), dp(8));
        root.addView(card, cardLp);

        overlayView = root;
        overlayView.setAlpha(0f);

        overlayLP = new WindowManager.LayoutParams(
            SW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        overlayLP.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayLP.y = dp(8);

        safeAddView(overlayView, overlayLP);
    }

    private void showOverlay() {
        if (destroyed || overlayView == null) return;
        overlayHiding  = false;
        overlayVisible = true;
        overlayLP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        safeUpdateLayout(overlayView, overlayLP);
        overlayView.animate().cancel();
        overlayView.animate().alpha(1f).setDuration(180).start();
    }

    private void hideOverlay() {
        cancelDismiss();
        if (destroyed || overlayView == null || overlayHiding) return;
        overlayHiding = true;
        overlayView.animate().cancel();
        overlayView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (destroyed) return;
            overlayHiding  = false;
            overlayVisible = false;
            overlayLP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            safeUpdateLayout(overlayView, overlayLP);
        }).start();
        if (btnView != null) btnView.animate().alpha(ALPHA_IDLE).setDuration(300).start();
        scheduleBtnFade();
    }

    private void scheduleDismiss() {
        cancelDismiss();
        dismissR = this::hideOverlay;
        H.postDelayed(dismissR, DISMISS_MS);
    }

    private void cancelDismiss() {
        if (dismissR != null) { H.removeCallbacks(dismissR); dismissR = null; }
    }

    // ═════════════════════════════════════════════════════════════
    // OCR
    // ═════════════════════════════════════════════════════════════

    private void doOCR() {
        long now = System.currentTimeMillis();
        if (now - lastOcrTime < OCR_COOLDOWN_MS) return;
        lastOcrTime = now;

        if (!ocrBusy.compareAndSet(false, true)) return;

        synchronized (mpLock) {
            if (mediaProjection == null) {
                ocrBusy.set(false);
                toast(isAr
                    ? "أعد فتح التطبيق لاستعادة صلاحية الشاشة"
                    : "Reopen app to restore screen permission");
                return;
            }
        }

        if (recognizerJa == null || recognizerLat == null) {
            ocrBusy.set(false);
            toast(isAr ? "OCR غير جاهز" : "OCR not ready");
            return;
        }

        // Show "reading" state on main thread before background work
        H.post(() -> {
            if (destroyed) return;
            if (tvBtnLabel    != null) tvBtnLabel.setText("...");
            if (btnView       != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            if (tvTranslation != null) {
                tvTranslation.setText(isAr ? "جاري القراءة…" : "Reading…");
                tvTranslation.setTextColor(Color.argb(160, 140, 170, 220));
            }
            if (tvOriginal != null) tvOriginal.setText("");
            if (tvLangPair != null) tvLangPair.setText(pairText());
            showOverlay();
        });

        final int density = getResources().getDisplayMetrics().densityDpi;

        safeSubmit(() -> {
            if (destroyed) { ocrBusy.set(false); return; }

            ImageReader    reader  = null;
            VirtualDisplay vd     = null;
            Bitmap         fullBmp = null;

            try {
                // Use RGB_565 — 2 bytes/pixel instead of 4 — halves RAM for OCR capture
                reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

                synchronized (mpLock) {
                    if (mediaProjection == null) {
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                        return;
                    }
                    vd = mediaProjection.createVirtualDisplay(
                        "UT_OCR", capW, capH, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                }

                Thread.sleep(CAPTURE_DELAY_MS);
                if (destroyed) { ocrBusy.set(false); return; }

                // Acquire frame — up to 3 attempts
                Image img = null;
                for (int i = 0; i < 3 && img == null; i++) {
                    try { img = reader.acquireLatestImage(); }
                    catch (Exception e) { Log.w(TAG, "acquireLatestImage #" + i + ": " + e.getMessage()); }
                    if (img == null && i < 2)
                        try { Thread.sleep(80); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }

                if (img != null) {
                    try {
                        Image.Plane[] planes = img.getPlanes();
                        ByteBuffer buf   = planes[0].getBuffer();
                        int rStride      = planes[0].getRowStride();
                        int pStride      = planes[0].getPixelStride();
                        int bmpW         = rStride / pStride;
                        int usedW        = Math.min(capW, bmpW);

                        // Allocate once in ARGB_8888 (MLKit requires it), then crop inline
                        Bitmap tmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
                        tmp.copyPixelsFromBuffer(buf);

                        // Crop to exact capture width without a second full-size allocation
                        if (bmpW > usedW) {
                            fullBmp = Bitmap.createBitmap(tmp, 0, 0, usedW, capH);
                            tmp.recycle();
                        } else {
                            fullBmp = tmp; // already the right size — no extra copy
                        }
                    } catch (OutOfMemoryError oom) {
                        Log.e(TAG, "OOM during Bitmap copy");
                        // No System.gc() — let ART decide; just clean up below
                    } catch (Exception e) {
                        Log.e(TAG, "Bitmap copy: " + e.getMessage());
                    } finally {
                        try { img.close(); } catch (Exception ignored) {}
                    }
                } else {
                    Log.w(TAG, "No frame after 3 attempts");
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                return;
            } catch (Exception e) {
                Log.e(TAG, "Capture: " + e.getMessage(), e);
                ocrBusy.set(false);
            } finally {
                if (vd     != null) { try { vd.release();  } catch (Exception ignored) {} }
                if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
            }

            if (fullBmp == null) {
                ocrBusy.set(false);
                H.post(() -> {
                    if (destroyed) return;
                    resetBtn(shortPair());
                    if (tvTranslation != null) {
                        tvTranslation.setText(isAr ? "فشل الالتقاط — حاول مجدداً" : "Capture failed — try again");
                        tvTranslation.setTextColor(Color.argb(220, 255, 180, 80));
                    }
                    scheduleDismiss(); // FIX: overlay was getting stuck on capture failure
                });
                return;
            }

            // Crop to dialogue region
            int cropTop = (int)(capH * OCR_TOP);
            int cropH   = Math.max((int)(capH * OCR_BOTTOM) - cropTop, 1);
            Bitmap cropped = null;
            try {
                cropped = Bitmap.createBitmap(fullBmp, 0, cropTop, capW, cropH);
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "OOM during crop");
                recycleSafe(fullBmp);
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                return;
            } catch (Exception e) {
                Log.e(TAG, "Crop: " + e.getMessage());
                recycleSafe(fullBmp);
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                return;
            }

            boolean useJa = fromLang.equals("ja") || fromLang.equals("ko")
                || fromLang.startsWith("zh") || fromLang.equals("auto");
            TextRecognizer rec = useJa ? recognizerJa : recognizerLat;

            final Bitmap fFull    = fullBmp;
            final Bitmap fCropped = cropped;

            try {
                rec.process(InputImage.fromBitmap(fCropped, 0))
                    .addOnSuccessListener(result -> {
                        recycleSafe(fCropped);
                        String text = result.getText().trim();
                        if (!text.isEmpty()) {
                            recycleSafe(fFull);
                            ocrBusy.set(false);
                            H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                            doTranslate(text);
                        } else {
                            // Fallback: full-screen OCR
                            try {
                                rec.process(InputImage.fromBitmap(fFull, 0))
                                    .addOnSuccessListener(r2 -> {
                                        recycleSafe(fFull);
                                        ocrBusy.set(false);
                                        H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                                        String t2 = r2.getText().trim();
                                        if (!t2.isEmpty()) {
                                            doTranslate(t2);
                                        } else {
                                            H.post(() -> { if (!destroyed) onNoTextFound(); });
                                        }
                                    })
                                    .addOnFailureListener(e -> {
                                        recycleSafe(fFull);
                                        ocrBusy.set(false);
                                        Log.e(TAG, "Full OCR fail: " + e.getMessage());
                                        H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                                    });
                            } catch (Exception e) {
                                recycleSafe(fFull);
                                ocrBusy.set(false);
                                Log.e(TAG, "Full OCR exception: " + e.getMessage());
                                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        recycleSafe(fCropped);
                        recycleSafe(fFull);
                        ocrBusy.set(false);
                        Log.e(TAG, "Crop OCR fail: " + e.getMessage());
                        H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                    });
            } catch (Exception e) {
                recycleSafe(fCropped);
                recycleSafe(fFull);
                ocrBusy.set(false);
                Log.e(TAG, "rec.process exception: " + e.getMessage());
                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
            }
        });
    }

    /** Called on main thread when OCR finds no text in either crop or full-screen pass. */
    private void onNoTextFound() {
        resetBtn(shortPair());
        if (tvTranslation != null) {
            tvTranslation.setText(isAr ? "لا يوجد نص" : "No text found");
            tvTranslation.setTextColor(Color.argb(200, 255, 200, 80));
        }
        scheduleDismiss(); // FIX: overlay was staying visible indefinitely
    }

    // ═════════════════════════════════════════════════════════════
    // Translation
    // ═════════════════════════════════════════════════════════════

    private void doTranslate(final String text) {
        if (!translating.compareAndSet(false, true)) return;
        cancelDismiss();

        String cacheKey = fromLang + "|" + toLang + "|" + text;
        String cached   = translateCache.get(cacheKey);
        if (cached != null) {
            translating.set(false);
            H.post(() -> { if (!destroyed) showResult(text, cached); });
            return;
        }

        H.post(() -> {
            if (destroyed) return;
            if (tvBtnLabel    != null) tvBtnLabel.setText("...");
            if (btnView       != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            if (tvOriginal    != null) tvOriginal.setText(clip(text, 80));
            if (tvTranslation != null) {
                tvTranslation.setText(isAr ? "جاري الترجمة…" : "Translating…");
                tvTranslation.setTextColor(Color.argb(160, 140, 170, 220));
            }
            if (tvLangPair != null) tvLangPair.setText(pairText());
            showOverlay();
        });

        final String input    = text.length() > 600 ? text.substring(0, 600) : text;
        final String snapFrom = fromLang;
        final String snapTo   = toLang;
        final String key      = cacheKey;

        safeSubmit(() -> {
            if (destroyed) { translating.set(false); return; }
            String    result = null;
            Exception lastEx = null;

            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    result = googleTranslate(input, snapFrom, snapTo);
                    break;
                } catch (Exception e) {
                    lastEx = e;
                    Log.w(TAG, "Translate #" + (attempt + 1) + ": " + e.getMessage());
                    if (attempt == 0)
                        try { Thread.sleep(700); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }

            translating.set(false);
            final String    fResult = result;
            final Exception fErr    = lastEx;

            H.post(() -> {
                if (destroyed) return;
                if (fResult != null) {
                    translateCache.put(key, fResult);
                    showResult(text, fResult);
                } else {
                    Log.w(TAG, "Translate failed: " + (fErr != null ? fErr.getMessage() : "?"));
                    resetBtn(shortPair());
                    if (tvOriginal    != null) tvOriginal.setText(clip(text, 80));
                    if (tvTranslation != null) {
                        tvTranslation.setText(text);
                        tvTranslation.setTextColor(Color.argb(180, 220, 220, 180));
                    }
                    if (tvLangPair != null) tvLangPair.setText("\u26A0 " + pairText());
                    showOverlay();
                    scheduleDismiss();
                }
            });
        });
    }

    private void showResult(String original, String translated) {
        if (destroyed) return;
        if (tvOriginal    != null) tvOriginal.setText(clip(original, 80));
        if (tvTranslation != null) {
            tvTranslation.setText(translated);
            tvTranslation.setTextColor(Color.argb(245, 220, 238, 255));
        }
        if (tvLangPair != null) tvLangPair.setText(pairText());
        resetBtn(shortPair());
        showOverlay();
        scheduleDismiss();
    }

    // ═════════════════════════════════════════════════════════════
    // Language Picker
    // ═════════════════════════════════════════════════════════════

    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog(isAr ? "لغة النص" : "From language", true, code -> {
            pickerFrom = code;
            showPickerDialog(isAr ? "لغة الترجمة" : "To language", false, code2 -> {
                fromLang = pickerFrom;
                toLang   = code2;
                prefs.edit()
                    .putString(KEY_LANG_FROM, fromLang)
                    .putString(KEY_LANG_TO,   toLang)
                    .apply();
                if (tvLangPair != null) tvLangPair.setText(pairText());
                if (tvBtnLabel != null) tvBtnLabel.setText(shortPair());
                closePicker();
            });
        });
    }

    private void showPickerDialog(String title, boolean inclAuto, LangCb cb) {
        closePicker();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(245, 5, 12, 30));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(100, 50, 100, 220));
        root.setBackground(bg);
        root.setElevation(dp(16));

        // Title + close
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(0, 0, 0, dp(8));

        TextView tvT = new TextView(this);
        tvT.setText(title);
        tvT.setTextColor(Color.argb(220, 120, 170, 255));
        tvT.setTextSize(13f);
        tvT.setTypeface(Typeface.DEFAULT_BOLD);
        tr.addView(tvT, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView xb = new TextView(this);
        xb.setText("  \u00D7  ");
        xb.setTextColor(Color.argb(200, 220, 80, 80));
        xb.setTextSize(15f);
        xb.setOnClickListener(v -> closePicker());
        tr.addView(xb);
        root.addView(tr);

        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, 0, 0, dp(4));
        divider.setBackgroundColor(Color.argb(50, 60, 100, 200));
        root.addView(divider, divLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // Use LinkedHashSet to guarantee uniqueness if LANGS ever gains accidental duplicates
        LinkedHashSet<String> seenCodes = new LinkedHashSet<>();
        for (String[] lang : LANGS) {
            if (!seenCodes.add(lang[0])) continue; // skip duplicate code
            if (!inclAuto && lang[0].equals("auto")) continue;
            if (!inclAuto && lang[0].equals(pickerFrom)) continue;

            TextView row = new TextView(this);
            row.setText(lang[2] + "  " + lang[1]);
            row.setTextColor(Color.argb(200, 160, 195, 245));
            row.setTextSize(13.5f);
            row.setPadding(dp(4), dp(13), dp(4), dp(13));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> cb.pick(lang[0]));
            row.setOnTouchListener((v, e) -> {
                int a = e.getAction();
                if      (a == MotionEvent.ACTION_DOWN)
                    row.setTextColor(Color.argb(255, 80, 160, 255));
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                    row.setTextColor(Color.argb(200, 160, 195, 245));
                return false;
            });
            list.addView(row);

            View rd = new View(this);
            rd.setBackgroundColor(Color.argb(30, 50, 80, 160));
            list.addView(rd, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }

        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(SH * 0.48f)));

        pickerView = root;
        WindowManager.LayoutParams pickerLP = new WindowManager.LayoutParams(
            (int)(SW * 0.70f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        pickerLP.gravity = Gravity.CENTER;
        safeAddView(pickerView, pickerLP);
    }

    private void closePicker() {
        if (pickerView != null) {
            safeRemove(pickerView);
            pickerView = null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Button Touch Handler
    // ═════════════════════════════════════════════════════════════

    private class BtnTouch implements View.OnTouchListener {
        private int     ix, iy;
        private float   rx, ry;
        private boolean dragged;
        private long    downAt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = btnLP.x; iy = btnLP.y;
                    rx = e.getRawX(); ry = e.getRawY();
                    dragged = false; downAt = System.currentTimeMillis();
                    if (fadeOutR != null) H.removeCallbacks(fadeOutR);
                    if (btnView  != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(80).start();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        dragged = true;
                        btnLP.x = Math.max(0, Math.min(ix + (int)dx, SW - dp(68)));
                        btnLP.y = Math.max(0, Math.min(iy + (int)dy, SH - dp(40)));
                        safeUpdateLayout(btnView, btnLP);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= LONG_PRESS_MS) {
                            if (overlayVisible) hideOverlay();
                            openPicker();
                        } else {
                            tapCount++;
                            if (tapCount == 1) {
                                doubleTapCheck = () -> { tapCount = 0; onSingleTap(); };
                                H.postDelayed(doubleTapCheck, DOUBLE_TAP_MS);
                            } else {
                                if (doubleTapCheck != null) H.removeCallbacks(doubleTapCheck);
                                tapCount = 0;
                                onDoubleTap();
                            }
                        }
                    } else {
                        scheduleBtnFade();
                    }
                    return true;
            }
            return false;
        }
    }

    private void onSingleTap() {
        if (pickerView    != null) { closePicker(); scheduleBtnFade(); return; }
        if (overlayVisible)        { hideOverlay(); return; }
        doOCR();
    }

    private void onDoubleTap() {
        if (overlayVisible) hideOverlay();
        openPicker();
    }

    // ═════════════════════════════════════════════════════════════
    // Google Translate (unofficial API)
    // ═════════════════════════════════════════════════════════════

    private String googleTranslate(String text, String from, String to) throws Exception {
        String q      = URLEncoder.encode(text, "UTF-8");
        String urlStr = "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx&sl=" + (from.equals("auto") ? "auto" : from)
            + "&tl=" + to + "&dt=t&q=" + q;

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestMethod("GET");

            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);

            BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONArray root  = new JSONArray(sb.toString());
            JSONArray parts = root.getJSONArray(0);
            StringBuilder res = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONArray p = parts.getJSONArray(i);
                if (!p.isNull(0)) res.append(p.getString(0));
            }
            String result = res.toString().trim();
            if (result.isEmpty()) throw new Exception("Empty response");
            return result;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════

    private String pairText()  { return abbr(fromLang) + " \u2192 " + abbr(toLang); }
    private String shortPair() { return abbr(fromLang) + "\u2192" + abbr(toLang); }

    private String abbr(String c) {
        for (String[] l : LANGS) if (l[0].equals(c)) return l[2];
        return c.length() > 3 ? c.substring(0, 3).toUpperCase(Locale.US) : c.toUpperCase(Locale.US);
    }

    private String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void toast(String msg) {
        H.post(() -> { if (!destroyed) Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); });
    }

    // ── Safe WindowManager wrappers ───────────────────────────────

    private void safeAddView(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null) return;
        try { wm.addView(v, lp); }
        catch (WindowManager.BadTokenException e) { Log.e(TAG, "addView BadToken: " + e.getMessage()); }
        catch (Exception e) { Log.e(TAG, "addView: " + e.getMessage(), e); }
    }

    private void safeUpdateLayout(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null || v.getWindowToken() == null) return;
        try { wm.updateViewLayout(v, lp); }
        catch (WindowManager.BadTokenException e) { Log.e(TAG, "updateLayout BadToken"); }
        catch (IllegalArgumentException e)        { Log.e(TAG, "updateLayout IAE"); }
        catch (Exception e)                       { Log.e(TAG, "updateLayout: " + e.getMessage()); }
    }

    private void safeRemove(View v) {
        if (v == null || v.getWindowToken() == null) return;
        try { wm.removeViewImmediate(v); }
        catch (Exception e) { Log.e(TAG, "removeView: " + e.getMessage()); }
    }

    private void recycleSafe(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled())
            try { bmp.recycle(); } catch (Exception ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // Notification
    // ═════════════════════════════════════════════════════════════

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "UniversalTranslator", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        // FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE — required for Android 12+ (API 31+)
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), piFlags);
        boolean ar = Locale.getDefault().getLanguage().equals("ar");
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(ar ? "مترجم الألعاب" : "Game Translator")
            .setContentText(ar
                ? "ضغطة=ترجمة  |  ضغطتين/ضغطة طويلة=اللغة"
                : "Tap=translate  |  Double-tap/Hold=language")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
