package com.fpsmonitor.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class Temps(
    val cpu: Float? = null,
    val gpu: Float? = null,
    val skin: Float? = null,
    val battery: Float? = null,
)

data class Snapshot(
    val fps: Int?,
    val app: String?,
    val temps: Temps,
    val missingDump: Boolean,
    val note: String? = null,
)

/** Ejecuta un comando sin shell (los argumentos se pasan tal cual, sin problemas de comillas). */
object Shell {
    fun run(vararg args: String): String? {
        var p: Process? = null
        return try {
            p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            null
        } finally {
            p?.destroy()
        }
    }
}

/**
 * FPS reales de lo que se está mostrando en pantalla (cualquier app o juego), leídos de SurfaceFlinger.
 * Requiere el permiso DUMP concedido por ADB una sola vez.
 */
class FpsProbe(private val ctx: Context) {

    private var layer: String? = null
    private var lastScan = 0L

    /** Motivo por el que no hay FPS (se muestra en la ventana para diagnosticar). */
    var note: String? = null
        private set

    fun hasDump(): Boolean =
        ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

    /** Devuelve (fps, paquete de la capa activa). */
    fun sample(): Pair<Int?, String?> {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan > SCAN_EVERY_MS) {
            layer = pickActiveLayer()
            lastScan = now
        }
        val l = layer
        if (l == null) return fallbackGlobal() to null
        val lat = latency(l)
        if (lat == null) {
            layer = null
            return fallbackGlobal() to null
        }
        note = null
        // Pantalla estática o app en segundo plano: vuelve a buscar la capa activa en ~2 s.
        if (lat.fps == 0) lastScan = minOf(lastScan, now - SCAN_EVERY_MS + 2000L)
        return lat.fps to packageOf(l)
    }

    /** Respaldo: FPS globales de la pantalla (frames que presenta SurfaceFlinger por segundo). */
    private fun fallbackGlobal(): Int? {
        val g = globalFps()
        if (g != null) note = "FPS global de pantalla"
        return g
    }

    private var tsEnabled = false
    private var lastTotal = -1L
    private var lastTotalAt = 0L

    private fun globalFps(): Int? {
        if (!tsEnabled) {
            Shell.run("dumpsys", "SurfaceFlinger", "--timestats", "-enable")
            tsEnabled = true
        }
        val out = Shell.run("dumpsys", "SurfaceFlinger", "--timestats", "-dump", "-maxlayers", "0")
            ?: return null
        val total = Regex("totalFrames = (\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        val now = SystemClock.elapsedRealtime()
        val prevTotal = lastTotal
        val prevAt = lastTotalAt
        lastTotal = total
        lastTotalAt = now
        if (prevTotal < 0L || now <= prevAt || total < prevTotal) return null
        val dt = (now - prevAt) / 1000.0
        return ((total - prevTotal) / dt).roundToInt().coerceIn(0, 240)
    }

    private class Lat(val fps: Int, val lastTs: Long)

    /**
     * `--list` imprime líneas como "<nombre>#id parentId=N", pero `--latency` compara contra el nombre
     * sin ese sufijo. Se prueba primero limpio y, si no hay datos, con el nombre tal cual.
     */
    private fun latency(layerName: String): Lat? {
        val clean = cleanName(layerName)
        val a = latencyRaw(clean)
        if (a != null && a.lastTs > 0L) return a
        if (clean != layerName) {
            val b = latencyRaw(layerName)
            if (b != null && b.lastTs > 0L) return b
        }
        return a
    }

    /**
     * Android 16 imprime "RequestedLayerState{<hex> <nombre>#<id> parentId=N ...}".
     * Se extrae solo <nombre>, que es lo que entiende `--latency`.
     */
    private fun cleanName(line: String): String =
        line.trim()
            .replace(Regex("^RequestedLayerState\\{[0-9a-fA-F]+\\s+"), "")
            .replace(Regex("#\\d+\\s+parentId=.*$"), "")
            .replace(Regex("\\s+parentId=\\S*.*$"), "")
            .replace(Regex("#\\d+\\}?$"), "")

    private fun latencyRaw(layerName: String): Lat? {
        val out = Shell.run("dumpsys", "SurfaceFlinger", "--latency", layerName) ?: return null
        val lines = out.lines()
        if (lines.size < 2) return null
        val ts = ArrayList<Long>(128)
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size != 3) continue
            val t = parts[1].toLongOrNull() ?: continue
            if (t > 0L && t != Long.MAX_VALUE) ts.add(t)
        }
        if (ts.isEmpty()) return Lat(0, 0L)
        val last = ts.max()
        // Si no se ha presentado ningún frame en más de ~1.2 s, la pantalla está estática: 0 FPS.
        if (System.nanoTime() - last > 1_200_000_000L) return Lat(0, last)
        val window = ts.filter { it > last - 1_000_000_000L }.sorted()
        if (window.size < 2) return Lat(0, last)
        val span = window.last() - window.first()
        if (span <= 0L) return Lat(0, last)
        val fps = ((window.size - 1) * 1_000_000_000.0 / span).roundToInt().coerceIn(0, 240)
        return Lat(fps, last)
    }

    /** Entre las capas visibles de apps, elige la que presentó un frame más recientemente. */
    private fun pickActiveLayer(): String? {
        val list = Shell.run("dumpsys", "SurfaceFlinger", "--list")
        if (list == null) {
            note = "no se pudo ejecutar dumpsys"
            return null
        }
        if (list.contains("Permission Denial", ignoreCase = true) || list.contains("denied", ignoreCase = true)) {
            note = "dumpsys: permiso denegado"
            return null
        }
        val candidates = list.lines()
            .map { cleanName(it) }
            .filter { it.contains('/') && EXCLUDE.none { ex -> it.contains(ex, ignoreCase = true) } }
            .filter { CONTAINERS.none { c -> it.startsWith(c) } }
            .filter { !it.contains(ctx.packageName) }
            .distinct()
            .sortedWith(compareByDescending<String> { it.startsWith("SurfaceView") }
                .thenByDescending { it.contains("BLAST") })
            .take(12)
        if (candidates.isEmpty()) {
            note = "sin capas: abre otra app (capas=${list.lines().size})"
            return null
        }
        var best: String? = null
        var bestTs = 0L
        for (c in candidates) {
            val lat = latency(c) ?: continue
            if (lat.lastTs > bestTs) {
                bestTs = lat.lastTs
                best = c
            }
        }
        note = if (best == null) "sin frames[${candidates.size}] ${candidates.first().takeLast(22)}" else null
        return best
    }

    private fun packageOf(layerName: String): String? {
        var s = layerName
        if (s.startsWith("SurfaceView[")) s = s.removePrefix("SurfaceView[")
        return s.substringBefore('/').takeIf { it.isNotBlank() }
    }

    companion object {
        private const val SCAN_EVERY_MS = 6000L
        // Capas "contenedor" de ventanas: nunca presentan frames.
        private val CONTAINERS = listOf(
            "ActivityRecord", "Task", "WindowToken", "DefaultTaskDisplayArea", "ImePlaceholder",
            "Leaf:", "WindowedMagnification", "DisplayArea", "Display ",
        )
        private val EXCLUDE = listOf(
            "com.android.systemui", "Background for", "Wallpaper", "ScreenDecor",
            "InputMethod", "NavigationBar", "StatusBar", "Splash", "Display Overlay",
            "Bounds for", "Gesture", "Dim layer", "Letterbox", "mouse", "animation-leash", "Surface(",
        )
    }
}

/** Temperaturas: ThermalService (vía DUMP) -> sysfs -> batería (siempre disponible). */
class ThermalReader(private val ctx: Context) {

    private var cached = Temps()
    private var lastRead = 0L

    fun read(hasDump: Boolean): Temps {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRead >= 2000L) {
            lastRead = now
            var t = if (hasDump) fromThermalService() else Temps()
            if (t.cpu == null && t.gpu == null) {
                val s = fromSysfs()
                t = t.copy(cpu = t.cpu ?: s.cpu, gpu = t.gpu ?: s.gpu, skin = t.skin ?: s.skin)
            }
            cached = t
        }
        return cached.copy(battery = batteryTemp() ?: cached.battery)
    }

    private fun batteryTemp(): Float? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE || t == 0) null else t / 10f
    }

    private fun fromThermalService(): Temps {
        val out = Shell.run("dumpsys", "thermalservice") ?: return Temps()
        var text = out
        val start = out.indexOf("Current temperatures from HAL")
        if (start >= 0) {
            text = out.substring(start)
            val end = listOf("Current cooling devices from HAL", "Cached temperatures")
                .map { text.indexOf(it, 10) }
                .filter { it > 0 }
                .minOrNull()
            if (end != null) text = text.substring(0, end)
        }
        var cpu: Float? = null
        var gpu: Float? = null
        var skin: Float? = null
        var bat: Float? = null
        for (m in TEMP_RE.findAll(text)) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            if (v !in 1f..120f) continue
            when (m.groupValues[2].toIntOrNull()) {
                0 -> cpu = maxOf(cpu ?: v, v)
                1 -> gpu = maxOf(gpu ?: v, v)
                2 -> bat = maxOf(bat ?: v, v)
                3 -> skin = maxOf(skin ?: v, v)
            }
        }
        return Temps(cpu, gpu, skin, bat)
    }

    private fun fromSysfs(): Temps {
        var cpu: Float? = null
        var gpu: Float? = null
        var skin: Float? = null
        try {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
                val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull()
                    ?: return@forEach
                val raw = runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull()
                    ?: return@forEach
                val v = if (abs(raw) > 1000f) raw / 1000f else raw
                if (v !in 1f..120f) return@forEach
                when {
                    type.contains("gpu") -> gpu = maxOf(gpu ?: v, v)
                    type.contains("cpu") -> cpu = maxOf(cpu ?: v, v)
                    type.contains("skin") || type.contains("quiet") -> skin = maxOf(skin ?: v, v)
                }
            }
        } catch (_: Exception) {
        }
        return Temps(cpu, gpu, skin, null)
    }

    companion object {
        private val TEMP_RE = Regex("mValue=(-?[0-9.]+),\\s*mType=(\\d+)")
    }
}
