package com.gametranslator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

public class ClipboardBridge {

    private static final String PREFS_NAME = "translator_prefs";
    private static final String KEY_FROM_LANG = "from_lang";
    private static final String KEY_TO_LANG   = "to_lang";

    private final Context context;

    public ClipboardBridge(Context context) {
        this.context = context;
    }

    // ── Clipboard ──

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

    // ── إعدادات اللغة — يحفظها الـ HTML، يقرأها الـ Service ──

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

    // ── static helper — يستخدمه الـ Service مباشرة بدون WebView ──

    public static String readFromLang(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_FROM_LANG, "auto");
    }

    public static String readToLang(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TO_LANG, "ar");
    }

    // يتحقق إذا اختار المستخدم لغة من قبل
    public static boolean hasLanguageSaved(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .contains(KEY_TO_LANG);
    }

    // يستخدمه الـ Service بعد اختيار اللغة من القائمة العائمة
    public static void saveLang(Context ctx, String fromLang, String toLang) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit()
           .putString(KEY_FROM_LANG, fromLang)
           .putString(KEY_TO_LANG, toLang)
           .apply();
    }
}
