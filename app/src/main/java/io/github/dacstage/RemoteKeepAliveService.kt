package io.github.dacstage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Control remoto en un teléfono: mantiene viva la conexión con el host mientras la app está abierta aunque
 * la pantalla se apague (sin esto Android congela la app en segundo plano y el host la desconecta). Su
 * notificación es la multimedia de [RemoteMedia] (lo que suena en el host, con controles). Se detiene al
 * cerrar la app (atrás o deslizándola de recientes) o con "Desconectar".
 */
class RemoteKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, basicNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_COMMAND)?.let { RemoteMedia.current?.command(it) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val CHANNEL_ID = "remote"
        internal const val NOTIFICATION_ID = 2
        /** Extra de la acción "Desconectar": la actividad se cierra y corta la conexión. */
        const val EXTRA_DISCONNECT = "io.github.dacstage.remote.DISCONNECT"
        private const val EXTRA_COMMAND = "io.github.dacstage.remote.COMMAND"
        internal const val CMD_PREVIOUS = "prev"
        internal const val CMD_TOGGLE = "toggle"
        internal const val CMD_NEXT = "next"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RemoteKeepAliveService::class.java))
            } catch (_: Exception) {
                // Sin permiso para servicio en primer plano ahora: la app sigue, solo sin mantenerse con la pantalla apagada.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteKeepAliveService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.remote_service_channel), NotificationManager.IMPORTANCE_LOW)
                        .apply {
                            setShowBadge(false)
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        },
                )
            }
        }

        /** Sin reproducción (buscando, emparejando o nada sonando): solo el estado de la conexión. */
        internal fun basicNotification(context: Context): Notification {
            ensureChannel(context)
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.role_remote))
                .setContentText(context.getString(R.string.remote_service_text))
                .setContentIntent(RemoteMedia.openAppIntent(context))
                .addAction(Notification.Action.Builder(null, context.getString(R.string.remote_service_disconnect), disconnectIntent(context)).build())
                .setOngoing(true)
                .build()
        }

        /** Botón de la notificación → comando al host (vía este servicio, no exportado). */
        internal fun commandIntent(context: Context, cmd: String): PendingIntent = PendingIntent.getService(
            context, cmd.hashCode(),
            Intent(context, RemoteKeepAliveService::class.java).putExtra(EXTRA_COMMAND, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Abre la actividad con un extra que la cierra: al terminar corta la conexión y detiene este servicio. */
        internal fun disconnectIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_DISCONNECT, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
