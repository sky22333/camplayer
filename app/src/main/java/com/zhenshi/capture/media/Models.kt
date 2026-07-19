package com.zhenshi.capture.media

sealed interface SignalSource {
    data class UsbDevice(
        val deviceId: Int,
        val name: String,
        val vendorId: Int = 0,
        val productId: Int = 0,
    ) : SignalSource

    data class RtmpUrl(val url: String) : SignalSource
    data class RtspUrl(val url: String) : SignalSource
}

data class VideoProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
) {
    val label: String get() = "${width}×${height} · ${fps} 帧/秒"

    companion object {
        const val DEFAULT_FPS = 30
        const val HIGH_FPS = 60
        val STANDARD_FPS_OPTIONS = listOf(DEFAULT_FPS, HIGH_FPS)
        val DEFAULT_PREVIEW = VideoProfile(1280, 720, DEFAULT_FPS)

        fun fromResolutions(sizes: Iterable<Pair<Int, Int>>): List<VideoProfile> =
            sizes
                .flatMap { (width, height) ->
                    STANDARD_FPS_OPTIONS.map { fps -> VideoProfile(width, height, fps) }
                }
                .distinctBy { "${it.width}x${it.height}@${it.fps}" }
                .sortedWith(
                    compareByDescending<VideoProfile> { it.width * it.height }
                        .thenBy { if (it.fps == DEFAULT_FPS) 0 else 1 },
                )

        fun openFallbacks(): List<VideoProfile> = listOf(
            DEFAULT_PREVIEW,
            VideoProfile(1920, 1080, DEFAULT_FPS),
            VideoProfile(640, 480, DEFAULT_FPS),
        )
    }
}

enum class BitratePreset(val bitrateBps: Int) {
    LOW(1_500_000),
    MID(3_500_000),
    HIGH(6_000_000),
    ;

    companion object {
        /** 按分辨率给出码率下限。 */
        fun forResolution(width: Int, height: Int): BitratePreset {
            val pixels = width * height
            return when {
                pixels >= 1920 * 1080 -> HIGH
                pixels >= 1280 * 720 -> MID
                else -> LOW
            }
        }

        fun atLeast(user: BitratePreset, floor: BitratePreset): BitratePreset =
            if (user.bitrateBps >= floor.bitrateBps) user else floor
    }
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Ready : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class PlaybackSessionState(
    val source: SignalSource? = null,
    val profile: VideoProfile? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    /** 网络观看缓冲延迟（ms）；USB 预览不采样。 */
    val measuredLatencyMs: Long? = null,
    /** 一次性用户提示（如档位回落），展示后应调用 [PlaybackSession.consumeUserMessage]。 */
    val userMessage: String? = null,
)

data class UsbOpenResult(
    val profile: VideoProfile,
    val fallbackFrom: VideoProfile? = null,
)

data class PushSessionState(
    val activeTargetName: String? = null,
    val bitrate: BitratePreset = BitratePreset.MID,
    val connection: ConnectionState = ConnectionState.Idle,
) {
    val isStreaming: Boolean get() = connection is ConnectionState.Ready
}

fun parseNetworkSource(raw: String): SignalSource? {
    val url = raw.trim()
    if (url.isEmpty()) return null
    val lower = url.lowercase()
    return when {
        lower.startsWith("rtmp://") || lower.startsWith("rtmps://") -> SignalSource.RtmpUrl(url)
        lower.startsWith("rtsp://") || lower.startsWith("rtsps://") -> SignalSource.RtspUrl(url)
        else -> null
    }
}
