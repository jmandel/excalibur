package dev.exe.kindleconverter.service

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
import dev.exe.kindleconverter.AppGraph
import dev.exe.kindleconverter.MainActivity
import dev.exe.kindleconverter.R
import dev.exe.kindleconverter.net.discoverAddresses
import dev.exe.kindleconverter.server.KindleHttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the Kindle web server and the conversion queue while the
 * user needs them. Deliberately START_NOT_STICKY with no reschedule: when the user taps
 * "Exit & stop server", the socket closes, the notification clears, and the service stays
 * dead until explicitly relaunched — no background resurrection.
 */
class ConverterService : LifecycleService() {
    private lateinit var graph: AppGraph
    private var server: KindleHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        lifecycleScope.launch { bindServer() }
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
            ACTION_EXIT -> { shutdown(); return START_NOT_STICKY }
            ACTION_RESTART -> lifecycleScope.launch { bindServer() }
            ACTION_CONVERT -> lifecycleScope.launch { graph.conversion.drain(); notify(notifText()) }
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        server?.stop(); server = null
        ServerBus.state.value = ServerBus.Info(running = false, port = 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop(); server = null
        ServerBus.state.value = ServerBus.Info(running = false, port = 0)
        super.onDestroy()
    }

    private fun notifText(): String {
        val info = ServerBus.state.value
        if (!info.running) return "Server stopped"
        val addr = discoverAddresses().firstOrNull()?.ip ?: "this device"
        return "Kindle: open  $addr:${info.port}"
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Kindle server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, ConverterService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Excalibur")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_book)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, "Exit & stop server", exit)
            .build()
    }

    companion object {
        private const val CHANNEL = "kindle_server"
        private const val NOTIF_ID = 1
        const val ACTION_CONVERT = "dev.exe.kindleconverter.CONVERT"
        const val ACTION_EXIT = "dev.exe.kindleconverter.EXIT"
        const val ACTION_RESTART = "dev.exe.kindleconverter.RESTART"

        /** Ensure the server is up and drain any queued conversions. */
        fun startAndConvert(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConverterService::class.java).setAction(ACTION_CONVERT)
            )
        }

        /** Rebind the server to the latest settings port (used after a port change). */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConverterService::class.java).setAction(ACTION_RESTART)
            )
        }

        fun exit(context: Context) {
            context.startService(Intent(context, ConverterService::class.java).setAction(ACTION_EXIT))
        }
    }
}
