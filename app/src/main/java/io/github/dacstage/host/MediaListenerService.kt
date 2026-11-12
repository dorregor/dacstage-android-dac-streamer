package io.github.dacstage.host

import android.service.notification.NotificationListenerService

/**
 * Solo existe para que MediaSessionManager.getActiveSessions(ComponentName) nos deje ver las sesiones
 * de HiBy/UAPP. No lee ni actúa sobre notificaciones.
 */
class MediaListenerService : NotificationListenerService()
