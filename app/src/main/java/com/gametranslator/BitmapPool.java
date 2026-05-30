package com.gametranslator;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * BitmapPool — lightweight reusable bitmap pool.
 *
 * Goal: avoid allocating a new full-screen Bitmap on every OCR capture.
 *       Instead we recycle a fixed-size pool of ARGB_8888 bitmaps.
 *
 * Thread-safety: all methods are synchronized on 'this'.
 * Usage:
 *   Bitmap bmp = BitmapPool.acquire(w, h);
 *   ... use bmp ...
 *   BitmapPool.release(bmp);   // returns to pool OR recycles if full
 *
 * Rules:
 *   • Never release a bitmap you didn't acquire from this pool.
 *   • Never use a bitmap after calling release().
 *   • Pool only stores ARGB_8888 bitmaps — others are ignored.
 *   • MAX_POOL_SIZE = 3  (keeps RAM footprint minimal)
 */
public final class BitmapPool {

    private static final String TAG           = "GT-BitmapPool";
    private static final int    MAX_POOL_SIZE = 3;

    // Internal pool — LIFO deque for better cache locality
    private static final Deque<Bitmap> pool = new ArrayDeque<>(MAX_POOL_SIZE);

    private BitmapPool() {}

    // ── Acquire ──────────────────────────────────────────────────────

    /**
     * Returns a bitmap of at least w×h in ARGB_8888.
     * If a suitable one exists in the pool it is reused (erased first).
     * Otherwise a new bitmap is allocated.
     */
    public static synchronized Bitmap acquire(int w, int h) {
        for (Bitmap candidate : pool) {
            if (candidate.getWidth() == w && candidate.getHeight() == h
                    && !candidate.isRecycled()) {
                pool.remove(candidate);
                candidate.eraseColor(0);
                Log.v(TAG, "reuse " + w + "x" + h);
                return candidate;
            }
        }
        Log.v(TAG, "alloc " + w + "x" + h);
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    // ── Release ──────────────────────────────────────────────────────

    /**
     * Returns bmp to the pool for future reuse.
     * If pool is full or bmp is invalid, it is recycled immediately.
     */
    public static synchronized void release(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            bmp.recycle();
            return;
        }
        if (pool.size() >= MAX_POOL_SIZE) {
            Log.v(TAG, "pool full — recycle");
            bmp.recycle();
            return;
        }
        pool.addFirst(bmp);
        Log.v(TAG, "returned to pool  size=" + pool.size());
    }

    // ── Convenience: safe recycle (won't throw) ───────────────────────

    /**
     * Recycles bmp immediately regardless of pool state.
     * Safe to call with null or already-recycled bitmaps.
     */
    public static void recycleSafe(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            try { bmp.recycle(); } catch (Exception ignored) {}
        }
    }

    // ── Drain all pooled bitmaps ──────────────────────────────────────

    /** Call from Service.onDestroy() to free all pooled memory immediately. */
    public static synchronized void drainPool() {
        while (!pool.isEmpty()) {
            Bitmap b = pool.poll();
            if (b != null && !b.isRecycled()) b.recycle();
        }
        Log.d(TAG, "pool drained");
    }

    // ── Stats ─────────────────────────────────────────────────────────

    public static synchronized int size() { return pool.size(); }
}

