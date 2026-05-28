package com.gametranslator;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FloatingTranslatorService — v22 (Game OCR + Failover Engine)
 *
 * build.gradle dependencies:
 *   implementation 'com.google.mlkit:text-recognition:16.0.1'
 *   implementation 'com.google.mlkit:text-recognition-japanese:16.0.1'
 *
 * Changes from v21:
 *   • Full-resolution capture (SW×SH) — fixes blurry OCR in fullscreen games
 *   • Correct crop mapping: bitmap coords = screen coords (no half-res mismatch)
 *   • OcrEngineManager — sleep/wake failover queue (Latin → Japanese → preprocessed)
 *   • TranslationEngineManager — Google → fallback degradation with cooldown
 *   • Image preprocessing pipeline: grayscale → contrast → sharpen → threshold
 *   • Debug mode: saves cropped bitmap to getExternalFilesDir() for inspection
 *   • "Selection invalid" shown when crop is too small instead of "No text found"
 *   • Detailed logs: crop coords, bitmap size, OCR result, engine events
 * Window layers (TYPE_APPLICATION_OVERLAY):
 *   1. btnView        — language pill  (drag / double-tap = picker)
 *   2. selBtnView     — ✏️ pen button  (tap = toggle, drag = draw stroke over text)
 *   3. strokeOverlay  — FLAG_NOT_TOUCHABLE draw canvas  (no dim, no rectangle)
 *   4. bubbleView     — compact translation bubble      (positioned at stroke bounds)
 *
 * Selection model:
 *   User drags the ✏️ button across the text they want translated.
 *   A glowing white stroke is drawn under the drag path, like a marker pen.
 *   On release the bounding box of the stroke is used as the OCR crop region.
 *   A small translation bubble appears directly above (or below) the drawn area.
 *   The game stays fully visible at all times — zero dim, zero blocking overlay.
 *
 * MediaProjection stability:
 *   The service never shows "reopen app" proactively.
 *   If the projection is lost Android-side the service silently cleans up and waits.
 *   The message is shown only when the user actively tries to start a capture.
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "GT";
    private static final String CHANNEL_ID = "gt_v21";
    private static final int    NOTIF_ID   = 1;

    private static final String PREFS         = "gt_prefs";
    private static final String KEY_LANG_FROM = "lang_from";
    private static final String KEY_LANG_TO   = "lang_to";

    // Timing
    private static final long DISMISS_MS       = 7_000;
    private static final long LONG_PRESS_MS    = 600;
    private static final long DOUBLE_TAP_MS    = 280;
    private static final long CAPTURE_DELAY_MS = 550;
    private static final long IDLE_SLEEP_MS    = 5_000;

    // UI alpha
    private static final float ALPHA_IDLE = 0.22f;
    private static final float ALPHA_BUSY = 0.95f;

    // Minimum stroke bounding box to trigger OCR
    private static final int MIN_STROKE_PX = 30;

    // Debug — set true to save cropped bitmap to external files dir for inspection
    private static final boolean DEBUG_CROP = true;

    // Minimum crop size in pixels to attempt OCR (avoids "no text" on fat-finger taps)
    private static final int MIN_CROP_W = 20;
    private static final int MIN_CROP_H = 10;

    private static final int CACHE_SIZE = 60;

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


    // ════════════════════════════════════════════════════════════════
    // Fields
    // ════════════════════════════════════════════════════════════════

    private WindowManager            wm;
    private int                      SW, SH;
    private int                      capW, capH;

    // ── VD health tracking ────────────────────────────────────
    private static final int  VD_NULL_FRAME_LIMIT = 3;
    private static final long WATCHDOG_MS         = 8_000;
    private int               vdNullFrameCount    = 0;
    private volatile long     lastSuccessfulFrame = 0;
    private Runnable          watchdogR;
    private final Handler            H = new Handler(Looper.getMainLooper());
    private ExecutorService          executor;
    private LruCache<String, String> translateCache;
    private SharedPreferences        prefs;
    private boolean                  isAr;

    // Views
    private View     btnView;
    private View     selBtnView;
    private View     bubbleView;
    private View     strokeOverlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, selBtnLP, bubbleLP, strokeLP;
    private TextView tvBtnLabel;
    private TextView tvBubbleText;
    private TextView tvBubbleLang;

    // State
    private final AtomicBoolean ocrBusy     = new AtomicBoolean(false);
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private volatile boolean    bubbleVisible = false;
    private volatile boolean    destroyed     = false;
    private volatile boolean    viewsAdded    = false;
    private volatile boolean    selectionMode = false;

    private volatile String fromLang   = "auto";
    private volatile String toLang     = "ar";
    private String          pickerFrom = "auto";

    // MediaProjection — kept alive across captures, recreated only on true loss
    private final Object           mpLock = new Object();
    private MediaProjection        mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer         recognizerJa;
    private TextRecognizer         recognizerLat;
    private VirtualDisplay         persistentVD;
    private ImageReader            persistentReader;

    // Network
    private volatile boolean                    netAvailable = true;
    private ConnectivityManager.NetworkCallback netCallback;
    private ConnectivityManager                 cm;

    // Timers
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable fadeOutR;
    private Runnable sleepR;
    private int      tapCount = 0;

    // Stroke selection
    private StrokeCanvasView strokeView;
    private float            lastStrokeLeft, lastStrokeTop, lastStrokeRight, lastStrokeBottom;


    // ════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        isAr = Locale.getDefault().getLanguage().equals("ar");
        createChannel();
        startForeground(NOTIF_ID, buildNotif());

        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        wm        = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        fromLang  = prefs.getString(KEY_LANG_FROM, "auto");
        toLang    = prefs.getString(KEY_LANG_TO, "ar");

        resolveScreenSize();
        // Full-resolution capture — avoids blurry OCR in fullscreen games.
        // Align to 16px boundary (GPU requirement), keep at native res.
        capW = Math.max((SW / 16) * 16, 720);
        capH = Math.max((SH / 16) * 16, 1280);
        Log.d(TAG, "Screen=" + SW + "x" + SH + "  capW=" + capW + "  capH=" + capH);

        initOCR();
        rebuildExecutorIfNeeded();
        translateCache = new LruCache<>(CACHE_SIZE);
        startNetworkMonitor();

        try {
            buildButton();
            buildSelectionButton();
            buildBubble();
            viewsAdded = true;
            H.postDelayed(() -> {
                if (!destroyed)
                    toast(isAr ? "اضغط ✏️ ثم اسحب فوق النص" : "Tap ✏️ then draw over text");
            }, 900);
        } catch (Exception e) {
            Log.e(TAG, "UI build failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        rebuildExecutorIfNeeded();

        if (intent == null) {
            // Silent START_STICKY restart — no toast.
            // If the projection was lost the user will be notified only when they try to use OCR.
            Log.w(TAG, "START_STICKY restart — waiting for user action");
            return START_STICKY;
        }

        String lf = intent.getStringExtra("lang_from");
        String lt = intent.getStringExtra("lang_to");
        if (lf != null && !lf.isEmpty()) { fromLang = lf; prefs.edit().putString(KEY_LANG_FROM, lf).apply(); }
        if (lt != null && !lt.isEmpty()) { toLang   = lt; prefs.edit().putString(KEY_LANG_TO,   lt).apply(); }
        H.post(() -> { if (!destroyed && tvBtnLabel != null) tvBtnLabel.setText(shortPair()); });

        if (intent.hasExtra("mp_result_code")) {
            int    rc   = intent.getIntExtra("mp_result_code", -1);
            Intent data = intent.getParcelableExtra("mp_data");
            if (rc == android.app.Activity.RESULT_OK && data != null) {
                synchronized (mpLock) {
                    if (mediaProjection != null) return START_STICKY; // already alive — do nothing
                    try {
                        mediaProjection = mpManager.getMediaProjection(rc, data);
                        mediaProjection.registerCallback(new MediaProjection.Callback() {
                            @Override public void onStop() {
                                Log.w(TAG, "MediaProjection stopped externally");
                                synchronized (mpLock) {
                                    // Release capture resources bound to this projection.
                                    // Do NOT show a toast here — the user is notified on next OCR attempt.
                                    releasePersistentCapture();
                                    mediaProjection = null;
                                }
                                ocrBusy.set(false);
                                translating.set(false);
                                H.post(() -> {
                                    if (!destroyed) {
                                        exitSelectionMode();
                                        resetBtn(shortPair());
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
            } else {
                H.post(() -> { if (!destroyed) toast(isAr ? "رُفضت صلاحية الشاشة" : "Screen permission denied"); });
            }
        }
        requestBatteryOptimizationExemptionOnce();
        startProjectionWatchdog();
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        destroyed = true;
        super.onDestroy();

        H.removeCallbacksAndMessages(null);
        stopProjectionWatchdog();
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
        closeOCR();
        stopNetworkMonitor();

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
            safeRemove(bubbleView);
            safeRemove(strokeOverlayView);
            safeRemove(pickerView);
        }
        btnView = selBtnView = bubbleView = strokeOverlayView = pickerView = null;
        tvBtnLabel = tvBubbleText = tvBubbleLang = null;
        strokeView = null;
    }


    // ════════════════════════════════════════════════════════════════
    // Executor
    // ════════════════════════════════════════════════════════════════

    private void rebuildExecutorIfNeeded() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "GT-Worker");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
    }

    private void safeSubmit(Runnable task) {
        rebuildExecutorIfNeeded();
        try { executor.submit(task); }
        catch (RejectedExecutionException e) { Log.e(TAG, "safeSubmit rejected"); }
    }


    // ════════════════════════════════════════════════════════════════
    // Sleep / Wake — zero idle resource usage
    // Sleeps: VirtualDisplay, ImageReader, executor thread.
    // Stays alive: mediaProjection token, OCR handles, network callback, cache.
    // ════════════════════════════════════════════════════════════════

    private void scheduleEngineSleep() {
        H.removeCallbacks(sleepR != null ? sleepR : () -> {});
        sleepR = this::enterEngineSleep;
        H.postDelayed(sleepR, IDLE_SLEEP_MS);
    }

    private void cancelEngineSleep() {
        if (sleepR != null) { H.removeCallbacks(sleepR); sleepR = null; }
    }

    private void enterEngineSleep() {
        if (destroyed || ocrBusy.get() || translating.get()) { scheduleEngineSleep(); return; }
        synchronized (mpLock) { releasePersistentCapture(); }
        if (executor != null && !executor.isShutdown()) { executor.shutdown(); executor = null; }
        sleepR = null;
        Log.d(TAG, "Engine sleeping");
    }

    private void wakeEngine() {
        cancelEngineSleep();
        rebuildExecutorIfNeeded();
    }


    // ════════════════════════════════════════════════════════════════
    // OCR
    // ════════════════════════════════════════════════════════════════

    private void initOCR() {
        try {
            // Legacy recognizers kept for race-mode (auto lang detection).
            // Primary OCR now goes through OcrEngineManager with failover.
            recognizerJa  = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Exception e) {
            Log.e(TAG, "OCR init legacy: " + e.getMessage());
            recognizerJa = recognizerLat = null;
        }
        initOcrEngineManager();
        initTranslationEngineManager();
    }

    private void closeOCR() {
        try { if (recognizerJa  != null) recognizerJa.close();  } catch (Exception ignored) {}
        try { if (recognizerLat != null) recognizerLat.close(); } catch (Exception ignored) {}
        recognizerJa = recognizerLat = null;
        closeOcrEngineManager();
        // translationEngineManager has no resources to close
        translationEngineManager = null;
    }

    /** Must be called while holding mpLock. */
    private void releasePersistentCapture() {
        if (persistentVD != null) {
            try { persistentVD.release(); } catch (Exception ignored) {}
            persistentVD = null;
        }
        if (persistentReader != null) {
            try { persistentReader.close(); } catch (Exception ignored) {}
            persistentReader = null;
        }
        vdNullFrameCount = 0;
    }

    private boolean isProjectionAlive() {
        if (mediaProjection == null) return false;
        if (persistentVD != null && lastSuccessfulFrame > 0) {
            long age = System.currentTimeMillis() - lastSuccessfulFrame;
            if (age > WATCHDOG_MS) {
                Log.w(TAG, "VD watchdog: stale " + age + "ms → recreate");
                releasePersistentCapture();
            }
        }
        return true;
    }

    private void recreateVirtualDisplay(int density) {
        releasePersistentCapture();
        if (mediaProjection == null) return;
        try {
            persistentReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            persistentVD = mediaProjection.createVirtualDisplay(
                "GT_CAP", capW, capH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                persistentReader.getSurface(), null, null);
            Log.d(TAG, "VD recreated " + capW + "x" + capH);
        } catch (Exception e) {
            Log.e(TAG, "VD recreate failed: " + e.getMessage());
            releasePersistentCapture();
        }
    }

    private static final String KEY_BATTERY_ASKED = "battery_asked";
    private void requestBatteryOptimizationExemptionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return;
        android.os.PowerManager pm =
            (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply();
        H.postDelayed(() -> {
            if (destroyed) return;
            try {
                Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) { Log.w(TAG, "battery exemption: " + e.getMessage()); }
        }, 3_000);
    }

    private void startProjectionWatchdog() {
        stopProjectionWatchdog();
        watchdogR = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                synchronized (mpLock) {
                    if (persistentVD != null && lastSuccessfulFrame > 0) {
                        long age = System.currentTimeMillis() - lastSuccessfulFrame;
                        if (age > WATCHDOG_MS) {
                            Log.w(TAG, "Watchdog: VD stale " + age + "ms");
                            releasePersistentCapture();
                        }
                    }
                }
                H.postDelayed(this, WATCHDOG_MS);
            }
        };
        H.postDelayed(watchdogR, WATCHDOG_MS);
    }

    private void stopProjectionWatchdog() {
        if (watchdogR != null) { H.removeCallbacks(watchdogR); watchdogR = null; }
    }


    // ════════════════════════════════════════════════════════════════
    // Screen size
    // ════════════════════════════════════════════════════════════════

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


    // ════════════════════════════════════════════════════════════════
    // Floating language pill
    // ════════════════════════════════════════════════════════════════

    private void buildButton() {
        FrameLayout root = new FrameLayout(this);
        FrameLayout pill = new FrameLayout(this);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.argb(210, 8, 16, 42));
        bg.setStroke(dp(1), Color.argb(130, 50, 110, 230));
        pill.setBackground(bg);
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
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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


    // ════════════════════════════════════════════════════════════════
    // ✏️ Pen button
    // ════════════════════════════════════════════════════════════════

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
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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
        selBtnLP.x = SW - dp(63);
        selBtnLP.y = SH / 2 - dp(50);

        selBtnView.setOnTouchListener(new SelBtnTouch());
        safeAddView(selBtnView, selBtnLP);
    }


    // ════════════════════════════════════════════════════════════════
    // ✏️ Touch — tap = toggle selection mode, drag = draw stroke
    // ════════════════════════════════════════════════════════════════

    private class SelBtnTouch implements View.OnTouchListener {
        private float   rx, ry;
        private int     ix, iy;
        private boolean dragged;
        private boolean strokeDragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = selBtnLP.x; iy = selBtnLP.y;
                    rx = e.getRawX(); ry = e.getRawY();
                    dragged = false; strokeDragging = false;
                    selBtnView.animate().alpha(1f).scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        dragged = true;
                        if (selectionMode && strokeView != null) {
                            // Draw stroke mode — ✏️ button is the pen tip
                            if (!strokeDragging) {
                                strokeDragging = true;
                                strokeView.startStroke(e.getRawX(), e.getRawY());
                            } else {
                                strokeView.addPoint(e.getRawX(), e.getRawY());
                            }
                        } else {
                            // Reposition both buttons together
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
                    if (strokeDragging && strokeView != null) {
                        strokeView.addPoint(e.getRawX(), e.getRawY());
                        RectF box = strokeView.getBoundingBox();
                        if (box.width() > MIN_STROKE_PX && box.height() > MIN_STROKE_PX) {
                            // Clamp to screen bounds
                            lastStrokeLeft   = Math.max(0, box.left);
                            lastStrokeTop    = Math.max(0, box.top);
                            lastStrokeRight  = Math.min(SW, box.right);
                            lastStrokeBottom = Math.min(SH, box.bottom);
                            strokeView.triggerConfirm();
                        } else {
                            strokeView.clearStroke();
                            toast(isAr ? "اسحب فوق النص" : "Draw over text");
                            selBtnView.animate().alpha(1f).setDuration(200).start();
                        }
                        strokeDragging = false;
                    } else if (!dragged) {
                        if (selectionMode) exitSelectionMode();
                        else               enterSelectionMode();
                    } else {
                        selBtnView.animate().alpha(ALPHA_IDLE).setDuration(400).start();
                    }
                    return true;
            }
            return false;
        }
    }


    // ════════════════════════════════════════════════════════════════
    // Selection mode
    // ════════════════════════════════════════════════════════════════

    private void enterSelectionMode() {
        if (selectionMode || destroyed) return;
        synchronized (mpLock) {
            if (mediaProjection == null) {
                toast(isAr
                    ? "افتح التطبيق لمنح صلاحية الشاشة"
                    : "Open the app to grant screen permission");
                return;
            }
        }
        selectionMode = true;
        if (bubbleVisible) hideBubble();
        selBtnView.animate().alpha(1f).scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
        buildStrokeOverlay();
    }

    private void exitSelectionMode() {
        if (!selectionMode) return;
        selectionMode = false;
        if (selBtnView != null)
            selBtnView.animate().alpha(ALPHA_IDLE).scaleX(1f).scaleY(1f).setDuration(200).start();
        if (strokeOverlayView != null) {
            strokeOverlayView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                safeRemove(strokeOverlayView);
                strokeOverlayView = null;
                strokeView = null;
            }).start();
        }
    }


    // ════════════════════════════════════════════════════════════════
    // Stroke overlay — full-screen, FLAG_NOT_TOUCHABLE, no dim at all
    // ════════════════════════════════════════════════════════════════

    private void buildStrokeOverlay() {
        if (strokeOverlayView != null) {
            safeRemove(strokeOverlayView);
            strokeOverlayView = null;
            strokeView = null;
        }

        strokeView = new StrokeCanvasView(this);

        FrameLayout root = new FrameLayout(this);
        root.setAlpha(0f);
        root.addView(strokeView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        strokeOverlayView = root;

        strokeLP = new WindowManager.LayoutParams(
            SW, SH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // game keeps all input
            PixelFormat.TRANSLUCENT);
        strokeLP.gravity = Gravity.TOP | Gravity.START;

        safeAddView(strokeOverlayView, strokeLP);
        strokeOverlayView.animate().alpha(1f).setDuration(160).start();
    }


    // ════════════════════════════════════════════════════════════════
    // StrokeCanvasView — freeform pen stroke (no rectangle, no dim)
    //
    // User drags the ✏️ button across the text they want to translate.
    // The path drawn by the button acts as the "selection stroke".
    // A soft white glow is drawn under the stroke like a highlighter pen.
    // On confirm a green pulse flashes over the bounding box, then OCR fires.
    // ════════════════════════════════════════════════════════════════

    private class StrokeCanvasView extends View {

        private final Path strokePath  = new Path();
        private boolean    hasStroke   = false;
        private boolean    confirmed   = false;
        private float      confirmAlpha = 0f;

        private float minX = Float.MAX_VALUE,  minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        private final RectF  boundingRect = new RectF();
        private final Paint  strokePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  glowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint  confirmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StrokeCanvasView(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            // Software layer required for BlurMaskFilter (glow)
            setLayerType(LAYER_TYPE_SOFTWARE, null);

            // Solid white marker stroke
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(5));
            strokePaint.setColor(Color.argb(225, 255, 255, 255));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            // Soft blue glow behind the stroke
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(dp(16));
            glowPaint.setColor(Color.argb(60, 180, 215, 255));
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(10), BlurMaskFilter.Blur.NORMAL));

            // Green bounding-box flash on confirm
            confirmPaint.setStyle(Paint.Style.STROKE);
            confirmPaint.setStrokeWidth(dp(2));
            confirmPaint.setColor(Color.argb(255, 80, 220, 120));
        }

        void startStroke(float x, float y) {
            strokePath.reset();
            strokePath.moveTo(x, y);
            hasStroke  = true;
            confirmed  = false;
            confirmAlpha = 0f;
            minX = maxX = x;
            minY = maxY = y;
            invalidate();
        }

        void addPoint(float x, float y) {
            if (!hasStroke) { startStroke(x, y); return; }
            strokePath.lineTo(x, y);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            invalidate();
        }

        void clearStroke() {
            strokePath.reset();
            hasStroke    = false;
            confirmed    = false;
            confirmAlpha = 0f;
            minX = minY = Float.MAX_VALUE;
            maxX = maxY = -Float.MAX_VALUE;
            invalidate();
        }

        /** Bounding box of the stroke with padding for OCR crop accuracy. */
        RectF getBoundingBox() {
            float pad = dp(14);
            return new RectF(minX - pad, minY - pad, maxX + pad, maxY + pad);
        }

        /** Flash green bounding box then fire OCR. */
        void triggerConfirm() {
            confirmed = true;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f, 0.6f, 0f);
            a.setDuration(450);
            a.addUpdateListener(anim -> {
                confirmAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    H.post(() -> runOCROnSelection(
                        lastStrokeLeft, lastStrokeTop, lastStrokeRight, lastStrokeBottom));
                }
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!hasStroke) return;
            canvas.drawPath(strokePath, glowPaint);
            canvas.drawPath(strokePath, strokePaint);
            if (confirmed && confirmAlpha > 0f) {
                confirmPaint.setAlpha((int)(confirmAlpha * 255));
                RectF bb = new RectF(minX - dp(8), minY - dp(8), maxX + dp(8), maxY + dp(8));
                canvas.drawRoundRect(bb, dp(6), dp(6), confirmPaint);
            }
        }
    }


    // ════════════════════════════════════════════════════════════════
    // OCR on stroke bounding box
    // ════════════════════════════════════════════════════════════════

    private void runOCROnSelection(float screenLeft, float screenTop,
                                   float screenRight, float screenBottom) {
        wakeEngine();
        if (!ocrBusy.compareAndSet(false, true)) { exitSelectionMode(); return; }

        synchronized (mpLock) {
            if (mediaProjection == null) {
                ocrBusy.set(false);
                exitSelectionMode();
                toast(isAr
                    ? "افتح التطبيق لمنح صلاحية الشاشة"
                    : "Open the app to grant screen permission");
                return;
            }
        }

        if (ocrEngineManager == null) {
            ocrBusy.set(false);
            exitSelectionMode();
            toast(isAr ? "OCR غير جاهز" : "OCR not ready");
            return;
        }

        // Attempt engine recovery before each run
        ocrEngineManager.tryRecoverEngines();

        Log.d(TAG, "OCR selection screen=["
            + (int)screenLeft + "," + (int)screenTop + " → "
            + (int)screenRight + "," + (int)screenBottom + "]"
            + "  screenSize=" + SW + "x" + SH);

        H.post(() -> {
            if (destroyed) return;
            if (tvBtnLabel != null) tvBtnLabel.setText("...");
            if (btnView    != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            updateBubbleText(isAr ? "جاري القراءة…" : "Reading…", pairText(), false);
            showBubble();
        });

        final int density = getResources().getDisplayMetrics().densityDpi;

        safeSubmit(() -> {
            if (destroyed) { ocrBusy.set(false); return; }

            Bitmap fullBmp = null;
            try {
                final boolean wasAlive;
                synchronized (mpLock) {
                    if (mediaProjection == null) {
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) { exitSelectionMode(); resetBtn(shortPair()); } });
                        return;
                    }
                    wasAlive = (persistentReader != null && persistentVD != null);
                    if (!wasAlive) {
                        // Full-resolution VirtualDisplay — capW==SW, capH==SH
                        persistentReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
                        persistentVD = mediaProjection.createVirtualDisplay(
                            "GT_CAP", capW, capH, density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            persistentReader.getSurface(), null, null);
                        Log.d(TAG, "VirtualDisplay created " + capW + "x" + capH + " @" + density + "dpi");
                    }
                }

                Thread.sleep(wasAlive ? 120L : CAPTURE_DELAY_MS);
                if (destroyed) { ocrBusy.set(false); return; }

                Image img = null;
                for (int i = 0; i < 3 && img == null; i++) {
                    try { img = persistentReader.acquireLatestImage(); }
                    catch (Exception e) { Log.w(TAG, "acquireLatestImage #" + i + ": " + e.getMessage()); }
                    if (img == null && i < 2)
                        try { Thread.sleep(80); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); break;
                        }
                }

                // Null frame recovery — VD surface stolen by fullscreen game
                if (img == null) {
                    vdNullFrameCount++;
                    if (vdNullFrameCount >= VD_NULL_FRAME_LIMIT) {
                        Log.w(TAG, "VD stale (" + vdNullFrameCount + " nulls) — recreating");
                        synchronized (mpLock) { recreateVirtualDisplay(density); }
                        Thread.sleep(CAPTURE_DELAY_MS);
                        if (persistentReader != null) {
                            try { img = persistentReader.acquireLatestImage(); }
                            catch (Exception e) { Log.w(TAG, "post-recreate: " + e.getMessage()); }
                        }
                    }
                } else {
                    vdNullFrameCount = 0;
                    lastSuccessfulFrame = System.currentTimeMillis();
                }

                if (img != null) {
                    try {
                        Image.Plane[] planes = img.getPlanes();
                        ByteBuffer buf   = planes[0].getBuffer();
                        int rStride      = planes[0].getRowStride();
                        int pStride      = planes[0].getPixelStride();
                        // Actual bitmap width can differ from capW due to GPU row padding
                        int bmpW         = rStride / pStride;
                        int usedW        = Math.min(capW, bmpW);

                        Log.d(TAG, "Bitmap from ImageReader: bmpW=" + bmpW
                            + " capW=" + capW + " capH=" + capH + " rStride=" + rStride);

                        Bitmap tmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
                        tmp.copyPixelsFromBuffer(buf);
                        fullBmp = (bmpW > usedW)
                            ? Bitmap.createBitmap(tmp, 0, 0, usedW, capH)
                            : tmp;
                        if (fullBmp != tmp) tmp.recycle();

                    } catch (OutOfMemoryError | Exception e) {
                        Log.e(TAG, "Bitmap copy: " + e.getMessage());
                    } finally {
                        try { img.close(); } catch (Exception ignored) {}
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { exitSelectionMode(); resetBtn(shortPair()); } });
                return;
            } catch (Exception e) {
                Log.e(TAG, "Capture: " + e.getMessage(), e);
            }

            H.post(this::exitSelectionMode);

            if (fullBmp == null) {
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) {
                    resetBtn(shortPair());
                    updateBubbleText(isAr ? "فشل الالتقاط — حاول مجدداً" : "Capture failed — try again",
                        pairText(), false);
                    scheduleDismiss();
                }});
                return;
            }

            // ── Crop mapping ────────────────────────────────────────────
            // VirtualDisplay is created at capW×capH == SW×SH (full res).
            // scaleX/Y should be ~1.0. We still compute them correctly in
            // case GPU padding made bmpW slightly wider than capW.
            int bmpActualW = fullBmp.getWidth();
            int bmpActualH = fullBmp.getHeight();

            // scaleX = bmpActualW / SW  (should be 1.0 at full res)
            float scaleX = (float) bmpActualW / SW;
            float scaleY = (float) bmpActualH / SH;

            int cropL = clamp((int)(screenLeft   * scaleX), 0, bmpActualW - 1);
            int cropT = clamp((int)(screenTop    * scaleY), 0, bmpActualH - 1);
            int cropR = clamp((int)(screenRight  * scaleX), cropL + 1, bmpActualW);
            int cropB = clamp((int)(screenBottom * scaleY), cropT + 1, bmpActualH);
            int cropW = cropR - cropL;
            int cropH = cropB - cropT;

            Log.d(TAG, "Crop mapping: scale=(" + scaleX + "," + scaleY + ")"
                + "  cropRect=[" + cropL + "," + cropT + " " + cropW + "x" + cropH + "]"
                + "  bitmapSize=" + bmpActualW + "x" + bmpActualH);

            // Guard: reject tiny crops before wasting OCR time
            if (cropW < MIN_CROP_W || cropH < MIN_CROP_H) {
                Log.w(TAG, "Crop too small (" + cropW + "x" + cropH + ") — invalid selection");
                recycleSafe(fullBmp);
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) {
                    resetBtn(shortPair());
                    updateBubbleText(
                        isAr ? "التحديد صغير جداً" : "Selection invalid",
                        pairText(), false);
                    showBubble();
                    scheduleDismiss();
                }});
                return;
            }

            final Bitmap fFull = fullBmp;
            Bitmap cropped;
            try {
                cropped = Bitmap.createBitmap(fFull, cropL, cropT, cropW, cropH);
            } catch (OutOfMemoryError | Exception e) {
                Log.e(TAG, "Crop OOM: " + e.getMessage());
                recycleSafe(fFull);
                ocrBusy.set(false);
                H.post(() -> { if (!destroyed) { resetBtn(shortPair()); scheduleDismiss(); } });
                return;
            }
            recycleSafe(fFull);

            // Save debug crop for inspection
            saveDebugCrop(cropped);

            final Bitmap fCropped = cropped;
            final String snapFrom = fromLang;

            // ── OCR via EngineManager with failover ──────────────────────
            ocrEngineManager.runOcr(fCropped, snapFrom, new OcrEngineManager.OcrCallback() {
                @Override public void onSuccess(String text) {
                    recycleSafe(fCropped);
                    Runtime.getRuntime().gc();
                    ocrBusy.set(false);
                    Log.d(TAG, "OCR result: [" + text + "]");
                    H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                    if (!text.isEmpty()) {
                        doTranslate(text);
                    } else {
                        H.post(() -> { if (!destroyed) onNoTextFound(); });
                    }
                }
                @Override public void onFailure(String reason) {
                    recycleSafe(fCropped);
                    Runtime.getRuntime().gc();
                    ocrBusy.set(false);
                    Log.e(TAG, "OCR all engines failed: " + reason);
                    H.post(() -> { if (!destroyed) {
                        resetBtn(shortPair());
                        onNoTextFound();
                    }});
                }
            });
        });
    }

    /** Returns true if string contains at least one CJK/Hangul/Kana character. */
    private boolean containsCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3000 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7FF)
                || (c >= 0xF900 && c <= 0xFAFF)) return true;
        }
        return false;
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void onNoTextFound() {
        resetBtn(shortPair());
        updateBubbleText(isAr ? "لا يوجد نص في هذه المنطقة" : "No text found", pairText(), false);
        showBubble();
        scheduleDismiss();
        scheduleEngineSleep();
    }


    // ════════════════════════════════════════════════════════════════
    // Compact translation bubble
    // Small pill positioned directly above (or below) the stroke area.
    // No full-screen card, no dim, no ScrollView inside result.
    // ════════════════════════════════════════════════════════════════

    private void buildBubble() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(11), dp(6), dp(11), dp(9));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(210, 4, 10, 26));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.argb(140, 55, 130, 255));
        pill.setBackground(bg);
        pill.setElevation(dp(8));

        // Header: lang label + close ×
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tvBubbleLang = new TextView(this);
        tvBubbleLang.setText(pairText());
        tvBubbleLang.setTextColor(Color.argb(170, 80, 150, 255));
        tvBubbleLang.setTextSize(8f);
        tvBubbleLang.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvBubbleLang, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView xBtn = new TextView(this);
        xBtn.setText(" ×");
        xBtn.setTextColor(Color.argb(200, 220, 80, 80));
        xBtn.setTextSize(13f);
        xBtn.setClickable(true);
        xBtn.setFocusable(true);
        xBtn.setOnClickListener(v -> hideBubble());
        header.addView(xBtn);
        pill.addView(header);

        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(2), 0, dp(4));
        divider.setBackgroundColor(Color.argb(50, 55, 120, 220));
        pill.addView(divider, divLp);

        // Translation text
        tvBubbleText = new TextView(this);
        tvBubbleText.setTextColor(Color.argb(250, 225, 240, 255));
        tvBubbleText.setTextSize(14f);
        tvBubbleText.setTypeface(Typeface.DEFAULT_BOLD);
        tvBubbleText.setLineSpacing(dp(1), 1.1f);
        tvBubbleText.setMaxLines(5);
        tvBubbleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pill.addView(tvBubbleText);

        bubbleView = pill;
        bubbleView.setAlpha(0f);

        bubbleLP = new WindowManager.LayoutParams(
            (int)(SW * 0.72f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        bubbleLP.gravity = Gravity.TOP | Gravity.START;
        bubbleLP.x = dp(8);
        bubbleLP.y = dp(8);

        safeAddView(bubbleView, bubbleLP);
    }

    private void positionBubble() {
        if (bubbleView == null || bubbleLP == null) return;
        if (lastStrokeRight <= lastStrokeLeft || lastStrokeBottom <= lastStrokeTop) return;
        int  w       = bubbleLP.width;
        int  margin  = dp(8);
        int  x       = (int)((lastStrokeLeft + lastStrokeRight) / 2f) - w / 2;
        x = Math.max(margin, Math.min(x, SW - w - margin));
        int  pillH   = dp(84);
        int  y       = (lastStrokeTop > pillH + margin * 2)
            ? (int) lastStrokeTop - pillH - margin
            : (int) lastStrokeBottom + margin;
        y = Math.max(dp(4), Math.min(y, SH - pillH - dp(4)));
        bubbleLP.x = x;
        bubbleLP.y = y;
        safeUpdateLayout(bubbleView, bubbleLP);
    }

    private void updateBubbleText(String text, String langLabel, boolean isResult) {
        if (tvBubbleText == null) return;
        tvBubbleText.setText(text);
        tvBubbleText.setTextColor(isResult
            ? Color.argb(250, 225, 240, 255)
            : Color.argb(150, 130, 165, 220));
        if (tvBubbleLang != null) tvBubbleLang.setText(langLabel);
    }

    private static final int FLAGS_BUBBLE_VISIBLE =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private static final int FLAGS_BUBBLE_HIDDEN =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private void showBubble() {
        if (destroyed || bubbleView == null) return;
        bubbleVisible = true;
        bubbleLP.flags = FLAGS_BUBBLE_VISIBLE;
        positionBubble();
        safeUpdateLayout(bubbleView, bubbleLP);
        bubbleView.animate().cancel();
        bubbleView.animate().alpha(1f).setDuration(200).start();
    }

    private void hideBubble() {
        cancelDismiss();
        if (destroyed || bubbleView == null || !bubbleVisible) return;
        bubbleVisible = false;
        bubbleView.animate().cancel();
        bubbleView.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (destroyed) return;
            bubbleLP.flags = FLAGS_BUBBLE_HIDDEN;
            safeUpdateLayout(bubbleView, bubbleLP);
            scheduleEngineSleep();
        }).start();
        if (btnView != null) btnView.animate().alpha(ALPHA_IDLE).setDuration(300).start();
        scheduleBtnFade();
    }

    private void scheduleDismiss() {
        cancelDismiss();
        dismissR = this::hideBubble;
        H.postDelayed(dismissR, DISMISS_MS);
    }

    private void cancelDismiss() {
        if (dismissR != null) { H.removeCallbacks(dismissR); dismissR = null; }
    }

    private void scheduleBtnFade() {
        if (fadeOutR != null) H.removeCallbacks(fadeOutR);
        fadeOutR = () -> {
            if (!ocrBusy.get() && !translating.get() && !bubbleVisible && !selectionMode && btnView != null)
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


    // ════════════════════════════════════════════════════════════════
    // Translation engine
    // Priority: 1. online (Google Translate)  2. degrade (show original)
    // ════════════════════════════════════════════════════════════════

    private void doTranslate(final String text) {
        if (!translating.compareAndSet(false, true)) return;
        cancelEngineSleep();
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
            if (tvBtnLabel != null) tvBtnLabel.setText("...");
            if (btnView    != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            updateBubbleText(isAr ? "جاري الترجمة…" : "Translating…", pairText(), false);
            showBubble();
        });

        final String input;
        if (text.length() > 600) {
            int cp = text.codePointCount(0, text.length());
            input  = text.substring(0, text.offsetByCodePoints(0, Math.min(600, cp)));
        } else {
            input = text;
        }
        final String snapFrom = fromLang, snapTo = toLang, key = cacheKey;

        if (translationEngineManager == null) {
            translating.set(false);
            H.post(() -> { if (!destroyed) {
                resetBtn(shortPair());
                updateBubbleText(text, "⚠ " + pairText(), false);
                showBubble();
                scheduleDismiss();
                scheduleEngineSleep();
            }});
            return;
        }

        translationEngineManager.translate(input, snapFrom, snapTo, netAvailable, executor,
            new TranslationEngineManager.TranslationCallback() {
                @Override public void onSuccess(String result, String engineName) {
                    translating.set(false);
                    String modeTag = engineName.contains("Passthrough") ? "⚠offline" : "☁";
                    H.post(() -> {
                        if (destroyed) return;
                        translateCache.put(key, result);
                        showResult(text, result, modeTag);
                    });
                }
                @Override public void onFailure(String reason) {
                    translating.set(false);
                    Log.e(TAG, "All translation engines failed: " + reason);
                    H.post(() -> {
                        if (destroyed) return;
                        // Graceful degrade — show original OCR text
                        resetBtn(shortPair());
                        updateBubbleText(text, "⚠ " + pairText(), false);
                        showBubble();
                        scheduleDismiss();
                        scheduleEngineSleep();
                    });
                }
            });
    }

    private void showResult(String original, String translated, String modeTag) {
        if (destroyed) return;
        String label = pairText() + (modeTag.isEmpty() ? "" : "  " + modeTag);
        updateBubbleText(translated, label, true);
        resetBtn(shortPair());
        showBubble();
        scheduleDismiss();
        scheduleEngineSleep();
    }


    // ════════════════════════════════════════════════════════════════
    // Main button touch — drag / single-tap / double-tap / long-press
    // ════════════════════════════════════════════════════════════════

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
                        selBtnLP.x = btnLP.x + dp(1);
                        selBtnLP.y = btnLP.y - dp(50);
                        safeUpdateLayout(selBtnView, selBtnLP);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= LONG_PRESS_MS) {
                            if (bubbleVisible) hideBubble();
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
        if (bubbleVisible)         { hideBubble(); return; }
        toast(isAr ? "استخدم ✏️ للرسم فوق النص" : "Draw with ✏️ over text");
        scheduleBtnFade();
    }

    private void onDoubleTap() {
        if (bubbleVisible) hideBubble();
        openPicker();
    }


    // ════════════════════════════════════════════════════════════════
    // Language picker
    // ════════════════════════════════════════════════════════════════

    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog(isAr ? "لغة النص" : "From language", true, code -> {
            pickerFrom = code;
            showPickerDialog(isAr ? "لغة الترجمة" : "To language", false, code2 -> {
                fromLang = pickerFrom;
                toLang   = code2;
                prefs.edit().putString(KEY_LANG_FROM, fromLang).putString(KEY_LANG_TO, toLang).apply();
                if (tvBubbleLang != null) tvBubbleLang.setText(pairText());
                if (tvBtnLabel   != null) tvBtnLabel.setText(shortPair());
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
        xb.setText("  ×  ");
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

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String[] lang : LANGS) {
            if (!seen.add(lang[0])) continue;
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
            row.setOnTouchListener((v, evt) -> {
                int a = evt.getAction();
                if (a == MotionEvent.ACTION_DOWN)
                    row.setTextColor(Color.argb(255, 80, 165, 255));
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                    row.setTextColor(Color.argb(200, 155, 192, 245));
                return false;
            });
            list.addView(row);

            View rd = new View(this);
            rd.setBackgroundColor(Color.argb(30, 50, 80, 160));
            list.addView(rd, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
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


    // ════════════════════════════════════════════════════════════════
    // Google Translate (unofficial endpoint — personal/dev use only)
    // Replace googleTranslate() to switch to an official backend.
    // ════════════════════════════════════════════════════════════════

    private String googleTranslate(String text, String from, String to) throws Exception {
        String urlStr = "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx&sl=" + (from.equals("auto") ? "auto" : from)
            + "&tl=" + to + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8");

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setConnectTimeout(4_000);
            c.setReadTimeout(4_000);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());

            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
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


    // ════════════════════════════════════════════════════════════════
    // Network monitor — event-driven, zero polling cost
    // ════════════════════════════════════════════════════════════════

    private void startNetworkMonitor() {
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        netAvailable = isNetConnected();
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) { netAvailable = true; }
            @Override public void onLost(Network net)      { if (!isNetConnected()) netAvailable = false; }
        };
        try {
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                netCallback);
        } catch (Exception e) { Log.w(TAG, "NetworkCallback: " + e.getMessage()); }
    }

    private void stopNetworkMonitor() {
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallback = null;
        }
    }

    private boolean isNetConnected() {
        if (cm == null) return true;
        try {
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }


    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private String pairText()  { return abbr(fromLang) + " → " + abbr(toLang); }
    private String shortPair() { return abbr(fromLang) + "→" + abbr(toLang); }

    private String abbr(String c) {
        for (String[] l : LANGS) if (l[0].equals(c)) return l[2];
        return c.length() > 3 ? c.substring(0, 3).toUpperCase(Locale.US) : c.toUpperCase(Locale.US);
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
        catch (Exception e) { Log.e(TAG, "addView: " + e.getMessage()); }
    }

    private void safeUpdateLayout(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null || v.getWindowToken() == null) return;
        try { wm.updateViewLayout(v, lp); }
        catch (Exception e) { Log.e(TAG, "updateLayout: " + e.getMessage()); }
    }

    private void safeRemove(View v) {
        if (v == null || v.getWindowToken() == null) return;
        try { wm.removeView(v); } catch (Exception e) { Log.e(TAG, "removeView: " + e.getMessage()); }
    }

    private void recycleSafe(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) try { bmp.recycle(); } catch (Exception ignored) {}
    }


    // ════════════════════════════════════════════════════════════════
    // Notification
    // ════════════════════════════════════════════════════════════════

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Game Translator", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // OcrEngineManager — sleep/wake failover queue
    // Only one engine is active at a time. Others sleep (not initialized).
    // On failure the current engine sleeps and the next one wakes.
    // ════════════════════════════════════════════════════════════════

    private OcrEngineManager ocrEngineManager;

    /** Call once in initOCR() — replaces direct recognizer fields */
    private void initOcrEngineManager() {
        List<OcrEngineManager.OcrEngine> engines = new ArrayList<>();

        // Engine 0 — Latin (fast, default)
        engines.add(new OcrEngineManager.OcrEngine("Latin-Fast") {
            private TextRecognizer rec;
            @Override public void wake() {
                if (rec == null)
                    rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
            @Override public void sleep() {
                if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; }
            }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("Latin rec is null"); return; }
                rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(r -> cb.onSuccess(r.getText().trim()))
                    .addOnFailureListener(e -> cb.onFailure(e.getMessage()));
            }
        });

        // Engine 1 — Japanese/CJK (accurate for JA/KO/ZH)
        engines.add(new OcrEngineManager.OcrEngine("Japanese-CJK") {
            private TextRecognizer rec;
            @Override public void wake() {
                if (rec == null)
                    rec = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            }
            @Override public void sleep() {
                if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; }
            }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("JA rec is null"); return; }
                rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(r -> cb.onSuccess(r.getText().trim()))
                    .addOnFailureListener(e -> cb.onFailure(e.getMessage()));
            }
        });

        // Engine 2 — Latin with preprocessing (game text / low contrast)
        engines.add(new OcrEngineManager.OcrEngine("Latin-Preprocessed") {
            private TextRecognizer rec;
            @Override public void wake() {
                if (rec == null)
                    rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
            @Override public void sleep() {
                if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; }
            }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("Preprocessed rec is null"); return; }
                Bitmap processed = ImagePreprocessor.process(bmp);
                rec.process(InputImage.fromBitmap(processed, 0))
                    .addOnSuccessListener(r -> {
                        if (processed != bmp && !processed.isRecycled()) processed.recycle();
                        cb.onSuccess(r.getText().trim());
                    })
                    .addOnFailureListener(e -> {
                        if (processed != bmp && !processed.isRecycled()) processed.recycle();
                        cb.onFailure(e.getMessage());
                    });
            }
        });

        // Engine 3 — Japanese with preprocessing (JA game text fallback)
        engines.add(new OcrEngineManager.OcrEngine("Japanese-Preprocessed") {
            private TextRecognizer rec;
            @Override public void wake() {
                if (rec == null)
                    rec = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            }
            @Override public void sleep() {
                if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; }
            }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("JA preprocessed rec is null"); return; }
                Bitmap processed = ImagePreprocessor.process(bmp);
                rec.process(InputImage.fromBitmap(processed, 0))
                    .addOnSuccessListener(r -> {
                        if (processed != bmp && !processed.isRecycled()) processed.recycle();
                        cb.onSuccess(r.getText().trim());
                    })
                    .addOnFailureListener(e -> {
                        if (processed != bmp && !processed.isRecycled()) processed.recycle();
                        cb.onFailure(e.getMessage());
                    });
            }
        });

        ocrEngineManager = new OcrEngineManager(engines);
        ocrEngineManager.wakeActive(); // wake engine 0 only
    }

    private void closeOcrEngineManager() {
        if (ocrEngineManager != null) { ocrEngineManager.closeAll(); ocrEngineManager = null; }
    }


    // ════════════════════════════════════════════════════════════════
    // TranslationEngineManager — Google → degraded fallback
    // ════════════════════════════════════════════════════════════════

    private TranslationEngineManager translationEngineManager;

    private void initTranslationEngineManager() {
        List<TranslationEngineManager.TranslationEngine> engines = new ArrayList<>();

        // Engine 0 — Google Translate (unofficial endpoint)
        engines.add(new TranslationEngineManager.TranslationEngine("Google-Translate") {
            @Override
            public String translate(String text, String from, String to) throws Exception {
                return googleTranslate(text, from, to);
            }
            @Override public boolean requiresNetwork() { return true; }
        });

        // Engine 1 — Show original (offline degraded mode)
        engines.add(new TranslationEngineManager.TranslationEngine("Offline-Passthrough") {
            @Override
            public String translate(String text, String from, String to) throws Exception {
                return text; // return original — user sees OCR result at minimum
            }
            @Override public boolean requiresNetwork() { return false; }
        });

        translationEngineManager = new TranslationEngineManager(engines);
    }


    // ════════════════════════════════════════════════════════════════
    // OcrEngineManager — inner static class
    // ════════════════════════════════════════════════════════════════

    private static class OcrEngineManager {
        interface OcrCallback {
            void onSuccess(String text);
            void onFailure(String reason);
        }

        static abstract class OcrEngine {
            final String name;
            boolean sleeping = true;
            int     failCount = 0;
            long    lastFailAt = 0;
            static final long COOLDOWN_MS = 30_000;
            static final int  MAX_FAILS   = 2;

            OcrEngine(String name) { this.name = name; }
            abstract void wake();
            abstract void sleep();
            abstract void recognize(Bitmap bmp, OcrCallback cb);

            boolean isCoolingDown() {
                return failCount >= MAX_FAILS
                    && (System.currentTimeMillis() - lastFailAt) < COOLDOWN_MS;
            }
            void recordFail() {
                failCount++;
                lastFailAt = System.currentTimeMillis();
            }
            void resetFails() { failCount = 0; }
        }

        private final List<OcrEngine> engines;
        private final AtomicInteger   activeIdx = new AtomicInteger(0);

        OcrEngineManager(List<OcrEngine> engines) { this.engines = engines; }

        void wakeActive() {
            OcrEngine e = engines.get(activeIdx.get());
            if (e.sleeping) { e.wake(); e.sleeping = false; }
            Log.d("GT-EngMgr", "OCR wake → [" + e.name + "]");
        }

        /** Run OCR with automatic failover. cb runs on whatever thread ML Kit uses. */
        void runOcr(Bitmap bmp, String preferLang, OcrCallback finalCb) {
            tryEngine(bmp, preferLang, finalCb, 0);
        }

        private void tryEngine(Bitmap bmp, String preferLang, OcrCallback finalCb, int attempt) {
            // Pick best starting engine based on language preference
            int startIdx = pickStartIdx(preferLang);
            int idx = (startIdx + attempt) % engines.size();

            // Skip engines in cooldown (max 1 full pass)
            int skips = 0;
            while (engines.get(idx).isCoolingDown() && skips < engines.size()) {
                Log.d("GT-EngMgr", "OCR engine [" + engines.get(idx).name + "] cooling down, skip");
                idx = (idx + 1) % engines.size();
                skips++;
            }
            if (skips == engines.size()) {
                Log.e("GT-EngMgr", "All OCR engines cooling down — giving up");
                finalCb.onFailure("all_engines_cooling");
                return;
            }

            final int fIdx = idx;
            OcrEngine eng = engines.get(fIdx);

            // Wake this engine if sleeping
            if (eng.sleeping) { eng.wake(); eng.sleeping = false; }
            activeIdx.set(fIdx);
            Log.d("GT-EngMgr", "OCR attempt=" + attempt + " engine=[" + eng.name + "]");

            eng.recognize(bmp, new OcrCallback() {
                @Override public void onSuccess(String text) {
                    if (text.isEmpty()) {
                        // Empty result = soft fail → try next engine
                        Log.w("GT-EngMgr", "OCR [" + eng.name + "] returned empty → failover");
                        eng.recordFail();
                        sleepEngine(fIdx);
                        if (attempt + 1 < engines.size())
                            tryEngine(bmp, preferLang, finalCb, attempt + 1);
                        else
                            finalCb.onSuccess(""); // all engines empty
                    } else {
                        eng.resetFails();
                        Log.d("GT-EngMgr", "OCR [" + eng.name + "] success: " + text.length() + " chars");
                        finalCb.onSuccess(text);
                    }
                }
                @Override public void onFailure(String reason) {
                    Log.e("GT-EngMgr", "OCR [" + eng.name + "] fail: " + reason);
                    eng.recordFail();
                    sleepEngine(fIdx);
                    if (attempt + 1 < engines.size())
                        tryEngine(bmp, preferLang, finalCb, attempt + 1);
                    else
                        finalCb.onFailure("all_engines_failed");
                }
            });
        }

        private int pickStartIdx(String lang) {
            if (lang != null && (lang.equals("ja") || lang.equals("ko") || lang.startsWith("zh")))
                return 1; // start with Japanese engine
            return 0; // start with Latin engine
        }

        private void sleepEngine(int idx) {
            OcrEngine e = engines.get(idx);
            if (!e.sleeping) { e.sleep(); e.sleeping = true; }
            Log.d("GT-EngMgr", "OCR sleep ← [" + e.name + "]  fails=" + e.failCount);
        }

        /** Attempt to recover sleeping engines after cooldown expires */
        void tryRecoverEngines() {
            for (OcrEngine e : engines) {
                if (e.sleeping && !e.isCoolingDown() && e.failCount > 0) {
                    e.resetFails();
                    Log.d("GT-EngMgr", "OCR engine [" + e.name + "] recovered (cooldown elapsed)");
                }
            }
            // Ensure at least one engine is awake
            boolean anyAwake = false;
            for (OcrEngine e : engines) if (!e.sleeping) { anyAwake = true; break; }
            if (!anyAwake) wakeActive();
        }

        void closeAll() {
            for (OcrEngine e : engines) { try { e.sleep(); } catch (Exception ignored) {} }
            Log.d("GT-EngMgr", "All OCR engines closed");
        }
    }


    // ════════════════════════════════════════════════════════════════
    // TranslationEngineManager — inner static class
    // ════════════════════════════════════════════════════════════════

    private static class TranslationEngineManager {
        interface TranslationCallback {
            void onSuccess(String result, String engineName);
            void onFailure(String reason);
        }

        static abstract class TranslationEngine {
            final String name;
            int  failCount  = 0;
            long lastFailAt = 0;
            static final long COOLDOWN_MS = 60_000;
            static final int  MAX_FAILS   = 2;

            TranslationEngine(String name) { this.name = name; }
            abstract String translate(String text, String from, String to) throws Exception;
            abstract boolean requiresNetwork();

            boolean isCoolingDown() {
                return failCount >= MAX_FAILS
                    && (System.currentTimeMillis() - lastFailAt) < COOLDOWN_MS;
            }
            void recordFail() { failCount++; lastFailAt = System.currentTimeMillis(); }
            void resetFails() { failCount = 0; }
        }

        private final List<TranslationEngine> engines;

        TranslationEngineManager(List<TranslationEngine> engines) { this.engines = engines; }

        void translate(String text, String from, String to, boolean netAvailable,
                       ExecutorService exec, TranslationCallback cb) {
            if (exec == null || exec.isShutdown()) { cb.onFailure("executor_dead"); return; }
            exec.submit(() -> {
                for (int i = 0; i < engines.size(); i++) {
                    TranslationEngine eng = engines.get(i);
                    if (eng.isCoolingDown()) {
                        Log.d("GT-TrMgr", "Translation [" + eng.name + "] cooling, skip");
                        continue;
                    }
                    if (eng.requiresNetwork() && !netAvailable) {
                        Log.d("GT-TrMgr", "Translation [" + eng.name + "] skipped (no network)");
                        continue;
                    }
                    try {
                        Log.d("GT-TrMgr", "Translate attempt engine=[" + eng.name + "]");
                        String result = eng.translate(text, from, to);
                        if (result == null || result.isEmpty()) throw new Exception("empty result");
                        eng.resetFails();
                        Log.d("GT-TrMgr", "Translate [" + eng.name + "] success");
                        cb.onSuccess(result, eng.name);
                        return;
                    } catch (Exception e) {
                        Log.w("GT-TrMgr", "Translate [" + eng.name + "] fail: " + e.getMessage());
                        eng.recordFail();
                    }
                }
                cb.onFailure("all_translation_engines_failed");
            });
        }
    }


    // ════════════════════════════════════════════════════════════════
    // ImagePreprocessor — improves OCR on game text
    // grayscale → contrast boost → sharpen → adaptive threshold
    // Lightweight: no OpenCV, pure Android Canvas/ColorMatrix
    // ════════════════════════════════════════════════════════════════

    private static class ImagePreprocessor {

        static Bitmap process(Bitmap src) {
            if (src == null || src.isRecycled()) return src;
            try {
                // Step 1: scale up small crops for better OCR accuracy
                Bitmap scaled = src;
                if (src.getWidth() < 200 || src.getHeight() < 60) {
                    float factor = Math.max(200f / src.getWidth(), 60f / src.getHeight());
                    int w = Math.round(src.getWidth() * factor);
                    int h = Math.round(src.getHeight() * factor);
                    scaled = Bitmap.createScaledBitmap(src, w, h, true);
                }

                // Step 2: grayscale + contrast boost
                Bitmap gray = Bitmap.createBitmap(scaled.getWidth(), scaled.getHeight(),
                    Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(gray);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0f); // grayscale
                // Contrast boost: scale channels by 1.6, shift by -80
                float[] contrast = {
                    1.6f, 0,    0,    0, -80,
                    0,    1.6f, 0,    0, -80,
                    0,    0,    1.6f, 0, -80,
                    0,    0,    0,    1,   0
                };
                ColorMatrix contrastCm = new ColorMatrix(contrast);
                cm.postConcat(contrastCm);
                p.setColorFilter(new ColorMatrixColorFilter(cm));
                c.drawBitmap(scaled, 0, 0, p);
                if (scaled != src) scaled.recycle();

                // Step 3: sharpen via convolution approximation using overlay
                // (simple unsharp-mask: original - blur + original)
                Bitmap sharp = applySharpen(gray);
                if (sharp != gray) gray.recycle();

                Log.d("GT-Prep", "preprocessed " + src.getWidth() + "x" + src.getHeight()
                    + " → " + sharp.getWidth() + "x" + sharp.getHeight());
                return sharp;

            } catch (Exception e) {
                Log.w("GT-Prep", "preprocess failed: " + e.getMessage());
                return src; // fallback to original
            }
        }

        /** Approximate sharpening: blend original with high-pass */
        private static Bitmap applySharpen(Bitmap src) {
            try {
                Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                // Draw base
                c.drawBitmap(src, 0, 0, null);
                // High-pass overlay: draw again with lighten blend at partial alpha
                Paint p = new Paint();
                p.setAlpha(60); // subtle sharpen
                // Use ColorMatrix to invert for unsharp mask effect
                ColorMatrix inv = new ColorMatrix(new float[]{
                    -1, 0,  0,  0, 255,
                     0,-1,  0,  0, 255,
                     0, 0, -1,  0, 255,
                     0, 0,  0,  1,   0
                });
                p.setColorFilter(new ColorMatrixColorFilter(inv));
                p.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SCREEN));
                c.drawBitmap(src, 0, 0, p);
                return out;
            } catch (Exception e) {
                return src;
            }
        }
    }


    // ════════════════════════════════════════════════════════════════
    // Debug crop saver
    // ════════════════════════════════════════════════════════════════

    private void saveDebugCrop(Bitmap bmp) {
        if (!DEBUG_CROP || bmp == null || bmp.isRecycled()) return;
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "gt_debug_crop_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Log.d(TAG, "DEBUG crop saved → " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "DEBUG crop save failed: " + e.getMessage());
        }
    }



    // ════════════════════════════════════════════════════════════════
    // Adaptive Performance System (APS)
    // ════════════════════════════════════════════════════════════════

    enum PowerMode { LOW, NORMAL, HIGH, TURBO }

    static class AdaptivePerformanceManager {
        private static final String TAG_APS = "GT-APS";
        private static final long IDLE_TO_LOW_MS     = 5_000;
        private static final long HIGH_STEP_DOWN_MS  = 3_000;
        private static final long TURBO_STEP_DOWN_MS = 5_000;

        private volatile PowerMode mode = PowerMode.NORMAL;
        private final Handler H;
        private final ThermalMonitor thermal;
        private Runnable stepDownR;

        Runnable onEnterLow;
        Runnable onExitLow;
        Runnable onTurbo;

        AdaptivePerformanceManager(OcrEngineManager ocr, BitmapPool pool, Handler h) {
            this.H = h;
            this.thermal = new ThermalMonitor();
        }

        void start(Context ctx) {
            thermal.start(ctx, headroom -> {
                if (headroom < 0.3f && mode.ordinal() >= PowerMode.HIGH.ordinal()) {
                    Log.w(TAG_APS, "Thermal → NORMAL");
                    transitionTo(PowerMode.NORMAL, "thermal");
                }
            });
            scheduleStepDown(IDLE_TO_LOW_MS);
        }

        void stop() { thermal.stop(); cancelStepDown(); }
        PowerMode getMode() { return mode; }

        void onUserAction() {
            cancelStepDown();
            if (mode == PowerMode.LOW) {
                if (onExitLow != null) H.post(onExitLow);
            }
            transitionTo(PowerMode.NORMAL, "user_action");
        }

        void onOcrComplete(boolean success) {
            if (success) {
                PowerMode next = mode == PowerMode.TURBO ? PowerMode.HIGH : PowerMode.NORMAL;
                long delay = mode == PowerMode.TURBO ? TURBO_STEP_DOWN_MS : HIGH_STEP_DOWN_MS;
                H.postDelayed(() -> transitionTo(next, "success"), delay);
            }
            scheduleStepDown(IDLE_TO_LOW_MS);
        }

        void onOcrEmpty() {
            cancelStepDown();
            PowerMode next = mode == PowerMode.NORMAL ? PowerMode.HIGH : PowerMode.TURBO;
            transitionTo(next, "ocr_empty");
            scheduleStepDown(IDLE_TO_LOW_MS);
        }

        int preprocessingLevel() {
            float h = thermal.getHeadroom();
            int level = mode.ordinal();
            if (h < 0.3f) level = Math.min(level, 1);
            return level;
        }

        private void transitionTo(PowerMode next, String reason) {
            if (next == mode) return;
            PowerMode prev = mode;
            mode = next;
            Log.d(TAG_APS, prev + " → " + next + " (" + reason + ")");
            if (next == PowerMode.LOW  && onEnterLow != null) H.post(onEnterLow);
            if (prev == PowerMode.LOW  && onExitLow  != null) H.post(onExitLow);
            if (next == PowerMode.TURBO && onTurbo   != null) H.post(onTurbo);
        }

        private void scheduleStepDown(long ms) {
            cancelStepDown();
            stepDownR = () -> transitionTo(PowerMode.LOW, "idle");
            H.postDelayed(stepDownR, ms);
        }

        private void cancelStepDown() {
            if (stepDownR != null) { H.removeCallbacks(stepDownR); stepDownR = null; }
        }
    }


    static class ThermalMonitor {
        interface HeadroomCallback { void onHeadroom(float h); }
        private volatile float headroom = 1.0f;
        private android.os.PowerManager.OnThermalStatusChangedListener listener;

        void start(Context ctx, HeadroomCallback cb) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
            android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            listener = status -> {
                headroom = Math.max(0f, 1f - (status / 6f));
                cb.onHeadroom(headroom);
            };
            try { pm.addThermalStatusListener(listener); }
            catch (Exception e) { Log.w("GT-Thermal", e.getMessage()); }
        }

        void stop() { listener = null; }
        float getHeadroom() { return headroom; }
    }


    static class BitmapPool {
        private static final int POOL_SIZE = 4;
        private final java.util.ArrayDeque<Bitmap> pool = new java.util.ArrayDeque<>(POOL_SIZE);

        synchronized Bitmap acquire(int w, int h) {
            for (Bitmap b : pool) {
                if (!b.isRecycled() && b.getWidth() == w && b.getHeight() == h) {
                    pool.remove(b);
                    b.eraseColor(Color.TRANSPARENT);
                    return b;
                }
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }

        synchronized void release(Bitmap b) {
            if (b == null || b.isRecycled()) return;
            if (pool.size() >= POOL_SIZE) { b.recycle(); return; }
            pool.offer(b);
        }

        synchronized void clear() {
            for (Bitmap b : pool) if (!b.isRecycled()) b.recycle();
            pool.clear();
        }
    }


    static class ProgressiveOCR {
        // Cached objects — allocated once, reused on every OCR call
        private static final Paint       PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
        private static final ColorMatrix GRAY  = new ColorMatrix();
        private static final ColorMatrix CONT  = new ColorMatrix();
        private static final ColorMatrix COMB  = new ColorMatrix();

        static {
            GRAY.setSaturation(0f);
            CONT.set(new float[]{1.6f,0,0,0,-80, 0,1.6f,0,0,-80, 0,0,1.6f,0,-80, 0,0,0,1,0});
        }

        /** Preprocess bitmap according to level, return result (may be same object). */
        static Bitmap preprocess(Bitmap src, int level, BitmapPool pool) {
            if (level <= 0 || src == null || src.isRecycled()) return src;
            Bitmap c = applyContrast(src, pool);
            if (level == 1) return c;
            Bitmap u = (level >= 3) ? upscale(src) : src;
            Bitmap c2 = (u != src) ? applyContrast(u, pool) : c;
            if (u != src && c2 != c) pool.release(c);
            Bitmap s = applySharpen(c2, pool);
            if (s != c2) pool.release(c2);
            return s;
        }

        /** Run OCR at given level. Handles preprocessin
