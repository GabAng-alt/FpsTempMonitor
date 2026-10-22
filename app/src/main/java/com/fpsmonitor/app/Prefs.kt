package com.fpsmonitor.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun opacity(c: Context) = sp(c).getInt("opacity", 70)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v).apply()

    fun compact(c: Context) = sp(c).getBoolean("compact", false)
    fun setCompact(c: Context, v: Boolean) = sp(c).edit().putBoolean("compact", v).apply()

    fun posX(c: Context) = sp(c).getInt("x", 24)
    fun posY(c: Context) = sp(c).getInt("y", 120)
    fun setPos(c: Context, x: Int, y: Int) = sp(c).edit().putInt("x", x).putInt("y", y).apply()
}
