package com.unkl3errl.helteccontroller.bruce

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class BruceDeviceDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onAction: ((BruceDisplayAction) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pageKey = BruceDisplayMenu.ROOT
    private var selected = 0
    private val rowBounds = mutableListOf<Pair<Int, RectF>>()
    private var controlsTop = 0f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Official Bruce device menu"
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val screenHeight = min(width / 2f, height - 46f * density)
        val screenScale = width / 128f
        val page = BruceDisplayMenu.pages.getValue(pageKey)

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.typeface = Typeface.create("monospace", Typeface.NORMAL)
        paint.color = Color.WHITE
        paint.textSize = 7.5f * screenScale
        canvas.drawText(page.title.take(21), 1f * screenScale, 10f * screenScale, paint)
        paint.strokeWidth = 0.7f * screenScale
        canvas.drawLine(0f, 12f * screenScale, width.toFloat(), 12f * screenScale, paint)

        val firstVisible = when {
            selected <= 2 -> 0
            page.items.size <= 4 -> 0
            else -> min(selected - 2, page.items.size - 4)
        }
        rowBounds.clear()
        page.items.drop(firstVisible).take(4).forEachIndexed { row, item ->
            val index = firstVisible + row
            val top = (15f + row * 10f) * screenScale
            val bounds = RectF(0f, top, width.toFloat(), top + 9f * screenScale)
            if (index == selected) {
                paint.color = Color.WHITE
                paint.style = Paint.Style.FILL
                canvas.drawRect(bounds, paint)
            }
            paint.color = if (index == selected) Color.BLACK else Color.WHITE
            paint.textSize = 6.3f * screenScale
            val prefix = if (index == selected) "> " else "  "
            canvas.drawText(prefix + item.label.take(20), 2f * screenScale, top + 7f * screenScale, paint)
            rowBounds += index to bounds
        }

        paint.color = Color.WHITE
        paint.textSize = 4.1f * screenScale
        canvas.drawText("1x next 2x back hold select", 1f * screenScale, 63f * screenScale, paint)

        controlsTop = maxOf(screenHeight + 5f * density, height - 42f * density)
        val labels = listOf("PREV", "BACK", "SELECT", "NEXT")
        val controlWidth = width / 4f
        labels.forEachIndexed { index, label ->
            val bounds = RectF(index * controlWidth, controlsTop, (index + 1) * controlWidth, height.toFloat())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.rgb(70, 70, 70)
            canvas.drawRect(bounds, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.typeface = Typeface.create("monospace", Typeface.BOLD)
            paint.textSize = 10f * density
            val textWidth = paint.measureText(label)
            canvas.drawText(label, bounds.centerX() - textWidth / 2, bounds.centerY() + 4f * density, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        performClick()
        rowBounds.firstOrNull { it.second.contains(event.x, event.y) }?.let {
            selected = it.first
            activateSelection()
            invalidate()
            return true
        }
        if (event.y >= controlsTop) {
            when ((event.x / (width / 4f)).toInt().coerceIn(0, 3)) {
                0 -> moveSelection(-1)
                1 -> goBack()
                2 -> activateSelection()
                3 -> moveSelection(1)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveSelection(delta: Int) {
        val count = BruceDisplayMenu.pages.getValue(pageKey).items.size
        selected = (selected + delta + count) % count
        invalidate()
    }

    private fun goBack() {
        BruceDisplayMenu.pages.getValue(pageKey).parent?.let(::openPage)
    }

    private fun activateSelection() {
        val item = BruceDisplayMenu.pages.getValue(pageKey).items[selected]
        item.destination?.let(::openPage) ?: item.action?.let { onAction?.invoke(it) }
    }

    private fun openPage(destination: String) {
        pageKey = destination
        selected = 0
        invalidate()
    }
}
