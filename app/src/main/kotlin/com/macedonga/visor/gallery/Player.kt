package com.macedonga.visor.gallery

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView

/** A video file, or the [length] bytes at the end of one: a Motion Photo's clip. */
data class ClipSource(val uri: Uri, val clipBytes: Long = 0)

/** One MediaPlayer, as Compose state: whether it is ready and playing, where it is, how big the picture is. */
class Playback internal constructor() {
    var ready by mutableStateOf(false)
        internal set
    var playing by mutableStateOf(false)
        internal set
    /** True once a frame has been drawn since the last start, so whatever stood in for the video can go. */
    var showing by mutableStateOf(false)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var size by mutableStateOf(IntSize.Zero)
        internal set

    internal var player: MediaPlayer? = null
    internal var surface: Surface? = null

    fun play(fromStart: Boolean = false) {
        val p = player ?: return
        if (!ready) return
        if (fromStart) p.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
        p.start()
        playing = true
    }

    fun pause() {
        player?.takeIf { ready }?.pause()
        playing = false
    }

    fun seek(ms: Long) {
        player?.takeIf { ready }?.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        positionMs = ms
    }
}

/**
 * A player for [source], released when it leaves. [autoplay] starts it once prepared; [loop] plays it round;
 * [onEnd] hears when it stops at the end.
 */
@Composable
fun rememberPlayback(source: ClipSource?, autoplay: Boolean, loop: Boolean = false, muted: Boolean = false, onEnd: () -> Unit = {}): Playback {
    val context = LocalContext.current
    val playback = remember(source) { Playback() }
    val ended by rememberUpdatedState(onEnd)
    DisposableEffect(source) {
        val player = source?.let { open(context, it) }
        if (player != null) {
            playback.player = player
            playback.surface?.let { runCatching { player.setSurface(it) } }
            player.isLooping = loop
            if (muted) player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                playback.ready = true
                playback.durationMs = it.duration.toLong()
                if (autoplay) playback.play()
            }
            player.setOnVideoSizeChangedListener { _, w, h -> playback.size = IntSize(w, h) }
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) playback.showing = true
                false
            }
            player.setOnCompletionListener {
                playback.playing = false
                playback.positionMs = playback.durationMs
                ended()
            }
            runCatching { player.prepareAsync() }
        }
        onDispose {
            playback.player = null
            playback.ready = false
            playback.playing = false
            runCatching { player?.release() }
        }
    }
    LaunchedEffect(playback, playback.playing) {
        while (playback.playing) {
            withFrameNanos { }
            playback.player?.let { p -> runCatching { playback.positionMs = p.currentPosition.toLong() } }
        }
    }
    return playback
}

private fun open(context: Context, source: ClipSource): MediaPlayer? = runCatching {
    val player = MediaPlayer()
    if (source.clipBytes > 0) {
        // A Motion Photo: the MP4 is the last clipBytes of the file.
        context.contentResolver.openAssetFileDescriptor(source.uri, "r")!!.use { afd ->
            val total = afd.parcelFileDescriptor.statSize
            player.setDataSource(afd.fileDescriptor, total - source.clipBytes, source.clipBytes)
        }
    } else {
        player.setDataSource(context, source.uri)
    }
    player
}.getOrNull()

/** Where [playback] draws. */
@Composable
fun PlaybackSurface(playback: Playback, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        val surface = Surface(texture)
                        playback.surface = surface
                        runCatching { playback.player?.setSurface(surface) }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        runCatching { playback.player?.setSurface(null) }
                        playback.surface?.release()
                        playback.surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )
}
