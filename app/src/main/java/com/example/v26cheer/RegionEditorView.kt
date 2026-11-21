package com.example.v26cheer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 캡처한 게임 화면 위에 영역(박스)을 직접 그리는 편집 화면.
 * 한 손가락: 선택된 영역 그리기 / 두 손가락: 확대·축소·이동
 */
class RegionEditorView(ctx: Context) : View(ctx) {

    companion object {
        val NAMES = arrayOf("타자 이름", "PLAY BALL", "스코어보드", "이닝", "하단 해설", "결과 배너(위)", "결과 배너(가운데)")
        private val COLORS = intArrayOf(
            Color.parseColor("#EF4444"), Color.parseColor("#8B5CF6"), Color.parseColor("#F59E0B"),
            Color.parseColor("#10B981"), Color.parseColor("#06B6D4"), Color.parseColor("#F97316"), Color.parseColor("#A855F7")
        )
    }

    private var bmp: Bitmap? = null
    val rects = arrayOfNulls<RectF>(7)      // 이미지 좌표(px)
    var selected = 0
        set(v) { field = v; invalidate() }
    var onChange: (() -> Unit)? = null

    private var s = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitScale = 1f
    private var cur: RectF? = null
    private var mode = 0                    // 0 없음, 1 그리는 중, 2 확대/이동, 3 확대 후(그리기 잠금)
    private var sx = 0f
    private var sy = 0f
    private var lastDist = 0f
    private var lastMx = 0f
    private var lastMy = 0f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; isFakeBoldText = true }

    fun setImage(b: Bitmap) { bmp = b; fit(); invalidate() }

    val imageWidth get() = bmp?.width ?: 0
    val imageHeight get() = bmp?.height ?: 0

    private fun fit() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        fitScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        s = fitScale
        tx = (width - b.width * s) / 2f
        ty = (height - b.height * s) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun toImgX(x: Float) = (x - tx) / s
    private fun toImgY(y: Float) = (y - ty) / s

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#111827"))
        val b = bmp ?: return
        c.save()
        c.translate(tx, ty)
        c.scale(s, s)
        c.drawBitmap(b, 0f, 0f, bmpPaint)
        for (i in rects.indices) {
            val r = rects[i] ?: continue
            drawBox(c, r, COLORS[i], i == selected, NAMES[i])
        }
        cur?.let { drawBox(c, it, COLORS[selected], true, null) }
        c.restore()
    }

    private fun drawBox(c: Canvas, r: RectF, color: Int, sel: Boolean, label: String?) {
        fill.color = color; fill.alpha = if (sel) 60 else 30
        c.drawRect(r, fill)
        stroke.color = color
        stroke.strokeWidth = (if (sel) 3f else 1.5f) / s
        c.drawRect(r, stroke)
        if (label != null) {
            text.color = color
            text.textSize = 30f / s
            c.drawText(label, r.left, max(r.top - 6f / s, 30f / s), text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1
                sx = toImgX(e.x); sy = toImgY(e.y)
                cur = RectF(sx, sy, sx, sy)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    mode = 2; cur = null
                    lastDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    lastMx = (e.getX(0) + e.getX(1)) / 2f
                    lastMy = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 2 && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2f
                    val my = (e.getY(0) + e.getY(1)) / 2f
                    if (lastDist > 0f) {
                        val ix = toImgX(lastMx); val iy = toImgY(lastMy)
                        s = (s * d / lastDist).coerceIn(fitScale * 0.8f, fitScale * 14f)
                        tx = mx - ix * s
                        ty = my - iy * s
                    }
                    lastDist = d; lastMx = mx; lastMy = my
                } else if (mode == 1) {
                    val x = toImgX(e.x); val y = toImgY(e.y)
                    cur = RectF(min(sx, x), min(sy, y), max(sx, x), max(sy, y))
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> { if (mode == 2) mode = 3 }
            MotionEvent.ACTION_UP -> {
                if (mode == 1) {
                    val r = cur
                    val b = bmp
                    if (r != null && b != null && r.width() * s >= 8f && r.height() * s >= 8f) {
                        r.left = r.left.coerceIn(0f, b.width.toFloat()); r.right = r.right.coerceIn(0f, b.width.toFloat())
                        r.top = r.top.coerceIn(0f, b.height.toFloat()); r.bottom = r.bottom.coerceIn(0f, b.height.toFloat())
                        rects[selected] = RectF(r)
                        onChange?.invoke()
                    }
                }
                mode = 0; cur = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { mode = 0; cur = null; invalidate() }
        }
        return true
    }
}
