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

/**
 * FloatingTranslatorService — v9
 *
 * التغييرات الجوهرية عن v8:
 * ─────────────────────────────────────────────────────
 * ✅ OCR هو الافتراضي دائماً — لا fallback للحافظة
 * ✅ إذا mediaProjection == null: يطلب من المستخدم إعادة فتح التطبيق (لا حافظة)
 * ✅ MediaProjection لا يُعاد إنشاؤه — يُستقبل مرة واحدة ويبقى
 * ✅ foregroundServiceType="mediaProjection" في Manifest — مطلوب Android 10+
 * ✅ Overlay خفيف شفاف أسفل الشاشة فقط
 * ✅ لا WebView، لا Clipboard، لا activity_main
 * ✅ AUTO MODE يراقب الحافظة اختياريًا (ضغط طويل)
 * ─────────────────────────────────────────────────────
 *
 * تفاعلات الزر العائم:
 *   نقرة واحدة  → OCR مباشر من الشاشة
 *   نقرتان      → اختيار اللغة
 *   ضغط طويل   → AUTO MODE (مراقبة الحافظة)
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "GT_Service";
    private static final String CHANNEL_ID = "gt_v9";
    private static final int    NOTIF_ID   = 1;

    // ثوابت التوقيت
    private static final long DISMISS_MS  = 10_000; // إخفاء النتيجة بعد 10 ثانية
    private static final long LONG_MS     = 700;    // ضغط طويل
    private static final long DOUBLE_MS   = 350;    // نقرتان

    // شفافية الزر
    private static final float ALPHA_IDLE = 0.40f;
    private static final float ALPHA_BUSY = 0.95f;

    // جميع لغات العالم المدعومة في Google Translate (130+ لغة)
    private static final String[][] LANGS = {
        {"auto","Auto Detect","🔍"},
        // ── Most Used ──
        {"ar","Arabic","🇸🇦"},
        {"en","English","🇺🇸"},
        {"ja","Japanese","🇯🇵"},
        {"ko","Korean","🇰🇷"},
        {"zh-CN","Chinese (Simplified)","🇨🇳"},
        {"zh-TW","Chinese (Traditional)","🇹🇼"},
        {"fr","French","🇫🇷"},
        {"de","German","🇩🇪"},
        {"es","Spanish","🇪🇸"},
        {"ru","Russian","🇷🇺"},
        {"tr","Turkish","🇹🇷"},
        {"it","Italian","🇮🇹"},
        {"pt","Portuguese","🇧🇷"},
        {"hi","Hindi","🇮🇳"},
        // ── Europe ──
        {"af","Afrikaans","🇿🇦"},
        {"sq","Albanian","🇦🇱"},
        {"hy","Armenian","🇦🇲"},
        {"az","Azerbaijani","🇦🇿"},
        {"eu","Basque","🏴"},
        {"be","Belarusian","🇧🇾"},
        {"bs","Bosnian","🇧🇦"},
        {"bg","Bulgarian","🇧🇬"},
        {"ca","Catalan","🏴"},
        {"hr","Croatian","🇭🇷"},
        {"cs","Czech","🇨🇿"},
        {"da","Danish","🇩🇰"},
        {"nl","Dutch","🇳🇱"},
        {"et","Estonian","🇪🇪"},
        {"fi","Finnish","🇫🇮"},
        {"gl","Galician","🏴"},
        {"ka","Georgian","🇬🇪"},
        {"el","Greek","🇬🇷"},
        {"hu","Hungarian","🇭🇺"},
        {"is","Icelandic","🇮🇸"},
        {"ga","Irish","🇮🇪"},
        {"lv","Latvian","🇱🇻"},
        {"lt","Lithuanian","🇱🇹"},
        {"lb","Luxembourgish","🇱🇺"},
        {"mk","Macedonian","🇲🇰"},
        {"mt","Maltese","🇲🇹"},
        {"no","Norwegian","🇳🇴"},
        {"pl","Polish","🇵🇱"},
        {"ro","Romanian","🇷🇴"},
        {"sr","Serbian","🇷🇸"},
        {"sk","Slovak","🇸🇰"},
        {"sl","Slovenian","🇸🇮"},
        {"sv","Swedish","🇸🇪"},
        {"uk","Ukrainian","🇺🇦"},
        {"cy","Welsh","🏴󠁧󠁢󠁷󠁬󠁳󠁿"},
        {"fy","Frisian","🇳🇱"},
        {"co","Corsican","🇫🇷"},
        {"br","Breton","🇫🇷"},
        {"gd","Scots Gaelic","🏴󠁧󠁢󠁳󠁣󠁴󠁿"},
        {"oc","Occitan","🏴"},
        {"la","Latin","🏛️"},
        {"eo","Esperanto","🌍"},
        // ── Asia ──
        {"bn","Bengali","🇧🇩"},
        {"my","Burmese","🇲🇲"},
        {"ceb","Cebuano","🇵🇭"},
        {"gu","Gujarati","🇮🇳"},
        {"hmn","Hmong","🏳️"},
        {"id","Indonesian","🇮🇩"},
        {"jw","Javanese","🇮🇩"},
        {"kn","Kannada","🇮🇳"},
        {"kk","Kazakh","🇰🇿"},
        {"km","Khmer","🇰🇭"},
        {"ky","Kyrgyz","🇰🇬"},
        {"lo","Lao","🇱🇦"},
        {"ms","Malay","🇲🇾"},
        {"ml","Malayalam","🇮🇳"},
        {"mr","Marathi","🇮🇳"},
        {"mn","Mongolian","🇲🇳"},
        {"ne","Nepali","🇳🇵"},
        {"or","Odia","🇮🇳"},
        {"pa","Punjabi","🇮🇳"},
        {"si","Sinhala","🇱🇰"},
        {"su","Sundanese","🇮🇩"},
        {"tg","Tajik","🇹🇯"},
        {"ta","Tamil","🇮🇳"},
        {"te","Telugu","🇮🇳"},
        {"th","Thai","🇹🇭"},
        {"tl","Filipino","🇵🇭"},
        {"tk","Turkmen","🇹🇲"},
        {"ug","Uyghur","🏳️"},
        {"ur","Urdu","🇵🇰"},
        {"uz","Uzbek","🇺🇿"},
        {"vi","Vietnamese","🇻🇳"},
        {"tt","Tatar","🇷🇺"},
        // ── Middle East ──
        {"fa","Persian","🇮🇷"},
        {"he","Hebrew","🇮🇱"},
        {"ku","Kurdish","🏳️"},
        {"ps","Pashto","🇦🇫"},
        {"sd","Sindhi","🇵🇰"},
        {"yi","Yiddish","✡️"},
        // ── Africa ──
        {"am","Amharic","🇪🇹"},
        {"ha","Hausa","🇳🇬"},
        {"ig","Igbo","🇳🇬"},
        {"rw","Kinyarwanda","🇷🇼"},
        {"mg","Malagasy","🇲🇬"},
        {"ny","Chichewa","🇲🇼"},
        {"om","Oromo","🇪🇹"},
        {"sn","Shona","🇿🇼"},
        {"so","Somali","🇸🇴"},
        {"st","Sesotho","🇱🇸"},
        {"sw","Swahili","🇰🇪"},
        {"ti","Tigrinya","🇪🇷"},
        {"xh","Xhosa","🇿🇦"},
        {"yo","Yoruba","🇳🇬"},
        {"zu","Zulu","🇿🇦"},
        {"ee","Ewe","🇬🇭"},
        {"lg","Luganda","🇺🇬"},
        {"ln","Lingala","🇨🇩"},
        {"bm","Bambara","🇲🇱"},
        {"wo","Wolof","🇸🇳"},
        // ── Latin America ──
        {"ht","Haitian Creole","🇭🇹"},
        {"qu","Quechua","🇵🇪"},
        {"gn","Guarani","🇵🇾"},
        {"ay","Aymara","🇧🇴"},
        // ── Pacific ──
        {"haw","Hawaiian","🌺"},
        {"mi","Maori","🇳🇿"},
        {"sm","Samoan","🇼🇸"},
    };

    // ═══ WindowManager & أبعاد الشاشة ═══
    private WindowManager wm;
    private int SW, SH; // عرض وارتفاع الشاشة بـ px

    private final Handler H = new Handler(Looper.getMainLooper());

    // ═══ Views ═══
    private View     btnView;
    private View     overlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, overlayLP, pickerLP;
    private GradientDrawable btnCircleBg;

    // مراجع مباشرة للـ TextViews داخل الـ Overlay
    private TextView tvBtnIcon;
    private View     autoDot;
    private TextView tvTranslation;
    private TextView tvOriginal;
    private TextView tvLangBar;

    // ═══ الحالة ═══
    private boolean overlayVisible = false;
    private boolean autoMode       = false;
    private boolean translating    = false;
    private boolean ocrBusy        = false;
    private volatile boolean destroyed = false;

    private String fromLang = "auto";
    private String toLang   = "ar";
    private String pickerFrom = "auto";

    // ═══ Clipboard (AUTO MODE فقط) ═══
    private android.content.ClipboardManager clipMgr;
    private android.content.ClipboardManager.OnPrimaryClipChangedListener clipCb;
    private String lastClipHash = "";

    // ═══ MediaProjection & OCR ═══
    private MediaProjection        mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer         recognizerJa;   // يابانية / كورية / صينية
    private TextRecognizer         recognizerLat;  // لاتينية / عربية / روسية / الخ

    // ═══ Timers ═══
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable pulseR;
    private boolean  pulseState = false;
    private int      tapCount   = 0;

    // ════════════════════════════════════════════════════════════════
    // onCreate
    // ════════════════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        clipMgr = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        // قياس الشاشة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            SW = bounds.width();
            SH = bounds.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            SW = dm.widthPixels;
            SH = dm.heightPixels;
        }

        // قراءة اللغة المحفوظة
        fromLang = ClipboardBridge.readFromLang(this);
        toLang   = ClipboardBridge.readToLang(this);

        // تهيئة OCR
        try {
            recognizerJa  = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            Log.d(TAG, "OCR recognizers initialized ✓");
        } catch (Exception e) {
            Log.e(TAG, "OCR init failed: " + e.getMessage());
            recognizerJa  = null;
            recognizerLat = null;
        }

        createChannel();
        startForeground(NOTIF_ID, buildNotif());
        buildButton();
        buildOverlay();
    }

    // ════════════════════════════════════════════════════════════════
    // onStartCommand — استقبال MediaProjection من MainActivity
    // ════════════════════════════════════════════════════════════════
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("mp_result_code")) {
            int    rc   = intent.getIntExtra("mp_result_code", -1);
            Intent data = intent.getParcelableExtra("mp_data");

            if (rc == android.app.Activity.RESULT_OK && data != null) {
                // أوقف القديم لو موجود
                if (mediaProjection != null) {
                    try { mediaProjection.stop(); } catch (Exception ignored) {}
                    mediaProjection = null;
                }
                try {
                    mediaProjection = mpManager.getMediaProjection(rc, data);
                    Log.d(TAG, "MediaProjection created ✓ — OCR جاهز");

                    // عند إنهاء MediaProjection (مثلاً المستخدم يسحب الإذن)
                    mediaProjection.registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.w(TAG, "MediaProjection stopped by system");
                            mediaProjection = null;
                            H.post(() -> {
                                if (tvBtnIcon != null) tvBtnIcon.setText("🌐");
                                ocrBusy = false;
                                animateBtnPulse(false);
                            });
                        }
                    }, H);

                } catch (Exception e) {
                    Log.e(TAG, "MediaProjection creation failed: " + e.getMessage());
                    mediaProjection = null;
                }
            } else {
                Log.w(TAG, "MediaProjection not granted (rc=" + rc + ")");
            }
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ════════════════════════════════════════════════════════════════
    // onDestroy
    // ════════════════════════════════════════════════════════════════
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        destroyed = true;
        cancelDismiss();
        if (pulseR != null) H.removeCallbacks(pulseR);
        if (doubleTapCheck != null) H.removeCallbacks(doubleTapCheck);
        stopAuto();
        try { if (recognizerJa  != null) recognizerJa.close();  } catch (Exception ignored) {}
        try { if (recognizerLat != null) recognizerLat.close(); } catch (Exception ignored) {}
        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Exception ignored) {}
        safeRemove(btnView);
        safeRemove(overlayView);
        safeRemove(pickerView);
    }

    // ════════════════════════════════════════════════════════════════
    // بناء الزر العائم
    // ════════════════════════════════════════════════════════════════
    private void buildButton() {
        FrameLayout root   = new FrameLayout(this);
        FrameLayout circle = new FrameLayout(this);

        int sz = dp(56);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(sz, sz);
        clp.setMargins(dp(6), dp(6), dp(6), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#0f1e40"));
        bg.setStroke(dp(2), Color.parseColor("#2a5090"));
        circle.setBackground(bg);
        circle.setElevation(dp(12));
        btnCircleBg = bg;

        tvBtnIcon = new TextView(this);
        tvBtnIcon.setText("🌐");
        tvBtnIcon.setTextSize(22f);
        tvBtnIcon.setGravity(Gravity.CENTER);
        tvBtnIcon.setIncludeFontPadding(false);
        circle.addView(tvBtnIcon, matchParentFLP());

        // نقطة خضراء — تظهر فقط في AUTO MODE
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
        btnLP.x = SW - dp(78);
        btnLP.y = SH / 2 - dp(34);

        btnView.setOnTouchListener(new BtnTouch());
        wm.addView(btnView, btnLP);
    }

    // ════════════════════════════════════════════════════════════════
    // بناء الـ Overlay (نتيجة الترجمة)
    // الـ Overlay خفيف، شفاف، أسفل الشاشة، لا يمنع اللمس
    // ════════════════════════════════════════════════════════════════
    private void buildOverlay() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(12), dp(4), dp(12), dp(4));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(12));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(Color.argb(220, 4, 8, 22));
        cardBg.setCornerRadius(dp(18));
        cardBg.setStroke(dp(1), Color.parseColor("#1e3566"));
        card.setBackground(cardBg);

        // شريط اللغة
        tvLangBar = new TextView(this);
        tvLangBar.setText(pairText());
        tvLangBar.setTextColor(Color.parseColor("#4a6a9a"));
        tvLangBar.setTextSize(9.5f);
        tvLangBar.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangBar.setLetterSpacing(0.08f);
        tvLangBar.setPadding(0, 0, 0, dp(4));
        card.addView(tvLangBar);

        // خط فاصل
        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, 0, 0, dp(6));
        div.setBackgroundColor(Color.parseColor("#121e40"));
        card.addView(div, divLp);

        // النص الأصلي (مختصر)
        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.parseColor("#2e4466"));
        tvOriginal.setTextSize(10f);
        tvOriginal.setMaxLines(1);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(4));
        card.addView(tvOriginal);

        // نص الترجمة (الرئيسي)
        tvTranslation = new TextView(this);
        tvTranslation.setText("اضغط الزر للترجمة 🌐");
        tvTranslation.setTextColor(Color.parseColor("#364a70"));
        tvTranslation.setTextSize(19f);
        tvTranslation.setTypeface(Typeface.DEFAULT_BOLD);
        tvTranslation.setLineSpacing(dp(2), 1.2f);
        tvTranslation.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        tvTranslation.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tvTranslation.setMaxLines(5);
        tvTranslation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvTranslation);

        // تلميح
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // لا يمنع اللعب
            PixelFormat.TRANSLUCENT
        );
        overlayLP.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlayLP.y = dp(12);

        wm.addView(overlayView, overlayLP);
    }

    private void showOverlay() {
        overlayVisible = true;
        overlayView.animate().alpha(0.93f).setDuration(200).start();
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

    // ════════════════════════════════════════════════════════════════
    // Pulse Animation للزر أثناء OCR
    // ════════════════════════════════════════════════════════════════
    private void animateBtnPulse(boolean start) {
        if (pulseR != null) { H.removeCallbacks(pulseR); pulseR = null; }
        if (!start) {
            if (btnCircleBg != null)
                btnCircleBg.setStroke(dp(2), Color.parseColor("#2a5090"));
            return;
        }
        pulseR = new Runnable() {
            @Override public void run() {
                if (btnCircleBg == null || destroyed) return;
                pulseState = !pulseState;
                btnCircleBg.setStroke(dp(pulseState ? 3 : 2),
                    pulseState ? Color.parseColor("#00c8ff")
                               : Color.parseColor("#1a5090"));
                H.postDelayed(this, 500);
            }
        };
        H.post(pulseR);
    }

    // ════════════════════════════════════════════════════════════════
    // AUTO MODE — مراقبة الحافظة (اختياري، ضغط طويل)
    // ════════════════════════════════════════════════════════════════
    private void startAuto() {
        if (autoMode) return;
        autoMode = true;
        if (autoDot != null) autoDot.setVisibility(View.VISIBLE);
        clipCb = () -> H.post(() -> {
            String t = getClipText();
            if (t != null && !t.isEmpty()) {
                String hash = t.substring(0, Math.min(t.length(), 80));
                if (!hash.equals(lastClipHash)) {
                    lastClipHash = hash;
                    doTranslate(t);
                }
            }
        });
        clipMgr.addPrimaryClipChangedListener(clipCb);
        Toast.makeText(this, "✓ AUTO — كل نص تنسخه يُترجم تلقائياً", Toast.LENGTH_SHORT).show();
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

    // ════════════════════════════════════════════════════════════════
    // OCR — القلب الرئيسي للتطبيق
    // ════════════════════════════════════════════════════════════════
    private void doOCR() {
        if (ocrBusy) {
            Log.d(TAG, "OCR already busy, skipping");
            return;
        }

        // ─── التحقق من توفر MediaProjection ───
        if (mediaProjection == null) {
            Log.w(TAG, "mediaProjection is null — asking user to reopen app");
            Toast.makeText(this,
                "⚠ يحتاج إعادة إذن تصوير الشاشة\nافتح التطبيق مجدداً وامنح الإذن",
                Toast.LENGTH_LONG).show();
            // افتح MainActivity لإعادة طلب الإذن
            Intent reopen = new Intent(this, MainActivity.class);
            reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(reopen);
            return;
        }

        // ─── التحقق من توفر OCR ───
        if (recognizerJa == null || recognizerLat == null) {
            Toast.makeText(this, "⚠ ML Kit غير مهيأ — أعد تثبيت التطبيق", Toast.LENGTH_LONG).show();
            return;
        }

        // ─── بدء OCR ───
        ocrBusy = true;
        tvBtnIcon.setText("📷");
        btnView.setAlpha(ALPHA_BUSY);
        animateBtnPulse(true);

        if (tvTranslation != null) {
            tvTranslation.setText("⏳  يقرأ الشاشة…");
            tvTranslation.setTextColor(Color.parseColor("#2a4a70"));
        }
        if (tvOriginal != null) tvOriginal.setText("");
        showOverlay();

        // دقة منخفضة لسرعة أكبر
        int capW = SW / 2;
        int capH = SH / 2;
        int density = getResources().getDisplayMetrics().densityDpi;

        ImageReader reader;
        VirtualDisplay vd;
        try {
            reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            vd = mediaProjection.createVirtualDisplay(
                "GT_OCR", capW, capH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
            Log.d(TAG, "VirtualDisplay created ✓");
        } catch (Exception e) {
            Log.e(TAG, "VirtualDisplay failed: " + e.getMessage());
            ocrBusy = false;
            animateBtnPulse(false);
            tvBtnIcon.setText("🌐");
            btnView.setAlpha(ALPHA_IDLE);
            mediaProjection = null; // انتهى صلاحيته
            if (tvTranslation != null) {
                tvTranslation.setText("⚠  فشل تصوير الشاشة\nافتح التطبيق وأعد الإذن");
                tvTranslation.setTextColor(Color.parseColor("#ef5350"));
            }
            return;
        }

        final ImageReader finalReader = reader;
        final VirtualDisplay finalVd  = vd;

        // انتظر 350ms حتى يستقر الـ VirtualDisplay
        H.postDelayed(() -> {
            Bitmap bmp = null;
            try {
                Image img = finalReader.acquireLatestImage();
                if (img != null) {
                    Image.Plane[] planes = img.getPlanes();
                    ByteBuffer buf    = planes[0].getBuffer();
                    int rStride       = planes[0].getRowStride();
                    int pStride       = planes[0].getPixelStride();
                    int bmpW          = capW + (rStride - pStride * capW) / pStride;
                    Bitmap tmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
                    tmp.copyPixelsFromBuffer(buf);
                    bmp = Bitmap.createBitmap(tmp, 0, 0, capW, capH);
                    img.close();
                    Log.d(TAG, "Screenshot captured ✓ (" + capW + "x" + capH + ")");
                } else {
                    Log.w(TAG, "acquireLatestImage returned null");
                }
            } catch (Exception e) {
                Log.e(TAG, "Screenshot error: " + e.getMessage());
            } finally {
                try { finalVd.release();    } catch (Exception ignored) {}
                try { finalReader.close();  } catch (Exception ignored) {}
            }

            if (bmp == null) {
                ocrBusy = false;
                animateBtnPulse(false);
                tvBtnIcon.setText("🌐");
                btnView.setAlpha(ALPHA_IDLE);
                if (tvTranslation != null) {
                    tvTranslation.setText("⚠  لم يتمكن من التقاط الشاشة\nأعد المحاولة");
                    tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                }
                return;
            }

            // اختر المعرِّف المناسب بحسب لغة المصدر
            boolean useJa = fromLang.equals("ja") || fromLang.equals("ko")
                         || fromLang.equals("zh") || fromLang.equals("auto");
            TextRecognizer rec = useJa ? recognizerJa : recognizerLat;

            final Bitmap finalBmp = bmp;
            rec.process(InputImage.fromBitmap(finalBmp, 0))
                .addOnSuccessListener(result -> {
                    ocrBusy = false;
                    animateBtnPulse(false);
                    tvBtnIcon.setText("🌐");
                    btnView.setAlpha(ALPHA_IDLE);

                    String text = result.getText().trim();
                    Log.d(TAG, "OCR result: [" + text.substring(0, Math.min(text.length(), 60)) + "]");

                    if (text.isEmpty()) {
                        if (tvTranslation != null) {
                            tvTranslation.setText("⚠  لم يُعثر على نص في الشاشة");
                            tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                        }
                    } else {
                        doTranslate(text);
                    }
                })
                .addOnFailureListener(e -> {
                    ocrBusy = false;
                    animateBtnPulse(false);
                    tvBtnIcon.setText("🌐");
                    btnView.setAlpha(ALPHA_IDLE);
                    Log.e(TAG, "OCR failed: " + e.getMessage());
                    if (tvTranslation != null) {
                        tvTranslation.setText("⚠  " + e.getMessage());
                        tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                    }
                });
        }, 350);
    }

    // ════════════════════════════════════════════════════════════════
    // الترجمة عبر Google Translate API (مجانية، بدون مفتاح)
    // ════════════════════════════════════════════════════════════════
    private void doTranslate(String text) {
        if (translating) return;
        translating = true;
        cancelDismiss();

        tvBtnIcon.setText("⏳");
        btnView.setAlpha(ALPHA_BUSY);

        String display = text.length() > 80 ? text.substring(0, 80) + "…" : text;
        if (tvOriginal != null) tvOriginal.setText(display);
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
                Log.d(TAG, "Translation ✓: " + result.substring(0, Math.min(result.length(), 40)));

                if (destroyed) return;
                H.post(() -> {
                    translating = false;
                    tvBtnIcon.setText("✓");
                    H.postDelayed(() -> { if (!destroyed) tvBtnIcon.setText("🌐"); }, 2_000);

                    if (tvTranslation != null) {
                        tvTranslation.setText(result);
                        tvTranslation.setTextColor(Color.parseColor("#d0e4ff"));
                    }
                    btnView.setAlpha(ALPHA_IDLE);
                    if (!autoMode) scheduleDismiss();

                    try { wm.updateViewLayout(overlayView, overlayLP); } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                Log.e(TAG, "Translation failed: " + e.getMessage());
                if (destroyed) return;
                H.post(() -> {
                    translating = false;
                    tvBtnIcon.setText("🌐");
                    if (tvTranslation != null) {
                        tvTranslation.setText("⚠  " + e.getMessage());
                        tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                    }
                    btnView.setAlpha(ALPHA_IDLE);
                });
            }
        }).start();
    }

    // ════════════════════════════════════════════════════════════════
    // اختيار اللغة (نقرتان)
    // ════════════════════════════════════════════════════════════════
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

        // ─── عنوان + زر الإغلاق ───
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(0, 0, 0, dp(10));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor("#7aa0d4"));
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tr.addView(tvTitle, new LinearLayout.LayoutParams(0, wc(), 1f));

        TextView xBtn = new TextView(this);
        xBtn.setText("✕");
        xBtn.setTextColor(Color.parseColor("#e05050"));
        xBtn.setTextSize(16f);
        xBtn.setPadding(dp(12), dp(4), dp(2), dp(4));
        xBtn.setOnClickListener(v -> closePicker());
        tr.addView(xBtn);
        root.addView(tr);

        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, 0, 0, dp(6));
        divider.setBackgroundColor(Color.parseColor("#192848"));
        root.addView(divider, divLp);

        // ─── قائمة اللغات ───
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

    // ════════════════════════════════════════════════════════════════
    // معالجة اللمس على الزر العائم
    // ════════════════════════════════════════════════════════════════
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
                    btnView.setAlpha(ALPHA_BUSY);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragged = true;
                        btnLP.x = Math.max(0, Math.min(ix + (int)dx, SW - dp(78)));
                        btnLP.y = Math.max(0, Math.min(iy + (int)dy, SH - dp(78)));
                        try { wm.updateViewLayout(btnView, btnLP); } catch (Exception ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= LONG_MS) {
                            // ضغط طويل — AUTO MODE
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
                        btnView.setAlpha(autoMode ? 0.75f : ALPHA_IDLE);
                    }
                    return true;
            }
            return false;
        }
    }

    /**
     * نقرة واحدة — OCR مباشر دائماً
     * ❌ لا fallback للحافظة
     * ❌ لا popup كبير
     */
    private void onSingleTap() {
        // إذا كان الـ picker مفتوح — اغلقه
        if (pickerView != null) {
            closePicker();
            btnView.setAlpha(ALPHA_IDLE);
            return;
        }
        // إذا الـ overlay ظاهر — أخفه
        if (overlayVisible) {
            hideOverlay();
            return;
        }
        // OCR مباشر — هذا هو السلوك الافتراضي الوحيد
        doOCR();
    }

    /** نقرتان — اختيار اللغة */
    private void onDoubleTap() {
        if (overlayVisible) hideOverlay();
        openPicker();
    }

    // ════════════════════════════════════════════════════════════════
    // Google Translate (بدون مفتاح API)
    // ════════════════════════════════════════════════════════════════
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

        JSONArray root  = new JSONArray(sb.toString());
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

    // ════════════════════════════════════════════════════════════════
    // أدوات مساعدة
    // ════════════════════════════════════════════════════════════════
    private String getClipText() {
        try {
            if (clipMgr != null && clipMgr.hasPrimaryClip()) {
                android.content.ClipData.Item it = clipMgr.getPrimaryClip().getItemAt(0);
                if (it != null && it.getText() != null)
                    return it.getText().toString().trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String pairText() { return emoji(fromLang) + " → " + emoji(toLang) + " " + name(toLang); }
    private String emoji(String c) { for (String[] l : LANGS) if (l[0].equals(c)) return l[2]; return "🌐"; }
    private String name(String c)  { for (String[] l : LANGS) if (l[0].equals(c)) return l[1]; return c; }

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

    private FrameLayout.LayoutParams matchParentFLP() {
        return new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void safeRemove(View v) {
        if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════
    // الإشعار الدائم (Foreground Service)
    // ════════════════════════════════════════════════════════════════
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
            .setContentTitle("🌐 UniversalTranslator")
            .setContentText("Tap = OCR  •  Double tap = Language  •  Hold = AUTO")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
