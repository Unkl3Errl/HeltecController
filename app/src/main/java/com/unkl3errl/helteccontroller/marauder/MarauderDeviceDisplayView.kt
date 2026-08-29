package com.unkl3errl.helteccontroller.marauder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class MarauderDeviceDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onCommand: ((String) -> Unit)? = null
    var onOpenCommands: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pageKey = MarauderDisplayMenu.ROOT
    private var selected = 0
    private var firstVisible = 0
    private val rowBounds = mutableListOf<Pair<Int, RectF>>()
    private var controlsTop = 0f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Marauder device display"
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = resources.displayMetrics.density
        val width = width.toFloat()
        val height = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, width, height, paint)

        paint.typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        paint.textSize = 13f * scale
        paint.color = Color.CYAN
        canvas.drawText("ESP32 MARAUDER", 12f * scale, 21f * scale, paint)
        paint.textSize = 11f * scale
        paint.color = Color.LTGRAY
        canvas.drawText(MarauderDisplayMenu.pages.getValue(pageKey).title, 12f * scale, 39f * scale, paint)
        paint.color = Color.DKGRAY
        canvas.drawLine(10f * scale, 46f * scale, width - 10f * scale, 46f * scale, paint)

        val page = MarauderDisplayMenu.pages.getValue(pageKey)
        val rowHeight = 38f * scale
        val gap = 4f * scale
        val listTop = 54f * scale
        controlsTop = height - 44f * scale
        val visibleCount = max(1, ((controlsTop - listTop) / (rowHeight + gap)).toInt())
        if (selected < firstVisible) firstVisible = selected
        if (selected >= firstVisible + visibleCount) firstVisible = selected - visibleCount + 1
        rowBounds.clear()

        page.items.drop(firstVisible).take(visibleCount).forEachIndexed { visibleIndex, item ->
            val index = firstVisible + visibleIndex
            val top = listTop + visibleIndex * (rowHeight + gap)
            val bounds = RectF(10f * scale, top, width - 10f * scale, top + rowHeight)
            val color = displayColor(item.color)
            paint.strokeWidth = 1.5f * scale
            paint.style = if (index == selected) Paint.Style.FILL else Paint.Style.STROKE
            paint.color = color
            canvas.drawRoundRect(bounds, 4f * scale, 4f * scale, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (index == selected) Color.BLACK else color
            paint.textSize = 13f * scale
            canvas.drawText(item.label, bounds.left + 12f * scale, bounds.centerY() + 5f * scale, paint)
            rowBounds += index to bounds
        }

        val labels = listOf("▲", "BACK", "SELECT", "▼")
        val controlWidth = width / labels.size
        labels.forEachIndexed { index, label ->
            val bounds = RectF(index * controlWidth, controlsTop, (index + 1) * controlWidth, height)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = scale
            paint.color = Color.rgb(64, 64, 64)
            canvas.drawRect(bounds, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (label == "SELECT") Color.CYAN else Color.LTGRAY
            paint.textSize = 11f * scale
            val textWidth = paint.measureText(label)
            canvas.drawText(label, bounds.centerX() - textWidth / 2, bounds.centerY() + 4f * scale, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        performClick()
        val row = rowBounds.firstOrNull { it.second.contains(event.x, event.y) }
        if (row != null) {
            selected = row.first
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
        val count = MarauderDisplayMenu.pages.getValue(pageKey).items.size
        selected = (selected + delta + count) % count
        invalidate()
    }

    private fun goBack() {
        val parent = MarauderDisplayMenu.pages.getValue(pageKey).parent ?: return
        openPage(parent)
    }

    private fun activateSelection() {
        val item = MarauderDisplayMenu.pages.getValue(pageKey).items[selected]
        when {
            item.destination != null -> openPage(item.destination)
            item.command != null -> onCommand?.invoke(item.command)
            item.opensCommands -> onOpenCommands?.invoke()
        }
    }

    private fun openPage(destination: String) {
        pageKey = destination
        selected = 0
        firstVisible = 0
        invalidate()
    }

    private fun displayColor(color: MarauderDisplayColor): Int = when (color) {
        MarauderDisplayColor.GREEN -> Color.rgb(0, 235, 90)
        MarauderDisplayColor.CYAN -> Color.CYAN
        MarauderDisplayColor.RED -> Color.rgb(255, 70, 70)
        MarauderDisplayColor.BLUE -> Color.rgb(65, 145, 255)
        MarauderDisplayColor.ORANGE -> Color.rgb(255, 145, 35)
        MarauderDisplayColor.YELLOW -> Color.YELLOW
        MarauderDisplayColor.PURPLE -> Color.rgb(205, 100, 255)
        MarauderDisplayColor.WHITE -> Color.WHITE
    }
}
