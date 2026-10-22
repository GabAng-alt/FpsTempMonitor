package com.fpsmonitor.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        content.removeAllViews()

        title("FPS & Temp Monitor")
        body("Ventana flotante con FPS y temperatura en tiempo real, también con otras apps y juegos abiertos.")

        val overlayOk = Settings.canDrawOverlays(this)
        val dumpOk = checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
        val batOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        section("1. Permisos")
        row("Mostrar sobre otras apps", overlayOk, "Conceder") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            row("Notificaciones", notifOk, "Conceder") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        row("Batería sin restricciones", batOk, "Ajustar") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        row("Lectura de FPS (permiso DUMP)", dumpOk, null, null)

        if (!dumpOk) {
            val cmd = "adb shell pm grant $packageName android.permission.DUMP"
            body("Android no permite a una app normal leer los FPS de otras apps. Conéctalo por USB con " +
                "«Depuración USB» activada y ejecuta esto una sola vez en tu PC:")
            val box = TextView(this).apply {
                text = cmd
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0x22888888)
                setTextIsSelectable(true)
            }
            content.addView(box, matchWrap(top = 8))
            button("Copiar comando") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
            }
            body("Sin este permiso la ventana sigue mostrando las temperaturas, pero no los FPS.")
        }

        section("2. Ventana flotante")
        val running = MonitorService.running
        button(if (running) "Detener monitor" else "Iniciar monitor") {
            if (running) {
                startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Primero concede «Mostrar sobre otras apps»", Toast.LENGTH_LONG).show()
                return@button
            } else {
                startForegroundService(Intent(this, MonitorService::class.java))
            }
            // El servicio actualiza su estado de forma asíncrona.
            content.postDelayed({ build() }, 400)
        }

        body("Opacidad del fondo")
        content.addView(SeekBar(this).apply {
            max = 100
            progress = Prefs.opacity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) Prefs.setOpacity(this@MainActivity, p.coerceAtLeast(10))
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, matchWrap(top = 4))

        content.addView(Switch(this).apply {
            text = "Modo compacto (solo FPS y temperatura máx.)"
            isChecked = Prefs.compact(this@MainActivity)
            setOnCheckedChangeListener { _: CompoundButton, c: Boolean -> Prefs.setCompact(this@MainActivity, c) }
        }, matchWrap(top = 16))

        body("Arrastra la ventana con el dedo para moverla. Para cerrarla usa «Detener» aquí o en la notificación.")
        body("Xiaomi/HyperOS: para que no se cierre en segundo plano, bloquea la app en Recientes y en " +
            "Ajustes › Apps › esta app › Ahorro de batería elige «Sin restricciones» (y activa Inicio automático).")
    }

    // ---- helpers de UI ----

    private fun title(t: String) = content.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    })

    private fun section(t: String) = content.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }, matchWrap(top = 28))

    private fun body(t: String) = content.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        alpha = 0.85f
    }, matchWrap(top = 10))

    private fun button(t: String, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }, matchWrap(top = 8))

    private fun row(label: String, ok: Boolean, action: String?, onClick: (() -> Unit)?) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        r.addView(TextView(this).apply {
            text = (if (ok) "✅  " else "⚠️  ") + label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!ok && action != null && onClick != null) {
            r.addView(Button(this).apply {
                text = action
                setOnClickListener { onClick() }
            })
        }
        content.addView(r, matchWrap(top = 6))
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        build()
    }
}
