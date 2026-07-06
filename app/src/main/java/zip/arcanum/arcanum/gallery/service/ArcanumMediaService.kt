package zip.arcanum.arcanum.gallery.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.ServiceEncryptedDataSourceFactory
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class ArcanumMediaService : MediaSessionService() {

    @Inject lateinit var engine: VeraCryptEngine
    @Inject lateinit var repo: ContainerRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ServiceEncryptedDataSourceFactory(engine, repo)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivity = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }

        mediaSession = MediaSession.Builder(this, player)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()

        // Stop playback if the container holding the current track is unmounted
        serviceScope.launch {
            repo.mountedContainerIds.collect { mounted ->
                val cid = player.currentMediaItem
                    ?.localConfiguration?.uri
                    ?.getQueryParameter("cid")
                if (cid != null && cid !in mounted) {
                    player.stop()
                    player.clearMediaItems()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
