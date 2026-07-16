package zip.arcanum.arcanum.gallery.service

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * A RenderersFactory whose video renderer tolerates a nonsensical declared frame rate.
 *
 * Some files carry garbage frame-rate metadata (seen in the wild: an H.264 720p file
 * reporting ~1324 fps). ExoPlayer configures MediaCodec with that rate, it exceeds every
 * decoder's capabilities, so MediaCodec.start() throws and the video shows only a black
 * screen even though the file plays fine elsewhere (#104). The frame rate is merely a decoder
 * hint - actual presentation timing comes from sample timestamps - so we clamp an implausible
 * value to a sane one, which lets the decoder start and the video play normally.
 *
 * This subclasses @UnstableApi Media3 internals; the overridden signatures track Media3
 * (pinned at 1.2.0 in the version catalog) and must be re-checked when Media3 is bumped.
 */
@UnstableApi
class SafeVideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // Arcanum bundles no decoder extensions (extension mode is OFF), so the default factory
        // builds only the primary MediaCodec video renderer - here, the frame-rate-tolerant one.
        out.add(
            FrameRateTolerantVideoRenderer(
                context,
                getCodecAdapterFactory(),
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            )
        )
    }
}

@UnstableApi
private class FrameRateTolerantVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify
) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(
            format,
            codecMimeType,
            codecMaxValues,
            codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround,
            tunnelingAudioSessionId
        )
        // Replace an implausible declared frame rate so MediaCodec.configure/start can't reject
        // the stream. Genuine high rates (120/240 fps high-speed capture) stay below the cap and
        // are left untouched; NO_VALUE (-1, unknown) never trips it either.
        if (format.frameRate > MAX_PLAUSIBLE_FRAME_RATE) {
            mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, FALLBACK_FRAME_RATE)
            // super derives KEY_OPERATING_RATE from the same bogus rate, which otherwise drives
            // the decoder clock at (e.g.) ~1324 - wasteful and, on stricter hardware, risky.
            // Bring it down to the same sane value when it was actually set.
            if (mediaFormat.containsKey(MediaFormat.KEY_OPERATING_RATE)) {
                mediaFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, FALLBACK_FRAME_RATE)
            }
        }
        return mediaFormat
    }

    private companion object {
        const val MAX_PLAUSIBLE_FRAME_RATE = 300f
        const val FALLBACK_FRAME_RATE = 30f
    }
}
