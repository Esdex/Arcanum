package zip.arcanum.arcanum.gallery.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
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
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

/** What the outside world is allowed to see of anything playing from a vault. */
val NEUTRAL_METADATA: MediaMetadata = MediaMetadata.Builder()
    .setTitle(SESSION_TITLE)
    .build()

/** The one title the shared MediaSession may carry. */
const val SESSION_TITLE = "Arcanum"

@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class ArcanumMediaService : MediaSessionService() {

    @Inject lateinit var engine: VeraCryptEngine
    @Inject lateinit var repo: ContainerRepository
    @Inject lateinit var prefs: AppPreferences

    /* Read on every metadata query, from a player callback, so it is a plain volatile rather
     * than something suspending. */
    @Volatile private var publishContent = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            // Tolerates files with a bogus declared frame rate (e.g. 720p @ ~1324 fps) that
            // would otherwise fail decoder init and show a black screen (#104).
            .setRenderersFactory(SafeVideoRenderersFactory(this))
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

        /*
         * The session is given a player that cannot report anything but a neutral title.
         *
         * Whatever reaches MediaSession is mirrored by Media3 to the system notification, the
         * lock screen, any NotificationListenerService and every connected controller - all of
         * it outside the app, past the PIN, past biometrics, past the calculator disguise and
         * past FLAG_SECURE, which protects this app's own window and nothing else.
         *
         * The audio player observed that rule by setting a neutral title on the MediaItem, and
         * the video player did not: it published the file's name and a frame taken from the
         * video as artwork, and both were visible on the lock screen. A rule that lives at
         * every call site is a rule that the next call site breaks, so it lives here now -
         * and this also covers what the app never sets at all, since ExoPlayer merges the
         * tags it parses out of the file itself into the metadata it reports.
         */
        mediaSession = MediaSession.Builder(this, NeutralMetadataPlayer(player) { publishContent })
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()

        serviceScope.launch { prefs.mediaSessionContent.collect { publishContent = it } }

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

    // The service is exported (required so Media3's own notification/media-button controller can
    // bind), which means any installed app can reach it. Gate connections here: only our own app
    // (this includes Media3's in-process media-notification controller, which binds under our own
    // package) and trusted system UI may connect. Every third-party MediaController is rejected —
    // an accepted controller can observe the session's MediaMetadata and hijack playback.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val isSelf   = controllerInfo.packageName == packageName
        val isSystem = controllerInfo.uid == Process.SYSTEM_UID
        return if (isSelf || isSystem) mediaSession else null
    }

    /**
     * The last thing between a vault's contents and the world outside the app.
     *
     * While "show what is playing" is off - the default - this reports [NEUTRAL_METADATA] for
     * anything, whatever the item carries and whatever tags ExoPlayer parsed out of the file
     * itself. The screens build neutral items too; this is here so that a screen added later
     * cannot leak by forgetting to.
     */
    private class NeutralMetadataPlayer(
        player: ExoPlayer,
        private val publishContent: () -> Boolean
    ) : ForwardingPlayer(player) {
        override fun getMediaMetadata(): MediaMetadata =
            if (publishContent()) super.getMediaMetadata() else NEUTRAL_METADATA
    }

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
