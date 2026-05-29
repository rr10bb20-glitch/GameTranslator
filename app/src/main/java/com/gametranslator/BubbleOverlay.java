package com.gametranslator;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

/**
 * BubbleOverlay — manages the compact translation result pill.
 *
 * Responsibilities:
 *   • Add / remove the bubble window via WindowManager.
 *   • Position bubble relative to the OCR stroke bounding box.
 *   • Animate show / hide with alpha transitions.
 *   • Expose setText(), setLangLabel(), show(), hide(), dismiss().
 *   • Expose a close-button callback (via OnCloseListener).
 *   • Optionally copies result text to clipboard on long-press.
 *
 * Thread-safety: all public methods post to the main thread if called from a
 * worker thread, so callers don't need to worry about threading.
 *
 * Lifecycle: call attach() once, detach() in Service.onDestroy().
 */
public final class BubbleOverlay {

    private static final String TAG = "GT-Bubble";

    // ── Dismiss timing ────────────────────────────────────────────────
    public static final long DISMISS_MS = 7_000;

    // ── Alpha states ──────────────────────────────────────────────────
    private static final float ALPHA_VISIBLE = 1f;
    private static final float ALPHA_HIDDEN  = 0f;

    // WindowManager flags
    private static final int FLAGS_TOUCHABLE =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private static final int FLAGS_PASS_THROUGH =
        FLAGS_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

    // ── Dependencies ──────────────────────────────────────────────────
    private final Context        ctx;
    private final WindowManager  wm;
    private final Handler        H  = new Handler(Looper.getMainLooper());
    private final int            SW, SH;

    // ── Views ─────────────────────────────────────────────────────────
    private View                         rootView;
    private TextView                     tvText;
    private TextView                     tvLang;
    private WindowManager.LayoutParams   lp;

    // ── State ─────────────────────────────────────────────────────────
    private boolean  visible    = false;
    private boolean  attached   = false;
    private boolean  destroyed  = false;
    private boolean  animating  = false;   // true while a show/hide animation is running
    private Runnable dismissR;

    // ── Callbacks ─────────────────────────────────────────────────────
    public interface OnCloseListener { void onClose(); }
    private OnCloseListener closeListener;

    // ── Stroke bounds (for positioning) ──────────────────────────────
    private float strokeLeft, strokeTop, strokeRight, strokeBottom;

    // ─────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────

    public BubbleOverlay(Context context, int screenW, int screenH) {
        this.ctx = context.getApplicationContext();
        this.wm  = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.SW  = screenW;
        this.SH  = screenH;
    }

    // ─────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────

    /** Add the bubble window. Call once from Service.onCreate(). */
    public void attach() {
        if (attached || destroyed) return;
        buildView();
        lp = makeLp();
        lp.flags = FLAGS_PASS_THROUGH;
        try {
            wm.addView(rootView, lp);
            attached = true;
            Log.d(TAG, "attached");
        } catch (Exception e) {
            Log.e(TAG, "attach failed: " + e.getMessage());
        }
    }

    /** Remove the bubble window. Call from Service.onDestroy(). */
    public void detach() {
        if (!attached) return;
        destroyed = true;
        cancelDismiss();
        if (rootView != null && rootView.getWindowToken() != null) {
            try { wm.removeView(rootView); } catch (Exception ignored) {}
        }
        attached = false;
        rootView = null;
        tvText = null;
        tvLang = null;
        Log.d(TAG, "detached");
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    public void setOnCloseListener(OnCloseListener l) { this.closeListener = l; }

    /**
     * Update stroke bounding box so bubble can position itself correctly.
     * Call before show().
     */
    public void setStrokeBounds(float left, float top, float right, float bottom) {
        this.strokeLeft   = left;
        this.strokeTop    = top;
        this.strokeRight  = right;
        this.strokeBottom = bottom;
    }

    /** Set the main translation text (large, bright). */
    public void setText(String text, boolean isResult) {
        runOnMain(() -> {
            if (tvText == null) return;
            tvText.setText(text);
            tvText.setTextColor(isResult
                ? Color.argb(250, 225, 240, 255)
                : Color.argb(150, 130, 165, 220));
        });
    }

    /** Set the small language label (top-left of bubble). */
    public void setLangLabel(String label) {
        runOnMain(() -> { if (tvLang != null) tvLang.setText(label); });
    }

    /**
     * Convenience: set both text and lang label at once.
     * isResult=true → bright white text (final translation)
     * isResult=false → dim blue text (status message e.g. "Translating…")
     * Does NOT trigger show() — caller must call show() explicitly.
     */
    public void update(String text, String langLabel, boolean isResult) {
        runOnMain(() -> {
            if (tvText == null) return;
            tvText.setText(text);
            tvText.setTextColor(isResult
                ? Color.argb(250, 225, 240, 255)
                : Color.argb(150, 130, 165, 220));
            if (tvLang != null) tvLang.setText(langLabel);
            // Only reposition if already visible — avoids a redundant updateViewLayout
            // when update() is called before show()
            if (visible) { reposition(); safeUpdate(); }
        });
    }

    /** Show bubble at auto-computed position, then schedule auto-dismiss. */
    public void show() {
        runOnMain(() -> {
            if (!attached || destroyed || rootView == null) return;
            // If already fully visible and not animating — just reposition, no re-animate
            if (visible && !animating && rootView.getAlpha() >= 0.99f) {
                reposition();
                safeUpdate();
                return;
            }
            visible   = true;
            animating = true;
            lp.flags  = FLAGS_TOUCHABLE;
            reposition();
            safeUpdate();
            rootView.animate().cancel();
            rootView.animate()
                .alpha(ALPHA_VISIBLE)
                .setDuration(200)
                .withEndAction(() -> animating = false)
                .start();
        });
    }

    /** Hide bubble immediately (animated). Cancels auto-dismiss timer. */
    public void hide() {
        runOnMain(() -> {
            cancelDismiss();
            if (!attached || !visible || rootView == null) return;
            visible   = false;
            animating = true;
            rootView.animate().cancel();
            rootView.animate()
                .alpha(ALPHA_HIDDEN)
                .setDuration(200)
                .withEndAction(() -> {
                    animating = false;
                    if (destroyed) return;
                    lp.flags = FLAGS_PASS_THROUGH;
                    safeUpdate();
                })
                .start();
            if (closeListener != null) closeListener.onClose();
        });
    }

    /** Schedule auto-hide after DISMISS_MS milliseconds. */
    public void scheduleDismiss() {
        cancelDismiss();
        dismissR = this::hide;
        H.postDelayed(dismissR, DISMISS_MS);
    }

    public void cancelDismiss() {
        if (dismissR != null) { H.removeCallbacks(dismissR); dismissR = null; }
    }

    public boolean isVisible() { return visible; }

    // ─────────────────────────────────────────────────────────────────
    // View construction
    // ─────────────────────────────────────────────────────────────────

    private void buildView() {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(11), dp(6), dp(11), dp(9));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.argb(210, 4, 10, 26));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.argb(140, 55, 130, 255));
        pill.setBackground(bg);
        pill.setElevation(dp(8));

        // ── Header row: lang label + close × ─────────────────────────
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        tvLang = new TextView(ctx);
        tvLang.setTextColor(Color.argb(170, 80, 150, 255));
        tvLang.setTextSize(8f);
        tvLang.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvLang, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView xBtn = new TextView(ctx);
        xBtn.setText(" ×");
        xBtn.setTextColor(Color.argb(200, 220, 80, 80));
        xBtn.setTextSize(13f);
        xBtn.setClickable(true);
        xBtn.setFocusable(true);
        xBtn.setOnClickListener(v -> hide());
        header.addView(xBtn);
        pill.addView(header);

        // ── Divider ───────────────────────────────────────────────────
        View divider = new View(ctx);
        LinearLayout.LayoutParams divLp =
            new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(2), 0, dp(4));
        divider.setBackgroundColor(Color.argb(50, 55, 120, 220));
        pill.addView(divider, divLp);

        // ── Translation text ──────────────────────────────────────────
        tvText = new TextView(ctx);
        tvText.setTextColor(Color.argb(250, 225, 240, 255));
        tvText.setTextSize(14f);
        tvText.setTypeface(Typeface.DEFAULT_BOLD);
        tvText.setLineSpacing(dp(1), 1.1f);
        tvText.setMaxLines(5);
        tvText.setEllipsize(android.text.TextUtils.TruncateAt.END);

        // Long-press copies text to clipboard
        tvText.setOnLongClickListener(v -> {
            CharSequence t = tvText.getText();
            if (t != null && t.length() > 0)
                ClipboardBridge.copy(ctx, t.toString(), true);
            return true;
        });
        pill.addView(tvText);

        pill.setAlpha(ALPHA_HIDDEN);
        rootView = pill;
    }

    // ── Window layout params ──────────────────────────────────────────

    private WindowManager.LayoutParams makeLp() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            (int)(SW * 0.72f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            FLAGS_PASS_THROUGH,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(8);
        p.y = dp(8);
        return p;
    }

    // ── Position bubble near stroke bounds ───────────────────────────

    private void reposition() {
        if (lp == null) return;
        // Horizontal: centre on stroke
        int  w      = lp.width;
        int  margin = dp(8);
        int  x      = (int)((strokeLeft + strokeRight) / 2f) - w / 2;
        x = Math.max(margin, Math.min(x, SW - w - margin));

        // Vertical: prefer above stroke, fall back to below
        int pillH = dp(84);
        int y = (strokeTop > pillH + margin * 2)
            ? (int) strokeTop - pillH - margin
            : (int) strokeBottom + margin;
        y = Math.max(dp(4), Math.min(y, SH - pillH - dp(4)));

        lp.x = x;
        lp.y = y;
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private void safeUpdate() {
        if (rootView == null || lp == null || rootView.getWindowToken() == null) return;
        try { wm.updateViewLayout(rootView, lp); }
        catch (Exception e) { Log.e(TAG, "updateLayout: " + e.getMessage()); }
    }

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else H.post(r);
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }
}
