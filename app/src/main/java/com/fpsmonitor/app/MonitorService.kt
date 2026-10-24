package com.fpsmonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class MonitorService : Service() {

    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    private val main = Handler(Looper.getMainLooper())

    private lateinit var probe: FpsProbe
    private lateinit var thermal: ThermalReader

    @Volatile private var paused = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> paused = true
                Intent.ACTION_SCREEN_ON -> {
                    paused = false
                    bg.removeCallbacks(tick)
                    bg.post(tick)
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (paused) return // se reanuda con ACTION_SCREEN_ON
            val hasDump = probe.hasDump()
            val cfg = Prefs.config(this@MonitorService)
            val (fps, app) = if (hasDump) probe.sample() else (null to null)
            // Solo se consultan temperaturas si el usuario activó alguna (ahorra batería).
            val temps = if (cfg.anyTemp) thermal.read(hasDump) else Temps()
            val snap = Snapshot(fps, app, temps, !hasDump, probe.note)
            main.post { overlay?.render(snap, cfg) }
            bg.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        probe = FpsProbe(this)
        thermal = ThermalReader(this)
        worker = HandlerThread("fps-sampler").also { it.start() }
        bg = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay == null) {
            showOverlay()
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            running = true
            bg.post(tick)
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Monitor de FPS", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Monitor de FPS y temperatura activo")
            .setContentText("Toca para abrir la configuración")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun showOverlay() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.posX(this@MonitorService)
            y = Prefs.posY(this@MonitorService)
        }
        val v = OverlayView(this)
        v.setOnTouchListener(dragListener(lp))
        wm.addView(v, lp)
        overlay = v
        params = lp
    }

    /** Arrastra la ventana con el dedo; el resto de toques no se ven afectados. */
    private fun dragListener(lp: WindowManager.LayoutParams) = object : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(v, lp)
                }
                MotionEvent.ACTION_UP -> Prefs.setPos(this@MonitorService, lp.x, lp.y)
            }
            return true
        }
    }

    override fun onDestroy() {
        running = false
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.fpsmonitor.app.STOP"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1

        @Volatile var running = false
    }
}
