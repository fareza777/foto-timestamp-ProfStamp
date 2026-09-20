package com.proofstamp.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.proofstamp.app.data.settings.WatermarkTemplate
import kotlin.math.min

/**
 * Draws the evidence stamp onto any Canvas. The same code paints the real-time camera
 * preview (scaled to the view) and the final full-resolution JPEG, so what you see is
 * exactly what gets sealed. All sizes are relative to `u` = canvasWidth / 1000.
 */
class OverlayRenderer(context: Context) {

    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)

    private val accent = Color.parseColor("#19C37D")
    private val onDark = Color.WHITE
    private val onDarkDim = Color.argb(200, 255, 255, 255)

    fun draw(canvas: Canvas, width: Int, height: Int, data: StampData) {
        val u = width / 1000f
        when (data.template) {
            WatermarkTemplate.CLASSIC -> drawClassic(canvas, width, height, u, data)
            WatermarkTemplate.CARD -> drawCard(canvas, width, height, u, data)
            WatermarkTemplate.MINIMAL -> drawMinimal(canvas, width, height, u, data)
            WatermarkTemplate.REPORT -> drawReport(canvas, width, height, u, data)
        }
    }

    /** Renders onto a copy of the bitmap and returns it (source is untouched). */
    fun render(source: Bitmap, data: StampData): Bitmap {
        val out = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(out), out.width, out.height, data)
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun text(size: Float, tf: Typeface, color: Int = onDark, shadow: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = tf
        this.color = color
        if (shadow) setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(180, 0, 0, 0))
    }

    private fun lines(data: StampData): List<String> = buildList {
        val loc = listOfNotNull(
            data.placeName?.takeIf { data.showPlaceName && it.isNotBlank() },
            data.coordsText?.takeIf { data.showCoordinates },
        )
        addAll(loc)
        if (data.identityText.isNotBlank()) add(data.identityText)
        data.sessionText?.let { add(it) }
        if (data.note.isNotBlank()) add("“${data.note}”")
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun drawQrBlock(canvas: Canvas, data: StampData, right: Float, bottom: Float, qrSize: Float, u: Float, dark: Boolean = true) {
        val codePaint = text(20f * u, mono, if (dark) onDark else Color.BLACK)
        val codeWidth = codePaint.measureText(data.verificationCode)
        val blockWidth = maxOf(qrSize, codeWidth)
        val qrLeft = right - blockWidth + (blockWidth - qrSize) / 2
        val qrTop = bottom - 26f * u - qrSize
        data.qr?.let { qr ->
            val pad = 4f * u
            val bg = Paint().apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(qrLeft - pad, qrTop - pad, qrLeft + qrSize + pad, qrTop + qrSize + pad), 6f * u, 6f * u, bg)
            canvas.drawBitmap(qr, null, RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        canvas.drawText(data.verificationCode, right - blockWidth + (blockWidth - codeWidth) / 2, bottom - 4f * u, codePaint)
    }

    // ---------------------------------------------------------------- templates

    private fun drawClassic(canvas: Canvas, w: Int, h: Int, u: Float, d: StampData) {
        val body = lines(d)
        val timeSize = 58f * u
        val lineSize = 22f * u
        val margin = 28f * u
        val contentH = timeSize + 28f * u + body.size * (lineSize * 1.35f) + margin
        val bandH = maxOf(contentH + margin, 190f * u)

        val gradient = Paint().apply {
            shader = LinearGradient(0f, h - bandH * 1.6f, 0f, h.toFloat(), intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Color.argb(215, 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, h - bandH * 1.6f, w.toFloat(), h.toFloat(), gradient)

        val qrSize = if (d.qr != null) 96f * u else 0f
        val rightBlock = if (d.qr != null) qrSize + 40f * u else 120f * u
        val maxTextW = w - margin * 2 - rightBlock

        var y = h - bandH + margin + timeSize
        val timePaint = text(timeSize, bold, shadow = true)
        canvas.drawText(d.timeText, margin, y, timePaint)
        val tw = timePaint.measureText(d.timeText)
        val datePaint = text(24f * u, medium, onDarkDim, true)
        canvas.drawText("${d.dateText}  ${d.zoneText}", margin + tw + 18f * u, y - 4f * u, datePaint)

        // accent underline
        canvas.drawRect(margin, y + 10f * u, margin + 64f * u, y + 14f * u, Paint().apply { color = accent })
        y += 28f * u + lineSize

        val linePaint = text(lineSize, regular, onDark, true)
        body.forEach {
            canvas.drawText(ellipsize(it, linePaint, maxTextW), margin, y, linePaint)
            y += lineSize * 1.35f
        }
        drawQrBlock(canvas, d, w - margin, h - margin + 12f * u, qrSize, u)
        drawIdTag(canvas, d, w, h, u)
    }

    private fun drawCard(canvas: Canvas, w: Int, h: Int, u: Float, d: StampData) {
        val body = lines(d)
        val margin = 28f * u
        val pad = 22f * u
        val timeSize = 48f * u
        val lineSize = 21f * u
        val qrSize = if (d.qr != null) 88f * u else 0f
        val textW = min(w * 0.62f, 620f * u)
        val cardW = textW + pad * 2 + (if (d.qr != null) qrSize + pad else 0f)
        val cardH = pad * 2 + timeSize + 30f * u + body.size * lineSize * 1.35f + 6f * u
        val left = margin
        val top = h - margin - cardH
        val rect = RectF(left, top, left + cardW, top + cardH)

        canvas.drawRoundRect(rect, 22f * u, 22f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 10, 14, 20) })
        canvas.drawRoundRect(rect, 22f * u, 22f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * u; color = Color.argb(70, 255, 255, 255)
        })
        canvas.drawRoundRect(RectF(left, top + 24f * u, left + 6f * u, top + cardH - 24f * u), 3f * u, 3f * u, Paint().apply { color = accent })

        var y = top + pad + timeSize
        canvas.drawText(d.timeText, left + pad, y, text(timeSize, bold))
        y += 26f * u
        canvas.drawText(d.dateText, left + pad, y, text(20f * u, medium, onDarkDim))
        y += 6f * u + lineSize * 1.2f
        val linePaint = text(lineSize, regular)
        body.forEach {
            canvas.drawText(ellipsize(it, linePaint, textW), left + pad, y, linePaint)
            y += lineSize * 1.35f
        }
        if (d.qr != null) drawQrBlock(canvas, d, rect.right - pad, rect.bottom - pad + 22f * u, qrSize, u)
        else canvas.drawText(d.verificationCode, rect.right - pad - text(20f * u, mono).measureText(d.verificationCode), rect.bottom - pad, text(20f * u, mono))
        drawIdTag(canvas, d, w, h, u)
    }

    private fun drawMinimal(canvas: Canvas, w: Int, h: Int, u: Float, d: StampData) {
        val margin = 30f * u
        val first = "${d.timeText}  ·  ${d.dateText}"
        val second = lines(d).joinToString("  ·  ")
        val p1 = text(30f * u, medium, shadow = true)
        val p2 = text(20f * u, regular, onDarkDim, true)
        val qrSize = if (d.qr != null) 72f * u else 0f
        val maxW = w - margin * 2 - qrSize - 30f * u
        canvas.drawText(ellipsize(first, p1, maxW), margin, h - margin - 34f * u, p1)
        if (second.isNotBlank()) canvas.drawText(ellipsize(second, p2, maxW), margin, h - margin, p2)
        drawQrBlock(canvas, d, w - margin, h - margin + 8f * u, qrSize, u)
    }

    private fun drawReport(canvas: Canvas, w: Int, h: Int, u: Float, d: StampData) {
        val body = lines(d)
        val headH = 54f * u
        val lineSize = 22f * u
        val qrSize = if (d.qr != null) 92f * u else 0f
        val bodyH = maxOf(body.size * lineSize * 1.4f + 60f * u, qrSize + 52f * u)
        val stripTop = h - headH - bodyH

        canvas.drawRect(0f, stripTop, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(225, 11, 15, 20) })
        canvas.drawRect(0f, stripTop, w.toFloat(), stripTop + headH, Paint().apply { color = Color.argb(255, 17, 24, 32) })
        canvas.drawRect(0f, stripTop, 10f * u, stripTop + headH, Paint().apply { color = accent })

        val margin = 30f * u
        val headText = d.project.ifBlank { "PROOFSTAMP" }.uppercase()
        canvas.drawText(ellipsize(headText, text(24f * u, bold), w * 0.55f), margin, stripTop + headH * 0.66f, text(24f * u, bold))
        val stamp = "${d.timeText}   ${d.dateText}"
        val sp = text(22f * u, medium, onDarkDim)
        canvas.drawText(stamp, w - margin - sp.measureText(stamp), stripTop + headH * 0.66f, sp)

        var y = stripTop + headH + 32f * u + lineSize
        val label = text(15f * u, medium, Color.argb(140, 255, 255, 255))
        val value = text(lineSize, regular)
        val maxW = w - margin * 2 - qrSize - 40f * u
        val rows = buildList {
            d.placeName?.takeIf { d.showPlaceName && it.isNotBlank() }?.let { add("LOCATION" to it) }
            d.coordsText?.takeIf { d.showCoordinates }?.let { add("GPS" to it) }
            if (d.operator.isNotBlank()) add("OPERATOR" to d.operator)
            d.sessionText?.let { add("SESSION" to it) }
            if (d.note.isNotBlank()) add("NOTE" to d.note)
        }
        rows.forEach { (k, v) ->
            canvas.drawText(k, margin, y - lineSize - 2f * u, label)
            canvas.drawText(ellipsize(v, value, maxW), margin, y, value)
            y += lineSize * 1.4f + 16f * u
        }
        drawQrBlock(canvas, d, w - margin, h - 20f * u, qrSize, u)
        drawIdTag(canvas, d, w, h, u, topRight = true)
    }

    /** Tiny Photo ID label; kept at the opposite corner from the main stamp. */
    private fun drawIdTag(canvas: Canvas, d: StampData, w: Int, h: Int, u: Float, topRight: Boolean = true) {
        val p = text(15f * u, mono, Color.argb(210, 255, 255, 255), shadow = true)
        val tw = p.measureText(d.photoId)
        val pad = 10f * u
        val x = w - 24f * u - tw
        val y = 24f * u + 15f * u
        val bg = RectF(x - pad, y - 15f * u - pad * 0.6f, x + tw + pad, y + pad * 0.7f)
        canvas.drawRoundRect(bg, 8f * u, 8f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) })
        canvas.drawText(d.photoId, x, y, p)
    }
}
