package com.unkl3errl.helteccontroller.bruce

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class BruceRemoteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    @Volatile private var frame = BruceScreenFrame(240, 135, emptyList())

    fun update(data: ByteArray) {
        frame = BruceScreenLog.parse(data)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val current = frame
        val scale = min(width / current.width.toFloat(), height / current.height.toFloat())
        val left = (width - current.width * scale) / 2f
        val top = (height - current.height * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        current.commands.forEach { drawCommand(canvas, it) }
        canvas.restore()
    }

    private fun drawCommand(canvas: Canvas, command: BruceDrawCommand) {
        val v = command.values
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        when (command.code) {
            0 -> {
                paint.style = Paint.Style.FILL
                paint.color = color565(v[0])
                canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), paint)
            }
            1, 2 -> {
                paint.style = if (command.code == 2) Paint.Style.FILL else Paint.Style.STROKE
                paint.color = color565(v[4])
                canvas.drawRect(v[0].f(), v[1].f(), (v[0] + v[2]).f(), (v[1] + v[3]).f(), paint)
            }
            3, 4 -> {
                paint.style = if (command.code == 4) Paint.Style.FILL else Paint.Style.STROKE
                paint.color = color565(v[5])
                canvas.drawRoundRect(
                    RectF(v[0].f(), v[1].f(), (v[0] + v[2]).f(), (v[1] + v[3]).f()),
                    v[4].f(), v[4].f(), paint,
                )
            }
            5, 6 -> {
                paint.style = if (command.code == 6) Paint.Style.FILL else Paint.Style.STROKE
                paint.color = color565(v[3])
                canvas.drawCircle(v[0].f(), v[1].f(), v[2].f(), paint)
            }
            7, 8 -> {
                paint.style = if (command.code == 8) Paint.Style.FILL else Paint.Style.STROKE
                paint.color = color565(v[6])
                val path = Path().apply {
                    moveTo(v[0].f(), v[1].f())
                    lineTo(v[2].f(), v[3].f())
                    lineTo(v[4].f(), v[5].f())
                    close()
                }
                canvas.drawPath(path, paint)
            }
            9, 10 -> {
                paint.style = if (command.code == 10) Paint.Style.FILL else Paint.Style.STROKE
                paint.color = color565(v[4])
                canvas.drawOval(
                    RectF((v[0] - v[2]).f(), (v[1] - v[3]).f(), (v[0] + v[2]).f(), (v[1] + v[3]).f()),
                    paint,
                )
            }
            11 -> {
                paint.color = color565(v[4])
                canvas.drawLine(v[0].f(), v[1].f(), v[2].f(), v[3].f(), paint)
            }
            12 -> {
                paint.color = color565(v[6])
                paint.strokeWidth = (v[2] - v[3]).coerceAtLeast(1).f()
                val radius = (v[2] + v[3]) / 2f
                canvas.drawArc(
                    RectF(v[0] - radius, v[1] - radius, v[0] + radius, v[1] + radius),
                    (v[4] + 90).f(), (v[5] - v[4]).f(), false, paint,
                )
            }
            13 -> {
                paint.color = color565(v[5])
                paint.strokeWidth = v[4].coerceAtLeast(1).f()
                canvas.drawLine(v[0].f(), v[1].f(), v[2].f(), v[3].f(), paint)
            }
            14, 15, 16, 17 -> drawText(canvas, command)
            18 -> {
                paint.style = Paint.Style.STROKE
                paint.color = Color.DKGRAY
                canvas.drawRect(v[0].f(), v[1].f(), (v[0] + 18).f(), (v[1] + 18).f(), paint)
            }
            19 -> {
                paint.style = Paint.Style.FILL
                paint.color = color565(v[2])
                canvas.drawRect(v[0].f(), v[1].f(), v[0] + 1f, v[1] + 1f, paint)
            }
            20 -> {
                paint.style = Paint.Style.FILL
                paint.color = color565(v[3])
                canvas.drawRect(v[0].f(), v[1].f(), v[0] + 1f, (v[1] + v[2]).f(), paint)
            }
            21 -> {
                paint.style = Paint.Style.FILL
                paint.color = color565(v[3])
                canvas.drawRect(v[0].f(), v[1].f(), (v[0] + v[2]).f(), v[1] + 1f, paint)
            }
        }
    }

    private fun drawText(canvas: Canvas, command: BruceDrawCommand) {
        val v = command.values
        val text = command.text.replace("\n", "")
        paint.style = Paint.Style.FILL
        paint.textSize = v[2].coerceAtLeast(1) * 8f
        paint.textAlign = when (command.code) {
            14 -> Paint.Align.CENTER
            15 -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        paint.color = color565(if (v[3] == v[4]) 0 else v[4])
        val width = paint.measureText(text)
        val start = when (paint.textAlign) {
            Paint.Align.CENTER -> v[0] - width / 2f
            Paint.Align.RIGHT -> v[0] - width
            else -> v[0].f()
        }
        canvas.drawRect(start, v[1].f(), start + width, v[1] + paint.textSize, paint)
        paint.color = color565(v[3])
        val baseline = v[1] - paint.fontMetrics.top
        canvas.drawText(text, v[0].f(), baseline, paint)
    }

    private fun color565(value: Int): Int {
        val red = ((value shr 11) and 0x1F) * 255 / 31
        val green = ((value shr 5) and 0x3F) * 255 / 63
        val blue = (value and 0x1F) * 255 / 31
        return Color.rgb(red, green, blue)
    }

    private fun Int.f() = toFloat()
}
