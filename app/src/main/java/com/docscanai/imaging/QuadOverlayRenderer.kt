package com.docscanai.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.docscanai.data.Quad
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * F4 (SUBTASK H3) — **DEBUG-ONLY** detected-quad overlay renderer.
 *
 * Draws the **4 detected corners** (numbered `1..4` in pipeline order `TL, TR, BR, BL`), the
 * connecting quad outline, each corner's pixel coordinates, and a one-line verdict banner
 * (`detected?`, full-frame fallback?, area fraction, enhancement mode) **on the pre-warp input
 * frame**. This is exactly what the user asked for: seeing *where* the detector put the corners on
 * the input, before any warp is applied.
 *
 * Ownership / safety contract:
 *  - Returns a **NEW** bitmap (a mutable copy of [input]); the caller owns and recycles it.
 *  - NEVER mutates [input], the pipeline's working image, or any stored page/output — it is purely
 *    a diagnostic artifact.
 *  - Wrapped so any failure returns `null` (the caller then simply skips the diagnostic); it can
 *    never crash the capture pipeline.
 *
 * Gating: this class is only reached from `ScanPipeline` when `BuildConfig.DEBUG` is true **and** a
 * `debugOverlaySink` was supplied (the corpus lane supplies one; production supplies none), so it is
 * zero-cost and absent from release builds. It is a diagnostic aid for the corpus lane and for
 * on-device debugging only.
 */
object QuadOverlayRenderer {

    /** Renders the overlay for [quad] over a copy of [input]; returns a new bitmap or `null`. */
    fun render(input: Bitmap, quad: Quad, verdict: String): Bitmap? = try {
        if (input.isRecycled || input.width <= 0 || input.height <= 0) {
            null
        } else {
            val out = input.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val w = out.width
            val h = out.height
            val canvas = Canvas(out)

            val pts = quad.points
            val stroke = max(2f, w / 320f)

            // ---- quad outline (yellow) ----
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0xFF, 0xD2, 0x00)
                style = Paint.Style.STROKE
                strokeWidth = stroke
            }
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            path.close()
            canvas.drawPath(path, linePaint)

            // ---- numbered corners + coordinate labels (pipeline order TL,TR,BR,BL) ----
            val radius = max(10f, w / 60f)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val numText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(16f, w / 40f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val coordText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(14f, w / 55f)
                typeface = Typeface.MONOSPACE
            }
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }
            val colors = intArrayOf(
                Color.rgb(0xE5, 0x3F, 0x35), // 1 TL red
                Color.rgb(0x1E, 0x88, 0xE5), // 2 TR blue
                Color.rgb(0x43, 0xA0, 0x47), // 3 BR green
                Color.rgb(0xF5, 0x9F, 0x00)  // 4 BL amber
            )
            for (i in pts.indices) {
                val p = pts[i]
                fill.color = colors[i % colors.size]
                canvas.drawCircle(p.x, p.y, radius, fill)

                val num = (i + 1).toString()
                val nw = numText.measureText(num)
                val fm = numText.fontMetrics
                canvas.drawText(num, p.x - nw / 2f, p.y - (fm.ascent + fm.descent) / 2f, numText)

                val coord = String.format(Locale.US, "(%d,%d)", p.x.roundToInt(), p.y.roundToInt())
                val cw = coordText.measureText(coord)
                val lx = (p.x + radius + 4f).coerceIn(2f, max(2f, w - cw - 6f))
                val ly = (p.y - radius - 4f).coerceIn(coordText.textSize + 6f, max(coordText.textSize + 6f, h - 4f))
                canvas.drawRect(lx - 3f, ly - coordText.textSize, lx + cw + 3f, ly + 4f, box)
                canvas.drawText(coord, lx, ly, coordText)
            }

            // ---- verdict banner (top-left) ----
            val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = max(16f, w / 45f)
                typeface = Typeface.DEFAULT_BOLD
                color = Color.WHITE
            }
            val bw = banner.measureText(verdict)
            val pad = banner.textSize * 0.4f
            canvas.drawRect(4f, 4f, 4f + bw + 2 * pad, 4f + banner.textSize + 2 * pad, box)
            canvas.drawText(verdict, 4f + pad, 4f + pad + banner.textSize, banner)

            out
        }
    } catch (t: Throwable) {
        // A diagnostic must never be able to break the capture path.
        null
    }
}
