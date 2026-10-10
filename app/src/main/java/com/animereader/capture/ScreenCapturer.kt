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

    /** Capture the latest frame and crop to [region] (screen pixels). Null if no frame yet. */
    fun captureRegion(region: Rect?): Bitmap? = synchronized(captureLock) {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bmpWidth = image.width + rowPadding / pixelStride
            var bmp = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            // Drop the row padding (recycle the padded intermediate so it isn't left to the GC).
            if (bmpWidth != image.width) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                if (cropped !== bmp) bmp.recycle()
                bmp = cropped
            }
            if (region != null) {
                val l = region.left.coerceIn(0, image.width - 1)
                val t = region.top.coerceIn(0, image.height - 1)
                val r = region.right.coerceIn(l + 1, image.width)
                val b = region.bottom.coerceIn(t + 1, image.height)
                val sub = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
                if (sub !== bmp) bmp.recycle()
                bmp = sub
            }
            return bmp
        } catch (e: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { thread.quitSafely() }
        virtualDisplay = null
        imageReader = null
    }
}
