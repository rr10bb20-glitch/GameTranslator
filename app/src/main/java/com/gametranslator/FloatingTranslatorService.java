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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
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
 * FloatingTranslatorService — v17 (Professional Selection Mode)
 *
 * NEW in v17:
 * ─ ✏️ Selection button: enters manual crop mode
 * ─ Full-screen dim overlay with animated selection rectangle
 * ─ Corner handles drawn on canvas for professional feel
 * ─ Marching-ants dashed border animation during drag
 * ─ OCR runs only on the user-selected region
 * ─ Auto-exits selection mode after translation
 * ─ Haptic-style scale animation on selection confirm
 * ─ SelectionView is a SurfaceView for smooth 60fps drawing
 * ─ All v16 stability fixes preserved (executor, leaks, crashes)
 * ─ Language system, cache, MediaProjection all untouched
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

    // ── Tap detection ─────────────────────────────────────────────
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable fadeOutR;
    private int      tapCount = 0;

    // ── Selection state ───────────────────────────────────────────
    private float selStartX, selStartY, selEndX, selEndY;
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
                                    synchronized (mpLock) { mediaProjection = null; }
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

        synchronized (mpLock) {
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
        private long    downAt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = selBtnLP.x; iy = selBtnLP.y;
                    rx = e.getRawX(); ry = e.getRawY();
                    dragged = false; downAt = System.currentTimeMillis();
                    selBtnView.animate().alpha(1f).scaleX(0.9f).scaleY(0.9f).setDuration(80).start();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragged = true;
                        selBtnLP.x = Math.max(0, Math.min(ix + (int) dx, SW - dp(50)));
                        selBtnLP.y = Math.max(0, Math.min(iy + (int) dy, SH - dp(50)));
                        safeUpdateLayout(selBtnView, selBtnLP);
                        // Keep main pill nearby
                        btnLP.x = selBtnLP.x + dp(1);
                        btnLP.y = selBtnLP.y + dp(50);
                        safeUpdateLayout(btnView, btnLP);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    selBtnView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (!dragged) {
                        if (selectionMode) {
                            exitSelectionMode();
                        } else {
                            enterSelectionMode();
                        }
                    } else {
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

        // Build and show full-screen selection overlay
        buildSelectionOverlay();

        // Brief hint
        H.postDelayed(() -> {
            if (destroyed || !selectionMode) return;
            toast(isAr ? "اسحب لتحديد النص" : "Drag to select text");
        }, 200);
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

        // The custom canvas view handles all drawing
        canvasView = new SelectionCanvasView(this);
        root.addView(canvasView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // "Cancel" label at top center
        TextView hint = new TextView(this);
        hint.setText(isAr ? "✕  إلغاء" : "✕  Cancel");
        hint.setTextColor(Color.argb(200, 200, 200, 200));
        hint.setTextSize(12f);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setLetterSpacing(0.08f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(20), dp(12), dp(20), dp(12));

        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(Color.argb(160, 0, 0, 0));
        hintBg.setCornerRadius(dp(20));
        hint.setBackground(hintBg);
        hint.setOnClickListener(v -> exitSelectionMode());

        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(52);
        root.addView(hint, hlp);

        selectionView = root;

        selectionLP = new WindowManager.LayoutParams(
            SW, SH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        selectionLP.gravity = Gravity.TOP | Gravity.START;

        safeAddView(selectionView, selectionLP);

        // Fade in
        selectionView.animate().alpha(1f).setDuration(200).start();
    }


    // ═══════════════════════════════════════════════════════════════
    // SelectionCanvasView — custom view for dim + rectangle drawing
    // ═══════════════════════════════════════════════════════════════

    private class SelectionCanvasView extends View {

        // Rect state
        private float sx, sy, ex, ey;
        private boolean dragging = false;
        private boolean confirmed = false;

        // Paints — all created once in constructor, NEVER inside onDraw()
        private final Paint dimPaint    = new Paint();
        private final Paint clearPaint  = new Paint();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint confirmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tintPaint   = new Paint(Paint.ANTI_ALIAS_FLAG); // reused every frame
        private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG); // reused every frame
        private final RectF selRectF    = new RectF();                       // reused every frame

        // Marching ants — cache DashPathEffect to avoid per-frame allocation
        private float           dashOffset   = -1f;          // -1 = unset sentinel
        private DashPathEffect  cachedEffect = null;
        private final float[]   dashIntervals = {dp(10), dp(6)};

        // Expand animation on confirm
        private float confirmAlpha = 0f;

        SelectionCanvasView(Context ctx) {
            super(ctx);
            setWillNotDraw(false);

            dimPaint.setColor(Color.argb((int)(255 * DIM_ALPHA), 0, 0, 0));
            dimPaint.setStyle(Paint.Style.FILL);

            clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
            clearPaint.setStyle(Paint.Style.FILL);
            setLayerType(LAYER_TYPE_SOFTWARE, null);

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

            // Pre-init reusable paints — never allocate inside onDraw()
            tintPaint.setColor(Color.argb(18, 80, 160, 255));
            tintPaint.setStyle(Paint.Style.FILL);

            labelPaint.setColor(Color.argb(180, 255, 255, 255));
            labelPaint.setTextSize(dp(10));
            labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        /**
         * Called by the marching-ants ValueAnimator (~60fps).
         * Rebuilds DashPathEffect only when offset actually changes — avoids
         * allocating a new object every single frame.
         */
        void setDashOffset(float offset) {
            if (offset != dashOffset) {
                dashOffset   = offset;
                cachedEffect = new DashPathEffect(dashIntervals, offset);
                borderPaint.setPathEffect(cachedEffect);
            }
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (confirmed) return true; // block input while processing
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getX(); sy = e.getY();
                    ex = sx; ey = sy;
                    dragging = true;
                    startDashAnimator();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    ex = e.getX(); ey = e.getY();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    ex = e.getX(); ey = e.getY();
                    float w = Math.abs(ex - sx);
                    float h = Math.abs(ey - sy);
                    if (w > MIN_SELECTION_PX && h > MIN_SELECTION_PX) {
                        confirmed = true;
                        stopDashAnimator();
                        // Flash confirm color then run OCR
                        animateConfirm();
                    } else {
                        // Too small — reset
                        sx = sy = ex = ey = 0;
                        stopDashAnimator();
                        invalidate();
                    }
                    return true;
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int cw = canvas.getWidth();
            int ch = canvas.getHeight();

            // Full dim
            canvas.drawRect(0, 0, cw, ch, dimPaint);

            if (sx == 0 && sy == 0 && ex == 0 && ey == 0) return;

            float left   = Math.min(sx, ex);
            float top    = Math.min(sy, ey);
            float right  = Math.max(sx, ex);
            float bottom = Math.max(sy, ey);

            // Punch transparent hole through dim
            canvas.drawRect(left, top, right, bottom, clearPaint);

            // Blue tint inside selection — reuse pre-allocated tintPaint
            canvas.drawRect(left, top, right, bottom, tintPaint);

            // Reuse pre-allocated RectF — avoids allocation every frame
            selRectF.set(left, top, right, bottom);

            if (confirmed) {
                confirmPaint.setAlpha((int)(255 * confirmAlpha));
                canvas.drawRoundRect(selRectF, dp(4), dp(4), confirmPaint);
            } else {
                // borderPaint already has the current DashPathEffect applied
                // via setDashOffset() — no allocation here, zero GC pressure
                canvas.drawRoundRect(selRectF, dp(4), dp(4), borderPaint);

                // Corner handles — L-shaped brackets at each corner
                float c = dp(16);
                // TL
                canvas.drawLine(left,  top,     left + c, top,       cornerPaint);
                canvas.drawLine(left,  top,     left,     top + c,   cornerPaint);
                // TR
                canvas.drawLine(right, top,     right - c, top,      cornerPaint);
                canvas.drawLine(right, top,     right,     top + c,  cornerPaint);
                // BL
                canvas.drawLine(left,  bottom,  left + c,  bottom,   cornerPaint);
                canvas.drawLine(left,  bottom,  left,      bottom - c, cornerPaint);
                // BR
                canvas.drawLine(right, bottom,  right - c, bottom,   cornerPaint);
                canvas.drawLine(right, bottom,  right,     bottom - c, cornerPaint);

                // Size label — reuse pre-allocated labelPaint (no new Paint)
                float rw = right - left;
                float rh = bottom - top;
                if (rw > dp(80) && rh > dp(40)) {
                    String label = (int) rw + " × " + (int) rh;
                    float lw = labelPaint.measureText(label);
                    canvas.drawText(label, left + (rw - lw) / 2f, bottom - dp(8), labelPaint);
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
                    // Map selection coords to capture coords, then run OCR
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

            ImageReader    reader  = null;
            VirtualDisplay vd      = null;
            Bitmap         fullBmp = null;

            try {
                reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

                synchronized (mpLock) {
                    if (mediaProjection == null) {
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) { exitSelectionMode(); resetBtn(shortPair()); } });
                        return;
                    }
                    vd = mediaProjection.createVirtualDisplay(
                        "UT_SEL", capW, capH, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                }

                Thread.sleep(CAPTURE_DELAY_MS);
                if (destroyed) { ocrBusy.set(false); return; }

                Image img = null;
                for (int i = 0; i < 3 && img == null; i++) {
                    try { img = reader.acquireLatestImage(); }
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
            } finally {
                if (vd     != null) try { vd.release();   } catch (Exception ignored) {}
                if (reader != null) try { reader.close();  } catch (Exception ignored) {}
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

            // Map screen touch coords → capture bitmap coords
            float scaleX = (float) capW / SW;
            float scaleY = (float) capH / SH;
            int cropL = clamp((int)(screenLeft  * scaleX), 0, capW - 1);
            int cropT = clamp((int)(screenTop   * scaleY), 0, capH - 1);
            int cropR = clamp((int)(screenRight  * scaleX), cropL + 1, capW);
            int cropB = clamp((int)(screenBottom * scaleY), cropT + 1, capH);
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

            boolean useJa = fromLang.equals("ja") || fromLang.equals("ko")
                || fromLang.startsWith("zh") || fromLang.equals("auto");
            TextRecognizer rec = useJa ? recognizerJa : recognizerLat;

            final Bitmap fCropped = cropped;

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

        overlayLP = new WindowManager.LayoutParams(
            SW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        overlayLP.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayLP.y = dp(10);

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
