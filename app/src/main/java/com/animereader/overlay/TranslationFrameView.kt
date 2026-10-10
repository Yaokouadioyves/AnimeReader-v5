package com.animereader.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * The reading rectangle's window content: its frame (the view background, dimmed on its own)
 * plus the translations drawn over the bubbles in overlay mode. One window for both, because
 * Android only lets touches pass through untrusted overlays whose combined opacity stays ≤ 0.8
 * — two stacked windows would block the scroll on the bubbles.
 */
class TranslationFrameView(context: Context) : View(context) {

    /** One translation ready to draw (box in this view's pixels = capture region pixels). */
    class Shown(val box: RectF, val layout: StaticLayout, val paper: Int, val inset: Float, val key: String)

    private var shown: List<Shown> = emptyList()
    private var highlighted: String? = null

    var accent: Int = 0xFFE0465A.toInt()
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val typeface: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun setShown(list: List<Shown>) { shown = list; invalidate() }
    fun clearShown() { if (shown.isNotEmpty()) { shown = emptyList(); highlighted = null; invalidate() } }
    fun hasKey(key: String): Boolean = shown.any { it.key == key }

    /** Outlines the translation being read aloud (null = none). */
    fun highlight(key: String?) {
        if (key == highlighted) return
        highlighted = key
        if (shown.isNotEmpty()) invalidate()
    }

    /** Lays out [text] centred in [width] px at [sizePx], in [ink]. */
    fun textLayout(text: String, sizePx: Float, width: Int, ink: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = this@TranslationFrameView.typeface
            textSize = sizePx
            color = ink
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.95f)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shown.isEmpty()) return
        val r = 6f * density
        for (s in shown) {
            fill.color = s.paper
            canvas.drawRoundRect(s.box, r, r, fill)
            canvas.save()
            val top = s.box.top + ((s.box.height() - s.layout.height) / 2f).coerceAtLeast(s.inset)
            canvas.translate(s.box.left + s.inset, top)
            canvas.clipRect(0f, 0f, s.box.width() - 2 * s.inset, s.box.bottom - top)
            s.layout.draw(canvas)
            canvas.restore()
            if (s.key == highlighted) {
                ring.color = accent
                canvas.drawRoundRect(s.box, r, r, ring)
            }
        }
    }
}
