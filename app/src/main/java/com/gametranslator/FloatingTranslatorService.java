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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * FloatingTranslatorService — v23 (Modular Refactor)
 *
 * What changed from v22:
 *   • CaptureEngine     — owns all MediaProjection / VirtualDisplay / ImageReader logic.
 *                         Fixes wide-screen and high-DPI capture bugs.
 *   • BubbleOverlay     — owns the translation result pill window.
 *   • BitmapPool        — reusable bitmap allocation, reduces GC spikes.
 *   • ClipboardBridge   — debounced clipboard copy (long-press bubble copies text).
 *   • This file now focuses solely on UI orchestration and workflow coordination.
 *   • No static Context, no duplicate code, no pooling bugs.
 *
 * Window layers (TYPE_APPLICATION_OVERLAY):
 *   1. btnView        — language pill  (drag / double-tap = picker)
 *   2. selBtnView     — ✏️ pen button  (tap = toggle, drag = draw stroke)
 *   3. strokeOverlay  — FLAG_NOT_TOUCHABLE canvas (no dim, no rectangle)
 *   4. BubbleOverlay  — compact translation bubble (managed externally)
 *
 * Selection model:
 *   User drags ✏️ across game text → bounding box → OCR → translation → bubble.
 *   Long-press bubble → copies text to clipboard.
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "GT";
    private static final String CHANNEL_ID = "gt_v23";
    private static final int    NOTIF_ID   = 1;

    private static final String PREFS         = "gt_prefs";
    private static final String KEY_LANG_FROM = "lang_from";
    private static final String KEY_LANG_TO   = "lang_to";
    private static final String KEY_BATTERY_ASKED = "battery_asked";

    // Timing constants
    private static final long LONG_PRESS_MS  = 600;
    private static final long DOUBLE_TAP_MS  = 280;
    private static final long IDLE_SLEEP_MS  = 5_000;

    // UI
    private static final float ALPHA_IDLE = 0.22f;
    private static final float ALPHA_BUSY = 0.95f;

    // OCR guards
    private static final int MIN_STROKE_PX = 30;
    private static final int MIN_CROP_W    = 20;
    private static final int MIN_CROP_H    = 10;

    private static final int CACHE_SIZE = 60;

    // Debug: set true to save cropped bitmap for inspection
    private static final boolean DEBUG_CROP = false;

    // ── Supported languages ───────────────────────────────────────────
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

    // ═════════════════════════════════════════════════════════════════
    // Fields
    // ═════════════════════════════════════════════════════════════════

    private final Handler H = new Handler(Looper.getMainLooper());

    // Core modules
    private CaptureEngine   captureEngine;
    private BubbleOverlay   bubbleOverlay;
    private OcrEngineManager         ocrEngineManager;
    private TranslationEngineManager translationEngineManager;

    private WindowManager     wm;
    private int               SW, SH;
    private ExecutorService   executor;
    private LruCache<String, String> translateCache;
    private SharedPreferences prefs;
    private boolean           isAr;

    // ── Floating UI views ─────────────────────────────────────────────
    private View     btnView;
    private View     selBtnView;
    private View     strokeOverlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, selBtnLP, strokeLP;
    private TextView tvBtnLabel;

    // ── Service state ─────────────────────────────────────────────────
    private final AtomicBoolean ocrBusy     = new AtomicBoolean(false);
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private volatile boolean    destroyed   = false;
    private volatile boolean    viewsAdded  = false;
    private volatile boolean    selectionMode = false;

    private volatile String fromLang  = "auto";
    private volatile String toLang    = "ar";
    private String          pickerFrom = "auto";

    // ── Network ───────────────────────────────────────────────────────
    private volatile boolean                    netAvailable = true;
    private ConnectivityManager.NetworkCallback netCallback;
    private ConnectivityManager                 cm;

    // ── Tap / dismiss timers ──────────────────────────────────────────
    private Runnable doubleTapCheck;
    private Runnable fadeOutR;
    private Runnable sleepR;
    private int      tapCount = 0;

    // ── Stroke selection ──────────────────────────────────────────────
    private StrokeCanvasView strokeView;
    private float lastStrokeLeft, lastStrokeTop, lastStrokeRight, lastStrokeBottom;


    // ═════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        isAr = Locale.getDefault().getLanguage().equals("ar");
        createChannel();
        startForeground(NOTIF_ID, buildNotif());

        prefs    = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        wm       = (WindowManager) getSystemService(WINDOW_SERVICE);
        fromLang = prefs.getString(KEY_LANG_FROM, "auto");
        toLang   = prefs.getString(KEY_LANG_TO, "ar");

        resolveScreenSize();

        // Initialize modular components
        captureEngine            = new CaptureEngine();
        bubbleOverlay            = new BubbleOverlay(this, SW, SH);
        translateCache           = new LruCache<>(CACHE_SIZE);

        initOcrEngines();
        initTranslationEngines();
        rebuildExecutor();
        startNetworkMonitor();

        bubbleOverlay.attach();
        bubbleOverlay.setOnCloseListener(this::onBubbleClosed);

        try {
            buildButton();
            buildSelectionButton();
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
        rebuildExecutor();

        if (intent == null) {
            Log.w(TAG, "START_STICKY restart — waiting for user action");
            return START_STICKY;
        }

        // ── Apply lang params from MainActivity ───────────────────────
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

        // ── Receive MediaProjection token ─────────────────────────────
        // Always accept a fresh token — MainActivity always requests a new one.
        // This handles Xiaomi/Honor stale token after lock screen / force-stop.
        if (intent.hasExtra("mp_result_code")) {
            int    rc   = intent.getIntExtra("mp_result_code", -1);
            Intent data = intent.getParcelableExtra("mp_data");
            if (rc == android.app.Activity.RESULT_OK && data != null) {
                android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);
                if (mpm != null) {
                    try {
                        android.media.projection.MediaProjection mp =
                            mpm.getMediaProjection(rc, data);

                        mp.registerCallback(new android.media.projection.MediaProjection.Callback() {
                            @Override public void onStop() {
                                Log.w(TAG, "MediaProjection stopped externally");
                                captureEngine.releaseVD();
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

                        // setProjection() releases old VD internally before assigning new mp
                        captureEngine.setProjection(mp);
                        Log.d(TAG, "MediaProjection (re)set — fresh token");
                    } catch (Exception e) {
                        Log.e(TAG, "MediaProjection init: " + e.getMessage(), e);
                    }
                }
            } else {
                H.post(() -> { if (!destroyed)
                    toast(isAr ? "رُفضت صلاحية الشاشة" : "Screen permission denied"); });
            }
        }

        requestBatteryExemptionOnce();
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        destroyed = true;
        super.onDestroy();

        H.removeCallbacksAndMessages(null);
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();

        // Close OCR engines
        if (ocrEngineManager != null) { ocrEngineManager.closeAll(); ocrEngineManager = null; }
        translationEngineManager = null;

        // Release capture resources
        if (captureEngine != null) { captureEngine.release(); captureEngine = null; }

        // Release bubble
        if (bubbleOverlay != null) { bubbleOverlay.detach(); bubbleOverlay = null; }

        // Drain bitmap pool
        BitmapPool.drainPool();

        stopNetworkMonitor();
        ClipboardBridge.cancel();

        if (viewsAdded) {
            safeRemove(btnView);
            safeRemove(selBtnView);
            safeRemove(strokeOverlayView);
            safeRemove(pickerView);
        }
        btnView = selBtnView = strokeOverlayView = pickerView = null;
        tvBtnLabel = null;
        strokeView = null;
    }


    // ═════════════════════════════════════════════════════════════════
    // Executor management
    // ═════════════════════════════════════════════════════════════════

    private void rebuildExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "GT-Worker");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
    }

    private void safeSubmit(Runnable task) {
        rebuildExecutor();
        try { executor.submit(task); }
        catch (RejectedExecutionException e) { Log.e(TAG, "safeSubmit rejected"); }
    }


    // ═════════════════════════════════════════════════════════════════
    // Sleep / wake (idle resource management)
    // ═════════════════════════════════════════════════════════════════

    private void scheduleEngineSleep() {
        cancelEngineSleep();
        sleepR = this::enterEngineSleep;
        H.postDelayed(sleepR, IDLE_SLEEP_MS);
    }

    private void cancelEngineSleep() {
        if (sleepR != null) { H.removeCallbacks(sleepR); sleepR = null; }
    }

    private void enterEngineSleep() {
        if (destroyed || ocrBusy.get() || translating.get()) { scheduleEngineSleep(); return; }
        // Release VD (keeps projection token alive for fast resume)
        if (captureEngine != null) captureEngine.releaseVD();
        if (executor != null && !executor.isShutdown()) { executor.shutdown(); executor = null; }
        sleepR = null;
        Log.d(TAG, "engine sleeping");
    }

    private void wakeEngine() {
        cancelEngineSleep();
        rebuildExecutor();
    }


    // ═════════════════════════════════════════════════════════════════
    // Screen size
    // ═════════════════════════════════════════════════════════════════

    private void resolveScreenSize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                SW = b.width(); SH = b.height();
            } else {
                DisplayMetrics dm = new DisplayMetrics();
                //noinspection deprecation
                wm.getDefaultDisplay().getRealMetrics(dm);   // getRealMetrics > getMetrics for full panel
                SW = dm.widthPixels; SH = dm.heightPixels;
            }
        } catch (Exception e) { SW = 1080; SH = 1920; }
        Log.d(TAG, "screen=" + SW + "x" + SH);
    }


    // ═════════════════════════════════════════════════════════════════
    // Floating language pill
    // ═════════════════════════════════════════════════════════════════

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


    // ═════════════════════════════════════════════════════════════════
    // ✏️ Pen button
    // ═════════════════════════════════════════════════════════════════

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
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        selBtnLP.gravity = Gravity.TOP | Gravity.START;
        selBtnLP.x = SW - dp(63);
        selBtnLP.y = SH / 2 - dp(50);

        selBtnView.setOnTouchListener(new SelBtnTouch());
        safeAddView(selBtnView, selBtnLP);
    }


    // ═════════════════════════════════════════════════════════════════
    // ✏️ Touch — tap = toggle, drag = draw stroke
    // ═════════════════════════════════════════════════════════════════

    private class SelBtnTouch implements View.OnTouchListener {
        private float   rx, ry;
        private int     ix, iy;
        private boolean dragged, strokeDragging;

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
                            if (!strokeDragging) {
                                strokeDragging = true;
                                strokeView.startStroke(e.getRawX(), e.getRawY());
                            } else {
                                strokeView.addPoint(e.getRawX(), e.getRawY());
                            }
                        } else {
                            selBtnLP.x = clamp(ix + (int) dx, 0, SW - dp(50));
                            selBtnLP.y = clamp(iy + (int) dy, 0, SH - dp(50));
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


    // ═════════════════════════════════════════════════════════════════
    // Selection mode
    // ═════════════════════════════════════════════════════════════════

    private void enterSelectionMode() {
        if (selectionMode || destroyed) return;
        if (!captureEngine.hasProjection()) {
            toast(isAr ? "افتح التطبيق لمنح صلاحية الشاشة" : "Open the app to grant screen permission");
            return;
        }
        selectionMode = true;
        if (bubbleOverlay.isVisible()) bubbleOverlay.hide();
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

    private void onBubbleClosed() {
        if (btnView != null) btnView.animate().alpha(ALPHA_IDLE).setDuration(300).start();
        scheduleBtnFade();
        scheduleEngineSleep();
    }


    // ═════════════════════════════════════════════════════════════════
    // Stroke overlay — full-screen, FLAG_NOT_TOUCHABLE
    // ═════════════════════════════════════════════════════════════════

    private void buildStrokeOverlay() {
        if (strokeOverlayView != null) { safeRemove(strokeOverlayView); strokeOverlayView = null; strokeView = null; }

        strokeView = new StrokeCanvasView(this);

        FrameLayout root = new FrameLayout(this);
        root.setAlpha(0f);
        root.addView(strokeView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        strokeOverlayView = root;

        strokeLP = new WindowManager.LayoutParams(
            SW, SH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        strokeLP.gravity = Gravity.TOP | Gravity.START;

        safeAddView(strokeOverlayView, strokeLP);
        strokeOverlayView.animate().alpha(1f).setDuration(160).start();
    }


    // ═════════════════════════════════════════════════════════════════
    // StrokeCanvasView — freeform pen stroke
    // ═════════════════════════════════════════════════════════════════

    private class StrokeCanvasView extends View {
        private final Path   strokePath   = new Path();
        private boolean      hasStroke    = false;
        private boolean      confirmed    = false;
        private float        confirmAlpha = 0f;

        private float minX = Float.MAX_VALUE,  minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        private final Paint strokePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint confirmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StrokeCanvasView(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // required for BlurMaskFilter

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(5));
            strokePaint.setColor(Color.argb(225, 255, 255, 255));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(dp(16));
            glowPaint.setColor(Color.argb(60, 180, 215, 255));
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(10), BlurMaskFilter.Blur.NORMAL));

            confirmPaint.setStyle(Paint.Style.STROKE);
            confirmPaint.setStrokeWidth(dp(2));
            confirmPaint.setColor(Color.argb(255, 80, 220, 120));
        }

        void startStroke(float x, float y) {
            strokePath.reset();
            strokePath.moveTo(x, y);
            hasStroke = true; confirmed = false; confirmAlpha = 0f;
            minX = maxX = x; minY = maxY = y;
            invalidate();
        }

        void addPoint(float x, float y) {
            if (!hasStroke) { startStroke(x, y); return; }
            strokePath.lineTo(x, y);
            if (x < minX) minX = x; if (y < minY) minY = y;
            if (x > maxX) maxX = x; if (y > maxY) maxY = y;
            invalidate();
        }

        void clearStroke() {
            strokePath.reset(); hasStroke = false; confirmed = false; confirmAlpha = 0f;
            minX = minY = Float.MAX_VALUE; maxX = maxY = -Float.MAX_VALUE;
            invalidate();
        }

        /** Bounding box with padding for OCR accuracy */
        RectF getBoundingBox() {
            float pad = dp(14);
            return new RectF(minX - pad, minY - pad, maxX + pad, maxY + pad);
        }

        /** Green flash animation then OCR */
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


    // ═════════════════════════════════════════════════════════════════
    // OCR pipeline
    // ═════════════════════════════════════════════════════════════════

    private void runOCROnSelection(float sL, float sT, float sR, float sB) {
        wakeEngine();
        if (!ocrBusy.compareAndSet(false, true)) { exitSelectionMode(); return; }
        if (!captureEngine.hasProjection()) {
            ocrBusy.set(false); exitSelectionMode();
            toast(isAr ? "افتح التطبيق لمنح صلاحية الشاشة" : "Open the app to grant screen permission");
            return;
        }
        if (ocrEngineManager == null) {
            ocrBusy.set(false); exitSelectionMode();
            toast(isAr ? "OCR غير جاهز" : "OCR not ready");
            return;
        }

        ocrEngineManager.tryRecoverEngines();

        Log.d(TAG, "OCR start screen=[" + (int)sL + "," + (int)sT
            + " → " + (int)sR + "," + (int)sB + "]");

        H.post(() -> {
            if (destroyed) return;
            if (tvBtnLabel != null) tvBtnLabel.setText("...");
            if (btnView    != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            bubbleOverlay.update(isAr ? "جاري القراءة…" : "Reading…", pairText(), false);
            bubbleOverlay.setStrokeBounds(sL, sT, sR, sB);
            bubbleOverlay.show();
        });

        final int density = getResources().getDisplayMetrics().densityDpi;
        final String snapFrom = fromLang;

        safeSubmit(() -> {
            if (destroyed) { ocrBusy.set(false); return; }

            // ── Capture full frame ────────────────────────────────────
            captureEngine.acquireFrame(SW, SH, density, new CaptureEngine.FrameCallback() {

                @Override
                public void onFrame(Bitmap fullBmp) {
                    H.post(FloatingTranslatorService.this::exitSelectionMode);

                    int bW = fullBmp.getWidth(), bH = fullBmp.getHeight();
                    int[] crop = CaptureEngine.computeCrop(sL, sT, sR, sB, bW, bH, SW, SH,
                        MIN_CROP_W, MIN_CROP_H);

                    if (crop == null) {
                        BitmapPool.release(fullBmp);
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) {
                            resetBtn(shortPair());
                            bubbleOverlay.update(
                                isAr ? "التحديد صغير جداً" : "Selection invalid",
                                pairText(), false);
                            bubbleOverlay.show();
                            bubbleOverlay.scheduleDismiss();
                        }});
                        return;
                    }

                    // Crop the selection region
                    Bitmap cropped;
                    try {
                        cropped = Bitmap.createBitmap(fullBmp, crop[0], crop[1], crop[2], crop[3]);
                    } catch (OutOfMemoryError | Exception e) {
                        Log.e(TAG, "crop OOM: " + e.getMessage());
                        BitmapPool.release(fullBmp);
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) { resetBtn(shortPair()); bubbleOverlay.scheduleDismiss(); } });
                        return;
                    }
                    BitmapPool.release(fullBmp);  // full bitmap no longer needed

                    saveDebugCrop(cropped);

                    // ── OCR with failover ─────────────────────────────
                    ocrEngineManager.runOcr(cropped, snapFrom, new OcrEngineManager.OcrCallback() {
                        @Override public void onSuccess(String text) {
                            BitmapPool.recycleSafe(cropped);
                            ocrBusy.set(false);
                            Log.d(TAG, "OCR: [" + text + "]");
                            H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                            if (!text.isEmpty()) doTranslate(text);
                            else H.post(() -> { if (!destroyed) onNoTextFound(); });
                        }
                        @Override public void onFailure(String reason) {
                            BitmapPool.recycleSafe(cropped);
                            ocrBusy.set(false);
                            Log.e(TAG, "OCR failed: " + reason);
                            H.post(() -> { if (!destroyed) { resetBtn(shortPair()); onNoTextFound(); } });
                        }
                    });
                }

                @Override
                public void onError(String reason) {
                    ocrBusy.set(false);
                    Log.e(TAG, "capture error: " + reason);
                    H.post(() -> { if (!destroyed) {
                        exitSelectionMode();
                        resetBtn(shortPair());
                        bubbleOverlay.update(
                            isAr ? "فشل الالتقاط — حاول مجدداً" : "Capture failed — try again",
                            pairText(), false);
                        bubbleOverlay.show();
                        bubbleOverlay.scheduleDismiss();
                    }});
                }
            });
        });
    }

    private void onNoTextFound() {
        resetBtn(shortPair());
        bubbleOverlay.update(isAr ? "لا يوجد نص في هذه المنطقة" : "No text found", pairText(), false);
        bubbleOverlay.show();
        bubbleOverlay.scheduleDismiss();
        scheduleEngineSleep();
    }


    // ═════════════════════════════════════════════════════════════════
    // Translation pipeline
    // ═════════════════════════════════════════════════════════════════

    private void doTranslate(String text) {
        if (!translating.compareAndSet(false, true)) return;
        cancelEngineSleep();
        bubbleOverlay.cancelDismiss();

        // Cache check
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
            bubbleOverlay.update(isAr ? "جاري الترجمة…" : "Translating…", pairText(), false);
            bubbleOverlay.show();
        });

        // Truncate very long strings to prevent timeout
        String input = text.length() > 600
            ? text.substring(0, text.offsetByCodePoints(0,
                Math.min(600, text.codePointCount(0, text.length()))))
            : text;

        final String snapFrom = fromLang, snapTo = toLang, key = cacheKey;

        if (translationEngineManager == null) {
            translating.set(false);
            H.post(() -> { if (!destroyed) {
                resetBtn(shortPair());
                bubbleOverlay.update(text, "⚠ " + pairText(), false);
                bubbleOverlay.show();
                bubbleOverlay.scheduleDismiss();
                scheduleEngineSleep();
            }});
            return;
        }

        translationEngineManager.translate(input, snapFrom, snapTo, netAvailable, executor,
            new TranslationEngineManager.TranslationCallback() {
                @Override public void onSuccess(String result, String engineName) {
                    translating.set(false);
                    String tag = engineName.contains("Passthrough") ? "⚠offline" : "☁";
                    H.post(() -> {
                        if (destroyed) return;
                        translateCache.put(key, result);
                        showResult(text, result, tag);
                    });
                }
                @Override public void onFailure(String reason) {
                    translating.set(false);
                    Log.e(TAG, "translate all failed: " + reason);
                    H.post(() -> { if (!destroyed) {
                        resetBtn(shortPair());
                        bubbleOverlay.update(text, "⚠ " + pairText(), false);
                        bubbleOverlay.show();
                        bubbleOverlay.scheduleDismiss();
                        scheduleEngineSleep();
                    }});
                }
            });
    }

    private void showResult(String original, String translated, String modeTag) {
        if (destroyed) return;
        String label = pairText() + (modeTag.isEmpty() ? "" : "  " + modeTag);
        bubbleOverlay.update(translated, label, true);
        resetBtn(shortPair());
        bubbleOverlay.show();
        bubbleOverlay.scheduleDismiss();
        scheduleEngineSleep();
    }


    // ═════════════════════════════════════════════════════════════════
    // Main button touch — drag / single / double-tap / long-press
    // ═════════════════════════════════════════════════════════════════

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
                        btnLP.x = clamp(ix + (int) dx, 0, SW - dp(68));
                        btnLP.y = clamp(iy + (int) dy, 0, SH - dp(40));
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
                            if (bubbleOverlay.isVisible()) bubbleOverlay.hide();
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
        if (bubbleOverlay.isVisible()) { bubbleOverlay.hide(); return; }
        toast(isAr ? "استخدم ✏️ للرسم فوق النص" : "Draw with ✏️ over text");
        scheduleBtnFade();
    }

    private void onDoubleTap() {
        if (bubbleOverlay.isVisible()) bubbleOverlay.hide();
        openPicker();
    }


    // ═════════════════════════════════════════════════════════════════
    // Language picker
    // ═════════════════════════════════════════════════════════════════

    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog(isAr ? "لغة النص" : "From language", true, code -> {
            pickerFrom = code;
            showPickerDialog(isAr ? "لغة الترجمة" : "To language", false, code2 -> {
                fromLang = pickerFrom;
                toLang   = code2;
                prefs.edit().putString(KEY_LANG_FROM, fromLang).putString(KEY_LANG_TO, toLang).apply();
                bubbleOverlay.setLangLabel(pairText());
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

        // Title row
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
        LinearLayout.LayoutParams divLp =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
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
        WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
            (int)(SW * 0.70f), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.CENTER;
        safeAddView(pickerView, plp);
    }

    private void closePicker() {
        if (pickerView != null) { safeRemove(pickerView); pickerView = null; }
    }


    // ═════════════════════════════════════════════════════════════════
    // Google Translate (unofficial endpoint — personal/dev use only)
    // ═════════════════════════════════════════════════════════════════

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


    // ═════════════════════════════════════════════════════════════════
    // Network monitor
    // ═════════════════════════════════════════════════════════════════

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


    // ═════════════════════════════════════════════════════════════════
    // OCR engine manager
    // ═════════════════════════════════════════════════════════════════

    private void initOcrEngines() {
        List<OcrEngineManager.OcrEngine> engines = new ArrayList<>();

        engines.add(new OcrEngineManager.OcrEngine("Latin-Fast") {
            private TextRecognizer rec;
            @Override public void wake()  { if (rec == null) rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); }
            @Override public void sleep() { if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; } }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("Latin rec null"); return; }
                rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(r -> cb.onSuccess(r.getText().trim()))
                    .addOnFailureListener(e -> cb.onFailure(e.getMessage()));
            }
        });

        engines.add(new OcrEngineManager.OcrEngine("Japanese-CJK") {
            private TextRecognizer rec;
            @Override public void wake()  { if (rec == null) rec = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()); }
            @Override public void sleep() { if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; } }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("JA rec null"); return; }
                rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(r -> cb.onSuccess(r.getText().trim()))
                    .addOnFailureListener(e -> cb.onFailure(e.getMessage()));
            }
        });

        engines.add(new OcrEngineManager.OcrEngine("Latin-Preprocessed") {
            private TextRecognizer rec;
            @Override public void wake()  { if (rec == null) rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); }
            @Override public void sleep() { if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; } }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("Preprocessed rec null"); return; }
                Bitmap processed = ImagePreprocessor.process(bmp);
                rec.process(InputImage.fromBitmap(processed, 0))
                    .addOnSuccessListener(r -> {
                        if (processed != bmp) BitmapPool.recycleSafe(processed);
                        cb.onSuccess(r.getText().trim());
                    })
                    .addOnFailureListener(e -> {
                        if (processed != bmp) BitmapPool.recycleSafe(processed);
                        cb.onFailure(e.getMessage());
                    });
            }
        });

        engines.add(new OcrEngineManager.OcrEngine("Japanese-Preprocessed") {
            private TextRecognizer rec;
            @Override public void wake()  { if (rec == null) rec = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build()); }
            @Override public void sleep() { if (rec != null) { try { rec.close(); } catch (Exception ignored) {} rec = null; } }
            @Override public void recognize(Bitmap bmp, OcrEngineManager.OcrCallback cb) {
                if (rec == null) { cb.onFailure("JA-Prep rec null"); return; }
                Bitmap processed = ImagePreprocessor.process(bmp);
                rec.process(InputImage.fromBitmap(processed, 0))
                    .addOnSuccessListener(r -> {
                        if (processed != bmp) BitmapPool.recycleSafe(processed);
                        cb.onSuccess(r.getText().trim());
                    })
                    .addOnFailureListener(e -> {
                        if (processed != bmp) BitmapPool.recycleSafe(processed);
                        cb.onFailure(e.getMessage());
                    });
            }
        });

        ocrEngineManager = new OcrEngineManager(engines);
        ocrEngineManager.wakeActive();
    }


    // ═════════════════════════════════════════════════════════════════
    // Translation engine manager
    // ═════════════════════════════════════════════════════════════════

    private void initTranslationEngines() {
        List<TranslationEngineManager.TranslationEngine> engines = new ArrayList<>();

        engines.add(new TranslationEngineManager.TranslationEngine("Google-Translate") {
            @Override public String translate(String text, String from, String to) throws Exception {
                return googleTranslate(text, from, to);
            }
            @Override public boolean requiresNetwork() { return true; }
        });

        engines.add(new TranslationEngineManager.TranslationEngine("Offline-Passthrough") {
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public boolean requiresNetwork() { return false; }
        });

        translationEngineManager = new TranslationEngineManager(engines);
    }


    // ═════════════════════════════════════════════════════════════════
    // OcrEngineManager (inner static — no outer class reference)
    // ═════════════════════════════════════════════════════════════════

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
            void recordFail() { failCount++; lastFailAt = System.currentTimeMillis(); }
            void resetFails() { failCount = 0; }
        }

        private final List<OcrEngine>     engines;
        private final AtomicInteger       activeIdx = new AtomicInteger(0);

        OcrEngineManager(List<OcrEngine> engines) { this.engines = engines; }

        void wakeActive() {
            OcrEngine e = engines.get(activeIdx.get());
            if (e.sleeping) { e.wake(); e.sleeping = false; }
            Log.d("GT-OCR", "wake → [" + e.name + "]");
        }

        void runOcr(Bitmap bmp, String preferLang, OcrCallback finalCb) {
            tryEngine(bmp, preferLang, finalCb, 0);
        }

        private void tryEngine(Bitmap bmp, String preferLang, OcrCallback finalCb, int attempt) {
            int startIdx = pickStartIdx(preferLang);
            int idx      = (startIdx + attempt) % engines.size();
            int skips    = 0;

            while (engines.get(idx).isCoolingDown() && skips < engines.size()) {
                idx = (idx + 1) % engines.size();
                skips++;
            }
            if (skips == engines.size()) {
                finalCb.onFailure("all_cooling"); return;
            }

            final int fIdx = idx;
            OcrEngine eng  = engines.get(fIdx);
            if (eng.sleeping) { eng.wake(); eng.sleeping = false; }
            activeIdx.set(fIdx);
            Log.d("GT-OCR", "try[" + attempt + "] engine=" + eng.name);

            eng.recognize(bmp, new OcrCallback() {
                @Override public void onSuccess(String text) {
                    if (text.isEmpty()) {
                        eng.recordFail();
                        sleepEngine(fIdx);
                        if (attempt + 1 < engines.size())
                            tryEngine(bmp, preferLang, finalCb, attempt + 1);
                        else
                            finalCb.onSuccess("");
                    } else {
                        eng.resetFails();
                        finalCb.onSuccess(text);
                    }
                }
                @Override public void onFailure(String reason) {
                    Log.e("GT-OCR", "[" + eng.name + "] fail: " + reason);
                    eng.recordFail();
                    sleepEngine(fIdx);
                    if (attempt + 1 < engines.size())
                        tryEngine(bmp, preferLang, finalCb, attempt + 1);
                    else
                        finalCb.onFailure("all_failed");
                }
            });
        }

        private int pickStartIdx(String lang) {
            if (lang != null && (lang.equals("ja") || lang.equals("ko") || lang.startsWith("zh")))
                return 1;
            return 0;
        }

        private void sleepEngine(int idx) {
            OcrEngine e = engines.get(idx);
            if (!e.sleeping) { e.sleep(); e.sleeping = true; }
        }

        void tryRecoverEngines() {
            for (OcrEngine e : engines) {
                if (e.sleeping && !e.isCoolingDown() && e.failCount > 0) e.resetFails();
            }
            boolean anyAwake = false;
            for (OcrEngine e : engines) if (!e.sleeping) { anyAwake = true; break; }
            if (!anyAwake) wakeActive();
        }

        void closeAll() {
            for (OcrEngine e : engines) try { e.sleep(); } catch (Exception ignored) {}
        }
    }


    // ═════════════════════════════════════════════════════════════════
    // TranslationEngineManager (inner static)
    // ═════════════════════════════════════════════════════════════════

    private static class TranslationEngineManager {
        interface TranslationCallback {
            void onSuccess(String result, String engineName);
            void onFailure(String reason);
        }

        static abstract class TranslationEngine {
            final String name;
            int  failCount = 0;
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
                for (TranslationEngine eng : engines) {
                    if (eng.isCoolingDown()) continue;
                    if (eng.requiresNetwork() && !netAvailable) continue;
                    try {
                        String result = eng.translate(text, from, to);
                        if (result == null || result.isEmpty()) throw new Exception("empty");
                        eng.resetFails();
                        cb.onSuccess(result, eng.name);
                        return;
                    } catch (Exception e) {
                        Log.w("GT-Tr", "[" + eng.name + "] fail: " + e.getMessage());
                        eng.recordFail();
                    }
                }
                cb.onFailure("all_failed");
            });
        }
    }


    // ═════════════════════════════════════════════════════════════════
    // ImagePreprocessor (inner static — no context needed)
    // ═════════════════════════════════════════════════════════════════

    static class ImagePreprocessor {
        static Bitmap process(Bitmap src) {
            if (src == null || src.isRecycled()) return src;
            try {
                // Scale up small crops for better OCR
                Bitmap scaled = src;
                if (src.getWidth() < 200 || src.getHeight() < 60) {
                    float f = Math.max(200f / src.getWidth(), 60f / src.getHeight());
                    scaled = Bitmap.createScaledBitmap(src,
                        Math.round(src.getWidth() * f), Math.round(src.getHeight() * f), true);
                }

                // Grayscale + contrast boost
                Bitmap gray = Bitmap.createBitmap(scaled.getWidth(), scaled.getHeight(),
                    Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(gray);
                Paint  p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(0f);
                float[] contrast = {
                    1.6f,0,0,0,-80, 0,1.6f,0,0,-80, 0,0,1.6f,0,-80, 0,0,0,1,0
                };
                android.graphics.ColorMatrix cc = new android.graphics.ColorMatrix(contrast);
                cm.postConcat(cc);
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                c.drawBitmap(scaled, 0, 0, p);
                if (scaled != src) scaled.recycle();

                // Sharpen
                Bitmap sharp = applySharpen(gray);
                if (sharp != gray) gray.recycle();
                return sharp;
            } catch (Exception e) {
                Log.w("GT-Prep", "preprocess failed: " + e.getMessage());
                return src;
            }
        }

        private static Bitmap applySharpen(Bitmap src) {
            try {
                Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                    Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                c.drawBitmap(src, 0, 0, null);
                Paint p = new Paint();
                p.setAlpha(60);
                android.graphics.ColorMatrix inv = new android.graphics.ColorMatrix(new float[]{
                    -1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0
                });
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(inv));
                p.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SCREEN));
                c.drawBitmap(src, 0, 0, p);
                return out;
            } catch (Exception e) { return src; }
        }
    }


    // ═════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════

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

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void resetBtn(String label) {
        if (destroyed || tvBtnLabel == null || btnView == null) return;
        tvBtnLabel.setText(label);
        btnView.animate().alpha(ALPHA_IDLE).setDuration(300).start();
        scheduleBtnFade();
    }

    private void scheduleBtnFade() {
        if (fadeOutR != null) H.removeCallbacks(fadeOutR);
        fadeOutR = () -> {
            if (!ocrBusy.get() && !translating.get()
                    && !bubbleOverlay.isVisible() && !selectionMode && btnView != null)
                btnView.animate().alpha(ALPHA_IDLE).setDuration(800).start();
        };
        H.postDelayed(fadeOutR, 3_000);
    }

    private void requestBatteryExemptionOnce() {
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


    // ═════════════════════════════════════════════════════════════════
    // Debug crop save
    // ═════════════════════════════════════════════════════════════════

    private void saveDebugCrop(Bitmap bmp) {
        if (!DEBUG_CROP || bmp == null || bmp.isRecycled()) return;
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "gt_crop_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Log.d(TAG, "DEBUG crop → " + f.getAbsolutePath());
        } catch (Exception e) { Log.w(TAG, "DEBUG crop save: " + e.getMessage()); }
    }


    // ═════════════════════════════════════════════════════════════════
    // Notification
    // ═════════════════════════════════════════════════════════════════

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

    private Notification buildNotif() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(isAr ? "مترجم الألعاب" : "Game Translator")
            .setContentText(isAr
                ? "✏️ للتحديد  |  ضغطتين/ضغطة طويلة = اللغة"
                : "✏️ to select  |  Double-tap/Hold = language")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
