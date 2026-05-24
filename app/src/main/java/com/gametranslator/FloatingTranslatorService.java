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
 * FloatingTranslatorService \u2014 v9
 *
 * \u0627\u0644\u062A\u063A\u064A\u064A\u0631\u0627\u062A \u0627\u0644\u062C\u0648\u0647\u0631\u064A\u0629 \u0639\u0646 v8:
 * \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
 * \u2705 OCR \u0647\u0648 \u0627\u0644\u0627\u0641\u062A\u0631\u0627\u0636\u064A \u062F\u0627\u0626\u0645\u0627\u064B \u2014 \u0644\u0627 fallback \u0644\u0644\u062D\u0627\u0641\u0638\u0629
 * \u2705 \u0625\u0630\u0627 mediaProjection == null: \u064A\u0637\u0644\u0628 \u0645\u0646 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u0625\u0639\u0627\u062F\u0629 \u0641\u062A\u062D \u0627\u0644\u062A\u0637\u0628\u064A\u0642 (\u0644\u0627 \u062D\u0627\u0641\u0638\u0629)
 * \u2705 MediaProjection \u0644\u0627 \u064A\u064F\u0639\u0627\u062F \u0625\u0646\u0634\u0627\u0624\u0647 \u2014 \u064A\u064F\u0633\u062A\u0642\u0628\u0644 \u0645\u0631\u0629 \u0648\u0627\u062D\u062F\u0629 \u0648\u064A\u0628\u0642\u0649
 * \u2705 foregroundServiceType="mediaProjection" \u0641\u064A Manifest \u2014 \u0645\u0637\u0644\u0648\u0628 Android 10+
 * \u2705 Overlay \u062E\u0641\u064A\u0641 \u0634\u0641\u0627\u0641 \u0623\u0633\u0641\u0644 \u0627\u0644\u0634\u0627\u0634\u0629 \u0641\u0642\u0637
 * \u2705 \u0644\u0627 WebView\u060C \u0644\u0627 Clipboard\u060C \u0644\u0627 activity_main
 * \u2705 AUTO MODE \u064A\u0631\u0627\u0642\u0628 \u0627\u0644\u062D\u0627\u0641\u0638\u0629 \u0627\u062E\u062A\u064A\u0627\u0631\u064A\u064B\u0627 (\u0636\u063A\u0637 \u0637\u0648\u064A\u0644)
 * \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
 *
 * \u062A\u0641\u0627\u0639\u0644\u0627\u062A \u0627\u0644\u0632\u0631 \u0627\u0644\u0639\u0627\u0626\u0645:
 *   \u0646\u0642\u0631\u0629 \u0648\u0627\u062D\u062F\u0629  \u2192 OCR \u0645\u0628\u0627\u0634\u0631 \u0645\u0646 \u0627\u0644\u0634\u0627\u0634\u0629
 *   \u0646\u0642\u0631\u062A\u0627\u0646      \u2192 \u0627\u062E\u062A\u064A\u0627\u0631 \u0627\u0644\u0644\u063A\u0629
 *   \u0636\u063A\u0637 \u0637\u0648\u064A\u0644   \u2192 AUTO MODE (\u0645\u0631\u0627\u0642\u0628\u0629 \u0627\u0644\u062D\u0627\u0641\u0638\u0629)
 */
public class FloatingTranslatorService extends Service {

    private static final String TAG        = "GT_Service";
    private static final String CHANNEL_ID = "gt_v9";
    private static final int    NOTIF_ID   = 1;

    // \u062B\u0648\u0627\u0628\u062A \u0627\u0644\u062A\u0648\u0642\u064A\u062A
    private static final long DISMISS_MS  = 10_000; // \u0625\u062E\u0641\u0627\u0621 \u0627\u0644\u0646\u062A\u064A\u062C\u0629 \u0628\u0639\u062F 10 \u062B\u0627\u0646\u064A\u0629
    private static final long LONG_MS     = 700;    // \u0636\u063A\u0637 \u0637\u0648\u064A\u0644
    private static final long DOUBLE_MS   = 350;    // \u0646\u0642\u0631\u062A\u0627\u0646

    // \u0634\u0641\u0627\u0641\u064A\u0629 \u0627\u0644\u0632\u0631
    private static final float ALPHA_IDLE = 0.40f;
    private static final float ALPHA_BUSY = 0.95f;

    // \u062C\u0645\u064A\u0639 \u0644\u063A\u0627\u062A \u0627\u0644\u0639\u0627\u0644\u0645 \u0627\u0644\u0645\u062F\u0639\u0648\u0645\u0629 \u0641\u064A Google Translate (130+ \u0644\u063A\u0629)
    private static final String[][] LANGS = {
        {"auto","Auto Detect","\uD83D\uDD0D"},
        // \u2500\u2500 Most Used \u2500\u2500
        {"ar","Arabic","\uD83C\uDDF8\uD83C\uDDE6"},
        {"en","English","\uD83C\uDDFA\uD83C\uDDF8"},
        {"ja","Japanese","\uD83C\uDDEF\uD83C\uDDF5"},
        {"ko","Korean","\uD83C\uDDF0\uD83C\uDDF7"},
        {"zh-CN","Chinese (Simplified)","\uD83C\uDDE8\uD83C\uDDF3"},
        {"zh-TW","Chinese (Traditional)","\uD83C\uDDF9\uD83C\uDDFC"},
        {"fr","French","\uD83C\uDDEB\uD83C\uDDF7"},
        {"de","German","\uD83C\uDDE9\uD83C\uDDEA"},
        {"es","Spanish","\uD83C\uDDEA\uD83C\uDDF8"},
        {"ru","Russian","\uD83C\uDDF7\uD83C\uDDFA"},
        {"tr","Turkish","\uD83C\uDDF9\uD83C\uDDF7"},
        {"it","Italian","\uD83C\uDDEE\uD83C\uDDF9"},
        {"pt","Portuguese","\uD83C\uDDE7\uD83C\uDDF7"},
        {"hi","Hindi","\uD83C\uDDEE\uD83C\uDDF3"},
        // \u2500\u2500 Europe \u2500\u2500
        {"af","Afrikaans","\uD83C\uDDFF\uD83C\uDDE6"},
        {"sq","Albanian","\uD83C\uDDE6\uD83C\uDDF1"},
        {"hy","Armenian","\uD83C\uDDE6\uD83C\uDDF2"},
        {"az","Azerbaijani","\uD83C\uDDE6\uD83C\uDDFF"},
        {"eu","Basque","\uD83C\uDFF4"},
        {"be","Belarusian","\uD83C\uDDE7\uD83C\uDDFE"},
        {"bs","Bosnian","\uD83C\uDDE7\uD83C\uDDE6"},
        {"bg","Bulgarian","\uD83C\uDDE7\uD83C\uDDEC"},
        {"ca","Catalan","\uD83C\uDFF4"},
        {"hr","Croatian","\uD83C\uDDED\uD83C\uDDF7"},
        {"cs","Czech","\uD83C\uDDE8\uD83C\uDDFF"},
        {"da","Danish","\uD83C\uDDE9\uD83C\uDDF0"},
        {"nl","Dutch","\uD83C\uDDF3\uD83C\uDDF1"},
        {"et","Estonian","\uD83C\uDDEA\uD83C\uDDEA"},
        {"fi","Finnish","\uD83C\uDDEB\uD83C\uDDEE"},
        {"gl","Galician","\uD83C\uDFF4"},
        {"ka","Georgian","\uD83C\uDDEC\uD83C\uDDEA"},
        {"el","Greek","\uD83C\uDDEC\uD83C\uDDF7"},
        {"hu","Hungarian","\uD83C\uDDED\uD83C\uDDFA"},
        {"is","Icelandic","\uD83C\uDDEE\uD83C\uDDF8"},
        {"ga","Irish","\uD83C\uDDEE\uD83C\uDDEA"},
        {"lv","Latvian","\uD83C\uDDF1\uD83C\uDDFB"},
        {"lt","Lithuanian","\uD83C\uDDF1\uD83C\uDDF9"},
        {"lb","Luxembourgish","\uD83C\uDDF1\uD83C\uDDFA"},
        {"mk","Macedonian","\uD83C\uDDF2\uD83C\uDDF0"},
        {"mt","Maltese","\uD83C\uDDF2\uD83C\uDDF9"},
        {"no","Norwegian","\uD83C\uDDF3\uD83C\uDDF4"},
        {"pl","Polish","\uD83C\uDDF5\uD83C\uDDF1"},
        {"ro","Romanian","\uD83C\uDDF7\uD83C\uDDF4"},
        {"sr","Serbian","\uD83C\uDDF7\uD83C\uDDF8"},
        {"sk","Slovak","\uD83C\uDDF8\uD83C\uDDF0"},
        {"sl","Slovenian","\uD83C\uDDF8\uD83C\uDDEE"},
        {"sv","Swedish","\uD83C\uDDF8\uD83C\uDDEA"},
        {"uk","Ukrainian","\uD83C\uDDFA\uD83C\uDDE6"},
        {"cy","Welsh","\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F"},
        {"fy","Frisian","\uD83C\uDDF3\uD83C\uDDF1"},
        {"co","Corsican","\uD83C\uDDEB\uD83C\uDDF7"},
        {"br","Breton","\uD83C\uDDEB\uD83C\uDDF7"},
        {"gd","Scots Gaelic","\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"},
        {"oc","Occitan","\uD83C\uDFF4"},
        {"la","Latin","\uD83C\uDFDB\uFE0F"},
        {"eo","Esperanto","\uD83C\uDF0D"},
        // \u2500\u2500 Asia \u2500\u2500
        {"bn","Bengali","\uD83C\uDDE7\uD83C\uDDE9"},
        {"my","Burmese","\uD83C\uDDF2\uD83C\uDDF2"},
        {"ceb","Cebuano","\uD83C\uDDF5\uD83C\uDDED"},
        {"gu","Gujarati","\uD83C\uDDEE\uD83C\uDDF3"},
        {"hmn","Hmong","\uD83C\uDFF3\uFE0F"},
        {"id","Indonesian","\uD83C\uDDEE\uD83C\uDDE9"},
        {"jw","Javanese","\uD83C\uDDEE\uD83C\uDDE9"},
        {"kn","Kannada","\uD83C\uDDEE\uD83C\uDDF3"},
        {"kk","Kazakh","\uD83C\uDDF0\uD83C\uDDFF"},
        {"km","Khmer","\uD83C\uDDF0\uD83C\uDDED"},
        {"ky","Kyrgyz","\uD83C\uDDF0\uD83C\uDDEC"},
        {"lo","Lao","\uD83C\uDDF1\uD83C\uDDE6"},
        {"ms","Malay","\uD83C\uDDF2\uD83C\uDDFE"},
        {"ml","Malayalam","\uD83C\uDDEE\uD83C\uDDF3"},
        {"mr","Marathi","\uD83C\uDDEE\uD83C\uDDF3"},
        {"mn","Mongolian","\uD83C\uDDF2\uD83C\uDDF3"},
        {"ne","Nepali","\uD83C\uDDF3\uD83C\uDDF5"},
        {"or","Odia","\uD83C\uDDEE\uD83C\uDDF3"},
        {"pa","Punjabi","\uD83C\uDDEE\uD83C\uDDF3"},
        {"si","Sinhala","\uD83C\uDDF1\uD83C\uDDF0"},
        {"su","Sundanese","\uD83C\uDDEE\uD83C\uDDE9"},
        {"tg","Tajik","\uD83C\uDDF9\uD83C\uDDEF"},
        {"ta","Tamil","\uD83C\uDDEE\uD83C\uDDF3"},
        {"te","Telugu","\uD83C\uDDEE\uD83C\uDDF3"},
        {"th","Thai","\uD83C\uDDF9\uD83C\uDDED"},
        {"tl","Filipino","\uD83C\uDDF5\uD83C\uDDED"},
        {"tk","Turkmen","\uD83C\uDDF9\uD83C\uDDF2"},
        {"ug","Uyghur","\uD83C\uDFF3\uFE0F"},
        {"ur","Urdu","\uD83C\uDDF5\uD83C\uDDF0"},
        {"uz","Uzbek","\uD83C\uDDFA\uD83C\uDDFF"},
        {"vi","Vietnamese","\uD83C\uDDFB\uD83C\uDDF3"},
        {"tt","Tatar","\uD83C\uDDF7\uD83C\uDDFA"},
        // \u2500\u2500 Middle East \u2500\u2500
        {"fa","Persian","\uD83C\uDDEE\uD83C\uDDF7"},
        {"he","Hebrew","\uD83C\uDDEE\uD83C\uDDF1"},
        {"ku","Kurdish","\uD83C\uDFF3\uFE0F"},
        {"ps","Pashto","\uD83C\uDDE6\uD83C\uDDEB"},
        {"sd","Sindhi","\uD83C\uDDF5\uD83C\uDDF0"},
        {"yi","Yiddish","\u2721\uFE0F"},
        // \u2500\u2500 Africa \u2500\u2500
        {"am","Amharic","\uD83C\uDDEA\uD83C\uDDF9"},
        {"ha","Hausa","\uD83C\uDDF3\uD83C\uDDEC"},
        {"ig","Igbo","\uD83C\uDDF3\uD83C\uDDEC"},
        {"rw","Kinyarwanda","\uD83C\uDDF7\uD83C\uDDFC"},
        {"mg","Malagasy","\uD83C\uDDF2\uD83C\uDDEC"},
        {"ny","Chichewa","\uD83C\uDDF2\uD83C\uDDFC"},
        {"om","Oromo","\uD83C\uDDEA\uD83C\uDDF9"},
        {"sn","Shona","\uD83C\uDDFF\uD83C\uDDFC"},
        {"so","Somali","\uD83C\uDDF8\uD83C\uDDF4"},
        {"st","Sesotho","\uD83C\uDDF1\uD83C\uDDF8"},
        {"sw","Swahili","\uD83C\uDDF0\uD83C\uDDEA"},
        {"ti","Tigrinya","\uD83C\uDDEA\uD83C\uDDF7"},
        {"xh","Xhosa","\uD83C\uDDFF\uD83C\uDDE6"},
        {"yo","Yoruba","\uD83C\uDDF3\uD83C\uDDEC"},
        {"zu","Zulu","\uD83C\uDDFF\uD83C\uDDE6"},
        {"ee","Ewe","\uD83C\uDDEC\uD83C\uDDED"},
        {"lg","Luganda","\uD83C\uDDFA\uD83C\uDDEC"},
        {"ln","Lingala","\uD83C\uDDE8\uD83C\uDDE9"},
        {"bm","Bambara","\uD83C\uDDF2\uD83C\uDDF1"},
        {"wo","Wolof","\uD83C\uDDF8\uD83C\uDDF3"},
        // \u2500\u2500 Latin America \u2500\u2500
        {"ht","Haitian Creole","\uD83C\uDDED\uD83C\uDDF9"},
        {"qu","Quechua","\uD83C\uDDF5\uD83C\uDDEA"},
        {"gn","Guarani","\uD83C\uDDF5\uD83C\uDDFE"},
        {"ay","Aymara","\uD83C\uDDE7\uD83C\uDDF4"},
        // \u2500\u2500 Pacific \u2500\u2500
        {"haw","Hawaiian","\uD83C\uDF3A"},
        {"mi","Maori","\uD83C\uDDF3\uD83C\uDDFF"},
        {"sm","Samoan","\uD83C\uDDFC\uD83C\uDDF8"},
    };

    // \u2550\u2550\u2550 WindowManager & \u0623\u0628\u0639\u0627\u062F \u0627\u0644\u0634\u0627\u0634\u0629 \u2550\u2550\u2550
    private WindowManager wm;
    private int SW, SH; // \u0639\u0631\u0636 \u0648\u0627\u0631\u062A\u0641\u0627\u0639 \u0627\u0644\u0634\u0627\u0634\u0629 \u0628\u0640 px

    private final Handler H = new Handler(Looper.getMainLooper());

    // \u2550\u2550\u2550 Views \u2550\u2550\u2550
    private View     btnView;
    private View     overlayView;
    private View     pickerView;
    private WindowManager.LayoutParams btnLP, overlayLP, pickerLP;
    private GradientDrawable btnCircleBg;

    // \u0645\u0631\u0627\u062C\u0639 \u0645\u0628\u0627\u0634\u0631\u0629 \u0644\u0644\u0640 TextViews \u062F\u0627\u062E\u0644 \u0627\u0644\u0640 Overlay
    private TextView tvBtnIcon;
    private View     autoDot;
    private TextView tvTranslation;
    private TextView tvOriginal;
    private TextView tvLangBar;

    // \u2550\u2550\u2550 \u0627\u0644\u062D\u0627\u0644\u0629 \u2550\u2550\u2550
    private boolean overlayVisible = false;
    private boolean autoMode       = false;
    private boolean translating    = false;
    private boolean ocrBusy        = false;
    private volatile boolean destroyed = false;

    private String fromLang = "auto";
    private String toLang   = "ar";
    private String pickerFrom = "auto";

    // \u2550\u2550\u2550 Clipboard (AUTO MODE \u0641\u0642\u0637) \u2550\u2550\u2550
    private android.content.ClipboardManager clipMgr;
    private android.content.ClipboardManager.OnPrimaryClipChangedListener clipCb;
    private String lastClipHash = "";

    // \u2550\u2550\u2550 MediaProjection & OCR \u2550\u2550\u2550
    private MediaProjection        mediaProjection;
    private MediaProjectionManager mpManager;
    private TextRecognizer         recognizerJa;   // \u064A\u0627\u0628\u0627\u0646\u064A\u0629 / \u0643\u0648\u0631\u064A\u0629 / \u0635\u064A\u0646\u064A\u0629
    private TextRecognizer         recognizerLat;  // \u0644\u0627\u062A\u064A\u0646\u064A\u0629 / \u0639\u0631\u0628\u064A\u0629 / \u0631\u0648\u0633\u064A\u0629 / \u0627\u0644\u062E

    // \u2550\u2550\u2550 Timers \u2550\u2550\u2550
    private Runnable dismissR;
    private Runnable doubleTapCheck;
    private Runnable pulseR;
    private boolean  pulseState = false;
    private int      tapCount   = 0;

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // onCreate
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mpManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        clipMgr = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        // \u0642\u064A\u0627\u0633 \u0627\u0644\u0634\u0627\u0634\u0629
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

        // \u0642\u0631\u0627\u0621\u0629 \u0627\u0644\u0644\u063A\u0629 \u0627\u0644\u0645\u062D\u0641\u0648\u0638\u0629
        fromLang = ClipboardBridge.readFromLang(this);
        toLang   = ClipboardBridge.readToLang(this);

        // \u062A\u0647\u064A\u0626\u0629 OCR
        try {
            recognizerJa  = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            recognizerLat = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            Log.d(TAG, "OCR recognizers initialized \u2713");
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // onStartCommand \u2014 \u0627\u0633\u062A\u0642\u0628\u0627\u0644 MediaProjection \u0645\u0646 MainActivity
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("mp_result_code")) {
            int    rc   = intent.getIntExtra("mp_result_code", -1);
            Intent data = intent.getParcelableExtra("mp_data");

            if (rc == android.app.Activity.RESULT_OK && data != null) {
                // \u0623\u0648\u0642\u0641 \u0627\u0644\u0642\u062F\u064A\u0645 \u0644\u0648 \u0645\u0648\u062C\u0648\u062F
                if (mediaProjection != null) {
                    try { mediaProjection.stop(); } catch (Exception ignored) {}
                    mediaProjection = null;
                }
                try {
                    mediaProjection = mpManager.getMediaProjection(rc, data);
                    Log.d(TAG, "MediaProjection created \u2713 \u2014 OCR \u062C\u0627\u0647\u0632");

                    // \u0639\u0646\u062F \u0625\u0646\u0647\u0627\u0621 MediaProjection (\u0645\u062B\u0644\u0627\u064B \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u064A\u0633\u062D\u0628 \u0627\u0644\u0625\u0630\u0646)
                    mediaProjection.registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.w(TAG, "MediaProjection stopped by system");
                            mediaProjection = null;
                            H.post(() -> {
                                if (tvBtnIcon != null) tvBtnIcon.setText("\uD83C\uDF10");
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // onDestroy
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0628\u0646\u0627\u0621 \u0627\u0644\u0632\u0631 \u0627\u0644\u0639\u0627\u0626\u0645
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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
        tvBtnIcon.setText("\uD83C\uDF10");
        tvBtnIcon.setTextSize(22f);
        tvBtnIcon.setGravity(Gravity.CENTER);
        tvBtnIcon.setIncludeFontPadding(false);
        circle.addView(tvBtnIcon, matchParentFLP());

        // \u0646\u0642\u0637\u0629 \u062E\u0636\u0631\u0627\u0621 \u2014 \u062A\u0638\u0647\u0631 \u0641\u0642\u0637 \u0641\u064A AUTO MODE
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0628\u0646\u0627\u0621 \u0627\u0644\u0640 Overlay (\u0646\u062A\u064A\u062C\u0629 \u0627\u0644\u062A\u0631\u062C\u0645\u0629)
    // \u0627\u0644\u0640 Overlay \u062E\u0641\u064A\u0641\u060C \u0634\u0641\u0627\u0641\u060C \u0623\u0633\u0641\u0644 \u0627\u0644\u0634\u0627\u0634\u0629\u060C \u0644\u0627 \u064A\u0645\u0646\u0639 \u0627\u0644\u0644\u0645\u0633
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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

        // \u0634\u0631\u064A\u0637 \u0627\u0644\u0644\u063A\u0629
        tvLangBar = new TextView(this);
        tvLangBar.setText(pairText());
        tvLangBar.setTextColor(Color.parseColor("#4a6a9a"));
        tvLangBar.setTextSize(9.5f);
        tvLangBar.setTypeface(Typeface.DEFAULT_BOLD);
        tvLangBar.setLetterSpacing(0.08f);
        tvLangBar.setPadding(0, 0, 0, dp(4));
        card.addView(tvLangBar);

        // \u062E\u0637 \u0641\u0627\u0635\u0644
        View div = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(mp(), dp(1));
        divLp.setMargins(0, 0, 0, dp(6));
        div.setBackgroundColor(Color.parseColor("#121e40"));
        card.addView(div, divLp);

        // \u0627\u0644\u0646\u0635 \u0627\u0644\u0623\u0635\u0644\u064A (\u0645\u062E\u062A\u0635\u0631)
        tvOriginal = new TextView(this);
        tvOriginal.setTextColor(Color.parseColor("#2e4466"));
        tvOriginal.setTextSize(10f);
        tvOriginal.setMaxLines(1);
        tvOriginal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvOriginal.setPadding(0, 0, 0, dp(4));
        card.addView(tvOriginal);

        // \u0646\u0635 \u0627\u0644\u062A\u0631\u062C\u0645\u0629 (\u0627\u0644\u0631\u0626\u064A\u0633\u064A)
        tvTranslation = new TextView(this);
        tvTranslation.setText("\u0627\u0636\u063A\u0637 \u0627\u0644\u0632\u0631 \u0644\u0644\u062A\u0631\u062C\u0645\u0629 \uD83C\uDF10");
        tvTranslation.setTextColor(Color.parseColor("#364a70"));
        tvTranslation.setTextSize(19f);
        tvTranslation.setTypeface(Typeface.DEFAULT_BOLD);
        tvTranslation.setLineSpacing(dp(2), 1.2f);
        tvTranslation.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        tvTranslation.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tvTranslation.setMaxLines(5);
        tvTranslation.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tvTranslation);

        // \u062A\u0644\u0645\u064A\u062D
        TextView hint = new TextView(this);
        hint.setText("\u23F1 \u064A\u062E\u062A\u0641\u064A \u0628\u0639\u062F 10 \u062B  \u2022  \u0627\u0636\u063A\u0637 \uD83C\uDF10 \u0644\u0644\u0625\u063A\u0644\u0627\u0642");
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
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // \u0644\u0627 \u064A\u0645\u0646\u0639 \u0627\u0644\u0644\u0639\u0628
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // Pulse Animation \u0644\u0644\u0632\u0631 \u0623\u062B\u0646\u0627\u0621 OCR
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // AUTO MODE \u2014 \u0645\u0631\u0627\u0642\u0628\u0629 \u0627\u0644\u062D\u0627\u0641\u0638\u0629 (\u0627\u062E\u062A\u064A\u0627\u0631\u064A\u060C \u0636\u063A\u0637 \u0637\u0648\u064A\u0644)
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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
        Toast.makeText(this, "\u2713 AUTO \u2014 \u0643\u0644 \u0646\u0635 \u062A\u0646\u0633\u062E\u0647 \u064A\u064F\u062A\u0631\u062C\u0645 \u062A\u0644\u0642\u0627\u0626\u064A\u0627\u064B", Toast.LENGTH_SHORT).show();
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // OCR \u2014 \u0627\u0644\u0642\u0644\u0628 \u0627\u0644\u0631\u0626\u064A\u0633\u064A \u0644\u0644\u062A\u0637\u0628\u064A\u0642
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    private void doOCR() {
        if (ocrBusy) {
            Log.d(TAG, "OCR already busy, skipping");
            return;
        }

        // \u2500\u2500\u2500 \u0627\u0644\u062A\u062D\u0642\u0642 \u0645\u0646 \u062A\u0648\u0641\u0631 MediaProjection \u2500\u2500\u2500
        if (mediaProjection == null) {
            Log.w(TAG, "mediaProjection is null \u2014 asking user to reopen app");
            Toast.makeText(this,
                "\u26A0 \u064A\u062D\u062A\u0627\u062C \u0625\u0639\u0627\u062F\u0629 \u0625\u0630\u0646 \u062A\u0635\u0648\u064A\u0631 \u0627\u0644\u0634\u0627\u0634\u0629\n\u0627\u0641\u062A\u062D \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0645\u062C\u062F\u062F\u0627\u064B \u0648\u0627\u0645\u0646\u062D \u0627\u0644\u0625\u0630\u0646",
                Toast.LENGTH_LONG).show();
            // \u0627\u0641\u062A\u062D MainActivity \u0644\u0625\u0639\u0627\u062F\u0629 \u0637\u0644\u0628 \u0627\u0644\u0625\u0630\u0646
            Intent reopen = new Intent(this, MainActivity.class);
            reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(reopen);
            return;
        }

        // \u2500\u2500\u2500 \u0627\u0644\u062A\u062D\u0642\u0642 \u0645\u0646 \u062A\u0648\u0641\u0631 OCR \u2500\u2500\u2500
        if (recognizerJa == null || recognizerLat == null) {
            Toast.makeText(this, "\u26A0 ML Kit \u063A\u064A\u0631 \u0645\u0647\u064A\u0623 \u2014 \u0623\u0639\u062F \u062A\u062B\u0628\u064A\u062A \u0627\u0644\u062A\u0637\u0628\u064A\u0642", Toast.LENGTH_LONG).show();
            return;
        }

        // \u2500\u2500\u2500 \u0628\u062F\u0621 OCR \u2500\u2500\u2500
        ocrBusy = true;
        tvBtnIcon.setText("\uD83D\uDCF7");
        btnView.setAlpha(ALPHA_BUSY);
        animateBtnPulse(true);

        if (tvTranslation != null) {
            tvTranslation.setText("\u23F3  \u064A\u0642\u0631\u0623 \u0627\u0644\u0634\u0627\u0634\u0629\u2026");
            tvTranslation.setTextColor(Color.parseColor("#2a4a70"));
        }
        if (tvOriginal != null) tvOriginal.setText("");
        showOverlay();

        // \u062F\u0642\u0629 \u0645\u0646\u062E\u0641\u0636\u0629 \u0644\u0633\u0631\u0639\u0629 \u0623\u0643\u0628\u0631
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
            Log.d(TAG, "VirtualDisplay created \u2713");
        } catch (Exception e) {
            Log.e(TAG, "VirtualDisplay failed: " + e.getMessage());
            ocrBusy = false;
            animateBtnPulse(false);
            tvBtnIcon.setText("\uD83C\uDF10");
            btnView.setAlpha(ALPHA_IDLE);
            mediaProjection = null; // \u0627\u0646\u062A\u0647\u0649 \u0635\u0644\u0627\u062D\u064A\u062A\u0647
            if (tvTranslation != null) {
                tvTranslation.setText("\u26A0  \u0641\u0634\u0644 \u062A\u0635\u0648\u064A\u0631 \u0627\u0644\u0634\u0627\u0634\u0629\n\u0627\u0641\u062A\u062D \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0648\u0623\u0639\u062F \u0627\u0644\u0625\u0630\u0646");
                tvTranslation.setTextColor(Color.parseColor("#ef5350"));
            }
            return;
        }

        final ImageReader finalReader = reader;
        final VirtualDisplay finalVd  = vd;

        // \u0627\u0646\u062A\u0638\u0631 350ms \u062D\u062A\u0649 \u064A\u0633\u062A\u0642\u0631 \u0627\u0644\u0640 VirtualDisplay
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
                    Log.d(TAG, "Screenshot captured \u2713 (" + capW + "x" + capH + ")");
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
                tvBtnIcon.setText("\uD83C\uDF10");
                btnView.setAlpha(ALPHA_IDLE);
                if (tvTranslation != null) {
                    tvTranslation.setText("\u26A0  \u0644\u0645 \u064A\u062A\u0645\u0643\u0646 \u0645\u0646 \u0627\u0644\u062A\u0642\u0627\u0637 \u0627\u0644\u0634\u0627\u0634\u0629\n\u0623\u0639\u062F \u0627\u0644\u0645\u062D\u0627\u0648\u0644\u0629");
                    tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                }
                return;
            }

            // \u0627\u062E\u062A\u0631 \u0627\u0644\u0645\u0639\u0631\u0650\u0651\u0641 \u0627\u0644\u0645\u0646\u0627\u0633\u0628 \u0628\u062D\u0633\u0628 \u0644\u063A\u0629 \u0627\u0644\u0645\u0635\u062F\u0631
            boolean useJa = fromLang.equals("ja") || fromLang.equals("ko")
                         || fromLang.equals("zh") || fromLang.equals("auto");
            TextRecognizer rec = useJa ? recognizerJa : recognizerLat;

            final Bitmap finalBmp = bmp;
            rec.process(InputImage.fromBitmap(finalBmp, 0))
                .addOnSuccessListener(result -> {
                    ocrBusy = false;
                    animateBtnPulse(false);
                    tvBtnIcon.setText("\uD83C\uDF10");
                    btnView.setAlpha(ALPHA_IDLE);

                    String text = result.getText().trim();
                    Log.d(TAG, "OCR result: [" + text.substring(0, Math.min(text.length(), 60)) + "]");

                    if (text.isEmpty()) {
                        if (tvTranslation != null) {
                            tvTranslation.setText("\u26A0  \u0644\u0645 \u064A\u064F\u0639\u062B\u0631 \u0639\u0644\u0649 \u0646\u0635 \u0641\u064A \u0627\u0644\u0634\u0627\u0634\u0629");
                            tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                        }
                    } else {
                        doTranslate(text);
                    }
                })
                .addOnFailureListener(e -> {
                    ocrBusy = false;
                    animateBtnPulse(false);
                    tvBtnIcon.setText("\uD83C\uDF10");
                    btnView.setAlpha(ALPHA_IDLE);
                    Log.e(TAG, "OCR failed: " + e.getMessage());
                    if (tvTranslation != null) {
                        tvTranslation.setText("\u26A0  " + e.getMessage());
                        tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                    }
                });
        }, 350);
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0627\u0644\u062A\u0631\u062C\u0645\u0629 \u0639\u0628\u0631 Google Translate API (\u0645\u062C\u0627\u0646\u064A\u0629\u060C \u0628\u062F\u0648\u0646 \u0645\u0641\u062A\u0627\u062D)
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    private void doTranslate(String text) {
        if (translating) return;
        translating = true;
        cancelDismiss();

        tvBtnIcon.setText("\u23F3");
        btnView.setAlpha(ALPHA_BUSY);

        String display = text.length() > 80 ? text.substring(0, 80) + "\u2026" : text;
        if (tvOriginal != null) tvOriginal.setText(display);
        if (tvTranslation != null) {
            tvTranslation.setText("\u23F3  \u062C\u0627\u0631\u064A \u0627\u0644\u062A\u0631\u062C\u0645\u0629\u2026");
            tvTranslation.setTextColor(Color.parseColor("#2a4a70"));
        }
        if (tvLangBar != null) tvLangBar.setText(pairText());
        showOverlay();

        final String input = text.length() > 600 ? text.substring(0, 600) : text;

        new Thread(() -> {
            try {
                String result = googleTranslate(input, fromLang, toLang);
                Log.d(TAG, "Translation \u2713: " + result.substring(0, Math.min(result.length(), 40)));

                if (destroyed) return;
                H.post(() -> {
                    translating = false;
                    tvBtnIcon.setText("\u2713");
                    H.postDelayed(() -> { if (!destroyed) tvBtnIcon.setText("\uD83C\uDF10"); }, 2_000);

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
                    tvBtnIcon.setText("\uD83C\uDF10");
                    if (tvTranslation != null) {
                        tvTranslation.setText("\u26A0  " + e.getMessage());
                        tvTranslation.setTextColor(Color.parseColor("#ef5350"));
                    }
                    btnView.setAlpha(ALPHA_IDLE);
                });
            }
        }).start();
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0627\u062E\u062A\u064A\u0627\u0631 \u0627\u0644\u0644\u063A\u0629 (\u0646\u0642\u0631\u062A\u0627\u0646)
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    interface LangCb { void pick(String code); }

    private void openPicker() {
        pickerFrom = fromLang;
        showPickerDialog("\u0645\u0646 \u0623\u064A \u0644\u063A\u0629\u061F", true, code -> {
            pickerFrom = code;
            H.post(() -> showPickerDialog("\u0625\u0644\u0649 \u0623\u064A \u0644\u063A\u0629\u061F", false, code2 -> {
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

        // \u2500\u2500\u2500 \u0639\u0646\u0648\u0627\u0646 + \u0632\u0631 \u0627\u0644\u0625\u063A\u0644\u0627\u0642 \u2500\u2500\u2500
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
        xBtn.setText("\u2715");
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

        // \u2500\u2500\u2500 \u0642\u0627\u0626\u0645\u0629 \u0627\u0644\u0644\u063A\u0627\u062A \u2500\u2500\u2500
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0645\u0639\u0627\u0644\u062C\u0629 \u0627\u0644\u0644\u0645\u0633 \u0639\u0644\u0649 \u0627\u0644\u0632\u0631 \u0627\u0644\u0639\u0627\u0626\u0645
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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
                            // \u0636\u063A\u0637 \u0637\u0648\u064A\u0644 \u2014 AUTO MODE
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
     * \u0646\u0642\u0631\u0629 \u0648\u0627\u062D\u062F\u0629 \u2014 OCR \u0645\u0628\u0627\u0634\u0631 \u062F\u0627\u0626\u0645\u0627\u064B
     * \u274C \u0644\u0627 fallback \u0644\u0644\u062D\u0627\u0641\u0638\u0629
     * \u274C \u0644\u0627 popup \u0643\u0628\u064A\u0631
     */
    private void onSingleTap() {
        // \u0625\u0630\u0627 \u0643\u0627\u0646 \u0627\u0644\u0640 picker \u0645\u0641\u062A\u0648\u062D \u2014 \u0627\u063A\u0644\u0642\u0647
        if (pickerView != null) {
            closePicker();
            btnView.setAlpha(ALPHA_IDLE);
            return;
        }
        // \u0625\u0630\u0627 \u0627\u0644\u0640 overlay \u0638\u0627\u0647\u0631 \u2014 \u0623\u062E\u0641\u0647
        if (overlayVisible) {
            hideOverlay();
            return;
        }
        // OCR \u0645\u0628\u0627\u0634\u0631 \u2014 \u0647\u0630\u0627 \u0647\u0648 \u0627\u0644\u0633\u0644\u0648\u0643 \u0627\u0644\u0627\u0641\u062A\u0631\u0627\u0636\u064A \u0627\u0644\u0648\u062D\u064A\u062F
        doOCR();
    }

    /** \u0646\u0642\u0631\u062A\u0627\u0646 \u2014 \u0627\u062E\u062A\u064A\u0627\u0631 \u0627\u0644\u0644\u063A\u0629 */
    private void onDoubleTap() {
        if (overlayVisible) hideOverlay();
        openPicker();
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // Google Translate (\u0628\u062F\u0648\u0646 \u0645\u0641\u062A\u0627\u062D API)
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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
        if (result.isEmpty()) throw new Exception("\u0627\u0644\u062A\u0631\u062C\u0645\u0629 \u0641\u0627\u0631\u063A\u0629");
        return result;
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0623\u062F\u0648\u0627\u062A \u0645\u0633\u0627\u0639\u062F\u0629
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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

    private String pairText() { return emoji(fromLang) + " \u2192 " + emoji(toLang) + " " + name(toLang); }
    private String emoji(String c) { for (String[] l : LANGS) if (l[0].equals(c)) return l[2]; return "\uD83C\uDF10"; }
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

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    // \u0627\u0644\u0625\u0634\u0639\u0627\u0631 \u0627\u0644\u062F\u0627\u0626\u0645 (Foreground Service)
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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
            .setContentTitle("\uD83C\uDF10 UniversalTranslator")
            .setContentText("Tap = OCR  \u2022  Double tap = Language  \u2022  Hold = AUTO")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
