package com.gametranslator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.util.Locale;

public class ClipboardBridge {

    private static final String PREFS_NAME   = "translator_prefs";
    private static final String KEY_FROM_LANG = "from_lang";
    private static final String KEY_TO_LANG   = "to_lang";

    private final Context context;

    public ClipboardBridge(Context context) {
        this.context = context;
    }

    // ══════════════════════════════════════════════════════
    // الـ methods القديمة — ما مسيناها
    // ══════════════════════════════════════════════════════

    @JavascriptInterface
    public String getText() {
        try {
            ClipboardManager cm = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                CharSequence text = item.getText();
                if (text != null) return text.toString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    @JavascriptInterface
    public void setText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("translation", text);
                cm.setPrimaryClip(clip);
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void saveLanguages(String fromLang, String toLang) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                 .putString(KEY_FROM_LANG, fromLang)
                 .putString(KEY_TO_LANG, toLang)
                 .apply();
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String getFromLang() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_FROM_LANG, "auto");
    }

    @JavascriptInterface
    public String getToLang() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TO_LANG, "ar");
    }

    public static String readFromLang(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_FROM_LANG, "auto");
    }

    public static String readToLang(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TO_LANG, "ar");
    }

    public static boolean hasLanguageSaved(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .contains(KEY_TO_LANG);
    }

    public static void saveLang(Context ctx, String fromLang, String toLang) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit()
           .putString(KEY_FROM_LANG, fromLang)
           .putString(KEY_TO_LANG, toLang)
           .apply();
    }

    // ══════════════════════════════════════════════════════
    // methods جديدة — يطلبها BubbleOverlay + FloatingTranslatorService
    // ══════════════════════════════════════════════════════

    // pending debounce runnable
    private static Runnable pendingCopy;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_MS = 400;

    /**
     * يستخدمه BubbleOverlay عند long-press:
     * ClipboardBridge.copy(ctx, text, true)
     */
    public static void copy(Context context, String text, boolean showToast) {
        if (text == null || text.isEmpty()) return;

        // إلغاء أي copy معلق
        if (pendingCopy != null) H.removeCallbacks(pendingCopy);

        final Context appCtx = context.getApplicationContext();
        pendingCopy = () -> {
            pendingCopy = null;
            try {
                ClipboardManager cm = (ClipboardManager)
                    appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                cm.setPrimaryClip(ClipData.newPlainText("translation", text));
                if (showToast) {
                    boolean ar = Locale.getDefault().getLanguage().equals("ar");
                    Toast.makeText(appCtx,
                        ar ? "تم النسخ إلى الحافظة" : "Copied to clipboard",
                        Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {}
        };
        H.postDelayed(pendingCopy, DEBOUNCE_MS);
    }

    /**
     * يستخدمه FloatingTranslatorService.onDestroy():
     * ClipboardBridge.cancel()
     */
    public static void cancel() {
        if (pendingCopy != null) {
            H.removeCallbacks(pendingCopy);
            pendingCopy = null;
        }
    }
}
