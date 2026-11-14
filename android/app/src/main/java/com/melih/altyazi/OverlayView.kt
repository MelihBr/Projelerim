package com.melih.altyazi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Kamera önizlemesinin üstünde okuma alanını (turuncu çerçeve) ve bulunan satırları (yeşil) çizer.
 * Parmakla sürükleyerek yeni bir okuma alanı çizilebilir.
 * Bölge 0..1 aralığında, dik (upright) kamera görüntüsüne göre saklanır.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var region = RectF(0.10f, 0.60f, 0.90f, 0.95f)
        set(value) { field = value; invalidate() }
    var onRegionChanged: ((RectF) -> Unit)? = null

    private var imgW = 16
    private var imgH = 9
    private var boxes: List<Rect> = emptyList()

    private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 180, 74); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val found = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 211, 160); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private var downX = 0f
    private var downY = 0f
    private var before: RectF? = null

    fun setDetections(b: List<Rect>, w: Int, h: Int) {
        boxes = b
        if (w > 0 && h > 0) { imgW = w; imgH = h }
        invalidate()
    }

    /** Önizlemenin (FIT_CENTER) ekranda kapladığı alan. */
    private fun content(): RectF {
        val a = imgW.toFloat() / imgH
        val vw = width.toFloat()
        val vh = height.toFloat()
        return if (vw / vh > a) {
            val cw = vh * a
            RectF((vw - cw) / 2, 0f, (vw + cw) / 2, vh)
        } else {
            val ch = vw / a
            RectF(0f, (vh - ch) / 2, vw, (vh + ch) / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = content()
        val r = RectF(
            c.left + region.left * c.width(), c.top + region.top * c.height(),
            c.left + region.right * c.width(), c.top + region.bottom * c.height()
        )
        canvas.drawRect(c.left, c.top, c.right, r.top, dim)
        canvas.drawRect(c.left, r.bottom, c.right, c.bottom, dim)
        canvas.drawRect(c.left, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, c.right, r.bottom, dim)
        canvas.drawRect(r, frame)

        val s = c.width() / imgW
        for (b in boxes) {
            canvas.drawRect(c.left + b.left * s, c.top + b.top * s, c.left + b.right * s, c.top + b.bottom * s, found)
        }
        canvas.drawText("Okuma alanını değiştirmek için parmağınla kutu çiz", c.left + 12f, c.top + 38f, hint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = content()
        val x = ((e.x - c.left) / c.width()).coerceIn(0f, 1f)
        val y = ((e.y - c.top) / c.height()).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; before = RectF(region)
            }
            MotionEvent.ACTION_MOVE -> {
                region = RectF(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
            }
            MotionEvent.ACTION_UP -> {
                if (region.width() < 0.05f || region.height() < 0.03f) {
                    before?.let { region = it }
                } else {
                    onRegionChanged?.invoke(region)
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> before?.let { region = it }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
