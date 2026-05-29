package com.gametranslator;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * CaptureEngine — owns the MediaProjection / VirtualDisplay / ImageReader lifecycle.
 *
 * Design goals:
 *   • One persistent VirtualDisplay per MediaProjection session (no re-create on every frame).
 *   • Correct bitmap dimensions for ALL resolutions including wide-screen and high-DPI devices.
 *   • Handles GPU row-stride padding (bmpW ≥ capW in practice on some GPUs).
 *   • Null-frame recovery: if the game steals the surface, we rebuild the VD automatically.
 *   • Never re-requests MediaProjection permission — the projection token is kept alive.
 *   • Thread-safe: public methods may be called from any thread.
 *
 * Callers are responsible for providing a valid MediaProjection token obtained via
 * MediaProjectionManager in MainActivity, and passing it via setProjection().
 *
 * Usage:
 *   captureEngine.setProjection(mp);
 *   captureEngine.acquireFrame(screenW, screenH, crop, density, callback);
 */
public final class CaptureEngine {

    private static final String TAG = "GT-Capture";

    // ── Null-frame recovery threshold ────────────────────────────────
    private static final int  VD_NULL_FRAME_LIMIT  = 3;
    private static final long VD_RECREATE_BACKOFF_MS = 2_000; // min time between recreates
    private static final long CAPTURE_DELAY_MS     = 550;
    private static final long FAST_DELAY_MS        = 120;

    // ── VD watchdog: stale = no successful frame for this many ms ────
    private static final long WATCHDOG_STALE_MS = 8_000;

    // ─────────────────────────────────────────────────────────────────
    // State — guarded by lock
    // ─────────────────────────────────────────────────────────────────

    private final Object lock = new Object();

    private MediaProjection mp;
    private VirtualDisplay  vd;
    private ImageReader     reader;

    private int  capW, capH;
    private int  vdNullCount    = 0;
    private long lastFrameMs    = 0;
    private long lastRecreateMs = 0;   // throttle VD recreates

    private volatile boolean destroyed = false;

    // ─────────────────────────────────────────────────────────────────
    // Callback
    // ─────────────────────────────────────────────────────────────────

    public interface FrameCallback {
        /** Called on the background thread — caller must recycle the bitmap. */
        void onFrame(Bitmap bmp);
        /** Called when capture fails for any reason. */
        void onError(String reason);
    }

    // ─────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────

    public CaptureEngine() {}

    // ─────────────────────────────────────────────────────────────────
    // MediaProjection management
    // ─────────────────────────────────────────────────────────────────

    /**
     * Provide (or replace) the active MediaProjection token.
     * Releases any existing VD/reader; the new ones are lazily created on next capture.
     */
    public void setProjection(MediaProjection newMp) {
        synchronized (lock) {
            releaseVD();
            mp = newMp;
            Log.d(TAG, "projection set");
        }
    }

    /** True if a MediaProjection token is available. Does NOT test if it is still valid. */
    public boolean hasProjection() {
        synchronized (lock) { return mp != null; }
    }

    /** Release projection and all associated resources. */
    public void release() {
        destroyed = true;
        synchronized (lock) {
            releaseVD();
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) {}
                mp = null;
            }
        }
        Log.d(TAG, "released");
    }

    /** Release only VD + reader (projection token stays alive for reuse). */
    public void releaseVD() {
        synchronized (lock) {
            if (vd != null) { try { vd.release(); } catch (Exception ignored) {} vd = null; }
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
            capW = capH = 0;
            vdNullCount = 0;
            Log.d(TAG, "VD released");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Frame acquisition
    // ─────────────────────────────────────────────────────────────────

    /**
     * Capture a frame on the CURRENT (caller's background) thread.
     * Blocks for up to ~700 ms waiting for the first valid frame.
     *
     * @param screenW   Physical screen width  (px)
     * @param screenH   Physical screen height (px)
     * @param densityDpi Device DPI from DisplayMetrics
     * @param callback  Result — onFrame() or onError()
     */
    public void acquireFrame(int screenW, int screenH, int densityDpi,
                             FrameCallback callback) {
        if (destroyed) { callback.onError("destroyed"); return; }

        synchronized (lock) {
            if (mp == null) { callback.onError("no_projection"); return; }
        }

        // ── Compute capture dimensions ────────────────────────────────
        // Align to 16px (GPU requirement). Never go below 720×1280.
        // Use native screen resolution for maximum OCR clarity.
        final int targetW = Math.max((screenW / 16) * 16, 720);
        final int targetH = Math.max((screenH / 16) * 16, 1280);

        boolean wasWarm;
        synchronized (lock) {
            wasWarm = (vd != null && reader != null
                    && capW == targetW && capH == targetH
                    && !isVDStale());

            if (!wasWarm) {
                // Release old VD and create a fresh one at the new size
                releaseVD();
                if (mp == null) { callback.onError("projection_gone"); return; }
                try {
                    reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2);
                    vd     = mp.createVirtualDisplay(
                        "GT_CAP", targetW, targetH, densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, null);
                    capW = targetW;
                    capH = targetH;
                    Log.d(TAG, "VD created " + capW + "x" + capH + " @" + densityDpi + "dpi");
                } catch (Exception e) {
                    releaseVD();
                    Log.e(TAG, "VD create failed: " + e.getMessage());
                    callback.onError("vd_create_failed: " + e.getMessage());
                    return;
                }
            }
        }

        // Wait for surface to settle
        try { Thread.sleep(wasWarm ? FAST_DELAY_MS : CAPTURE_DELAY_MS); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); callback.onError("interrupted"); return; }

        // ── Acquire image from ImageReader ────────────────────────────
        Image img = acquireWithRetry(3);

        // ── Null-frame recovery ───────────────────────────────────────
        if (img == null) {
            synchronized (lock) { vdNullCount++; }
            Log.w(TAG, "null frame #" + vdNullCount);

            if (vdNullCount >= VD_NULL_FRAME_LIMIT) {
                // Throttle: don't recreate more than once per VD_RECREATE_BACKOFF_MS
                long now = System.currentTimeMillis();
                boolean backoffActive;
                synchronized (lock) {
                    backoffActive = (now - lastRecreateMs) < VD_RECREATE_BACKOFF_MS;
                }
                if (backoffActive) {
                    Log.w(TAG, "VD recreate suppressed by backoff — returning null_frame");
                    callback.onError("null_frame_backoff");
                    return;
                }

                Log.w(TAG, "VD stale — forcing recreate");
                synchronized (lock) { releaseVD(); lastRecreateMs = now; }

                // Re-create and try once more
                synchronized (lock) {
                    if (mp == null) { callback.onError("projection_gone_recovery"); return; }
                    try {
                        reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2);
                        vd     = mp.createVirtualDisplay(
                            "GT_CAP_R", targetW, targetH, densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            reader.getSurface(), null, null);
                        capW = targetW; capH = targetH;
                    } catch (Exception e) {
                        releaseVD();
                        callback.onError("vd_recovery_failed");
                        return;
                    }
                }
                try { Thread.sleep(CAPTURE_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                img = acquireWithRetry(2);
            }

            if (img == null) {
                callback.onError("null_frame");
                return;
            }
        }

        synchronized (lock) { vdNullCount = 0; lastFrameMs = System.currentTimeMillis(); }

        // ── Decode Image → Bitmap ─────────────────────────────────────
        Bitmap result = decodeToBitmap(img, targetW, targetH);
        try { img.close(); } catch (Exception ignored) {}

        if (result == null) {
            callback.onError("bitmap_decode_failed");
            return;
        }

        callback.onFrame(result);
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────

    /** Attempt to acquire latest image up to maxTries times with 80ms backoff. */
    private Image acquireWithRetry(int maxTries) {
        ImageReader r;
        synchronized (lock) { r = reader; }
        if (r == null) return null;

        Image img = null;
        for (int i = 0; i < maxTries && img == null; i++) {
            try { img = r.acquireLatestImage(); }
            catch (Exception e) { Log.w(TAG, "acquireLatest #" + i + ": " + e.getMessage()); }
            if (img == null && i < maxTries - 1) {
                try { Thread.sleep(80); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        return img;
    }

    /**
     * Convert an Image plane to a correctly-cropped ARGB_8888 Bitmap.
     *
     * GPU row stride can make the raw buffer wider than targetW.
     * We allocate at rowStride/pixelStride width and then crop to targetW.
     * This correctly handles all wide-screen and high-DPI cases.
     */
    private Bitmap decodeToBitmap(Image img, int targetW, int targetH) {
        try {
            Image.Plane plane   = img.getPlanes()[0];
            ByteBuffer  buf     = plane.getBuffer();
            int         rStride = plane.getRowStride();
            int         pStride = plane.getPixelStride();

            // bmpW can be > targetW when GPU adds padding rows
            int bmpW = rStride / pStride;

            Log.d(TAG, "decode bmpW=" + bmpW + " targetW=" + targetW
                    + " targetH=" + targetH + " rStride=" + rStride);

            // Allocate from pool — avoids a GC-heavy allocation on every frame
            Bitmap raw = BitmapPool.acquire(bmpW, targetH);
            raw.copyPixelsFromBuffer(buf);

            if (bmpW > targetW) {
                // Crop away GPU padding on the right edge
                Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, targetW, targetH);
                BitmapPool.release(raw);
                return cropped;
            }
            return raw; // exact size — no copy needed

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "OOM decoding frame — freeing memory");
            // Release VD to free GPU surface memory, caller will retry
            releaseVD();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "decodeToBitmap: " + e.getMessage());
            return null;
        }
    }

    private boolean isVDStale() {
        return lastFrameMs > 0
            && (System.currentTimeMillis() - lastFrameMs) > WATCHDOG_STALE_MS;
    }

    // ─────────────────────────────────────────────────────────────────
    // Crop helpers (static — no instance state needed)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Map screen-space stroke bounds to bitmap-space crop rect.
     *
     * Because VD resolution == screen resolution, scale is ~1.0 in most cases.
     * We still compute it explicitly to handle edge cases (round-up alignment etc.)
     *
     * Returns int[]{cropL, cropT, cropW, cropH} or null if the region is too small.
     */
    public static int[] computeCrop(
            float screenLeft, float screenTop, float screenRight, float screenBottom,
            int bmpW, int bmpH, int screenW, int screenH,
            int minCropW, int minCropH) {

        float scaleX = (float) bmpW / screenW;
        float scaleY = (float) bmpH / screenH;

        int cropL = clamp((int)(screenLeft  * scaleX), 0, bmpW - 1);
        int cropT = clamp((int)(screenTop   * scaleY), 0, bmpH - 1);
        int cropR = clamp((int)(screenRight * scaleX), cropL + 1, bmpW);
        int cropB = clamp((int)(screenBottom * scaleY), cropT + 1, bmpH);
        int cropW = cropR - cropL;
        int cropH = cropB - cropT;

        Log.d(TAG, "crop scale=(" + scaleX + "," + scaleY + ")"
            + " rect=[" + cropL + "," + cropT + " " + cropW + "x" + cropH + "]");

        if (cropW < minCropW || cropH < minCropH) return null;
        return new int[]{ cropL, cropT, cropW, cropH };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
