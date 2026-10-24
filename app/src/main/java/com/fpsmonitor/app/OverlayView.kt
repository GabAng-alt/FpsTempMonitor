package com.fpsmonitor.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Cápsula flotante de una sola línea: "120 FPS". Los medidores de temperatura
 * son opcionales y, al activarlos, se añaden a la misma línea (la cápsula se alarga).
 */
class OverlayView(ctx: Context) : LinearLayout(ctx) {

    private val tv = TextView(ctx).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setSingleLine(true)
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }

    private val bg = GradientDrawable().apply {
        // Radio enorme: Android lo limita a la mitad de la altura, y queda una cápsula perfecta.
        cornerRadius = dp(100f)
        setColor(Color.BLACK)
        setStroke(dp(1f).toInt(), Color.parseColor("#33FFFFFF"))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bg
        addView(tv)
    }

    fun render(s: Snapshot, cfg: OverlayConfig) {
        bg.alpha = cfg.opacity.coerceIn(10, 100) * 255 / 100

        val k = cfg.scale.coerceIn(60, 150) / 100f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * k)
        val padH = dp(10f * k).toInt()
        val padV = dp(3f * k).toInt()
        setPadding(padH, padV, padH, padV)

        val b = SpannableStringBuilder()
        if (s.fps != null) b.add("${s.fps}", fpsColor(s.fps)) else b.add("--", GRAY)
        b.add(" FPS", GRAY, 0.7f)

        val t = s.temps
        if (cfg.cpu) b.metric("CPU", t.cpu, 55f, 70f)
        if (cfg.gpu) b.metric("GPU", t.gpu, 55f, 70f)
        if (cfg.bat) b.metric("BAT", t.battery, 38f, 43f)
        if (cfg.skin) b.metric("SKN", t.skin, 38f, 43f)

        tv.text = b
    }

    private fun SpannableStringBuilder.add(txt: String, color: Int, size: Float = 1f) {
        val start = length
        append(txt)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (size != 1f) setSpan(RelativeSizeSpan(size), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.metric(label: String, v: Float?, warn: Float, hot: Float) {
        add("  │  ", DIVIDER)
        add("$label ", GRAY, 0.7f)
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

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )

    companion object {
        private val GREEN = Color.parseColor("#3DDC84")
        private val AMBER = Color.parseColor("#FFC043")
        private val RED = Color.parseColor("#FF5C5C")
        private val GRAY = Color.parseColor("#B0B8C0")
        private val DIVIDER = Color.parseColor("#55FFFFFF")
    }
}
