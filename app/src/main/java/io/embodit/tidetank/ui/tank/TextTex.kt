package io.embodit.tidetank.ui.tank

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Words in the volume drawn into textures (design spatialLayout.tags / plaque): image up = quad +Y (F-test: debug `testf`).
 *  - [tag]: creature name over the fish, light text with a solid dark outline on a dark rounded plate (contrast on any water).
 *  - [plate]: brass plate (plaque on the base, gauge marks, the 「面板」 board), engraved dark text, opaque.
 */
internal object TextTex {
    const val PX_PER_M = 6000f
    private val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    fun tag(text: String, wM: Float, hM: Float, textM: Float): Bitmap {
        val w = (wM * PX_PER_M).toInt(); val h = (hM * PX_PER_M).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = h * 0.45f
        c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9102A33.toInt() })
        c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7C77A.toInt(); style = Paint.Style.STROKE; strokeWidth = h * 0.05f })
        val size = textM * PX_PER_M
        val base = h / 2f + size * 0.36f
        c.drawText(text, w / 2f, base, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = BOLD; textSize = size; textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt()
        })
        return bmp
    }

    fun plate(text: String, wM: Float, hM: Float, textM: Float, sub: String? = null): Bitmap {
        val w = (wM * PX_PER_M).toInt(); val h = (hM * PX_PER_M).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFFE9C978.toInt(), 0xFFC79A3E.toInt(), 0xFFA97C2C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.06f; p.color = 0xFF6E4E1A.toInt()
        c.drawRect(p.strokeWidth / 2, p.strokeWidth / 2, w - p.strokeWidth / 2, h - p.strokeWidth / 2, p)
        val size = textM * PX_PER_M
        var tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = BOLD; textSize = size; textAlign = Paint.Align.CENTER; color = 0xFF2A1A08.toInt() }
        // shrink to fit (never cut, D13)
        val maxW = w * 0.94f
        val mw = tp.measureText(text)
        if (mw > maxW) tp = Paint(tp).apply { textSize = size * maxW / mw }
        if (sub == null) {
            c.drawText(text, w / 2f, h / 2f + tp.textSize * 0.36f, tp)
        } else {
            c.drawText(text, w / 2f, h * 0.48f, tp)
            val sp = Paint(tp).apply { textSize = tp.textSize * 0.45f; typeface = Typeface.DEFAULT_BOLD }
            c.drawText(sub, w / 2f, h * 0.85f, sp)
        }
        return bmp
    }

    /** The 3D tip beside a picked-up jar (D53 补) / the level hint: dark rounded card, two light lines (contrast on any water). */
    fun tip(line1: String, line2: String, wM: Float, hM: Float): Bitmap {
        val w = (wM * PX_PER_M).toInt(); val h = (hM * PX_PER_M).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = h * 0.22f
        c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6102A33.toInt() })
        c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE7C77A.toInt(); style = Paint.Style.STROKE; strokeWidth = h * 0.03f })
        val maxW = w * 0.90f
        fun fit(t: String, size: Float, bold: Boolean, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (bold) BOLD else Typeface.DEFAULT; textSize = size; textAlign = Paint.Align.CENTER; this.color = color
            val mw = measureText(t); if (mw > maxW) textSize = size * maxW / mw
        }
        val p1 = fit(line1, h * 0.36f, true, 0xFFFFFFFF.toInt())
        val p2 = fit(line2, h * 0.24f, false, 0xFFF3E3B8.toInt())
        c.drawText(line1, w / 2f, h * 0.47f, p1)
        c.drawText(line2, w / 2f, h * 0.82f, p2)
        return bmp
    }

    /** The F-up test pattern (D10): an asymmetric glyph pair, white on red, to check the words are not mirrored. */
    fun fTest(wM: Float, hM: Float): Bitmap {
        val w = (wM * PX_PER_M).toInt(); val h = (hM * PX_PER_M).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFC62828.toInt())
        c.drawText("F↑ R→", w / 2f, h * 0.7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = BOLD; textSize = h * 0.7f; textAlign = Paint.Align.CENTER; color = -1 })
        return bmp
    }

    @Suppress("unused")
    private val glow = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
}
