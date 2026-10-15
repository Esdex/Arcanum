package zip.arcanum.arcanum.gallery.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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

/** The one title the media sessions may carry. */
const val SESSION_TITLE = "Arcanum"

/** Set to true in a controller's connection hints to be handed the video session (#194). */
const val HINT_VIDEO_SESSION = "zip.arcanum.VIDEO_SESSION"

private const val SESSION_ID_AUDIO = "audio"
private const val SESSION_ID_VIDEO = "video"

/** Asks the service to lay the shuffled order out again, starting from what is playing. */
const val COMMAND_RESHUFFLE = "zip.arcanum.RESHUFFLE"

@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class ArcanumMediaService : MediaSessionService() {

    @Inject lateinit var engine: VeraCryptEngine
    @Inject lateinit var repo: ContainerRepository
    @Inject lateinit var prefs: AppPreferences

    /* Read on every metadata query, from a player callback, so it is a plain volatile rather
     * than something suspending. */
    @Volatile private var publishContent = false
    @Volatile private var resumeMusic = true

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /*
     * Two players, one for music and one for video (#194). They used to be one, shared by the
     * audio screen and the viewer, so opening a video threw the playlist away, leaving it
     * cleared the player and the notification with it, and a photo was enough to pause the
     * music. Each screen now drives its own, and which of them is heard is decided here.
     */
    private lateinit var audioPlayer: ExoPlayer
    private lateinit var videoPlayer: ExoPlayer
    private lateinit var audioSession: MediaSession
    private lateinit var videoSession: MediaSession

    /*
     * A video that starts playing takes over: the music is paused and the notification is the
     * video's. When the video is left - its queue emptied, which is what the viewer does on a
     * swipe to a photo and on the way out - the music comes back, playing again if it was the
     * video that paused it and the setting allows. Music started while a video holds the
     * screen (a headset button, say) takes over the other way.
     */
    private var videoHasPriority = false
    private var musicPausedByVideo = false

    override fun onCreate() {
        super.onCreate()
        audioPlayer = buildPlayer(C.AUDIO_CONTENT_TYPE_MUSIC)
        videoPlayer = buildPlayer(C.AUDIO_CONTENT_TYPE_MOVIE)

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
        audioSession = MediaSession.Builder(this, NeutralMetadataPlayer(audioPlayer) { publishContent })
            .setId(SESSION_ID_AUDIO)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult
                        .DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(COMMAND_RESHUFFLE, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == COMMAND_RESHUFFLE) reshuffleFromCurrent()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()
        videoSession = MediaSession.Builder(this, NeutralMetadataPlayer(videoPlayer) { publishContent })
            .setId(SESSION_ID_VIDEO)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()
        // Both up front, so the notification manager knows the music's session even when only
        // the viewer has connected since the service started.
        addSession(audioSession)
        addSession(videoSession)

        /* Decided from the state on every change, not from the change itself: stop() and an
           emptied queue leave playWhenReady true, so the next video sets it to what it already
           is and no playWhenReady change is ever reported - the second video in a row took
           nothing over, found on device. */
        videoPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                when {
                    videoPlayer.mediaItemCount == 0 -> videoLeft()
                    videoPlayer.playWhenReady       -> videoTakesOver()
                }
            }
        })
        audioPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                when {
                    playWhenReady && videoHasPriority -> musicTakesOver()
                    /* The video's audio focus request can reach the music before the listener
                       above has paused it; a pause for that reason is still the video's. */
                    !playWhenReady && videoHasPriority &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> musicPausedByVideo = true
                }
            }
        })

        serviceScope.launch { prefs.mediaSessionContent.collect { publishContent = it } }
        serviceScope.launch { prefs.videoPlayer.collect { resumeMusic = it.resumeMusic } }

        /*
         * Nothing from an unmounted vault may stay in the queue. Checking only the item that
         * happens to be playing was enough while the player was handed one track at a time;
         * with the real playlist on it (#139) the rest of the list is just as much a way back
         * into a vault the user has closed, so every item belonging to it goes.
         */
        serviceScope.launch {
            repo.mountedContainerIds.collect { mounted ->
                for (player in listOf(audioPlayer, videoPlayer)) {
                    val playingGone = player.currentMediaItem?.containerId()
                        ?.let { it !in mounted } == true
                    if (playingGone) player.stop()

                    for (i in player.mediaItemCount - 1 downTo 0) {
                        val cid = player.getMediaItemAt(i).containerId()
                        if (cid != null && cid !in mounted) player.removeMediaItem(i)
                    }
                }
            }
        }
    }

    private fun buildPlayer(contentType: Int): ExoPlayer = ExoPlayer.Builder(this)
        // Tolerates files with a bogus declared frame rate (e.g. 720p @ ~1324 fps) that
        // would otherwise fail decoder init and show a black screen (#104).
        .setRenderersFactory(SafeVideoRenderersFactory(this))
        .setMediaSourceFactory(DefaultMediaSourceFactory(ServiceEncryptedDataSourceFactory(engine, repo)))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(contentType)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private fun videoTakesOver() {
        if (videoHasPriority) return
        videoHasPriority = true
        if (audioPlayer.playWhenReady) {
            musicPausedByVideo = true
            audioPlayer.pause()
        }
    }

    private fun videoLeft() {
        if (!videoHasPriority) return
        videoHasPriority = false
        val resume = musicPausedByVideo && resumeMusic
        musicPausedByVideo = false
        if (resume) audioPlayer.play()
    }

    private fun musicTakesOver() {
        videoHasPriority = false
        musicPausedByVideo = false
        videoPlayer.pause()
    }

    /*
     * Media3 1.2 gives every session of a service the same notification, and whichever session
     * changed last is the one it shows. So a change in the session that is not in charge must
     * not reach it: the music pausing for a video would take the notification back from the
     * video, and the video pausing or emptying after it was left would take it from the music
     * - an emptied session cancels the notification outright, and that arrives after the
     * music has already been told to come back. Such a change shows the music again instead.
     * With no music at all the video's own update goes through, so its notification goes.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        when {
            session === audioSession && videoHasPriority -> return
            session === videoSession && !videoHasPriority && audioPlayer.mediaItemCount > 0 -> {
                val musicPlaying = audioPlayer.playWhenReady &&
                    (audioPlayer.playbackState == Player.STATE_READY || audioPlayer.playbackState == Player.STATE_BUFFERING)
                super.onUpdateNotification(audioSession, musicPlaying)
            }
            else -> super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    // The service is exported (required so Media3's own notification/media-button controller can
    // bind), which means any installed app can reach it. Gate connections here: only our own app
    // (this includes Media3's in-process media-notification controller, which binds under our own
    // package) and trusted system UI may connect. Every third-party MediaController is rejected —
    // an accepted controller can observe the session's MediaMetadata and hijack playback.
    // The viewer asks for the video session by a connection hint; everyone else gets the music.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val isSelf   = controllerInfo.packageName == packageName
        val isSystem = controllerInfo.uid == Process.SYSTEM_UID
        if (!isSelf && !isSystem) return null
        return if (controllerInfo.connectionHints.getBoolean(HINT_VIDEO_SESSION)) videoSession else audioSession
    }

    /**
     * Lays the shuffled order out again with the track that is playing at the front.
     *
     * Two things go wrong without it. The order is drawn once, when the queue is set, so
     * turning shuffle off and on again replays the same order - and turning it on while the
     * track that is playing happens to sit last in that order leaves nothing after it, so the
     * notification correctly, and confusingly, drops its next button.
     *
     * `setShuffleOrder` belongs to ExoPlayer rather than to a controller, which is why the
     * screen asks for it through a session command instead of doing it itself.
     */
    private fun reshuffleFromCurrent() {
        val count = audioPlayer.mediaItemCount
        if (count <= 1) return
        val current = audioPlayer.currentMediaItemIndex
        val rest = (0 until count).filter { it != current }.shuffled()
        val order = (listOf(current) + rest).toIntArray()
        audioPlayer.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    private fun MediaItem.containerId(): String? =
        localConfiguration?.uri?.getQueryParameter("cid")

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
        val playing = listOf(audioPlayer, videoPlayer).any { it.playWhenReady && it.mediaItemCount > 0 }
        if (!playing) stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        audioSession.release()
        videoSession.release()
        audioPlayer.release()
        videoPlayer.release()
        super.onDestroy()
    }
}
