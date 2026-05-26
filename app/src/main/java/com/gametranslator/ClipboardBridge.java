package com.gametranslator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * ClipboardBridge — v2
 *
 * Changes vs v1:
 * ─ PREFS_NAME unified to "ut_prefs" — matches MainActivity & FloatingTranslatorService
 * ─ KEY_FROM_LANG / KEY_TO_LANG unified to "lang_from" / "lang_to" — same keys everywhere
 * ─ context stored as applicationContext — prevents Activity/WebView memory leak
 * ─ SharedPreferences instance cached in constructor — avoids repeated lookup
 * ─ getText(): itemCount > 0 guard before getItemAt(0) — fixes empty-clip crash on some ROMs
 * ─ catch(Exception ignored) replaced with Log.e() — silent bugs become visible
 */
public class ClipboardBridge {

    private static final String TAG        = "ClipboardBridge";

    // ── Unified with MainActivity & FloatingTranslatorService ─────
    private static final String PREFS_NAME   = "ut_prefs";
    private static final String KEY_FROM_LANG = "lang_from";
    private static final String KEY_TO_LANG   = "lang_to";

    private final Context          context; // applicationContext — no leak
    private final SharedPreferences prefs;  // cached instance

    public ClipboardBridge(Context context) {
        this.context = context.getApplicationContext();
        this.prefs   = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Clipboard ──────────────────────────────────────────────────

    @JavascriptInterface
    public String getText() {
        try {
            ClipboardManager cm = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                // Guard: some ROMs return a non-null but empty ClipData
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) return text.toString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getText failed", e);
        }
        return "";
    }

    @JavascriptInterface
    public void setText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("translation", text));
            }
        } catch (Exception e) {
            Log.e(TAG, "setText failed", e);
        }
    }

    // ── Language settings — saved by HTML, read by Service ────────

    @JavascriptInterface
    public void saveLanguages(String fromLang, String toLang) {
        try {
            prefs.edit()
                 .putString(KEY_FROM_LANG, fromLang)
                 .putString(KEY_TO_LANG,   toLang)
                 .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveLanguages failed", e);
        }
    }

    @JavascriptInterface
    public String getFromLang() {
        return prefs.getString(KEY_FROM_LANG, "auto");
    }

    @JavascriptInterface
    public String getToLang() {
        return prefs.getString(KEY_TO_LANG, "ar");
    }

    // ── Static helpers — used by Service directly without WebView ──

    public static String readFromLang(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .getString(KEY_FROM_LANG, "auto");
    }

    public static String readToLang(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .getString(KEY_TO_LANG, "ar");
    }

    public static boolean hasLanguageSaved(Context ctx) {
        return ctx.getApplicationContext()
                  .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .contains(KEY_TO_LANG);
    }

    public static void saveLang(Context ctx, String fromLang, String toLang) {
        ctx.getApplicationContext()
           .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit()
           .putString(KEY_FROM_LANG, fromLang)
           .putString(KEY_TO_LANG,   toLang)
           .apply();
    }
}
