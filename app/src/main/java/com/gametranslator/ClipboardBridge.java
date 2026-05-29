package com.gametranslator;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * ClipboardBridge — pastes the latest translation result to the system clipboard.
 *
 * Features:
 *   • Debounced: rapid successive calls within DEBOUNCE_MS are collapsed into one.
 *   • Always runs on the main thread (required by ClipboardManager).
 *   • Optional user-visible Toast confirmation.
 *   • Zero memory leaks — no static Context references.
 *
 * Usage (from any thread):
 *   ClipboardBridge.copy(context, "translated text", true);
 */
public final class ClipboardBridge {

    private static final String TAG         = "GT-Clipboard";
    private static final long   DEBOUNCE_MS = 400;

    private static final Handler H = new Handler(Looper.getMainLooper());

    // Pending copy runnable — cancelled on rapid successive calls
    private static Runnable pendingCopy;

    private ClipboardBridge() {}

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Copy text to clipboard (debounced).
     *
     * @param context     Application or Service context (not Activity)
     * @param text        Text to copy — ignored if null/empty
     * @param showToast   Whether to show a brief confirmation toast
     */
    public static void copy(Context context, String text, boolean showToast) {
        if (text == null || text.isEmpty()) return;

        // Cancel pending copy from rapid previous call
        if (pendingCopy != null) H.removeCallbacks(pendingCopy);

        final Context appCtx = context.getApplicationContext();
        pendingCopy = () -> {
            pendingCopy = null;
            doCopy(appCtx, text, showToast);
        };
        H.postDelayed(pendingCopy, DEBOUNCE_MS);
    }

    /**
     * Copy immediately — no debounce. Must be called on main thread.
     */
    public static void copyNow(Context context, String text) {
        doCopy(context.getApplicationContext(), text, false);
    }

    /** Cancel any pending debounced copy. */
    public static void cancel() {
        if (pendingCopy != null) {
            H.removeCallbacks(pendingCopy);
            pendingCopy = null;
        }
    }

    // ── Internal ──────────────────────────────────────────────────────

    private static void doCopy(Context ctx, String text, boolean showToast) {
        try {
            ClipboardManager cm =
                (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { Log.w(TAG, "ClipboardManager null"); return; }
            cm.setPrimaryClip(ClipData.newPlainText("translation", text));
            Log.d(TAG, "copied " + text.length() + " chars");
            if (showToast) {
                boolean ar = java.util.Locale.getDefault().getLanguage().equals("ar");
                Toast.makeText(ctx,
                    ar ? "تم النسخ إلى الحافظة" : "Copied to clipboard",
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "copy failed: " + e.getMessage());
        }
    }

    // ── Read last copied text ─────────────────────────────────────────

    /**
     * Returns the current primary clip text, or empty string if unavailable.
     * Must be called on main thread on API 29+.
     */
    public static String read(Context context) {
        try {
            ClipboardManager cm =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            CharSequence seq = item.getText();
            return seq != null ? seq.toString() : "";
        } catch (Exception e) {
            Log.w(TAG, "read failed: " + e.getMessage());
            return "";
        }
    }
}
