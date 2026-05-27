package com.gametranslator;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.google.mlkit.nl.translate.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FloatingTranslatorService — v19 (Offline Translation + Smart Network Routing)
 *
 * ── build.gradle (app) dependencies required ──────────────────────────────────
 *   // ML Kit OCR
 *   implementation 'com.google.mlkit:text-recognition:16.0.1'
 *   implementation 'com.google.mlkit:text-recognition-japanese:16.0.1'
 *   // ML Kit On-Device Translation (offline engine — ~30 MB per lang pair)
 *   implementation 'com.google.mlkit:translate:17.0.2'
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NEW in v19:
 * ─ 🌐 TranslationEngine: smart online/offline routing — zero polling
 * ─    ConnectivityManager.NetworkCallback for instant, battery-free net detection
 * ─ 📴 ML Kit On-Device Translation offline fallback (com.google.mlkit:translate)
 * ─    ~30 MB model downloaded once, cached by ML Kit, works without internet
 * ─    CountDownLatch bridge: async Task → sync call on executor thread
 * ─ ⚡  Online timeout reduced 8 s → 4 s for faster offline fallback in games
 * ─ ☁/📴 Mode indicator in UI: shows which engine produced the translation
 * ─ 🛡 3-level fallback: online → offline → show original (never a blank screen)
 * ─ ♻️  Single Translator instance per lang pair — old one closed on lang change
 *
 * NEW in v18:
 * ─ 🎮 Touch passthrough: selectionView is FLAG_NOT_TOUCHABLE (draw-only)
 * ─    Game receives ALL input — analog, buttons, swipes — unaffected
 * ─ ✏️ SelBtnTouch acts as drag-input proxy for the selection rectangle
 * ─    Drag ✏️ button to draw selection; release triggers OCR
 * ─ 🔆 Dynamic dim: 0% opacity when idle, ramps to 40% only while dragging
 * ─    No full-screen black overlay blocking game view
 * ─ 🗺 Crop mapping fix: uses fullBmp.getWidth()/getHeight() (not capW/capH)
 * ─    Eliminates off-by-N errors when rowStride ≠ capW × pixelStride
 * ─ All v17 features preserved (marching ants, corner handles, cache, etc.)
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "UT";
    private static final String CHANNEL_ID = "ut_v17";
    private static final int    NOTIF_ID   = 1;

    private static final String PREFS         = "ut_prefs";
    private static final String KEY_LANG_FROM = "lang_from";
    private static final String KEY_LANG_TO   = "lang_to";

    // ── Timing ──────────────────────────────────────────────────
    private static final long DISMISS_MS       = 7_000;
    private static final long LONG_PRESS_MS    = 620;
    private static final long DOUBLE_TAP_MS    = 280;
    private static final long CAPTURE_DELAY_MS = 550;
    private static final long OCR_COOLDOWN_MS  = 1_000;
    private static final long IDLE_SLEEP_MS   = 4_000;  // enter deep sleep after 4 s idle

    // ── Alpha ────────────────────────────────────────────────────
    private static final float ALPHA_IDLE = 0.22f;
    private static final float ALPHA_BUSY = 0.95f;

    // ── Selection mode constants ─────────────────────────────────
    private static final int   MIN_SELECTION_PX = 40;   // min drag size to trigger OCR
    private static final float DIM_ALPHA         = 0.55f;

    private static final int CACHE_SIZE = 60;

    // ── Language table ───────────────────────────────────────────
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
    private View     btnView;          // main floating pill
    private View     selBtnView;       // ✏️ selection button
    private View     overlayView;      // translation result card
    private View     selectionView;    // full-screen dim + drag canvas
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, selBtnLP, overlayLP, selectionLP;
    private TextView tvBtnLabel;
    private TextView tvTranslation;
    private TextView tvOriginal;
    private TextView tvLangPair;

    // ── State ─────────────────────────────────────────────────────
    private final AtomicBoolean ocrBusy      = new AtomicBoolean(false);
    private final AtomicBoolean translating  = new AtomicBoolean(false);
    private volatile long       lastOcrTime  = 0;
    private volatile boolean    overlayVisible   = false;
    private volatile boolean    overlayHiding    = false;
    private volatile boolean    destroyed        = false;
    private volatile boolean    viewsAdded       = false;
    private volatile boolean    selectionMode    = false;  // NEW

    private volatile String fromLang   = "auto";
    private volatile String toLang     = "ar";
    private String          pickerFrom = "auto";

    // ── MediaProjection ───────────────────────────────────────────
    private final Object           mpLock = new Object();
    private MediaProjection        mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer         recognizerJa;
    private TextRecognizer         recognizerLat;

    // ── Persistent capture (reused across OCR calls — avoids create/destroy overhead) ──
    private VirtualDisplay persistentVD;
    private ImageReader    persistentReader;

    // ── Translation Engine ────────────────────────────────────────
    // Network state — updated instantly via NetworkCallback (no polling)
    private volatile boolean                          netAvailable     = true;
    private ConnectivityManager.NetworkCallback       netCallback;
    private ConnectivityManager                       cm;

    // Offline translator (ML Kit on-device) — lazy-loaded per language pair
    private volatile Translator  offlineTranslator;
    private volatile String      offlinePairKey   = "";   // "from|to" currently loaded
    private final AtomicBoolean  offlineReady     = new AtomicBoolean(false);
    private final AtomicBoolean  offlineLoading   = new AtomicBoolean(false);
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable fadeOutR;
    private Runnable sleepR;          // deep-sleep timer
    private int      tapCount = 0;

    // ── Selection state ───────────────────────────────────────────
    private float selStartX, selStartY, selEndX, selEndY;
    private float selOriginX, selOriginY;          // true drag-start in screen coords
    private SelectionCanvasView canvasView;
    private ValueAnimator       dashAnimator; // marching ants


    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

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
        capW = Math.max((SW / 2 / 16) * 16, 480);
        capH = Math.max((SH / 2 / 16) * 16, 640);
        Log.d(TAG, "Screen=" + SW + "x" + SH + " Capture=" + capW + "x" + capH);

        initOCR();
        rebuildExecutorIfNeeded();
        translateCache = new LruCache<>(CACHE_SIZE);
        startNetworkMonitor();
        // Offline model loaded lazily on first actual translation — not proactively

        try {
            buildButton();
            buildSelectionButton();
            buildOverlay();
            viewsAdded = true;
            H.postDelayed(() -> {
                if (!destroyed)
                    toast(isAr
                        ? "اضغط ✏️ لتحديد النص يدوياً"
                        : "Tap ✏️ to select text manually");
            }, 900);
        } catch (Exception e) {
            Log.e(TAG, "UI build: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "START_STICKY restart");
            rebuildExecutorIfNeeded();
            H.postDelayed(() -> {
                if (!destroyed)
                    toast(isAr
                        ? "أعد فتح التطبيق لاستعادة صلاحية الشاشة"
                        : "Reopen app to restore screen permission");
            }, 1_000);
            return START_STICKY;
        }

        rebuildExecutorIfNeeded();

        String lf = intent.getStringExtra("lang_from");
        String lt = intent.getStringExtra("lang_to");
        if (lf != null && !lf.isEmpty()) { fromLang = lf; prefs.edit().putString(KEY_LANG_FROM, lf).apply(); }
        if (lt != null && !lt.isEmpty()) { toLang   = lt; prefs.edit().putString(KEY_LANG_TO,   lt).apply(); }
        H.post(() -> { if (!destroyed && tvBtnLabel != null) tvBtnLabel.setText(shortPair()); });
        // Offline model will reload lazily on next translation request

        if (intent.hasExtra("mp_result_code")) {
            int    rc   = intent.getIntExtra("mp_result_code", -1);
            Intent data = intent.getParcelableExtra("mp_data");
            if (rc == android.app.Activity.RESULT_OK && data != null) {
                synchronized (mpLock) {
                    if (mediaProjection != null) {
                        Log.d(TAG, "MediaProjection already alive");
                    } else {
                        try {
                            mediaProjection = mpManager.getMediaProjection(rc, data);
                            mediaProjection.registerCallback(new MediaProjection.Callback() {
                                @Override public void onStop() {
                                    Log.w(TAG, "MediaProjection stopped");
                                    synchronized (mpLock) {
                                        mediaProjection = null;
                                        // Release persistent capture tied to this projection
                                        releasePersistentCapture();
                                    }
                                    ocrBusy.set(false);
                                    translating.set(false);
                                    H.post(() -> {
                                        if (!destroyed) {
                                            exitSelectionMode();
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
                H.post(() -> { if (!destroyed) toast(isAr ? "رُفضت صلاحية الشاشة" : "Screen permission denied"); });
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
        stopDashAnimator();

        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
        closeOCR();
        stopNetworkMonitor();
        closeOfflineTranslator();

        synchronized (mpLock) {
            releasePersistentCapture();
            if (mediaProjection != null) {
                try { mediaProjection.stop(); } catch (Exception ignored) {}
                mediaProjection = null;
            }
        }

        if (viewsAdded) {
            safeRemove(btnView);
            safeRemove(selBtnView);
            safeRemove(overlayView);
            safeRemove(selectionView);
            safeRemove(pickerView);
        }

        btnView = selBtnView = overlayView = selectionView = pickerView = null;
        tvBtnLabel = tvTranslation = tvOriginal = tvLangPair = null;
        canvasView = null;
    }


    // ═══════════════════════════════════════════════════════════════
    // Executor
    // ═══════════════════════════════════════════════════════════════

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

    private void safeSubmit(Runnable task) {
        rebuildExecutorIfNeeded();
        try { executor.submit(task); }
        catch (RejectedExecutionException e) { Log.e(TAG, "safeSubmit rejected: " + e.getMessage()); }
    }


    // ═══════════════════════════════════════════════════════════════
    // TranslationEngine — Sleep / Wake
    //
    // Goal: zero idle resource usage.
    //
    // What sleeps:
    //   • persistentVD + persistentReader  (VirtualDisplay / ImageReader — GPU resource)
    //   • offlineTranslator                (ML Kit model handle)
    //   • executor thread                  (background thread)
    //
    // What stays alive 24/7 (all lightweight):
    //   • btnView / selBtnView             (UI only, no processing)
    //   • recognizerJa / recognizerLat     (thin handles, model managed by ML Kit)
    //   • mediaProjection                  (just a token until VD is created)
    //   • netCallback                      (event-driven, zero polling cost)
    //   • translateCache                   (LruCache, pure RAM)
    //
    // Sleep is triggered after IDLE_SLEEP_MS of no translation activity.
    // Wake is instant — executor rebuilt, VD created lazily inside runOCROnSelection,
    // offline model loaded lazily inside doTranslate if needed.
    // ═══════════════════════════════════════════════════════════════

    /** Schedule deep-sleep after IDLE_SLEEP_MS. Safe to call from any thread. */
    private void scheduleEngineSleep() {
        H.removeCallbacks(sleepR != null ? sleepR : () -> {});
        sleepR = this::enterEngineSleep;
        H.postDelayed(sleepR, IDLE_SLEEP_MS);
        Log.d(TAG, "Engine sleep scheduled in " + IDLE_SLEEP_MS + " ms");
    }

    /** Cancel pending sleep — call before starting OCR or translation. */
    private void cancelEngineSleep() {
        if (sleepR != null) {
            H.removeCallbacks(sleepR);
            sleepR = null;
        }
    }

    /**
     * Enter deep sleep — release heavy resources.
     * Called on main thread via Handler.
     */
    private void enterEngineSleep() {
        if (destroyed || ocrBusy.get() || translating.get()) {
            // Still active — reschedule rather than interrupt a live operation
            scheduleEngineSleep();
            return;
        }
        Log.d(TAG, "Engine entering deep sleep");

        // 1. Release VirtualDisplay + ImageReader
        synchronized (mpLock) {
            releasePersistentCapture();
        }

        // 2. Release ML Kit offline translator
        closeOfflineTranslator();

        // 3. Shut down executor thread
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();   // graceful — lets queued tasks finish, then stops
            executor = null;
            Log.d(TAG, "Executor shut down");
        }

        sleepR = null;
    }

    /**
     * Wake up — rebuild executor so next operation can proceed.
     * VirtualDisplay and offline model are created lazily when actually needed.
     * Safe to call from main thread.
     */
    private void wakeEngine() {
        cancelEngineSleep();
        rebuildExecutorIfNeeded();
        Log.d(TAG, "Engine woken");
    }


    // ═══════════════════════════════════════════════════════════════
    // OCR Init
    // ═══════════════════════════════════════════════════════════════

    private void initOCR() {
        try {
            recognizerJa  = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
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

    /** Must be called while holding {@code mpLock}. */
    private void releasePersistentCapture() {
        if (persistentVD != null) {
            try { persistentVD.release(); } catch (Exception ignored) {}
            persistentVD = null;
        }
        if (persistentReader != null) {
            try { persistentReader.close(); } catch (Exception ignored) {}
            persistentReader = null;
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // Screen size
    // ═══════════════════════════════════════════════════════════════

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
        } catch (Exception e) { SW = 1080; SH = 1920; }
    }


    // ═══════════════════════════════════════════════════════════════
    // Floating Button (translate pill)
    // ═══════════════════════════════════════════════════════════════

    private void buildButton() {
        FrameLayout root = new FrameLayout(this);
        FrameLayout pill = new FrameLayout(this);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setCornerRadius(dp(14));
        pillBg.setColor(Color.argb(210, 8, 16, 42));
        pillBg.setStroke(dp(1), Color.argb(130, 50, 110, 230));
        pill.setBackground(pillBg);
        pill.setElevation(dp(8));

        tvBtnLabel = new TextView(this);
        tvBtnLabel.setText(shortPair());
        tvBtnLabel.setTextColor(Color.argb(230, 150, 195, 255));
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


    // ═══════════════════════════════════════════════════════════════
    // Selection Button  ✏️
    // ═══════════════════════════════════════════════════════════════

    private void buildSelectionButton() {
        FrameLayout root = new FrameLayout(this);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(200, 10, 22, 55));
        bg.setStroke(dp(1), Color.argb(140, 80, 160, 255));
        root.setBackground(bg);
        root.setElevation(dp(10));

        TextView icon = new TextView(this);
        icon.setText("✏️");
        icon.setTextSize(15f);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        root.addView(icon, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        selBtnView = root;
        selBtnView.setAlpha(ALPHA_IDLE);

        int size = dp(42);
        selBtnLP = new WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        selBtnLP.gravity = Gravity.TOP | Gravity.START;
        // Position just above the main button
        selBtnLP.x = SW - dp(63);
        selBtnLP.y = SH / 2 - dp(50);

        selBtnView.setOnTouchListener(new SelBtnTouch());
        safeAddView(selBtnView, selBtnLP);
    }


    // ═══════════════════════════════════════════════════════════════
    // Selection Button Touch
    // ═══════════════════════════════════════════════════════════════

    private class SelBtnTouch implements View.OnTouchListener {
        private float rx, ry;
        private int   ix, iy;
        private boolean dragged;
        private boolean selDragging;  // true when this drag is a selection gesture
        private long    downAt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = selBtnLP.x; iy = selBtnLP.y;
                    rx = e.getRawX(); ry = e.getRawY();
                    dragged = false; selDragging = false;
                    downAt = System.currentTimeMillis();
                    selBtnView.animate().alpha(1f).scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragged = true;
                        if (selectionMode && canvasView != null) {
                            // ── SELECTION DRAG MODE ──────────────────────────────
                            // ✏️ button acts as the drag-input proxy.
                            // The draw layer (FLAG_NOT_TOUCHABLE) just renders what
                            // we tell it; the game never loses its input stream.
                            if (!selDragging) {
                                selDragging = true;
                                // FIX: record the CURRENT finger position as the true
                                // selection origin — not the button's down position.
                                // This lets the rectangle start wherever the drag begins.
                                selOriginX = e.getRawX();
                                selOriginY = e.getRawY();
                                startDashAnimator();
                            }
                            canvasView.setSelectionCoords(selOriginX, selOriginY, e.getRawX(), e.getRawY(), true);
                        } else {
                            // Normal drag: move both buttons together
                            selBtnLP.x = Math.max(0, Math.min(ix + (int) dx, SW - dp(50)));
                            selBtnLP.y = Math.max(0, Math.min(iy + (int) dy, SH - dp(50)));
                            safeUpdateLayout(selBtnView, selBtnLP);
                            btnLP.x = selBtnLP.x + dp(1);
                            btnLP.y = selBtnLP.y + dp(50);
                            safeUpdateLayout(btnView, btnLP);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    selBtnView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (selDragging && canvasView != null) {
                        // ── COMMIT SELECTION ────────────────────────────────────
                        stopDashAnimator();
                        float x1 = Math.min(selOriginX, e.getRawX()), y1 = Math.min(selOriginY, e.getRawY());
                        float x2 = Math.max(selOriginX, e.getRawX()), y2 = Math.max(selOriginY, e.getRawY());
                        float selW = x2 - x1, selH = y2 - y1;
                        if (selW > MIN_SELECTION_PX && selH > MIN_SELECTION_PX) {
                            // Final position update, then trigger green-flash + OCR
                            canvasView.setSelectionCoords(selOriginX, selOriginY, e.getRawX(), e.getRawY(), false);
                            canvasView.triggerConfirm();
                        } else {
                            // Too small — reset canvas, stay in selection mode
                            canvasView.clearSelection();
                            toast(isAr ? "حدد منطقة أكبر" : "Selection too small");
                            selBtnView.animate().alpha(1f).setDuration(200).start();
                        }
                        selDragging = false;
                    } else if (!dragged) {
                        // Simple tap: toggle selection mode
                        if (selectionMode) {
                            exitSelectionMode();
                        } else {
                            enterSelectionMode();
                        }
                    } else {
                        // Normal reposition drag ended
                        selBtnView.animate().alpha(ALPHA_IDLE).setDuration(400).start();
                    }
                    return true;
            }
            return false;
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // Selection Mode — Enter / Exit
    // ═══════════════════════════════════════════════════════════════

    private void enterSelectionMode() {
        if (selectionMode || destroyed) return;
        selectionMode = true;

        synchronized (mpLock) {
            if (mediaProjection == null) {
                selectionMode = false;
                toast(isAr
                    ? "أعد فتح التطبيق لاستعادة صلاحية الشاشة"
                    : "Reopen app to restore screen permission");
                return;
            }
        }

        // Pulse the ✏️ button to indicate active mode
        selBtnView.animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(150).start();

        // Hide the translation overlay if visible
        if (overlayVisible) hideOverlay();

        // Build and show draw-only selection overlay (game input unaffected)
        buildSelectionOverlay();
    }

    private void exitSelectionMode() {
        if (!selectionMode) return;
        selectionMode = false;
        stopDashAnimator();

        if (selBtnView != null)
            selBtnView.animate().alpha(ALPHA_IDLE).scaleX(1f).scaleY(1f).setDuration(200).start();

        // Animate overlay out then remove
        if (selectionView != null) {
            selectionView.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> {
                    safeRemove(selectionView);
                    selectionView = null;
                    canvasView    = null;
                })
                .start();
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // Selection Overlay — Full-screen dim + drag canvas
    // ═══════════════════════════════════════════════════════════════

    private void buildSelectionOverlay() {
        // Remove any previous instance safely
        if (selectionView != null) {
            safeRemove(selectionView);
            selectionView = null;
            canvasView    = null;
        }

        FrameLayout root = new FrameLayout(this);
        root.setAlpha(0f);

        // Draw-only canvas — does NOT handle touch (game keeps all input)
        canvasView = new SelectionCanvasView(this);
        root.addView(canvasView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        selectionView = root;

        // ─────────────────────────────────────────────────────────────
        // KEY: FLAG_NOT_TOUCHABLE → this window is a pure draw layer.
        // All touch events fall through to the game underneath.
        // Input is handled entirely by SelBtnTouch (drag proxy).
        // ─────────────────────────────────────────────────────────────
        selectionLP = new WindowManager.LayoutParams(
            SW, SH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        selectionLP.gravity = Gravity.TOP | Gravity.START;

        safeAddView(selectionView, selectionLP);

        // Fade in (draw layer only — game is unaffected)
        selectionView.animate().alpha(1f).setDuration(180).start();

        // Hint: user drags from ✏️ button to draw selection
        toast(isAr ? "اسحب من ✏️ لتحديد النص" : "Drag ✏️ to select text");
    }


    // ═══════════════════════════════════════════════════════════════
    // SelectionCanvasView — custom view for dim + rectangle drawing
    // ═══════════════════════════════════════════════════════════════

    private class SelectionCanvasView extends View {

        // Rect state — set externally by SelBtnTouch (no internal touch handling)
        private float sx, sy, ex, ey;
        private boolean dragging  = false;
        private boolean confirmed = false;

        // Dynamic dim: 0 when idle (game fully visible), ramps to DIM_ALPHA while dragging
        private int dimAlphaCurrent = 0;

        // Paints — all created once in constructor, NEVER inside onDraw()
        private final Paint dimPaint    = new Paint();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint confirmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tintPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guidePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF selRectF    = new RectF();

        // Marching ants — cache DashPathEffect to avoid per-frame allocation
        private float           dashOffset   = -1f;
        private DashPathEffect  cachedEffect = null;
        private final float[]   dashIntervals = {dp(10), dp(6)};

        // Confirm animation
        private float confirmAlpha = 0f;

        SelectionCanvasView(Context ctx) {
            super(ctx);
            setWillNotDraw(false);

            // dimPaint alpha set dynamically; base color is black
            dimPaint.setColor(Color.BLACK);
            dimPaint.setStyle(Paint.Style.FILL);
            dimPaint.setAlpha(0); // transparent until drag starts

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2));
            borderPaint.setColor(Color.argb(230, 80, 180, 255));

            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(dp(3));
            cornerPaint.setColor(Color.WHITE);
            cornerPaint.setStrokeCap(Paint.Cap.ROUND);

            confirmPaint.setStyle(Paint.Style.STROKE);
            confirmPaint.setStrokeWidth(dp(3));
            confirmPaint.setColor(Color.argb(255, 100, 220, 130));

            tintPaint.setColor(Color.argb(18, 80, 160, 255));
            tintPaint.setStyle(Paint.Style.FILL);

            labelPaint.setColor(Color.argb(180, 255, 255, 255));
            labelPaint.setTextSize(dp(10));
            labelPaint.setTypeface(Typeface.DEFAULT_BOLD);

            // Guide text shown when idle (no drag started yet)
            guidePaint.setColor(Color.argb(160, 180, 210, 255));
            guidePaint.setTextSize(dp(13));
            guidePaint.setTypeface(Typeface.DEFAULT_BOLD);
            guidePaint.setTextAlign(Paint.Align.CENTER);
            // Layer type set dynamically: SOFTWARE only while DashPathEffect is active,
            // NONE (hardware) otherwise — saves GPU memory when the canvas is idle.
            setLayerType(LAYER_TYPE_NONE, null);
        }

        // ── External coordinate API (called by SelBtnTouch) ──────────

        /**
         * Called every MOVE event from SelBtnTouch.
         * rawX/Y are screen-absolute coordinates — matches canvas position
         * since selectionView has gravity TOP|START at (0,0).
         */
        void setSelectionCoords(float startX, float startY,
                                float curX,   float curY,
                                boolean isDragging) {
            sx = startX; sy = startY;
            ex = curX;   ey = curY;
            dragging  = isDragging;
            confirmed = false;
            // Ramp dim in as drag grows
            float diagPx = (float) Math.hypot(Math.abs(ex - sx), Math.abs(ey - sy));
            float ramp    = Math.min(diagPx / dp(80), 1f);
            dimAlphaCurrent = (int)(ramp * 255 * DIM_ALPHA);
            dimPaint.setAlpha(dimAlphaCurrent);
            invalidate();
        }

        /** Trigger the green-flash confirm animation, then fires OCR. */
        void triggerConfirm() {
            confirmed = true;
            animateConfirm();
        }

        /** Reset canvas to idle/transparent state. */
        void clearSelection() {
            sx = sy = ex = ey = 0;
            dragging  = false;
            confirmed = false;
            dimAlphaCurrent = 0;
            dimPaint.setAlpha(0);
            if (cachedEffect != null) { cachedEffect = null; borderPaint.setPathEffect(null); }
            invalidate();
        }

        /**
         * Called by the marching-ants ValueAnimator (~60fps).
         * Rebuilds DashPathEffect only when offset actually changes.
         */
        void setDashOffset(float offset) {
            if (offset != dashOffset) {
                dashOffset   = offset;
                cachedEffect = new DashPathEffect(dashIntervals, offset);
                borderPaint.setPathEffect(cachedEffect);
            }
            invalidate();
        }

        // NOTE: onTouchEvent intentionally absent.
        // This view is FLAG_NOT_TOUCHABLE — the game receives all input.
        // Drag gestures are handled by SelBtnTouch and forwarded here.

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int cw = canvas.getWidth();
            int ch = canvas.getHeight();

            // Dim layer (alpha = 0 when idle; ramps up during drag)
            if (dimAlphaCurrent > 0) {
                canvas.drawRect(0, 0, cw, ch, dimPaint);
            }

            if (!dragging && !confirmed && sx == 0 && sy == 0) {
                // Idle state: show subtle guide text only (no rect, no blocking)
                String guide = isAr ? "اسحب ✏️ لتحديد النص" : "Drag ✏️ to select text";
                canvas.drawText(guide, cw / 2f, ch - dp(80), guidePaint);
                return;
            }

            if (sx == 0 && sy == 0 && ex == 0 && ey == 0) return;

            float left   = Math.min(sx, ex);
            float top    = Math.min(sy, ey);
            float right  = Math.max(sx, ex);
            float bottom = Math.max(sy, ey);

            // Blue tint inside selection
            canvas.drawRect(left, top, right, bottom, tintPaint);

            selRectF.set(left, top, right, bottom);

            if (confirmed) {
                confirmPaint.setAlpha((int)(255 * confirmAlpha));
                canvas.drawRoundRect(selRectF, dp(4), dp(4), confirmPaint);
            } else {
                canvas.drawRoundRect(selRectF, dp(4), dp(4), borderPaint);

                // Corner handles
                float c = dp(16);
                canvas.drawLine(left,  top,     left + c, top,         cornerPaint);
                canvas.drawLine(left,  top,     left,     top + c,     cornerPaint);
                canvas.drawLine(right, top,     right - c, top,        cornerPaint);
                canvas.drawLine(right, top,     right,     top + c,    cornerPaint);
                canvas.drawLine(left,  bottom,  left + c,  bottom,     cornerPaint);
                canvas.drawLine(left,  bottom,  left,      bottom - c, cornerPaint);
                canvas.drawLine(right, bottom,  right - c, bottom,     cornerPaint);
                canvas.drawLine(right, bottom,  right,     bottom - c, cornerPaint);

                // Size label
                float rw = right - left;
                float rh = bottom - top;
                if (rw > dp(80) && rh > dp(40)) {
                    String lbl = (int) rw + " × " + (int) rh;
                    float lw = labelPaint.measureText(lbl);
                    canvas.drawText(lbl, left + (rw - lw) / 2f, bottom - dp(8), labelPaint);
                }
            }
        }

        private void animateConfirm() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f, 0.6f);
            a.setDuration(350);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(anim -> {
                confirmAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    H.post(() -> runOCROnSelection(
                        Math.min(sx, ex), Math.min(sy, ey),
                        Math.max(sx, ex), Math.max(sy, ey)));
                }
            });
            a.start();
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // Marching-ants animator
    // ═══════════════════════════════════════════════════════════════

    private void startDashAnimator() {
        stopDashAnimator();
        // DashPathEffect requires software rendering — enable it only while animating
        if (canvasView != null) canvasView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        float total = dp(10) + dp(6); // dashOn + dashOff
        dashAnimator = ValueAnimator.ofFloat(0f, total);
        dashAnimator.setDuration(600);
        dashAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dashAnimator.setRepeatMode(ValueAnimator.RESTART);
        dashAnimator.addUpdateListener(a -> {
            if (canvasView != null)
                canvasView.setDashOffset((float) a.getAnimatedValue());
        });
        dashAnimator.start();
    }

    private void stopDashAnimator() {
        if (dashAnimator != null) {
            dashAnimator.cancel();
            dashAnimator = null;
        }
        // Return to hardware layer when idle — DashPathEffect no longer needed
        if (canvasView != null) canvasView.setLayerType(View.LAYER_TYPE_NONE, null);
    }


    // ═══════════════════════════════════════════════════════════════
    // OCR on selected region
    // ═══════════════════════════════════════════════════════════════

    /**
     * screenX/Y are in device-pixel coordinates (touch coordinates).
     * We map them to the capture bitmap coordinates proportionally.
     */
    private void runOCROnSelection(float screenLeft, float screenTop,
                                   float screenRight, float screenBottom) {
        wakeEngine();  // cancel pending sleep + ensure executor is alive

        if (!ocrBusy.compareAndSet(false, true)) {
            exitSelectionMode();
            return;
        }

        synchronized (mpLock) {
            if (mediaProjection == null) {
                ocrBusy.set(false);
                exitSelectionMode();
                toast(isAr
                    ? "أعد فتح التطبيق لاستعادة صلاحية الشاشة"
                    : "Reopen app to restore screen permission");
                return;
            }
        }

        if (recognizerJa == null || recognizerLat == null) {
            ocrBusy.set(false);
            exitSelectionMode();
            toast(isAr ? "OCR غير جاهز" : "OCR not ready");
            return;
        }

        // Show "reading" state
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

            Bitmap         fullBmp = null;

            try {
                // ── PERSISTENT CAPTURE ────────────────────────────────────────
                // Reuse VirtualDisplay + ImageReader across OCR calls.
                // First call: creates them. Subsequent calls: reuses. ~400ms faster.
                final boolean wasAlive;  // used for adaptive delay below
                synchronized (mpLock) {
                    if (mediaProjection == null) {
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) { exitSelectionMode(); resetBtn(shortPair()); } });
                        return;
                    }
                    wasAlive = (persistentReader != null && persistentVD != null);
                    if (!wasAlive) {
                        persistentReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
                        persistentVD = mediaProjection.createVirtualDisplay(
                            "UT_SEL", capW, capH, density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            persistentReader.getSurface(), null, null);
                    }
                }

                // Adaptive delay:
                //   First capture (VD just created): 550 ms to let the display sync.
                //   Subsequent captures (VD already mirroring): 120 ms is enough.
                Thread.sleep(wasAlive ? 120L : CAPTURE_DELAY_MS);
                if (destroyed) { ocrBusy.set(false); return; }

                Image img = null;
                for (int i = 0; i < 3 && img == null; i++) {
                    try { img = persistentReader.acquireLatestImage(); }
                    catch (Exception e) { Log.w(TAG, "acquireLatestImage #" + i + ": " + e.getMessage()); }
                    if (img == null && i < 2)
                        try { Thread.sleep(80); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }

                if (img != null) {
                    try {
                        Image.Plane[] planes = img.getPlanes();
                        ByteBuffer buf  = planes[0].getBuffer();
                        int rStride     = planes[0].getRowStride();
                        int pStride     = planes[0].getPixelStride();
                        int bmpW        = rStride / pStride;
                        int usedW       = Math.min(capW, bmpW);

                        Bitmap tmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
                        tmp.copyPixelsFromBuffer(buf);

                        if (bmpW > usedW) {
                            fullBmp = Bitmap.createBitmap(tmp, 0, 0, usedW, capH);
                            tmp.recycle();
                        } else {
                            fullBmp = tmp;
                        }
                    } catch (OutOfMemoryError oom) {
                        Log.e(TAG, "OOM during Bitmap copy");
                    } catch (Exception e) {
                        Log.e(TAG, "Bitmap copy: " + e.getMessage());
                    } finally {
                        try { img.close(); } catch (Exception ignored) {}
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { exitSelectionMode(); resetBtn(shortPair()); scheduleDismiss(); } });
                return;
            } catch (Exception e) {
                Log.e(TAG, "Capture: " + e.getMessage(), e);
                ocrBusy.set(false);
            }

            // Exit selection overlay now (capture is done)
            H.post(this::exitSelectionMode);

            if (fullBmp == null) {
                ocrBusy.set(false);
                H.post(() -> {
                    if (destroyed) return;
                    resetBtn(shortPair());
                    if (tvTranslation != null) {
                        tvTranslation.setText(isAr ? "فشل الالتقاط — حاول مجدداً" : "Capture failed — try again");
                        tvTranslation.setTextColor(Color.argb(220, 255, 180, 80));
                    }
                    scheduleDismiss();
                });
                return;
            }

            // ── Crop mapping fix ─────────────────────────────────────────
            // fullBmp.getWidth() may differ from capW when rowStride > capW * 4
            // (some devices/drivers pad rows for memory alignment).
            // Always derive scale from the ACTUAL bitmap dimensions, not the
            // requested capture size, to avoid off-by-N pixel errors.
            int bmpActualW = fullBmp.getWidth();
            int bmpActualH = fullBmp.getHeight();
            float scaleX = (float) bmpActualW / SW;
            float scaleY = (float) bmpActualH / SH;
            int cropL = clamp((int)(screenLeft   * scaleX), 0, bmpActualW - 1);
            int cropT = clamp((int)(screenTop    * scaleY), 0, bmpActualH - 1);
            int cropR = clamp((int)(screenRight  * scaleX), cropL + 1, bmpActualW);
            int cropB = clamp((int)(screenBottom * scaleY), cropT + 1, bmpActualH);
            int cropW = cropR - cropL;
            int cropH = cropB - cropT;

            Bitmap cropped = null;
            final Bitmap fFull = fullBmp;

            try {
                cropped = Bitmap.createBitmap(fFull, cropL, cropT, cropW, cropH);
            } catch (OutOfMemoryError | Exception oom) {
                Log.e(TAG, "Crop OOM/error: " + oom.getMessage());
                recycleSafe(fFull);
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                return;
            }

            boolean explicitJa = fromLang.equals("ja") || fromLang.equals("ko")
                || fromLang.startsWith("zh");
            boolean runBoth    = fromLang.equals("auto"); // auto → race both, pick best

            final Bitmap fCropped = cropped;

            if (runBoth) {
                // ── AUTO MODE: run both recognizers in parallel ──────────────────
                // The one that returns the longer (non-trivial) text wins.
                // This avoids the old bug where "auto" always defaulted to the
                // Japanese recognizer even for Latin/Arabic game text.
                final String[] results    = new String[2]; // [0]=Ja, [1]=Lat
                final int[]    doneCount  = {0};
                final Object   lock       = new Object();

                com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text> jaTask =
                    recognizerJa.process(InputImage.fromBitmap(fCropped, 0));
                com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text> latTask =
                    recognizerLat.process(InputImage.fromBitmap(fCropped, 0));

                android.os.Handler bothDone = new android.os.Handler(Looper.getMainLooper());
                Runnable onBothFinished = () -> {
                    recycleSafe(fCropped);
                    recycleSafe(fFull);
                    Runtime.getRuntime().gc();
                    ocrBusy.set(false);
                    H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                    // Pick the longer result — more characters = more likely correct
                    String jaText  = results[0] != null ? results[0] : "";
                    String latText = results[1] != null ? results[1] : "";
                    String best    = jaText.length() >= latText.length() ? jaText : latText;
                    if (!best.isEmpty()) {
                        doTranslate(best);
                    } else {
                        H.post(() -> { if (!destroyed) onNoTextFound(); });
                    }
                };

                jaTask.addOnCompleteListener(t -> {
                    synchronized (lock) {
                        results[0] = t.isSuccessful() ? t.getResult().getText().trim() : "";
                        if (++doneCount[0] == 2) bothDone.post(onBothFinished);
                    }
                });
                latTask.addOnCompleteListener(t -> {
                    synchronized (lock) {
                        results[1] = t.isSuccessful() ? t.getResult().getText().trim() : "";
                        if (++doneCount[0] == 2) bothDone.post(onBothFinished);
                    }
                });
            } else {
                // ── EXPLICIT LANGUAGE MODE: use single recognizer ────────────────
                TextRecognizer rec = explicitJa ? recognizerJa : recognizerLat;

                try {
                    rec.process(InputImage.fromBitmap(fCropped, 0))
                        .addOnSuccessListener(result -> {
                            recycleSafe(fCropped);
                            recycleSafe(fFull);
                            // Hint to GC after releasing large bitmaps — helps on low-RAM devices.
                            // ART ignores this on capable devices, costs nothing on good ones.
                            Runtime.getRuntime().gc();
                            ocrBusy.set(false);
                            H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                            String text = result.getText().trim();
                            if (!text.isEmpty()) {
                                doTranslate(text);
                            } else {
                                H.post(() -> { if (!destroyed) onNoTextFound(); });
                            }
                        })
                        .addOnFailureListener(e -> {
                            recycleSafe(fCropped);
                            recycleSafe(fFull);
                            Runtime.getRuntime().gc();
                            ocrBusy.set(false);
                            Log.e(TAG, "OCR fail: " + e.getMessage());
                            H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                        });
                } catch (Exception e) {
                    recycleSafe(fCropped);
                    recycleSafe(fFull);
                    Runtime.getRuntime().gc();
                    ocrBusy.set(false);
                    Log.e(TAG, "rec.process exception: " + e.getMessage());
                    H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                }
            }
        });
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void onNoTextFound() {
        resetBtn(shortPair());
        if (tvTranslation != null) {
            tvTranslation.setText(isAr ? "لا يوجد نص في هذه المنطقة" : "No text in selection");
            tvTranslation.setTextColor(Color.argb(200, 255, 210, 80));
        }
        showOverlay();
        scheduleDismiss();
        scheduleEngineSleep(); // nothing to do — sleep after showing message
    }


    // ═══════════════════════════════════════════════════════════════
    // Overlay (translation result card)
    // ═══════════════════════════════════════════════════════════════

    private void buildOverlay() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(12));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(Color.argb(218, 4, 9, 26));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.argb(90, 40, 90, 210));
        card.setBackground(cardBg);
        card.setElevation(dp(12));

        // Top row
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Small ✏️ icon to indicate it came from selection
        tvLangPair = new TextView(this);
        tvLangPair.setText(pairText());
        tvLangPair.setTextColor(Color.argb(160, 80, 135, 230));
        tvLangPair.setTextSize(9f);
        tvLangPair.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangPair.setLetterSpacing(0.06f);
        topRow.addView(tvLangPair, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView xBtn = new TextView(this);
        xBtn.setText("  \u00D7  ");
        xBtn.setTextColor(Color.argb(180, 210, 80, 80));
        xBtn.setTextSize(17f);
        xBtn.setClickable(true);
        xBtn.setFocusable(true);
        xBtn.setOnClickListener(v -> hideOverlay());
        topRow.addView(xBtn);
        card.addView(topRow);

        // Divider
        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(5), 0, dp(6));
        div.setBackgroundColor(Color.argb(55, 40, 85, 190));
        card.addView(div, divLp);

        // Original (small)
        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.argb(100, 130, 165, 220));
        tvOriginal.setTextSize(9.5f);
        tvOriginal.setMaxLines(2);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(4));
        card.addView(tvOriginal);

        // Translation (large)
        tvTranslation = new TextView(this);
        tvTranslation.setTextColor(Color.argb(245, 220, 236, 255));
        tvTranslation.setTextSize(18f);
        tvTranslation.setTypeface(Typeface.DEFAULT_BOLD);
        tvTranslation.setLineSpacing(dp(2), 1.15f);
        tvTranslation.setMaxLines(5);
        tvTranslation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvTranslation);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(10), 0, dp(10), dp(10));
        root.addView(card, cardLp);

        overlayView = root;
        overlayView.setAlpha(0f);

        // ── Overlay window ────────────────────────────────────────────
        // WRAP_CONTENT (not SW) so the card never stretches edge-to-edge.
        // Hard-cap at 82 % of screen width for readability on all screen sizes.
        // FLAG_ALT_FOCUSABLE_IM: keeps the soft keyboard from pushing this
        // window around and prevents FLAG conflicts when an IME is active.
        overlayLP = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT);
        overlayLP.width   = (int)(SW * 0.82f);   // max width cap
        overlayLP.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayLP.y = dp(10);

        safeAddView(overlayView, overlayLP);
    }

    private static final int OVERLAY_FLAGS_VISIBLE =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;

    private static final int OVERLAY_FLAGS_HIDDEN =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;

    private void showOverlay() {
        if (destroyed || overlayView == null) return;
        overlayHiding  = false;
        overlayVisible = true;
        overlayLP.flags = OVERLAY_FLAGS_VISIBLE;
        safeUpdateLayout(overlayView, overlayLP);
        overlayView.animate().cancel();
        overlayView.animate().alpha(1f).setDuration(200).start();
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
            overlayLP.flags = OVERLAY_FLAGS_HIDDEN;
            safeUpdateLayout(overlayView, overlayLP);
            // Overlay closed — schedule deep sleep (no translation should stay alive)
            scheduleEngineSleep();
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

    private void scheduleBtnFade() {
        if (fadeOutR != null) H.removeCallbacks(fadeOutR);
        fadeOutR = () -> {
            if (!ocrBusy.get() && !translating.get() && !overlayVisible
                && !selectionMode && btnView != null)
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


    // ═══════════════════════════════════════════════════════════════
    // Translation
    // ═══════════════════════════════════════════════════════════════

    private void doTranslate(final String text) {
        if (!translating.compareAndSet(false, true)) return;
        cancelEngineSleep();   // active translation — don't sleep yet
        cancelDismiss();

        String cacheKey = fromLang + "|" + toLang + "|" + text;
        String cached   = translateCache.get(cacheKey);
        if (cached != null) {
            translating.set(false);
            H.post(() -> { if (!destroyed) showResult(text, cached, ""); });
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

        // Truncate safely — substring(0,600) can split a surrogate pair mid-char.
        // offsetByCodePoints walks the string by Unicode code points, not char units.
        final String input;
        if (text.length() > 600) {
            int cpCount = text.codePointCount(0, text.length());
            int safeEnd = text.offsetByCodePoints(0, Math.min(600, cpCount));
            input = text.substring(0, safeEnd);
        } else {
            input = text;
        }
        final String snapFrom = fromLang;
        final String snapTo   = toLang;
        final String key      = cacheKey;

        safeSubmit(() -> {
            if (destroyed) { translating.set(false); return; }

            // ══════════════════════════════════════════════════════════
            // TranslationEngine — Smart Online / Offline Routing
            //
            // Priority:
            //   1. Online  (Google Translate, 4 s timeout — fast fail for games)
            //   2. Offline (ML Kit on-device, 0 ms extra latency if model loaded)
            //   3. Degrade (show original text with ⚠ — never a blank screen)
            //
            // Network state is maintained by a ConnectivityManager.NetworkCallback
            // (no polling, no background loops, no battery drain).
            // ══════════════════════════════════════════════════════════

            String result  = null;
            String modeTag = "";          // shown in UI: ☁ online, 📴 offline

            // ── 1. Online ────────────────────────────────────────────
            if (netAvailable) {
                try {
                    result  = googleTranslate(input, snapFrom, snapTo);
                    modeTag = "☁";
                } catch (Exception e) {
                    Log.w(TAG, "Online translate failed → offline: " + e.getMessage());
                }
            }

            // ── 2. Offline fallback ──────────────────────────────────
            if (result == null) {
                if (offlineReady.get() && offlineTranslator != null) {
                    try {
                        result  = offlineTranslateSync(input);
                        modeTag = "📴";
                    } catch (Exception e) {
                        Log.w(TAG, "Offline translate failed: " + e.getMessage());
                    }
                } else if (!offlineLoading.get()) {
                    // Model not loaded — trigger lazy download, result will come next tap
                    H.post(() -> {
                        if (!destroyed) prepareOfflineTranslator(snapFrom, snapTo);
                    });
                }
            }

            translating.set(false);
            final String fResult  = result;
            final String fMode    = modeTag;

            H.post(() -> {
                if (destroyed) return;
                if (fResult != null) {
                    translateCache.put(key, fResult);
                    showResult(text, fResult, fMode);
                } else {
                    // ── 3. Graceful degrade — show original, never blank ─
                    Log.w(TAG, "All translate engines failed — showing original");
                    resetBtn(shortPair());
                    if (tvOriginal    != null) tvOriginal.setText(clip(text, 80));
                    if (tvTranslation != null) {
                        tvTranslation.setText(text);
                        tvTranslation.setTextColor(Color.argb(180, 220, 220, 180));
                    }
                    if (tvLangPair != null) tvLangPair.setText("\u26A0 " + pairText());
                    showOverlay();
                    scheduleDismiss();
                    scheduleEngineSleep(); // failed — still go to sleep
                }
            });
        });
    }

    private void showResult(String original, String translated, String modeTag) {
        if (destroyed) return;
        if (tvOriginal    != null) tvOriginal.setText(clip(original, 80));
        if (tvTranslation != null) {
            tvTranslation.setText(translated);
            tvTranslation.setTextColor(Color.argb(245, 220, 238, 255));
        }
        String pair = pairText() + (modeTag.isEmpty() ? "" : "  " + modeTag);
        if (tvLangPair != null) tvLangPair.setText(pair);
        resetBtn(shortPair());
        showOverlay();
        scheduleDismiss();
        // Translation done — schedule deep sleep after idle timeout
        scheduleEngineSleep();
    }


    // ═══════════════════════════════════════════════════════════════
    // Button touch (main pill — double-tap → picker, single → legacy OCR)
    // ═══════════════════════════════════════════════════════════════

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
                        btnLP.x = Math.max(0, Math.min(ix + (int) dx, SW - dp(68)));
                        btnLP.y = Math.max(0, Math.min(iy + (int) dy, SH - dp(40)));
                        safeUpdateLayout(btnView, btnLP);
                        // Keep ✏️ button nearby
                        selBtnLP.x = btnLP.x + dp(1);
                        selBtnLP.y = btnLP.y - dp(50);
                        safeUpdateLayout(selBtnView, selBtnLP);
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
        if (selectionMode)         { exitSelectionMode(); return; }
        if (overlayVisible)        { hideOverlay(); return; }
        // Single tap on pill now hints to use ✏️
        toast(isAr ? "استخدم ✏️ لتحديد النص" : "Use ✏️ to select text");
        if (btnView != null) btnView.animate().alpha(ALPHA_IDLE).setDuration(400).start();
        scheduleBtnFade();
    }

    private void onDoubleTap() {
        if (overlayVisible) hideOverlay();
        openPicker();
    }


    // ═══════════════════════════════════════════════════════════════
    // Language Picker
    // ═══════════════════════════════════════════════════════════════

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
        bg.setColor(Color.argb(248, 4, 11, 30));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.argb(110, 50, 100, 230));
        root.setBackground(bg);
        root.setElevation(dp(16));

        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(0, 0, 0, dp(8));

        TextView tvT = new TextView(this);
        tvT.setText(title);
        tvT.setTextColor(Color.argb(220, 110, 165, 255));
        tvT.setTextSize(13f);
        tvT.setTypeface(Typeface.DEFAULT_BOLD);
        tr.addView(tvT, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

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

        LinkedHashSet<String> seenCodes = new LinkedHashSet<>();
        for (String[] lang : LANGS) {
            if (!seenCodes.add(lang[0])) continue;
            if (!inclAuto && lang[0].equals("auto")) continue;
            if (!inclAuto && lang[0].equals(pickerFrom)) continue;

            TextView row = new TextView(this);
            row.setText(lang[2] + "  " + lang[1]);
            row.setTextColor(Color.argb(200, 155, 192, 245));
            row.setTextSize(13.5f);
            row.setPadding(dp(4), dp(14), dp(4), dp(14));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> cb.pick(lang[0]));
            row.setOnTouchListener((v, e) -> {
                int a = e.getAction();
                if      (a == MotionEvent.ACTION_DOWN)  row.setTextColor(Color.argb(255, 80, 165, 255));
                else if (a == MotionEvent.ACTION_UP ||
                         a == MotionEvent.ACTION_CANCEL) row.setTextColor(Color.argb(200, 155, 192, 245));
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
        if (pickerView != null) { safeRemove(pickerView); pickerView = null; }
    }


    // ═══════════════════════════════════════════════════════════════
    // Google Translate (unofficial API)
    //
    // ⚠️  WARNING — This endpoint is NOT an official Google API.
    //     It may be rate-limited, blocked, or removed without notice.
    //     Suitable for development / personal use only.
    //
    //     Migration options (in order of preference):
    //       1. Google Cloud Translation API (free tier: 500K chars/month)
    //          → https://cloud.google.com/translate
    //       2. LibreTranslate (self-hosted, fully offline)
    //          → https://libretranslate.com
    //       3. MyMemory free tier (1000 words/day, no key needed)
    //          → https://mymemory.translated.net/doc/spec.php
    //     Replace googleTranslate() with your chosen backend; the rest
    //     of the service (cache, threading, UI) stays identical.
    // ═══════════════════════════════════════════════════════════════

    private String googleTranslate(String text, String from, String to) throws Exception {
        String q      = URLEncoder.encode(text, "UTF-8");
        String urlStr = "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx&sl=" + (from.equals("auto") ? "auto" : from)
            + "&tl=" + to + "&dt=t&q=" + q;

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            // 4 s timeout — fast enough for game text, fast enough to fallback offline
            c.setConnectTimeout(4_000);
            c.setReadTimeout(4_000);
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


    // ═══════════════════════════════════════════════════════════════
    // TranslationEngine — Network Monitor
    //
    // Uses ConnectivityManager.NetworkCallback for zero-cost, event-driven
    // network state tracking. No polling, no WakeLocks, no timers.
    // ═══════════════════════════════════════════════════════════════

    private void startNetworkMonitor() {
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        // Snapshot current state so first translation doesn't wait for a callback
        netAvailable = isNetConnected();

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) {
                if (!netAvailable) Log.d(TAG, "Network: online");
                netAvailable = true;
            }
            @Override public void onLost(Network net) {
                // Only mark offline if truly no network remains
                if (!isNetConnected()) {
                    Log.d(TAG, "Network: offline");
                    netAvailable = false;
                }
            }
        };

        NetworkRequest req = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        try {
            cm.registerNetworkCallback(req, netCallback);
        } catch (Exception e) {
            Log.w(TAG, "NetworkCallback register failed: " + e.getMessage());
        }
    }

    private void stopNetworkMonitor() {
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); }
            catch (Exception ignored) {}
            netCallback = null;
        }
    }

    /** Checks active network capabilities synchronously — only for initialization. */
    private boolean isNetConnected() {
        if (cm == null) return true; // assume online if CM unavailable
        try {
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // TranslationEngine — Offline Engine (ML Kit On-Device)
    //
    // Architecture:
    //  • Model is ~30 MB per language pair, downloaded once, cached by ML Kit
    //  • prepareOfflineTranslator() is idempotent — safe to call on lang change
    //  • offlineTranslateSync() bridges the async Task to a blocking call
    //    using CountDownLatch — safe on the executor thread (never the main thread)
    //  • RAM: ML Kit keeps the model in its own heap; we hold only a thin
    //    Translator handle (~2 KB). Single model active at a time.
    //
    // build.gradle dependency needed:
    //   implementation 'com.google.mlkit:translate:17.0.2'
    // ═══════════════════════════════════════════════════════════════

    /**
     * Loads (or reuses) the ML Kit on-device translator for the given pair.
     * Called from the main thread; all heavy work happens on ML Kit's own threads.
     * Safe to call multiple times — no-op if the same pair is already loading/ready.
     */
    private void prepareOfflineTranslator(String from, String to) {
        String mlFrom = toMlKitLang(from.equals("auto") ? "en" : from);
        String mlTo   = toMlKitLang(to);
        if (mlFrom == null || mlTo == null) {
            Log.d(TAG, "Offline: unsupported lang pair " + from + "→" + to);
            return;
        }

        String pairKey = mlFrom + "|" + mlTo;
        if (pairKey.equals(offlinePairKey) && (offlineReady.get() || offlineLoading.get())) return;

        offlineReady.set(false);
        offlineLoading.set(true);
        offlinePairKey = pairKey;

        // Close previous translator to free its model from ML Kit's cache
        closeOfflineTranslator();

        TranslatorOptions opts = new TranslatorOptions.Builder()
            .setSourceLanguage(mlFrom)
            .setTargetLanguage(mlTo)
            .build();
        Translator t = Translation.getClient(opts);
        offlineTranslator = t;

        // Download without WiFi requirement — model is small (~30 MB) and
        // cached permanently. Use DownloadConditions.Builder().requireWifi()
        // if you want to restrict downloads to WiFi only.
        t.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(v -> {
                if (t == offlineTranslator) { // guard against stale callback
                    offlineReady.set(true);
                    Log.d(TAG, "Offline model ready: " + mlFrom + "→" + mlTo);
                }
                offlineLoading.set(false);
            })
            .addOnFailureListener(e -> {
                offlineLoading.set(false);
                Log.w(TAG, "Offline model download failed: " + e.getMessage());
            });
    }

    /**
     * Synchronous offline translation — MUST be called from a background thread.
     * Bridges ML Kit's async Task using CountDownLatch with a 3 s safety timeout.
     */
    private String offlineTranslateSync(String text) throws Exception {
        Translator t = offlineTranslator;
        if (t == null || !offlineReady.get()) throw new Exception("Offline not ready");

        CountDownLatch latch     = new CountDownLatch(1);
        String[]       out       = {null};
        Exception[]    err       = {null};

        t.translate(text)
            .addOnSuccessListener(r -> { out[0] = r; latch.countDown(); })
            .addOnFailureListener(e -> { err[0] = e; latch.countDown(); });

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        if (!completed)            throw new Exception("Offline translate timeout");
        if (err[0] != null)        throw err[0];
        if (out[0] == null || out[0].isEmpty()) throw new Exception("Offline empty result");
        return out[0];
    }

    private void closeOfflineTranslator() {
        Translator old = offlineTranslator;
        offlineTranslator = null;
        offlineReady.set(false);
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Maps app language codes to ML Kit TranslateLanguage constants.
     * Returns null for unsupported languages (engine will skip offline for those).
     *
     * ML Kit supports ~59 languages. Full list:
     * https://developers.google.com/ml-kit/language/translation/translation-language-support
     */
    private String toMlKitLang(String code) {
        if (code == null) return null;
        switch (code.toLowerCase(Locale.US)) {
            case "af": return TranslateLanguage.AFRIKAANS;
            case "ar": return TranslateLanguage.ARABIC;
            case "be": return TranslateLanguage.BELARUSIAN;
            case "bg": return TranslateLanguage.BULGARIAN;
            case "bn": return TranslateLanguage.BENGALI;
            case "ca": return TranslateLanguage.CATALAN;
            case "cs": return TranslateLanguage.CZECH;
            case "cy": return TranslateLanguage.WELSH;
            case "da": return TranslateLanguage.DANISH;
            case "de": return TranslateLanguage.GERMAN;
            case "el": return TranslateLanguage.GREEK;
            case "en": return TranslateLanguage.ENGLISH;
            case "eo": return TranslateLanguage.ESPERANTO;
            case "es": return TranslateLanguage.SPANISH;
            case "et": return TranslateLanguage.ESTONIAN;
            case "fa": return TranslateLanguage.PERSIAN;
            case "fi": return TranslateLanguage.FINNISH;
            case "fr": return TranslateLanguage.FRENCH;
            case "ga": return TranslateLanguage.IRISH;
            case "gl": return TranslateLanguage.GALICIAN;
            case "gu": return TranslateLanguage.GUJARATI;
            case "he": return TranslateLanguage.HEBREW;
            case "hi": return TranslateLanguage.HINDI;
            case "hr": return TranslateLanguage.CROATIAN;
            case "ht": return TranslateLanguage.HAITIAN_CREOLE;
            case "hu": return TranslateLanguage.HUNGARIAN;
            case "id": return TranslateLanguage.INDONESIAN;
            case "is": return TranslateLanguage.ICELANDIC;
            case "it": return TranslateLanguage.ITALIAN;
            case "ja": return TranslateLanguage.JAPANESE;
            case "ka": return TranslateLanguage.GEORGIAN;
            case "kn": return TranslateLanguage.KANNADA;
            case "ko": return TranslateLanguage.KOREAN;
            case "lt": return TranslateLanguage.LITHUANIAN;
            case "lv": return TranslateLanguage.LATVIAN;
            case "mk": return TranslateLanguage.MACEDONIAN;
            case "mr": return TranslateLanguage.MARATHI;
            case "ms": return TranslateLanguage.MALAY;
            case "mt": return TranslateLanguage.MALTESE;
            case "nl": return TranslateLanguage.DUTCH;
            case "no": return TranslateLanguage.NORWEGIAN;
            case "pl": return TranslateLanguage.POLISH;
            case "pt": return TranslateLanguage.PORTUGUESE;
            case "ro": return TranslateLanguage.ROMANIAN;
            case "ru": return TranslateLanguage.RUSSIAN;
            case "sk": return TranslateLanguage.SLOVAK;
            case "sl": return TranslateLanguage.SLOVENIAN;
            case "sq": return TranslateLanguage.ALBANIAN;
            case "sv": return TranslateLanguage.SWEDISH;
            case "sw": return TranslateLanguage.SWAHILI;
            case "ta": return TranslateLanguage.TAMIL;
            case "te": return TranslateLanguage.TELUGU;
            case "th": return TranslateLanguage.THAI;
            case "tl": return TranslateLanguage.TAGALOG;
            case "tr": return TranslateLanguage.TURKISH;
            case "uk": return TranslateLanguage.UKRAINIAN;
            case "ur": return TranslateLanguage.URDU;
            case "vi": return TranslateLanguage.VIETNAMESE;
            case "zh":
            case "zh-cn":
            case "zh-tw":
            case "zh-hans":
            case "zh-hant": return TranslateLanguage.CHINESE;
            default:        return null; // unsupported — will skip offline mode
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

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
        H.post(() -> { if (!destroyed) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); });
    }

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
        try { wm.removeView(v); }
        catch (Exception e) { Log.e(TAG, "removeView: " + e.getMessage()); }
    }

    private void recycleSafe(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled())
            try { bmp.recycle(); } catch (Exception ignored) {}
    }


    // ═══════════════════════════════════════════════════════════════
    // Notification
    // ═══════════════════════════════════════════════════════════════

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
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), piFlags);
        boolean ar = Locale.getDefault().getLanguage().equals("ar");
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(ar ? "مترجم الألعاب" : "Game Translator")
            .setContentText(ar
                ? "✏️ للتحديد اليدوي  |  ضغطتين/ضغطة طويلة = اللغة"
                : "✏️ to select  |  Double-tap/Hold = language")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
