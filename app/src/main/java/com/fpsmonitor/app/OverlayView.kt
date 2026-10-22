package com.fpsmonitor.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Ventana flotante pequeña y semitransparente. */
class OverlayView(ctx: Context) : LinearLayout(ctx) {

    private val fpsTv = text(22f, bold = true)
    private val line1 = text(12f)
    private val line2 = text(12f)
    private val appTv = text(9f).apply { setTextColor(GRAY) }
    private val bg = GradientDrawable().apply {
        cornerRadius = dp(10f)
        setColor(Color.BLACK)
    }

    init {
        orientation = VERTICAL
        background = bg
        val p = dp(8f).toInt()
        setPadding(p, dp(5f).toInt(), p, dp(5f).toInt())
        addView(fpsTv)
        addView(line1)
        addView(line2)
        addView(appTv)
    }

    fun render(s: Snapshot, opacity: Int, compact: Boolean) {
        bg.alpha = (opacity.coerceIn(10, 100) * 255 / 100)

        // FPS
        val fb = SpannableStringBuilder()
        if (s.fps != null) {
            fb.add("${s.fps}", fpsColor(s.fps))
            fb.add(" FPS", GRAY)
        } else {
            fb.add("-- FPS", GRAY)
        }
        fpsTv.text = fb

        val t = s.temps
        if (compact) {
            val max = listOfNotNull(t.cpu, t.gpu, t.skin, t.battery).maxOrNull()
            line1.text = SpannableStringBuilder().also { b ->
                if (max != null) b.add("${max.roundToInt()}°C", tempColor(max, 45f, 60f)) else b.add("--°C", GRAY)
            }
            line2.visibility = GONE
            appTv.visibility = GONE
            return
        }

        line1.text = SpannableStringBuilder().also { b ->
            b.pair("CPU", t.cpu, 55f, 70f)
            b.add("  ", GRAY)
            b.pair("GPU", t.gpu, 55f, 70f)
        }
        line2.text = SpannableStringBuilder().also { b ->
            b.pair("BAT", t.battery, 38f, 43f)
            b.add("  ", GRAY)
            b.pair("SKN", t.skin, 38f, 43f)
        }
        line2.visibility = VISIBLE

        when {
            s.missingDump -> {
                appTv.text = "Falta permiso DUMP (ver app)"
                appTv.visibility = VISIBLE
            }
            s.note != null -> {
                appTv.text = s.note.take(34)
                appTv.visibility = VISIBLE
            }
            s.app != null -> {
                appTv.text = s.app.takeLast(26)
                appTv.visibility = VISIBLE
            }
            else -> appTv.visibility = GONE
        }
    }

    private fun SpannableStringBuilder.add(txt: String, color: Int) {
        val start = length
        append(txt)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.pair(label: String, v: Float?, warn: Float, hot: Float) {
        add("$label ", GRAY)
        if (v != null) add("${v.roundToInt()}°", tempColor(v, warn, hot)) else add("--", GRAY)
    }

    private fun fpsColor(fps: Int) = when {
        fps >= 50 -> GREEN
        fps >= 30 -> AMBER
        else -> RED
    }

    private fun tempColor(v: Float, warn: Float, hot: Float) = when {
        v >= hot -> RED
        v >= warn -> AMBER
        else -> GREEN
    }

    private fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.WHITE)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    companion object {
        private val GREEN = Color.parseColor("#3DDC84")
        private val AMBER = Color.parseColor("#FFC043")
        private val RED = Color.parseColor("#FF5C5C")
        private val GRAY = Color.parseColor("#B0B8C0")
    }
}
