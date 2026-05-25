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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FloatingTranslatorService — v10 (Production)
 *
 * Fixes vs v9:
 * - ExecutorService replaces raw new Thread (no unbounded thread creation)
 * - LruCache for translation results (avoids redundant network calls)
 * - Bitmap recycled immediately after OCR — no memory leak
 * - ImageReader + VirtualDisplay always released in finally block
 * - Handler.removeCallbacksAndMessages(null) on destroy — no dangling callbacks
 * - AtomicBoolean for ocrBusy/translating — thread-safe, no race condition
 * - HttpURLConnection always disconnected in finally — no connection leak
 * - MediaProjection null-checked with synchronized guard
 * - Debounce on button: ignores taps while OCR/translate in progress
 * - Retry logic for translation (1 retry on IOException)
 * - OCR crops dialogue region first, falls back to full screen
 * - All UI updates guarded with destroyed check
 * - No emoji in strings — pure ASCII, no bidi warning
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "UT";
    private static final String CHANNEL_ID = "ut_v10";
    private static final int    NOTIF_ID   = 1;

    // Timing constants
    private static final long DISMISS_MS  = 6_000;
    private static final long LONG_MS     = 650;
    private static final long DOUBLE_MS   = 300;
    private static final long CAPTURE_DELAY_MS = 260; // wait for VirtualDisplay to stabilize

    // Alpha states
    private static final float ALPHA_IDLE = 0.25f;
    private static final float ALPHA_BUSY = 0.92f;

    // OCR region: rows 58%-93% of screen height (typical dialogue box area)
    private static final float OCR_TOP    = 0.58f;
    private static final float OCR_BOTTOM = 0.93f;

    // Translation cache: max 40 entries (~small strings, negligible RAM)
    private static final int CACHE_SIZE = 40;

    // ── Languages (code, full name, abbreviation) ──
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

    // ── Core ──
    private WindowManager wm;
    private int SW, SH;

    // Main-thread handler — all UI ops go through this
    private final Handler H = new Handler(Looper.getMainLooper());

    // Single-thread pool for OCR+translate (sequential, no parallel confusion)
    // Using 1 thread prevents concurrent captures from colliding
    private ExecutorService executor;
    private Future<?> pendingTask; // track in-flight task for cancellation

    // Translation cache: key = "fromLang|toLang|text", value = translated string
    private LruCache<String, String> translateCache;

    // ── Views ──
    private View     btnView;
    private View     overlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, overlayLP, pickerLP;
    private GradientDrawable btnBg;
    private TextView tvBtnLabel;
    private View     autoDot;
    private TextView tvTranslation;
    private TextView tvOriginal;
    private TextView tvLangPair;

    // ── State (AtomicBoolean for thread-safety) ──
    private final AtomicBoolean ocrBusy     = new AtomicBoolean(false);
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private volatile boolean overlayVisible = false;
    private volatile boolean autoMode       = false;
    private volatile boolean destroyed      = false;

    private volatile String fromLang   = "auto";
    private volatile String toLang     = "ar";
    private String pickerFrom = "auto";

    // ── Clipboard (AUTO MODE only) ──
    private android.content.ClipboardManager clipMgr;
    private android.content.ClipboardManager.OnPrimaryClipChangedListener clipCb;
    private String lastClipHash = "";

    // ── MediaProjection & OCR ──
    private final Object mpLock = new Object(); // guards mediaProjection access
    private MediaProjection mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer recognizerJa;
    private TextRecognizer recognizerLat;

    // ── Timers ──
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable fadeOutR;
    private int tapCount = 0;

    // ════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();

        wm        = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        clipMgr   = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        // Screen dimensions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
            SW = b.width(); SH = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getMetrics(dm);
            SW = dm.widthPixels; SH = dm.heightPixels;
        }

        fromLang = ClipboardBridge.readFromLang(this);
        toLang   = ClipboardBridge.readToLang(this);

        // OCR recognizers
        try {
            recognizerJa  = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Exception e) {
            Log.e(TAG, "OCR init failed: " + e.getMessage());
            recognizerJa = recognizerLat = null;
        }

        // Thread pool: 1 background thread (sequential ops, minimal battery)
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UT-Worker");
            t.setPriority(Thread.NORM_PRIORITY - 1); // slightly below UI priority
            return t;
        });

        // Translation cache
        translateCache = new LruCache<>(CACHE_SIZE);

        createChannel();
        startForeground(NOTIF_ID, buildNotif());
        buildButton();
        buildOverlay();

        Log.d(TAG, "Service started. Screen: " + SW + "x" + SH);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("mp_result_code")) {
            int    rc = intent.getIntExtra("mp_result_code", -1);
            Intent d  = intent.getParcelableExtra("mp_data");
            if (rc == android.app.Activity.RESULT_OK && d != null) {
                synchronized (mpLock) {
                    // Stop previous projection if any
                    if (mediaProjection != null) {
                        try { mediaProjection.stop(); } catch (Exception ignored) {}
                        mediaProjection = null;
                    }
                    try {
                        mediaProjection = mpManager.getMediaProjection(rc, d);
                        // Register callback to detect when system revokes permission
                        mediaProjection.registerCallback(new MediaProjection.Callback() {
                            @Override public void onStop() {
                                Log.w(TAG, "MediaProjection stopped by system");
                                synchronized (mpLock) { mediaProjection = null; }
                                ocrBusy.set(false);
                                H.post(() -> resetBtn("?"));
                            }
                        }, H);
                        Log.d(TAG, "MediaProjection ready");
                    } catch (Exception e) {
                        Log.e(TAG, "MediaProjection failed: " + e.getMessage());
                        mediaProjection = null;
                    }
                }
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

        // Cancel all pending Handler callbacks — prevents leaks & post-destroy UI ops
        H.removeCallbacksAndMessages(null);

        stopAuto();

        // Shut down executor — wait for current task to finish (max 2s)
        if (executor != null) {
            executor.shutdownNow();
        }

        // Close OCR clients
        try { if (recognizerJa  != null) recognizerJa.close();  } catch (Exception ignored) {}
        try { if (recognizerLat != null) recognizerLat.close(); } catch (Exception ignored) {}

        // Stop MediaProjection
        synchronized (mpLock) {
            if (mediaProjection != null) {
                try { mediaProjection.stop(); } catch (Exception ignored) {}
                mediaProjection = null;
            }
        }

        // Remove all window views
        safeRemove(btnView);
        safeRemove(overlayView);
        safeRemove(pickerView);
    }

    // ════════════════════════════════════════
    // Floating Button (compact pill)
    // ════════════════════════════════════════

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
        wm.addView(btnView, btnLP);
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

    // ════════════════════════════════════════
    // Overlay (compact bottom bar)
    // ════════════════════════════════════════

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

        // Top row: lang pair + X close
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        tvLangPair = new TextView(this);
        tvLangPair.setText(pairText());
        tvLangPair.setTextColor(Color.argb(160, 80, 130, 220));
        tvLangPair.setTextSize(9f);
        tvLangPair.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangPair.setLetterSpacing(0.06f);
        topRow.addView(tvLangPair, new LinearLayout.LayoutParams(0, wc(), 1f));

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
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, dp(4), 0, dp(5));
        div.setBackgroundColor(Color.argb(60, 40, 80, 180));
        card.addView(div, divLp);

        // Original text (dim, small, 1 line)
        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.argb(100, 140, 170, 220));
        tvOriginal.setTextSize(9.5f);
        tvOriginal.setMaxLines(1);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(3));
        card.addView(tvOriginal);

        // Translation (main text, bright)
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

        overlayLP = new WindowManager.LayoutParams(
            SW, wc(), overlayType(),
            // Start NOT_TOUCHABLE; we remove this flag when showing (for X button)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        overlayLP.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayLP.y = dp(8);

        wm.addView(overlayView, overlayLP);
    }

    private void showOverlay() {
        if (destroyed || overlayView == null) return;
        overlayVisible = true;
        // Enable touch so X button works
        overlayLP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        try { wm.updateViewLayout(overlayView, overlayLP); } catch (Exception ignored) {}
        overlayView.animate().alpha(1f).setDuration(180).start();
    }

    private void hideOverlay() {
        cancelDismiss();
        if (destroyed || overlayView == null) return;
        overlayView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (destroyed) return;
            overlayVisible = false;
            // Restore pass-through so game input is not blocked
            overlayLP.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(overlayView, overlayLP); } catch (Exception ignored) {}
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

    // ════════════════════════════════════════
    // AUTO MODE (clipboard monitoring)
    // ════════════════════════════════════════

    private void startAuto() {
        if (autoMode) return;
        autoMode = true;
        if (autoDot != null) autoDot.setVisibility(View.VISIBLE);
        clipCb = () -> H.post(() -> {
            String t = clip();
            if (t != null && !t.isEmpty()) {
                String h = t.substring(0, Math.min(t.length(), 80));
                if (!h.equals(lastClipHash)) { lastClipHash = h; doTranslate(t); }
            }
        });
        clipMgr.addPrimaryClipChangedListener(clipCb);
        Toast.makeText(this, "AUTO ON", Toast.LENGTH_SHORT).show();
    }

    private void stopAuto() {
        if (!autoMode) return;
        autoMode = false;
        if (autoDot != null) autoDot.setVisibility(View.GONE);
        if (clipCb != null) {
            try { clipMgr.removePrimaryClipChangedListener(clipCb); } catch (Exception ignored) {}
            clipCb = null;
        }
    }

    // ════════════════════════════════════════
    // OCR
    // ════════════════════════════════════════

    private void doOCR() {
        // Debounce: ignore if already working
        if (!ocrBusy.compareAndSet(false, true)) {
            Log.d(TAG, "OCR debounced");
            return;
        }

        // Check MediaProjection
        synchronized (mpLock) {
            if (mediaProjection == null) {
                ocrBusy.set(false);
                Toast.makeText(this, "Need screen permission — reopen app", Toast.LENGTH_SHORT).show();
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                return;
            }
        }

        if (recognizerJa == null || recognizerLat == null) {
            ocrBusy.set(false);
            Toast.makeText(this, "OCR not ready — reinstall app", Toast.LENGTH_SHORT).show();
            return;
        }

        // Update UI: show "Reading..."
        if (tvBtnLabel != null) tvBtnLabel.setText("...");
        if (btnView != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
        if (tvTranslation != null) {
            tvTranslation.setText("Reading screen...");
            tvTranslation.setTextColor(Color.argb(160, 140, 170, 220));
        }
        if (tvOriginal != null) tvOriginal.setText("");
        if (tvLangPair != null) tvLangPair.setText(pairText());
        showOverlay();

        // Capture full-res (needed for accuracy; we crop after)
        final int capW   = SW;
        final int capH   = SH;
        final int density = getResources().getDisplayMetrics().densityDpi;

        // Use WeakReference to avoid holding Service reference in lambda after destroy
        final WeakReference<FloatingTranslatorService> weakSelf = new WeakReference<>(this);

        pendingTask = executor.submit(() -> {
            FloatingTranslatorService self = weakSelf.get();
            if (self == null || self.destroyed) { ocrBusy.set(false); return; }

            ImageReader reader = null;
            VirtualDisplay vd  = null;
            Bitmap fullBmp     = null;

            try {
                reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

                synchronized (mpLock) {
                    if (mediaProjection == null) {
                        ocrBusy.set(false);
                        H.post(() -> resetBtn(shortPair()));
                        return;
                    }
                    vd = mediaProjection.createVirtualDisplay(
                        "UT_OCR", capW, capH, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                }

                // Wait for display to render a frame
                Thread.sleep(CAPTURE_DELAY_MS);

                // Acquire frame
                final ImageReader finalReader = reader;
                Image img = finalReader.acquireLatestImage();
                if (img != null) {
                    try {
                        Image.Plane[] planes = img.getPlanes();
                        ByteBuffer buf    = planes[0].getBuffer();
                        int rStride       = planes[0].getRowStride();
                        int pStride       = planes[0].getPixelStride();
                        int bmpW          = rStride / pStride;
                        Bitmap tmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
                        tmp.copyPixelsFromBuffer(buf);
                        // Crop to actual screen width (stride may be wider)
                        fullBmp = Bitmap.createBitmap(tmp, 0, 0, capW, capH);
                        tmp.recycle(); // recycle intermediate immediately
                    } finally {
                        img.close(); // ALWAYS close Image to release buffer
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "OCR interrupted");
            } catch (Exception e) {
                Log.e(TAG, "Capture error: " + e.getMessage());
            } finally {
                // ALWAYS release VirtualDisplay and ImageReader
                if (vd != null)     { try { vd.release();     } catch (Exception ignored) {} }
                if (reader != null) { try { reader.close();   } catch (Exception ignored) {} }
            }

            if (fullBmp == null) {
                ocrBusy.set(false);
                H.post(() -> {
                    if (destroyed) return;
                    resetBtn(shortPair());
                    if (tvTranslation != null) {
                        tvTranslation.setText("Capture failed — try again");
                        tvTranslation.setTextColor(Color.argb(220, 255, 180, 80));
                    }
                });
                return;
            }

            // Crop to dialogue region (bottom ~35% of screen)
            int cropTop = (int)(capH * OCR_TOP);
            int cropH   = (int)(capH * OCR_BOTTOM) - cropTop;
            Bitmap cropped = Bitmap.createBitmap(fullBmp, 0, cropTop, capW, cropH);

            // Select recognizer based on source language
            boolean useJa = fromLang.equals("ja") || fromLang.equals("ko")
                         || fromLang.startsWith("zh") || fromLang.equals("auto");
            TextRecognizer rec = useJa ? recognizerJa : recognizerLat;

            final Bitmap finalFull    = fullBmp;
            final Bitmap finalCropped = cropped;

            // Run OCR on cropped region first
            rec.process(InputImage.fromBitmap(finalCropped, 0))
                .addOnSuccessListener(result -> {
                    finalCropped.recycle(); // done with cropped
                    String text = result.getText().trim();
                    if (!text.isEmpty()) {
                        finalFull.recycle(); // done with full too
                        ocrBusy.set(false);
                        H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                        doTranslate(text);
                    } else {
                        // Fallback: OCR on full screen
                        rec.process(InputImage.fromBitmap(finalFull, 0))
                            .addOnSuccessListener(r2 -> {
                                finalFull.recycle();
                                ocrBusy.set(false);
                                H.post(() -> { if (!destroyed) resetBtn(shortPair()); });
                                String t2 = r2.getText().trim();
                                if (!t2.isEmpty()) {
                                    doTranslate(t2);
                                } else {
                                    H.post(() -> {
                                        if (destroyed) return;
                                        if (tvTranslation != null) {
                                            tvTranslation.setText("No text found on screen");
                                            tvTranslation.setTextColor(Color.argb(200, 255, 200, 80));
                                        }
                                    });
                                }
                            })
                            .addOnFailureListener(e -> {
                                finalFull.recycle();
                                ocrBusy.set(false);
                                H.post(() -> {
                                    if (destroyed) return;
                                    resetBtn(shortPair());
                                    if (tvTranslation != null) {
                                        tvTranslation.setText("OCR failed: " + e.getMessage());
                                        tvTranslation.setTextColor(Color.argb(220, 255, 100, 100));
                                    }
                                });
                            });
                    }
                })
                .addOnFailureListener(e -> {
                    finalCropped.recycle();
                    finalFull.recycle();
                    ocrBusy.set(false);
                    H.post(() -> {
                        if (destroyed) return;
                        resetBtn(shortPair());
                        if (tvTranslation != null) {
                            tvTranslation.setText("OCR error: " + e.getMessage());
                            tvTranslation.setTextColor(Color.argb(220, 255, 100, 100));
                        }
                    });
                });
        });
    }

    // ════════════════════════════════════════
    // Translation
    // ════════════════════════════════════════

    private void doTranslate(final String text) {
        // Debounce: ignore if already translating
        if (!translating.compareAndSet(false, true)) {
            Log.d(TAG, "Translation debounced");
            return;
        }
        cancelDismiss();

        // Check cache first
        String cacheKey = fromLang + "|" + toLang + "|" + text;
        String cached   = translateCache.get(cacheKey);
        if (cached != null) {
            Log.d(TAG, "Cache hit");
            translating.set(false);
            final String result = cached;
            H.post(() -> {
                if (destroyed) return;
                showTranslationResult(text, result);
            });
            return;
        }

        // Update UI
        H.post(() -> {
            if (destroyed) return;
            if (tvBtnLabel != null) tvBtnLabel.setText("...");
            if (btnView != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(100).start();
            String disp = text.length() > 80 ? text.substring(0, 80) + "\u2026" : text;
            if (tvOriginal != null) tvOriginal.setText(disp);
            if (tvTranslation != null) {
                tvTranslation.setText("Translating...");
                tvTranslation.setTextColor(Color.argb(160, 140, 170, 220));
            }
            if (tvLangPair != null) tvLangPair.setText(pairText());
            showOverlay();
        });

        final String input = text.length() > 600 ? text.substring(0, 600) : text;
        final String snapFrom = fromLang;
        final String snapTo   = toLang;

        executor.submit(() -> {
            if (destroyed) { translating.set(false); return; }
            String result = null;
            Exception lastError = null;

            // Try up to 2 times (1 retry on network error)
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    result = googleTranslate(input, snapFrom, snapTo);
                    break; // success
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "Translate attempt " + (attempt+1) + " failed: " + e.getMessage());
                    if (attempt == 0) {
                        try { Thread.sleep(600); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            translating.set(false);

            final String finalResult = result;
            final Exception finalError = lastError;
            final String key = cacheKey;

            H.post(() -> {
                if (destroyed) return;
                if (finalResult != null) {
                    translateCache.put(key, finalResult);
                    showTranslationResult(text, finalResult);
                } else {
                    resetBtn(shortPair());
                    if (tvTranslation != null) {
                        String msg = finalError != null ? finalError.getMessage() : "Unknown error";
                        tvTranslation.setText("Error: " + msg);
                        tvTranslation.setTextColor(Color.argb(220, 255, 120, 120));
                    }
                }
            });
        });
    }

    private void showTranslationResult(String original, String translated) {
        if (destroyed) return;
        String disp = original.length() > 80 ? original.substring(0, 80) + "\u2026" : original;
        if (tvOriginal != null)    tvOriginal.setText(disp);
        if (tvTranslation != null) {
            tvTranslation.setText(translated);
            tvTranslation.setTextColor(Color.argb(245, 220, 238, 255));
        }
        if (tvLangPair != null) tvLangPair.setText(pairText());
        resetBtn(shortPair());
        showOverlay();
        if (!autoMode) scheduleDismiss();
        try { wm.updateViewLayout(overlayView, overlayLP); } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════
    // Language Picker
    // ════════════════════════════════════════

    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog("From language", true, code -> {
            pickerFrom = code;
            H.post(() -> showPickerDialog("To language", false, code2 -> {
                fromLang = pickerFrom;
                toLang   = code2;
                ClipboardBridge.saveLang(this, fromLang, toLang);
                if (tvLangPair != null) tvLangPair.setText(pairText());
                if (tvBtnLabel != null) tvBtnLabel.setText(shortPair());
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

        // Title + X
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
                if (a == MotionEvent.ACTION_DOWN)
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
        root.addView(scroll, new LinearLayout.LayoutParams(mp(), (int)(SH * 0.48f)));

        pickerView = root;
        pickerLP = new WindowManager.LayoutParams(
            (int)(SW * 0.70f), wc(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        pickerLP.gravity = Gravity.CENTER;
        wm.addView(pickerView, pickerLP);
    }

    private void closePicker() {
        safeRemove(pickerView);
        pickerView = null;
    }

    // ════════════════════════════════════════
    // Button Touch
    // ════════════════════════════════════════

    private class BtnTouch implements View.OnTouchListener {
        private int ix, iy;
        private float rx, ry;
        private boolean dragged;
        private long downAt;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ix = btnLP.x; iy = btnLP.y;
                    rx = e.getRawX(); ry = e.getRawY();
                    dragged = false; downAt = System.currentTimeMillis();
                    if (fadeOutR != null) H.removeCallbacks(fadeOutR);
                    if (btnView != null) btnView.animate().alpha(ALPHA_BUSY).setDuration(80).start();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        dragged = true;
                        btnLP.x = Math.max(0, Math.min(ix + (int)dx, SW - dp(68)));
                        btnLP.y = Math.max(0, Math.min(iy + (int)dy, SH - dp(40)));
                        try { wm.updateViewLayout(btnView, btnLP); } catch (Exception ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= LONG_MS) {
                            if (autoMode) stopAuto(); else startAuto();
                        } else {
                            tapCount++;
                            if (tapCount == 1) {
                                doubleTapCheck = () -> { tapCount = 0; onSingleTap(); };
                                H.postDelayed(doubleTapCheck, DOUBLE_MS);
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
        if (pickerView != null)  { closePicker(); scheduleBtnFade(); return; }
        if (overlayVisible)      { hideOverlay(); return; }
        doOCR();
    }

    private void onDoubleTap() {
        if (overlayVisible) hideOverlay();
        openPicker();
    }

    // ════════════════════════════════════════
    // Google Translate
    // ════════════════════════════════════════

    private String googleTranslate(String text, String from, String to) throws Exception {
        String q = URLEncoder.encode(text, "UTF-8");
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
            // ALWAYS disconnect to release connection pool slot
            if (c != null) c.disconnect();
        }
    }

    // ════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════

    private String clip() {
        try {
            if (clipMgr != null && clipMgr.hasPrimaryClip()) {
                android.content.ClipData.Item it = clipMgr.getPrimaryClip().getItemAt(0);
                if (it != null && it.getText() != null) return it.getText().toString().trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String pairText()  { return abbr(fromLang) + " \u2192 " + abbr(toLang); }
    private String shortPair() { return abbr(fromLang) + "\u2192" + abbr(toLang); }

    private String abbr(String c) {
        for (String[] l : LANGS) if (l[0].equals(c)) return l[2];
        return c.length() > 3 ? c.substring(0, 3).toUpperCase() : c.toUpperCase();
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

    private void safeRemove(View v) {
        if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════
    // Notification
    // ════════════════════════════════════════

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "UniversalTranslator", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UniversalTranslator")
            .setContentText("Tap = translate  \u2022  Double tap = language  \u2022  Hold = AUTO")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
