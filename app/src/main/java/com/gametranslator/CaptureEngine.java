package com.gametranslator;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CaptureEngine — v23
 *
 * Owns all MediaProjection / VirtualDisplay / ImageReader logic.
 *
 * Root causes of "Capture failed" this class fixes:
 *
 *   BUG-1 — Null frame after VD recreation (most common):
 *     enterEngineSleep() calls releaseVD() every 5s of idle.
 *     On next capture a fresh VD needs time to render its first frame.
 *     Without a warmup wait, acquireLatestImage() returns null → onError.
 *     Fix: CountDownLatch waits up to VD_WARMUP_TIMEOUT_MS for the first frame.
 *
 *   BUG-2 — Row padding on wide-screen / high-DPI devices:
 *     ImageReader.rowStride can be > width * 4 (RGBA). Copying a buffer
 *     with padding into a bitmap of size width × height corrupts pixels
 *     or throws a Buffer too small exception.
 *     Fix: imageToBitmap() calculates rowPadding and crops if necessary.
 *
 *   BUG-3 — SecurityException on stale MediaProjection token:
 *     Xiaomi/Honor/MIUI can invalidate the token silently (no onStop).
 *     Fix: setProjection() catches SecurityException separately and logs it
 *     so the service can request a new token.
 *
 *   BUG-4 — Concurrent acquire guard:
 *     If two strokes fire quickly, a second acquireFrame() call while the
 *     first is in-flight causes VD dimension conflicts.
 *     Fix: AtomicBoolean acquiring guard, returns onError("acquire_busy").
 *
 * Threading model:
 *   acquireFrame()  → called from background (worker) thread
 *   FrameCallback   → invoked on the same background thread
 *   ImageReader CB  → delivered on private HandlerThread (irThread)
 *   setProjection() / releaseVD() / release() → synchronized, safe from any thread
 */
public class CaptureEngine {

    private static final String TAG = "GT-Capture";

    // ── Timing constants ──────────────────────────────────────────────
    /**
     * How long to wait for the FIRST frame from a freshly created VirtualDisplay.
     * 1500ms is conservative — most devices render in < 400ms.
     * Increase if capture still fails on slow devices after VD rebuild.
     */
    private static final long VD_WARMUP_TIMEOUT_MS = 1_500;

    /**
     * How long to wait for a frame from an existing (warm) VirtualDisplay.
     */
    private static final long FRAME_TIMEOUT_MS = 2_000;

    /**
     * Polling fallback: retries after latch times out.
     * POLL_RETRY_MAX × POLL_RETRY_DELAY_MS = additional wait budget.
     */
    private static final int  POLL_RETRY_MAX      = 5;
    private static final long POLL_RETRY_DELAY_MS = 100;

    // ── State ─────────────────────────────────────────────────────────
    private MediaProjection mp;
    private VirtualDisplay  vd;
    private ImageReader     imageReader;

    /** Track VD dimensions — rebuild if they change (orientation / screen size change). */
    private int currentW = 0, currentH = 0, currentDpi = 0;

    /**
     * true immediately after buildVirtualDisplay() succeeds.
     * Causes acquireFrame() to use the longer VD_WARMUP_TIMEOUT_MS.
     * Reset to false after the first successful frame.
     */
    private boolean freshVD = false;

    /** Prevents concurrent acquireFrame() calls from racing on VD state. */
    private final AtomicBoolean acquiring = new AtomicBoolean(false);

    /**
     * Dedicated HandlerThread for ImageReader.OnImageAvailableListener callbacks.
     * MUST be separate from the main thread — if the listener fires on the same
     * thread that is blocked on latch.await(), we get deadlock.
     */
    private HandlerThread irThread;
    private Handler       irHandler;

    // ─────────────────────────────────────────────────────────────────
    // Public callback interface
    // ─────────────────────────────────────────────────────────────────

    public interface FrameCallback {
        /** Called with a full-screen Bitmap. Caller MUST release via BitmapPool.release(). */
        void onFrame(Bitmap fullBmp);

        /**
         * Called when capture fails.
         * @param reason machine-readable tag, one of:
         *   "acquire_busy"           — concurrent call, safe to ignore
         *   "no_projection"          — MP token missing, ask user to reopen app
         *   "vd_build_failed"        — generic VD creation failure
         *   "vd_security_exception"  — stale token, ask user to reopen app
         *   "frame_unavailable"      — all strategies exhausted (null frame)
         *   "bitmap_null"            — imageToBitmap returned null
         *   "bitmap_exception:<msg>" — unexpected crash in bitmap conversion
         */
        void onError(String reason);
    }

    // ─────────────────────────────────────────────────────────────────
    // Constructor / lifecycle
    // ─────────────────────────────────────────────────────────────────

    public CaptureEngine() {
        irThread = new HandlerThread("GT-ImageReader");
        irThread.start();
        irHandler = new Handler(irThread.getLooper());
        Log.d(TAG, "CaptureEngine created, irThread started");
    }

    /**
     * Set or replace the MediaProjection token.
     * Releases the old VirtualDisplay before assigning the new projection.
     * Safe to call multiple times (handles token refresh on MIUI/EMUI).
     */
    public synchronized void setProjection(MediaProjection newMp) {
        Log.d(TAG, "setProjection called — releasing VD only (token preserved)");
        Log.d(TAG, "setProjection: old mp=" + (mp != null ? "exists" : "null")
            + "  new mp=" + (newMp != null ? "exists" : "null"));

        releaseVD();

        // ── لا نستدعي mp.stop() ──────────────────────────────────────────
        // السبب: mp.stop() يُلغي الـ Token نهائياً.
        // إذا استُدعيت setProjection() أكثر من مرة (تدوير شاشة / restart)
        // كان mp.stop() يجبر المستخدم على إعادة منح إذن Screen Capture.
        // الـ Token القديم يتحرر تلقائياً عند GC — لا حاجة لإيقافه يدوياً.

        mp = newMp;
        Log.d(TAG, "setProjection: token assigned OK — VD built on next acquireFrame()");
    }

    /** @return true if a MediaProjection token is held */
    public synchronized boolean hasProjection() {
        return mp != null;
    }

    /**
     * Release VirtualDisplay + ImageReader but keep the MediaProjection token alive.
     * Call this for idle sleep — allows fast resume without requesting a new token.
     */
    public synchronized void releaseVD() {
        if (vd != null) {
            try { vd.release(); }
            catch (Exception e) { Log.w(TAG, "releaseVD: " + e.getMessage()); }
            vd = null;
            Log.d(TAG, "VD released");
        }
        if (imageReader != null) {
            try { imageReader.close(); }
            catch (Exception e) { Log.w(TAG, "closeIR: " + e.getMessage()); }
            imageReader = null;
            Log.d(TAG, "ImageReader closed");
        }
        currentW = currentH = currentDpi = 0;
        freshVD  = false;
    }

    /**
     * Full teardown — call from Service.onDestroy().
     * Stops the MediaProjection and kills the HandlerThread.
     */
    public synchronized void release() {
        Log.d(TAG, "release() — full teardown");
        releaseVD();

        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            mp = null;
        }

        if (irThread != null) {
            irThread.quitSafely();
            irThread = null;
            irHandler = null;
        }
        Log.d(TAG, "CaptureEngine released");
    }

    // ─────────────────────────────────────────────────────────────────
    // Frame acquisition — entry point
    // ─────────────────────────────────────────────────────────────────

    /**
     * Capture one full-screen frame.
     *
     * MUST be called from a background thread — this method blocks until a frame
     * is ready (up to VD_WARMUP_TIMEOUT_MS or FRAME_TIMEOUT_MS).
     *
     * FrameCallback is invoked on the same calling thread.
     * The returned Bitmap MUST be released by the caller via BitmapPool.release().
     *
     * @param screenW  Live screen width  (use liveScreenSize(), NOT stale SW field)
     * @param screenH  Live screen height (use liveScreenSize(), NOT stale SH field)
     * @param dpi      Display density dpi
     * @param cb       Result callback
     */
    public void acquireFrame(int screenW, int screenH, int dpi, FrameCallback cb) {
        // ── Guard: no concurrent captures ────────────────────────────
        if (!acquiring.compareAndSet(false, true)) {
            Log.w(TAG, "acquireFrame: already in-progress — skipping (acquire_busy)");
            cb.onError("acquire_busy");
            return;
        }

        try {
            acquireFrameInternal(screenW, screenH, dpi, cb);
        } finally {
            acquiring.set(false);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal implementation
    // ─────────────────────────────────────────────────────────────────

    private void acquireFrameInternal(int screenW, int screenH, int dpi, FrameCallback cb) {
        Log.d(TAG, "acquireFrameInternal: target=" + screenW + "x" + screenH + " dpi=" + dpi);

        // ── Step 1: Ensure VD is built with correct dimensions ────────
        boolean needRebuild;
        synchronized (this) {
            needRebuild = (vd == null)
                || imageReader == null
                || (currentW != screenW)
                || (currentH != screenH)
                || (currentDpi != dpi);
        }

        if (needRebuild) {
            Log.d(TAG, "VD rebuild triggered — was=" + currentW + "x" + currentH
                + " dpi=" + currentDpi + " → new=" + screenW + "x" + screenH + " dpi=" + dpi);

            String buildError = buildVirtualDisplay(screenW, screenH, dpi);
            if (buildError != null) {
                Log.e(TAG, "buildVD failed: " + buildError);
                cb.onError(buildError);
                return;
            }
        }

        // ── Step 2: Acquire frame using latch (primary strategy) ──────
        long timeoutMs = freshVD ? VD_WARMUP_TIMEOUT_MS : FRAME_TIMEOUT_MS;
        Log.d(TAG, "waiting for frame: timeout=" + timeoutMs + "ms freshVD=" + freshVD);

        Bitmap bmp = acquireWithLatch(screenW, screenH, timeoutMs);

        if (bmp != null) {
            freshVD = false;
            Log.d(TAG, "acquireFrame OK via latch: " + bmp.getWidth() + "x" + bmp.getHeight());
            cb.onFrame(bmp);
            return;
        }

        // ── Step 3: Polling fallback ───────────────────────────────────
        Log.w(TAG, "latch strategy returned null — falling back to polling");
        bmp = acquireWithPolling(screenW, screenH);

        if (bmp != null) {
            freshVD = false;
            Log.d(TAG, "acquireFrame OK via polling: " + bmp.getWidth() + "x" + bmp.getHeight());
            cb.onFrame(bmp);
            return;
        }

        // ── Step 4: All strategies exhausted ─────────────────────────
        Log.e(TAG, "acquireFrame: ALL strategies failed for " + screenW + "x" + screenH);
        cb.onError("frame_unavailable");
    }

    // ─────────────────────────────────────────────────────────────────
    // VirtualDisplay construction
    // ─────────────────────────────────────────────────────────────────

    /**
     * Build or resize a VirtualDisplay + ImageReader.
     *
     * Strategy:
     *   1. If VD already exists → try vd.resize() + new ImageReader (avoids touching mp token).
     *      This is the Samsung fix: orientation change no longer calls createVirtualDisplay().
     *   2. If resize fails or VD doesn't exist → full rebuild via createVirtualDisplay().
     *
     * @return null on success, or error reason string on failure.
     */
    private synchronized String buildVirtualDisplay(int w, int h, int dpi) {
        if (mp == null) {
            Log.e(TAG, "buildVD: MediaProjection is null — token not set");
            return "no_projection";
        }

        // ── Strategy 1: resize existing VD (no mp touch) ─────────────
        if (vd != null && imageReader != null) {
            Log.d(TAG, "buildVD: trying resize " + currentW + "x" + currentH
                    + " → " + w + "x" + h + " dpi=" + dpi);
            try {
                ImageReader newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                vd.resize(w, h, dpi);
                vd.setSurface(newReader.getSurface());

                // close old reader AFTER new surface is attached
                try { imageReader.close(); } catch (Exception ignored) {}
                imageReader = newReader;

                currentW   = w;
                currentH   = h;
                currentDpi = dpi;
                freshVD    = true;

                Log.d(TAG, "buildVD: resize OK — mp token untouched");
                return null; // success

            } catch (Exception e) {
                // resize failed — fall through to full rebuild
                Log.w(TAG, "buildVD: resize failed (" + e.getMessage() + ") — full rebuild");
                releaseVD();
            }
        } else {
            // no existing VD — start clean
            releaseVD();
        }

        // ── Strategy 2: full rebuild via createVirtualDisplay ─────────
        Log.d(TAG, "buildVD: full rebuild " + w + "x" + h + " dpi=" + dpi);
        try {
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            Log.d(TAG, "ImageReader created: " + w + "x" + h + " RGBA_8888 maxImages=2");

            vd = mp.createVirtualDisplay(
                "GT-VD",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,   // DisplayCallback — not needed
                null);  // Handler — null = same thread as mp

            currentW   = w;
            currentH   = h;
            currentDpi = dpi;
            freshVD    = true;

            Log.d(TAG, "VD built OK: " + w + "x" + h + " dpi=" + dpi
                + " freshVD=true warmup=" + VD_WARMUP_TIMEOUT_MS + "ms");
            return null; // success

        } catch (SecurityException se) {
            Log.e(TAG, "buildVD SecurityException (stale token): " + se.getMessage());
            releaseVD();
            return "vd_security_exception";

        } catch (Exception e) {
            Log.e(TAG, "buildVD Exception: " + e.getMessage(), e);
            releaseVD();
            return "vd_build_failed";
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Strategy A — CountDownLatch (primary, most reliable)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Set an OnImageAvailableListener and block via CountDownLatch until a frame arrives
     * or timeout is reached.
     *
     * The listener runs on irHandler (HandlerThread), NOT on the calling thread.
     * This is critical — if both ran on the same thread, latch.await() would deadlock.
     *
     * @return Bitmap on success, null on timeout or conversion failure.
     */
    private Bitmap acquireWithLatch(int screenW, int screenH, long timeoutMs) {
        ImageReader reader;
        Handler     handler;
        synchronized (this) {
            reader  = imageReader;
            handler = irHandler;
        }

        if (reader == null || handler == null) {
            Log.w(TAG, "acquireWithLatch: reader or handler is null");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);

        // Listener just signals the latch — actual acquireLatestImage happens below
        // to avoid holding the Image across thread boundaries.
        try {
            reader.setOnImageAvailableListener(r -> {
                // This fires on irHandler thread
                Log.d(TAG, "OnImageAvailable fired");
                latch.countDown();
            }, handler);

        } catch (Exception e) {
            Log.w(TAG, "setOnImageAvailableListener failed: " + e.getMessage());
            return null;
        }

        boolean arrived;
        try {
            arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "latch interrupted");
            arrived = false;
        } finally {
            // Always remove listener to prevent stale callbacks on next capture
            try { reader.setOnImageAvailableListener(null, null); }
            catch (Exception ignored) {}
        }

        Log.d(TAG, "latch: arrived=" + arrived + " timeout=" + timeoutMs + "ms");
        if (!arrived) return null;

        // Frame is ready — acquire it
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) {
                Log.w(TAG, "latch: signal received but acquireLatestImage() returned null");
                return null;
            }
            Bitmap bmp = imageToBitmap(img, screenW, screenH);
            Log.d(TAG, "latch: imageToBitmap → " + (bmp != null
                ? bmp.getWidth() + "x" + bmp.getHeight() : "NULL"));
            return bmp;

        } catch (Exception e) {
            Log.e(TAG, "latch acquireLatestImage/convert: " + e.getMessage(), e);
            return null;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Strategy B — Polling (fallback)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Repeatedly call acquireLatestImage() with sleep intervals.
     * Used when the latch strategy times out (rare, e.g. older API devices).
     *
     * @return Bitmap on success, null if all retries exhausted.
     */
    private Bitmap acquireWithPolling(int screenW, int screenH) {
        ImageReader reader;
        synchronized (this) { reader = imageReader; }
        if (reader == null) {
            Log.w(TAG, "acquireWithPolling: imageReader is null");
            return null;
        }

        for (int i = 0; i < POLL_RETRY_MAX; i++) {
            try { Thread.sleep(POLL_RETRY_DELAY_MS); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "polling interrupted at attempt=" + i);
                break;
            }

            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img != null) {
                    Log.d(TAG, "polling: got frame at attempt=" + i);
                    Bitmap bmp = imageToBitmap(img, screenW, screenH);
                    img.close();
                    img = null;
                    return bmp;
                }
                Log.w(TAG, "polling[" + i + "]: acquireLatestImage() null");

            } catch (Exception e) {
                Log.w(TAG, "polling[" + i + "] exception: " + e.getMessage());
            } finally {
                if (img != null) { try { img.close(); } catch (Exception ignored) {} }
            }
        }

        Log.e(TAG, "polling: all " + POLL_RETRY_MAX + " retries exhausted");
        return null;
    }

    // ─────────────────────────────────────────────────────────────────
    // Image → Bitmap conversion
    // ─────────────────────────────────────────────────────────────────

    /**
     * Convert an ImageReader Image (RGBA_8888) to a Bitmap.
     *
     * KEY FIX (BUG-2 — wide-screen / high-DPI row padding):
     *   On many devices rowStride > width × 4.
     *   The extra bytes are padding — copyPixelsFromBuffer() with the raw buffer
     *   would corrupt the bitmap (shifted rows) or crash with a buffer-too-small error.
     *
     *   We compute rowPadding and create the Bitmap with the padded width first,
     *   then crop it to expectedW × expectedH if padding exists.
     *
     * Memory note:
     *   Main path (no padding): returns a BitmapPool bitmap — caller releases via BitmapPool.release().
     *   Padding path: the padded bitmap is released to pool; the cropped result is a
     *                 regular Bitmap.createBitmap — caller should still call BitmapPool.release()
     *                 which will recycle it.
     *
     * @param image     Image from acquireLatestImage() — caller closes it.
     * @param expectedW Intended screen width (from liveScreenSize())
     * @param expectedH Intended screen height
     * @return Bitmap or null on failure
     */
    private Bitmap imageToBitmap(Image image, int expectedW, int expectedH) {
        if (image == null) {
            Log.e(TAG, "imageToBitmap: image is null");
            return null;
        }

        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            Log.e(TAG, "imageToBitmap: no planes in image");
            return null;
        }

        ByteBuffer buf         = planes[0].getBuffer();
        int        pixelStride = planes[0].getPixelStride();  // bytes per pixel (should be 4 for RGBA)
        int        rowStride   = planes[0].getRowStride();    // bytes per row (may include padding)
        int        rowPadding  = rowStride - pixelStride * expectedW;

        // Actual bitmap width accounting for row padding
        int bmpW = expectedW + (pixelStride > 0 ? rowPadding / pixelStride : 0);

        Log.d(TAG, "imageToBitmap:"
            + " expectedW=" + expectedW + " expectedH=" + expectedH
            + " pixelStride=" + pixelStride + " rowStride=" + rowStride
            + " rowPadding=" + rowPadding + " bmpW=" + bmpW);

        if (buf == null || buf.capacity() < rowStride * expectedH) {
            Log.e(TAG, "imageToBitmap: buffer too small: capacity=" + (buf != null ? buf.capacity() : 0)
                + " required≈" + (rowStride * expectedH));
            return null;
        }

        Bitmap raw = null;
        try {
            raw = BitmapPool.acquire(bmpW, expectedH);
            buf.rewind();
            raw.copyPixelsFromBuffer(buf);

        } catch (Exception e) {
            Log.e(TAG, "imageToBitmap copyPixelsFromBuffer: " + e.getMessage());
            if (raw != null) BitmapPool.release(raw);
            return null;
        }

        // No padding — return raw bitmap directly
        if (bmpW == expectedW) {
            return raw;
        }

        // Crop padding columns — result is a regular Bitmap (not from pool)
        Log.d(TAG, "imageToBitmap: cropping row padding " + bmpW + " → " + expectedW);
        Bitmap cropped;
        try {
            cropped = Bitmap.createBitmap(raw, 0, 0, expectedW, expectedH);
        } catch (Exception e) {
            Log.e(TAG, "imageToBitmap padding crop failed: " + e.getMessage());
            BitmapPool.release(raw);
            return null;
        }
        BitmapPool.release(raw); // return padded raw to pool
        return cropped;
    }

    // ─────────────────────────────────────────────────────────────────
    // Static utility — crop coordinate mapping
    // ─────────────────────────────────────────────────────────────────

    /**
     * Map stroke screen coordinates → bitmap pixel crop rectangle.
     *
     * Uses liveW/liveH (NOT stale SW/SH) so crop is correct after orientation
     * changes or when game runs in landscape while service stored portrait dims.
     *
     * @param sL, sT, sR, sB  Stroke bounding box in screen pixels
     * @param bmpW, bmpH       Actual bitmap dimensions from imageToBitmap
     * @param liveW, liveH     Live screen dimensions passed to acquireFrame
     * @param minW, minH       Minimum valid crop size (reject tiny selections)
     * @return int[4] = {x, y, width, height} or null if invalid/too-small
     */
    public static int[] computeCrop(
        float sL, float sT, float sR, float sB,
        int bmpW,  int bmpH,
        int liveW, int liveH,
        int minW,  int minH
    ) {
        if (liveW <= 0 || liveH <= 0 || bmpW <= 0 || bmpH <= 0) {
            Log.e(TAG, "computeCrop: invalid dimensions"
                + " liveW=" + liveW + " liveH=" + liveH
                + " bmpW=" + bmpW + " bmpH=" + bmpH);
            return null;
        }

        float scaleX = (float) bmpW / liveW;
        float scaleY = (float) bmpH / liveH;

        int x  = (int) Math.max(0,    sL * scaleX);
        int y  = (int) Math.max(0,    sT * scaleY);
        int x2 = (int) Math.min(bmpW, sR * scaleX);
        int y2 = (int) Math.min(bmpH, sB * scaleY);
        int w  = x2 - x;
        int h  = y2 - y;

        Log.d(TAG, "computeCrop:"
            + " stroke=[" + (int)sL + "," + (int)sT + "→" + (int)sR + "," + (int)sB + "]"
            + " scale=[" + String.format("%.3f", scaleX) + "×" + String.format("%.3f", scaleY) + "]"
            + " crop=[" + x + "," + y + " " + w + "×" + h + "]"
            + " bmp=[" + bmpW + "×" + bmpH + "]"
            + " live=[" + liveW + "×" + liveH + "]");

        if (w < minW || h < minH) {
            Log.w(TAG, "computeCrop: result too small " + w + "×" + h
                + " (min " + minW + "×" + minH + ") — rejected");
            return null;
        }

        return new int[]{ x, y, w, h };
    }
}
