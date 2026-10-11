package com.animereader.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

/**
 * Mirrors the display into an [ImageReader] and produces a [Bitmap] of an arbitrary
 * screen region on demand. One instance per running capture session.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val thread = HandlerThread("ar-capture").apply { start() }
    private val handler = Handler(thread.looper)
    // Serializes concurrent captures (read loop + replay + online path) so we never exceed the
    // ImageReader's max-images quota by acquiring from several threads at once.
    private val captureLock = Any()

    fun start() {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "ar-capture", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    /**
     * The latest full screen frame. The virtual display only delivers a new image when something
     * on screen changes: on a still page there is none, and that must read as "same picture as
     * before" (the page is still), not as "no picture" — or a still page is never seen as still.
     */
    private var last: Bitmap? = null

    /**
     * Crop [region] (screen pixels) out of the latest frame — the previous one again when the
     * screen hasn't changed since. Always a new bitmap the caller owns. Null before the first frame.
     */
    fun captureRegion(region: Rect?): Bitmap? = synchronized(captureLock) {
        val reader = imageReader ?: return null
        reader.acquireLatestImage()?.let { image ->
            val fresh = try { toBitmap(image) } catch (e: Exception) { null } finally { image.close() }
            if (fresh != null) { last?.recycle(); last = fresh }
        }
        val full = last ?: return null
        return try {
            if (region == null) full.copy(Bitmap.Config.ARGB_8888, false)
            else {
                val l = region.left.coerceIn(0, full.width - 1)
                val t = region.top.coerceIn(0, full.height - 1)
                val r = region.right.coerceIn(l + 1, full.width)
                val b = region.bottom.coerceIn(t + 1, full.height)
                val sub = Bitmap.createBitmap(full, l, t, r - l, b - t)
                // The whole screen comes back as the same object: never hand out our own frame.
                if (sub === full) full.copy(Bitmap.Config.ARGB_8888, false) else sub
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val bmpWidth = image.width + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (bmpWidth == image.width) return bmp
        // Drop the row padding (recycle the padded intermediate so it isn't left to the GC).
        val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        if (cropped !== bmp) bmp.recycle()
        return cropped
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { thread.quitSafely() }
        virtualDisplay = null
        imageReader = null
        synchronized(captureLock) { last?.recycle(); last = null }
    }
}
