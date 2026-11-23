package com.oleg.chromasync

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Base64
import androidx.palette.graphics.Palette
import java.io.ByteArrayOutputStream

class MediaBridgeService : NotificationListenerService() {

    companion object {
        var activeController: MediaController? = null
        var onTrackUpdate: ((title: String, artist: String, coverBase64: String, accentHex: String, isPlaying: Boolean, duration: Long, position: Long) -> Unit)? = null

        fun refreshSession(context: Context) {
            try {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(context, MediaBridgeService::class.java)
                val controllers = manager.getActiveSessions(component)
                val target = controllers.firstOrNull { it.packageName.contains("yandex.music") }
                    ?: controllers.firstOrNull()
                if (target != null) {
                    activeController = target
                    extractAndDispatch(target)
                }
            } catch (_: Exception) {
            }
        }

        fun extractAndDispatch(controller: MediaController) {
            val meta = controller.metadata
            val state = controller.playbackState
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Ожидание трека..."
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Запустите Яндекс Музыку"
            val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = state?.position ?: 0L
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING

            val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)

            var coverBase64 = ""
            var accentHex = "#d4b46a"

            if (bmp != null) {
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                coverBase64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                val palette = Palette.from(bmp).generate()
                val rawColor = palette.getVibrantColor(palette.getMutedColor(0xFFD4B46A.toInt()))
                accentHex = String.format("#%06X", 0xFFFFFF and rawColor)
            }

            onTrackUpdate?.invoke(title, artist, coverBase64, accentHex, isPlaying, duration, position)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshSession(this)
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        refreshSession(this)
    }
}
