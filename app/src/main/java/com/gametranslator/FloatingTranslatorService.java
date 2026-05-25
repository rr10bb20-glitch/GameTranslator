package com.gametranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import android.os.PowerManager;
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
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FloatingTranslatorService — v12 (Final Stable)
 *
 * Fixes vs v11:
 * ─ Capture resolution halved (SW/2 × SH/2) → less RAM, faster OCR
 * ─ WakeLock acquired during capture → prevents sleep-mid-capture on some phones
 * ─ ImageReader buffer = 1 (was 2) → avoids stale-frame issues
 * ─ CAPTURE_DELAY_MS raised to 350 for slower devices
 * ─ AUTO_INTERVAL_MS raised to 1 800 → lower CPU load
 * ─ ocrBusy reset guaranteed on EVERY exit path (even async failures)
 * ─ All wm.addView / updateViewLayout / removeView wrapped in try-catch
 * ─ BadTokenException caught explicitly
 * ─ Overlay FLAG_NOT_TOUCHABLE when hidden → never blocks game input
 * ─ MediaProjection callback registered with H (main thread) → safe UI updates
 * ─ No OCR started in onCreate / onStartCommand — only on user gesture
 * ─ Bitmap.recycle() always null-checked & isRecycled()-checked
 * ─ Translation: 2-attempt retry with 700 ms back-off
 * ─ Translation cache size = 60 entries
 * ─ All network ops on executor (never blocks main thread)
 * ─ Logcat tags every failure with full stack
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "UT";
    private static final String CHANNEL_ID = "ut_v12";
    private static final int    NOTIF_ID   = 1;

    // ── Timing ──────────────────────────────────────────────────
    private static final long DISMISS_MS       = 6_000;
    private static final long LONG_PRESS_MS    = 650;
    private static final long DOUBLE_TAP_MS    = 300;
    private static final long CAPTURE_DELAY_MS = 350;   // wait for VirtualDisplay to fill
    private static final long AUTO_INTERVAL_MS = 1_800; // poll every 1.8 s (was 1.2)

    // ── Alpha ────────────────────────────────────────────────────
    private static final float ALPHA_IDLE = 0.25f;
    private static final float ALPHA_BUSY = 0.92f;

    // ── OCR crop (lower portion = dialogue box) ──────────────────
    private static final float OCR_TOP    = 0.58f;
    private static final float OCR_BOTTOM = 0.93f;

    private static final int CACHE_SIZE = 60;

    // ── Language table ───────────────────────────────────────────
    private static final String[][] LANGS = {
        {"auto","Auto","?"},
        {"ar","Arabic","AR"},{"en","English","EN"},{"ja","Japanese","JA"},
        {"ko","Korean","KO"},{"zh-CN","Chinese","ZH"},{"zh-TW","Chinese TW","ZH"},
        {"fr","French","FR"},{"de","German","DE"},{"es","Spanish","ES"},
        {"ru","Russian","RU"},{"tr","Turkish","TR"},{"it","Italian","IT"},
        {"pt","Portuguese","PT"},{"hi","Hindi","HI"},{"af","Afrikaans","AF"},
        {"sq","Albanian","SQ"},{"hy","Armenian","HY"},{"az","Azerbaijani","AZ"},
        {"be","Belarusian","BE"},{"bs","Bosnian","BS"},{"bg","Bulgarian","BG"},
        {"ca","Catalan","CA"},{"hr","Croatian","HR"},{"cs","Czech","CS"},
        {"da","Danish","DA"},{"nl","Dutch","NL"},{"et","Estonian","ET"},
        {"fi","Finnish","FI"},{"gl","Galician","GL"},{"ka","Georgian","KA"},
        {"el","Greek","EL"},{"hu","Hungarian","HU"},{"is","Icelandic","IS"},
        {"ga","Irish","GA"},{"lv","Latvian","LV"},{"lt","Lithuanian","LT"},
        {"lb","Luxembourgish","LB"},{"mk","Macedonian","MK"},{"mt","Maltese","MT"},
        {"no","Norwegian","NO"},{"pl","Polish","PL"},{"ro","Romanian","RO"},
        {"sr","Serbian","SR"},{"sk","Slovak","SK"},{"sl","Slovenian","SL"},
        {"sv","Swedish","SV"},{"uk","Ukrainian","UK"},{"cy","Welsh","CY"},
        {"fy","Frisian","FY"},{"co","Corsican","CO"},{"br","Breton","BR"},
        {"gd","Scots Gaelic","GD"},{"la","Latin","LA"},{"eo","Esperanto","EO"},
        {"bn","Bengali","BN"},{"my","Burmese","MY"},{"ceb","Cebuano","CEB"},
        {"gu","Gujarati","GU"},{"hmn","Hmong","HMN"},{"id","Indonesian","ID"},
        {"jw","Javanese","JW"},{"kn","Kannada","KN"},{"kk","Kazakh","KK"},
        {"km","Khmer","KM"},{"ky","Kyrgyz","KY"},{"lo","Lao","LO"},
        {"ms","Malay","MS"},{"ml","Malayalam","ML"},{"mr","Marathi","MR"},
        {"mn","Mongolian","MN"},{"ne","Nepali","NE"},{"or","Odia","OR"},
        {"pa","Punjabi","PA"},{"si","Sinhala","SI"},{"su","Sundanese","SU"},
        {"tg","Tajik","TG"},{"ta","Tamil","TA"},{"te","Telugu","TE"},
        {"th","Thai","TH"},{"tl","Filipino","TL"},{"tk","Turkmen","TK"},
        {"ug","Uyghur","UG"},{"ur","Urdu","UR"},{"uz","Uzbek","UZ"},
        {"vi","Vietnamese","VI"},{"tt","Tatar","TT"},{"fa","Persian","FA"},
        {"he","Hebrew","HE"},{"ku","Kurdish","KU"},{"ps","Pashto","PS"},
        {"sd","Sindhi","SD"},{"yi","Yiddish","YI"},{"am","Amharic","AM"},
        {"ha","Hausa","HA"},{"ig","Igbo","IG"},{"rw","Kinyarwanda","RW"},
        {"mg","Malagasy","MG"},{"ny","Chichewa","NY"},{"om","Oromo","OM"},
        {"sn","Shona","SN"},{"so","Somali","SO"},{"st","Sesotho","ST"},
        {"sw","Swahili","SW"},{"ti","Tigrinya","TI"},{"xh","Xhosa","XH"},
        {"yo","Yoruba","YO"},{"zu","Zulu","ZU"},{"ee","Ewe","EE"},
        {"lg","Luganda","LG"},{"ln","Lingala","LN"},{"bm","Bambara","BM"},
        {"wo","Wolof","WO"},{"ht","Haitian Creole","HT"},{"qu","Quechua","QU"},
        {"gn","Guarani","GN"},{"ay","Aymara","AY"},{"haw","Hawaiian","HAW"},
        {"mi","Maori","MI"},{"sm","Samoan","SM"},
    };

    // ── Core ─────────────────────────────────────────────────────
    private WindowManager            wm;
    private int                      SW, SH;           // real screen size
    private int                      capW, capH;       // capture size (halved)
    private final Handler            H = new Handler(Looper.getMainLooper());
    private ExecutorService          executor;
    private LruCache<String, String> translateCache;
    private PowerManager.WakeLock    wakeLock;

    // ── Views ─────────────────────────────────────────────────────
    private View     btnView;
    private View     overlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, overlayLP;
    private GradientDrawable           btnBg;
    private TextView                   tvBtnLabel;
    private View                       autoDot;
    private TextView                   tvTranslation;
    private TextView                   tvOriginal;
    private TextView                   tvLangPair;

    // ── State ─────────────────────────────────────────────────────
    private final AtomicBoolean ocrBusy    = new AtomicBoolean(false);
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private volatile boolean overlayVisible = false;
    private volatile boolean autoMode       = false;
    private volatile boolean destroyed      = false;
    private volatile boolean viewsAdded     = false;   // guard for wm ops

    private volatile String fromLang  = "auto";
    private volatile String toLang    = "ar";
    private String          pickerFrom = "auto";

    // ── MediaProjection ───────────────────────────────────────────
    private final Object           mpLock = new Object();
    private MediaProjection        mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer         recognizerJa;
    private TextRecognizer         recognizerLat;

    // ── AUTO MODE ─────────────────────────────────────────────────
    private Runnable autoLoopR;
    private String   lastAutoText = "";

    // ── Timers / tap detection ────────────────────────────────────
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

        // startForeground IMMEDIATELY (< 5 s rule — ANR guard)
        createChannel();
        startForeground(NOTIF_ID, buildNotif());

        wm        = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // Real screen dimensions
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                SW = b.width();  SH = b.height();
            } else {
                DisplayMetrics dm = new DisplayMetrics();
                //noinspection deprecation
                wm.getDefaultDisplay().getMetrics(dm);
                SW = dm.widthPixels; SH = dm.heightPixels;
            }
        } catch (Exception e) {
            Log.e(TAG, "getDisplayMetrics: " + e.getMessage(), e);
            SW = 1080; SH = 1920;
        }

        // ── Capture at half resolution (big performance win) ──────
        capW = SW / 2;
        capH = SH / 2;
        // Keep capture size divisible by 16 (some devices require this)
        capW = (capW / 16) * 16;
        capH = (capH / 16) * 16;
        if (capW < 480) capW = 480;
        if (capH < 640) capH = 640;
        Log.d(TAG, "Screen: " + SW + "x" + SH + "  Capture: " + capW + "x" + capH);

        fromLang = ClipboardBridge.readFromLang(this);
        toLang   = ClipboardBridge.readToLang(this);

        // WakeLock — prevents CPU sleep during capture on some devices
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "UT:capture");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock init failed: " + e.getMessage());
        }

        // OCR recognizers
        try {
            recognizerJa  = TextRecognition.getClient(
                new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Exception e) {
            Log.e(TAG, "OCR init failed: " + e.getMessage(), e);
            recognizerJa = recognizerLat = null;
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UT-Worker");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        translateCache = new LruCache<>(CACHE_SIZE);

        try {
            buildButton();
            buildOverlay();
            viewsAdded = true;
        } catch (Exception e) {
            Log.e(TAG, "UI build failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("mp_result_code")) {
            int    rc = intent.getIntExtra("mp_result_code", -1);
            Intent d  = intent.getParcelableExtra("mp_data");

            if (rc == android.app.Activity.RESULT_OK && d != null) {
                synchronized (mpLock) {
                    // Stop old projection if any
                    if (mediaProjection != null) {
                        try { mediaProjection.stop(); } catch (Exception ignored) {}
                        mediaProjection = null;
                    }
                    try {
                        mediaProjection = mpManager.getMediaProjection(rc, d);
                        // Callback on main thread — safe to do UI updates inside
                        mediaProjection.registerCallback(new MediaProjection.Callback() {
                            @Override public void onStop() {
                                Log.w(TAG, "MediaProjection stopped by system");
                                synchronized (mpLock) { mediaProjection = null; }
                                ocrBusy.set(false);
                                releaseWakeLock();
                                H.post(() -> {
                                    if (!destroyed) resetBtn(shortPair());
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
                Log.w(TAG, "MediaProjection not granted (rc=" + rc + ")");
                H.post(() -> {
                    if (!destroyed)
                        Toast.makeText(this,
                            "Screen permission needed for OCR",
                            Toast.LENGTH_LONG).show();
                });
            }
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        destroyed = true;

        H.removeCallbacksAndMessages(null);
        stopAutoInternal();
        releaseWakeLock();

        if (executor != null) executor.shutdownNow();

        try { if (recognizerJa  != null) recognizerJa.close();  }
        catch (Exception e) { Log.e(TAG, "recognizerJa close: " + e); }
        try { if (recognizerLat != null) recognizerLat.close(); }
        catch (Exception e) { Log.e(TAG, "recognizerLat close: " + e); }

        synchronized (mpLock) {
            if (mediaProjection != null) {
                try { mediaProjection.stop(); }
                catch (Exception e) { Log.e(TAG, "mp stop: " + e); }
                mediaProjection = null;
            }
        }

        if (viewsAdded) {
            safeRemove(btnView);
            safeRemove(overlayView);
            safeRemove(pickerView);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Floating Button
    // ═════════════════════════════════════════════════════════════

    private void buildButton() {
        FrameLayout root = new FrameLayout(this);
        FrameLayout pill = new FrameLayout(this);

        btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setCornerRadius(dp(14));
        btnBg.setColor(Color.argb(200, 10, 20, 50));
        btnBg.setStroke(dp(1), Color.argb(120, 60, 120, 220));
        pill.setBackground(btnBg);
        pill.setElevation(dp(8));

        tvBtnLabel = new TextView(this);
        tvBtnLabel.setText(shortPair());
        tvBtnLabel.setTextColor(Color.argb(230, 160, 200, 255));
        tvBtnLabel.setTextSize(10f);
        tvBtnLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tvBtnLabel.setLetterSpacing(0.05f);
        tvBtnLabel.setGravity(Gravity.CENTER);
        tvBtnLabel.setIncludeFontPadding(false);
        pill.addView(tvBtnLabel, matchFLP());

        autoDot = new View(this);
        int ds = dp(6);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ds, ds);
        dlp.gravity = Gravity.TOP | Gravity.END;
        dlp.setMargins(0, dp(3), dp(3), 0);
        GradientDrawable db = new GradientDrawable();
        db.setShape(GradientDrawable.OVAL);
        db.setColor(0xFF00E676);
        autoDot.setBackground(db);
        autoDot.setVisibility(View.GONE);
        pill.addView(autoDot, dlp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(52), dp(28));
        plp.setMargins(dp(4), dp(4), dp(4), dp(4));
        root.addView(pill, plp);
        btnView = root;
        btnView.setAlpha(ALPHA_IDLE);

        btnLP = new WindowManager.LayoutParams(
            wc(), wc(), overlayType(),
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

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        tvLangPair = new TextView(this);
        tvLangPair.setText(pairText());
        tvLangPair.setTextColor(Color.argb(160, 80, 130, 220));
        tvLangPair.setTextSize(9f);
        tvLangPair.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangPair.setLetterSpacing(0.06f);
        topRow.addView(tvLangPair,
            new LinearLayout.LayoutParams(0, wc(), 1f));

        TextView xBtn = new TextView(this);
        xBtn.setText("  \u00D7  ");
        xBtn.setTextColor(Color.argb(180, 200, 80, 80));
        xBtn.setTextSize(16f);
        xBtn.setClickable(true);
        xBtn.setFocusable(true);
        xBtn.setOnClickListener(v -> hideOverlay());
        topRow.addView(xBtn);
        card.addView(topRow);

        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, dp(4), 0, dp(5));
        div.setBackgroundColor(Color.argb(60, 40, 80, 180));
        card.addView(div, divLp);

        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.argb(100, 140, 170, 220));
        tvOriginal.setTextSize(9.5f);
        tvOriginal.setMaxLines(1);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(3));
        card.addView(tvOriginal);

        tvTranslation = new TextView(this);
        tvTranslation.setTextColor(Color.argb(240, 220, 235, 255));
        tvTranslation.setTextSize(17f);
        tvTranslation.setTypeface(Typeface.DEFAULT_BOLD);
        tvTranslation.setLineSpacing(dp(1), 1.15f);
        tvTranslation.setMaxLines(3);
        tvTranslation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvTranslation);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(mp(), wc());
        cardLp.setMargins(dp(10), 0, dp(10), dp(8));
        root.addView(card, cardLp);

        overlayView = root;
        overlayView.setAlpha(0f);

        // KEY: FLAG_NOT_TOUCHABLE when hidden → overlay never blocks game input
        overlayLP = new WindowManager.LayoutParams(
            SW, wc(), overlayType(),
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
        overlayVisible = true;
        // Remove NOT_TOUCHABLE so close-button works
        overlayLP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        safeUpdateLayout(overlayView, overlayLP);
        overlayView.animate().alpha(1f).setDuration(180).start();
    }

    private void hideOverlay() {
        cancelDismiss();
        if (destroyed || overlayView == null) return;
        overlayView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (destroyed) return;
            overlayVisible = false;
            // Restore NOT_TOUCHABLE → overlay is invisible and passthrough
            overlayLP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            safeUpdateLayout(overlayView, overlayLP);
        }).start();
        if (btnView != null)
            btnView.animate().alpha(ALPHA_IDLE).setDuration(300).start();
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
    // AUTO MODE
    // ═════════════════════════════════════════════════════════════

    private void startAuto() {
        if (autoMode) return;
        autoMode    = true;
        lastAutoText = "";
        if (autoDot != null) autoDot.setVisibility(View.VISIBLE);
        Toast.makeText(this, "AUTO ON", Toast.LENGTH_SHORT).show();
        scheduleAutoCapture();
    }

    private void stopAutoInternal() {
        autoMode = false;
        if (autoLoopR != null) {
            H.removeCallbacks(autoLoopR);
            autoLoopR = null;
        }
    }

    private void stopAuto() {
        stopAutoInternal();
        if (autoDot != null) autoDot.setVisibility(View.GONE);
        Toast.makeText(this, "AUTO OFF", Toast.LENGTH_SHORT).show();
        hideOverlay();
    }

    private void scheduleAutoCapture() {
        if (destroyed || !autoMode) return;
        autoLoopR = () -> {
            if (destroyed || !autoMode) return;
            if (!ocrBusy.get() && !translating.get()) {
                doOCRInternal(true /* silent */);
            }
            if (!destroyed && autoMode) scheduleAutoCapture();
        };
        H.postDelayed(autoLoopR, AUTO_INTERVAL_MS);
    }

    // ═════════════════════════════════════════════════════════════
    // OCR
    // ═════════════════════════════════════════════════════════════

    private void doOCR() { doOCRInternal(false); }

    /**
     * Core OCR method.
     *
     * @param silent true = AUTO MODE (no "Reading…" UI, skip if same text)
     *
     * Resource lifecycle (always guaranteed):
     *   VirtualDisplay  → released in finally
     *   ImageReader     → closed in finally
     *   Bitmap          → recycled after OCR or on every failure
     *   ocrBusy         → reset on every exit path
     *   WakeLock        → released after frame acquired
     */
    private void doOCRInternal(final boolean silent) {
        // Debounce — only one capture at a time
        if (!ocrBusy.compareAndSet(false, true)) {
            Log.d(TAG, "OCR debounced");
            return;
        }

        // Check MediaProjection under lock
        synchronized (mpLock) {
            if (mediaProjection == null) {
                ocrBusy.set(false);
                if (!silent) {
                    H.post(() -> {
                        if (destroyed) return;
                        Toast.makeText(this,
                            "Need screen permission — reopen app",
                            Toast.LENGTH_SHORT).show();
                        try {
                            Intent i = new Intent(this, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(i);
                        } catch (Exception ignored) {}
                    });
                }
                return;
            }
        }

        if (recognizerJa == null || recognizerLat == null) {
            ocrBusy.set(false);
            if (!silent)
                H.post(() -> {
                    if (!destroyed)
                        Toast.makeText(this, "OCR not ready", Toast.LENGTH_SHORT).show();
                });
            return;
        }

        // Show "reading" UI for manual captures
        if (!silent) {
            H.post(() -> {
                if (destroyed) return;
                if (tvBtnLabel   != null) tvBtnLabel.setText("...");
                if (btnView      != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
                if (tvTranslation!= null) {
                    tvTranslation.setText("Reading screen\u2026");
                    tvTranslation.setTextColor(Color.argb(160, 140, 170, 220));
                }
                if (tvOriginal  != null) tvOriginal.setText("");
                if (tvLangPair  != null) tvLangPair.setText(pairText());
                showOverlay();
            });
        }

        final int density = getResources().getDisplayMetrics().densityDpi;
        final WeakReference<FloatingTranslatorService> weakSelf = new WeakReference<>(this);

        executor.submit(() -> {
            FloatingTranslatorService self = weakSelf.get();
            if (self == null || self.destroyed) { ocrBusy.set(false); return; }

            // Acquire WakeLock for the duration of the capture
            try { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(5_000); }
            catch (Exception e) { Log.w(TAG, "WakeLock acquire: " + e.getMessage()); }

            ImageReader  reader  = null;
            VirtualDisplay vd   = null;
            Bitmap         fullBmp = null;

            try {
                // Buffer = 1 → always get the freshest frame, never a stale one
                reader = ImageReader.newInstance(
                    capW, capH, PixelFormat.RGBA_8888, 1);

                synchronized (mpLock) {
                    if (mediaProjection == null) {
                        ocrBusy.set(false);
                        releaseWakeLock();
                        H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                        return;
                    }
                    vd = mediaProjection.createVirtualDisplay(
                        "UT_OCR", capW, capH, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                }

                // Wait for VirtualDisplay to render first frame
                Thread.sleep(CAPTURE_DELAY_MS);

                if (destroyed) { ocrBusy.set(false); return; }

                // Acquire frame — try up to 3 times with short back-off
                Image img = null;
                for (int attempt = 0; attempt < 3 && img == null; attempt++) {
                    try {
                        img = reader.acquireLatestImage();
                    } catch (Exception e) {
                        Log.w(TAG, "acquireLatestImage attempt " + attempt + ": " + e.getMessage());
                    }
                    if (img == null && attempt < 2) {
                        try { Thread.sleep(80); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                releaseWakeLock(); // Release as soon as we have the frame

                if (img != null) {
                    try {
                        Image.Plane[] planes = img.getPlanes();
                        ByteBuffer buf   = planes[0].getBuffer();
                        int rStride      = planes[0].getRowStride();
                        int pStride      = planes[0].getPixelStride();
                        int bmpW         = rStride / pStride;
                        Bitmap tmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
                        tmp.copyPixelsFromBuffer(buf);
                        // Crop to actual capture width
                        fullBmp = Bitmap.createBitmap(
                            tmp, 0, 0, Math.min(capW, bmpW), capH);
                        if (tmp != fullBmp) recycleSafe(tmp);
                    } catch (Exception e) {
                        Log.e(TAG, "Bitmap copy: " + e.getMessage(), e);
                    } finally {
                        try { img.close(); } catch (Exception ignored) {}
                    }
                } else {
                    Log.w(TAG, "acquireLatestImage: null after 3 attempts");
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "OCR capture interrupted");
                ocrBusy.set(false);
                releaseWakeLock();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Capture error: " + e.getMessage(), e);
            } finally {
                // ALWAYS release VirtualDisplay and ImageReader
                if (vd     != null) { try { vd.release();    } catch (Exception e) { Log.e(TAG, "vd.release: " + e); } }
                if (reader != null) { try { reader.close();   } catch (Exception e) { Log.e(TAG, "reader.close: " + e); } }
                releaseWakeLock();
            }

            if (fullBmp == null) {
                ocrBusy.set(false);
                if (!silent) {
                    H.post(() -> {
                        if (destroyed) return;
                        resetBtn(shortPair());
                        if (tvTranslation != null) {
                            tvTranslation.setText("Capture failed — try again");
                            tvTranslation.setTextColor(Color.argb(220, 255, 180, 80));
                        }
                    });
                }
                return;
            }

            // Crop to dialogue region (lower portion of screen)
            int cropTop = (int)(capH * OCR_TOP);
            int cropH   = (int)(capH * OCR_BOTTOM) - cropTop;
            cropH = Math.max(cropH, 1);
            Bitmap cropped = null;
            try {
                cropped = Bitmap.createBitmap(fullBmp, 0, cropTop, capW, cropH);
            } catch (Exception e) {
                Log.e(TAG, "Bitmap crop: " + e.getMessage(), e);
                recycleSafe(fullBmp);
                ocrBusy.set(false);
                return;
            }

            // Choose recognizer: Japanese model for CJK, Latin for everything else
            boolean useJa = fromLang.equals("ja") || fromLang.equals("ko")
                || fromLang.startsWith("zh") || fromLang.equals("auto");
            TextRecognizer rec = useJa ? recognizerJa : recognizerLat;

            final Bitmap fFull    = fullBmp;
            final Bitmap fCropped = cropped;
            final boolean fSilent = silent;

            rec.process(InputImage.fromBitmap(fCropped, 0))
                .addOnSuccessListener(result -> {
                    recycleSafe(fCropped);
                    String text = result.getText().trim();
                    if (!text.isEmpty()) {
                        recycleSafe(fFull);
                        ocrBusy.set(false);
                        if (!fSilent)
                            H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                        handleOCRResult(text, fSilent);
                    } else {
                        // Fallback: full-screen OCR
                        rec.process(InputImage.fromBitmap(fFull, 0))
                            .addOnSuccessListener(r2 -> {
                                recycleSafe(fFull);
                                ocrBusy.set(false);
                                if (!fSilent)
                                    H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                                String t2 = r2.getText().trim();
                                if (!t2.isEmpty()) {
                                    handleOCRResult(t2, fSilent);
                                } else if (!fSilent) {
                                    H.post(() -> {
                                        if (destroyed) return;
                                        if (tvTranslation != null) {
                                            tvTranslation.setText("No text found on screen");
                                            tvTranslation.setTextColor(
                                                Color.argb(200, 255, 200, 80));
                                        }
                                    });
                                }
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Full OCR failed: " + e.getMessage(), e);
                                recycleSafe(fFull);
                                ocrBusy.set(false);
                                if (!fSilent) H.post(() -> {
                                    if (!destroyed) {
                                        resetBtn(shortPair());
                                        setTranslationError("OCR failed: " + e.getMessage());
                                    }
                                });
                            });
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Crop OCR failed: " + e.getMessage(), e);
                    recycleSafe(fCropped);
                    recycleSafe(fFull);
                    ocrBusy.set(false);
                    if (!fSilent) H.post(() -> {
                        if (!destroyed) {
                            resetBtn(shortPair());
                            setTranslationError("OCR error: " + e.getMessage());
                        }
                    });
                });
        });
    }

    private void handleOCRResult(String text, boolean silent) {
        if (silent) {
            if (text.equals(lastAutoText)) return; // no change
            lastAutoText = text;
        }
        doTranslate(text);
    }

    // ═════════════════════════════════════════════════════════════
    // Translation
    // ═════════════════════════════════════════════════════════════

    private void doTranslate(final String text) {
        if (!translating.compareAndSet(false, true)) {
            Log.d(TAG, "Translation debounced");
            return;
        }
        cancelDismiss();

        // Cache hit?
        String cacheKey = fromLang + "|" + toLang + "|" + text;
        String cached   = translateCache.get(cacheKey);
        if (cached != null) {
            Log.d(TAG, "Cache hit");
            translating.set(false);
            H.post(() -> { if (!destroyed) showTranslationResult(text, cached); });
            return;
        }

        // Show "translating" UI
        H.post(() -> {
            if (destroyed) return;
            if (tvBtnLabel   != null) tvBtnLabel.setText("...");
            if (btnView      != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            String disp = clip(text, 80);
            if (tvOriginal   != null) tvOriginal.setText(disp);
            if (tvTranslation!= null) {
                tvTranslation.setText("Translating\u2026");
                tvTranslation.setTextColor(Color.argb(160, 140, 170, 220));
            }
            if (tvLangPair   != null) tvLangPair.setText(pairText());
            showOverlay();
        });

        final String input    = text.length() > 600 ? text.substring(0, 600) : text;
        final String snapFrom = fromLang;
        final String snapTo   = toLang;
        final String key      = cacheKey;

        executor.submit(() -> {
            if (destroyed) { translating.set(false); return; }
            String    result = null;
            Exception lastEx = null;

            // 2 attempts with 700 ms back-off
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    result = googleTranslate(input, snapFrom, snapTo);
                    break;
                } catch (Exception e) {
                    lastEx = e;
                    Log.w(TAG, "Translate attempt " + (attempt + 1) + ": " + e.getMessage(), e);
                    if (attempt == 0) {
                        try { Thread.sleep(700); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            translating.set(false);
            final String    fResult = result;
            final Exception fErr    = lastEx;

            H.post(() -> {
                if (destroyed) return;
                if (fResult != null) {
                    translateCache.put(key, fResult);
                    showTranslationResult(text, fResult);
                } else {
                    resetBtn(shortPair());
                    String msg = fErr != null ? fErr.getMessage() : "Unknown error";
                    setTranslationError("Error: " + msg);
                }
            });
        });
    }

    private void showTranslationResult(String original, String translated) {
        if (destroyed) return;
        if (tvOriginal   != null) tvOriginal.setText(clip(original, 80));
        if (tvTranslation!= null) {
            tvTranslation.setText(translated);
            tvTranslation.setTextColor(Color.argb(245, 220, 238, 255));
        }
        if (tvLangPair   != null) tvLangPair.setText(pairText());
        resetBtn(shortPair());
        showOverlay();
        if (!autoMode) scheduleDismiss();
        safeUpdateLayout(overlayView, overlayLP);
    }

    private void setTranslationError(String msg) {
        if (tvTranslation != null) {
            tvTranslation.setText(msg);
            tvTranslation.setTextColor(Color.argb(220, 255, 120, 120));
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Language Picker
    // ═════════════════════════════════════════════════════════════

    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog("From language", true, code -> {
            pickerFrom = code;
            H.post(() -> showPickerDialog("To language", false, code2 -> {
                fromLang = pickerFrom;
                toLang   = code2;
                ClipboardBridge.saveLang(this, fromLang, toLang);
                if (tvLangPair  != null) tvLangPair.setText(pairText());
                if (tvBtnLabel  != null) tvBtnLabel.setText(shortPair());
                closePicker();
            }));
        });
    }

    private void showPickerDialog(String title, boolean inclAuto, LangCb cb) {
        closePicker();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(240, 5, 12, 30));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(100, 50, 100, 220));
        root.setBackground(bg);
        root.setElevation(dp(16));

        // Title row
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(0, 0, 0, dp(8));

        TextView tvT = new TextView(this);
        tvT.setText(title);
        tvT.setTextColor(Color.argb(220, 120, 170, 255));
        tvT.setTextSize(13f);
        tvT.setTypeface(Typeface.DEFAULT_BOLD);
        tr.addView(tvT, new LinearLayout.LayoutParams(0, wc(), 1f));

        TextView xb = new TextView(this);
        xb.setText("  \u00D7  ");
        xb.setTextColor(Color.argb(200, 220, 80, 80));
        xb.setTextSize(15f);
        xb.setOnClickListener(v -> closePicker());
        tr.addView(xb);
        root.addView(tr);

        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, 0, 0, dp(4));
        divider.setBackgroundColor(Color.argb(50, 60, 100, 200));
        root.addView(divider, divLp);

        // Scrollable list
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (String[] lang : LANGS) {
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
            list.addView(rd, new LinearLayout.LayoutParams(mp(), dp(1)));
        }

        scroll.addView(list);
        root.addView(scroll,
            new LinearLayout.LayoutParams(mp(), (int)(SH * 0.48f)));

        pickerView = root;
        WindowManager.LayoutParams pickerLP = new WindowManager.LayoutParams(
            (int)(SW * 0.70f), wc(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        pickerLP.gravity = Gravity.CENTER;
        safeAddView(pickerView, pickerLP);
    }

    private void closePicker() {
        safeRemove(pickerView);
        pickerView = null;
    }

    // ═════════════════════════════════════════════════════════════
    // Button Touch
    // ═════════════════════════════════════════════════════════════

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
                    if (fadeOutR != null) H.removeCallbacks(fadeOutR);
                    if (btnView  != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(80).start();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        dragged  = true;
                        btnLP.x  = Math.max(0, Math.min(ix + (int)dx, SW - dp(68)));
                        btnLP.y  = Math.max(0, Math.min(iy + (int)dy, SH - dp(40)));
                        safeUpdateLayout(btnView, btnLP);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= LONG_PRESS_MS) {
                            if (autoMode) stopAuto(); else startAuto();
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
        if (pickerView  != null)  { closePicker(); scheduleBtnFade(); return; }
        if (overlayVisible)       { hideOverlay(); return; }
        doOCR();
    }

    private void onDoubleTap() {
        if (overlayVisible) hideOverlay();
        openPicker();
    }

    // ═════════════════════════════════════════════════════════════
    // Google Translate
    // ═════════════════════════════════════════════════════════════

    private String googleTranslate(String text, String from, String to) throws Exception {
        String q = URLEncoder.encode(text, "UTF-8");
        String urlStr = "https://translate.googleapis.com/translate_a/single"
            + "?client=gtx"
            + "&sl=" + (from.equals("auto") ? "auto" : from)
            + "&tl=" + to
            + "&dt=t&q=" + q;

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
        return c.length() > 3 ? c.substring(0, 3).toUpperCase() : c.toUpperCase();
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

    private int wc() { return WindowManager.LayoutParams.WRAP_CONTENT; }
    private int mp() { return LinearLayout.LayoutParams.MATCH_PARENT; }

    private FrameLayout.LayoutParams matchFLP() {
        return new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
    }

    // ── Safe WindowManager wrappers ───────────────────────────────

    private void safeAddView(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null) return;
        try { wm.addView(v, lp); }
        catch (WindowManager.BadTokenException e) {
            Log.e(TAG, "safeAddView BadToken: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "safeAddView: " + e.getMessage(), e);
        }
    }

    private void safeUpdateLayout(View v, WindowManager.LayoutParams lp) {
        if (v == null || lp == null || v.getWindowToken() == null) return;
        try { wm.updateViewLayout(v, lp); }
        catch (WindowManager.BadTokenException e) {
            Log.e(TAG, "safeUpdateLayout BadToken: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "safeUpdateLayout IAE (view not attached): " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "safeUpdateLayout: " + e.getMessage(), e);
        }
    }

    private void safeRemove(View v) {
        if (v == null || v.getWindowToken() == null) return;
        try { wm.removeView(v); }
        catch (Exception e) { Log.e(TAG, "safeRemove: " + e.getMessage()); }
    }

    private void recycleSafe(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            try { bmp.recycle(); } catch (Exception ignored) {}
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            Log.w(TAG, "WakeLock release: " + e.getMessage());
        }
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
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UniversalTranslator")
            .setContentText("Tap=translate  Double-tap=language  Hold=AUTO")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
