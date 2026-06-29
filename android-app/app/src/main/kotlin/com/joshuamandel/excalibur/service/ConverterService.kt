package com.joshuamandel.excalibur.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.joshuamandel.excalibur.AppGraph
import com.joshuamandel.excalibur.MainActivity
import com.joshuamandel.excalibur.R
import com.joshuamandel.excalibur.net.discoverAddresses
import com.joshuamandel.excalibur.server.KindleHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that runs conversion work and, only when explicitly enabled,
 * the Kindle web server. Deliberately START_NOT_STICKY with no reschedule: when
 * the server is stopped and no conversion is active, the service exits instead
 * of resurrecting in the background.
 */
class ConverterService : LifecycleService() {
    private lateinit var graph: AppGraph
    private var server: KindleHttpServer? = null
    private var converting = false

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        // Pre-warm the converter (unpack runtime + compile/deserialize the module) off
        // the critical path, so the first conversion doesn't wait on the ~4s compile.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { graph.runtime.prewarm() } }
    }

    /** (Re)bind the HTTP server to the current settings port. */
    private suspend fun bindServer() {
        server?.stop()
        val port = graph.settings.settings.first().serverPort
        val actual = KindleHttpServer(graph.db.bookDao()).also { server = it }.start(port)
        ServerBus.state.value = ServerBus.Info(running = true, port = actual)
        notify(notifText())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_EXIT, ACTION_STOP_SERVER -> { stopServer(); return START_NOT_STICKY }
            ACTION_START_SERVER -> lifecycleScope.launch { bindServer() }
            ACTION_RESTART -> lifecycleScope.launch { bindServer() }
            ACTION_CONVERT -> drainConversions()
        }
        return START_NOT_STICKY
    }

    private fun drainConversions() {
        if (converting) return
        lifecycleScope.launch {
            converting = true
            notify(notifText())
            try {
                graph.conversion.drain()
            } finally {
                converting = false
                if (server == null) {
                    stopIfIdle()
                } else {
                    notify(notifText())
                }
            }
        }
    }

    private fun stopServer() {
        server?.stop(); server = null
        ServerBus.state.value = ServerBus.Info(running = false, port = 0)
        if (converting) notify(notifText()) else stopIfIdle()
    }

    private fun stopIfIdle() {
        if (server == null && !converting) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        server?.stop(); server = null
        ServerBus.state.value = ServerBus.Info(running = false, port = 0)
        super.onDestroy()
    }

    private fun notifText(): String {
        val info = ServerBus.state.value
        if (!info.running) return if (converting) "Converting queued books" else "Web server stopped"
        val addr = discoverAddresses().firstOrNull()?.ip ?: "this device"
        return "Kindle: open  $addr:${info.port}"
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Web server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Excalibur")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_book)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
        if (ServerBus.state.value.running) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, ConverterService::class.java).setAction(ACTION_STOP_SERVER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Stop server", stop)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "kindle_server"
        private const val NOTIF_ID = 1
        const val ACTION_CONVERT = "com.joshuamandel.excalibur.CONVERT"
        const val ACTION_EXIT = "com.joshuamandel.excalibur.EXIT"
        const val ACTION_RESTART = "com.joshuamandel.excalibur.RESTART"
        const val ACTION_START_SERVER = "com.joshuamandel.excalibur.START_SERVER"
        const val ACTION_STOP_SERVER = "com.joshuamandel.excalibur.STOP_SERVER"

        /** Drain queued conversions without enabling Kindle web access. */
        fun convert(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConverterService::class.java).setAction(ACTION_CONVERT)
            )
        }

        /** Start Kindle web access. Conversion remains independently queued. */
        fun startServer(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConverterService::class.java).setAction(ACTION_START_SERVER)
            )
        }

        /** Rebind the server to the latest settings port (used after a port change). */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConverterService::class.java).setAction(ACTION_RESTART)
            )
        }

        fun stopServer(context: Context) {
            context.startService(Intent(context, ConverterService::class.java).setAction(ACTION_STOP_SERVER))
        }
    }
}
