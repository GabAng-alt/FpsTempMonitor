package com.fpsmonitor.app

import android.content.Context

/** Lo que el usuario eligió para la cápsula. Por defecto: solo FPS. */
data class OverlayConfig(
    val opacity: Int,
    val scale: Int,
    val cpu: Boolean,
    val gpu: Boolean,
    val bat: Boolean,
    val skin: Boolean,
) {
    val anyTemp get() = cpu || gpu || bat || skin
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun config(c: Context) = OverlayConfig(
        opacity = opacity(c),
        scale = scale(c),
        cpu = flag(c, "t_cpu"),
        gpu = flag(c, "t_gpu"),
        bat = flag(c, "t_bat"),
        skin = flag(c, "t_skin"),
    )

    fun opacity(c: Context) = sp(c).getInt("opacity", 70)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v).apply()

    /** Tamaño de la cápsula en %, 60..150. */
    fun scale(c: Context) = sp(c).getInt("scale", 100)
    fun setScale(c: Context, v: Int) = sp(c).edit().putInt("scale", v).apply()

    fun flag(c: Context, key: String) = sp(c).getBoolean(key, false)
    fun setFlag(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()

    fun posX(c: Context) = sp(c).getInt("x", 24)
    fun posY(c: Context) = sp(c).getInt("y", 120)
    fun setPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("x", x).putInt("y", y).apply()
}
