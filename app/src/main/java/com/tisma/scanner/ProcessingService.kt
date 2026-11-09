package com.tisma.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Mantiene viva la app mientras procesa un escaneo (malla con IA, nitidez de las fotos, .zip) o lo
 * manda a Tisma Cloud (subida de 0,5-2 GB y espera del resultado): servicio en primer plano con una
 * notificación de progreso, para que Android no la cierre en segundo plano, y un "wake lock" parcial
 * para que la CPU no se duerma con la pantalla apagada. El trabajo lo hace el ViewModel; esto sólo lo
 * protege y lo enseña. Cada trabajo lo pide con su nombre (Holders): el servicio sigue mientras alguno
 * lo necesite, así que terminar la subida no deja sin protección a un procesado en curso.
 */
class ProcessingService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Procesando el escaneo…"
        val n = build(this, text, -1)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        if (wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tisma:procesado").apply { acquire(3 * 60 * 60 * 1000L) }
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wake?.let { if (it.isHeld) it.release() }; wake = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 42
        private const val CHANNEL = "procesado"
        private const val EXTRA_TEXT = "text"
        @Volatile var running = false; private set

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Procesado de escaneos", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(ctx: Context, text: String, percent: Int): Notification {
            channel(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Tisma Scanner")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .apply { if (percent in 0..100) setProgress(100, percent, false) else setProgress(0, 0, true) }
                .build()
        }

        /** Quién necesita el servicio: el procesado del escaneo, la subida a la nube… */
        val holders = Holders()

        fun start(ctx: Context, text: String, holder: String = PROCESSING) {
            holders.add(holder)
            runCatching { ctx.startForegroundService(Intent(ctx, ProcessingService::class.java).putExtra(EXTRA_TEXT, text)) }
        }

        fun update(ctx: Context, text: String, percent: Int = -1) {
            if (!running) return
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, text, percent)) }
        }

        /** Este trabajo ya no lo necesita; el servicio se para cuando ninguno lo necesita. */
        fun stop(ctx: Context, holder: String = PROCESSING) {
            if (holders.remove(holder)) runCatching { ctx.stopService(Intent(ctx, ProcessingService::class.java)) }
        }

        const val PROCESSING = "procesado"
        const val CLOUD = "nube"
    }

    /** Los trabajos que tienen el servicio pedido. remove() dice si ya no queda ninguno. */
    class Holders {
        private val names = mutableSetOf<String>()
        @Synchronized fun add(name: String) { names += name }
        @Synchronized fun remove(name: String): Boolean { names -= name; return names.isEmpty() }
        @Synchronized fun isEmpty() = names.isEmpty()
    }
}
