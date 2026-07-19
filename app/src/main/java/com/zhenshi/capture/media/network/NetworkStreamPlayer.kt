package com.zhenshi.capture.media.network

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.zhenshi.capture.R
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.util.LatencySampler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 网络源仅拉流，不做二次处理。ExoPlayer 须在主线程访问。 */
@Singleton
class NetworkStreamPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private var player: ExoPlayer? = null
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val bufferLatencySampler = LatencySampler()

    suspend fun open(source: SignalSource) = withContext(Dispatchers.Main.immediate) {
        releasePlayerNow()
        _error.value = null
        bufferLatencySampler.clear()
        val exo = buildPlayer()
        player = exo
        val mediaSource = when (source) {
            is SignalSource.RtmpUrl -> rtmpSource(source.url)
            is SignalSource.RtspUrl -> rtspSource(source.url)
            else -> error("不支持的网络源")
        }
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun playerOrNull(): ExoPlayer? = player

    fun readBufferedLatencyMs(): Long? {
        val exo = player ?: return null
        val latency = exo.totalBufferedDuration.coerceAtLeast(0L)
        if (latency > 0L) {
            bufferLatencySampler.record(latency)
        }
        return bufferLatencySampler.read()
    }

    /** [afterUiSettle]：离开页时先停再延后 release，避免卡导航。 */
    suspend fun release(afterUiSettle: Boolean = false) {
        val toRelease = withContext(Dispatchers.Main.immediate) {
            detachAndStop()
        } ?: return
        if (afterUiSettle) {
            delay(UI_SETTLE_BEFORE_RELEASE_MS)
        }
        withContext(Dispatchers.Main.immediate) {
            runCatching { toRelease.release() }
        }
    }

    /** 当前线程须已是主线程。 */
    private fun releasePlayerNow() {
        val p = detachAndStop() ?: return
        p.release()
    }

    /** 摘引用并 stop，不 release；返回待释放实例。 */
    private fun detachAndStop(): ExoPlayer? {
        val p = player ?: return null
        player = null
        bufferLatencySampler.clear()
        p.playWhenReady = false
        runCatching { p.stop() }
        return p
    }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 250,
                /* maxBufferMs = */ 1_000,
                /* bufferForPlaybackMs = */ 150,
                /* bufferForPlaybackAfterRebufferMs = */ 250,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _error.value = error.message ?: context.getString(R.string.player_error)
                    }
                })
            }
    }

    private fun buildLiveMediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(400)
                    .setMaxOffsetMs(1_200)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.05f)
                    .build(),
            )
            .build()

    private fun rtmpSource(url: String): MediaSource {
        val factory = RtmpDataSource.Factory()
        return ProgressiveMediaSource.Factory(factory).createMediaSource(buildLiveMediaItem(url))
    }

    private fun rtspSource(url: String): MediaSource =
        RtspMediaSource.Factory()
            .setTimeoutMs(5_000)
            .createMediaSource(buildLiveMediaItem(url))

    companion object {
        private const val UI_SETTLE_BEFORE_RELEASE_MS = 100L
    }
}
